# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Fabric mod for Minecraft 1.21.10 (Java 21, Mojang official mappings) that adds a single mob: the
Ore Lizard — a rare, invisible-while-dormant cave critter that erupts from the floor when a player
walks near, flees, then burrows back down. GeckoLib **5.3-alpha-3** drives its model/animations.

> **This branch is experimental.** 5.3-alpha-3 is the *only* GeckoLib build published for Fabric
> 1.21.10, and it is an alpha: its renderer API sits between the 5.1 (1.21.5) and 5.4 (1.21.11)
> releases and has at least one outright bug this mod had to route around (see *Variants and
> rendering*). Nothing here has been rendered in a client. Treat the branch as a compile-and-boot
> proof, not a release, until someone has watched a lizard erupt on it.

This branch (`1.21.10`) is a port of the 1.20.1 original on `main`, built on top of the `1.21.11` port
branch (itself on top of `1.21.5`, `1.21.4` and `1.21.1`); behaviour is meant to be identical, and the
`## 1.2.0+mc1.21.10`, `## 1.2.0+mc1.21.11`, `## 1.2.0+mc1.21.5`, `## 1.2.0+mc1.21.4` and
`## 1.2.0+mc1.21.1` sections of [CHANGELOG.md](CHANGELOG.md) together list every place the port had
to differ and why. When in doubt about *what the mob should do*, `main` is the source of truth.

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
`OreLizardModel.addAdditionalStateData` (deepslate) and `OreTintLayer.addRenderData` (tint colour).
Invisibility needs no ticket of ours: vanilla's `EntityRenderState.isInvisible` is
`Entity.isInvisible()` at extraction time, and the layer reads that. A new piece of per-lizard
rendering data needs both hops.

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
enderman/spider eye overlay type — on 1.21.10 still a static factory on
`net.minecraft.client.renderer.RenderType`, pipeline `RenderPipelines.EYES`; coincidental name clash
with our own `eyes` bone): fullbright and additive in vanilla, and mapped to `gbuffers_spidereyes` by
Iris/OptiFine so shader packs treat it as emissive. Deliberately *not* GeckoLib's
`AutoGlowingGeoLayer`, which needs a per-skin `_glowmask` texture and a custom render pipeline that
shader packs have no convention for. The pass is skipped for invisible (dormant) lizards.

**Rendering is submission, not drawing — and render types are only ordered across collector
orders.** Since 1.21.9 an entity renderer hands geometry to a `SubmitNodeCollector`; each submission
records its render type and a *copy* of the pose, and vanilla plays everything back once all entities
are in, one buffer per render type, one collector order (`order(n)`, ascending) at a time. GeckoLib
5.3's model pass is a single `submitCustomGeometry` whose playback callback first runs the frame's
animation (`GeoModel.handleAnimations`, which poses the `GeoBone`s) and then draws the bone tree; a
layer's `submitRenderTask` hook runs right after that submission, at submission time, with the pose
stack still at the model's root. `OreTintLayer` is built around three consequences:

