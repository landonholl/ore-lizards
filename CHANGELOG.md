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

## 1.2.0+mc1.16.5

A port of 1.2.0 to Minecraft 1.16.5 - the oldest version the mod targets, and the one furthest
from the 1.20.1 build on `main`. The mob is meant to behave exactly as it does there; this section
lists only what had to be done differently and why. It is built on the 1.18.2 port (commit 50611fa),
which had already re-implemented everything GeckoLib-facing against GeckoLib 3, and 1.16.5 is served
by GeckoLib **3.0.107** - the same API generation - so that work carries over and was re-verified
against 3.0.107's bytecode and bundled `core` sources rather than redone. Three things 1.16.5 simply
does not have account for the rest: deepslate, raw ores and copper, and any Java newer than 8.

### Changed

- **There are no deepslate lizards.** 1.16.5's world floors at Y=0 and is stone all the way down;
  deepslate, its sounds and its texture arrive in 1.17/1.18. `finalizeSpawn` therefore no longer
  attributes by `Y < -4` - every lizard is a stone lizard, rolled from the uniform variant table -
  and the erupt/burrow, hurt and step sounds are `STONE_*` unconditionally, since `DEEPSLATE_*` does
  not exist to fall back to. The `DEEPSLATE` tracked data, its `"Deepslate"` NBT key,
  `isDeepslate()`, `OreVariant.randomDeepslate` and the deepslate texture are all kept as they are, so
  the save format, the client's texture selection and the variant table read identically to every
  other version; on this one the flag is simply never true, and `finalizeSpawn` sets it false
  explicitly so spawn and load arrive at the same value by the same route.
- **Iron and gold lizards drop ingots, and there is no copper lizard.** Raw ores and copper are 1.17
  additions. `IRON` drops `IRON_INGOT` and `GOLD` drops `GOLD_INGOT` - what a furnace would have made
  of the raw ore, and the nearest thing to "the metal, unrefined" that exists here - in the same 4-6
  and 2-4 (2% for 6) counts as before. `COPPER` is removed from `OreVariant` outright, which leaves
  seven variants in the uniform roll instead of eight. The tint colours are deliberately left as the
  raw-block means measured for 1.20.1 rather than re-derived from the ingot textures, so an iron or
  gold lizard is the same colour on every version. A save written by a later version that names
  `COPPER` reads back through `OreVariant.byName` as unknown and keeps the default variant - the
  existing rule for any unrecognised name, now with a concrete case.
- **The spawn band is unchanged and still means something with a Y=0 floor.** `Y < 50` and "at least
  8 below `WORLD_SURFACE`" carry over verbatim; the effective band is Y 0-49 instead of reaching
  below zero, and `BASE_STONE_OVERWORLD` exists in 1.16 with the same members (stone, granite,
  diorite, andesite). Nothing about the 70% roll, the AMBIENT category, the weight or the heightmap
  changed.

### Carried over from the 1.18.2 port

These were worked out for GeckoLib 3.0.80 and hold unchanged for 3.0.107, whose `AnimationController`,
`AnimationProcessor`, `ILoopType`, `GeoLayerRenderer` and `GeoEntityRenderer` behave the same way in
every respect that matters here. Listed so this section is complete against `main` on its own.

- **Holding the burrow pose is done with GeckoLib's bone reset speed, not a loop type.** GeckoLib 3
  declares `HOLD_ON_LAST_FRAME` but never implements it: the enum constant is built with the same
  `looping=false` flag as `PLAY_ONCE`, and neither `AnimationController` nor `AnimationProcessor`
  ever tests for it, so at the end of the 20-tick `burrow` clip the controller still stops and the
  processor starts easing every bone back to its rest pose over `AnimationData.resetTickLength`,
  which defaults to a single tick - the 1.1.0 "lizard pops back above ground" bug all over again.
  The loop type stays on the animations for intent and, while the lizard is DIGGING_DOWN, the reset
  speed is set to 1200 ticks: the easing still begins the moment the clip ends, but at 1/1200th of
  the way per tick the body has moved well under a percent of its two blocks by the time the state
  removes the entity ten ticks later. Every other state puts the default of 1 tick back before
  anything of its own can stop, and nothing follows DIGGING_DOWN, so the slow reset cannot leak into
  the walk cycle. The `AnimationData` that owns the setting is the one handed to
  `registerControllers`, which is captured into the predicate for the purpose.
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
- **The layer checks `isInvisible()` itself, for both passes.** GeckoLib 4 skips the whole render
  for an invisible entity, so on 1.20.1 the check was belt-and-braces. GeckoLib 3.0.107's
  `GeoEntityRenderer.render` skips only the body pass for an entity invisible to the local player
  (3.0.80 drew it at alpha 0 instead - same outcome) and then runs every layer regardless. Both of
  the layer's passes draw at full alpha, so without the check a dormant lizard would show as a
  floating, glowing set of shards - the one failure that breaks the mob outright.
