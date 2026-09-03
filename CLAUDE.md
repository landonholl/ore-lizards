# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Fabric mod for Minecraft 26.1.2 (Java 25, Mojang's official names - 26.x ships unobfuscated) that adds
a single mob: the Ore Lizard — a rare, invisible-while-dormant cave critter that erupts from the floor
when a player walks near, flees, then burrows back down. GeckoLib 5.5.2 drives its model/animations.

This branch (`26.1.2`) is a port of the 1.20.1 original on `main`, built on top of the `26.2` port
branch (itself on top of `1.21.11`, `1.21.5`, `1.21.4` and `1.21.1`; there is no branch for 26.1.0 or
26.1.1); behaviour is meant to be identical, and the `## 1.2.0+mc26.1.2`, `## 1.2.0+mc26.2`,
`## 1.2.0+mc1.21.11`, `## 1.2.0+mc1.21.5`, `## 1.2.0+mc1.21.4` and `## 1.2.0+mc1.21.1` sections of
[CHANGELOG.md](CHANGELOG.md) together list every place the port had to differ and why. The Java sources
are identical to `26.2` except for `OreLizardsModClient` (vanilla `EntityRenderers.register`) and the
comments. When in doubt about *what the mob should do*, `main` is the source of truth.

## Commands

```bash
./gradlew build          # compile; jar lands in build/libs/orelizards-<version>.jar (no remap step on 26.x)
./gradlew runClient      # launch a dev client (world data under run/)
./gradlew runServer      # launch a dev server
./gradlew genSources     # decompile Minecraft for navigating vanilla code
```

There is no test source set and no linter configured — verification is done by running the client
and playing. Use `/summon orelizards:ore_lizard` for a raw spawn, or the spawn egg (Spawn Eggs
creative tab) when you need `finalizeSpawn` to run (variant assignment, deepslate attribution,
dormancy). `/summon` skips `finalizeSpawn`, so summoned lizards are *not* representative.

Dependency versions live in [gradle.properties](gradle.properties), not `build.gradle`.

The build needs a JDK 25 (`java { toolchain }` in `build.gradle`); Gradle finds the one it has
auto-provisioned under `~/.gradle/jdks` even when the daemon runs on an older JVM. For API archaeology,
`javap -p -cp <jar>` works directly on the Minecraft jars Loom caches at
`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-clientonly-deobf/26.1.2/` and
`minecraft-common-deobf/26.1.2/` (split source sets mean two jars — put both on the classpath) and on
the GeckoLib jar from the Gradle module cache — neither is remapped, so there is no `remapped_mods` copy
to hunt for. Use the JDK 25 `javap` (`~/.gradle/jdks/eclipse_adoptium-25-*/bin/javap`): the game's class
files are version 69 and a JDK 21 `javap` refuses them. Those cache jars are *un-tweaked*: they still
show `SpawnPlacements.register`, `EntityRenderers.register` and `CreativeModeTabs.SPAWN_EGGS` as
private, which is what an IDE reading them reports. What `./gradlew build` actually compiles against
are the copies under the project's `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-clientOnly-<hash>/`
and `minecraft-common-<hash>/`, with Fabric's and GeckoLib's transitive class tweakers applied — check
there when the IDE and the compiler disagree.

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

The `shards` and `eyes` bones get a third, emissive pass through `RenderTypes.eyes` (vanilla's
enderman/spider eye overlay type, under `net.minecraft.client.renderer.rendertype`, pipeline
`RenderPipelines.EYES` — coincidental name clash with our own `eyes` bone): fullbright and additive in
vanilla, and mapped to `gbuffers_spidereyes` by Iris/OptiFine so shader packs treat it as emissive.
Deliberately *not* GeckoLib's `AutoGlowingGeoLayer`, which needs a per-skin `_glowmask` texture and a
custom render pipeline that shader packs have no convention for. The pass is skipped for invisible
(dormant) lizards.

**Rendering is submission, not drawing — and the only ordering the collector promises is between
collector orders and, for custom geometry, between the solid and the translucent sweep.** An entity
renderer hands geometry to a `SubmitNodeCollector`; each submission records its render type and a
*copy* of the pose under the collector order it was submitted at (`order(n)`, default 0). GeckoLib
5.5's model pass is a single `submitCustomGeometry` (in `RenderTypes.entityCutout(texture)`), and a
layer's per-bone tasks (`GeoRenderLayer.addPerBoneRender`) run at submission time with the pose stack
placed at the bone — so they submit too, and there is no bone-pose bookkeeping to do. What happens next
on 26.1.2, and why `OreTintLayer` submits **both** of its passes at `order(1)`:

- `SubmitNodeStorage` keeps one `SubmitNodeCollection` per order in an `Int2ObjectAVLTreeMap`.
  `CustomFeatureRenderer.Storage` files each `submitCustomGeometry` by `RenderType.hasBlending()` into
  a *solid* or a *translucent* `HashMap<RenderType, List<CustomGeometrySubmit>>` — the body's cutout
  type, and so the tint, go solid; `eyes` goes translucent. `FeatureRenderDispatcher.renderAllFeatures`
  runs `renderSolidFeatures` (every order ascending; within an order the model, model-part, flame,
  leash, item, block, custom-geometry and particle solids in that fixed sequence), only then
  `renderTranslucentFeatures` (every order ascending again), then translucent particles. Custom
  geometry is played back one render type at a time — `BufferSource.getBuffer(type)`, then that type's
  list in submission order — and the types within one map come out in `HashMap` order.
- `MultiBufferSource.BufferSource` is still the sink on 26.1.2: every render type without a fixed
  buffer shares one builder, and asking for a different shared type ends whatever that builder holds.
  That is what made the 1.20.1 rule ("never request a different render type's buffer while the
  model's batch is being written") matter, and it is why an extra pass filed at the body's order in a
  *different* render type has no defined position relative to the body.
- So the body stays at order 0 and both extra passes go to order 1: the tint (same render type as the
  body) is drawn during order 1's solid pass, after every order-0 solid draw, at the same depth as the
  body's quads where the later draw wins; the glow (`eyes`, blending, writes no depth) is drawn during
  the translucent sweep, after every solid draw of every order, so the body cannot paint over it.
  Vanilla's `EyesLayer` submits at order 1 on 26.1.2 as on 1.21.9+ and 26.2. A tint at order 0 would
  also have worked *here* (appended to the body's own per-type list, as on 1.21.11) but not on 26.2,
  whose collector replaces the per-type lists with phases it reserves the right to shuffle
  (`SimpleFeatureRenderPhase.maybeShuffle`; see the `26.2` branch) — order 1 is the placement both
  versions guarantee, so the layer is identical on both. The rule, on the submit side of 26.x: **never
  submit an extra pass at the body's collector order — not even in the body's own render type.**

**Register per-bone tasks only when the model is actually drawn.** `RenderPassInfo.willRender()` is
false when GeckoLib resolved no render type — an entity invisible to the viewer — and GeckoLib still
runs every layer hook, and every task registered, in that case. On GeckoLib 5.1 registering anyway
was an NPE inside GeckoLib (a never-captured pose); on 5.4+ it would merely submit a tinted pair of
bones for a lizard that is meant to draw nothing at all. `addPerBoneRender` returns early on
`!willRender()`.

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
  while it is invisible.** The controller's handler runs from `AnimationProcessor.extractControllerStates`,
  which `GeoRendererInternals.fillRenderState` calls at the end of render-state extraction whether or
  not a render type resolves; the resulting `ControllerState`s are only applied to the bones inside
  `RenderPassInfo.renderPosed`, i.e. when something is actually submitted. So a dormant lizard's
  controller is no longer frozen as it was on 4.x: it ticks and gets `PlayState.STOP`. Out of frustum
  or past render distance it still doesn't tick at all. Dormancy is still not represented as a held
  animation — a buried lizard draws nothing, so there is nothing to animate.
- **An animation does not begin on its first frame.** GeckoLib first spends the controller's
  transition ticks (`setTransitionTicks`) blending into that frame from the model's current pose, and
  starts the animation's clock only afterwards. Any animation whose first frame is displaced from the
  rest pose — `appear` starts 0.81 blocks underground — must therefore run with a zero-tick
  transition, or the model visibly travels into position first. It also means a non-zero transition
  makes an animation finish that many ticks later than its authored length, which has to be accounted
  for against the state timer driving it. The handler sets the transition and the animation in the
  same call, which only works because `AnimationController.checkControllerState` captures the
  transition field *before* the handler but `initializeNewAnimation` only uses that captured value for
  `triggerAnim` animations (`triggeredAnimTime > 0`); for a handler-set animation it reads the field
  again after the handler has returned — checked on 5.5.2 (this branch) and 5.5.4 (bytecode:
  `javap -c`), as on 5.4.5; re-check it on any GeckoLib bump.

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

Registration is `SpawnPlacements.register(...)`, which vanilla made *private* in 1.21. It compiles and
runs because Fabric API ships a transitive class tweaker that re-opens it
(`fabric-transitive-access-wideners-v1` 8.1.3 on 0.155.2; Loom applies it to the dev jar, Fabric
applies it at runtime). The same widener re-opens the private `EntityRenderers.register` that
`OreLizardsModClient` now calls directly, and the same goes for `CreativeModeTabs.SPAWN_EGGS`, which
26.x made private and Fabric's creative-tab module re-opens. Don't "fix" any of the three private calls
by hand — and don't drop the Fabric API dependency thinking the mod only uses it for biome spawns.

## Repo gotchas

- **26.x is unobfuscated, so the build is Loom's *non-remapping* plugin**: `build.gradle` applies
  `net.fabricmc.fabric-loom` (not `net.fabricmc.fabric-loom-remap`), there is no `mappings` line, and
  the loader, Fabric API and GeckoLib are plain `implementation` dependencies — the non-remapping
  plugin has no `modImplementation` and will fail configuration if you add one back. Loom still picks
  up mod metadata and transitive class tweakers from `implementation` jars (that is how the private
  vanilla members above compile). `loom_version` is still `1.17-SNAPSHOT` (resolves to 1.17.20+).
- There is no `libs/` directory on this branch. The 1.20.1 original carries `libs/mclib-20.jar`
  because GeckoLib 4.8.4 ships mclib jar-in-jar and Loom's dev-launch classpath misses nested jars.
  GeckoLib 5.5.2 for 26.1.2 (like every 4.9+/5.x build the other ports use) has no `META-INF/jars/` at
  all and no mclib references, so the workaround was dropped along with its `implementation files(...)`
  line. If a future GeckoLib bump brings a nested jar back, `runClient`/`runServer` will fail with
  `NoClassDefFoundError` and the fix is the same extract-and-reference dance described on `main`.
- `run/` is git-ignored. The smoke-test scaffolding there is not part of the repo: `eula.txt`; a
  `server.properties` with a non-default `server-port` and `function-permission-level=4`; and a
  `world/datapacks/smoke` pack whose `#load` function summons a lizard and whose `#tick` function
  counts ticks on a scoreboard and runs `stop` at 200 (data pack format 101.1 on 26.1.2 — `pack_format`
  plus `min_format`/`max_format` as `[major, minor]` arrays — function folder `function`). `#load`
  runs on the first server tick, after the spawn chunks are prepared, so `~ ~ ~` from the server's
  command source lands in a loaded chunk; `function-permission-level=4` is what lets a function run
  `stop`. Function feedback is suppressed, so the pack reports through `say`, which the dedicated server
  logs. Console commands piped into `runServer` before the level exists hit a vanilla NPE and kill the
  console reader, so a self-stopping pack is the more reliable headless test.
- GeckoLib 5.5.2's `fabric.mod.json` declares `minecraft >=26.1.2`, and ours declares the same range
  (the `26.2` branch pinned `26.2`). Only 26.1.2 has been built and smoke-tested from this branch; 26.2
  has its own branch and jar.
- GeckoLib is pulled through the Modrinth maven proxy by project/version ID to sidestep its
  group-id churn — the coordinate in `gradle.properties` is opaque on purpose. On 26.x its root
  package is `com.geckolib` (was `software.bernie.geckolib`), which is a rename of every import and
  nothing else.
- `ore-lizards/` is a stray embedded git repo (a gitlink, no `.gitmodules`) pointing at this same
  remote. Ignore it; don't edit anything inside it.
- `orelizards.mixins.json` is wired up but has no mixins yet. Its `compatibilityLevel` is `JAVA_25`,
  which the Mixin 0.17.4 fork shipped by Fabric Loader 0.19.5 accepts (GeckoLib's own config still
  says `JAVA_17`; either works for an empty mixin list).
- Keep [CHANGELOG.md](CHANGELOG.md) updated — it is maintained in detail, with the reasoning behind
  each change, and is the best record of why things are the way they are.

## 26.x-specific API notes

Things that bit during the port (1.21.1 first, then 1.21.4, 1.21.5, 1.21.11, 26.2 and 26.1.2 on top)
and will bite again on any further bump. The 26.x / GeckoLib 5.5 additions first:

- **GeckoLib's root package is `com.geckolib`.** Every class is where 5.4 had it under the new root:
  `com.geckolib.animatable.GeoEntity`, `animatable.instance.AnimatableInstanceCache`,
  `animatable.manager.AnimatableManager`, `animation.AnimationController`, `animation.RawAnimation`,
  `animation.object.PlayState`, `animation.state.AnimationTest`, `model.GeoModel`,
  `renderer.GeoEntityRenderer`, `renderer.base.{GeoRenderer,GeoRenderState,RenderPassInfo,PerBoneRender}`,
  `renderer.layer.GeoRenderLayer`, `cache.model.GeoBone`, `cache.model.cuboid.{CuboidGeoBone,GeoCube}`,
  `constant.dataticket.DataTicket`, `util.GeckoLibUtil`. Hook signatures are unchanged from 5.4, and
  identical between 5.5.2 and 5.5.4 for every class this mod touches (the one `javap` difference is the
  bound on `GeoEntityRenderer.convertRenderStateToLiving`, which we never call).
- **`GeoEntityRenderer<T, R extends EntityRenderState>` — no `GeoRenderState` bound any more**, because
  GeckoLib's class tweaker injects `GeoRenderState` into `EntityRenderState` *transitively*
  (`transitive-inject-interface`), so in a Loom dev environment `LivingEntityRenderState` *is* a
  `GeoRenderState` at compile time. `OreLizardRenderer`/`OreTintLayer` name it concretely. If a future
  GeckoLib drops the transitive injection, the compile error is on the `extends` clauses and the fix is
  the 1.21.11 shape: `R extends LivingEntityRenderState & GeoRenderState` as a type parameter.
- **`LightTexture` is gone; `FULL_BRIGHT` is `net.minecraft.util.LightCoordsUtil.FULL_BRIGHT`.** The
  client's lightmap object is `Lightmap`; the packing helpers (`pack`, `block`, `sky`) are on
  `LightCoordsUtil` too.
- **Playback on 26.1.2 is `FeatureRenderDispatcher` over `SubmitNodeStorage` → per-order
  `SubmitNodeCollection` → per-feature `Storage`s → `MultiBufferSource.BufferSource`**, with
  `CustomFeatureRenderer.Storage` holding `submitCustomGeometry` in a solid and a translucent
  `HashMap<RenderType, List>` split by `RenderType.hasBlending()`. `MultiBufferSource`/`BufferSource`
  still exist here; 26.2 removes them for render phases writing one `StagedVertexBuffer`
  (`SimpleFeatureRenderPhase`, `RenderTypeFeatureRenderer`), which changed the ordering *argument* in
  *Variants and rendering* without changing the code. On both, the
  `SubmitNodeCollector.CustomGeometryRenderer` callback is `(PoseStack.Pose, VertexConsumer)`,
  `order(int)` returns an `OrderedSubmitNodeCollector`, and `RenderTypes.eyes(Identifier)` is
  unchanged. `RenderTypes.entityCutoutNoCull` is `entityCutout(Identifier)` (GeckoLib's default render
  type; we never name it).
- **Fabric API: the item-group module is `fabric-creative-tab-api-v1`.** `ItemGroupEvents` is
  `net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents`, `modifyEntriesEvent` is
  `modifyOutputEvent(ResourceKey<CreativeModeTab>)`, and the callback receives a
  `FabricCreativeModeTabOutput` (a `CreativeModeTab.Output`, so `accept(ItemLike)` works).
  `BiomeModifications.addSpawn`, `BiomeSelectors.foundInOverworld` and
  `FabricDefaultAttributeRegistry.register` are unchanged. `EntityRendererRegistry` is `@Deprecated`
  (rendering-v1 23.3.1 in 0.155.2 and 25.3.3 in 0.159.0) and is a one-line delegate to vanilla's
  private `EntityRenderers.register`, which `fabric-transitive-access-wideners-v1` re-opens —
  `OreLizardsModClient` calls the vanilla method directly.
- **Every common-side vanilla signature is unchanged from 1.21.11**: `Mob.finalizeSpawn(ServerLevelAccessor,
  DifficultyInstance, EntitySpawnReason, SpawnGroupData)`, `LivingEntity.hurtServer(ServerLevel,
  DamageSource, float)`, `dropCustomDeathLoot(ServerLevel, DamageSource, boolean)`,
  `addAdditionalSaveData(ValueOutput)`/`readAdditionalSaveData(ValueInput)`, `SpawnEggItem(Item.Properties)`
  with `Item.Properties.spawnEgg`, `EntityType.Builder.of/sized/clientTrackingRange/build(ResourceKey)`,
  `Attributes.STEP_HEIGHT`, `Heightmap.Types.{WORLD_SURFACE,MOTION_BLOCKING}`,
  `BlockTags.BASE_STONE_OVERWORLD`, `ItemTags.PICKAXES`, `EntityGetter.getNearestPlayer` (via `Level`),
  `ParticleTypes.{FIREWORK,BLOCK}`, `BlockParticleOption(ParticleType, BlockState)`, the STONE/DEEPSLATE
  sound events, `AttributeModifier(Identifier, double, Operation)` and `Identifier.fromNamespaceAndPath`.
- **Java 25 everywhere**: game (`version.json`: `java_version: 25`), Fabric API 0.155.2 (`java >=25`,
  `minecraft ~26.1-`) and GeckoLib 5.5.2 (`java >=25`, `minecraft >=26.1.2`).
  `options.release = 25`, source/target 25, toolchain 25, `fabric.mod.json` `java >=25`, mixin
  `JAVA_25`.

Carried over from the 1.21.11 port:

- **`ResourceLocation` is `Identifier`** (`net.minecraft.resources.Identifier`, still
  `fromNamespaceAndPath`); `ResourceKey` and `Registries` are unchanged. `Level.isClientSide` is a
  private field — call `isClientSide()`.
- **Save data is `ValueOutput`/`ValueInput`** (`net.minecraft.world.level.storage`):
  `addAdditionalSaveData(ValueOutput)` with `putString`/`putBoolean`, `readAdditionalSaveData(ValueInput)`
  with `getString` → `Optional<String>` and `getBooleanOr(key, default)`. Same keys and types as the
  `CompoundTag` code wrote, so old saves load.
- **`SpawnEggItem(Item.Properties)` only.** The mob is a default data component set with
  `Item.Properties.spawnEgg(EntityType)`, chained after `setId`.
- **`RenderType` lives in `net.minecraft.client.renderer.rendertype`; the factories are on
  `RenderTypes`**, the pipelines on `RenderPipelines`. Entity renderers implement `submit(S, PoseStack,
  SubmitNodeCollector, CameraRenderState)`, not `render`; `submitCustomGeometry(PoseStack, RenderType,
  (pose, vertexConsumer) -> …)` copies `poseStack.last()` at the call and `order(int)` picks the
  replay order (see *Variants and rendering* for why that matters).
- **GeckoLib 5.4+'s layer hooks all take a `RenderPassInfo<R>`:** `addRenderData(T, O, R, float
  partialTick)`, `preRender(RenderPassInfo<R>, SubmitNodeCollector)`,
  `addPerBoneRender(RenderPassInfo<R>, BiConsumer<GeoBone, PerBoneRender<R>>)` and
  `submitRenderTask(RenderPassInfo<R>, SubmitNodeCollector)` (was `render`). `PerBoneRender` is
  `(RenderPassInfo<R>, GeoBone, SubmitNodeCollector)`. `RenderPassInfo` carries `renderState()`,
  `poseStack()`, `model()`, `packedLight()`, `packedOverlay()`, `renderColor()` and `willRender()`.
  `renderCubesOfBone` is gone (see the next note). `GeoModel.addAdditionalStateData(T, @Nullable
  Object relatedObject, GeoRenderState)` has a middle argument; `GeoEntityRenderer.withRenderLayer`
  replaces `addRenderLayer`; the built-in layers are under `renderer.layer.builtin`.
- **Drawing one bone from a per-bone task.** The task's pose stack sits *at the bone's pivot* with the
  parent chain and this frame's animation applied. In the playback callback: fresh `PoseStack`,
  `last().set(pose)`, `bone.translateAwayFromPivotPoint`, then `cube.render(…)` for each of
  `((CuboidGeoBone) bone).cubes` inside its own push/pop — `GeoCube.render` does not restore the stack;
  `CuboidGeoBone.render` is what does the push/pop. Do not call `bone.render`/`positionAndRender` from
  there: they re-apply the bone transform, and the animation snapshot is gone again by playback time
  anyway. This is GeckoLib's own `CustomBoneTextureGeoLayer` recipe.
- **`AnimationController` is `animation.AnimationController`**, `PlayState` is
  `animation.object.PlayState`, `AnimationTest` is `animation.state.AnimationTest`, and
  `transitionLength(int)` is `setTransitionTicks(int)`.
- **`GeoRenderState.getGeckolibData` returns `null`** (it threw on 5.1) for a key that was never added —
  unboxing an `Integer` from it is an NPE — so keep using `getOrDefaultGeckolibData`.
- **`PoseStack.Pose.set(Pose)` is public vanilla API**, so the matrix-copy workaround the 1.21.5 layer
  needed is moot; the general caution still applies to anything else GeckoLib widens for itself.

Carried over from the 1.21.5 port:

- **GeckoLib 5 is a different renderer API.** `GeoModel.getModelResource`/`getTextureResource` take a
  `GeoRenderState` (only `getAnimationResource` still takes the entity); there is no `renderForBone`.
  The renderer is generic in the render state — see the 26.x notes for how that is spelt now.
- **`DataTicket.create(id, Class)` dedupes on (type, id) across every mod** — namespace the id
  (`"orelizards:tint_color"`).
- **`AnimationController` no longer takes the animatable**, and needs its type argument spelt out
  (`new AnimationController<OreLizardEntity>(name, transition, handler)`) or the diamond infers a bare
  `GeoAnimatable`. The handler gets an `AnimationTest` record — `test.animatable()`,
  `test.controller()`, `test.isMoving()`, `test.setAndContinue(…)` — and runs during render-state
  extraction. `AnimatableManager` is under `animatable.manager`.
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
- **`SpawnEggItem` takes no colours** (and, since 1.21.6, no entity type either — see the 1.21.11
  notes) — and every `Item.Properties` needs `.setId(ResourceKey<Item>)` or the `Item` constructor
  throws. Colours are baked into the egg texture (see *Variants and rendering*).
  `EntityType.Builder.build` likewise takes the `ResourceKey<EntityType<?>>`, not a string.
- **`MobSpawnType` is `EntitySpawnReason`** (in `canSpawn`, `finalizeSpawn` and
  `SpawnPlacements.SpawnPredicate`). `spawnAtLocation` needs the `ServerLevel`, which
  `dropCustomDeathLoot` already receives.
- **The renderer's own packed-ARGB `renderColor` is available to layers** (`RenderPassInfo.renderColor()`).
  `OreTintLayer` ignores it on purpose (the variant tint is meant to multiply the texture as written),
  which also means a spectator's translucent ghost of a dormant lizard gets opaque tinted bones —
  unverified in a client, see the changelog.

Carried over from the 1.21.1 port:

- **Attribute modifiers are keyed by `Identifier`, not `UUID`.** `AttributeModifier(id, amount,
  operation)` — no name string — and `AttributeInstance.hasModifier/getModifier/removeModifier` take
  the id. Ours is `orelizards:flee_speed_boost`. `Operation.MULTIPLY_TOTAL` is now
  `ADD_MULTIPLIED_TOTAL` (same maths).
- **`setMaxUpStep` is gone; step height is the `Attributes.STEP_HEIGHT` attribute** (set in
  `createAttributes`). `Entity.maxUpStep()` still exists and reads the attribute.
- **`defineSynchedData` takes a `SynchedEntityData.Builder`**; define on the builder, not on
  `this.entityData`. `finalizeSpawn` lost its trailing `CompoundTag`; `dropCustomDeathLoot` is
  `(ServerLevel, DamageSource, boolean)` — the looting multiplier is gone.
- **GeckoLib 4.5+ takes colours as one packed ARGB `int`**, not four floats — the same form
  `VertexConsumer.setColor(int)` eats (`GeoCube.render`'s last argument). Forget the alpha byte and the
  bone renders fully transparent. `OreTintLayer` has `opaque()`/`scaleRgb()` helpers for this; use them
  rather than hand-packing.
- **GeckoLib's `core` package is gone.** `AnimatableInstanceCache` is under
  `animatable.instance`; `RawAnimation` is directly under `animation`.
- **`new ResourceLocation(ns, path)` is private** — use `fromNamespaceAndPath` (on `Identifier` here).