- The tint pass is submitted into the model's *own* render type at the default order. The collector
  keeps one list per render type in submission order, so it lands in the model's batch right after
  the model's quads — the "append to the batch in progress" the 1.20.1 layer relied on, now
  guaranteed by the collector rather than by us. It is also what makes the pose right: the
  `GeoBone`s are shared by every lizard using the model and are only posed inside the model's own
  playback callback, so the only callback guaranteed to see *this* lizard's pose is the one replayed
  immediately after it. The tint callback therefore walks the bone tree itself (from the root, with
  `RenderUtil.prepMatrixForBone` — the same five transforms GeckoLib's `renderBone` applies), draws
  only the two glowing bones' cubes, and **captures each one's pose** as it goes.
- The glow is submitted at `order(1)`, exactly as vanilla's `EyesLayer` does, and drawn from the
  captured poses. Within one order the collector replays custom geometry per render type out of a
  `HashMap` — no ordering between types — and `MultiBufferSource.BufferSource` still has a single
  shared builder for every type without a dedicated buffer, which on 1.21.10 *includes the entity
  cutout types* (only the glint types and the water mask are dedicated), and draws it the moment a
  different shared type is requested. Filed at order 0 next to the body, the glow could be drawn
  first and painted over by the body (it writes no depth). Orders replay in sequence, each in full,
  so an order-1 glow is buffered only after the body has been written — and by then the shared bones
  hold whichever lizard was drawn *last*, which is why it cannot just walk the tree again and why the
  tint pass captures poses for it. The old rule ("never request a different render type's buffer
  while the model's batch is being written") is the same fact seen from the drawing side; on the
  submit side it reads: **never submit an extra-pass render type at the same order as the body.**
- Nothing is submitted when `didRenderModel` is false — GeckoLib resolved no render type, i.e. the
  entity is invisible to the viewer — because a dormant lizard is meant to draw nothing at all, tint
  included.

**Don't use GeckoLib's per-bone render tasks (`addPerBoneRender`) on 5.3-alpha-3.** The 1.21.11 port
(GeckoLib 5.4) submitted both extra passes from a per-bone task; on this alpha that is broken twice
over. The tasks run at submission time, before any playback callback has animated the bones for the
frame, so the pose they are handed is the previous frame's (or another lizard's); and
`GeoBone.transformToBone`, which positions the task's pose stack, applies the bone chain child-first
(`[bone, parent, grandparent…]` in that order), which puts a nested bone — `shards` is `body → shards`,
`eyes` is `body → head → eyes` — in the wrong place. Re-running `handleAnimations` from a later
callback (GeckoLib's own `TextureLayerGeoLayer` does this via `buildRenderTask`) is not a fix either:
`beginTick` re-poses a controller whose handler returned `STOP` this frame, because `finishRenderPass`
has already cleared `nextPlaystate`. Hence the capture.

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
  while it is invisible.** The controller's handler runs from `AnimationController.prepareForRenderPass`,
  which `GeoModel.prepareForRenderPass` calls at the end of render-state extraction
  (`GeoRenderer.fillRenderState`) whether or not a render type resolves; the bones are only posed
  inside the model's playback callback (`handleAnimations` → `AnimationProcessor.tickAnimation`),
  i.e. when something is actually submitted. So a dormant lizard's controller is no longer frozen as
  it was on 4.x: it ticks and gets `PlayState.STOP`. Out of frustum or past render distance it still
  doesn't tick at all. Dormancy is still not represented as a held animation — a buried lizard draws
  nothing, so there is nothing to animate.
- **An animation does not begin on its first frame.** GeckoLib first spends the controller's
  transition ticks (`transitionLength(int)` on 5.3) blending into that frame from the model's current
  pose, and starts the animation's clock only afterwards. Any animation whose first frame is displaced
  from the rest pose — `appear` starts 0.81 blocks underground — must therefore run with a zero-tick
  transition, or the model visibly travels into position first. It also means a non-zero transition
  makes an animation finish that many ticks later than its authored length, which has to be accounted
  for against the state timer driving it. The handler sets the transition and the animation in the
  same call, which works because the handler runs at extraction and `AnimationController.beginTick`
  reads the `transitionLength` *field* at playback, after the handler has returned, when it builds
  the transition keyframes (a zero-length `AnimationPoint` evaluates straight to its end value, so
  the first frame is exact) — checked against 5.3-alpha-3's source; re-check it on any GeckoLib bump.

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
API dependency thinking the mod only uses it for biome spawns. The renderer is registered the same
way: vanilla's private `EntityRenderers.register`, opened by the same transitive wideners, which is
what Fabric API 0.138.4 deprecated its `EntityRendererRegistry` wrapper in favour of.

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
  GeckoLib 5.3-alpha-3 for 1.21.10 (like 5.4.5 for 1.21.11, 5.1.0 for 1.21.5, 4.8.5 for 1.21.4 and
  4.9.2 for 1.21.1) has no `META-INF/jars/` at all and no mclib references, so the workaround was
  dropped along with its `implementation files(...)` line.
- **GeckoLib 5.3-alpha-3 is the only GeckoLib for 1.21.10 and declares `minecraft >= 1.21.10`** in its
  own `fabric.mod.json`; Modrinth lists it for 1.21.10 only, which is why ours pins `"minecraft":
  "1.21.10"` exactly. Its API is the 5.1 package layout (`AnimationController` under
  `animatable.processing`, `PlayState` under `animation`, bones under `cache.object`) with the 5.4
  submit-model renderer hooks minus `RenderPassInfo` — see the API notes below. If GeckoLib ever
  publishes a 5.4.x for 1.21.10, the 1.21.11 branch's client code is the better starting point.
- `run/` is git-ignored. The smoke-test scaffolding there (`eula.txt`, a `server.properties` with a
  non-default port and `function-permission-level=4`, and a `world/datapacks/smoke` pack whose `#load`
  function schedules a `summon` and then `stop`) is not part of the repo. Data pack format is 88 on
  1.21.10, function folder `function`, not `functions`, and a `pack.mcmeta` declaring a format above
  81 must carry `min_format`/`max_format` (`[88, 0]`) alongside `pack_format` or the metadata fails to
  parse. A `summon` from the `#load` function into the fresh spawn area produced no entity visible to
  `@e` (the spawn chunks are not yet entity-ticking at that point); `forceload add ~ ~` first, then a
  `schedule`d summon 100 ticks later, does — a vanilla armor stand behaves the same way, so it is the
  chunk state, not the mob.
- GeckoLib 5.3-alpha-3 prints no WARN of its own on a dedicated dev server — not even the
  `Reference map 'geckolib.refmap.json'` line 5.4.5 does. If a future GeckoLib bump brings a nested
  jar back, `runClient`/`runServer` will fail with `NoClassDefFoundError` and the fix is the same
  extract-and-reference dance described on `main`.
- GeckoLib is pulled through the Modrinth maven proxy by project/version ID to sidestep its
  group-id churn — the coordinate in `gradle.properties` is opaque on purpose.
- `ore-lizards/` is a stray embedded git repo (a gitlink, no `.gitmodules`) pointing at this same
  remote. Ignore it; don't edit anything inside it.
- `orelizards.mixins.json` is wired up but has no mixins yet.
- Keep [CHANGELOG.md](CHANGELOG.md) updated — it is maintained in detail, with the reasoning behind
  each change, and is the best record of why things are the way they are.

## 1.21.10-specific API notes

Things that bit during the port (1.21.1 first, then 1.21.4, 1.21.5, 1.21.11 and finally this step
*back* from 1.21.11 to 1.21.10) and will bite again on any bump in either direction. The 1.21.10 /
GeckoLib 5.3-alpha-3 notes first — most of them are where 1.21.10 differs from 1.21.11:

- **1.21.10 is before the `Identifier` rename: it is still `ResourceLocation`**
  (`net.minecraft.resources.ResourceLocation`, `fromNamespaceAndPath`). `Level.isClientSide()` exists as
  a method (the field is public too); the code calls the method so it reads the same on both branches.
- **`RenderType` is still `net.minecraft.client.renderer.RenderType` with its static factories**
  (`RenderType.eyes`, `RenderType.entityCutoutNoCull`); the `client.renderer.rendertype` package and
  `RenderTypes` are 1.21.11. Pipelines are on `RenderPipelines` as on 1.21.11.
- **Save data is `ValueOutput`/`ValueInput`** (1.21.6+, `net.minecraft.world.level.storage`):
  `addAdditionalSaveData(ValueOutput)` with `putString`/`putBoolean`, `readAdditionalSaveData(ValueInput)`
  with `getString` → `Optional<String>` and `getBooleanOr(key, default)`. Same keys and types as the
  `CompoundTag` code wrote, so old saves load. **`SpawnEggItem(Item.Properties)` only** (1.21.6+), the
  mob set with `Item.Properties.spawnEgg(EntityType)` after `setId`.
- **The submit/collector renderer is the same as 1.21.11's.** Entity renderers implement
  `submit(S, PoseStack, SubmitNodeCollector, CameraRenderState)`; `SubmitNodeCollector extends
  OrderedSubmitNodeCollector`, `order(int)` picks the replay order, and
  `submitCustomGeometry(PoseStack, RenderType, (pose, vertexConsumer) -> …)` copies `poseStack.last()`
  at the call (`CustomFeatureRenderer.Storage.add`), filing it under `Map<RenderType, List<…>>` — a
  `HashMap` — for that order. `RenderBuffers`' fixed buffers are only the glint types and the water
  mask. `EyesLayer` submits at `order(1)`. `PoseStack.Pose.copy()` and `set(Pose)` are public.
- **GeckoLib 5.3-alpha-3's layer hooks have no `RenderPassInfo`; everything is a flat argument list:**
  `addRenderData(T, O, R, float partialTick)`, `preRender(R, PoseStack, BakedGeoModel,
  SubmitNodeCollector, CameraRenderState, int packedLight, int packedOverlay, int renderColor, boolean
  didRenderModel)`, `submitRenderTask(…same…)`, `addPerBoneRender(R, BakedGeoModel, boolean
  didRenderModel, BiConsumer<GeoBone, PerBoneRender<R>>)`, and `PerBoneRender.submitRenderTask(R,
  PoseStack, GeoBone, SubmitNodeCollector, CameraRenderState, int, int, int)`. `didRenderModel` is
  `GeoRenderer.getRenderType(...) != null`. The order inside `GeoRenderer.submitRenderTasks` is:
  `preRender` → scale/pose → `preApplyRenderLayers` (each layer's `preRender`, then `addPerBoneRender`)
  → `buildRenderTask` (the model's `submitCustomGeometry`; its callback runs `handleAnimations` and
  then `renderBone` over the top-level bones) → `applyRenderLayers` (per-bone tasks, then each layer's
  `submitRenderTask`) → `postRender` → `renderFinal` (vanilla's `EntityRenderer.submit`, nametags).
  The `poseStack` handed to `submitRenderTask` is still at the model root — the pose the model was
  submitted with (`DataTickets.MODEL_RENDER_POSE`).
- **Drawing bones by hand on 5.3.** `GeoCube` is a plain record here (no `render`); the draw primitives
  are `GeoRenderer.renderCubesOfBone(R, GeoBone, PoseStack, VertexConsumer, CameraRenderState, int
  packedLight, int packedOverlay, int color)` (honours `bone.isHidden()`, push/pops per cube) and
  `renderCube`. The per-bone transform GeckoLib's own pass applies is `RenderUtil.prepMatrixForBone`
  (translate to bone, to pivot, rotate, scale, away from pivot — `GeoEntityRenderer.renderBone` does the
  same five with matrix tracking in the middle), so a hand-rolled walk that pushes, calls it, draws,
  recurses `getChildBones()` unless `isHidingChildren()`, and pops lands exactly on the model pass.
  `BakedGeoModel.topLevelBones()`/`getBone(name)`, `GeoBone.getCubes()/getChildBones()/getName()`.
- **Per-bone tasks are stale and mis-transformed on this alpha** — see *Variants and rendering*. Don't.
- **GeckoLib packages on 5.3-alpha-3 are the 5.1 layout:** `AnimationController` and `AnimationTest`
  under `animatable.processing`, `PlayState` under `animation`, `AnimatableManager` under
  `animatable.manager`, `BakedGeoModel`/`GeoBone`/`GeoCube` under `cache.object`, layers under
  `renderer.layer` (no `builtin` sub-package), `GeoRenderState`/`GeoRenderer`/`PerBoneRender` under
  `renderer.base`. The transition setter is `transitionLength(int)` (5.4: `setTransitionTicks`).
  `GeoModel.addAdditionalStateData(T, GeoRenderState)` has two arguments (5.4 inserted a "related
  object"). `GeoEntityRenderer.withRenderLayer` exists (chainable), as on 5.4.
- **`GeoRenderState.getGeckolibData` on a key that was never added** is a `Map.get` followed by a
  `hasGeckolibData` check on the alpha — don't lean on what it returns or throws; use
  `getOrDefaultGeckolibData`, which is what the model and layer do.
- **Fabric API 0.138.4 deprecates `EntityRendererRegistry`** in favour of vanilla's
  `EntityRenderers.register(EntityType, EntityRendererProvider)`, which its transitive access wideners
  make callable. `OreLizardsModClient` calls the vanilla method; `OreLizardRenderer::new` still
  satisfies `EntityRendererProvider<OreLizardEntity>` (javac infers the intersection-bounded `R`).
- **Data pack format is 88, resource pack format 69** (from the client jar's `version.json`). The
  1.21.4-style item model definition and the baked egg texture load as they are.

Carried over from the 1.21.11 port (still true here):

- **`DataTicket.create(id, Class)` dedupes on (type, id) across every mod** — namespace the id
  (`"orelizards:tint_color"`).
- **`AnimationController` no longer takes the animatable**, and needs its type argument spelt out
  (`new AnimationController<OreLizardEntity>(name, transition, handler)`) or the diamond infers a bare
  `GeoAnimatable`. The handler gets an `AnimationTest` record — `test.animatable()`,
  `test.controller()`, `test.isMoving()`, `test.setAndContinue(…)` — and runs during render-state
  extraction. `isMoving()` reads GeckoLib's `IS_MOVING` state entry, which `GeoEntityRenderer` fills
  from `walkAnimation.speed()` against the same 0.015 threshold 4.x used.

Carried over from the 1.21.5 port:

- **GeckoLib 5 is a different renderer API.** `GeoModel.getModelResource`/`getTextureResource` take a
  `GeoRenderState` (only `getAnimationResource` still takes the entity); there is no `renderForBone`.
  `GeoEntityRenderer<T, R extends EntityRenderState & GeoRenderState>` is generic in the render state,
  and since vanilla's state classes only implement `GeoRenderState` through a runtime mixin (no
  interface injection), `OreLizardRenderer` and `OreTintLayer` keep `R` as a type parameter with that
  bound, as GeckoLib's own `DirectionalProjectileRenderer` does.
- **`PickaxeItem` is gone** (every `DiggerItem` subclass is): a pickaxe is `stack.is(ItemTags.PICKAXES)`.
- **No spawn egg tint layers.** `template_spawn_egg`, `spawn_egg.png` and `spawn_egg_overlay.png` no
  longer exist in the client jar; see *Variants and rendering* for how ours is produced.

Carried over from the 1.21.4 port:

- **`Entity.hurt` is final.** Override `hurtServer(ServerLevel, DamageSource, float)` instead — it is
  the server half of the dispatcher and the only place damage logic runs. `hurtClient` exists but is
  not ours to touch.
- **`Tier`/`Tiers` are gone.** "Iron or better" is
  `stack.isCorrectToolForDrops(Blocks.DIAMOND_ORE.defaultBlockState())`, which reads the stack's `TOOL`
  component.
- **`SpawnEggItem` takes no colours** (and, since 1.21.6, no entity type either — see above) — and
  every `Item.Properties` needs `.setId(ResourceKey<Item>)` or the `Item` constructor throws. Colours
  are baked into the egg texture (see *Variants and rendering*). `EntityType.Builder.build` likewise
  takes the `ResourceKey<EntityType<?>>`, not a string.
- **`MobSpawnType` is `EntitySpawnReason`** (in `canSpawn`, `finalizeSpawn` and
  `SpawnPlacements.SpawnPredicate`). `spawnAtLocation` needs the `ServerLevel`, which
  `dropCustomDeathLoot` already receives.
- **The renderer's own packed-ARGB `renderColor` is available to layers** (a trailing hook argument
  on 4.8.5–5.3; `RenderPassInfo.renderColor()` on 5.4). `OreTintLayer` ignores it on purpose (the
  variant tint is meant to multiply the texture as written), which also means a spectator's
  translucent ghost of a dormant lizard gets opaque tinted bones — unverified in a client, see the
  changelog.

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
- **GeckoLib 4.5+ takes colours as one packed ARGB `int`**, not four floats — the same form
  `VertexConsumer.setColor(int)` eats (`renderCubesOfBone`'s last argument on 5.3). Forget the alpha
  byte and the bone renders fully transparent. `OreTintLayer` has `opaque()`/`scaleRgb()` helpers for
  this; use them rather than hand-packing.
- **GeckoLib's `core` package is gone.** `AnimatableInstanceCache` is under
  `software.bernie.geckolib.animatable.instance`; `RawAnimation` is directly under
  `software.bernie.geckolib.animation` (see the 1.21.10 notes for where `AnimationController`,
  `PlayState` and `AnimatableManager` live).
- **`new ResourceLocation(ns, path)` is private** — use `fromNamespaceAndPath`.
