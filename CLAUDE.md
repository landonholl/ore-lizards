# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Fabric mod for Minecraft 1.21.3 (Java 21, Mojang official mappings) that adds a single mob: the
Ore Lizard — a rare, invisible-while-dormant cave critter that erupts from the floor when a player
walks near, flees, then burrows back down. GeckoLib 4.7.1 drives its model/animations.

This branch (`1.21.3`) is a port of the 1.20.1 original on `main`, built on top of the `1.21.4` port
branch (itself built on `1.21.1`); behaviour is meant to be identical, and the `## 1.2.0+mc1.21.3`,
`## 1.2.0+mc1.21.4` and `## 1.2.0+mc1.21.1` sections of [CHANGELOG.md](CHANGELOG.md) together list
every place the port had to differ and why. When in doubt about *what the mob should do*, `main` is
the source of truth.

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

The spawn egg's two colours are constructor arguments on `SpawnEggItem`, exactly as on 1.20.1: the
client tints the two layers of `models/item/ore_lizard_spawn_egg.json` (parent `template_spawn_egg`)
through `ItemColors` → `SpawnEggItem.getColor`. There is deliberately **no**
`assets/orelizards/items/ore_lizard_spawn_egg.json` on this branch — item model definitions are a
1.21.4 addition; 1.21.3 has no `items/` folder and would silently ignore one. Going *up* to 1.21.4 is
where the colours have to leave Java for that JSON (see the `1.21.4` branch); coming down to 1.21.3 is
where they have to come back.

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
by `Y < -4` (the midpoint of the stone→deepslate blend band, unchanged since 1.18), not by sampling
blocks.

Registration is `SpawnPlacements.register(...)`, which vanilla 1.21 made *private*. It compiles and
runs because Fabric API's object-builder module ships a transitive access widener that re-opens it
(Loom applies it to the dev jar; Fabric applies it at runtime). Don't "fix" the private call by
switching to `FabricEntityTypeBuilder` — that class is deprecated here — and don't drop the Fabric
API dependency thinking the mod only uses it for biome spawns.

## Repo gotchas

- There is no `libs/` directory on this branch. The 1.20.1 original carries `libs/mclib-20.jar`
  because GeckoLib 4.8.4 ships mclib jar-in-jar and Loom's dev-launch classpath misses nested jars.
  GeckoLib 4.7.1 for 1.21.3 (like 4.8.5 for 1.21.4 and 4.9.2 for 1.21.1) has no `META-INF/jars/` at
  all and no mclib references, so the workaround was dropped along with its `implementation files(...)`
  line. If a
  future GeckoLib bump brings a nested jar back, `runClient`/`runServer` will fail with
  `NoClassDefFoundError` and the fix is the same extract-and-reference dance described on `main`.
- GeckoLib is pulled through the Modrinth maven proxy by project/version ID to sidestep its
  group-id churn — the coordinate in `gradle.properties` is opaque on purpose.
- `ore-lizards/` is a stray embedded git repo (a gitlink, no `.gitmodules`) pointing at this same
  remote. Ignore it; don't edit anything inside it.
- `orelizards.mixins.json` is wired up but has no mixins yet.
- Keep [CHANGELOG.md](CHANGELOG.md) updated — it is maintained in detail, with the reasoning behind
  each change, and is the best record of why things are the way they are.

## 1.21.3-specific API notes

Things that bit during the port (1.21.1 first, then 1.21.4 on top of it, then this branch stepping back
from 1.21.4 to 1.21.3) and will bite again on any further bump. 1.21.2 is the release that introduced
nearly all of the 1.21.x churn, so the 1.21.4 notes apply here with one exception, flagged below. The
1.21.2–1.21.3 additions first:

- **`Entity.hurt` is final.** Override `hurtServer(ServerLevel, DamageSource, float)` instead — it is
  the server half of the dispatcher and the only place damage logic runs. `hurtClient` exists but is
  not ours to touch.
