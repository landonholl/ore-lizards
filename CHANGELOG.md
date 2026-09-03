# Changelog

## 1.2.0+mc26.1.2

A port of 1.2.0 to Minecraft 26.1.2 (Fabric Loader 0.19.5, Fabric API 0.155.2, GeckoLib 5.5.2, Java 25),
built on top of the 26.2 port below rather than the 1.21.11 one, since 26.2 had already absorbed the
26.x build changes (unobfuscated game, non-remapping Loom, Java 25, `com.geckolib`, `LightCoordsUtil`,
`CreativeModeTabEvents`) and the GeckoLib 5.5 render-state naming. The mob is meant to behave exactly
as it does on 1.20.1. Everything the `## 1.2.0+mc26.2`, `## 1.2.0+mc1.21.11`, `## 1.2.0+mc1.21.5`,
`## 1.2.0+mc1.21.4` and `## 1.2.0+mc1.21.1` sections list still applies here and is not repeated; the
Java sources are identical to the 26.2 branch apart from the renderer registration below and the
comments. Every vanilla signature the mod uses, common and client side, is the same on 26.1.2 as on
26.2 - the whole thing compiled against 26.1.2 + GeckoLib 5.5.2 + Fabric API 0.155.2 before a single
line was changed - and Fabric API 0.155.2 already has the renamed `fabric-creative-tab-api-v1` module
(5.0.11) with `CreativeModeTabEvents.modifyOutputEvent`, and `CreativeModeTabs.SPAWN_EGGS` is already
private on 26.1.2 with that module's transitive class tweaker re-opening it, so the 26.2 notes on both
apply verbatim. Below is only what differs.

### Changed

- **GeckoLib 5.5.2 (Modrinth `XZTmZlwb`), the build published for 26.1.2, instead of 5.5.4.** Every
  GeckoLib class the mod touches (`GeoRenderLayer`, `RenderPassInfo`, `PerBoneRender`,
  `GeoEntityRenderer`, `GeoRenderer`, `GeoModel`, `GeoRenderState`, `GeoBone`, `CuboidGeoBone`,
  `GeoCube`, `AnimationController`, `AnimationTest`, `AnimatableManager`) was `javap`-diffed against
  5.5.4: the only difference is the type bound on `GeoEntityRenderer.convertRenderStateToLiving` (5.5.2
  still asks for `LivingEntityRenderState & GeoRenderState`), a protected helper this mod never calls.
  The class tweaker carries the same two `transitive-inject-interface` lines, so naming the render
  state as a plain `LivingEntityRenderState` holds on 5.5.2 too, and the body pass is still one
  `submitCustomGeometry` in `RenderTypes.entityCutout` at the default order. The `appear`/`burrow`
  zero-tick transition check the 1.21.11 notes ask for on every GeckoLib bump was repeated on 5.5.2's
  bytecode: `checkControllerState` captures `transitionTicks` before the handler runs, and
  `initializeNewAnimation` uses that captured value only when `triggeredAnimTime > 0`, re-reading the
  field otherwise - so `setTransitionTicks(0)` inside the handler still governs the animation the same
  call starts. No `META-INF/jars/`, so still no `mclib` jar.
- **`fabric.mod.json` declares `minecraft >=26.1.2`**, which is exactly the range GeckoLib 5.5.2's own
  `fabric.mod.json` declares for itself (the 26.2 branch pinned `26.2` instead, though GeckoLib 5.5.4
  declares `>=26.2`). Only 26.1.2 was built and smoke-tested; the same source does compile against 26.2
  + GeckoLib 5.5.4, which is what the `26.2` branch is, but that branch's jar is the one to use there.
- **The renderer is registered with vanilla's `EntityRenderers.register`, not Fabric API's
  `EntityRendererRegistry.register`.** Fabric API marks `EntityRendererRegistry` `@Deprecated` on 26.x
  (rendering-v1 23.3.1 in 0.155.2 and 25.3.3 in 0.159.0 alike) - it was the build's only deprecation
  warning - and its `register` is a one-line delegate to the vanilla method. That method is private in
  vanilla; it compiles and runs because `fabric-transitive-access-wideners-v1` re-opens it
  (`transitive-accessible method ... EntityRenderers register`), the same widener that makes
  `SpawnPlacements.register` usable on the common side. Registration still happens in
  `onInitializeClient`, before the `EntityRenderDispatcher`'s first resource reload reads the provider
  map, so nothing about *when* the renderer exists changed.
- **Both extra render passes still go to collector order 1, but the reason reads differently on
  26.1.2, because its collector is the 1.21.11 shape, not 26.2's.** There are no render phases and no
  `StagedVertexBuffer` here: `MultiBufferSource.BufferSource` is still the playback sink (one shared
  builder, a handful of fixed buffers, `lastSharedType`), `CustomFeatureRenderer.Storage` files each
  `submitCustomGeometry` by `RenderType.hasBlending()` into either a solid or a translucent
  `HashMap<RenderType, List<CustomGeometrySubmit>>`, and `FeatureRenderDispatcher.renderAllFeatures`
  runs `renderSolidFeatures` - every collector order ascending out of `SubmitNodeStorage`'s
  `Int2ObjectAVLTreeMap`, and within each order the model, model-part, flame, leash, item, block,
  custom-geometry and particle solids - then `renderTranslucentFeatures` over every order again, then
  translucent particles. Custom geometry is drawn one render type at a time (`getBuffer(type)`, then
  the type's list in submission order), with `HashMap` order between types. So the body
  (`entityCutout`, order 0, solid map) is drawn during order 0's solid pass; the tint (same render
  type, order 1) during order 1's solid pass, after every order-0 solid draw; and the glow (`eyes`, a
  blending type, order 1) during the translucent sweep, after every solid draw of every order. On this
  collector the 1.21.11 argument - a tint at order 0 is appended to the body's own per-type list -
  would also have held, but the order-1 placement is what both 26.1.2 and 26.2 actually guarantee,
  matches vanilla's `EyesLayer` (order 1 here too), and keeps the layer's source identical to the 26.2
  branch, so it stays. 26.2's `SharedConstants.DEBUG_SHUFFLE_MODELS` flag exists on 26.1.2 as well,
  but `CustomFeatureRenderer` does not shuffle anything. `OreTintLayer`'s comments and CLAUDE.md
  describe the 26.1.2 mechanics; the 26.2 ones are in the section below.

### Not verified

- **Rendering, and the spawn egg's appearance.** Compiled against 26.1.2 + GeckoLib 5.5.2 and
  smoke-tested on a headless dedicated server only (a data pack `#load` function summoning a lizard on
  the first tick and a `#tick` function stopping the server - see CLAUDE.md), so everything the 26.2 and
  1.21.11 sections list as unverified still wants a look in a client: that the tint submitted at order 1
  lands on the two bones exactly as before, that the concrete `LivingEntityRenderState` is the state
  GeckoLib 5.5.2 hands the layer, whether Iris maps the `eyes` render type to `gbuffers_spidereyes` on
  26.1.2, and how a spectator sees a buried lizard. Also that the renderer registered through vanilla's
  `EntityRenderers.register` is picked up at all (a missing renderer would draw nothing - compiling
  through the transitive widener is the only check that was possible here).

