package com.orelizards;

import com.orelizards.client.OreLizardRenderer;
import com.orelizards.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.entity.EntityRenderers;

public class OreLizardsModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		OreLizardsMod.LOGGER.info("Ore Lizards client initializing");
		// Vanilla's own registrar, reachable because Fabric API's transitive access wideners open it - Fabric
		// deprecated its EntityRendererRegistry wrapper in favour of exactly this call.
		EntityRenderers.register(ModEntities.ORE_LIZARD, OreLizardRenderer::new);
	}
}
