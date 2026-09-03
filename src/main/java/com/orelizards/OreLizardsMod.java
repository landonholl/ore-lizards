package com.orelizards;

import com.orelizards.entity.OreLizardEntity;
import com.orelizards.registry.ModEntities;
import com.orelizards.registry.ModItems;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.world.entity.MobCategory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class OreLizardsMod implements ModInitializer {
	public static final String MOD_ID = "orelizards";
	// Log4j rather than SLF4J: Minecraft only started shipping SLF4J in 1.18, and 1.16.5's runtime
	// has just log4j-api/log4j-core on it.
	public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Ore Lizards initializing");

		ModEntities.register();
		ModItems.register();
		FabricDefaultAttributeRegistry.register(ModEntities.ORE_LIZARD, OreLizardEntity.createAttributes());
		// The spawn placement rule (ON_GROUND, MOTION_BLOCKING, OreLizardEntity::canSpawn) is
		// declared on the entity type builder in ModEntities - see the comment there. The spawn
		// egg's creative tab is set on the item itself in ModItems; 1.16.5 has no tab event API.
		// Weight 1, group size 1: rare relative to Bat's own vanilla weight of 10 in the same
		// AMBIENT category/cap. Real-world sighting frequency also depends on how much of that
		// shared cap bats themselves are using, so treat this as a starting point to retune from
		// actual playtesting, not a precise "X sightings per Y hours" calculation.
		BiomeModifications.addSpawn(BiomeSelectors.foundInOverworld(), MobCategory.AMBIENT,
				ModEntities.ORE_LIZARD, 1, 1, 1);
	}
}