## 1.2.0+mc26.2

A port of 1.2.0 to Minecraft 26.2 (Fabric Loader 0.19.5, Fabric API 0.159.0, GeckoLib 5.5.4, Java 25),
built on top of the 1.21.11 port below. The mob is meant to behave exactly as it does on 1.20.1.
Everything the `## 1.2.0+mc1.21.11`, `## 1.2.0+mc1.21.5`, `## 1.2.0+mc1.21.4` and `## 1.2.0+mc1.21.1`
sections list still applies here unchanged - the submit/collector rendering with per-bone tasks, the
render-state `DataTicket`s, `ValueOutput`/`ValueInput` saves, `hurtServer`, the pickaxes tag plus the
diamond-ore harvest check standing in for "iron or better", the baked spawn egg texture and its item
model definition, the `STEP_HEIGHT` attribute, the id-keyed flee modifier, the packed-int tint colours,
vanilla's `EntityType.Builder`, `Item.Properties.setId`/`spawnEgg`, and no `mclib` jar (GeckoLib 5.5.4
has no `META-INF/jars/` either) - and is not repeated. Below is only what 26.2 and the GeckoLib
5.4 → 5.5 jump forced on top of that. Plain renames are not listed: GeckoLib's root package is now
`com.geckolib` (was `software.bernie.geckolib`, every class otherwise where 5.4 had it),
`LightTexture.FULL_BRIGHT` is `net.minecraft.util.LightCoordsUtil.FULL_BRIGHT`, and Fabric API's
item-group module became `fabric-creative-tab-api-v1`, so `ItemGroupEvents.modifyEntriesEvent` is
`CreativeModeTabEvents.modifyOutputEvent` with the same tab key and the same "append to the tab"
semantics. Every vanilla signature the common side uses (`finalizeSpawn`, `hurtServer`,
`dropCustomDeathLoot`, `checkDespawn`, the save-data views, `SpawnEggItem`, `EntityType.Builder`, the
attribute, heightmap, tag, particle and sound constants) is unchanged from 1.21.11.

### Changed

- **The tint pass is submitted one collector order after the body, like the glow already was.** On
  1.21.11 the tint went into the model's own render type at the body's order 0 and relied on the
  collector keeping one list per render type in submission order, which guaranteed it was appended
  after the body's quads; only the glow needed order 1. 26.2 has no per-render-type lists. A
  submission goes into a *phase* of the collection for its order, chosen by its render type
  (`SubmitNodeCollection.submitCustomGeometry`: outline types to the outline phase, blending types such
  as `eyes` to translucent custom geometry, everything else - the body and the tint - to solid); a
  phase holds every feature's submissions in a single list, and `SimpleFeatureRenderPhase.maybeShuffle`
  shuffles that list when `SharedConstants.DEBUG_SHUFFLE_MODELS` is on - Mojang's declaration that the
  order of submissions within a phase is unspecified. What is guaranteed is the order between orders
  (`SubmitNodeStorage` keys its collections in an `Int2ObjectAVLTreeMap`, drained ascending) and
  between phases (`FeatureRenderDispatcher.renderAllFeatures` executes every solid phase, order by
  order, before any translucent one). So both extra passes now go to order 1: the tint into order 1's
  solid phase, after every order-0 solid draw, and the glow into order 1's translucent custom
  geometry phase, after every solid draw including the tint. Vanilla's `EyesLayer` still submits at
  order 1 on 26.2. Nothing visible changes - the tint is drawn at the same depth as the body with the
  later draw winning, exactly as the appended draw did - it just rests on an ordering the collector
  actually promises. The rule from the earlier ports reads, on 26.2: **never submit an extra pass at
  the body's collector order** - not even in the body's own render type.
- **The render state is named as `LivingEntityRenderState` instead of a bounded type parameter.**
  GeckoLib 5.5 declares `EntityRenderState implements GeoRenderState` through a
  `transitive-inject-interface` line in its class tweaker, which Loom applies to the dev jar and
  Fabric Loader applies at runtime; `GeoEntityRenderer` itself dropped its `GeoRenderState` bound for
  the same reason. `OreLizardRenderer` and `OreTintLayer` therefore use the concrete state class, and
  the `R extends LivingEntityRenderState & GeoRenderState` intersection the 1.21.5 and 1.21.11 ports
  needed is gone. Behaviourally identical; the object at runtime is the same `LivingEntityRenderState`.
- **The build uses Loom's non-remapping plugin.** 26.x is published unobfuscated with Mojang's names
  already in the jar, so `build.gradle` applies `net.fabricmc.fabric-loom` (not `fabric-loom-remap`),
  has no `mappings` line, and declares the loader, Fabric API and GeckoLib as plain `implementation`
  dependencies - the non-remapping plugin has no `modImplementation`. Loom still reads each mod's
  `fabric.mod.json` and class tweaker from there: `CreativeModeTabs.SPAWN_EGGS` is private in 26.2's
  own source and `SpawnPlacements.register` still is, and both compile only because Fabric's
  creative-tab and transitive-access-wideners modules re-open them transitively. Java 25 is required
  by the game, Fabric API and GeckoLib alike, so the build declares a Java 25 toolchain (Gradle
  auto-detects the JDK 25 it has provisioned under `~/.gradle/jdks`; the daemon itself may run on 21),
  compiles with `--release 25`, and the mixin config's `compatibilityLevel` is `JAVA_25`, which the
  Mixin 0.17.4 fork Fabric Loader 0.19.5 ships accepts. `fabric.mod.json` pins `minecraft` to `26.2`
  and `java` to `>=25`.
- **The zero-tick `appear`/`burrow` transition still applies on the frame it is requested** - a check,
  not a change, repeated for GeckoLib 5.5.4 as the 1.21.11 notes ask for on every GeckoLib bump.
  `AnimationController.checkControllerState` captures `transitionTicks` before calling the handler,
  but `initializeNewAnimation` only uses that captured value when the animation was started through
  `triggerAnim` (`triggeredAnimTime > 0`); for a handler-set animation it reads the field again after
  the handler has returned, so `setTransitionTicks(0)` inside the handler governs the animation the
  same call starts.

### Not verified

- **Rendering, and the spawn egg's appearance.** Compiled against 26.2 + GeckoLib 5.5.4 and
  smoke-tested on a headless dedicated server only. Everything the 1.21.11 section lists as unverified
  still wants a look in a client, plus the two things this port changed in the layer: that the tint
  submitted at order 1 lands on the two bones exactly as before (same depth as the body, drawn after
  it - a visibly untinted `shards`/`eyes` pair would mean the solid-phase execution order is not what
  the dispatcher bytecode says), and that the concrete `LivingEntityRenderState` is indeed the state
  GeckoLib hands the layer (a `ClassCastException` on first render would say otherwise). Also
  unverified: whether Iris maps the `eyes` render type to `gbuffers_spidereyes` on 26.2's
  feature-renderer pipeline, and how a spectator sees a buried lizard.

