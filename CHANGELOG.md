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

## 1.2.0+mc1.21.3

A port of 1.2.0 to Minecraft 1.21.3 (Fabric Loader 0.19.5, Fabric API 0.114.1, GeckoLib 4.7.1,
Java 21), built from the 1.21.4 port below rather than from 1.20.1 directly. 1.21.2 is the release
where the API changes that section describes arrived - `hurtServer`, `EntitySpawnReason`,
`EntityType.Builder.build` taking the registry key, `Item.Properties.setId`, the `ToolMaterial`-based
"iron or better" pickaxe check - so almost all of it applies to 1.21.3 unchanged, as does everything in
the `## 1.2.0+mc1.21.1` section. GeckoLib 4.7.1 for 1.21.3 has the same `GeoModel`, `GeoRenderLayer`
and `renderCubesOfBone` signatures as 4.8.5 for 1.21.4 and, like it, no nested `mclib` jar, so the
client code is byte-for-byte the 1.21.4 code. The mob is meant to behave exactly as it does on 1.20.1.
Below is only where this branch differs from the 1.21.4 port.

### Changed

- **The spawn egg's colours are back on the item, as on 1.20.1.** Item model definitions
  (`assets/<namespace>/items/<id>.json`) are a 1.21.4 addition; 1.21.3 has no such folder, and its
  `SpawnEggItem` still takes the body and highlight colours as constructor arguments, which the client
  applies to the two layers of `template_spawn_egg` through `ItemColors` and `SpawnEggItem.getColor`
  exactly as 1.20.1 does. So `ModItems` passes `0x6E6E6E` / `0x63E1FF` in Java again, and the
  `items/ore_lizard_spawn_egg.json` the 1.21.4 port added is gone from this branch - 1.21.3 would never
  read it, and leaving it in place would only suggest that it does something. `Item.Properties.setId`
  is still required; that requirement is 1.21.2's, not 1.21.4's.

### Not verified

- **Rendering, and the spawn egg's appearance.** Compiled against 1.21.3 + GeckoLib 4.7.1 and
  smoke-tested on a headless dedicated server only. Everything the 1.21.4 section lists as unverified -
  the tint pass, the emissive pass, the buried-lizard invisibility skip, the `appear`/`burrow`
  transition timing, the egg's tint, and the `PoseStack.Pose.trustedNormals` detail (the flag exists on
  1.21.3 too) - is unverified here for the same reason.

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
