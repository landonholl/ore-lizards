package com.orelizards.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.orelizards.entity.OreLizardEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

import java.util.List;

/**
 * Re-draws the "shards" and "eyes" bones with a color multiply matching the lizard's ore variant,
 * on top of their normal white/grey pass, so one texture can represent every ore type.
 *
 * <p>Both bones then get a further emissive pass, so they read as glowing crystal rather than
 * painted rock - the mob was very hard to pick out against cave stone otherwise.
 */
public class OreTintLayer extends GeoRenderLayer<OreLizardEntity> {
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

	/** A bone's transform for this frame, captured during the bone pass and consumed in render(). */
	private static final class PendingGlow {
		@Nullable
		private GeoBone bone;
		private final Matrix4f pose = new Matrix4f();
		private final Matrix3f normal = new Matrix3f();
	}

	// Where each glowing bone ended up this frame - see renderEmissive for why the emissive draw
	// can't happen inline. Mutable renderer state is safe here only because a renderer instance is
	// driven by a single thread and both halves run within one entity's render call; render()
	// always clears every slot, so a stale bone can never leak into the next entity. Slots are
	// reused rather than reallocated each frame.
	private final PendingGlow[] pending = GLOWING_BONES.stream().map(name -> new PendingGlow()).toArray(PendingGlow[]::new);

	public OreTintLayer(GeoRenderer<OreLizardEntity> renderer) {
		super(renderer);
	}

	@Override
	public void renderForBone(PoseStack poseStack, OreLizardEntity animatable, GeoBone bone, RenderType renderType,
			MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
		int slot = GLOWING_BONES.indexOf(bone.getName());
		if (slot < 0) {
			return;
		}

		// GeckoLib 4.5+ takes the tint as one packed ARGB int - the form VertexConsumer.setColor(int)
		// consumes - instead of four floats. Alpha forced opaque: a straight multiply over the base pass.
		int color = opaque(animatable.getOreVariant().getTintColor());
		getRenderer().renderCubesOfBone(poseStack, bone, buffer, packedLight, packedOverlay, color);

		PendingGlow pendingGlow = this.pending[slot];
		pendingGlow.bone = bone;
		pendingGlow.pose.set(poseStack.last().pose());
		pendingGlow.normal.set(poseStack.last().normal());
	}

	@Override
	public void render(PoseStack poseStack, OreLizardEntity animatable, BakedGeoModel bakedModel, RenderType renderType,
			MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
		// A dormant lizard is meant to be undetectable, and a glow is exactly the thing that would
		// give it away. GeckoLib already skips the whole render for an invisible entity, so this is
		// belt-and-braces - but it's the one case where getting it wrong breaks the core mechanic.
		boolean visible = !animatable.isInvisible();
		VertexConsumer emissiveBuffer = null;

		for (PendingGlow pendingGlow : this.pending) {
			GeoBone bone = pendingGlow.bone;
			pendingGlow.bone = null;

			if (bone == null || !visible) {
				continue;
			}
			if (emissiveBuffer == null) {
				emissiveBuffer = bufferSource.getBuffer(RenderType.eyes(getTextureResource(animatable)));
			}
			renderEmissive(poseStack, animatable, bone, pendingGlow, emissiveBuffer, packedOverlay);
		}
	}

	/**
	 * Draws a bone a second time through {@link RenderType#eyes}, the same render type vanilla uses
	 * for enderman/spider eye overlays. (Coincidental name clash with our own "eyes" bone - the
	 * shards go through it too.) Two reasons for that specific render type:
	 * <ul>
	 *   <li>Vanilla: its shader ({@code rendertype_eyes}) never samples the lightmap, so the pass
	 *       is fullbright regardless of the light level in the cave, and it blends additively -
	 *       the bone visibly glows in the dark instead of just being brightly lit.</li>
	 *   <li>Shader packs: Iris/OptiFine route this render type through the {@code gbuffers_spidereyes}
	 *       program, which packs treat as emissive. GeckoLib's own {@code AutoGlowingGeoLayer} was
	 *       the obvious alternative, but it builds a custom {@code geo_glowing_layer} render type
	 *       that packs have no convention for, and it needs a separate {@code _glowmask} texture
	 *       per skin - which would also cost us the per-variant tint.</li>
	 * </ul>
	 * Depth testing still applies (only the depth <em>write</em> mask is off), so this doesn't
	 * shine through walls.
	 *
	 * <p><b>Why this is deferred out of {@link #renderForBone} instead of drawn inline.</b>
	 * {@code RenderType.eyes} isn't one of the fixed buffers in {@code RenderBuffers}, so it shares
	 * a single {@code BufferBuilder} with the body's own render type. Asking the buffer source for
	 * it partway through the bone recursion ends the in-progress batch and re-begins that shared
	 * builder as an eyes batch - so every bone drawn after that one (the tail and legs) got
	 * rendered fullbright, additive and depth-writeless too. Layers' {@code render} runs after
	 * {@code actuallyRender} has finished writing the whole model, which is the point where
	 * swapping render types is safe; GeckoLib's own glow layer switches buffers from exactly here.
	 *
	 * <p>Bone matrices have to be carried across because they can't be recomputed later:
	 * {@code GeoEntityRenderer.actuallyRender} pushes the entity's rotation and model transforms
	 * and pops them before layers run, and it keeps the model-space matrix in a private field.
	 */
	private void renderEmissive(PoseStack poseStack, OreLizardEntity animatable, GeoBone bone, PendingGlow pendingGlow,
			VertexConsumer emissiveBuffer, int packedOverlay) {
		int color = opaque(scaleRgb(animatable.getOreVariant().getTintColor(), GLOW_STRENGTH));

		poseStack.pushPose();
		poseStack.last().pose().set(pendingGlow.pose);
		poseStack.last().normal().set(pendingGlow.normal);
		getRenderer().renderCubesOfBone(poseStack, bone, emissiveBuffer, LightTexture.FULL_BRIGHT, packedOverlay, color);
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
