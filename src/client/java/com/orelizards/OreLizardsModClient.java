package com.orelizards;

import com.orelizards.client.OreLizardRenderer;
import com.orelizards.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendereregistry.v1.EntityRendererRegistry;

public class OreLizardsModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		OreLizardsMod.LOGGER.info("Ore Lizards client initializing");
		// Fabric API 0.42's registry is the older instance-based one (module
		// fabric-renderer-registries-v1, package "rendereregistry" - the typo is Fabric's), and
		// its factory hands over the EntityRenderDispatcher, which is what a 1.16 renderer is
		// built from - there is no EntityRendererProvider.Context yet.
		EntityRendererRegistry.INSTANCE.register(ModEntities.ORE_LIZARD,
				(dispatcher, context) -> new OreLizardRenderer(dispatcher));
	}
}
