# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Fabric mod for Minecraft 1.17.1 (Java 16, Mojang official mappings) that adds a single mob: the
Ore Lizard — a rare, invisible-while-dormant cave critter that erupts from the floor when a player
walks near, flees, then burrows back down. GeckoLib 3.0.32 drives its model/animations. This is the
1.17.1 port of the 1.20.1 build on `main` (GeckoLib 4.8.4), made from the 1.18.2 port (GeckoLib
3.0.80); behaviour is meant to be identical, and the `## 1.2.0+mc1.17.1` and `## 1.2.0+mc1.18.2`
sections of [CHANGELOG.md](CHANGELOG.md) together list everything that differs and why.

## Commands

```bash
./gradlew build          # compile + remap; jar lands in build/libs/orelizards-<version>.jar
./gradlew runClient      # launch a dev client (world data under run/)
./gradlew runServer      # launch a dev server
./gradlew genSources     # decompile Minecraft for navigating vanilla code
```

There is no test source set and no linter configured — verification is done by running the client
and playing. Use `/summon orelizards:ore_lizard` for a raw spawn, or the spawn egg (Miscellaneous
creative tab — 1.17.1 has no Spawn Eggs tab) when you need `finalizeSpawn` to run (variant
assignment, deepslate attribution, dormancy). `/summon` skips `finalizeSpawn`, so summoned lizards
are *not* representative.

Dependency versions live in [gradle.properties](gradle.properties), not `build.gradle`.

## Architecture

Split source sets, declared by hand in `build.gradle` (`sourceSets { client { ... } }`):

- [src/main/java](src/main/java) — common/server. Anything in here must not touch client classes.
- [src/client/java](src/client/java) — rendering only. Registered from
  [OreLizardsModClient.java](src/client/java/com/orelizards/OreLizardsModClient.java).