## 1.2.0+mc1.21.11

A port of 1.2.0 to Minecraft 1.21.11 (Fabric Loader 0.19.5, Fabric API 0.141.6, GeckoLib 5.4.5,
Java 21), built on top of the 1.21.5 port below - the 1.21.6 through 1.21.10 changes are all absorbed
here, there is no intermediate branch between the two. The mob is meant to behave exactly as it does on
1.20.1. Everything the `## 1.2.0+mc1.21.5`, `## 1.2.0+mc1.21.4` and `## 1.2.0+mc1.21.1` sections list
still applies here unchanged - the render-state `DataTicket`s, `hurtServer`, the pickaxes tag plus the
diamond-ore harvest check standing in for "iron or better", the baked spawn egg texture, the
`STEP_HEIGHT` attribute, the id-keyed flee modifier, the packed-int tint colours, vanilla's
`EntityType.Builder`, `Item.Properties.setId`, `SpawnPlacements.register` through Fabric's access
widener, and no `mclib` jar (GeckoLib 5.4.5 has no `META-INF/jars/` either) - and is not repeated.
Below is only what 1.21.6 through 1.21.11 and the GeckoLib 5.1 → 5.4 jump forced on top of that. Plain
renames are not listed: `ResourceLocation` is `Identifier`, `Level.isClientSide` is a method,
GeckoLib's `AnimationController` moved back up to `animation` with `PlayState` under
`animation.object`, `BakedGeoModel`/`GeoBone` moved to `cache.model`, `transitionLength` is
`setTransitionTicks`, `addRenderLayer` is `withRenderLayer`, `GeoModel.addAdditionalStateData` and
`GeoRenderLayer.addRenderData` grew a "related object" and a partial-tick argument respectively. The
renderer-side signature changes are covered only where they changed how something works.

### Changed

- **The client no longer draws the lizard; it submits it, so the rendering layer was rewritten a
  second time, around vanilla's 1.21.9 submit/collector model.** Entity renderers now hand geometry
  to a `SubmitNodeCollector`, which files each submission under its render type together with a copy
  of the pose it was submitted with, and vanilla plays everything back after all entities are in,
  one buffer per render type. GeckoLib 5.4 follows suit: its model pass is a single
  `submitCustomGeometry`, its layer hooks take a `RenderPassInfo` and the collector, and a layer's
  per-bone tasks run at submission time with the pose stack placed at the bone in its animated pose.
  Nothing about the three-pass structure changed - base model, the `shards`/`eyes` bones again
  multiplied by the variant colour, then those two bones once more fullbright through vanilla's
  `eyes` render type at 0.7 × the colour, skipped for an invisible lizard - but the mechanics did,
  twice over:
  - *The bone-pose bookkeeping is gone.* Both the 1.20.1 and 1.21.5 layers spent most of their code
    carrying a bone's matrices from the point they were available to the point the emissive draw was
    safe. The collector now captures the pose the moment the per-bone task submits, so the task
    simply submits both extra passes for its bone and vanilla plays them back later with the right
    buffer. The playback callback draws just that bone's cubes from the captured pose, translating
    away from the pivot first because GeckoLib hands a per-bone task the pose *at* the pivot, which is
    GeckoLib's own recipe from its `CustomBoneTextureGeoLayer`. Invisibility is read off vanilla's
    `EntityRenderState.isInvisible` instead of a ticket of our own, since that field is exactly
    `Entity.isInvisible()` at extraction time.
  - *The emissive pass is submitted at collector order 1, one order after the body and the tint.* The
    old rule - never ask the buffer source for a different render type while the model's batch is
    being written - still describes how `MultiBufferSource.BufferSource` works: every render type
    without a dedicated buffer shares one builder, and requesting another shared type draws whatever
    that builder holds. What changed underneath is that the entity cutout types are no longer among
    the dedicated buffers, so the body itself now lives in the shared builder, and that the collector
    replays custom geometry per render type out of a plain `HashMap`, with no ordering between types.
    Filed at order 0 next to the body, the glow's `eyes` list could be replayed first, drawn the
    instant the body's type was requested, and then painted over by the body - which the glow, writing
    no depth, cannot keep out. Orders are replayed in ascending sequence, each in full before the
    next, so an order-1 glow is buffered only after the body has been written, and the body's batch is
    flushed by the first shared-type request order 1 makes. That is the same ordering property the
    1.20.1 code got from deferring the swap until after the whole model, expressed in the collector's
    terms - and it is exactly what vanilla's own `EyesLayer` (spider and enderman eyes) does. The
    tint pass stays at order 0 in the model's own render type: the collector keeps one list per type
    in submission order, so it lands in the model's batch after the model's quads, the same append
    it always was.
  GeckoLib's `AutoGlowingGeoLayer` was looked at again as the 5.4 reference for an extra pass and
  rejected for the same reasons as before - it re-renders the whole model through a GeckoLib-private
  `geckolib_emissive` pipeline shader packs have no convention for, and needs a `_glowmask` texture
  per skin - though its use of order 1 for the emissive submission is the same conclusion reached
  here. `RenderTypes.eyes` (now under `client.renderer.rendertype`) is still the spider-eyes type
  Iris and OptiFine map to `gbuffers_spidereyes`; its pipeline is `RenderPipelines.EYES`.
- **Saved-lizard data goes through `ValueOutput`/`ValueInput`.** 1.21.6 replaced the `CompoundTag`
  overloads of `addAdditionalSaveData`/`readAdditionalSaveData` with typed views the game fills in and
  reads back itself. The keys (`OreVariant` as the enum name, `Deepslate` as a boolean) and their
  types are unchanged, so worlds saved by any earlier version of the mod load, and `ValueInput` kept
  the `Optional`-returning `getString` and the defaulted `getBooleanOr` 1.21.5 introduced on
  `CompoundTag`, so the "unknown or missing variant keeps the default" behaviour is the same code path
  as before.
- **The spawn egg names its mob through a data component.** `SpawnEggItem` lost its `EntityType`
  constructor argument in 1.21.6; the item is a plain `SpawnEggItem(Item.Properties)` and the entity it
  spawns is a default component set through `Item.Properties.spawnEgg(EntityType)`. Same egg, same
  behaviour; only the wiring moved. The baked texture, item model and item model definition from the
  1.21.5 port are unchanged - 1.21.11's item model definitions still accept them as written.
- **The zero-tick `appear`/`burrow` transition still takes effect on the frame it is requested.** This
  is a check rather than a change: the mob depends on `setTransitionTicks(0)` inside the controller
  handler applying to the animation the same handler call starts (see the 1.2.0 notes on why `appear`
  must start on its first frame). GeckoLib 5.4's `AnimationController` reads its transition-tick field
  after the handler returns when it builds the timeline for a non-triggered animation, so it does; the
  captured pre-handler value is only used for animations started through `triggerAnim`, which this mod
  never uses.

