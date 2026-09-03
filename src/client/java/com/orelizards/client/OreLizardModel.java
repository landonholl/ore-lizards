package com.orelizards.client;

import com.orelizards.OreLizardsMod;
import com.orelizards.entity.OreLizardEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;

public class OreLizardModel extends GeoModel<OreLizardEntity> {
	private static final ResourceLocation MODEL =
			ResourceLocation.fromNamespaceAndPath(OreLizardsMod.MOD_ID, "geo/entity/ore_lizard.geo.json");
	private static final ResourceLocation TEXTURE =
			ResourceLocation.fromNamespaceAndPath(OreLizardsMod.MOD_ID, "textures/entity/ore_lizard.png");
	private static final ResourceLocation TEXTURE_DEEPSLATE =
			ResourceLocation.fromNamespaceAndPath(OreLizardsMod.MOD_ID, "textures/entity/ore_lizard_deepslate.png");
	private static final ResourceLocation ANIMATIONS =
			ResourceLocation.fromNamespaceAndPath(OreLizardsMod.MOD_ID, "animations/entity/ore_lizard.animation.json");

	// GeckoLib 4.8 (1.21.4) hands the model and texture lookups the renderer that is asking, so one
	// GeoModel can serve several renderers. There is only ever one renderer here; the parameter is
	// unused.
	@Override
	public ResourceLocation getModelResource(OreLizardEntity animatable, GeoRenderer<OreLizardEntity> renderer) {
		return MODEL;
	}

	@Override
	public ResourceLocation getTextureResource(OreLizardEntity animatable, GeoRenderer<OreLizardEntity> renderer) {
		return animatable.isDeepslate() ? TEXTURE_DEEPSLATE : TEXTURE;
	}

	@Override
	public ResourceLocation getAnimationResource(OreLizardEntity animatable) {
		return ANIMATIONS;
	}
}
