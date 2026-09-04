# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Fabric mod for Minecraft 1.21.5 (Java 21, Mojang official mappings) that adds a single mob: the
Ore Lizard — a rare, invisible-while-dormant cave critter that erupts from the floor when a player
walks near, flees, then burrows back down. GeckoLib 5.1.0 drives its model/animations.

This branch (`1.21.5`) is a port of the 1.20.1 original on `main`, built on top of the `1.21.4` port
branch (itself on top of `1.21.1`); behaviour is meant to be identical, and the `## 1.2.0+mc1.21.5`,
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

**…and on this version everything the *renderer* needs must then also go through the render state.**
GeckoLib 5 draws from an `EntityRenderState` snapshot, not the entity: `GeoModel` picks the texture
from it, layer hooks receive it instead of the entity, and there is no entity to read from inside a
render hook. Two hops, then: entity data is synced to the client (above), and the client copies what
the renderer needs into the render state as a `DataTicket` during extraction —
`OreLizardModel.addAdditionalStateData` (deepslate) and `OreTintLayer.addRenderData` (tint colour,
invisibility). A new piece of per-lizard rendering data needs both.

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

The spawn egg's two colours are **baked into a texture**, not in Java or JSON. 1.21.4 moved spawn egg
tinting into item model definitions; 1.21.5 then gave every vanilla egg its own texture and deleted
the shared `spawn_egg`/`spawn_egg_overlay` layers and the `template_spawn_egg` model, so there is
nothing left to tint.
[textures/item/ore_lizard_spawn_egg.png](src/main/resources/assets/orelizards/textures/item/ore_lizard_spawn_egg.png)
is the 1.21.4 layer pair with `#6E6E6E` (body) and `#63E1FF` (spots) multiplied in pixel by pixel —
if the colours ever change, regenerate it from those two vanilla textures (a 1.21.4 client jar has
them) rather than hand-painting. `models/item/ore_lizard_spawn_egg.json` is a plain `item/generated`
on that texture and
[items/ore_lizard_spawn_egg.json](src/main/resources/assets/orelizards/items/ore_lizard_spawn_egg.json)
a plain `minecraft:model` pointing at it. Delete or misname the definition and the egg renders as the
missing-model placeholder; the item's registry key (`Item.Properties.setId`) is what the client uses
to find it.

The `shards` and `eyes` bones get a third, emissive pass through `RenderType.eyes` (vanilla's
enderman/spider eye overlay type — coincidental name clash with our own `eyes` bone): fullbright and additive in vanilla, and mapped to `gbuffers_spidereyes` by
Iris/OptiFine so shader packs treat it as emissive. Deliberately *not* GeckoLib's
`AutoGlowingGeoLayer`, which needs a per-skin `_glowmask` texture and a custom render type that
shader packs have no convention for. The pass is skipped for invisible (dormant) lizards.

**Never request a *different* render type's buffer while the model's batch is still being written.**
Only a fixed set of render types get their own buffer in `RenderBuffers`; everything else shares one,
and `MultiBufferSource.BufferSource.getBuffer` ends the shared batch in progress the moment another
shared type is asked for (asking for the *same* type again just hands back the batch in progress).
On 1.20.1 an `eyes` request from inside the bone recursion re-typed every bone drawn after it — the
tail and legs came out fullbright and see-through. GeckoLib 5 has no per-bone hook inside the
recursion at all; the tint pass is a *per-bone render task* (`GeoRenderLayer.addPerBoneRender`),
which GeckoLib runs after the model is complete, with the bone's pose restored, and which draws into
the model's own render type. Those tasks run in hash-map order, so the one `eyes` swap still happens
in the layer's `render` (after every task), with the bone matrices copied out of the task because the
restored pose is gone again by then.

**Register per-bone tasks only when the model is actually drawn.** GeckoLib 5 runs every layer hook
for an entity that is invisible to the viewer; it merely skips the bone recursion — the only place a
task's pose gets captured — and then runs the task anyway, restoring a null pose, which NPEs inside
GeckoLib. `OreTintLayer.preRender` records whether a render type and buffer were resolved and
`addPerBoneRender` registers nothing otherwise.

### GeckoLib asset contract

Three files must agree, and mismatches fail *silently* (log spam at most):

- Animation names in `RawAnimation.begin().thenPlay("...")` must exactly match the top-level keys
  in [ore_lizard.animation.json](src/main/resources/assets/orelizards/animations/entity/ore_lizard.animation.json)
  (currently `idle`, `scuttle`, `burrow`, `appear` — bare names, no `animation.orelizard.` prefix).
