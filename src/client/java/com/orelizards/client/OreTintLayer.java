package com.orelizards.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.orelizards.entity.OreLizardEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.model.provider.GeoModelProvider;
import software.bernie.geckolib3.renderer.geo.GeoLayerRenderer;
import software.bernie.geckolib3.renderer.geo.IGeoRenderer;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Re-draws the "shards" and "eyes" bones with a color multiply matching the lizard's ore variant,
 * on top of their normal white/grey pass, so one texture can represent every ore type.
 *
 * <p>Both bones then get a further emissive pass, so they read as glowing crystal rather than
 * painted rock - the mob was very hard to pick out against cave stone otherwise.
 *
 * <p><b>How the two passes are drawn on GeckoLib 3.</b> A GeckoLib 3 layer has no per-bone hook;
 * its one entry point, {@link #render}, runs after the whole body has been written and re-renders
 * the <em>entire</em> model. So instead of capturing bone matrices during the body pass (what the
 * 1.20.1 build does), this hides the cubes of every bone that isn't a glowing one and renders the
 * model twice more through {@link IGeoRenderer#render}: once with the tint through the body's own
 * render type, once fullbright through {@code RenderType.eyes}. Only the cubes are hidden, never the
 * bones themselves - a hidden bone takes its children with it, and {@code eyes} sits under
 * {@code head} while {@code shards} sits under {@code body}. GeckoLib 3 also runs its layers inside
 * the entity's own model transform (rotation applied, {@code (0, 0.01, 0)} lift included), so the
 * bones land exactly where the body pass put them with no matrix bookkeeping.
 *
 * <p>Requesting the {@code eyes} buffer is only safe because a layer runs after the body pass has
 * finished. {@code RenderType.eyes} isn't one of the fixed buffers in {@code RenderBuffers}, so it
 * shares a single {@code BufferBuilder} with the body's own render type, and asking for it partway
 * through the bone recursion would end the body's batch and re-begin the shared builder as an eyes
 * batch - every bone drawn after that point would come out fullbright and depth-writeless. That is
 * exactly why this is not done from an override of {@code renderRecursively}.
 */
public class OreTintLayer extends GeoLayerRenderer<OreLizardEntity> {
	private static final String SHARDS_BONE = "shards";
	private static final String EYES_BONE = "eyes";

	/** Bones that get the variant tint and the emissive pass. */
	private static final List<String> GLOWING_BONES =
			Collections.unmodifiableList(Arrays.asList(SHARDS_BONE, EYES_BONE));

	/**
	 * Scales the additive glow pass. The emissive pass adds the variant color on top of the
	 * already-tinted bones, so 1.0 clips the brighter ores (diamond/emerald/gold) to near-white
	 * and loses their hue; this keeps the ore recognisable while still reading as a light source.
	 * Coal, being nearly black, barely glows at all - which is the behaviour we want for the
	 * shards, and means a coal lizard's eyes stay dark too.
	 */
	private static final float GLOW_STRENGTH = 0.7F;

	/**
	 * Maximum block light and sky light packed the way the lightmap wants them. 1.17 named this
	 * {@code LightTexture.FULL_BRIGHT}; 1.16.5 only has the packer, so it is spelled out here. The
	 * eyes shader never samples the lightmap anyway, so this is belt-and-braces for the pass being
	 * fullbright and matters only to anything that does read the light argument.
	 */
	private static final int FULL_BRIGHT = LightTexture.pack(15, 15);

	public OreTintLayer(IGeoRenderer<OreLizardEntity> renderer) {
		super(renderer);
	}

	@Override
	public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, OreLizardEntity animatable,
			float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
		// A dormant lizard is meant to be undetectable, and a glow is exactly the thing that would
		// give it away. GeckoLib 3 offers no help here: unlike 4.x, which skips the whole render
		// for an invisible entity, 3.0.107 skips only the body pass for one invisible to the local
		// player and then runs every layer regardless. Both passes below draw at full alpha, so
		// without this check a buried lizard would show as a floating, glowing set of shards -
		// the one failure that breaks the core mechanic outright.
		if (animatable.isInvisible()) {
			return;
		}

		GeoModelProvider<OreLizardEntity> modelProvider = getEntityModel();
		GeoModel model = modelProvider.getModel(modelProvider.getModelLocation(animatable));
		ResourceLocation texture = getEntityTexture(animatable);
		// Same overlay the body pass used, so the hurt flash tints the shards along with the body.
		int packedOverlay = LivingEntityRenderer.getOverlayCoords(animatable, 0.0F);

		int color = animatable.getOreVariant().getTintColor();
		float red = ((color >> 16) & 0xFF) / 255F;
		float green = ((color >> 8) & 0xFF) / 255F;
		float blue = (color & 0xFF) / 255F;

		IGeoRenderer<OreLizardEntity> renderer = getRenderer();
		// The baked model is shared by every lizard on screen, so the flags are always put back
		// before returning; the render thread is the only one that touches them.
		setCubesHiddenExceptGlowing(model, true);
		try {
			// Tint pass: the glowing bones again, through the very render type the body used, with
			// the variant color multiplied in. Same geometry at the same depth, so it overwrites.
			RenderType bodyType = renderer.getRenderType(animatable, partialTick, poseStack, bufferSource, null,
					packedLight, texture);
			renderer.render(model, animatable, partialTick, bodyType, poseStack, bufferSource,
					bufferSource.getBuffer(bodyType), packedLight, packedOverlay, red, green, blue, 1.0F);

			renderEmissive(renderer, model, animatable, texture, poseStack, bufferSource, partialTick, packedOverlay,
					red, green, blue);
		} finally {
			setCubesHiddenExceptGlowing(model, false);
		}
	}

	/**
	 * Draws the glowing bones a third time through {@link RenderType#eyes}, the same render type
	 * vanilla uses for enderman/spider eye overlays. (Coincidental name clash with our own "eyes"
	 * bone - the shards go through it too.) Two reasons for that specific render type:
	 * <ul>
	 *   <li>Vanilla: its shader ({@code rendertype_eyes}) never samples the lightmap, so the pass
	 *       is fullbright regardless of the light level in the cave, and it blends additively -
	 *       the bone visibly glows in the dark instead of just being brightly lit.</li>
	 *   <li>Shader packs: Iris/OptiFine route this render type through the {@code gbuffers_spidereyes}
	 *       program, which packs treat as emissive. GeckoLib's own {@code AutoGlowingGeoLayer} was
	 *       the obvious alternative, but it builds a custom render type that packs have no
	 *       convention for, and it needs a separate {@code _glowmask} texture per skin - which
	 *       would also cost us the per-variant tint.</li>
	 * </ul>
	 * Depth testing still applies (only the depth <em>write</em> mask is off), so this doesn't
	 * shine through walls.
	 */
	private void renderEmissive(IGeoRenderer<OreLizardEntity> renderer, GeoModel model, OreLizardEntity animatable,
			ResourceLocation texture, PoseStack poseStack, MultiBufferSource bufferSource, float partialTick,
			int packedOverlay, float red, float green, float blue) {
		RenderType emissiveType = RenderType.eyes(texture);
		renderer.render(model, animatable, partialTick, emissiveType, poseStack, bufferSource,
				bufferSource.getBuffer(emissiveType), FULL_BRIGHT, packedOverlay,
				red * GLOW_STRENGTH, green * GLOW_STRENGTH, blue * GLOW_STRENGTH, 1.0F);
	}

	/**
	 * Hides (or restores) the cubes of every bone except the glowing ones. Cubes only: GeckoLib 3's
	 * {@code renderRecursively} skips a hidden bone's children as well as its cubes, and both
	 * glowing bones are children of bones that must be skipped.
	 */
	private static void setCubesHiddenExceptGlowing(GeoModel model, boolean hidden) {
		for (GeoBone bone : model.topLevelBones) {
			setCubesHiddenExceptGlowing(bone, hidden);
		}
	}

	private static void setCubesHiddenExceptGlowing(GeoBone bone, boolean hidden) {
		if (!GLOWING_BONES.contains(bone.getName())) {
			bone.setCubesHidden(hidden);
		}
		for (GeoBone child : bone.childBones) {
			setCubesHiddenExceptGlowing(child, hidden);
		}
	}
}
