# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Fabric mod for Minecraft 1.20.4 (Java 17, Mojang official mappings) that adds a single mob: the
Ore Lizard — a rare, invisible-while-dormant cave critter that erupts from the floor when a player
walks near, flees, then burrows back down. GeckoLib 4.4.4 drives its model/animations.

## Commands

```bash
./gradlew build          # compile + remap; jar lands in build/libs/orelizards-<version>.jar
./gradlew runClient      # launch a dev client (world data under run/)
./gradlew runServer      # launch a dev server
./gradlew genSources     # decompile Minecraft for navigating vanilla code
```

There is no test source set and no linter configured — verification is done by running the client
and playing. Use `/summon orelizards:ore_lizard` for a raw spawn, or the spawn egg (Spawn Eggs
creative tab) when you need `finalizeSpawn` to run (variant assignment, deepslate attribution,
dormancy). `/summon` skips `finalizeSpawn`, so summoned lizards are *not* representative.

Dependency versions live in [gradle.properties](gradle.properties), not `build.gradle`.

## Architecture

Split source sets via Loom's `splitEnvironmentSourceSets()`:

- [src/main/java](src/main/java) — common/server. Anything in here must not touch client classes.
- [src/client/java](src/client/java) — rendering only. Registered from
  [OreLizardsModClient.java](src/client/java/com/orelizards/OreLizardsModClient.java).

Both source sets are declared as one mod (`mods { orelizards { ... } }`) so they share a classpath
at runtime.

### The state machine

[OreLizardEntity](src/main/java/com/orelizards/entity/OreLizardEntity.java) is the whole mob.
`State` (BURIED → ERUPTING → FLEEING → DIGGING_DOWN → discard) is advanced by a `switch` in
`tick()` that returns early on the client, with per-state tick methods and a `stateTimer` countdown.
[FleeAndBurrowGoal](src/main/java/com/orelizards/entity/ai/FleeAndBurrowGoal.java) only does
pathing; it reads `isFleeing()`/`getFleeTarget()` and never mutates state. Where it flees *to* is
its own deterministic sweep: 16 directions, outermost ring inwards, first standable column per
direction, furthest from the player wins.

Two constraints that shaped this and are easy to trip over again:

- **`DefaultRandomPos.getPosAway` cannot express a destination preference.** It draws ten *random*
  samples and discards any failing its pathability filters, so what survives is dictated by terrain,
  not by whatever you tried to rank on. It was tried for a darkness preference and could not deliver
  one at any weighting. It remains only as a fallback for when the sweep finds nothing reachable.
- **Don't aim further than about 12 blocks.** A mob's A* only expands nodes within `FOLLOW_RANGE`
  (16, Manhattan) and stops after `FOLLOW_RANGE * 16` = 256 nodes, so a more distant target burns the
  whole node budget and yields a partial path anyway. Range comes from repathing often, not from a
  bigger horizon.

**Everything the client needs must go through `SynchedEntityData`.** `STATE`, `ORE_VARIANT`, and
`DEEPSLATE` are all synced tracked data for this reason: GeckoLib's `AnimationController` runs on
the client's copy of the entity, so a plain field mutated inside the server-gated half of `tick()`
is invisible to animations. This was a real bug (burrow animation never played) — don't reintroduce
it by adding an unsynced field and reading it from a controller or renderer.

**Synced is not saved.** `SynchedEntityData` goes over the network, not to disk, so anything decided
once at spawn also needs writing in `addAdditionalSaveData`/`readAdditionalSaveData` or it reverts to
its `defineSynchedData` default on the next chunk reload. `ORE_VARIANT` and `DEEPSLATE` are persisted
for exactly this reason, by enum *name* so the save format survives reordering the enum. `STATE` is
deliberately not persisted: `fleeTarget` is a live entity reference that can't be saved, so a reload
always returns the lizard to BURIED via `becomeDormant()` rather than resuming a flee it has nothing
to flee from.

