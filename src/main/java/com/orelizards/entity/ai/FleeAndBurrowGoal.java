package com.orelizards.entity.ai;

import com.orelizards.entity.OreLizardEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * Active only while the lizard is in its FLEEING state; sends it to the furthest place from the
 * player that it can reach.
 */
public class FleeAndBurrowGoal extends Goal {
	private static final int REPATH_INTERVAL = 10;
	/**
	 * Floor on how often a repath can happen. The interval above is bypassed as soon as the current
	 * path runs out, which is deliberate - but a destination the navigator can't actually reach
	 * leaves {@code isDone()} true forever, and without this the goal would search every tick.
	 */
	private static final int MIN_REPATH_INTERVAL = 4;
	private static final double FLEE_SPEED = 1.0;

	private static final int SCAN_DIRECTIONS = 16;
	/**
	 * Kept inside what the pathfinder can actually deliver. A mob's A* only expands nodes within
	 * FOLLOW_RANGE (16, Manhattan) of itself and gives up after FOLLOW_RANGE * 16 = 256 nodes, so a
	 * target much beyond this can never be reached: the search spends its whole node budget and
	 * returns a partial path anyway. Aiming inside that horizon means paths usually complete, which
	 * is both cheaper and more predictable. It costs nothing in range, because the lizard repaths
	 * every {@value #REPATH_INTERVAL} ticks from wherever it has got to and covers only about six
	 * blocks in that time, so the horizon keeps moving out ahead of it.
	 */
	private static final int SCAN_MAX_DISTANCE = 12;
	private static final int SCAN_MIN_DISTANCE = 6;
	private static final int SCAN_DISTANCE_STEP = 3;

	private static final int FALLBACK_RADIUS = 16;
	private static final int FALLBACK_Y_RANGE = 7;

	private final OreLizardEntity lizard;
	private int repathCooldown;
	private int earlyRepathCooldown;

	public FleeAndBurrowGoal(OreLizardEntity lizard) {
		this.lizard = lizard;
		this.setFlags(EnumSet.of(Flag.MOVE));
	}

	@Override
	public boolean canUse() {
		return this.lizard.isFleeing() && this.lizard.getFleeTarget() != null;
	}

	@Override
	public boolean canContinueToUse() {
		return this.canUse();
	}

	@Override
	public void start() {
		this.repathCooldown = 0;
		this.earlyRepathCooldown = 0;
	}

	@Override
	public void tick() {
		if (this.repathCooldown > 0) {
			this.repathCooldown--;
		}
		if (this.earlyRepathCooldown > 0) {
			this.earlyRepathCooldown--;
		}

		// Repath on the fixed interval OR as soon as the current path runs out, whichever comes
		// first - a pure timer left it standing idle for the rest of the interval whenever the
		// path found was a short one (common in tight caves), which read as it pausing.
		boolean pathExhausted = this.lizard.getNavigation().isDone();
		if (this.earlyRepathCooldown > 0 || (this.repathCooldown > 0 && !pathExhausted)) {
			return;
		}

		LivingEntity target = this.lizard.getFleeTarget();
		if (target == null) {
			return;
		}

		BlockPos escape = findFurthestEscape(target);
		boolean moving = escape != null && this.lizard.getNavigation().moveTo(
				escape.getX() + 0.5, escape.getY(), escape.getZ() + 0.5, FLEE_SPEED);

		if (!moving) {
			// Nothing standable found, or nothing the pathfinder could reach. Running somewhere is
			// always better than standing still, so fall back to vanilla's random flee pathing.
			Vec3 anywhere = DefaultRandomPos.getPosAway(this.lizard, FALLBACK_RADIUS, FALLBACK_Y_RANGE,
					target.position());
			if (anywhere != null) {
				this.lizard.getNavigation().moveTo(anywhere.x, anywhere.y, anywhere.z, FLEE_SPEED);
			}
		}

		this.repathCooldown = REPATH_INTERVAL;
		this.earlyRepathCooldown = MIN_REPATH_INTERVAL;
	}

	/**
	 * Sweeps {@value #SCAN_DIRECTIONS} directions around the lizard and returns the standable spot
	 * that puts the most distance between it and the player.
	 *
	 * <p>Each direction is probed from its outermost ring inwards and stops at the first hit, so an
	 * open direction costs a single column check and only a blocked one falls back to nearer rings.
	 * Candidates no further from the player than the lizard already is are rejected, so a direction
	 * that curls back towards the player can never win.
	 */
	// hasChunksAt is @Deprecated in Mojang mappings, as is every other "is this chunk present"
	// accessor on LevelReader; the only variants that aren't deprecated are the ones that force the
	// chunk to load, which is exactly the thing being guarded against here. Nothing to migrate to.
	@SuppressWarnings("deprecation")
	@Nullable
	private BlockPos findFurthestEscape(LivingEntity target) {
		Level level = this.lizard.level();
		BlockPos origin = this.lizard.blockPosition();

		BlockPos best = null;
		// Seeded with the lizard's current distance, so anything that isn't an improvement on just
		// standing still is rejected by the same comparison that ranks the candidates.
		double bestDistanceSq = target.distanceToSqr(this.lizard);

		for (int direction = 0; direction < SCAN_DIRECTIONS; direction++) {
			float angle = (float) (Math.PI * 2.0 / SCAN_DIRECTIONS) * direction;
			float dx = Mth.cos(angle);
			float dz = Mth.sin(angle);

			for (int distance = SCAN_MAX_DISTANCE; distance >= SCAN_MIN_DISTANCE; distance -= SCAN_DISTANCE_STEP) {
				int x = origin.getX() + Math.round(dx * distance);
				int z = origin.getZ() + Math.round(dz * distance);
				// Never read through an unloaded chunk: Level.getBlockState would force it to load.
				// This covers exactly the column findFloor reads.
				if (!level.hasChunksAt(x, origin.getY() - CaveTerrain.FLOOR_SEARCH_DOWN - 1, z,
						x, origin.getY() + CaveTerrain.FLOOR_SEARCH_UP + 1, z)) {
					continue;
				}

				BlockPos floor = CaveTerrain.findFloor(level, x, origin.getY(), z);
				if (floor == null) {
					continue;
				}

				double distanceSq = target.distanceToSqr(floor.getX() + 0.5, floor.getY(), floor.getZ() + 0.5);
				if (distanceSq > bestDistanceSq) {
					best = floor;
					bestDistanceSq = distanceSq;
				}
				// The furthest reachable ring in this direction; nearer ones can only be worse.
				break;
			}
		}

		return best;
	}
}
