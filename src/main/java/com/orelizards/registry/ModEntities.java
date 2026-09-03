package com.orelizards.registry;

import com.orelizards.OreLizardsMod;
import com.orelizards.entity.OreLizardEntity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
	public static final EntityType<OreLizardEntity> ORE_LIZARD = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			ResourceLocation.fromNamespaceAndPath(OreLizardsMod.MOD_ID, "ore_lizard"),
			// AMBIENT (not CREATURE) - matches the vanilla Bat, the closest real precedent for a
			// rare cave-dwelling critter. CREATURE shares one population cap with every animal on
			// the surface (cows/pigs/sheep/etc.), which is almost always already full by the time
			// a player is exploring caves, so a CREATURE-category mob may never get a single spawn
			// attempt regardless of weight. AMBIENT's cap is shared with basically just bats.
			//
			// Vanilla's own builder rather than Fabric's FabricEntityTypeBuilder, which is deprecated
			// on 1.21. sized() is the scalable-dimensions case EntityDimensions.scalable used to spell
			// out, and the string handed to build() is only ever used to look the type up in the
			// DataFixer schema - the description id and loot table both still derive from the
			// registry key.
			EntityType.Builder.of(OreLizardEntity::new, MobCategory.AMBIENT)
					.sized(0.9F, 0.6F)
					.clientTrackingRange(8)
					.build("ore_lizard"));

	private ModEntities() {
	}

	public static void register() {
		// class-load trigger only; registration happens via the static field above
	}
}