- **Spawn placement is declared on Fabric's entity type builder, not in `onInitialize`.**
  `SpawnPlacements.register` is private in 1.16.5 as it is in 1.18.2 (Mojang only opened it in
  1.19), so the rule - `ON_GROUND`, `MOTION_BLOCKING`, `OreLizardEntity::canSpawn`, unchanged - lives
  on `FabricEntityTypeBuilder.createMob().spawnRestriction(...)` in `ModEntities`, which reaches the
  method through Fabric's accessor.
- **The spawn egg sits in the Miscellaneous creative tab.** 1.16.5 has neither a Spawn Eggs tab nor
  the `ItemGroupEvents` API; vanilla's own eggs live in Miscellaneous, and an item names its tab on
  its `Item.Properties`.
- **`fabric.mod.json` depends on `geckolib3`, not `geckolib`,** the mod id of the 3.x line;
  declaring `geckolib` would make Fabric Loader refuse to start. `minecraft` is pinned to `1.16.5`
  (GeckoLib 3.0.107 declares `1.16.x` for itself; this is the only one of those it was built and
  tested against) and `java` to `>=8`.

### Build

- **Java 8.** `build.gradle` compiles with `--release 8` and source/target 1.8, `fabric.mod.json`
  asks for `java >=8`, and the (empty) mixin config is `JAVA_8`. The source was rewritten to Java 8
  syntax with identical semantics: the `tick()` state `switch` is the classic `case:`/`break` form,
  the `instanceof` patterns in `hurt`, `panicFromDamageIfDormant`, `isPickaxeHit` and the two
  `ServerLevel` checks are explicit casts, and `OreTintLayer.GLOWING_BONES` is
  `Collections.unmodifiableList(Arrays.asList(...))` in place of `List.of`. JDK 21's javac handles
  `--release 8` with a warning that 8 is obsolete, which is expected.
- **The two source sets are declared by hand; Loom's `splitEnvironmentSourceSets()` cannot be used.**
  That call switches Loom to its split client-only/common Minecraft jars, which only exist for
  versions that ship a bundled server jar (1.18+); on 1.16.5 Loom aborts setup with "Only Minecraft
  versions using a bundled server jar can be split". `build.gradle` therefore creates the `client`
  source set itself on top of the merged jar, mirroring what Loom's split mode does internally: it
  compiles against `main`, is grouped with it as one mod, is packed into the jar and sources jar,
  and `runClient` launches from it. The directory layout is unchanged. What is lost is only the
  compile-time guarantee that `src/main` never touches a client class - the merged jar has them all -
  so that rule is now kept by review.
- **GeckoLib 3.0.x still needs its Mojmap patch, re-pointed at 3.0.107's package.** Same conflict as
  on 1.18.2 - `GeoProjectilesRenderer` declares both `getTextureLocation(Entity)` and the
  `EntityRenderer` override (intermediary `method_3931`) with descriptors that collide under Mojang
  names - and the same fix: `build.gradle` fetches the Modrinth artifact and strips that one
  delegating method with ASM at configuration time. But 3.0.107 keeps its renderers in
  `software.bernie.geckolib3.renderer.geo` (singular) where 3.0.80 had `renderers.geo`, so the class
  path the patch targets changed, as did every renderer import in `src/client`. Every class in that
  package was checked: only `GeoProjectilesRenderer` collides (`GeoEntityRenderer`'s
  `getTextureLocation(T)` erases to `LivingEntity` and so does not).
- **`libs/mclib-20.jar` and its dependency line stay gone.** 3.0.107 shades mclib into
  `software.bernie.shadowed.eliotlash.mclib` and nests no jar-in-jar copy at all.
- **`fabric.mod.json` depends on `fabric`, not `fabric-api`.** Fabric API 0.42.0+1.16 registers
  under the mod id `fabric`; the `fabric-api` id that every later version of the mod declares came
  with a later Fabric API, and Fabric Loader refuses to start a mod whose declared dependency is
  absent. The headless dedicated-server run caught this - the build itself cannot, since mod ids are
  only checked at launch. (GeckoLib 3.0.107's own manifest depends on `fabric >=0.28.0`, which is the
  same id.)
- **Mechanical substitutions for APIs 1.16.5 lacks**, listed because the brief for this port asks for
  them rather than because any changes behaviour: `Tag.TAG_STRING` (1.17+) is the literal `8`,
  named `TAG_TYPE_STRING`; `Entity.discard()` is `remove()`; `DefaultRandomPos.getPosAway` is
  `RandomPos.getPosAvoid` (same ten-random-samples fallback); `LightTexture.FULL_BRIGHT` is
  `LightTexture.pack(15, 15)`; the logger is Log4j's, since Minecraft only ships SLF4J from 1.18;
  and Fabric API 0.42's `EntityRendererRegistry` lives in `fabric-renderer-registries-v1` under
  `net.fabricmc.fabric.api.client.rendereregistry.v1`, is instance-based, and hands the renderer an
  `EntityRenderDispatcher` rather than a provider context.

### Not verified

- Everything client-side was checked against GeckoLib 3.0.107's bytecode and bundled `core` sources,
  not in a running client: the tint and emissive passes, the zero-tick transition (`MathUtil.lerpValues`
  returns the end value outright when the transition length is 0), and the reset-speed hold. The
  headless dedicated server run covers registration, spawning, the state machine and drops only.
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
