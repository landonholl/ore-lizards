package com.orelizards.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.orelizards.entity.OreLizardEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib3.renderers.geo.GeoEntityRenderer;

public class OreLizardRenderer extends GeoEntityRenderer<OreLizardEntity> {
	public OreLizardRenderer(EntityRendererProvider.Context context) {
		super(context, new OreLizardModel());
		this.shadowRadius = 0.5F;
		this.addLayer(new OreTintLayer(this));
	}

	/**
	 * GeckoLib 3 draws entities through the back-face-culled {@code entityCutout} by default, where
	 * GeckoLib 4 - which this mod's look was tuned against - uses {@code entityCutoutNoCull}. Pinned
	 * to the latter so the model reads identically on both: any face the model shows edge-on or
	 * from behind would otherwise vanish here. {@link OreTintLayer} asks this same method for its
	 * tint pass, so the two always agree (and share a buffer).
	 */
	@Override
	public RenderType getRenderType(OreLizardEntity animatable, float partialTick, PoseStack poseStack,
			@Nullable MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, int packedLight,
			ResourceLocation texture) {
		return RenderType.entityCutoutNoCull(texture);
	}
}
