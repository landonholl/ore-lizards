# Changelog

## 1.2.1

A tuning pass: the lizard moves at the speed it was always meant to, and its walk cycle has a body
sway to go with its legs.

### Changed

- **Movement speed reduced by a third**, from a base `MOVEMENT_SPEED` of 0.3 to 0.199. The cut is
  made on the base attribute rather than on `FLEE_SPEED_BONUS`, which is the other place a speed
  change could go. That boost is a `MULTIPLY_TOTAL` modifier, so lowering the base carries the
  reduction into the flee as well: fleeing goes from 0.5775 to 0.383, down by the same third, while
  the 1.925x ratio between fleeing and walking is left intact. Cutting the boost instead would have
  slowed only the escape and left the mob moving at full speed everywhere else, flattening the
  difference between a lizard that has been startled and one that is merely wandering. Nothing
  downstream needed retuning: `FleeAndBurrowGoal`'s scan distances are in blocks and its repath
  interval is in ticks, so a slower lizard simply covers less ground between repaths, and
  `playStepSound` is paced by distance travelled so the scuttle sound slows to match on its own.
- **`scuttle` re-exported with a body rotation channel.** The walk cycle previously moved only the
  head, legs and tail; the body itself stayed rigid, which read as the lizard gliding while its legs
  worked. The re-export keys `body` at the same eighth-second beats as the legs, so the torso now
  rocks with the stride. Nothing else in the file changed - `appear`, `burrow` and `idle` are
  byte-for-byte the same, the geometry was not re-exported, and every bone the new channel touches
  exists in the geo (checked, per the asset contract in CLAUDE.md).

## 1.2.0+mc1.17.1

A port of 1.2.0 to Minecraft 1.17.1, built from the 1.18.2 port rather than from 1.20.1 directly.
1.17.1 is served by GeckoLib **3.0.32**, the same API generation as the 3.0.80 the 1.18.2 build uses
but a few months older, so everything the `## 1.2.0+mc1.18.2` section says about GeckoLib 3 - the
layer that re-renders the model for the tint and glow passes, the reset-speed hold on the burrow
pose, the layer checking `isInvisible()` itself, the `geckolib3` mod id, spawn placement on Fabric's
entity builder, the Miscellaneous creative tab, and the Mojmap patch in `build.gradle` - applies
here unchanged and is not repeated. The mob is still meant to behave exactly as it does on 1.20.1;
this section lists only what 1.17.1 itself forced, and the one place it could not be matched.

### Changed

- **Deepslate attribution also samples the block underfoot, because 1.17.1 has no deepslate
  layer to read a depth against.** On 1.18+ the rule is `Y < -4`, the midpoint of the band where
  worldgen blends stone into deepslate, and it is exact there because depth is what decides the
  surrounding rock. A default 1.17.1 world ends at Y=0, and its deepslate occurs only as blobs
  scattered through the stone between Y=0 and Y=16, so kept as written the threshold could never be
  met: every natural spawn would be a stone lizard, and the deepslate skin, the deepslate dig and
  step sounds and the diamond/emerald-weighted variant table would all be dead code on this version.
  `finalizeSpawn` therefore treats the lizard as deepslate when *either* the threshold holds - kept
  for worlds a datapack has extended downwards, where 1.17's experimental worldgen lays the same
  layer, and for parity with the other versions - *or* the block it is sitting on is
  `minecraft:deepslate`. `canSpawn` already requires that block to be base stone, so for a natural
  spawn the second test is precisely "did it land on a deepslate blob". Two consequences worth
  knowing: deepslate lizards are rarer here than on 1.18+, since blobs are a small fraction of the
  stone at those depths rather than all of it; and a spawn egg used on a deepslate block gives a
  deepslate lizard at any height, where on 1.18+ it would not above Y=-4. This is the one deliberate
  behavioural deviation in the port.
- **Animations are queued with GeckoLib 3.0.32's plain loop boolean.** 3.0.32 predates `ILoopType`
  altogether; `AnimationBuilder.addAnimation` takes `(name, Boolean loop)`, so `scuttle` is queued
  with `true` and `burrow`/`appear` with `false`. `false` is play-once, which does exactly what
  3.0.80's inert `HOLD_ON_LAST_FRAME` does - the controller stops at the end of the clip and the
  processor starts easing the bones back to rest over `AnimationData.resetTickLength` - so the
  reset-speed hold from the 1.18.2 port carries over unchanged. Both were re-checked against the
  `AnimationController` and `AnimationProcessor` sources bundled in the 3.0.32 jar: a stopped
  controller queues no bone points, the reset lerp reads `getResetSpeed()` every frame, and with a
  zero transition length `MathUtil.lerpValues` still returns the clip's own first-frame value on the
  first frame and starts the clock at tick 0 on the next.