- **`Tier`/`Tiers` are gone; `PickaxeItem` is not (yet).** "Iron or better" is
  `stack.isCorrectToolForDrops(Blocks.DIAMOND_ORE.defaultBlockState())`, which reads the stack's `TOOL`
  component. 1.21.5 removes `PickaxeItem` too — the `instanceof` half becomes `stack.is(ItemTags.PICKAXES)`.
- **`SpawnEggItem(EntityType, int, int, Item.Properties)` still takes its two colours here** — the one
  place 1.21.3 and 1.21.4 differ for this mod: 1.21.4 drops the ints and moves the colours into an item
  model definition, which 1.21.3 does not have (see *Variants and rendering*). Every `Item.Properties`
  does already need `.setId(ResourceKey<Item>)` or the `Item` constructor throws, and
  `EntityType.Builder.build` likewise takes the `ResourceKey<EntityType<?>>`, not a string.
- **`MobSpawnType` is `EntitySpawnReason`** (in `canSpawn`, `finalizeSpawn` and
  `SpawnPlacements.SpawnPredicate`). `spawnAtLocation` needs the `ServerLevel`, which
  `dropCustomDeathLoot` already receives.
- **GeckoLib 4.7.1 (1.21.3), like 4.8.5 (1.21.4): `GeoModel.getModelResource`/`getTextureResource`
  take `(T, GeoRenderer<T>)`**; `getAnimationResource(T)` does not. Every `GeoRenderLayer` hook
  (`preRender`, `renderForBone`, `render`) grew a trailing `int renderColor` — the renderer's own
  packed ARGB from `getRenderColor(...).argbInt()`. `OreTintLayer` ignores it on purpose (the variant
  tint is meant to multiply the texture as written). The hooks still receive the entity itself, not a
  render state, and `renderCubesOfBone(PoseStack, GeoBone, VertexConsumer, int, int, int)` is
  unchanged, so the deferred emissive pass ported as-is.
- **`PoseStack.Pose` has a `trustedNormals` flag** with no setter. `pushPose()` copies it from the
  current top, and `OreTintLayer` then overwrites `pose()`/`normal()` in place, so the emissive draw's
  normals may be re-normalised (or not) according to the wrong pose. Harmless for `RenderType.eyes`,
  which does no lighting; would matter for a lit pass.

Carried over from the 1.21.1 port:

- **Attribute modifiers are keyed by `ResourceLocation`, not `UUID`.** `AttributeModifier(id, amount,
  operation)` — no name string — and `AttributeInstance.hasModifier/getModifier/removeModifier` take
  the id. Ours is `orelizards:flee_speed_boost`. `Operation.MULTIPLY_TOTAL` is now
  `ADD_MULTIPLIED_TOTAL` (same maths).
- **`setMaxUpStep` is gone; step height is the `Attributes.STEP_HEIGHT` attribute** (set in
  `createAttributes`). `Entity.maxUpStep()` still exists and reads the attribute.
- **`defineSynchedData` takes a `SynchedEntityData.Builder`**; define on the builder, not on
  `this.entityData`. `finalizeSpawn` lost its trailing `CompoundTag`; `dropCustomDeathLoot` is
  `(ServerLevel, DamageSource, boolean)` — the looting multiplier is gone.
- **GeckoLib 4.5+'s `renderCubesOfBone` takes one packed ARGB `int`**, not four floats — the same form
  `VertexConsumer.setColor(int)` eats. Forget the alpha byte and the bone renders fully transparent.
  `OreTintLayer` has `opaque()`/`scaleRgb()` helpers for this; use them rather than hand-packing.
- **GeckoLib's `core` package is gone.** `AnimatableInstanceCache` is under
  `software.bernie.geckolib.animatable.instance`; `AnimationController`, `AnimatableManager`,
  `RawAnimation`, `PlayState`, `AnimationState` are all directly under
  `software.bernie.geckolib.animation`.
- **`new ResourceLocation(ns, path)` is private** — use `ResourceLocation.fromNamespaceAndPath`.
