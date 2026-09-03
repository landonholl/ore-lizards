package com.orelizards.registry;

import com.orelizards.OreLizardsMod;
import com.orelizards.entity.OreLizardEntity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
	// 1.21.2+ wants the registry key up front: EntityType.Builder.build takes it (rather than a bare
	// id string) and derives the description id, the default loot table and the DataFixer schema
	// lookup from it, so the lang key and /summon id stay entity.orelizards.ore_lizard as before.
	private static final ResourceKey<EntityType<?>> ORE_LIZARD_KEY = ResourceKey.create(Registries.ENTITY_TYPE,
			ResourceLocation.fromNamespaceAndPath(OreLizardsMod.MOD_ID, "ore_lizard"));

	public static final EntityType<OreLizardEntity> ORE_LIZARD = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			ORE_LIZARD_KEY,
			// AMBIENT (not CREATURE) - matches the vanilla Bat, the closest real precedent for a
			// rare cave-dwelling critter. CREATURE shares one population cap with every animal on
			// the surface (cows/pigs/sheep/etc.), which is almost always already full by the time
			// a player is exploring caves, so a CREATURE-category mob may never get a single spawn
			// attempt regardless of weight. AMBIENT's cap is shared with basically just bats.
			//
			// Vanilla's own builder rather than Fabric's FabricEntityTypeBuilder, which is deprecated
			// on 1.21. sized() is the scalable-dimensions case EntityDimensions.scalable used to spell
			// out.
			EntityType.Builder.of(OreLizardEntity::new, MobCategory.AMBIENT)
					.sized(0.9F, 0.6F)
					.clientTrackingRange(8)
					.build(ORE_LIZARD_KEY));

	private ModEntities() {
	}

	public static void register() {
		// class-load trigger only; registration happens via the static field above
	}
}
