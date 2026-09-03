package com.orelizards;

import com.orelizards.entity.OreLizardEntity;
import com.orelizards.registry.ModEntities;
import com.orelizards.registry.ModItems;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.entity.MobCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OreLizardsMod implements ModInitializer {
	public static final String MOD_ID = "orelizards";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Ore Lizards initializing");

		ModEntities.register();
		ModItems.register();
		FabricDefaultAttributeRegistry.register(ModEntities.ORE_LIZARD, OreLizardEntity.createAttributes());
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.SPAWN_EGGS)
				.register(entries -> entries.accept(ModItems.ORE_LIZARD_SPAWN_EGG));
		// Spawn placement (ON_GROUND / MOTION_BLOCKING / canSpawn) is declared on the entity type
		// itself in ModEntities - see the note there for why it is not registered from here.
		// Weight 1, group size 1: rare relative to Bat's own vanilla weight of 10 in the same
		// AMBIENT category/cap. Real-world sighting frequency also depends on how much of that
		// shared cap bats themselves are using, so treat this as a starting point to retune from
		// actual playtesting, not a precise "X sightings per Y hours" calculation.
		BiomeModifications.addSpawn(BiomeSelectors.foundInOverworld(), MobCategory.AMBIENT,
				ModEntities.ORE_LIZARD, 1, 1, 1);
	}
}
