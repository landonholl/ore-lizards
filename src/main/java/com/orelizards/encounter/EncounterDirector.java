package com.orelizards.encounter;

import com.orelizards.OreLizardsMod;
import com.orelizards.entity.OreLizardEntity;
import com.orelizards.entity.ai.CaveTerrain;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Places dormant lizards where a player is about to walk, because vanilla spawning cannot.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The mob's spawn registration, placement predicate and biome entries were all measured and all
 * correct: a headless run against real worldgen produced <b>43 valid lizard placements per 400,000
 * simulated attempts</b>, present in the plains, dripstone-cave and lush-cave spawn lists. Spawning
 * worked. Discovery did not, and four things compounded to make it not:
 *
 * <ul>
 *   <li>{@code MobCategory.AMBIENT} allots roughly <b>15 spawn slots across the ~289 loaded
 *       chunks</b> around a player, and bats are already using them.</li>
 *   <li>A dormant lizard is invisible, silent and emits no particles - it has no discovery affordance
 *       at all beyond being stood next to.</li>
 *   <li>The wake radius is <b>5 blocks</b>, so a player has to pass almost directly over one.</li>
 *   <li>Worldgen happily puts lizards inside sealed pockets of stone, where they are unreachable
 *       forever while still holding a slot in that shared cap.</li>
 * </ul>
 *
 * <p>No amount of weight tuning fixes this, because a spawn weight cannot express "somewhere the
 * player will actually walk". So the director tracks how long each player spends genuinely
 * exploring underground and, on a randomised 20-60 minute budget, places one dormant lizard ahead
 * on their path, <em>in the same cave the player is standing in</em>, so that they walk into it.
 * Vanilla natural spawning is switched off in {@link OreLizardsMod} but kept in the source behind
 * a constant.
 *
 * <p>"Same cave" is proven by a clear line from the player's eye to the site. The first design
 * demanded the opposite - a site the player could <em>not</em> see - and the first playtest showed
 * why that was wrong twice over. Underground, "16-32 blocks away and hidden from view" is very
 * often exactly "in a different cave pocket behind a wall", so both placements of that session
 * landed somewhere the player could never have reached; both happened to be underwater, where the
 * lizard drowned within seconds and its death panic was scored as a successful encounter. And the
 * concealment bought nothing anyway: {@code finalizeSpawn} runs before {@code addFreshEntity}, so
 * the lizard is invisible before any client hears of it. Open air between the eye and the site is
 * the cheapest available proof that the player can walk there.
 *
 * <h2>Shape of the thing</h2>
 *
 * <p>All static, no instance, one {@code HashMap} keyed by player UUID. A plain {@code HashMap} is
 * correct rather than lucky: {@code END_SERVER_TICK} only ever fires on the server thread, and
 * nothing else touches the map.
 *
 * <p>Nothing is persisted to disk. The first time a UUID is seen in a server run its budget is
 * seeded with a uniform 0-{@value #JOIN_HEAD_START_MAX_TICKS}-tick head start, so a short session
 * still has a real chance of landing an encounter instead of always restarting the clock from zero.
 * Seeded once per run rather than per join, so it cannot be rerolled by relogging.
 *
 * <h2>Porting</h2>
 *
 * <p>This file is intended to be <b>source-identical on all 20 version branches</b>. That is why it
 * routes through {@code ServerTickEvents.END_SERVER_TICK} (not {@code END_WORLD_TICK}, which is
 * {@code END_LEVEL_TICK} on 26.x), {@code server.getAllLevels()} / {@code level.players()} (not a
 * {@code ServerPlayer} to {@code ServerLevel} accessor, and not the {@code Entity.level} field,
 * whose field-versus-method split moved), holds a <b>direct entity reference</b> for the pending
 * lizard (not a UUID plus {@code ServerLevel.getEntity}, which was renamed on 26.x), calls
 * {@code level.getRandom()} without ever naming its return type, and names no
 * {@code ResourceLocation}. It is also written in Java-8-compatible syntax so the 1.16.5 branch
 * takes it unmodified. Everything version-sensitive it needs lives behind
 * {@link OreLizardEntity#spawnDormant}, {@link OreLizardEntity#removeAsUnfound} and
 * {@link CaveTerrain#isStandable}.
 */
public final class EncounterDirector {
	// ---------------------------------------------------------------------------------------------
	// Cadence
	// ---------------------------------------------------------------------------------------------

	/**
	 * One second. The shortest window that still yields a usable heading from a position delta - at
	 * ten ticks a player rounding a corner reads as pure noise. It also makes the arithmetic honest:
	 * a sample adds exactly this many ticks to the budget, so underground time accrues 1:1 with real
	 * time and the budget bounds below are literally minutes.
	 */
	private static final int SAMPLE_INTERVAL_TICKS = 20;

	/**
	 * The placement sweep is the only expensive thing here, so at most one runs per tick across the
	 * whole server. This bounds the worst case regardless of player count: with fifty armed players
	 * the work does not fifty times, it queues.
	 */
	private static final int MAX_SWEEPS_PER_TICK = 1;

	/**
	 * 20 to 60 minutes of underground movement, mean 40. The band is deliberately <em>wide</em>. A
	 * narrow one produces a rhythm players pattern-match, and the moment somebody works out the
	 * interval, every encounter they have already had retroactively reads as scripted. A 3x spread
	 * cannot be felt as a schedule.
	 */
	private static final int ENCOUNTER_BUDGET_MIN_TICKS = 24000;
	private static final int ENCOUNTER_BUDGET_MAX_TICKS = 72000;

	/**
	 * What a missed placement costs. An abandoned encounter refunds the budget to five minutes short
	 * of its threshold, so the player is re-armed shortly rather than starting the whole wait again.
	 * With {@code p} = the probability a placement is walked into rather than abandoned, the
	 * expected interval works out at {@code 40 + 5*(1/p - 1)} minutes: 41.7 at p=0.75, 45.0 at p=0.5.
	 * Each miss pushes that particular wait out by about five minutes; the floor of the band does not
	 * move. {@code p} is the one number that cannot be read off the code, which is what the debug
	 * hit/miss tally is for.
	 */
	private static final int ABANDON_RETRY_TICKS = 6000;

	/** Uniform 0-20 minute seed per UUID per server run. See the class comment. */
	private static final int JOIN_HEAD_START_MAX_TICKS = 24000;

	/**
	 * 0.5 blocks per sample second, squared. Underground time only accrues while the player is
	 * actually going somewhere, which excludes AFK, and excludes standing in one spot building or
	 * strip-mining a face. Sneaking is 1.3 blocks/s and still counts comfortably.
	 */
	private static final double EXPLORING_MIN_MOVE_SQ = 0.25;

	/**
	 * Weight kept on the previous heading each sample, so roughly a three-second memory (0.6^3 is
	 * about 0.2). One sidestep to dodge a skeleton must not swing the aim; a genuine turn down a side
	 * passage should be followed within a couple of seconds.
	 */
	private static final double HEADING_SMOOTHING = 0.6;

	/**
	 * Below this the heading is treated as noise and left alone rather than blended towards zero -
	 * a player standing still should keep pointing wherever they were last going, not lose the
	 * direction entirely.
	 */
	private static final double HEADING_MIN_MOVE_SQ = 1.0e-4;

	/** A heading shorter than this cannot be normalised into a direction; skip the sweep and retry. */
	private static final double HEADING_MIN_LENGTH = 1.0e-3;

	/** Three minutes. Only bites on a player who parks next to their own pending encounter. */
	private static final int PENDING_LEASE_TICKS = 3600;

	/**
	 * Matches {@code OreLizardEntity.DORMANT_DESPAWN_RADIUS}. The two cleanup paths - this one, which
	 * knows the lizard was placed for this player, and the entity's own despawn check, which does not
	 * - must agree on when an encounter has been walked away from, or they spend their time undoing
	 * each other.
	 */
	private static final double PENDING_ABANDON_DISTANCE = 48.0;
	private static final double PENDING_ABANDON_DISTANCE_SQ =
			PENDING_ABANDON_DISTANCE * PENDING_ABANDON_DISTANCE;

	/**
	 * Wider than the abandon radius on purpose: a leftover lizard that is already doomed - out past
	 * 48 blocks and about to be culled - should still suppress a new placement, so a player who
	 * doubles back does not find two.
	 */
	private static final double NEARBY_LIZARD_SCAN_RADIUS = 64.0;

	/** Ten seconds. How often an armed player with nowhere valid to place gets another look. */
	private static final int ARMED_RETRY_INTERVAL_TICKS = 200;

	/** Bounds map growth for a long-running server: an hour unseen and the entry goes. */
	private static final int STATE_EXPIRY_TICKS = 72000;
	private static final int STATE_PRUNE_INTERVAL_TICKS = 6000;

	// ---------------------------------------------------------------------------------------------
	// Placement search
	// ---------------------------------------------------------------------------------------------

	/** Same 16-way sweep, outermost ring inwards, as {@code FleeAndBurrowGoal} uses to flee. */
	private static final int PLACE_SCAN_DIRECTIONS = 16;

	/**
	 * The minimum is 3.2x the entity's own 5-block TRIGGER_RANGE, so a lizard can never erupt on the
	 * tick it is placed - which would read as it spawning in the player's face. The maximum is a
	 * quarter of the 128-block tracking range and about seven seconds of walking, near enough that
	 * the player's own chunks cover it and far enough that they have to get there.
	 */
	private static final int PLACE_MAX_DISTANCE = 32;
	private static final int PLACE_MIN_DISTANCE = 16;
	/** Rings at 32, 24 and 16. */
	private static final int PLACE_DISTANCE_STEP = 8;
	private static final double PLACE_IDEAL_DISTANCE = 24.0;

	/**
	 * Vertical bases for the column search. {@link CaveTerrain#findFloor} looks 3 up and 5 down from
	 * whatever base it is given, so three bases six apart tile a contiguous band from 11 below the
	 * player to 9 above, with the bands overlapping by three blocks at each seam (0 covers -5..+3,
	 * -6 covers -11..-3, +6 covers +1..+9) - enough to catch the passage one level down that most
	 * caves actually continue into, without scanning the same rock three times. Ordered
	 * nearest-first so a passage on the player's own level wins before one a storey away.
	 */
	private static final int[] PLACE_Y_OFFSETS = {0, -6, 6};

	/**
	 * How far off the player's path line the lizard wants to be. Because TRIGGER_RANGE stays at 5, a
	 * head-on placement means a sprinting player is on top of the lizard before the 20-tick
	 * {@code appear} animation has finished - the eruption has to read as something coming at them
	 * from the side, not as the floor opening underneath. Two to four blocks is inside the trigger
	 * radius (so they still set it off by walking past) while leaving the animation somewhere to
	 * happen.
	 */
	private static final double PLACE_LATERAL_OFFSET_MIN = 2.0;
	private static final double PLACE_LATERAL_OFFSET_MAX = 4.0;

	/**
	 * Cap on how far outside that band a candidate is penalised for being. Without it the lateral
	 * term grows with perpendicular distance - at 24 blocks out, 45 degrees off heading is 17
	 * blocks of lateral miss - and so becomes a second, steeper alignment penalty that makes a
	 * candidate directly behind the player score level with one 45 degrees ahead. Alignment
	 * already says "ahead beats behind"; this term should only ever say "a flank beats dead ahead",
	 * so the miss is clamped to the width of the band itself.
	 */
	private static final double PLACE_LATERAL_MISS_CAP = 4.0;

	/**
	 * Scoring weights. Alignment dominates deliberately: the full swing from dead ahead to directly
	 * behind is {@code 2 * ALIGNMENT_WEIGHT} = 48 points, more than the other three terms can recover
	 * between them: distance is at most ~8.5 blocks off ideal (8.5 points), vertical at most 11
	 * blocks (16.5 points, the PLACE_Y_OFFSETS band), and the lateral miss is clamped to
	 * PLACE_LATERAL_MISS_CAP so its penalty is at most 3 * 4 = 12 points - 37 in total. A candidate
	 * the player is walking away from cannot win on being nicely placed. Vertical is weighted above
	 * distance because a lizard two storeys down is a worse encounter than one a few blocks nearer
	 * or further along the same passage.
	 */
	private static final double ALIGNMENT_WEIGHT = 24.0;
	private static final double DISTANCE_WEIGHT = 1.0;
	private static final double VERTICAL_WEIGHT = 1.5;
	private static final double LATERAL_PENALTY = 3.0;

	/**
	 * The anti-tell. The sight test below only proves a site is reachable; it says nothing about
	 * whether the player has already been there, and the giveaway is not "I can see it now" (a
	 * dormant lizard is invisible) but "I mined that floor twenty seconds ago and it was solid".
	 * Rejecting anything within 12 blocks of somewhere the player has recently stood covers that,
	 * and with it walking backwards, doubling back and retracing a passage.
	 */
	private static final double RECENT_POS_REJECT_RADIUS = 12.0;
	private static final double RECENT_POS_REJECT_RADIUS_SQ =
			RECENT_POS_REJECT_RADIUS * RECENT_POS_REJECT_RADIUS;

	/**
	 * Ring buffer depth for that history. Entries are deduplicated onto a 2-block grid, so standing
	 * still or shuffling about within one cell costs nothing and 128 entries is a genuine 128 places
	 * the player has been - at one sample a second that is at least two minutes of walking and a good
	 * deal more in practice.
	 */
	private static final int RECENT_POS_CAPACITY = 128;

	/**
	 * Ten minutes of accumulated player presence marks a chunk as explored ground or somebody's base.
	 * Chunk inhabited time is free (vanilla already tracks and persists it, for regional difficulty)
	 * and is the best oracle available for "this is not fresh cave".
	 */
	private static final long MAX_INHABITED_TIME_TICKS = 12000L;

	// ---------------------------------------------------------------------------------------------
	// Debug hooks. Read once in register(), so they cost nothing per tick. Wired commented-out into
	// build.gradle's run configs, next to the geckolib.disable_examples precedent.
	// ---------------------------------------------------------------------------------------------

	private static final String PROPERTY_BUDGET_SECONDS = "orelizards.director.budgetSeconds";
	private static final String PROPERTY_DEBUG = "orelizards.director.debug";
	private static final String PROPERTY_SKIP_SIGHT_CHECK = "orelizards.director.skipSightCheck";

	/**
	 * Player state, one entry per UUID. Package-private fields on a private nested class: this is a
	 * struct, and routing a dozen one-line accessors through it would obscure the tick algorithm
	 * rather than protect anything.
	 */
	private static final class PlayerBudget {
		int undergroundTicks;
		int nextThresholdTicks;
		int nextSweepTick;
		int lastSeenTick;

		double lastX;
		double lastZ;
		boolean hasLastPos;
		double headingX;
		double headingZ;

		/**
		 * A direct reference, not a UUID. Looking an entity back up by UUID needs
		 * {@code ServerLevel.getEntity}, which is one of the methods renamed on 26.x, and this whole
		 * file is meant to be branch-identical. The reference is cleared on every exit path, and
		 * SERVER_STOPPED clears the map, so it cannot outlive its level.
		 */
		@Nullable
		OreLizardEntity pending;
		@Nullable
		ResourceKey<Level> pendingDimension;
		int pendingPlacedTick;

		final int[] recentX = new int[RECENT_POS_CAPACITY];
		final int[] recentY = new int[RECENT_POS_CAPACITY];
		final int[] recentZ = new int[RECENT_POS_CAPACITY];
		int recentCount;
		int recentWrite;

		/**
		 * Folds this sample's movement into the smoothed heading, records the position, and reports
		 * how far the player moved (squared, horizontally) so the caller can decide whether that
		 * counted as exploring. Runs on every sample, armed or not, so the heading is already warm
		 * the moment a sweep fires rather than being estimated from a single frame.
		 */
		double pushPosition(Player player) {
			double x = player.getX();
			double z = player.getZ();
			double movedSq = 0.0;
			if (this.hasLastPos) {
				double dx = x - this.lastX;
				double dz = z - this.lastZ;
				movedSq = dx * dx + dz * dz;
				// The delta is normalised before blending so the heading is a direction, not a
				// velocity - otherwise one sprint down a corridor would drown out every turn after
				// it. Below the epsilon the heading is left untouched rather than decayed towards
				// zero: a player who stops for a moment should keep pointing where they were going.
				if (movedSq > HEADING_MIN_MOVE_SQ) {
					double length = Math.sqrt(movedSq);
					this.headingX = this.headingX * HEADING_SMOOTHING
							+ (dx / length) * (1.0 - HEADING_SMOOTHING);
					this.headingZ = this.headingZ * HEADING_SMOOTHING
							+ (dz / length) * (1.0 - HEADING_SMOOTHING);
				}
			}
			this.lastX = x;
			this.lastZ = z;
			this.hasLastPos = true;
			this.recordPosition(player.blockPosition());
			return movedSq;
		}

		/** Appends to the ring buffer unless it repeats the last entry's 2-block grid cell. */
		private void recordPosition(BlockPos pos) {
			if (this.recentCount > 0) {
				int last = (this.recentWrite - 1 + RECENT_POS_CAPACITY) % RECENT_POS_CAPACITY;
				if ((this.recentX[last] >> 1) == (pos.getX() >> 1)
						&& (this.recentY[last] >> 1) == (pos.getY() >> 1)
						&& (this.recentZ[last] >> 1) == (pos.getZ() >> 1)) {
					return;
				}
			}
			this.recentX[this.recentWrite] = pos.getX();
			this.recentY[this.recentWrite] = pos.getY();
			this.recentZ[this.recentWrite] = pos.getZ();
			this.recentWrite = (this.recentWrite + 1) % RECENT_POS_CAPACITY;
			if (this.recentCount < RECENT_POS_CAPACITY) {
				this.recentCount++;
			}
		}

		boolean isRecentlyVisited(BlockPos pos) {
			for (int i = 0; i < this.recentCount; i++) {
				double dx = this.recentX[i] - pos.getX();
				double dy = this.recentY[i] - pos.getY();
				double dz = this.recentZ[i] - pos.getZ();
				if (dx * dx + dy * dy + dz * dz < RECENT_POS_REJECT_RADIUS_SQ) {
					return true;
				}
			}
			return false;
		}

		void clearPending() {
			this.pending = null;
			this.pendingDimension = null;
		}
	}

	/** A scored placement site. Sorted best-first, then sight-tested lazily. */
	private static final class Candidate {
		final BlockPos pos;
		final double score;

		Candidate(BlockPos pos, double score) {
			this.pos = pos;
			this.score = score;
		}
	}

	private static final Map<UUID, PlayerBudget> BUDGETS = new HashMap<UUID, PlayerBudget>();

	private static final Comparator<Candidate> BEST_FIRST =
			(a, b) -> Double.compare(b.score, a.score);

	private static final Predicate<OreLizardEntity> IS_DORMANT = lizard -> lizard.isDormant();

	private static boolean debug;
	private static boolean skipSightCheck;
	/** Zero means "no override"; otherwise it replaces both budget bounds. */
	private static int budgetOverrideTicks;
	private static int deliveredCount;
	private static int abandonedCount;

	private EncounterDirector() {
	}

	public static void register() {
		budgetOverrideTicks = Integer.getInteger(PROPERTY_BUDGET_SECONDS, 0).intValue() * 20;
		debug = Boolean.getBoolean(PROPERTY_DEBUG);
		skipSightCheck = Boolean.getBoolean(PROPERTY_SKIP_SIGHT_CHECK);
		if (debug) {
			OreLizardsMod.LOGGER.info("[director] debug on; budget override {} ticks, skipSightCheck {}",
					Integer.valueOf(budgetOverrideTicks), Boolean.valueOf(skipSightCheck));
		}

		ServerTickEvents.END_SERVER_TICK.register(EncounterDirector::onEndServerTick);

		// Without this, quitting to the title screen and opening a different world in the same
		// client JVM carries every budget across into the new world - and, worse, leaks a dead
		// ServerLevel through the pending entity reference for as long as that JVM lives.
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			BUDGETS.clear();
			deliveredCount = 0;
			abandonedCount = 0;
		});
	}

	// ---------------------------------------------------------------------------------------------
	// Tick
	// ---------------------------------------------------------------------------------------------

	private static void onEndServerTick(MinecraftServer server) {
		int tick = server.getTickCount();
		if (tick % STATE_PRUNE_INTERVAL_TICKS == 0) {
			pruneStaleBudgets(tick);
		}

		int sweepsThisTick = 0;
		for (ServerLevel level : server.getAllLevels()) {
			// Overworld only. The mob's whole premise is overworld cave stone, and restricting the
			// loop here also means the per-player work below never runs for someone in the Nether.
			if (level.dimension() != Level.OVERWORLD) {
				continue;
			}
			for (Player player : level.players()) {
				// Stagger by entity id so the per-second work spreads evenly across the interval
				// instead of every player landing on the same tick. floorMod rather than %, because
				// % of a negative left operand is negative and would silently skip a player forever.
				if (Math.floorMod(tick + player.getId(), SAMPLE_INTERVAL_TICKS) != 0) {
					continue;
				}
				if (samplePlayer(level, player, tick, sweepsThisTick < MAX_SWEEPS_PER_TICK)) {
					sweepsThisTick++;
				}
			}
		}
	}

	/**
	 * One second of bookkeeping for one player. Returns whether it consumed this tick's sweep.
	 *
	 * <p>The common path is one map lookup, one position delta, one predicate and one heightmap read
	 * on the player's own (always loaded) chunk. Everything expensive is behind the budget threshold.
	 */
	private static boolean samplePlayer(ServerLevel level, Player player, int tick, boolean sweepAvailable) {
		PlayerBudget budget = BUDGETS.get(player.getUUID());
		if (budget == null) {
			budget = new PlayerBudget();
			budget.nextThresholdTicks = rollThreshold(level);
			// The head start is what stops a short session from being a guaranteed miss. Expected
			// first encounter of a run is ~30 minutes of underground time rather than 40; the
			// long-run rate is unchanged, because only the first threshold is discounted.
			budget.undergroundTicks = budgetOverrideTicks > 0
					? 0
					: level.getRandom().nextInt(JOIN_HEAD_START_MAX_TICKS);
			BUDGETS.put(player.getUUID(), budget);
		}
		budget.lastSeenTick = tick;

		double movedSq = budget.pushPosition(player);

		// One pending encounter per player at a time. Everything else waits on it resolving.
		if (budget.pending != null) {
			resolvePending(level, player, budget, tick);
			return false;
		}

		if (!EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(player) || !player.isAlive()) {
			return false;
		}
		if (movedSq < EXPLORING_MIN_MOVE_SQ) {
			return false;
		}
		// Safe without a hasChunksAt guard, uniquely: this is the player's own position, and the
		// chunk a player is standing in is loaded by definition.
		if (!OreLizardEntity.isCaveDepth(level, player.blockPosition())) {
			return false;
		}

		budget.undergroundTicks += SAMPLE_INTERVAL_TICKS;
		if (budget.undergroundTicks < budget.nextThresholdTicks) {
			return false;
		}

		// Armed from here on.
		if (tick < budget.nextSweepTick) {
			return false;
		}
		if (!sweepAvailable) {
			// Deferred by the server-wide sweep cap. Deliberately does not touch nextSweepTick: a
			// player crowded out of this tick should get looked at on the next one, not be pushed
			// ten seconds down the road for something that is not their fault.
			return false;
		}
		budget.nextSweepTick = tick + ARMED_RETRY_INTERVAL_TICKS;

		if (hasDormantLizardNearby(level, player)) {
			debugLog("{} armed, but a dormant lizard is already within {} blocks",
					player.getName().getString(), Double.valueOf(NEARBY_LIZARD_SCAN_RADIUS));
			return true;
		}

		BlockPos site = findEncounterSite(level, player, budget);
		if (site == null) {
			// Nothing qualified. Stay armed and try again in ten seconds; there is no state in which
			// a player exploring caves stops receiving attempts.
			return true;
		}

		OreLizardEntity placed = OreLizardEntity.spawnDormant(level, site);
		if (placed == null) {
			debugLog("spawnDormant refused {}", site);
			return true;
		}

		budget.pending = placed;
		budget.pendingDimension = level.dimension();
		budget.pendingPlacedTick = tick;
		budget.undergroundTicks = 0;
		budget.nextThresholdTicks = rollThreshold(level);
		debugLog("placed for {} at {} (next threshold {} ticks)", player.getName().getString(),
				site, Integer.valueOf(budget.nextThresholdTicks));
		return true;
	}

	/**
	 * Decides what became of a placed lizard. The ordering of these checks is load-bearing in one
	 * place: {@code isAlive} is tested <em>before</em> {@code isDormant}, so a dead lizard is a miss
	 * whatever state it died in. The original design had it the other way round, so that a lizard
	 * the player erupted and then killed would count as delivered - and the first playtest showed
	 * what else that ordering counted. A lizard placed underwater drowns; its final drowning tick
	 * goes through {@code panicFromDamageIfDormant}, which finds the nearest player within 16 blocks
	 * and flips the corpse-to-be into FLEEING, so the next sample saw "not dormant" and scored a hit
	 * for an encounter nobody had. Death now wins. The case that ordering gave up - a player who
	 * kills the lizard inside the one-second window between its eruption and the next sample - is
	 * both rare (10 HP behind armour, in under 20 ticks) and harmless when it happens: the lizard is
	 * not dormant, so {@link #abandon} leaves it alone, and the player is refunded five minutes of
	 * budget for an encounter they in fact had. Only the debug tally is off by one, in the direction
	 * that under-reports {@code p}.
	 *
	 * <p>Anything alive that has left BURIED was activated by a player, which is exactly what
	 * "found" means.
	 */
	private static void resolvePending(ServerLevel level, Player player, PlayerBudget budget, int tick) {
		OreLizardEntity lizard = budget.pending;
		if (lizard == null) {
			return;
		}
		if (budget.pendingDimension != level.dimension()) {
			abandon(budget, "player changed dimension");
			return;
		}
		// Each reason names its branch and carries the numbers that tell the branches apart in a
		// log, because the same outcome has more than one cause: a lizard that is no longer alive may
		// have died (isDeadOrDying, and the state says whether it died in the floor or after a
		// damage panic pulled it out) or been unloaded with its chunk (not).
		int age = tick - budget.pendingPlacedTick;
		String where = String.format("%.1f blocks from the player, %d ticks after placement",
				Double.valueOf(Math.sqrt(lizard.distanceToSqr(player))), Integer.valueOf(age));
		if (!lizard.isAlive()) {
			abandon(budget, (lizard.isDeadOrDying()
					? "not alive: lizard DIED in state " + lizard.getLizardState() + ", "
					: "not alive: lizard was UNLOADED or culled in state " + lizard.getLizardState() + ", ")
					+ where);
			return;
		}
		if (!lizard.isDormant()) {
			budget.clearPending();
			deliveredCount++;
			debugLog("delivered to {} (hits {} / misses {})", player.getName().getString(),
					Integer.valueOf(deliveredCount), Integer.valueOf(abandonedCount));
			return;
		}
		if (age > PENDING_LEASE_TICKS) {
			abandon(budget, "lease: exceeded " + PENDING_LEASE_TICKS + " ticks, " + where);
			return;
		}
		if (lizard.distanceToSqr(player) > PENDING_ABANDON_DISTANCE_SQ) {
			abandon(budget, "distance: player beyond " + PENDING_ABANDON_DISTANCE + " blocks, " + where);
		}
	}

	/**
	 * Writes off a placement the player never walked into: culls the lizard if it is still in the
	 * ground, then refunds the budget to {@value #ABANDON_RETRY_TICKS} ticks short of the threshold
	 * so the next attempt comes round in about five minutes rather than another full band.
	 */
	private static void abandon(PlayerBudget budget, String reason) {
		OreLizardEntity lizard = budget.pending;
		// Only if still dormant. A lizard that has been activated belongs to its own state machine
		// now - it discards itself at the end of DIGGING_DOWN - and deleting it mid-run in front of
		// whoever startled it is the one removal that reads as the mob glitching out.
		if (lizard != null && lizard.isDormant()) {
			lizard.removeAsUnfound();
		}
		budget.clearPending();
		budget.undergroundTicks = Math.max(0, budget.nextThresholdTicks - ABANDON_RETRY_TICKS);
		abandonedCount++;
		debugLog("abandoned: {} (hits {} / misses {})", reason, Integer.valueOf(deliveredCount),
				Integer.valueOf(abandonedCount));
	}

	/**
	 * Drops budgets for players nobody has seen in an hour, so a long-running server's map does not
	 * grow with every visitor it has ever had. An entry holding a pending lizard takes it with it -
	 * that lizard has no director watching it any more, and the alternative is one immortal dormant
	 * lizard per departed player.
	 */
	private static void pruneStaleBudgets(int tick) {
		Iterator<Map.Entry<UUID, PlayerBudget>> iterator = BUDGETS.entrySet().iterator();
		while (iterator.hasNext()) {
			PlayerBudget budget = iterator.next().getValue();
			if (tick - budget.lastSeenTick <= STATE_EXPIRY_TICKS) {
				continue;
			}
			if (budget.pending != null && budget.pending.isDormant()) {
				budget.pending.removeAsUnfound();
			}
			budget.clearPending();
			iterator.remove();
		}
	}

	private static int rollThreshold(ServerLevel level) {
		if (budgetOverrideTicks > 0) {
			return budgetOverrideTicks;
		}
		return ENCOUNTER_BUDGET_MIN_TICKS
				+ level.getRandom().nextInt(ENCOUNTER_BUDGET_MAX_TICKS - ENCOUNTER_BUDGET_MIN_TICKS + 1);
	}

	private static boolean hasDormantLizardNearby(ServerLevel level, Player player) {
		double x = player.getX();
		double y = player.getY();
		double z = player.getZ();
		AABB box = new AABB(x - NEARBY_LIZARD_SCAN_RADIUS, y - NEARBY_LIZARD_SCAN_RADIUS,
				z - NEARBY_LIZARD_SCAN_RADIUS, x + NEARBY_LIZARD_SCAN_RADIUS,
				y + NEARBY_LIZARD_SCAN_RADIUS, z + NEARBY_LIZARD_SCAN_RADIUS);
		List<OreLizardEntity> nearby = level.getEntitiesOfClass(OreLizardEntity.class, box, IS_DORMANT);
		return !nearby.isEmpty();
	}

	// ---------------------------------------------------------------------------------------------
	// Placement search
	// ---------------------------------------------------------------------------------------------

	/**
	 * Finds somewhere to put the encounter, or null if nothing qualifies right now.
	 *
	 * <p>Sixteen compass directions, each probed outermost ring inwards for the first standable,
	 * valid, dry cave floor - the same shape of sweep {@code FleeAndBurrowGoal} uses, and for the
	 * same reason: an open direction costs one column check and only a blocked one falls back to
	 * nearer rings. Survivors are scored, sorted, and only then sight-tested, because the raycast is
	 * by some distance the most expensive thing in here and most candidates never need one.
	 *
	 * <p>The sight test is a <b>reachability</b> test, not a concealment one: the first candidate
	 * with a clear line from the player's eye wins. A cave floor 16-32 blocks away that the player
	 * cannot see is, far more often than not, the floor of a different cave pocket behind a wall of
	 * stone - somewhere they will never walk, which the first playtest confirmed twice. Open air
	 * between the eye and the site is the cheapest proof available that the site is in the same
	 * cave. Placing in plain view costs nothing, because {@code finalizeSpawn} makes the lizard
	 * invisible before it is added to the level, so no client is ever sent a visible frame anyway.
	 */
	// hasChunksAt and hasChunkAt are @Deprecated in Mojang mappings, as is every other "is this
	// chunk present" accessor on LevelReader; the only variants that aren't deprecated are the ones
	// that generate the chunk, on this thread, which is exactly what is being guarded against.
	// Nothing to migrate to. Same precedent as FleeAndBurrowGoal.
	@SuppressWarnings("deprecation")
	@Nullable
	private static BlockPos findEncounterSite(ServerLevel level, Player player, PlayerBudget budget) {
		double headingLength = Math.sqrt(budget.headingX * budget.headingX + budget.headingZ * budget.headingZ);
		if (headingLength < HEADING_MIN_LENGTH) {
			debugLog("no usable heading yet; deferring");
			return null;
		}
		double headingUnitX = budget.headingX / headingLength;
		double headingUnitZ = budget.headingZ / headingLength;

		BlockPos origin = player.blockPosition();
		List<Candidate> candidates = new ArrayList<Candidate>();
		int noFloor = 0;
		int rejectedVisited = 0;
		int rejectedInhabited = 0;

		for (int direction = 0; direction < PLACE_SCAN_DIRECTIONS; direction++) {
			float angle = (float) (Math.PI * 2.0 / PLACE_SCAN_DIRECTIONS) * direction;
			float dirX = Mth.cos(angle);
			float dirZ = Mth.sin(angle);

			BlockPos floor = null;
			for (int distance = PLACE_MAX_DISTANCE;
					distance >= PLACE_MIN_DISTANCE && floor == null;
					distance -= PLACE_DISTANCE_STEP) {
				int x = origin.getX() + Math.round(dirX * distance);
				int z = origin.getZ() + Math.round(dirZ * distance);
				for (int i = 0; i < PLACE_Y_OFFSETS.length && floor == null; i++) {
					int baseY = origin.getY() + PLACE_Y_OFFSETS[i];
					// Nothing below this line may read a block until this passes.
					if (!level.hasChunksAt(x, baseY - CaveTerrain.FLOOR_SEARCH_DOWN - 1, z,
							x, baseY + CaveTerrain.FLOOR_SEARCH_UP + 1, z)) {
						continue;
					}
					BlockPos found = CaveTerrain.findFloor(level, x, baseY, z);
					if (found != null && OreLizardEntity.isDirectorSiteValid(level, found)) {
						floor = found;
					}
				}
			}
			if (floor == null) {
				noFloor++;
				continue;
			}

			// Filters in cost order: the array scan, then the already-loaded chunk field, and only
			// then (lazily, below) the raycast.
			if (budget.isRecentlyVisited(floor)) {
				rejectedVisited++;
				continue;
			}
			if (isExploredChunk(level, floor)) {
				rejectedInhabited++;
				continue;
			}

			double toX = (floor.getX() + 0.5) - player.getX();
			double toZ = (floor.getZ() + 0.5) - player.getZ();
			double horizontal = Math.sqrt(toX * toX + toZ * toZ);
			if (horizontal < 1.0e-4) {
				continue;
			}
			double alignment = headingUnitX * (toX / horizontal) + headingUnitZ * (toZ / horizontal);
			// |cross product| of two vectors in the XZ plane is the perpendicular distance from the
			// path line - how far off to one side the lizard sits.
			double lateral = Math.abs(headingUnitX * toZ - headingUnitZ * toX);
			double lateralMiss;
			if (lateral < PLACE_LATERAL_OFFSET_MIN) {
				lateralMiss = PLACE_LATERAL_OFFSET_MIN - lateral;
			} else if (lateral > PLACE_LATERAL_OFFSET_MAX) {
				lateralMiss = Math.min(lateral - PLACE_LATERAL_OFFSET_MAX, PLACE_LATERAL_MISS_CAP);
			} else {
				lateralMiss = 0.0;
			}
			double score = ALIGNMENT_WEIGHT * alignment
					- DISTANCE_WEIGHT * Math.abs(horizontal - PLACE_IDEAL_DISTANCE)
					- VERTICAL_WEIGHT * Math.abs(floor.getY() - origin.getY())
					- LATERAL_PENALTY * lateralMiss;
			candidates.add(new Candidate(floor, score));
		}

		// The four counts partition the directions: a rejected floor is never a candidate. The kept
		// ones are still to be sight-tested, best-first, below.
		debugLog("sweep of {} directions: {} kept as candidates for the sight test, "
				+ "{} dropped as recently visited, {} dropped as explored chunks, {} with no valid floor",
				Integer.valueOf(PLACE_SCAN_DIRECTIONS), Integer.valueOf(candidates.size()),
				Integer.valueOf(rejectedVisited), Integer.valueOf(rejectedInhabited),
				Integer.valueOf(noFloor));
		if (candidates.isEmpty()) {
			return null;
		}

		Collections.sort(candidates, BEST_FIRST);
		for (int i = 0; i < candidates.size(); i++) {
			Candidate candidate = candidates.get(i);
			String blocker = sightBlocker(level, player, candidate.pos);
			if (blocker == null) {
				debugLog("chose {} (score {}): sight check clear", candidate.pos,
						Double.valueOf(candidate.score));
				return candidate.pos;
			}
			debugLog("rejected {} (score {}): sight check {}", candidate.pos,
					Double.valueOf(candidate.score), blocker);
		}
		debugLog("no candidate had a clear line from the player's eye; deferring");
		return null;
	}

	/**
	 * Explored ground, by chunk inhabited time. Fails closed on a missing chunk - we cannot read the
	 * field without generating the chunk, and "unknown" should not be treated as "pristine".
	 */
	@SuppressWarnings("deprecation")
	private static boolean isExploredChunk(ServerLevel level, BlockPos pos) {
		if (!level.hasChunkAt(pos)) {
			return true;
		}
		return level.getChunk(pos).getInhabitedTime() > MAX_INHABITED_TIME_TICKS;
	}

	/**
	 * Why the player cannot see this spot from where they stand, or null if the line is clear.
	 *
	 * <p>The rule: a candidate is valid only if a ray from the player's eye to the centre of the
	 * site's air block reaches it without touching a block. Where the player is <em>looking</em>
	 * is deliberately not consulted - the test is about whether the site shares the player's cave,
	 * and a passage behind them is as much theirs as one ahead (alignment scoring already prefers
	 * ahead). The previous version demanded the ray be blocked and pre-filtered on the eye plane;
	 * both are gone, and the class comment records what they cost.
	 *
	 * <p>{@code ClipContext.Block.VISUAL}, not {@code COLLIDER}: COLLIDER reports glass as a wall,
	 * and a site the player can see through a window is one they can plainly reach.
	 * {@code Fluid.NONE} so the ray passes through water - the site itself is required to be dry by
	 * {@code CaveTerrain.isStandable}, but a flooded stretch of floor between the player and a dry
	 * ledge is walkable and should not hide it.
	 */
	@SuppressWarnings("deprecation")
	@Nullable
	private static String sightBlocker(ServerLevel level, Player player, BlockPos pos) {
		if (skipSightCheck) {
			return null;
		}
		// Built from getEyeY rather than getEyePosition(): the no-argument form of the latter does
		// not exist on 1.16.5, and this file has to port unmodified.
		Vec3 eye = new Vec3(player.getX(), player.getEyeY(), player.getZ());
		Vec3 target = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

		int minX = Mth.floor(Math.min(eye.x, target.x));
		int minY = Mth.floor(Math.min(eye.y, target.y));
		int minZ = Mth.floor(Math.min(eye.z, target.z));
		int maxX = Mth.floor(Math.max(eye.x, target.x));
		int maxY = Mth.floor(Math.max(eye.y, target.y));
		int maxZ = Mth.floor(Math.max(eye.z, target.z));
		// Fail closed: Level.clip would generate anything missing along the segment, so an unloaded
		// stretch rejects the candidate rather than being gambled on.
		if (!level.hasChunksAt(minX, minY, minZ, maxX, maxY, maxZ)) {
			return "unloaded chunk along the ray";
		}

		HitResult hit = level.clip(new ClipContext(eye, target, ClipContext.Block.VISUAL,
				ClipContext.Fluid.NONE, player));
		if (hit.getType() == HitResult.Type.MISS) {
			return null;
		}
		// Floored hit location: the block the ray met, for the debug log.
		Vec3 at = hit.getLocation();
		return "blocked at [" + Mth.floor(at.x) + ", " + Mth.floor(at.y) + ", " + Mth.floor(at.z)
				+ "] (different cave pocket or behind a wall)";
	}

	private static void debugLog(String message, Object... args) {
		if (debug) {
			OreLizardsMod.LOGGER.info("[director] " + message, args);
		}
	}
}
