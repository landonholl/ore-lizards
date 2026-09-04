# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Fabric mod for Minecraft 1.19.2 (Java 17, Mojang official mappings) that adds a single mob: the
Ore Lizard — a rare, invisible-while-dormant cave critter that erupts from the floor when a player
walks near, flees, then burrows back down. GeckoLib 3.1.40 drives its model/animations — the
GeckoLib **3** API generation (`software.bernie.geckolib3`), not the 4.x one `main` is written
against. This branch is the 1.19.2 port of `main`; `main` stays the source of truth for what the
mob *does*, and the `1.2.0+mc1.19.2` section of CHANGELOG.md lists every place this branch differs
and why.

## Commands

```bash
./gradlew build          # compile + remap; jar lands in build/libs/orelizards-<version>.jar
./gradlew runClient      # launch a dev client (world data under run/)
./gradlew runServer      # launch a dev server
./gradlew genSources     # decompile Minecraft for navigating vanilla code
```

There is no test source set and no linter configured — verification is done by running the client
and playing. Use `/summon orelizards:ore_lizard` for a raw spawn, or the spawn egg (Miscellaneous
creative tab — 1.19.2 has no Spawn Eggs tab) when you need `finalizeSpawn` to run (variant
assignment, deepslate attribution, dormancy). `/summon` skips `finalizeSpawn`, so summoned lizards
are *not* representative.

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