- **The layer strips each bone's cube list instead of setting a cube-hidden flag.** 3.0.32's
  `GeoBone` has only `setHidden`, which takes the bone's children with it - and both glowing bones
  are children of bones that must be skipped - so the `setCubesHidden` the 1.18.2 port relies on is
  not there. `childCubes` is a public field that `renderRecursively` simply iterates after applying
  the bone transform, so `OreTintLayer` swaps it for an empty list on every non-glowing bone before
  its two passes and puts the originals back in a `finally`. Same geometry drawn, same shared baked
  model restored before anyone else renders from it.

### Build

- **The client source set is declared by hand.** Loom's `splitEnvironmentSourceSets()` needs the
  split client-only/common Minecraft jars, which Loom only produces for versions with a bundled
  server jar (1.18+); on 1.17.1 configuration fails with "Only Minecraft versions using a bundled
  server jar can be split". `build.gradle` now declares `sourceSets.client` itself against the merged
  jar (main's classpath plus main's output), registers it in `loom.mods` and on the `client` run, and
  adds it to `jar` and `sourcesJar`. The layout and the jar's contents are identical to the other
  versions; what is lost is only the compiler enforcing that `src/main` never touches a client class.
- **`libs/mclib-18.jar` and `libs/molang-18.jar` replace `main`'s `libs/mclib-20.jar`.** GeckoLib
  3.0.32 does not shade its maths libraries: `AnimationController` and `AnimationProcessor` import
  `com.eliotlash.mclib` and `com.eliotlash.molang` directly and the jar supplies both only as
  jar-in-jar under `META-INF/jars/`. Loom 1.17's dev classpath does not pick nested jars up (its
  remapped-mods cache holds nothing but the GeckoLib jar itself), so both are extracted and put on
  the dev classpath with `implementation files(...)`. Dev-only, as before: the shipped mod carries
  neither, GeckoLib does.
- **`fabric.mod.json` depends on `fabric`, not `fabric-api`.** Fabric API's umbrella mod was still
  registered under the id `fabric` in the 1.17 line (0.46.1+1.17 here; GeckoLib 3.0.32's own
  manifest depends on it by that name too), and the `fabric-api` id the other versions declare only
  arrived with the 1.18 releases. With `fabric-api` Fabric Loader refuses to start - "requires any
  version of fabric-api, which is missing" - exactly the way `geckolib` instead of `geckolib3` does.
- **Java 16.** `options.release = 16`, `JAVA_16` for the mixin config, `"java": ">=16"` in
  `fabric.mod.json`; the sources use nothing past that (arrow switches, `instanceof` patterns and
  `List.of` are all fine). `minecraft` is pinned to `1.17.1` and Fabric API is `0.46.1+1.17`. The
  Mojmap patch for `GeoProjectilesRenderer` applies to 3.0.32 unchanged - it has the same
  `getTextureLocation(Entity)`/`method_3931` pair - and was confirmed by the remap going through.

### Not verified

- Everything client-side was checked against GeckoLib 3.0.32's bytecode and bundled `core` sources,
  not in a running client: the cube-list swap, the tint and emissive passes, the zero-tick
  transition and the reset-speed hold. The headless dedicated server run covers registration,
  spawning, the state machine and drops only.
- The deepslate-blob attribution was checked by reading the worldgen, not by catching a lizard on a
  blob; a natural deepslate spawn on this version has not been observed.

A port of 1.2.0 to Minecraft 1.18.2. The mob is meant to behave exactly as it does on 1.20.1; this
section lists only what had to be done differently and why. The big one is the animation library:
1.18.2 is served by GeckoLib **3.0.80**, an earlier API generation than the 4.8.4 the 1.20.1 build is
written against, so everything that touches GeckoLib was re-implemented rather than translated.

### Changed

- **Holding the burrow pose is done with GeckoLib's bone reset speed, not a loop type.** GeckoLib
  3.0.80 declares `HOLD_ON_LAST_FRAME` but never implements it: the enum constant is built with the
  same `looping=false` flag as `PLAY_ONCE`, and neither `AnimationController` nor `AnimationProcessor`
  ever tests for it, so at the end of the 20-tick `burrow` clip the controller still stops and the
  processor starts easing every bone back to its rest pose - over `AnimationData.resetTickLength`,
  which defaults to a single tick. That is the 1.1.0 "lizard pops back above ground" bug all over
  again. The port therefore leaves the loop type on the animations for intent and, while the lizard
  is DIGGING_DOWN, sets the reset speed to 1200 ticks: the easing still begins the moment the clip
  ends, but at 1/1200th of the way per tick the body has moved well under a percent of its two
  blocks by the time the state discards the entity ten ticks later. Every other state puts the
  default of 1 tick back before anything of its own can stop, and nothing follows DIGGING_DOWN, so
  the slow reset cannot leak into the walk cycle. The `AnimationData` that owns the setting is the
  one handed to `registerControllers`, which is captured into the predicate for the purpose.