### Not verified

- **Rendering, and the spawn egg's appearance.** Compiled against 1.21.11 + GeckoLib 5.4.5 and
  smoke-tested on a headless dedicated server only. This is the second rewrite of the layer in two
  versions and the first under the submit/collector model, so everything in it wants a look in a
  client before release: that the tint lands on the model's own batch (the two bones exactly as
  tinted as on 1.20.1, no re-typed tail or legs), that the order-1 glow is drawn over the body rather
  than under it and does not shine through walls, that a buried lizard draws nothing, that the
  `appear`/`burrow` transitions still start on their first frame under GeckoLib 5.4's timeline code,
  that `IS_MOVING` fires the scuttle at the same speeds as before, and that the egg icon reads as the
  grey-and-cyan egg it was. Also unverified: how a spectator sees a buried lizard - GeckoLib draws
  invisible mobs as translucent ghosts for spectators, and the tint pass is submitted opaque into that
  translucent render type, so the two bones may read solid on a ghostly body; the glow is skipped in
  that case as intended - and whether Iris maps the `eyes` render type to `gbuffers_spidereyes` on
  1.21.11's render-pipeline-based render types as it did on the shader-based ones.

## 1.2.0+mc1.21.5

A port of 1.2.0 to Minecraft 1.21.5 (Fabric Loader 0.19.5, Fabric API 0.128.2, GeckoLib 5.1.0,
Java 21), built on top of the 1.21.4 port below. The mob is meant to behave exactly as it does on
1.20.1. Everything the `## 1.2.0+mc1.21.4` and `## 1.2.0+mc1.21.1` sections list still applies here
unchanged - `hurtServer`, the diamond-ore harvest check standing in for "iron or better", the
`STEP_HEIGHT` attribute, the `ResourceLocation`-keyed flee modifier, the packed-int tint colours,
vanilla's `EntityType.Builder`, `Item.Properties.setId`, `SpawnPlacements.register` through Fabric's
access widener, and no `mclib` jar (GeckoLib 5.1.0 has no `META-INF/jars/` either) - and is not
repeated. Below is only what 1.21.5 and the GeckoLib 4 → 5 jump forced on top of that. Plain API
renames are not listed; GeckoLib's package reshuffle (`AnimatableManager` under `animatable.manager`,
`AnimationController` under `animatable.processing`) and the renderer-side signature changes are
covered only where they changed how something works.

### Changed

- **The client draws the lizard from a render state, so the rendering layer was rewritten around
  GeckoLib 5's render-state model.** 1.21.2+ vanilla renders every entity from a snapshot
  (`EntityRenderState`) taken before drawing, and GeckoLib 5 follows suit: its model resolves the
  texture from that snapshot, its layer hooks receive the snapshot instead of the entity, and its
  animation handlers run while the snapshot is being taken. Nothing about the three-pass structure
  changed - base model, the `shards`/`eyes` bones again multiplied by the variant colour, then those
  two bones once more fullbright through vanilla's `RenderType.eyes` at 0.7 × the colour, skipped for
  an invisible lizard - but every piece of entity data it reads now travels through the render state
  as a GeckoLib `DataTicket`: the variant's tint colour and the invisibility flag are copied in by
  `OreTintLayer.addRenderData`, the stone/deepslate choice by `OreLizardModel.addAdditionalStateData`,
  and all three are read back from the state in the render hooks. The tint pass moved from the
  removed per-bone layer hook (`renderForBone`) to GeckoLib 5's per-bone render tasks
  (`addPerBoneRender`), which GeckoLib runs after the whole model has been written with each bone's
  pose restored - structurally the same "capture during the bone pass, draw after the model" that the
  1.20.1 layer implemented by hand. The tint draws into the render type the model was just drawn with,
  which the buffer source answers with the very batch still in progress, so it stays an append and
  never a swap; the emissive draw stays deferred to the layer's `render`, which runs after every
  per-bone task, because the tasks execute in hash-map order and an `eyes` request inside one of them
  would still cut the other bone's tint off into a batch of its own. That is the same rule as before
  (never request a different render type's buffer while the model's batch is still being written),
  applied to the new hook layout. GeckoLib's own `AutoGlowingGeoLayer` was looked at as the 5.x
  reference for a post-model extra pass and rejected again for the same reasons as on 1.20.1: it
  re-renders the whole model through a GeckoLib-private `geckolib_emissive` pipeline shader packs have
  no convention for and needs a `_glowmask` texture per skin, which would cost the per-variant tint.
  Because vanilla's render state classes only implement GeckoLib's `GeoRenderState` through a runtime
  mixin, the renderer and layer keep the render state as a bounded type parameter
  (`R extends LivingEntityRenderState & GeoRenderState`), the way GeckoLib's own subclasses do.
- **The tint pass is only scheduled when the model is actually being drawn.** GeckoLib 5 still runs
  every layer hook for an entity that is invisible to the viewer; it merely skips the bone recursion,
  and the bone recursion is the only place a per-bone task's pose gets captured. A task registered for
  an invisible entity therefore runs against a pose that was never captured, which is a
  `NullPointerException` inside GeckoLib for every dormant lizard in view. `OreTintLayer` notes in
  `preRender` whether GeckoLib resolved a render type and buffer for the model and registers nothing
  when it did not. This also happens to be the right behaviour: a dormant lizard draws nothing at all,
  which is what 1.20.1 did too.
- **Animation state is decided during render-state extraction rather than mid-render.** GeckoLib 5's
  controller handler receives an `AnimationTest` (the animatable, its render state, its manager and the
  controller) and is invoked from `GeoModel.prepareForRenderPass` while the client builds the render
  state, not from inside the draw. It is still the client's copy of the entity, so the handler reads
  the synced `STATE` data exactly as before and the appear/scuttle/burrow decisions are unchanged.
  `isMoving()` now reads GeckoLib's `IS_MOVING` state entry, which `GeoEntityRenderer` fills from
  `walkAnimation.speed()` against the same 0.015 threshold 4.x used. One consequence worth knowing:
  GeckoLib 5 ticks controllers for an invisible entity too (it extracts a state and runs
  `handleAnimations` even when nothing is drawn), where 4.x froze them. Nothing changes for the lizard
  - the handler returns `STOP` while buried, so the controller sits stopped rather than frozen - but
  the 1.20.1 note that a dormant lizard's controller "never ticks" is no longer literally true.
