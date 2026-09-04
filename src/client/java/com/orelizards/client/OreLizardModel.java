package com.orelizards.client;

import com.orelizards.OreLizardsMod;
import com.orelizards.entity.OreLizardEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.constant.dataticket.DataTicket;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.base.GeoRenderState;

public class OreLizardModel extends GeoModel<OreLizardEntity> {
	// GeckoLib 5.3 scans assets/<ns>/geckolib/models and assets/<ns>/geckolib/animations only, and keys
	// what it finds by the path with that prefix and the .geo/.animation/.json suffixes stripped. So the
	// files live under geckolib/ and both ids are the bare "entity/ore_lizard" - a "geo/..." path or a
	// ".geo.json" suffix here finds nothing and the entity falls back to the missingno placeholder quad.
	private static final ResourceLocation MODEL =
			ResourceLocation.fromNamespaceAndPath(OreLizardsMod.MOD_ID, "entity/ore_lizard");
	private static final ResourceLocation TEXTURE =
			ResourceLocation.fromNamespaceAndPath(OreLizardsMod.MOD_ID, "textures/entity/ore_lizard.png");
	private static final ResourceLocation TEXTURE_DEEPSLATE =
			ResourceLocation.fromNamespaceAndPath(OreLizardsMod.MOD_ID, "textures/entity/ore_lizard_deepslate.png");
	private static final ResourceLocation ANIMATIONS =
			ResourceLocation.fromNamespaceAndPath(OreLizardsMod.MOD_ID, "entity/ore_lizard");

	/**
	 * Which of the two skins this lizard wears, carried in the render state. GeckoLib 5 resolves the
	 * model and texture from the render state - the snapshot the client takes of an entity before
	 * drawing it - rather than from the entity itself, so the synced DEEPSLATE tracked data has to be
	 * copied across first. That happens in {@link #addAdditionalStateData}, GeoModel's own hook for
	 * exactly this, which GeckoLib calls while the state is being extracted and before anything asks
	 * for a texture. The id is namespaced because {@code DataTicket.create} dedupes on (type, id).
	 */
	private static final DataTicket<Boolean> DEEPSLATE = DataTicket.create("orelizards:deepslate", Boolean.class);

	@Override
	public ResourceLocation getModelResource(GeoRenderState renderState) {
		return MODEL;
	}

	@Override
	public ResourceLocation getTextureResource(GeoRenderState renderState) {
		// getOrDefault rather than get: the stone skin is the right answer for a state nothing has
		// filled in yet, and get() is not to be trusted with a missing key across GeckoLib versions.
		return renderState.getOrDefaultGeckolibData(DEEPSLATE, false) ? TEXTURE_DEEPSLATE : TEXTURE;
	}

	// Still takes the entity: animation lookup happens on the extraction side, where the entity is
	// available, and the same file serves every lizard anyway.
	@Override
	public ResourceLocation getAnimationResource(OreLizardEntity animatable) {
		return ANIMATIONS;
	}

	// Two arguments on GeckoLib 5.3 (5.4 inserted a "related object" in the middle, which is always
	// null for a plain entity renderer anyway).
	@Override
	public void addAdditionalStateData(OreLizardEntity animatable, GeoRenderState renderState) {
		renderState.addGeckolibData(DEEPSLATE, animatable.isDeepslate());
	}
}
