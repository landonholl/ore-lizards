package com.orelizards.entity.ai;

import com.orelizards.entity.OreLizardEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Active only while the lizard is in its FLEEING state; scrambles it away from
 * whichever player triggered the eruption using vanilla's flee-pathing utility.
 */
public class FleeAndBurrowGoal extends Goal {
	private static final int REPATH_INTERVAL = 10;

	private final OreLizardEntity lizard;
	private int repathCooldown;

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
	}

	@Override
	public void tick() {
		// Repath on the fixed interval OR as soon as the current path runs out, whichever comes
		// first - a pure timer left it standing idle for the rest of the interval whenever
		// DefaultRandomPos found a short path (common in tight caves), which read as it pausing.
		if (this.repathCooldown > 0 && !this.lizard.getNavigation().isDone()) {
			this.repathCooldown--;
			return;
		}

		LivingEntity target = this.lizard.getFleeTarget();
		if (target == null) {
			return;
		}

		Vec3 away = DefaultRandomPos.getPosAway(this.lizard, 16, 7, target.position());
		if (away != null) {
			this.lizard.getNavigation().moveTo(away.x, away.y, away.z, 1.0);
		}
		this.repathCooldown = REPATH_INTERVAL;
	}
}