Both source sets are declared as one mod (`mods { orelizards { ... } }`) so they share a classpath
at runtime. The other versions get the `client` source set from Loom's `splitEnvironmentSourceSets()`;
that needs Loom's split client-only/common Minecraft jars, which only exist for versions with a
bundled server jar (1.18+), so on 1.17.1 Loom refuses ("Only Minecraft versions using a bundled
server jar can be split") and the source set compiles against the merged jar instead. The split is
therefore a convention here, not something the compiler enforces — a client import in `src/main`
would compile fine and crash a dedicated server.

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

**How a GeckoLib 3 layer draws only two bones.** A `GeoLayerRenderer` has no per-bone hook; its
single `render` runs after the body has been written and re-renders the *whole* model. So
`OreTintLayer` strips the cubes off every bone that isn't `shards`/`eyes`, renders the model twice
more through `IGeoRenderer.render`, and puts them back. GeckoLib 3.0.32 has no `setCubesHidden`
(later 3.0.x builds do), only `setHidden` — which takes the bone's children with it, and both glowing
bones are children of bones that must be skipped — so the layer swaps the public `childCubes` list
for an empty one and parks the original until the `finally`. The baked `GeoModel` is shared by every
lizard on screen, hence the `finally`. GeckoLib 3 runs layers inside the entity's model transform,
so no bone matrices need carrying over.

**Never request a different render type's buffer partway through the bone recursion.** Only a
fixed set of render types get their own `BufferBuilder` in `RenderBuffers`; everything else shares
one. Asking for a second type mid-recursion ends the in-progress batch and re-begins the shared
builder under the new type, so every bone drawn *after* that one inherits it — which showed up on
1.20.1 as the lizard's tail and legs rendering fullbright and see-through. That is why the tint and
glow passes live in a layer, which GeckoLib 3 invokes after `render` has finished the body, and not
in an override of `renderRecursively`.

**GeckoLib 3 runs layers for invisible entities.** `GeoEntityRenderer.render` draws the body at
alpha 0 when the entity is invisible to the local player (the cutout shader discards it) and then
runs every layer anyway — there is no upstream skip like GeckoLib 4's. `OreTintLayer.render` returns
early on `isInvisible()` for that reason, and it is not optional: both of its passes draw at full
alpha, so a dormant lizard would otherwise show as a floating, glowing set of shards.

### GeckoLib asset contract

**Blockbench's keyframes are rewritten at build time, and must be.** The export writes each keyframe
as `{"post": {"vector": [x,y,z]}, "lerp_mode": "catmullrom"}`. GeckoLib 3's
`JsonKeyFrameUtils.getKeyFrameVector` only accepts a bare `[x,y,z]` array or `{"vector": [x,y,z]}`
(plus optional `easing`/`easingArgs`); it has no concept of `pre`, `post` or `lerp_mode` - neither
string exists anywhere in the jar. Given the newer shape it finds no vector, reads **zero**
keyframes, and animates nothing *silently*: the controller still reports the animation as playing
with its clock advancing, and the model just sits in its bind pose. That is the "statue" bug, and it
produces no log line of any kind. `processResources` in [build.gradle](build.gradle) therefore
collapses every keyframe to `{"vector": ...}` on the way into `build/resources`. Consequences:

- **Never "fix" the asset by hand.** `src/main/resources/.../ore_lizard.animation.json` is kept
  byte-identical to main's so a re-export can be copied straight across; the build does the rest.
- The downgrade is lossless for position and rotation - every `pre`/`post` pair the export emits
  holds the same vector - but catmullrom smoothing is dropped, so GeckoLib 3 interpolates linearly.
  Expect the walk cycle to read very slightly less rounded than on GeckoLib 4 versions.
- If a re-export ever introduces a keyframe shape the collapser doesn't know, it will pass through
  untouched and animate nothing, again silently. After any re-export, check the task's
  `GeckoLib 3 keyframe downgrade: collapsed N keyframe(s)` line and confirm N matches the number of
  keyframes in the file.

Three files must agree, and mismatches fail *silently* (log spam at most):

- Animation names in `new AnimationBuilder().addAnimation("...", loop)` must exactly match the
  top-level keys in [ore_lizard.animation.json](src/main/resources/assets/orelizards/animations/entity/ore_lizard.animation.json)
  (currently `idle`, `scuttle`, `burrow`, `appear` — bare names, no `animation.orelizard.` prefix).
- Bone names animated in the animation JSON must exist in
  [ore_lizard.geo.json](src/main/resources/assets/orelizards/geo/entity/ore_lizard.geo.json).
  Re-exporting the geometry without the animation (or vice versa) has already broken the tail once.
- Bone names in `OreTintLayer.GLOWING_BONES` must match the geo too.

After any Blockbench re-export, check all three.

GeckoLib 3 timing rules this mob depends on:

- **Controllers tick whenever the entity is rendered, invisible or not.** Unlike GeckoLib 4, an
  invisible entity still goes through `setLivingAnimations`, so a buried lizard's controller keeps
  processing (our predicate returns `PlayState.STOP` for it). Dormancy is still not represented as
  a held "buried" animation — the state machine drives everything.
- **An animation does not begin on its first frame.** GeckoLib first spends `transitionLengthTicks`
  blending into that frame from the model's current pose, and starts the animation's clock only
  afterwards. Any animation whose first frame is displaced from the rest pose — `appear` starts 0.81
  blocks underground — must therefore run with a zero-tick transition, or the model visibly travels
  into position first. `transitionLengthTicks` is a public field on the controller in GeckoLib 3.
  A zero-length transition is safe: `MathUtil.lerpValues` returns the end value outright when the
  transition length is 0, so the frame shown is the clip's own first frame.
- **There is no way to hold a last frame in GeckoLib 3.0.32.** It has no `ILoopType`;
  `addAnimation(name, Boolean loop)` is the whole API, and `false` is play-once: a finished one-shot
  stops its controller and the processor eases every bone back to rest over
  `AnimationData.resetTickLength` (default 1 tick). (3.0.80 declares a `HOLD_ON_LAST_FRAME` that
  does the same thing.) The burrow pose is held by setting that reset speed to 1200 ticks while
  DIGGING_DOWN (`HOLD_RESET_TICKS` in `OreLizardEntity`) and back to 1 in every other state; the
  `AnimationData` handed to `registerControllers` is captured into the predicate for that. Do not
  "simplify" this away.
- **GeckoLib 3.0.32's `core` sources ship inside the jar** (`software/bernie/geckolib3/core/**/*.java`
  next to the classes), so `AnimationController`, `AnimationProcessor`, `AnimationData` and
  `MathUtil` can be read directly with `unzip -p` instead of decompiled. Everything above was
  checked that way.

