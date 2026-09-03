package com.orelizards.client;

import com.orelizards.OreLizardsMod;
import com.orelizards.entity.OreLizardEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib3.model.AnimatedGeoModel;

public class OreLizardModel extends AnimatedGeoModel<OreLizardEntity> {
	private static final ResourceLocation MODEL =
			new ResourceLocation(OreLizardsMod.MOD_ID, "geo/entity/ore_lizard.geo.json");
	private static final ResourceLocation TEXTURE =
			new ResourceLocation(OreLizardsMod.MOD_ID, "textures/entity/ore_lizard.png");
	// Never selected on 1.16.5 - nothing here can set a lizard's deepslate flag - but kept, along
	// with the texture itself, so the model reads the same as on the versions that have deepslate.
	private static final ResourceLocation TEXTURE_DEEPSLATE =
			new ResourceLocation(OreLizardsMod.MOD_ID, "textures/entity/ore_lizard_deepslate.png");
	private static final ResourceLocation ANIMATIONS =
			new ResourceLocation(OreLizardsMod.MOD_ID, "animations/entity/ore_lizard.animation.json");

	@Override
	public ResourceLocation getModelLocation(OreLizardEntity animatable) {
		return MODEL;
	}

	@Override
	public ResourceLocation getTextureLocation(OreLizardEntity animatable) {
		return animatable.isDeepslate() ? TEXTURE_DEEPSLATE : TEXTURE;
	}

	@Override
	public ResourceLocation getAnimationFileLocation(OreLizardEntity animatable) {
		return ANIMATIONS;
	}
}
