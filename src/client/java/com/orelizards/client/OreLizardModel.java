package com.orelizards.client;

import com.orelizards.OreLizardsMod;
import com.orelizards.entity.OreLizardEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class OreLizardModel extends GeoModel<OreLizardEntity> {
	private static final ResourceLocation MODEL =
			ResourceLocation.fromNamespaceAndPath(OreLizardsMod.MOD_ID, "geo/entity/ore_lizard.geo.json");
	private static final ResourceLocation TEXTURE =
			ResourceLocation.fromNamespaceAndPath(OreLizardsMod.MOD_ID, "textures/entity/ore_lizard.png");
	private static final ResourceLocation TEXTURE_DEEPSLATE =
			ResourceLocation.fromNamespaceAndPath(OreLizardsMod.MOD_ID, "textures/entity/ore_lizard_deepslate.png");
	private static final ResourceLocation ANIMATIONS =
			ResourceLocation.fromNamespaceAndPath(OreLizardsMod.MOD_ID, "animations/entity/ore_lizard.animation.json");

	@Override
	public ResourceLocation getModelResource(OreLizardEntity animatable) {
		return MODEL;
	}

	@Override
	public ResourceLocation getTextureResource(OreLizardEntity animatable) {
		return animatable.isDeepslate() ? TEXTURE_DEEPSLATE : TEXTURE;
	}

	@Override
	public ResourceLocation getAnimationResource(OreLizardEntity animatable) {
		return ANIMATIONS;
	}
}
