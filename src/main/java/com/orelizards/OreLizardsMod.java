package com.orelizards;

import com.orelizards.encounter.EncounterDirector;
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
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OreLizardsMod implements ModInitializer {
	public static final String MOD_ID = "orelizards";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/**
	 * Vanilla natural spawning, switched off. Kept in the source rather than deleted, because
	 * nothing was wrong with it: a headless run against real worldgen produced <b>43 valid lizard
	 * placements per 400,000 simulated attempts</b>, in the plains, dripstone-cave and lush-cave
	 * spawn lists, which is exactly what a weight of 1 in a rare category is supposed to look like.
	 * What it could not do is put a lizard anywhere a player was going to be. {@code AMBIENT} offers
	 * roughly <b>15 spawn slots across the ~289 loaded chunks</b> around a player and shares them
	 * with bats; a dormant lizard is invisible, silent and particle-free with a <b>5-block</b> wake
	 * radius; and worldgen cheerfully seals some of them inside solid stone, where they hold cap
	 * slots forever. {@link EncounterDirector} replaces the whole mechanism with placement that
	 * knows where the player is walking. Flipping this back to true restores the vanilla behaviour
	 * for anyone who wants to compare, or for a future version where the two coexist.
	 *
	 * <p><b>Both registrations below must be enabled or disabled together.</b> Removing only the
	 * {@code SpawnPlacements.register} call makes the mob spawn <em>more</em>, not less, and without
	 * any of the depth or block rules: for an entity type with no registered placement data,
	 * {@code SpawnPlacements.checkSpawnRules} returns {@code true} and {@code getPlacementType}
	 * returns {@code NO_RESTRICTIONS}. The biome entry is what decides whether the mob is a spawn
	 * candidate at all; the placement registration is only the filter applied afterwards.
	 */
	private static final boolean NATURAL_SPAWNING_ENABLED = false;

	@Override
	public void onInitialize() {
		LOGGER.info("Ore Lizards initializing");

		ModEntities.register();
		ModItems.register();
		FabricDefaultAttributeRegistry.register(ModEntities.ORE_LIZARD, OreLizardEntity.createAttributes());
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.SPAWN_EGGS)
				.register(entries -> entries.accept(ModItems.ORE_LIZARD_SPAWN_EGG));

		if (NATURAL_SPAWNING_ENABLED) {
			// MOTION_BLOCKING heightmap matches the vanilla Bat's own spawn registration convention.
			// The predicate is a lambda rather than a method reference so that the parameter types -
			// one of which is MobSpawnType here and EntitySpawnReason from 1.21.3 on - are inferred
			// and never written down, leaving OreLizardEntity's rule method branch-identical.
			SpawnPlacements.register(ModEntities.ORE_LIZARD, SpawnPlacements.Type.ON_GROUND,
					Heightmap.Types.MOTION_BLOCKING,
					(type, level, spawnType, pos, random) -> OreLizardEntity.isDirectorSiteValid(level, pos));
			// Weight 1, group size 1: rare relative to Bat's own vanilla weight of 10 in the same
			// AMBIENT category/cap.
			BiomeModifications.addSpawn(BiomeSelectors.foundInOverworld(), MobCategory.AMBIENT,
					ModEntities.ORE_LIZARD, 1, 1, 1);
		}

		EncounterDirector.register();
	}
}