- **"Is it a pickaxe" is now the `minecraft:pickaxes` item tag.** 1.21.5 removed `PickaxeItem` along
  with the other tool subclasses - a pickaxe is a plain `Item` whose digging behaviour comes entirely
  from its `TOOL` component - so the `instanceof` half of the 1.21.4 rule has nothing left to test.
  The complete rule is now `stack.is(ItemTags.PICKAXES) && stack.isCorrectToolForDrops(DIAMOND_ORE)`,
  which accepts exactly the vanilla set the 1.20.1 tier check did (iron, diamond, netherite) and
  rejects wood, stone and gold. The one place it can differ from 1.20.1 is a modded pickaxe: it
  qualifies if it is in the pickaxes tag (the tag vanilla itself uses to recognise pickaxes, which
  modded ones are expected to join) and its material can mine diamond ore, and no longer qualifies if
  it skipped the tag. Previously no modded pickaxe qualified at all.
- **The spawn egg has its own baked texture.** 1.21.5 gave every vanilla egg an individual texture
  and deleted the shared `spawn_egg`/`spawn_egg_overlay` layers and the `template_spawn_egg` model, so
  the 1.21.4 item model definition - the template plus two constant tints - has nothing left to tint.
  `textures/item/ore_lizard_spawn_egg.png` is generated from the 1.21.4 client's two layer textures by
  doing in pixel space exactly what the tinted model did at draw time: body texels multiplied by
  `#6E6E6E`, spot texels multiplied by `#63E1FF`, spots composited over body with the 10% alpha
  cutout `item/generated` applies. The model is now a plain `item/generated` with that texture as
  `layer0`, and the item model definition is a plain `minecraft:model` with no tints. Same silhouette
  and colours as the 1.21.4 egg; the only difference is that the multiply is baked at texture
  resolution instead of applied per draw, which is not visible.
- **Saved-lizard data is read through `CompoundTag`'s `Optional` getters.** 1.21.5 replaced
  `contains(key, type)` + `getString(key)` with `getString(key)` returning an `Optional` that is empty
  when the key is missing or holds another tag type, and `getBoolean` with `getBooleanOr(key, false)`.
  Semantics are unchanged: a lizard saved without a variant, or with a name this version doesn't
  recognise, keeps the default rather than being re-rolled, because `byName`'s null for an unknown
  name collapses into the empty `Optional` and nothing is set.

### Not verified

- **Rendering, and the spawn egg's appearance.** Compiled against 1.21.5 + GeckoLib 5.1.0 and
  smoke-tested on a headless dedicated server only, and this is the first port where the layer is a
  genuine rewrite rather than a signature update. Everything in it wants a look in a client before
  release: that the tint pass lands on the model's own batch (the two bones should look exactly as
  tinted as on 1.20.1, with no re-typed tail or legs), that the emissive pass glows and does not shine
  through walls, that a buried lizard draws nothing and throws no per-bone-task exception, that the
  `appear`/`burrow` transitions still start on their first frame under GeckoLib 5's extraction-time
  handler, and that the egg icon reads as the grey-and-cyan egg it was. Also unverified: how a
  spectator sees a buried lizard (GeckoLib 5, like vanilla, draws invisible mobs as translucent ghosts
  for spectators; the tint is drawn in that case and the glow is not, which matches 1.20.1's
  behaviour by construction but was not observed), and whether GeckoLib 5's `IS_MOVING` fires the
  scuttle at the same speeds 4.x's `isMoving()` did.

## 1.2.0+mc1.21.4

A port of 1.2.0 to Minecraft 1.21.4 (Fabric Loader 0.19.5, Fabric API 0.119.4, GeckoLib 4.8.5,
Java 21), built on top of the 1.21.1 port below. The mob is meant to behave exactly as it does on
1.20.1. Everything the `## 1.2.0+mc1.21.1` section lists still applies here unchanged - step height as
the `STEP_HEIGHT` attribute, the `ResourceLocation`-keyed flee modifier, the packed-int tint colours,
vanilla's `EntityType.Builder`, `SpawnPlacements.register` through Fabric's access widener, and no
`mclib` jar (GeckoLib 4.8.5 for 1.21.4 has no `META-INF/jars/` either) - and is not repeated. Below is
only what 1.21.2 through 1.21.4 forced on top of that. Plain API renames (`MobSpawnType` →
`EntitySpawnReason`, `EntityType.Builder.build` taking the registry key, `spawnAtLocation` taking the
level, GeckoLib's `GeoModel` getters taking the renderer and its layer hooks growing a trailing
`renderColor` argument) are not listed.

### Changed

- **"Iron or better pickaxe" is now "a pickaxe that could drop diamond ore".** 1.21.2 removed
  `Tier`/`Tiers`, so the identity comparison against `IRON`/`DIAMOND`/`NETHERITE` has nothing left to
  compare. Its replacement, `ToolMaterial`, carries no rank; what it carries is the tag of blocks the
  tool is *not* good enough to harvest, baked into the stack's `TOOL` component. The armour bypass
  therefore asks `ItemStack.isCorrectToolForDrops(DIAMOND_ORE)`: diamond ore needs an iron tool, so
  wood, stone and gold picks fail it and iron, diamond and netherite pass, which is exactly the set the
  tier check accepted. `instanceof PickaxeItem` is still the "is it a pickaxe" half, as before. The one
  place this can differ from 1.20.1 is a modded pickaxe with its own material: previously it never
  qualified (it wasn't one of the three vanilla tiers), now it qualifies if its material can mine
  diamond ore - which is arguably what the rule always meant.
- **Damage handling moved from `hurt` to `hurtServer`.** 1.21.2 made `Entity.hurt` final and split it
  into a client/server dispatcher; `hurtServer(ServerLevel, DamageSource, float)` is the half with the
  logic, and is what the creative/spectator rejection, the dormant panic and the pickaxe armour bypass
  now override. Same behaviour - `LivingEntity.hurt` already returned false on the client before doing
  anything, so nothing that used to run client-side has been lost.
- **The spawn egg's colours live in an item model definition instead of on the item.** 1.21.4
  introduced `assets/<namespace>/items/<id>.json` as the thing that decides how an item renders, and
  moved spawn egg tinting there: `SpawnEggItem` no longer takes colours at all, and vanilla's own eggs
  are `minecraft:model` definitions carrying two `minecraft:constant` tints. Ours is written the same
  way, pointing at the existing `models/item/ore_lizard_spawn_egg.json` (parent `template_spawn_egg`)
  with the same `0x6E6E6E` body and `0x63E1FF` highlight as before, as the signed ARGB ints vanilla
  uses. Without that file the egg would render as the missing-model placeholder, so it is load-bearing.
  Every item also now has to be handed its registry key through `Item.Properties.setId` before
  construction (1.21.2+; the constructor throws "Item id not set" without it), which is also how the
  client finds that definition.

### Not verified

- **Rendering, and the spawn egg's appearance.** Compiled against 1.21.4 + GeckoLib 4.8.5 and
  smoke-tested on a headless dedicated server only. GeckoLib 4.8.5's layer hooks (`renderForBone`,
  `render`) still hand the layer the entity itself and still run in the same order as 4.8.4/4.9.2, so
  the three-pass tint/emissive structure and the deferred `RenderType.eyes` draw are ported unchanged -
  but the tint pass, the emissive pass, the buried-lizard invisibility skip, the `appear`/`burrow`
  transition timing and the tinted egg icon all want a look in a client before release. One detail
  specific to 1.21.4: `PoseStack.Pose` gained a `trustedNormals` flag, and the pose `OreTintLayer`
  pushes for the emissive draw inherits it from the current stack top rather than from the captured
  bone pose. It only governs whether normals are re-normalised, and the emissive pass goes through
  `rendertype_eyes`, which does no lighting, so it should be invisible - but it is untested.

