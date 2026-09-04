package com.orelizards.registry;

import com.orelizards.OreLizardsMod;
import com.orelizards.entity.OreLizardEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
	public static final EntityType<OreLizardEntity> ORE_LIZARD = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			new ResourceLocation(OreLizardsMod.MOD_ID, "ore_lizard"),
			// AMBIENT (not CREATURE) - matches the vanilla Bat, the closest real precedent for a
			// rare cave-dwelling critter. The original reason was the population cap: CREATURE
			// shares one with every animal on the surface, which is effectively always full by the
			// time a player is underground, so a CREATURE-category mob might never get a single
			// spawn attempt regardless of weight, whereas AMBIENT's cap is shared with basically
			// just bats. That argument is now moot - EncounterDirector places lizards itself and
			// natural spawning is off - but the category stays, and deliberately. It is baked into
			// the registered EntityType, it is what /data and every mob-cap tool reports, and the
			// obvious alternative (MISC) is wrong on its own terms: that category is for entities
			// that aren't Mobs. Changing it would be a behaviour change across 20 branches for no
			// gain.
			FabricEntityTypeBuilder.create(MobCategory.AMBIENT, OreLizardEntity::new)
					.dimensions(EntityDimensions.scalable(0.9F, 0.6F))
					.trackRangeChunks(8)
					.build());

	private ModEntities() {
	}

	public static void register() {
		// class-load trigger only; registration happens via the static field above
	}
}
