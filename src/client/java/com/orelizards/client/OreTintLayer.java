package com.orelizards.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.orelizards.entity.OreLizardEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

import java.util.Set;

/**
 * Re-draws the "shards" and "eyes" bones with a color multiply matching the lizard's ore variant,
 * on top of their normal white/grey pass, so one texture can represent every ore type.
 */
public class OreTintLayer extends GeoRenderLayer<OreLizardEntity> {
	private static final Set<String> TINTED_BONES = Set.of("shards", "eyes");

	public OreTintLayer(GeoRenderer<OreLizardEntity> renderer) {
		super(renderer);
	}

	@Override
	public void renderForBone(PoseStack poseStack, OreLizardEntity animatable, GeoBone bone, RenderType renderType,
			MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
		if (!TINTED_BONES.contains(bone.getName())) {
			return;
		}

		int color = animatable.getOreVariant().getTintColor();
		float red = ((color >> 16) & 0xFF) / 255F;
		float green = ((color >> 8) & 0xFF) / 255F;
		float blue = (color & 0xFF) / 255F;

		getRenderer().renderCubesOfBone(poseStack, bone, buffer, packedLight, packedOverlay, red, green, blue, 1.0F);
	}
}