## 1.2.0+mc1.21.1

A port of 1.2.0 to Minecraft 1.21.1 (Fabric Loader 0.19.5, Fabric API 0.116.17, GeckoLib 4.9.2,
Java 21). The mob is meant to behave exactly as it does on 1.20.1; everything below is a place the
target version forced a different implementation, with what that does and does not change. Plain
API renames (`ResourceLocation.fromNamespaceAndPath`, `SynchedEntityData.Builder`, GeckoLib's
package reshuffle, the shorter `finalizeSpawn`/`dropCustomDeathLoot` signatures) are not listed.

### Changed

- **Step height is now the `STEP_HEIGHT` attribute rather than a setter.** 1.20.5 removed
  `Entity.setMaxUpStep` and made step height a generic attribute, so the lizard's 1.0 now lives in
  `createAttributes` alongside its health and speed. Same value, same effect on `MoveControl` and the
  pathfinder's `WalkNodeEvaluator`; the one visible difference is that it is now an attribute like any
  other, so `/attribute` can read or override it.
- **The flee speed boost is identified by `orelizards:flee_speed_boost` instead of a UUID.** 1.21
  keys attribute modifiers by `ResourceLocation` and dropped the separate display name. The modifier
  is transient (never written to NBT), so no saved lizard carries the old id. `MULTIPLY_TOTAL` became
  `ADD_MULTIPLIED_TOTAL` under the rename; the maths - final speed = base total × (1 + 0.925) - is
  unchanged.
- **Tint and glow colours are handed to GeckoLib as one packed ARGB int.** GeckoLib 4.5+ replaced the
  four float channels on `renderCubesOfBone` with a single `0xAARRGGBB`. The tint pass packs the
  variant colour with a fully opaque alpha, exactly the multiply it was before. The emissive pass
  scales each channel by `GLOW_STRENGTH` in integer space and truncates rather than rounds, because
  that is what the old float path did when it quantised `channel / 255 × 0.7` back to a byte - so the
  glow lands on byte-identical colours. The three-pass structure (base, tint, deferred `RenderType.eyes`
  pass after the whole model is written) is untouched: GeckoLib 4.9.2's `renderForBone`/`render`
  layer hooks and its bone-recursion order are the same as 4.8.4's, so the buffer-swap rule in
  `CLAUDE.md` still holds and the emissive draw is still deferred for the same reason.
- **Entity type is built with vanilla's `EntityType.Builder`.** Fabric's `FabricEntityTypeBuilder`
  still exists on 1.21.1 but is deprecated. `sized(0.9F, 0.6F)` is the same scalable hitbox
  `EntityDimensions.scalable` spelt out, and `clientTrackingRange(8)` the same 8 chunks. The string
  passed to `build` only feeds the DataFixer schema lookup; the entity's description id and default
  loot table still derive from the registry key, so the lang key and `/summon` id are unchanged.
- **`SpawnPlacements.register` is private in vanilla 1.21** and is reachable only because Fabric API's
  object-builder module widens it. This is the officially supported route (it is what Fabric's own
  builder calls), so spawn registration reads exactly as it does on 1.20.1 - but it is the reason the
  Fabric API dependency is load-bearing beyond biome spawn injection.

### Removed

- **`libs/mclib-20.jar` and its `implementation files(...)` line.** GeckoLib 4.9.2 no longer bundles
  mclib jar-in-jar (its `META-INF/jars/` is gone entirely and the jar has no mclib references), so the
  Loom dev-classpath workaround the 1.20.1 build needed has nothing left to work around.

### Not verified

- **Rendering.** Compiled against the real 1.21.1 + GeckoLib 4.9.2 signatures and the layer code is
  structurally identical to 1.20.1, but this port was built and smoke-tested on a headless dedicated
  server only. The tint pass, the emissive pass, the buried-lizard invisibility skip and the
  `appear`/`burrow` transition timing all want a look in a client before release.

## 1.2.0

A persistence pass. Everything here is about a lizard still being the lizard you left: the ore it
spawned as now survives a chunk unload, and a dormant one is no longer something the game can quietly
delete or push into its escape sequence on its own.

### Fixed

- **A lizard's ore variant and stone/deepslate skin were never saved.** `OreVariant` and `Deepslate`
  are tracked entity data, which is synced to clients but not written to disk, and nothing wrote them
  to NBT. Both therefore reverted to whatever `defineSynchedData` declares as the default the first
  time the lizard's chunk unloaded and came back - which is to say every deepslate diamond lizard
  silently became a stone coal lizard as soon as a player walked out of range and returned, texture,
  tint, dig sounds and drops all included. Both are now saved and restored. The variant is written by
  name rather than by ordinal, so reordering the enum or inserting a variant into the middle of it
  doesn't rewrite the ore in every already-saved world. A lizard saved before this release has
  nothing recorded and keeps the default rather than being re-rolled, on the grounds that changing
  the ore of a lizard a player has already found is worse than one legacy lizard reading as coal.
- **Environmental damage sent a dormant lizard into a flee it could not perform.** Any damage while
  buried triggered the panic response, but the flee target was only set when the damage came from a
  living entity. Lava, a falling block or a stray tick of AoE therefore left it FLEEING with a null
  target, and `FleeAndBurrowGoal` needs a target to measure distance from, so it never activated: the
  lizard stood visible and completely motionless in the open for the full 13 seconds, then burrowed
  and deleted itself. Damage with no attacker behind it now looks for the nearest player within 16
  blocks - the pathfinder's own `FOLLOW_RANGE`, past which there is nothing it could meaningfully run
  from - and if there is nobody there it stays in the rock and takes the damage.
- **A flee whose target disappeared ran to full length.** Killing, logging out or changing dimension
  during the 13 seconds left the same untargeted flee, with the same motionless lizard. Both
  ERUPTING and FLEEING now check that there is still something to run from, and burrow immediately
  when there isn't.

### Changed

- **Dormant and activated lizards now despawn by different rules, and neither of them rolls dice.**
  A buried lizard is the entire premise of the mob, so within 128 blocks of any player it does not
  despawn at all - previously it was rolling 1-in-10 every 5 seconds past 48 blocks, which meant a
  rare find could evaporate out of the floor of a cave a player was still working through. 128 is the
  entity's own tracking range, so beyond it the lizard isn't even being sent to a client; nobody can
  encounter it there, and leaving it in place only holds a slot in the AMBIENT population cap that a
  lizard nearer the player could be using, so past that radius it is now removed outright instead of
  waiting on a roll. The rule that nothing despawns while the nearest player is still underground is
  unchanged and still applies first. An **activated** lizard - erupting, fleeing or digging down -
  never despawns under any circumstance. It has already been seen, and it discards itself at the end
  of `DIGGING_DOWN` anyway, so the only thing a despawn could do there is delete it mid-run in front
  of the player who startled it.
