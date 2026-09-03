package com.orelizards.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.orelizards.entity.OreLizardEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.renderers.geo.GeoLayerRenderer;
import software.bernie.geckolib3.renderers.geo.IGeoRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Re-draws the "shards" and "eyes" bones with a color multiply matching the lizard's ore variant,
 * on top of their normal white/grey pass, so one texture can represent every ore type.
 *
 * <p>Both bones then get a further emissive pass, so they read as glowing crystal rather than
 * painted rock - the mob was very hard to pick out against cave stone otherwise.
 *
 * <p><b>How this works under GeckoLib 3.</b> A GeckoLib 3 layer has no per-bone hook: its only
 * entry point is {@link #render}, which runs once, after the whole model has been written, and is
 * expected to re-render the model itself. So both passes here re-run the renderer over the full
 * bone tree with every bone except {@code shards} and {@code eyes} hidden for the duration (their
 * ancestors stay un-hidden but with their own cubes suppressed, since hiding a bone in GeckoLib 3
 * hides its whole subtree). That happens to land exactly where the render-type swap has to happen
 * anyway - see {@link #render} for why.
 */
public class OreTintLayer extends GeoLayerRenderer<OreLizardEntity> {
	private static final String SHARDS_BONE = "shards";
	private static final String EYES_BONE = "eyes";

	/** Bones that get the variant tint and the emissive pass. */
	private static final List<String> GLOWING_BONES = List.of(SHARDS_BONE, EYES_BONE);

	/**
	 * Scales the additive glow pass. The emissive pass adds the variant color on top of the
	 * already-tinted bones, so 1.0 clips the brighter ores (diamond/emerald/gold) to near-white
	 * and loses their hue; this keeps the ore recognisable while still reading as a light source.
	 * Coal, being nearly black, barely glows at all - which is the behaviour we want for the
	 * shards, and means a coal lizard's eyes stay dark too.
	 */
	private static final float GLOW_STRENGTH = 0.7F;

	/** One bone's visibility flags as they were before a pass, so the pass can be undone exactly. */
	private record BoneVisibility(GeoBone bone, boolean hidden, boolean cubesHidden, boolean childrenHidden) {
		static BoneVisibility capture(GeoBone bone) {
			return new BoneVisibility(bone, bone.isHidden, bone.areCubesHidden, bone.hideChildBonesToo);
		}

		void restore() {
			this.bone.setHidden(this.hidden, this.childrenHidden);
			this.bone.setCubesHidden(this.cubesHidden);
		}
	}

	// The baked GeoModel - and so every GeoBone's hidden flag - is one cached object shared by every
	// lizard on screen, so the flags are flipped for exactly the duration of one entity's two passes
	// and put back in a finally. Safe only because a renderer is driven by a single thread. Reused
	// rather than reallocated each frame.
	private final List<BoneVisibility> savedVisibility = new ArrayList<>();

	public OreTintLayer(IGeoRenderer<OreLizardEntity> renderer) {
		super(renderer);
	}

	/**
	 * Both extra passes, in order: the variant tint through the body's own render type, then the
	 * glow through {@link RenderType#eyes}.
	 *
	 * <p>{@code RenderType.eyes} is the same render type vanilla uses for enderman/spider eye
	 * overlays. (Coincidental name clash with our own "eyes" bone - the shards go through it too.)
	 * Two reasons for that specific render type:
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
	 * <p><b>Why the glow can only be drawn from here, after the body is finished.</b>
	 * {@code RenderType.eyes} isn't one of the fixed buffers in {@code RenderBuffers}, so it shares
	 * a single {@code BufferBuilder} with the body's own render type. Asking the buffer source for
	 * it partway through the bone recursion ends the in-progress batch and re-begins that shared
	 * builder as an eyes batch - so every bone drawn after that one (the tail and legs) got
	 * rendered fullbright, additive and depth-writeless too. GeckoLib 3 invokes layers from
	 * {@code GeoEntityRenderer.render} only once the model pass has returned, which is the point
	 * where swapping render types is safe. The tint pass asks for the body's render type, so it
	 * simply continues the body's batch rather than starting a new one.
	 */
	@Override
	public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, OreLizardEntity animatable,
			float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
		// GeckoLib 3 runs its layers whether or not it drew the body - the body pass is gated on
		// exactly this check, the layer loop only on spectator mode - so a layer has to repeat the
		// check itself. A dormant lizard is invisible, and tinting or lighting up bones that were
		// never drawn would paint it back into view.
		if (animatable.isInvisibleTo(Minecraft.getInstance().player)) {
			return;
		}

		GeoModel model = getEntityModel().getModel(getEntityModel().getModelResource(animatable));
		ResourceLocation texture = getEntityTexture(animatable);
		// Same overlay the body pass used, so the hurt flash covers the shards too.
		int packedOverlay = LivingEntityRenderer.getOverlayCoords(animatable, 0.0F);
		int color = animatable.getOreVariant().getTintColor();
		float red = ((color >> 16) & 0xFF) / 255F;
		float green = ((color >> 8) & 0xFF) / 255F;
		float blue = (color & 0xFF) / 255F;

		isolateGlowingBones(model);
		try {
			// Tint: the same render type the body just used, so this is appended to the body's own
			// batch and draws over the untinted shards at identical depth.
			RenderType bodyType = getRenderer().getRenderType(animatable, partialTick, poseStack, bufferSource, null,
					packedLight, texture);
			getRenderer().render(model, animatable, partialTick, bodyType, poseStack, bufferSource,
					bufferSource.getBuffer(bodyType), packedLight, packedOverlay, red, green, blue, 1.0F);

			// Glow. A dormant lizard is meant to be undetectable, and a glow is exactly the thing
			// that would give it away; this is the one case where getting it wrong breaks the core
			// mechanic, so it is checked on its own even though the gate above already covers it
			// for every viewer but a spectator.
			if (animatable.isInvisible()) {
				return;
			}
			RenderType glowType = RenderType.eyes(texture);
			getRenderer().render(model, animatable, partialTick, glowType, poseStack, bufferSource,
					bufferSource.getBuffer(glowType), LightTexture.FULL_BRIGHT, packedOverlay,
					red * GLOW_STRENGTH, green * GLOW_STRENGTH, blue * GLOW_STRENGTH, 1.0F);
		} finally {
			restoreBoneVisibility();
		}
	}

	/**
	 * Leaves only the glowing bones drawable. {@code GeoEntityRenderer.renderRecursively} skips a
	 * hidden bone's entire subtree, so an ancestor of a glowing bone has to stay un-hidden with just
	 * its own cubes suppressed; everything else is hidden outright. Every touched bone's previous
	 * flags are recorded first so {@link #restoreBoneVisibility} can put them back exactly.
	 */
	private void isolateGlowingBones(GeoModel model) {
		this.savedVisibility.clear();
		for (GeoBone bone : model.topLevelBones) {
			isolateGlowingBones(bone);
		}
	}

	/** @return whether this bone or anything beneath it is a glowing bone */
	private boolean isolateGlowingBones(GeoBone bone) {
		this.savedVisibility.add(BoneVisibility.capture(bone));
		boolean glowing = GLOWING_BONES.contains(bone.getName());
		boolean descendantGlows = false;
		for (GeoBone child : bone.childBones) {
			// Non-short-circuit on purpose: every child has to be visited to be hidden.
			descendantGlows |= isolateGlowingBones(child);
		}

		if (glowing) {
			bone.setHidden(false, false);
			bone.setCubesHidden(false);
		} else if (descendantGlows) {
			bone.setHidden(false, false);
			bone.setCubesHidden(true);
		} else {
			bone.setHidden(true, true);
		}
		return glowing || descendantGlows;
	}

	private void restoreBoneVisibility() {
		for (BoneVisibility saved : this.savedVisibility) {
			saved.restore();
		}
		this.savedVisibility.clear();
	}
}