## Spawning

Registered in [ModEntities](src/main/java/com/orelizards/registry/ModEntities.java) as
`MobCategory.AMBIENT` (not `CREATURE` — `CREATURE`'s population cap is shared with all surface
animals and is effectively always full underground, so the mob would never get a spawn attempt).
Weight 1 (added to every overworld biome in [OreLizardsMod](src/main/java/com/orelizards/OreLizardsMod.java)),
plus a 30% rejection roll inside `canSpawn` because spawn weights are integers and 1 is the floor.

The placement rule (`ON_GROUND`, `MOTION_BLOCKING`, `OreLizardEntity::canSpawn`) is declared on
`FabricEntityTypeBuilder.createMob().spawnRestriction(...)` because `SpawnPlacements.register` is
private in 1.17.1; Fabric's builder reaches it through an accessor.

Spawn rules in `canSpawn`: `Y < 50`, at least 8 blocks below the `WORLD_SURFACE` heightmap, on
`BASE_STONE_OVERWORLD`. Depth-below-surface is used rather than a light check because it works
during worldgen before lighting exists and ignores player torches. Stone vs. deepslate is decided in
`finalizeSpawn` by `Y < -4` (the midpoint of the 1.18+ stone→deepslate blend band, kept for parity
and for datapack-extended worlds) **or** by the block underfoot being `minecraft:deepslate`. The
second test exists only on this version: a default 1.17.1 world ends at Y=0 and its deepslate is
blobs between Y=0 and Y=16 rather than a layer, so the threshold alone could never be met and every
lizard would be stone. It is the port's one documented behavioural deviation — see the changelog.

## Repo gotchas

- **GeckoLib 3.0.x does not remap to Mojang mappings as shipped.** `GeoProjectilesRenderer` has
  two methods that Mojmap gives the same name and descriptor (`getTextureLocation(Entity)` and the
  `EntityRenderer` override, intermediary `method_3931`), which Tiny Remapper treats as unfixable
  and Loom aborts on. `build.gradle` (`geckolibMojmapJar`) fetches the Modrinth artifact, strips
  that one delegating method with ASM at configuration time, caches the result under
  `.gradle/geckolib-mojmap/`, and points `modImplementation` at it. Delete that directory to redo
  the patch; bump the `-r1` suffix if the patch changes. It cannot go through a Gradle
  configuration (resolving one that early breaks Loom's repository setup) or Loom's
  `RemapperExtension` API (Loom loads those via its own classloader, which can't see build-script
  classes).
- `fabric.mod.json` depends on `geckolib3` — the mod id of the 3.x line — not `geckolib`, and on
  `fabric` — Fabric API's umbrella id in the 1.17 line — not `fabric-api`. Either wrong id makes
  Loader refuse to start with a "requires any version of X, which is missing" error.
- `libs/mclib-18.jar` and `libs/molang-18.jar` are checked in for the same reason `main` carries
  `libs/mclib-20.jar`: GeckoLib 3.0.32 imports `com.eliotlash.mclib`/`com.eliotlash.molang`
  unshaded (3.0.80 shades them) and only supplies them jar-in-jar, which Loom 1.17's dev classpath
  doesn't pick up. Both were extracted straight out of the GeckoLib jar's `META-INF/jars/`. Only
  `runClient`/`runServer` need them; `build` does not.
- GeckoLib is pulled through the Modrinth maven proxy by project/version ID to sidestep its
  group-id churn — the coordinate in `gradle.properties` is opaque on purpose.
- slf4j is usable on 1.17.1 despite the game logging through log4j: the dedicated server jar
  bundles `slf4j-api` and the log4j binding, and the client lists both as libraries. 1.16.x has
  neither, so that is where `OreLizardsMod.LOGGER` would have to become log4j.
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
- `ore-lizards/` is a stray embedded git repo (a gitlink, no `.gitmodules`) pointing at this same
  remote. Ignore it; don't edit anything inside it.
- `orelizards.mixins.json` is wired up but has no mixins yet.
- Keep [CHANGELOG.md](CHANGELOG.md) updated — it is maintained in detail, with the reasoning behind
  each change, and is the best record of why things are the way they are.
