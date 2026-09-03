package com.orelizards;

import com.orelizards.client.OreLizardRenderer;
import com.orelizards.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class OreLizardsModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		OreLizardsMod.LOGGER.info("Ore Lizards client initializing");
		EntityRendererRegistry.register(ModEntities.ORE_LIZARD, OreLizardRenderer::new);
	}
}
