package com.orelizards.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.orelizards.entity.OreLizardEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.constant.dataticket.DataTicket;
import software.bernie.geckolib.renderer.base.GeoRenderState;
import software.bernie.geckolib.renderer.base.GeoRenderer;
import software.bernie.geckolib.renderer.base.PerBoneRender;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Re-draws the "shards" and "eyes" bones with a color multiply matching the lizard's ore variant,
 * on top of their normal white/grey pass, so one texture can represent every ore type.
 *
 * <p>Both bones then get a further emissive pass, so they read as glowing crystal rather than
 * painted rock - the mob was very hard to pick out against cave stone otherwise.
 *
 * <p><b>GeckoLib 5 shape.</b> Layers no longer see the entity, and there is no per-bone hook inside
 * the model's bone recursion any more. Instead a layer registers <em>per-bone render tasks</em> up
 * front ({@link #addPerBoneRender}); GeckoLib captures each tasked bone's pose as the recursion
 * passes it, then runs the tasks with that pose restored once the whole model has been written, and
 * only then calls {@link #render}. That is exactly the "capture during the bone pass, draw after the
 * model" structure the 1.20.1 version of this class hand-rolled, so the three passes survive with the
 * same ordering: base model, then the tint re-draw of the two bones (per-bone tasks), then the
 * deferred emissive pass (render). Everything the passes need from the entity - the variant tint and
 * whether it is invisible - is copied into the render state in {@link #addRenderData} and read back
 * from there.
 */
public class OreTintLayer<R extends LivingEntityRenderState & GeoRenderState> extends GeoRenderLayer<OreLizardEntity, Void, R> {
	private static final String SHARDS_BONE = "shards";
	private static final String EYES_BONE = "eyes";

	/**
	 * Bones that get the variant tint and the emissive pass. Order is only used to give each bone
	 * a stable slot in {@link #pending}.
	 */
	private static final List<String> GLOWING_BONES = List.of(SHARDS_BONE, EYES_BONE);

	/**
	 * Scales the additive glow pass. The emissive pass adds the variant color on top of the
	 * already-tinted bones, so 1.0 clips the brighter ores (diamond/emerald/gold) to near-white
	 * and loses their hue; this keeps the ore recognisable while still reading as a light source.
	 * Coal, being nearly black, barely glows at all - which is the behaviour we want for the
	 * shards, and means a coal lizard's eyes stay dark too.
	 */
	private static final float GLOW_STRENGTH = 0.7F;

	/**
	 * What this layer needs to know about the lizard, carried in the render state. GeckoLib 5 draws
	 * from a snapshot of the entity (the render state) rather than the entity itself, so the layer
	 * hooks never receive the entity: the variant's tint colour and the dormancy/invisibility flag are
	 * copied in by {@link #addRenderData} during extraction and read back through these tickets in the
	 * render hooks. The invisibility flag mirrors {@code Entity.isInvisible()} at extraction time, which
	 * is what the 1.20.1 code checked directly. Ids are namespaced because {@code DataTicket.create}
	 * dedupes on (type, id), so a bare "tint_color" would silently share a ticket with any other mod
	 * that picked the same name.
	 */
	private static final DataTicket<Integer> TINT_COLOR = DataTicket.create("orelizards:tint_color", Integer.class);
	private static final DataTicket<Boolean> INVISIBLE = DataTicket.create("orelizards:invisible", Boolean.class);

	/** A bone's transform for this frame, captured during the tint task and consumed in render(). */
	private static final class PendingGlow {
		@Nullable
		private GeoBone bone;
		private final Matrix4f pose = new Matrix4f();
		private final Matrix3f normal = new Matrix3f();
	}

	// Where each glowing bone ended up this frame - see renderEmissive for why the emissive draw
	// can't happen inside the per-bone task. Mutable renderer state is safe here only because a
	// renderer instance is driven by a single thread and both halves run within one entity's render
	// call; render() always clears every slot, so a stale bone can never leak into the next entity.
	// Slots are reused rather than reallocated each frame.
	private final PendingGlow[] pending = GLOWING_BONES.stream().map(name -> new PendingGlow()).toArray(PendingGlow[]::new);

	// Whether GeckoLib is actually writing the model this pass - see preRender/addPerBoneRender.
	private boolean modelDrawn;

	public OreTintLayer(GeoRenderer<OreLizardEntity, Void, R> renderer) {
		super(renderer);
	}

	@Override
	public void addRenderData(OreLizardEntity animatable, Void relatedObject, R renderState) {
		renderState.addGeckolibData(TINT_COLOR, animatable.getOreVariant().getTintColor());
		renderState.addGeckolibData(INVISIBLE, animatable.isInvisible());
	}

	/**
	 * Records whether the model is being drawn at all this pass. GeckoLib hands every layer the render
	 * type and buffer it resolved for the model, and both are null when the entity is invisible to the
	 * viewer - it still runs the layer hooks in that case, it just skips the bone recursion. See
	 * {@link #addPerBoneRender} for why that has to be known before registering any per-bone task.
	 */
	@Override
	public void preRender(R renderState, PoseStack poseStack, BakedGeoModel bakedModel, @Nullable RenderType renderType,
			MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, int packedLight, int packedOverlay,
			int renderColor) {
		this.modelDrawn = renderType != null && buffer != null;
	}

	/**
	 * Registers the tint pass for the two bones. GeckoLib runs these after the whole model has been
	 * written, with the pose stack set to the pose each bone had when the recursion drew it.
	 *
	 * <p><b>Nothing may be registered when the model isn't being drawn.</b> The captured pose is only
	 * filled in by the bone recursion, and GeckoLib skips that recursion for an invisible entity but
	 * still runs every registered task, restoring the never-captured (null) pose first - which is a
	 * NullPointerException from inside GeckoLib for every dormant lizard in view. Skipping registration
	 * is also simply correct: a dormant lizard is meant to draw nothing at all.
	 */
	@Override
	public void addPerBoneRender(R renderState, BakedGeoModel model, BiConsumer<GeoBone, PerBoneRender<R>> consumer) {
		if (!this.modelDrawn) {
			return;
		}
		for (int slot = 0; slot < GLOWING_BONES.size(); slot++) {
			PendingGlow pendingGlow = this.pending[slot];
			model.getBone(GLOWING_BONES.get(slot)).ifPresent(bone -> consumer.accept(bone,
					(state, poseStack, tintedBone, renderType, bufferSource, packedLight, packedOverlay, renderColor) ->
							renderTint(state, poseStack, tintedBone, renderType, bufferSource, packedLight, packedOverlay, pendingGlow)));
		}
	}

	/**
	 * The tint pass for one bone: the bone's cubes again, multiplied by the variant colour, into the
	 * <em>same</em> render type the model was just drawn with. Asking the buffer source for the type
	 * that is already in progress hands back that same batch, so this is an append to the model's
	 * geometry and never a buffer swap - the one kind of request that is safe from here (see
	 * {@link #renderEmissive} for the kind that isn't). The renderer's own {@code renderColor} is
	 * deliberately not folded in: the variant colour is meant to multiply the texture as written, and
	 * the 1.20.1 original never modulated it by the base colour either.
	 *
	 * <p>The pose stack GeckoLib restores for the task is the bone's pose from the model pass, which
	 * is also the last chance to get hold of it - so it is copied out here for the emissive pass.
	 */
	private void renderTint(R renderState, PoseStack poseStack, GeoBone bone, @Nullable RenderType renderType,
			MultiBufferSource bufferSource, int packedLight, int packedOverlay, PendingGlow pendingGlow) {
		if (renderType == null) {
			return;
		}

		// GeckoLib 4.5+ takes the tint as one packed ARGB int - the form VertexConsumer.setColor(int)
		// consumes - instead of four floats. Alpha forced opaque: a straight multiply over the base pass.
		int color = opaque(renderState.getGeckolibData(TINT_COLOR));
		VertexConsumer buffer = bufferSource.getBuffer(renderType);
		getRenderer().renderCubesOfBone(renderState, bone, poseStack, buffer, packedLight, packedOverlay, color);

		pendingGlow.bone = bone;
		pendingGlow.pose.set(poseStack.last().pose());
		pendingGlow.normal.set(poseStack.last().normal());
	}

	@Override
	public void render(R renderState, PoseStack poseStack, BakedGeoModel bakedModel, @Nullable RenderType renderType,
			MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, int packedLight, int packedOverlay,
			int renderColor) {
		// A dormant lizard is meant to be undetectable, and a glow is exactly the thing that would
		// give it away. GeckoLib already draws nothing for an entity invisible to the viewer, so this
		// is belt-and-braces (it also covers a spectator, to whom vanilla shows invisible mobs as
		// translucent ghosts: they get the tint but no glow) - but it's the one case where getting it
		// wrong breaks the core mechanic.
		boolean visible = !renderState.getGeckolibData(INVISIBLE);
		VertexConsumer emissiveBuffer = null;

		for (PendingGlow pendingGlow : this.pending) {
			GeoBone bone = pendingGlow.bone;
			pendingGlow.bone = null;

			if (bone == null || !visible) {
				continue;
			}
			if (emissiveBuffer == null) {
				emissiveBuffer = bufferSource.getBuffer(RenderType.eyes(getTextureResource(renderState)));
			}
			renderEmissive(renderState, poseStack, bone, pendingGlow, emissiveBuffer, packedOverlay);
		}
	}

	/**
	 * Draws a bone a second time through {@link RenderType#eyes}, the same render type vanilla uses
	 * for enderman/spider eye overlays. (Coincidental name clash with our own "eyes" bone - the
	 * shards go through it too.) Two reasons for that specific render type:
	 * <ul>
	 *   <li>Vanilla: its pipeline ({@code rendertype_eyes}) never samples the lightmap, so the pass
	 *       is fullbright regardless of the light level in the cave, and it blends additively -
	 *       the bone visibly glows in the dark instead of just being brightly lit.</li>
	 *   <li>Shader packs: Iris/OptiFine route this render type through the {@code gbuffers_spidereyes}
	 *       program, which packs treat as emissive. GeckoLib's own {@code AutoGlowingGeoLayer} was
	 *       the obvious alternative, but it builds its own {@code geckolib_emissive} render type and
	 *       pipeline that packs have no convention for, and it needs a separate {@code _glowmask}
	 *       texture per skin - which would also cost us the per-variant tint.</li>
	 * </ul>
	 * Depth testing still applies (only the depth <em>write</em> mask is off), so this doesn't
	 * shine through walls.
	 *
	 * <p><b>Why this is deferred out of the per-bone task instead of drawn there.</b>
	 * {@code RenderType.eyes} isn't one of the fixed buffers in {@code RenderBuffers}, so it shares
	 * one builder with the body's own render type: asking the buffer source for it ends whatever
	 * shared batch is in progress and starts an eyes batch in its place. Inside the bone recursion
	 * (1.20.1's {@code renderForBone}) that re-typed every bone drawn after it - the tail and legs
	 * came out fullbright, additive and depth-writeless. GeckoLib 5's per-bone tasks already run
	 * after the model is complete, but they run in hash-map order, so an eyes request in one task
	 * would still cut the other bone's tint (same type as the model) off into a batch of its own.
	 * Doing all tinting in the tasks and all glowing here, after every task has finished, keeps it to
	 * one swap at a point where nothing else is writing - which is also where GeckoLib's own glow
	 * layer switches buffers from.
	 *
	 * <p>Bone matrices have to be carried across because they can't be recomputed later: the pose
	 * GeckoLib restores for a per-bone task is gone again by the time {@link #render} runs, and
	 * {@code GeoEntityRenderer} keeps its model-space matrix in a protected field that is reset after
	 * the pass.
	 */
	private void renderEmissive(R renderState, PoseStack poseStack, GeoBone bone, PendingGlow pendingGlow,
			VertexConsumer emissiveBuffer, int packedOverlay) {
		int color = opaque(scaleRgb(renderState.getGeckolibData(TINT_COLOR), GLOW_STRENGTH));

		poseStack.pushPose();
		poseStack.last().pose().set(pendingGlow.pose);
		poseStack.last().normal().set(pendingGlow.normal);
		getRenderer().renderCubesOfBone(renderState, bone, poseStack, emissiveBuffer, LightTexture.FULL_BRIGHT, packedOverlay, color);
		poseStack.popPose();
	}

	/** An 0xRRGGBB colour with the alpha byte set to fully opaque, as GeckoLib's packed-int colour expects. */
	private static int opaque(int rgb) {
		return 0xFF000000 | (rgb & 0xFFFFFF);
	}

	/**
	 * Scales each channel of an 0xRRGGBB colour. Truncates rather than rounds, which is what the
	 * float-channel path this replaced did when it quantised {@code channel / 255 * factor} back to a
	 * byte, so the glow lands on exactly the same values as before.
	 */
	private static int scaleRgb(int rgb, float factor) {
		int red = (int) (((rgb >> 16) & 0xFF) * factor);
		int green = (int) (((rgb >> 8) & 0xFF) * factor);
		int blue = (int) ((rgb & 0xFF) * factor);
		return (red << 16) | (green << 8) | blue;
	}
}
