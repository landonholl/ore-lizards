package com.orelizards.registry;

import com.orelizards.OreLizardsMod;
import com.orelizards.entity.OreLizardEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.level.levelgen.Heightmap;

public final class ModEntities {
	public static final EntityType<OreLizardEntity> ORE_LIZARD = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			new ResourceLocation(OreLizardsMod.MOD_ID, "ore_lizard"),
			// AMBIENT (not CREATURE) - matches the vanilla Bat, the closest real precedent for a
			// rare cave-dwelling critter. CREATURE shares one population cap with every animal on
			// the surface (cows/pigs/sheep/etc.), which is almost always already full by the time
			// a player is exploring caves, so a CREATURE-category mob may never get a single spawn
			// attempt regardless of weight. AMBIENT's cap is shared with basically just bats.
			//
			// The spawn placement is declared here rather than next to the biome spawn entry in
			// OreLizardsMod because 1.20.5 made SpawnPlacements.register private. Fabric API widens
			// it again, but only at runtime, not for the compiler; its supported route is the
			// entity-type builder's spawnRestriction, which calls that same method when the type is
			// built. MOTION_BLOCKING heightmap matches the vanilla Bat's own spawn registration.
			FabricEntityType.Builder.createMob(OreLizardEntity::new, MobCategory.AMBIENT, mob -> mob
							.spawnRestriction(SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING,
									OreLizardEntity::canSpawn))
					.sized(0.9F, 0.6F)
					.clientTrackingRange(8)
					// Fabric's no-argument build(), not vanilla's build(String): the latter looks the
					// key up in the DFU schema and logs "No data fixer registered" for any modded type.
					.build());

	private ModEntities() {
	}

	public static void register() {
		// class-load trigger only; registration happens via the static field above
	}
}