- **Loading a chunk always returns a lizard to dormancy.** The state machine is deliberately left out
  of NBT. Its flee target is a live entity reference that can't survive a save in the first place, so
  the honest alternative is reloading mid-flee with nothing to run from. Coming back buried is both
  the better failure mode and the better fiction - it went back into the rock while nobody was loaded
  to watch it. It also makes the guarantee absolute: nothing but a genuine activation can put a
  lizard onto the burrow-and-discard path.
- **Every state transition now goes through one method each.** `beginErupting`, `beginFleeing`,
  `beginDiggingDown` and `becomeDormant` replace four copies of the same timer/attribute/visibility
  bookkeeping spread across the tick methods and the damage handler - two of which had already
  drifted apart. Both routes out of dormancy take the entity being fled from as a parameter, so
  "activated" and "has something to flee from" are the same thing by construction rather than by
  remembering to set a field.

## 1.1.0

A visibility and movement pass. 1.0.0 made the lizard possible to find; this release makes it
possible to follow once found — it glows, it trails sparks, and its emergence and burrow animations
finally play from the right place — and it moves like something genuinely trying to get away.

### Added

- **Emissive ore shards and eyes.** They are the parts of the mob meant to catch your eye, but lit
  normally against cave stone they were very easy to walk past. Both now get a second fullbright
  pass on top of the variant tint, so they glow in the dark rather than merely being brightly
  coloured. Drawn through `RenderType.eyes` — the same render type vanilla uses for enderman and
  spider eye overlays — which is fullbright and additive in vanilla, and which Iris and OptiFine
  route through their `gbuffers_spidereyes` program, so shader packs pick it up as a real emissive
  surface. GeckoLib's own `AutoGlowingGeoLayer` was the alternative, but it builds a custom render
  type shader packs have no convention for and needs a per-skin `_glowmask` texture, which would
  have cost the per-variant tinting. Glow strength is scaled to 70% so the brighter ores keep their
  hue instead of clipping to white; coal, being near-black, barely glows at all. Dormant lizards are
  excluded — a glow would defeat the camouflage outright.
- **A light spark trail while the lizard is out of the ground.** Vanilla's `firework` particle, one
  every 3 ticks, emitted at mid-body height with no velocity so the sparks hang where the lizard was
  and mark its actual path — the point is to still be able to follow it after it rounds a corner in
  an unlit cave. Gated on the state machine (erupting, fleeing, digging down) rather than on whether
  it is currently moving, so it doesn't cut out exactly when the mob is cornered or pathing round an
  obstacle, which is when you are most likely to lose it. Never emitted while dormant, for the same
  reason the glow isn't.

### Changed

- **Flee destinations are now chosen deterministically: the furthest reachable spot from the
  player.** The goal no longer asks `DefaultRandomPos.getPosAway` where to run. That draws ten
  *random* samples and discards any failing its pathability filters, so in a cave the player has
  opened up, the survivors are whatever the open corridor happens to offer — which made the choice
  of destination effectively arbitrary. It now sweeps 16 directions, probing each from its outermost
  ring inwards and stopping at the first standable column, then takes whichever puts the most
  distance between the lizard and the player. Anything no further away than the lizard already is
  gets rejected, so a direction that curls back towards the player can't win, and if nothing is
  reachable it still falls back to the random flee rather than standing still.
- **Flee scan distance capped at 12 blocks, to match what the pathfinder can actually deliver.** A
  mob's A* only expands nodes within `FOLLOW_RANGE` (16, Manhattan) of itself and gives up after
  `FOLLOW_RANGE * 16` = 256 nodes, so a target beyond that can never be reached — the search spends
  its entire node budget and returns a partial path regardless. Aiming inside that horizon means
  paths usually complete, which is cheaper and more predictable, and costs nothing in range because
  the lizard repaths every 10 ticks from wherever it has reached and only covers about six blocks in
  that time.
- **Flee repathing is now floored at 4 ticks.** The goal deliberately repaths early whenever the
  current path runs out, but a destination the navigator can't reach leaves `isDone()` true
  permanently — which meant a full destination search every single tick for the rest of the flee.
  The bug predates this release and was easy to miss, because it only fires when the lizard is
  already failing to find a way out.
- **`maxUpStep` raised from the default 0.6 to 1.0, so the lizard steps over one-block rises instead
  of jumping them.** `MoveControl` only jumps when the next waypoint is higher than `maxUpStep`, so
  every ledge in an uneven cave floor was costing it a jump, and each jump bled the momentum the
  flee speed boost had just given it. 1.0 is the value vanilla gives horses and ravagers. The same
  number feeds `WalkNodeEvaluator`, so its paths route over those rises as steps as well.
- **Drop counts are now tiered by ore.** Coal, iron, redstone, lapis and copper drop 4-6; gold,
  diamond and emerald drop 2-4, with a 2% chance of paying out 6 instead. Previously everything
  dropped a flat 1-3. The split is on the reasoning that a cheap ore is only worth killing for if
  you get a proper handful, whereas a valuable one is worth killing for at any amount — so those pay
  out smaller with an occasional windfall. Expressed as a `DropTier` on `OreVariant`, so a new
  variant declares which tier it belongs to and nothing else changes.
- **Ore tint colors re-derived from the actual block textures.** Every variant's tint is now the
  mean of all pixels in that mineral's solid block texture from the client jar, rather than
  hand-picked values. The solid block rather than the *ore* block, since an ore block is mostly its
  stone matrix and averages out grey. For the three metals that have one, the *raw* block is the
  source (`raw_iron_block`, `raw_gold_block`, `raw_copper_block`), because raw metal is what those
  variants drop and it looks nothing like the refined bar — raw iron is a tan-brown where an iron
  block is near-white. Coal `2B2B2B`→`101010`, iron `B8B1AA`→`A6886B`, gold `FFED00`→`DEA92F`,
  redstone `9C0D0D`→`B01905`, lapis `1A51B8`→`1F438C`, diamond `54BFD9`→`62EDE4`, emerald
  `30A758`→`2ACB58`, copper `A75A2C`→`B0664D`. Copper is the one value that isn't a straight mean:
  `raw_copper_block` is speckled with green oxidation — a third of its pixels sit at hues of 46° to
  161° — which pulled the mean to an olive-leaning `9A6A4F`, so it uses the mean of just its
  copper-hued pixels instead. These reach the model close to unchanged: the tint multiplies shard
  pixels averaging 229/255 and eye pixels averaging 252/255, so there is nothing meaningful to
  compensate for.

### Fixed