- Bone names animated in the animation JSON must exist in
  [ore_lizard.geo.json](src/main/resources/assets/orelizards/geo/entity/ore_lizard.geo.json).
  Re-exporting the geometry without the animation (or vice versa) has already broken the tail once.
- Bone names in `OreTintLayer.GLOWING_BONES` must match the geo too.

After any Blockbench re-export, check all three.

Two GeckoLib timing rules this mob depends on, both learned the hard way:

- **Controllers only tick while the entity is being rendered — and on GeckoLib 5 that includes
  while it is invisible.** The controller's handler runs from `GeoModel.prepareForRenderPass` during
  render-state extraction and `handleAnimations` from `GeoEntityRenderer.actuallyRender`, and both
  run even when GeckoLib resolves no render type (invisible entity) and draws nothing. So a dormant
  lizard's controller is no longer frozen as it was on 4.x: it ticks and gets `PlayState.STOP`. Out
  of frustum or past render distance it still doesn't tick at all. Dormancy is still not represented
  as a held animation — a buried lizard draws nothing, so there is nothing to animate.
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

- **GeckoLib 5 only scans `assets/<namespace>/geckolib/models` and `assets/<namespace>/geckolib/animations`.**
  `com.geckolib.cache.GeckoLibResources` walks exactly those two roots and keys every entry by its path
  with that prefix stripped (its regex is `^(geckolib/)((animations/)|(models/))?`) along with the
  `.geo` / `.animation` / `.json` suffixes. The id passed to `getModelResource` and `getAnimationResource`
  is therefore the bare `orelizards:entity/ore_lizard` — no folder prefix, no extension, and the same
  string for both. The GeckoLib 4 layout this branch was ported from (`assets/orelizards/geo/entity/` and
  `assets/orelizards/animations/entity/`) is never visited at all, so GeckoLib loads 0 models and 0
  animations and quietly substitutes its `geckolib:internal/missingno` placeholder — in game the lizard is
  a single flat magenta-and-black quad with no geometry and no animation, which reads as a broken model
  rather than a missing file. Textures are exempt: they are ordinary Minecraft assets resolved by full
  `ResourceLocation` and stay under `textures/entity/`. The scan roots are identical in GeckoLib 5.1.0,
  5.4.5 and 5.5.4.
- There is no `libs/` directory on this branch. The 1.20.1 original carries `libs/mclib-20.jar`
  because GeckoLib 4.8.4 ships mclib jar-in-jar and Loom's dev-launch classpath misses nested jars.
  GeckoLib 5.1.0 for 1.21.5 (like 4.8.5 for 1.21.4 and 4.9.2 for 1.21.1) has no `META-INF/jars/` at
  all and no mclib references, so the workaround was dropped along with its `implementation files(...)`
  line.
- GeckoLib 5.1.0 logs one `@Mixin target net.minecraft.client.renderer.entity.EntityRenderer was not
  found` WARN on a dedicated server (its `EntityRendererMixin` is listed under `mixins`, not `client`).
  Harmless and not ours; don't chase it. If a
  future GeckoLib bump brings a nested jar back, `runClient`/`runServer` will fail with
  `NoClassDefFoundError` and the fix is the same extract-and-reference dance described on `main`.
- GeckoLib is pulled through the Modrinth maven proxy by project/version ID to sidestep its
  group-id churn — the coordinate in `gradle.properties` is opaque on purpose.
- `ore-lizards/` is a stray embedded git repo (a gitlink, no `.gitmodules`) pointing at this same
  remote. Ignore it; don't edit anything inside it.
- `orelizards.mixins.json` is wired up but has no mixins yet.
- Keep [CHANGELOG.md](CHANGELOG.md) updated — it is maintained in detail, with the reasoning behind
  each change, and is the best record of why things are the way they are.

## 1.21.5-specific API notes

Things that bit during the port (1.21.1 first, then 1.21.4, then 1.21.5 on top) and will bite again
on any further bump. The 1.21.5 / GeckoLib 5 additions first:

