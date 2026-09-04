# Changelog

## 1.3.0

Ore Lizards are now placed for you rather than left to chance. Everything below follows from one
measurement: vanilla spawning was working correctly and players still never met the mob.

### Added

- **An encounter director** (`EncounterDirector`), server-side, that tracks how long each player
  spends genuinely exploring underground and, on a randomised 20-60 minute budget, places one
  dormant lizard ahead on their path, in the cave they are standing in, so they walk into it.

  The motivation was measured, not guessed. A headless run against real worldgen produced **43 valid
  lizard placements per 400,000 simulated attempts**, present in the plains, dripstone-cave and
  lush-cave spawn lists - which is exactly what a weight of 1 in a rare category is meant to look
  like. The registration, the placement predicate and the biome entries were all correct. Spawning
  worked; discovery did not, for four reasons that compound. `MobCategory.AMBIENT` allots roughly
  **15 spawn slots across the ~289 loaded chunks** around a player and shares them with bats. A
  dormant lizard is invisible, silent and emits no particles, so it has no discovery affordance
  beyond being stood next to. The wake radius is 5 blocks. And worldgen puts some lizards inside
  sealed pockets of stone, where they are unreachable forever while still holding a cap slot. No
  weight fixes any of that, because a spawn weight cannot express "somewhere the player will
  actually walk".

  The cadence is deliberately wide rather than tight. A narrow band produces a rhythm players
  pattern-match, and the moment somebody works out the interval, every encounter they have already
  had retroactively reads as scripted; a 3x spread cannot be felt as a schedule. The first budget of
  each server run is seeded with a uniform 0-20 minute head start, so a short session is not a
  guaranteed miss - expected first encounter is around 30 minutes of underground time rather than
  40, while the long-run rate is unchanged. Underground time only accrues while the player is
  actually moving (0.5 blocks/s, so sneaking counts and AFK does not) and actually below ground, so
  the budget measures exploring rather than wall-clock time.

  Placement is a 16-direction sweep from 32 blocks inwards, the same shape `FleeAndBurrowGoal` uses
  to flee, scored on how well the candidate lines up with the player's smoothed heading, how close
  it is to 24 blocks out, how far it is vertically, and whether it sits 2-4 blocks *off* the path
  line. That lateral offset is not decoration: the trigger range is still 5 blocks, so a head-on
  placement means a sprinting player is on top of the lizard before the 20-tick `appear` animation
  has finished. The eruption has to read as something coming at them from the side.

  Candidates are rejected in cost order, and the cheap filters matter more than the expensive one. A
  candidate within 12 blocks of anywhere the player has recently stood is dropped, because the tell
  a sight test cannot catch is "I mined that floor twenty seconds ago and it was solid" - and that
  filter also quietly covers walking backwards and retracing a passage. Chunks with more than ten
  minutes of accumulated inhabited time are dropped as explored ground or somebody's base. Only then
  is line of sight tested, and only lazily, best candidate first. Every one of those reads is gated
  behind `hasChunksAt`, because `getBlockState`, `getHeight` and `clip` all generate the chunk on the
  server thread if it is missing.

  **The sight test is the opposite of what was planned.** The design said "never in the player's
  line of sight", on the theory that the player should walk into the encounter rather than watch it
  appear on screen. The first playtest placed two lizards, and the player saw neither: both landed
  hidden from view, both were underwater - one under three blocks of it - and both drowned within
  seconds of being placed. That is not two coincidences. Underground, "16-32 blocks away and out of
  view" is very often exactly "in a different cave pocket behind a wall", which is to say somewhere
  the player will never walk, and a flooded pocket is as hidden as any. Concealment was also buying
  nothing, because `finalizeSpawn` runs `setInvisible(true)` before `addFreshEntity`, so no client is
  ever sent a visible frame wherever the lizard goes. A candidate is therefore now valid only if a
  ray from the player's eye to the site is *clear*: `Level.clip` with `ClipContext.Block.VISUAL` and
  `ClipContext.Fluid.NONE` must return `MISS`. Open air between the two is the cheapest available
  proof that the site is in the player's cave. `VISUAL` rather than `COLLIDER` because `COLLIDER`
  reports glass as a wall, and a site seen through a window is plainly reachable; `Fluid.NONE` so a
  flooded stretch of floor between the player and a dry ledge does not hide it. The look direction
  is not consulted - the alignment score already prefers ahead, and a passage behind the player is
  as much their cave as one in front - and the behind-the-eye-plane pre-filter that went with the
  old rule is gone. An unloaded chunk anywhere along the ray still rejects the candidate.

  A site must also be dry, and that is now enforced where the floor is found rather than only
  where it is accepted. Vanilla's `LiquidBlock.isPathfindable` is `!fluid.is(LAVA)` regardless of
  the path type asked for, so `CaveTerrain.isStandable` was passing a column of water over stone as
  walkable floor; it now requires an empty fluid state at the feet and the head as well.
  `isDirectorSiteValid` keeps its own fluid check as belt-and-braces, so the placement rule is safe
  whichever floor-finder feeds it. `FleeAndBurrowGoal` shares `isStandable` and gains from the
  change: its sweep used to be perfectly willing to send a fleeing lizard *into* a pool, where a
  0.6-block mob wades and reads as having given up. A dormant lizard placed underwater takes its
  first drowning tick 320 ticks (16 s) after placement and is dead by 400, which is what turned
  the playtest's placements into the accounting problem below.

  Each player has at most one pending lizard, and the order its fate is decided in matters. The
  plan checked "has it left BURIED" *before* "is it alive", so that erupting a lizard and then
  killing it would count as a hit. The playtest showed what else that ordering counted: a drowning
  lizard's final damage tick goes through `panicFromDamageIfDormant`, which found a survival player
  within 16 blocks - through a wall - and flipped the corpse-to-be into FLEEING, so the next sample
  saw "not dormant" and recorded a delivered encounter nobody had. `!isAlive()` is now checked first
  and is a miss in any state; only a *living* lizard that has left BURIED counts as delivered. The
  case that gives up - a player killing the lizard inside the one-second window between eruption and
  the next sample - is rare (10 HP behind armour, in under 20 ticks) and harmless when it happens,
  since the abandon path leaves a non-dormant lizard alone and merely refunds the player five
  minutes of budget for an encounter they in fact had. Otherwise a pending lizard is abandoned on a
  dimension change, a three-minute lease, or the player getting 48 blocks away. Abandoning culls the
  lizard and refunds the budget to five minutes short of its threshold, so a miss costs about five
  minutes rather than another full wait. The guarantee that follows is the entire point: even if
  every single placement were missed, an armed player receives a fresh attempt every five
  underground minutes indefinitely, where the measured status quo was never.

  The debug lines were sharpened along the way: the sweep summary reports all four disjoint
  outcomes per direction (kept, recently visited, explored chunk, no valid floor - "5 candidates, 5
  rejected" had read as a contradiction), every candidate logs its sight-check outcome and where
  the ray was blocked, and every abandon reason names its branch, the lizard's state, distance and
  age, so a lizard that died in the floor is told apart from one that was unloaded or one that
  panicked out of the ground before dying.

  Three system properties, read once at startup and wired commented-out into `build.gradle` beside
  the existing `geckolib.disable_examples`, make this testable in a single sitting:
  `orelizards.director.budgetSeconds` collapses the whole 20-60 minute loop into seconds,
  `orelizards.director.debug` logs every decision including a running hit/miss tally, and
  `orelizards.director.skipSightCheck` accepts candidates without the line-of-sight test, to
  exercise the sweep on its own. That tally exists because the hit rate is the one number the
  cadence arithmetic cannot derive from the code, and it is what the budget bounds should be retuned
  against.

- **`CaveTerrain`**, holding the `findFloor` / `isStandable` pair that used to be private to
  `FleeAndBurrowGoal`. The director needs the same answer, and a spawn director reaching into an AI
  goal is the wrong dependency direction. It also confines the `isPathfindable` arity split (three
  arguments up to 1.20.4, one from 1.20.6) to a single file for the port branches. One rule was
  added on the way through - a standable block must be dry, see above - and otherwise only the
  shared `MutableBlockPos` cursor moved from an instance field into the calls.

- **`OreLizardEntity.spawnDormant`**, now the only supported way to create a lizard on the server. It
  goes through `EntityType.spawn`, and that ordering is load-bearing: `create`, then position, then
  `finalizeSpawn`, then add to the level. `finalizeSpawn` reads `blockPosition().getY()` to pick the
  deepslate flag and the ore variant, so constructing the entity and calling `finalizeSpawn` yourself
  hands back a stone coal lizard wherever you put it, Y=-50 deepslate included. This mod has shipped
  that bug once already.

### Changed

- **Natural spawning is disabled**, behind `NATURAL_SPAWNING_ENABLED = false` rather than deleted.
  Nothing was wrong with the code; it simply cannot express what the mob needs, and keeping it makes
  the comparison one boolean away. Both registrations sit inside the guard, and the comment there
  records why they have to move together: removing only `SpawnPlacements.register` makes the mob
  spawn *more*, not less, and with none of the depth or block rules, because for a type with no
  registered placement data `SpawnPlacements.checkSpawnRules` returns `true` and `getPlacementType`
  returns `NO_RESTRICTIONS`. The biome entry is what makes the mob a spawn candidate at all; the
  placement registration is only the filter applied afterwards.

- **A dormant lizard only erupts for a player it can see.** `tickBuried` finds the nearest survival
  player within the 5-block trigger range as before, and now also requires
  `LivingEntity.hasLineOfSight` to that player before erupting; the nearest-player fallback in
  `panicFromDamageIfDormant` applies the same rule when the damage had nobody behind it. The trigger
  is a sphere, and underground a 5-block sphere routinely reaches through a wall into the next
  pocket: a lizard sealed in stone four blocks away would erupt and flee where nobody could see it,
  spending the encounter on nothing. The director's first playtest lost placements to exactly this,
  but the rule lives in the entity because it protects natural spawns just the same. `hasLineOfSight`
  was chosen over `Mob.getSensing()` deliberately, and checked with `javap`: it builds its own
  `ClipContext` and calls `Level.clip` directly, with no reference to `Sensing`. That matters because
  a dormant lizard is now `NoAi`, and `Sensing` is only refreshed from `serverAiStep`, so a cached
  answer would be stale for as long as the lizard was buried. Older branches spell the method
  `canSee`.

- **A dormant lizard no longer runs its AI.** `becomeDormant` sets `setNoAi(true)` and
  both routes out of BURIED (`beginErupting`, and `beginFleeing` for the panic-from-damage path)
  clear it, so the hours a lizard spends buried no longer cost a sensing pass, a
  goal-selector tick and a navigation tick each. `FleeAndBurrowGoal` is inert without a flee target
  and the two look goals only matter while the mob is visible, so nothing was being achieved by any
  of it. Three things this deliberately does not touch, each of which would have been a regression:
  `tickBuried` runs from the entity's own `tick()` override, so proximity triggering is unaffected;
  `checkDespawn` is called by `ServerLevel` directly rather than from the AI step, so dormant lizards
  are still culled; and falling is governed by `NoGravity`, a separate flag.

- **`DORMANT_DESPAWN_RADIUS` drops from 128 to 48, and the "nearest player is still underground"
  keep-clause is gone.** Both were written to protect a rare natural spawn from being culled out of a
  cave somebody was working through. Under the director the incentive inverts: every lizard in the
  world was deliberately placed a short walk ahead of one specific player, so both clauses are true
  by construction for exactly the lizards that most need collecting, and an unencountered one would
  be effectively immortal - and a leftover like that suppresses the next placement through the
  director's own nearby-lizard check. 48 matches the director's abandon radius so the two cleanup
  paths agree on when an encounter has been walked away from instead of each waiting on the other.
  `setPersistenceRequired()` was considered and rejected for the director's own placements: it
  short-circuits `checkDespawn` outright, which would make any lost lizard permanent.

- **`MobCategory.AMBIENT` stays, but for a different reason.** The original justification was purely
  about population caps, and with natural spawning off that argument is moot. The category is kept
  because it is baked into the registered `EntityType`, it is what `/data` and mob-cap tooling report,
  and the obvious alternative - `MISC` - is wrong on its own terms, being the category for entities
  that aren't `Mob`s. Only the comment changed.

### Removed

- **`OreLizardEntity.canSpawn` and its 30% rejection roll.** The roll only ever existed because
  vanilla spawn weights are integers and ours was already at the floor of 1; the director sets its
  cadence in minutes, so a dice roll on top would add nothing but noise. The rules themselves survive
  as `isDirectorSiteValid` (Y < 50, at least 8 blocks below the `WORLD_SURFACE` heightmap, on
  `BASE_STONE_OVERWORLD`, not in a fluid), which the disabled `SpawnPlacements.register` now reaches through a
  lambda. The method did not survive, because its signature names `MobSpawnType` - which is
  `EntitySpawnReason` from 1.21.3 on - and that would drag a per-version type into a director call
  path that is otherwise identical on all twenty branches. A lambda's parameter types are inferred,
  so they never have to be written down.

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
