package com.orelizards.registry;

import com.orelizards.OreLizardsMod;
import com.orelizards.entity.OreLizardEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.levelgen.Heightmap;

public final class ModEntities {
	public static final EntityType<OreLizardEntity> ORE_LIZARD = Registry.register(
			Registry.ENTITY_TYPE,
			new ResourceLocation(OreLizardsMod.MOD_ID, "ore_lizard"),
			// AMBIENT (not CREATURE) - matches the vanilla Bat, the closest real precedent for a
			// rare cave-dwelling critter. CREATURE shares one population cap with every animal on
			// the surface (cows/pigs/sheep/etc.), which is almost always already full by the time
			// a player is exploring caves, so a CREATURE-category mob may never get a single spawn
			// attempt regardless of weight. AMBIENT's cap is shared with basically just bats.
			FabricEntityTypeBuilder.<OreLizardEntity>createMob()
					.spawnGroup(MobCategory.AMBIENT)
					.entityFactory(OreLizardEntity::new)
					.dimensions(EntityDimensions.scalable(0.9F, 0.6F))
					.trackRangeChunks(8)
					// SpawnPlacements.register is private in 1.16.5 (Mojang only opened it up in
					// 1.19), so the placement rule is declared here on Fabric's mob builder, which
					// reaches it through an accessor, instead of in OreLizardsMod.onInitialize.
					// Same rule as before: ON_GROUND, and the MOTION_BLOCKING heightmap that matches
					// the vanilla Bat's own spawn registration convention.
					.spawnRestriction(SpawnPlacements.Type.ON_GROUND, Heightmap.Types.MOTION_BLOCKING,
							OreLizardEntity::canSpawn)
					.build());

	private ModEntities() {
	}

	public static void register() {
		// class-load trigger only; registration happens via the static field above
	}
}