**Only an activation may leave BURIED.** `beginErupting` and `beginFleeing` both take the entity
being fled from as a parameter, which is what makes "activated" and "has a flee target" the same
condition by construction. Keep it that way — a FLEEING lizard without a target is one
`FleeAndBurrowGoal` can't path for, which strands it visible and motionless until its timer runs out
and then deletes it. Both `tickErupting` and `tickFleeing` re-check the target each tick and burrow
early if it's gone.

`tick()` also drives a `firework`-particle spark trail (`emitSparkTrail`) so a fleeing lizard stays
trackable in an unlit cave. Like the emissive glow, it is gated on the state machine and never runs
while BURIED — anything that reveals a dormant lizard defeats the mob's core mechanic.

Several vanilla behaviours are deliberately overridden to support the "embedded in the floor"
illusion, each with a comment explaining why: `updateInvisibilityStatus` (vanilla re-derives the
invisible flag from potion effects every tick and would wipe our dormancy), `isInWall`
(suffocation exemption except while FLEEING), `isPushable`, and `checkDespawn` (interval-gated,
never despawns while the nearest player is still underground; dormant lizards are additionally safe
within 128 blocks of any player and removed outright beyond it, and an activated one never despawns
at all since it discards itself at the end of DIGGING_DOWN).

### Variants and rendering

[OreVariant](src/main/java/com/orelizards/entity/OreVariant.java) is the source of truth for tint
color, drop item, deepslate spawn weight, and drop-count tier. One shared texture pair (stone / deepslate) covers
every ore type; [OreTintLayer](src/client/java/com/orelizards/client/OreTintLayer.java) re-draws
only the `shards` and `eyes` bones with the variant color on top of the base pass. Adding a variant
means adding an enum constant — no new textures, no renderer changes. Drop counts come from the
nested `DropTier` (bulk ores 4-6; gold/diamond/emerald 2-4 with a 2% roll for 6), so a new variant
just names its tier.

The `shards` and `eyes` bones get a third, emissive pass through `RenderType.eyes` (vanilla's
enderman/spider eye overlay type — coincidental name clash with our own `eyes` bone): fullbright and additive in vanilla, and mapped to `gbuffers_spidereyes` by
Iris/OptiFine so shader packs treat it as emissive. Deliberately *not* GeckoLib's
`AutoGlowingGeoLayer`, which needs a per-skin `_glowmask` texture and a custom render type that
shader packs have no convention for. The pass is skipped for invisible (dormant) lizards.

**Never call `bufferSource.getBuffer(...)` for a new render type from inside `renderForBone`.**
Only a fixed set of render types get their own `BufferBuilder` in `RenderBuffers`; everything else
shares one. Asking for a second type partway through the bone recursion ends the in-progress batch
and re-begins the shared builder under the new type, so every bone drawn *after* that one inherits
it — which showed up as the lizard's tail and legs rendering fullbright and see-through. Do the
swap from a layer's `render` instead, which `defaultRender` invokes after `actuallyRender` has
written the whole model. Bone matrices have to be carried across from `renderForBone` to get there:
`GeoEntityRenderer.actuallyRender` pushes the entity rotation and model transforms and pops them
before layers run, keeping the model-space matrix in a private field.

### GeckoLib asset contract

Three files must agree, and mismatches fail *silently* (log spam at most):

- Animation names in `RawAnimation.begin().thenPlay("...")` must exactly match the top-level keys
  in [ore_lizard.animation.json](src/main/resources/assets/orelizards/animations/entity/ore_lizard.animation.json)
  (currently `idle`, `scuttle`, `burrow`, `appear` — bare names, no `animation.orelizard.` prefix).
- Bone names animated in the animation JSON must exist in
  [ore_lizard.geo.json](src/main/resources/assets/orelizards/geo/entity/ore_lizard.geo.json).
  Re-exporting the geometry without the animation (or vice versa) has already broken the tail once.
- Bone names in `OreTintLayer.TINTED_BONES` must match the geo too.

After any Blockbench re-export, check all three.

Two GeckoLib timing rules this mob depends on, both learned the hard way:

- **Controllers only tick while the entity is being rendered.** `handleAnimations` is called from
  `GeoEntityRenderer.preRender`, which `defaultRender` skips when the render type is null — which is
  what happens for an invisible entity. A dormant lizard is `setInvisible(true)`, so its controller
  is frozen at the bind pose the whole time it is buried. That rules out representing dormancy as a
  held "buried" animation: it would never be processed.
- **An animation does not begin on its first frame.** GeckoLib first spends `transitionLength` ticks
  blending into that frame from the model's current pose, and starts the animation's clock only
  afterwards. Any animation whose first frame is displaced from the rest pose — `appear` starts 0.81
  blocks underground — must therefore run with a zero-tick transition, or the model visibly travels
  into position first. It also means a non-zero transition makes an animation finish
  `transitionLength` ticks later than its authored length, which has to be accounted for against the
  state timer driving it.

## Spawning

Registered in [OreLizardsMod](src/main/java/com/orelizards/OreLizardsMod.java) as `MobCategory.AMBIENT`
(not `CREATURE` — `CREATURE`'s population cap is shared with all surface animals and is effectively
always full underground, so the mob would never get a spawn attempt). Weight 1, plus a 30% rejection
roll inside `canSpawn` because spawn weights are integers and 1 is the floor.

Spawn rules in `canSpawn`: `Y < 50`, at least 8 blocks below the `WORLD_SURFACE` heightmap, on
`BASE_STONE_OVERWORLD`. Depth-below-surface is used rather than a light check because it works
during worldgen before lighting exists and ignores player torches. Stone vs. deepslate is decided
by `Y < -4`, not by sampling blocks. That threshold is carried over from 1.20.1 unchanged, for
behavioural parity. Note that vanilla's `deepslate` surface rule is `verticalGradient(0, 8)` on both
versions - all deepslate at Y ≤ 0, all stone at Y ≥ 8 - so the Y=-8..0 band the source comments
describe is off by eight, and -4 sits below the real blend rather than in the middle of it.

## Repo gotchas

- `libs/mclib-20.jar` is checked in as a workaround: GeckoLib ships it jar-in-jar, but Loom 1.17's
  dev-launch classpath doesn't pick the nested jar up, so `runClient` fails with
  `NoClassDefFoundError` on `software.bernie.geckolib.util.JsonUtil` without it.
- GeckoLib is pulled through the Modrinth maven proxy by project/version ID to sidestep its
  group-id churn — the coordinate in `gradle.properties` is opaque on purpose.
- `ore-lizards/` is a stray embedded git repo (a gitlink, no `.gitmodules`) pointing at this same
  remote. Ignore it; don't edit anything inside it.
- `orelizards.mixins.json` is wired up but has no mixins yet.
- Keep [CHANGELOG.md](CHANGELOG.md) updated — it is maintained in detail, with the reasoning behind
  each change, and is the best record of why things are the way they are.
- This branch is the **1.20.4 port**. GeckoLib 4.4.4 is *older* than main's 4.8.4, but every call the
  mod makes has the same signature there, and the vanilla API needed no changes at all. The breaks
  all land in 1.20.5/1.21: `defineSynchedData(SynchedEntityData.Builder)`, `finalizeSpawn` losing
  its `CompoundTag`, `setMaxUpStep` → the `STEP_HEIGHT` attribute, `AttributeModifier` keyed by
  `ResourceLocation`, `isPathfindable(PathComputationType)` only, the `ResourceLocation` constructor
  going private, and on the GeckoLib side the `core` package being flattened and
  `renderCubesOfBone` taking a packed int colour.
- The `FabricEntityTypeBuilder` deprecation note from `compileJava` is not new here: it is deprecated
  in main's Fabric API 0.92.11 as well, so it was left alone for parity. The replacement, when a
  version forces it, is vanilla `EntityType.Builder.of(...).sized(0.9F, 0.6F).clientTrackingRange(8)`
  plus Fabric's injected no-key `build()` - which is `build(null)`, so it goes through vanilla's
  datafixer choice-type lookup that the old builder skipped.
- `runServer`'s default port 25565 may already be taken by another dev server on the machine. Loom
  honours a pre-created `run/server.properties`, so set `server-port` there before a headless run
  rather than waiting for the bind failure.