**How the passes are drawn under GeckoLib 3.** A GeckoLib 3 layer (`GeoLayerRenderer`) has no
per-bone hook; its single `render` runs after `GeoEntityRenderer.render` has drawn the model and is
expected to re-render the model itself via `getRenderer().render(model, ...)`. `OreTintLayer`
therefore hides every bone except `shards`/`eyes` (an ancestor of one stays un-hidden with only its
own cubes suppressed — `GeoEntityRenderer.renderRecursively` skips a hidden bone's *whole subtree*),
re-renders once through the body's render type with the tint and once through `RenderType.eyes`,
and restores the flags in a `finally`. The flags live on the baked `GeoModel`'s `GeoBone`s, which is
one cached object shared by every lizard on screen — never leave them flipped. Two GeckoLib 3
traps around this:

- **GeckoLib 3 runs layers even when it skipped the body.** The body pass is gated on
  `isInvisibleTo(player)`; the layer loop only on `isSpectator()`. A layer must repeat the check or a
  dormant lizard is painted back into view as a floating tinted glow.
- **Its default body render type is `entityCutout`, not GeckoLib 4's `entityCutoutNoCull`.**
  `OreLizardRenderer.getRenderType` pins NoCull for parity with `main`, and the layer asks the
  renderer for its type rather than naming one, so the tint pass always shares the body's buffer.

**Never call `bufferSource.getBuffer(...)` for a new render type from inside the bone recursion**
(`renderRecursively`/`renderCube`). Only a fixed set of render types get their own `BufferBuilder`
in `RenderBuffers`; everything else shares one. Asking for a second type partway through the bone
recursion ends the in-progress batch and re-begins the shared builder under the new type, so every
bone drawn *after* that one inherits it — which showed up as the lizard's tail and legs rendering
fullbright and see-through. Do the swap from the layer's `render`, which GeckoLib 3 invokes only
once the model pass has returned. The full re-traversal recomputes bone matrices, so nothing needs
carrying across from the model pass (unlike the GeckoLib 4 layer on `main`).

### GeckoLib asset contract

Three files must agree, and mismatches fail *silently* (log spam at most):

- Animation names in `new AnimationBuilder().addAnimation("...", loopType)` must exactly match the
  top-level keys in [ore_lizard.animation.json](src/main/resources/assets/orelizards/animations/entity/ore_lizard.animation.json)
  (currently `idle`, `scuttle`, `burrow`, `appear` — bare names, no `animation.orelizard.` prefix).
  A miss is a `System.out.printf` line, not an exception.
- Bone names animated in the animation JSON must exist in
  [ore_lizard.geo.json](src/main/resources/assets/orelizards/geo/entity/ore_lizard.geo.json).
  Re-exporting the geometry without the animation (or vice versa) has already broken the tail once.
- Bone names in `OreTintLayer.GLOWING_BONES` must match the geo too.

After any Blockbench re-export, check all three.

Three GeckoLib timing rules this mob depends on, all learned the hard way:

- **Controllers tick even while the lizard is buried.** GeckoLib 3's `GeoEntityRenderer.render`
  calls `setLivingAnimations` *before* its `isInvisibleTo` check, so a dormant lizard's controller is
  processed every frame it is in view (GeckoLib 4 on `main` skips it, which is why that branch's
  note says the opposite). It makes no visible difference — the predicate returns `PlayState.STOP`
  while BURIED, so the controller sits stopped at the bind pose — but don't rely on a buried lizard's
  controller being frozen, and don't represent dormancy as a held animation on either branch.
- **An animation does not begin on its first frame.** GeckoLib first spends `transitionLengthTicks`
  ticks blending into that frame from the model's current pose, and starts the animation's clock
  only afterwards. Any animation whose first frame is displaced from the rest pose — `appear` starts
  0.81 blocks underground — must therefore run with a zero-tick transition, or the model visibly
  travels into position first. It also means a non-zero transition makes an animation finish
  `transitionLengthTicks` ticks later than its authored length, which has to be accounted for
  against the state timer driving it. In GeckoLib 3 the transition length is a public `double`
  field on the controller, read when `setAnimation` queues the blend, so it has to be written
  *before* that call (the predicate does exactly this).
- **GeckoLib 3's `HOLD_ON_LAST_FRAME` does not hold.** The loop type exists and
  `AnimationBuilder.playAndHold` hands it out, but `AnimationController` only ever consults
  `isRepeatingAfterEnd()`, so it behaves as `PLAY_ONCE`: the controller stops at the end and
  `AnimationProcessor` eases every bone back to the bind pose over one tick — which for `burrow`
  is the lizard popping back above ground for the last ten ticks of DIGGING_DOWN.
  [HoldLastFrameAnimationController](src/main/java/com/orelizards/entity/HoldLastFrameAnimationController.java)
  fixes it by overriding `adjustTick` to pin the clock just short of the animation length while a
  hold animation is running. Use it (not a bare `AnimationController`) for any controller that
  plays a hold animation.

## Spawning

Registered in [OreLizardsMod](src/main/java/com/orelizards/OreLizardsMod.java) as `MobCategory.AMBIENT`
(not `CREATURE` — `CREATURE`'s population cap is shared with all surface animals and is effectively
always full underground, so the mob would never get a spawn attempt). Weight 1, plus a 30% rejection
roll inside `canSpawn` because spawn weights are integers and 1 is the floor.

Spawn rules in `canSpawn`: `Y < 50`, at least 8 blocks below the `WORLD_SURFACE` heightmap, on
`BASE_STONE_OVERWORLD`. Depth-below-surface is used rather than a light check because it works
during worldgen before lighting exists and ignores player torches. Stone vs. deepslate is decided
by `Y < -4` (the midpoint of 1.19.2's stone→deepslate blend band, unchanged since 1.18), not by
sampling blocks.

## Repo gotchas

- **GeckoLib ships its own example mod inside the release jar and switches it on in dev.** Its
  `fabric.mod.json` entrypoints are `software.bernie.example.GeckoLibMod` and `.ClientListener`, and
  they register whenever `FabricLoader.isDevelopmentEnvironment()` is true. One example replaces the
  vanilla creeper renderer with a `GeoReplacedEntityRenderer`, which casts `Creeper` to
  `IAnimatable`, so `runClient` dies with a `ClassCastException` the first time a creeper is drawn -
  it has nothing to do with our mod. `build.gradle` therefore sets the
  `geckolib.disable_examples` system property on both run configs. Published jars were never
  affected, since the examples don't register outside a dev environment. Not every GeckoLib build
  bundles them: 4.5.4 and every 5.x release here are clean, while 3.0.x, 3.1.40, 4.2, 4.3.1, 4.4.4
  and 4.8.4 all carry them, so check before assuming a new version needs the property.
- There is no `libs/mclib-*.jar` on this branch, unlike `main`. GeckoLib 3.1.40 does ship
  `META-INF/jars/mclib-20.jar` jar-in-jar, but none of its bytecode references that jar's
  `com.eliotlash.mclib` package — every use goes to the copy it shades in at
  `software.bernie.shadowed.eliotlash.mclib`, so the dev classpath needs nothing extra. If
  `runClient` ever fails with `NoClassDefFoundError` on `com.eliotlash...`, extract that nested jar
  into `libs/` and add it back as `implementation files(...)`, the way `main` does.
- GeckoLib 3's mod id is `geckolib3` (4.x is `geckolib`), and `fabric.mod.json` depends on that id.
  Get it wrong and Fabric Loader refuses to start with "requires any version of geckolib, which is
  missing" — the build itself will not catch it.
- GeckoLib is pulled through the Modrinth maven proxy by project/version ID to sidestep its
  group-id churn — the coordinate in `gradle.properties` is opaque on purpose. The jar in
  `~/.gradle/caches/modules-2` is intermediary-mapped (`class_4587` and friends); the readable,
  Mojang-mapped copy Loom produces is under `.gradle/loom-cache/remapped_mods/` and is the one to
  `javap` or decompile (Vineflower is already in the Gradle cache from Loom). GeckoLib 3 has no
  sources jar on Modrinth, so decompiling is the only way to read it.
- `ore-lizards/` is a stray embedded git repo (a gitlink, no `.gitmodules`) pointing at this same
  remote. Ignore it; don't edit anything inside it.
- `orelizards.mixins.json` is wired up but has no mixins yet.
- Keep [CHANGELOG.md](CHANGELOG.md) updated — it is maintained in detail, with the reasoning behind
  each change, and is the best record of why things are the way they are.
