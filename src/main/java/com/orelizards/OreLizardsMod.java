package com.orelizards;

import com.orelizards.entity.OreLizardEntity;
import com.orelizards.registry.ModEntities;
import com.orelizards.registry.ModItems;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.levelgen.Heightmap;
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
		// Fabric API 0.159 (26.2) renamed the item-group module: ItemGroupEvents.modifyEntriesEvent is
		// CreativeModeTabEvents.modifyOutputEvent, same tab key, same "append to the tab" semantics.
		// CreativeModeTabs.SPAWN_EGGS is private in 26.2's own source; it compiles because Fabric's
		// creative-tab module ships a transitive class tweaker that re-opens the tab keys.
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.SPAWN_EGGS)
				.register(output -> output.accept(ModItems.ORE_LIZARD_SPAWN_EGG));
		// MOTION_BLOCKING heightmap matches the vanilla Bat's own spawn registration convention.
		SpawnPlacements.register(ModEntities.ORE_LIZARD, SpawnPlacementTypes.ON_GROUND,
				Heightmap.Types.MOTION_BLOCKING, OreLizardEntity::canSpawn);
		// Weight 1, group size 1: rare relative to Bat's own vanilla weight of 10 in the same
		// AMBIENT category/cap. Real-world sighting frequency also depends on how much of that
		// shared cap bats themselves are using, so treat this as a starting point to retune from
		// actual playtesting, not a precise "X sightings per Y hours" calculation.
		BiomeModifications.addSpawn(BiomeSelectors.foundInOverworld(), MobCategory.AMBIENT,
				ModEntities.ORE_LIZARD, 1, 1, 1);
	}
}