- **The eruption played from the wrong place for its first quarter-second.** GeckoLib doesn't start
  an animation on its first frame: it spends the controller's `transitionLength` (5 ticks here)
  blending from whatever pose the model is currently in into that first frame, and only then starts
  the animation's own clock. `appear` opens with the body 13 units — 0.81 blocks — underground,
  while the pose being blended from was the bind pose at ground level, and the lizard is made
  visible on the same tick the state flips. The result was a lizard popping into view standing on
  top of the block, sliding down into it over five ticks, and only then erupting. The two state
  animations now run with a zero-tick transition so they begin on their own first frame. That also
  makes `appear` line up with `ERUPT_DURATION_TICKS` — both are exactly 1 second, where previously
  the transition ate a quarter of the eruption and the rise was cut off before it finished.
- **The lizard popped back above ground at the end of burrowing.** `thenPlay` is `PLAY_ONCE`, and
  GeckoLib stops the controller when a `PLAY_ONCE` animation completes, which drops the model back
  to its bind pose. `burrow` ends with the body 32 units (two blocks) down, so for the last ticks of
  `DIGGING_DOWN` — the animation is 20 ticks, the state lasts 30 — the lizard reappeared at ground
  level, in full view, before being discarded. Both one-shots now use `thenPlayAndHold`.

## 1.0.0

First full release. The alpha had the mob's core loop working — spawn, erupt, flee, burrow, drop
ore — but it was static, silent, and effectively impossible to encounter in a real world. This
release makes it animated, audible, and actually findable.

### Added

- **`scuttle` walk-cycle animation.** Driven by GeckoLib's `isMoving()` (real velocity and
  limb-swing, the same signal vanilla mobs use), so it animates for any movement — fleeing,
  knockback, being shoved — not just its own AI state.
- **`appear` animation** on emergence, and **`burrow` animation** on digging back down. Both are
  one-shot and take priority over the movement animation so they can't be interrupted.
- **Spawn egg**, listed in the vanilla Spawn Eggs creative tab. Unlike `/summon`, it routes through
  `EntityType.spawn()` and therefore runs `finalizeSpawn`, so egg-spawned lizards get a proper ore
  variant, correct stone/deepslate attribution, and start genuinely dormant.
- **Digging sounds** when emerging and burrowing (`STONE_BREAK` / `DEEPSLATE_BREAK`, matching where
  it spawned), fired alongside the existing block-dust particle bursts.
- **Scuttle footstep sounds**, pitched up to read as a small skittering creature. Implemented via
  `playStepSound()`, which vanilla paces by distance travelled — so it automatically quickens with
  the flee speed boost rather than needing its own timer.
- **Ambient look goals** (`LookAtPlayerGoal`, `RandomLookAroundGoal`). These use the LOOK flag, not
  MOVE, so they run alongside fleeing rather than competing with it — they just stop it from
  looking like a statue while visible but stationary.
- **Panic on damage.** Taking any damage while dormant or emerging now immediately triggers a full
  flee response, targeting whatever hit it. Previously only player proximity could wake it, so a
  lizard struck by AoE damage just sat there.

### Changed

- **Spawn category `CREATURE` → `AMBIENT`.** `CREATURE` shares a single population cap with every
  surface animal, which is essentially always full by the time a player reaches a cave — meaning
  the lizard could go a whole session without a single spawn attempt regardless of weight. `AMBIENT`
  is the category vanilla's Bat uses for exactly this reason.
- **Spawn range widened from `Y < 0` to `Y < 50`**, now gated on being at least 8 blocks below the
  terrain surface rather than on raw depth. Depth-below-surface is used instead of a sky-light check
  because it works during worldgen before lighting is computed, and it ignores player torches.
- **Stone/deepslate attribution is now Y-based** (`Y < -4`) rather than sampling the block below.
  -4 is the midpoint of 1.20.1's stone→deepslate blend band, so it tracks what the surrounding rock
  actually looks like, and costs one comparison instead of block lookups at spawn time.
- **Despawning is now underground-aware.** Lizards never despawn while the nearest player is still
  below ground, and only start rolling to despawn once that player has surfaced. The check runs
  every 5 seconds instead of vanilla's every-tick nearest-player scan, with the heightmap lookup
  gated behind both a tick check and a distance check.
- **Spawn rate reduced by 30%** via a roll in the spawn predicate (spawn weights are integers and
  ours was already at the floor of 1, so the reduction couldn't be expressed as a weight).
- **Flee pathing repaths as soon as its current path completes**, instead of waiting out a fixed
  10-tick timer. In tight caves the old behaviour left it standing still mid-escape.
- **Emergence lengthened from 10 to 20 ticks** to match the `appear` animation's full 1-second
  length, so it no longer gets cut off partway through.
- **Creative and spectator players are ignored entirely** — they can't wake a dormant lizard by
  walking past, and can't damage one.
- Ore tint colors darkened slightly (gold lightened) for better contrast against the rock.

### Fixed

- **Dormant lizards were visible in the wild.** `LivingEntity.updateInvisibilityStatus()` re-evaluates
  the invisibility flag every tick as `setInvisible(hasEffect(INVISIBILITY))`, silently wiping the
  one-time `setInvisible(true)` from `finalizeSpawn` on the very next tick. This also caused the
  "teleported into the ground" effect on trigger: the lizard sat visible at rest pose, then snapped
  down when `appear`'s first keyframe (0.81 blocks underground) took over.
- **The burrow animation never played.** Entity state was a plain unsynced field, only ever updated
  inside the server-gated half of `tick()`. Animation controllers run on the client, whose copy of
  the state was permanently stuck at its initial value — so the "is it digging down?" check was
  never true. State is now synced properly.
- **Creative players still triggered dormant lizards** despite the exclusion, because
  `getNearestPlayer`'s boolean parameter has the opposite polarity to how it reads (`false` excludes
  only spectators). Now uses vanilla's named `EntitySelector.NO_CREATIVE_OR_SPECTATOR`.
- **Suffocation while dormant and burrowing.** Vanilla damages any entity whose hitbox overlaps a
  solid block; the lizard is exempt except while fleeing in open air.
- **Tail never animated.** The model's tail had been split into a `tail_01`/`tail_02`/`tail_03` bone
  chain, but the generated geometry still had a single static `tail` bone — so the animation's tail
  channels targeted bones that didn't exist and were silently discarded.
- **Animation lookups failed silently**, spamming `Unable to find animation` every frame, from a
  mismatch between the animation names in code and the actual keys in the exported file.
- **The stone variant could never spawn.** 1.20.1 replaces stone with deepslate below Y=-8, so the
  old `Y < 0` rule confined every natural spawn to deepslate — making the stone texture and its
  whole variant table (including coal, which deepslate excludes) unreachable.
- Dormant lizards could be shoved around and blocked movement despite being invisible.

## 1.0.0-alpha.1

Initial alpha. Buried/erupt/flee/burrow state machine, ore variants with per-instance tinting,
deepslate texture and variant weighting, pickaxe-bypasses-armor combat, ore drops, knockback and
hit sounds.
