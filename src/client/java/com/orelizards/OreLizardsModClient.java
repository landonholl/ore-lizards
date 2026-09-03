package com.orelizards;

import com.orelizards.client.OreLizardRenderer;
import com.orelizards.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.entity.EntityRenderers;

public class OreLizardsModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		OreLizardsMod.LOGGER.info("Ore Lizards client initializing");
		// Vanilla's own registration. Fabric API's EntityRendererRegistry.register is @Deprecated on 26.x
		// (0.155 and 0.159 alike) and was only ever a one-line delegate to this method, which is private in
		// vanilla but re-opened by Fabric's transitive access widener (fabric-transitive-access-wideners-v1,
		// "transitive-accessible method ... EntityRenderers register") - the same mechanism that lets
		// SpawnPlacements.register compile on the common side. Loom applies the widener to the dev jar and
		// Fabric Loader applies it at runtime. Same timing as before: client mod init runs before the
		// EntityRenderDispatcher's first resource reload, which is when the provider map is read.
		EntityRenderers.register(ModEntities.ORE_LIZARD, OreLizardRenderer::new);
	}
}
