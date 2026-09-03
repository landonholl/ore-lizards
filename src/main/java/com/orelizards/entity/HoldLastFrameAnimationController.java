package com.orelizards.entity;

import software.bernie.geckolib3.core.AnimationState;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.controller.AnimationController;

/**
 * An {@link AnimationController} whose {@code HOLD_ON_LAST_FRAME} animations actually hold.
 *
 * <p>GeckoLib 3.1 declares {@link ILoopType.EDefaultLoopTypes#HOLD_ON_LAST_FRAME} and offers it
 * from {@code AnimationBuilder.playAndHold}, but nothing in the controller ever checks for it: the
 * only loop property consulted is {@code isRepeatingAfterEnd()}, which is false for it just as for
 * {@code PLAY_ONCE}, so once the clock passes the animation's length the controller stops. A
 * stopped controller emits no keyframes, and {@code AnimationProcessor} then eases every bone back
 * to the bind pose over the model's reset length (one tick). For {@code burrow} that is the lizard
 * popping back up out of the ground for the last third of DIGGING_DOWN - the exact bug 1.1.0 fixed
 * under GeckoLib 4 by switching to {@code thenPlayAndHold}.
 *
 * <p>The fix is at the one point the controller derives its clock from: while a hold animation is
 * running, the adjusted tick is pinned a hair short of the animation's length. The end-of-animation
 * branch is therefore never taken, the controller stays running, and every frame keeps emitting the
 * final keyframe's values - so the pose is held until the predicate asks for something else, at
 * which point the normal transition blends away from it. Transitions are untouched because the pin
 * only applies in the {@code Running} state; {@code LOOP} and {@code PLAY_ONCE} are untouched
 * because it only applies to {@code HOLD_ON_LAST_FRAME}.
 */
public class HoldLastFrameAnimationController<T extends IAnimatable> extends AnimationController<T> {
	/**
	 * How far short of the end the clock is held. Anything positive works - it just has to stay
	 * below the length so the end-of-animation branch isn't reached; the keyframe lookup then
	 * resolves to the last frame at effectively 100%.
	 */
	private static final double HOLD_MARGIN_TICKS = 0.001;

	public HoldLastFrameAnimationController(T animatable, String name, float transitionLengthTicks,
			IAnimationPredicate<T> animationPredicate) {
		super(animatable, name, transitionLengthTicks, animationPredicate);
	}

	@Override
	protected double adjustTick(double tick) {
		double adjusted = super.adjustTick(tick);
		Animation current = this.currentAnimation;
		if (this.animationState != AnimationState.Running || current == null
				|| current.loop != ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME) {
			return adjusted;
		}
		return Math.min(adjusted, Math.max(current.animationLength - HOLD_MARGIN_TICKS, 0.0));
	}
}
