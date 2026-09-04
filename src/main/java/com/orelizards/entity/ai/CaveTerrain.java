package com.orelizards.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathComputationType;
import org.jetbrains.annotations.Nullable;

/**
 * "Where can a lizard stand?", answered for a single column of blocks.
 *
 * <p>This was private to {@link FleeAndBurrowGoal} until the encounter director needed the same
 * answer. Extracted rather than made public on the goal: a spawn director reaching into an AI goal
 * is the wrong dependency direction, and this keeps every version-sensitive block query the mob
 * makes about cave floors in one file. {@link #isStandable} in particular is one of the four lines
 * that differ across the port branches ({@code isPathfindable} takes three arguments up to 1.20.4
 * and one from 1.20.6 on), so confining it here means a port touches one method, not two callers.
 *
 * <p>One rule was added on extraction: a standable block must be dry. The goal's original copy
 * accepted water, and it took the director placing lizards on flooded floors to notice - see
 * {@link #isStandable}.
 */
public final class CaveTerrain {
	/**
	 * How far {@link #findFloor} looks either side of the height it is given. Asymmetric on purpose:
	 * the search runs downwards from slightly overhead so it lands on the floor of the passage rather
	 * than partway up a wall, and reaches further down than up because a cave floor that drops away
	 * is far more common than one that steps up out of reach.
	 */
	public static final int FLOOR_SEARCH_UP = 3;
	public static final int FLOOR_SEARCH_DOWN = 5;

	private CaveTerrain() {
	}

	/**
	 * The first standable position in this column, searched downwards from slightly above
	 * {@code baseY}, so a result lands on the cave floor rather than inside the rock.
	 *
	 * <p>Callers must have established that the column is loaded first - see the
	 * {@code hasChunksAt} guards at both call sites. Every non-deprecated way of reading a block
	 * generates the chunk if it is missing, on the calling (server) thread.
	 */
	@Nullable
	public static BlockPos findFloor(Level level, int x, int baseY, int z) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dy = FLOOR_SEARCH_UP; dy >= -FLOOR_SEARCH_DOWN; dy--) {
			int y = baseY + dy;
			if (isStandable(level, cursor, x, y, z)) {
				return new BlockPos(x, y, z);
			}
		}
		return null;
	}

	/**
	 * A sturdy top face to stand on, and two blocks of passable, <em>dry</em> space above it.
	 *
	 * <p>The passability half is the test vanilla's land pathfinder applies, so anything this
	 * accepts is somewhere the navigator will also agree the mob fits. The dry half is ours,
	 * because that vanilla test is wrong for this mob in one specific way: {@code
	 * LiquidBlock.isPathfindable} returns true for anything that is not lava <em>regardless of the
	 * path type asked for</em> ({@code javap} on 1.20.1 shows it as a bare
	 * {@code !fluid.is(FluidTags.LAVA)}), so a column of water over stone passed as a floor. The
	 * encounter director's first playtest put both of its placements underwater - one under three
	 * blocks of it - and each lizard drowned within seconds of being placed. Both fluid positions
	 * are checked, not just the feet: a lizard standing in one block of water with air above would
	 * survive, but it would also erupt out of a puddle, which is not the mob.
	 *
	 * <p>{@code FleeAndBurrowGoal} shares this rule, and gains from it: its sweep used to be happy to
	 * route a fleeing lizard <em>into</em> a pool, where a 0.6-block mob wades slowly and reads as
	 * having given up. Now the sweep only ever aims at dry floor. The goal's random-position fallback
	 * ({@code DefaultRandomPos.getPosAway}) is vanilla's and is not covered, but it only runs when
	 * this sweep has already found nothing.
	 */
	public static boolean isStandable(Level level, int x, int y, int z) {
		return isStandable(level, new BlockPos.MutableBlockPos(), x, y, z);
	}

	/**
	 * Cursor-reusing form, so a column scan allocates one {@code MutableBlockPos} rather than
	 * three per candidate height.
	 */
	private static boolean isStandable(Level level, BlockPos.MutableBlockPos cursor, int x, int y, int z) {
		cursor.set(x, y - 1, z);
		if (!level.getBlockState(cursor).isFaceSturdy(level, cursor, Direction.UP)) {
			return false;
		}
		cursor.set(x, y, z);
		if (!level.getBlockState(cursor).isPathfindable(level, cursor, PathComputationType.LAND)
				|| !level.getFluidState(cursor).isEmpty()) {
			return false;
		}
		cursor.set(x, y + 1, z);
		return level.getBlockState(cursor).isPathfindable(level, cursor, PathComputationType.LAND)
				&& level.getFluidState(cursor).isEmpty();
	}
}