- **GeckoLib 5 is a different renderer API.** `GeoModel.getModelResource`/`getTextureResource` take a
  `GeoRenderState` (only `getAnimationResource` still takes the entity). `GeoRenderLayer<T, O, R>`'s
  hooks are `addRenderData(T, O, R)`, `preRender(R, …)`, `addPerBoneRender(R, BakedGeoModel,
  BiConsumer<GeoBone, PerBoneRender<R>>)` and `render(R, …)` — there is no `renderForBone`.
  `renderCubesOfBone` is `(R, GeoBone, PoseStack, VertexConsumer, int, int, int)`: render state first,
  pose stack *after* the bone. `GeoEntityRenderer<T, R extends EntityRenderState & GeoRenderState>` is
  generic in the render state, and since vanilla's state classes only implement `GeoRenderState`
  through a runtime mixin (no interface injection), `OreLizardRenderer` and `OreTintLayer` keep `R` as
  a type parameter with that bound, as GeckoLib's own `DirectionalProjectileRenderer` does;
  `OreLizardRenderer::new` still satisfies `EntityRendererRegistry.register` (javac infers the
  intersection).
- **`DataTicket.create(id, Class)` dedupes on (type, id) across every mod** — namespace the id
  (`"orelizards:tint_color"`). `GeoRenderState.getGeckolibData` *throws* if the key was never added;
  use `getOrDefaultGeckolibData` anywhere the state might not have been filled by us yet.
- **`AnimationController` no longer takes the animatable**, and needs its type argument spelt out
  (`new AnimationController<OreLizardEntity>(name, transition, handler)`) or the diamond infers a bare
  `GeoAnimatable`. The handler gets an `AnimationTest` record — `test.animatable()`,
  `test.controller()`, `test.isMoving()`, `test.setAndContinue(…)` — and runs during render-state
  extraction. `RawAnimation`/`PlayState` are still under `software.bernie.geckolib.animation`;
  `AnimatableManager` moved to `animatable.manager` and `AnimationController` to
  `animatable.processing`.
- **GeckoLib's access widener is not transitive**, so what its own code may call (`PoseStack.Pose.set`)
  is not callable from ours in dev. Copy the JOML matrices (`pose().set`, `normal().set`) instead, as
  `OreTintLayer` does.
- **`PickaxeItem` is gone** (every `DiggerItem` subclass is): a pickaxe is `stack.is(ItemTags.PICKAXES)`.
- **`CompoundTag.getString`/`getBoolean` return `Optional`s**, `getStringOr`/`getBooleanOr` take a
  default, and `contains(key, type)` is gone (plain `contains(key)` remains).
  `readAdditionalSaveData`/`addAdditionalSaveData` still take a `CompoundTag` on 1.21.5 (1.21.6 moves
  them to `ValueInput`/`ValueOutput`).
- **No spawn egg tint layers.** `template_spawn_egg`, `spawn_egg.png` and `spawn_egg_overlay.png` no
  longer exist in the client jar; see *Variants and rendering* for how ours is produced.

Carried over from the 1.21.4 port:

- **`Entity.hurt` is final.** Override `hurtServer(ServerLevel, DamageSource, float)` instead — it is
  the server half of the dispatcher and the only place damage logic runs. `hurtClient` exists but is
  not ours to touch.
- **`Tier`/`Tiers` are gone.** "Iron or better" is
  `stack.isCorrectToolForDrops(Blocks.DIAMOND_ORE.defaultBlockState())`, which reads the stack's `TOOL`
  component.
- **`SpawnEggItem(EntityType, Item.Properties)` — no colours** — and every `Item.Properties` needs
  `.setId(ResourceKey<Item>)` or the `Item` constructor throws. Colours go in the item model definition
  (see *Variants and rendering*). `EntityType.Builder.build` likewise takes the
  `ResourceKey<EntityType<?>>`, not a string.
- **`MobSpawnType` is `EntitySpawnReason`** (in `canSpawn`, `finalizeSpawn` and
  `SpawnPlacements.SpawnPredicate`). `spawnAtLocation` needs the `ServerLevel`, which
  `dropCustomDeathLoot` already receives.
- **Every layer hook carries a trailing `int renderColor`** — the renderer's own packed ARGB.
  `OreTintLayer` ignores it on purpose (the variant tint is meant to multiply the texture as written).
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
  `software.bernie.geckolib.animatable.instance`; `RawAnimation` and `PlayState` are directly under
  `software.bernie.geckolib.animation` (see the 1.21.5 notes for where `AnimationController` and
  `AnimatableManager` moved again).
- **`new ResourceLocation(ns, path)` is private** — use `ResourceLocation.fromNamespaceAndPath`.