- **The tint and glow passes re-render the model with every other bone's cubes hidden.** A GeckoLib
  3 layer has no per-bone hook; its single `render` runs after the body has been written and can
  only draw the whole model again. So instead of capturing bone matrices during the body pass, the
  layer hides the *cubes* of every bone that isn't `shards` or `eyes` and renders the model twice
  more through `IGeoRenderer.render`: once with the variant tint through the body's own render type,
  once fullbright through `RenderType.eyes` at 0.7x the tint. Cubes rather than bones, because
  GeckoLib 3's `renderRecursively` skips a hidden bone's children too and both glowing bones sit
  under bones that must be skipped. GeckoLib 3 also runs its layers inside the entity's own model
  transform, so no matrix bookkeeping is needed for the bones to land where the body pass put them.
  The rule about only requesting a different render type's buffer after the body pass is done still
  holds and is still why this lives in a layer rather than a `renderRecursively` override.
- **The layer now checks `isInvisible()` itself, for both passes.** GeckoLib 4 skips the whole
  render for an invisible entity, so on 1.20.1 the check was belt-and-braces. GeckoLib 3 instead
  draws the body at alpha 0 (the cutout shader discards it) and then runs every layer regardless.
  Both of the layer's passes draw at full alpha, so without the check a dormant lizard would show as
  a floating, glowing set of shards - the one failure that breaks the mob outright.
- **Spawn placement is declared on Fabric's entity type builder, not in `onInitialize`.**
  `SpawnPlacements.register` is private in 1.18.2 (Mojang only opened it in 1.19), so the rule -
  `ON_GROUND`, `MOTION_BLOCKING`, `OreLizardEntity::canSpawn`, unchanged - moved to
  `FabricEntityTypeBuilder.createMob().spawnRestriction(...)` in `ModEntities`, which reaches the
  method through Fabric's accessor.
- **The spawn egg sits in the Miscellaneous creative tab.** 1.18.2 has neither a Spawn Eggs tab nor
  the `ItemGroupEvents` API; vanilla's own eggs live in Miscellaneous, and an item names its tab on
  its `Item.Properties`.
- **`fabric.mod.json` depends on `geckolib3`, not `geckolib`.** GeckoLib 3.x registers under the mod
  id `geckolib3`; declaring `geckolib` would make Fabric Loader refuse to start. `minecraft` is
  pinned to `1.18.2`, the only version GeckoLib 3.0.80 declares for itself.

### Build

- **GeckoLib 3.0.x cannot be remapped to Mojang mappings as shipped, so `build.gradle` patches one
  method out of it first.** `GeoProjectilesRenderer` declares both its own
  `IGeoRenderer.getTextureLocation(Entity)` and an override of `EntityRenderer.getTextureLocation`
  (intermediary `method_3931`). Yarn keeps those apart; Mojmap gives both the same name and
  descriptor, which Tiny Remapper reports as an unfixable conflict and Loom aborts on ("Failed to
  remap 48 mods"). Loom exposes no ignore-conflicts switch for dependency remapping, and its
  `RemapperExtension` API loads extension classes through Loom's own classloader, where a class
  defined in the build script is not visible. The override is a one-line delegate to the other
  method, so `build.gradle` rewrites the Modrinth artifact with that single method removed, at
  configuration time (Loom remaps mod dependencies while the project is configured), and points
  `modImplementation` at the result under `.gradle/geckolib-mojmap/`. Once remapped the surviving
  method *is* the override, so the class is unchanged in behaviour. The artifact is fetched by hand
  (Gradle's module cache first, then the Modrinth maven) because resolving a configuration that
  early freezes every repository descriptor and Loom then fails setting up its own. GeckoLib 3.1+
  renamed the method to `getTextureResource`, so only 3.0.x needs this.
- **`libs/mclib-20.jar` and its dependency line are gone.** GeckoLib 3.0.80 shades mclib into
  `software.bernie.shadowed.eliotlash.mclib` and nothing in it references the copy it also nests as
  `META-INF/jars/mclib-20.jar`, so the Loom jar-in-jar workaround the 1.20.1 build needs has nothing
  to work around here.

### Not verified

- Everything client-side was checked against GeckoLib's bytecode and bundled `core` sources, not in
  a running client: the tint and emissive passes, the zero-tick transition (GeckoLib 3's
  `MathUtil.lerpValues` returns the end value outright when the transition length is 0, so the first
  frame shown is the clip's own first frame), and the reset-speed hold. The headless dedicated
  server run covers registration, spawning, the state machine and drops only.

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
