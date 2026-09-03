package com.orelizards.registry;

import com.orelizards.OreLizardsMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;

public final class ModItems {
	// Since 1.21.2 every Item must be told its own registry key through Item.Properties.setId - the
	// constructor derives the description id from it and throws "Item id not set" without one.
	private static final ResourceKey<Item> ORE_LIZARD_SPAWN_EGG_KEY = ResourceKey.create(Registries.ITEM,
			ResourceLocation.fromNamespaceAndPath(OreLizardsMod.MOD_ID, "ore_lizard_spawn_egg"));

	// Stone-grey body with a bright crystal highlight, matching the mob's own read. On 1.21.3 these are
	// still constructor arguments, exactly as on 1.20.1: the client colours the two layers of the
	// template_spawn_egg model through ItemColors -> SpawnEggItem.getColor. The item model definitions
	// that move spawn egg tinting into assets/<namespace>/items/<id>.json only arrive in 1.21.4, so
	// there is deliberately no such file on this branch - 1.21.3 would ignore it.
	private static final int EGG_BACKGROUND_COLOR = 0x6E6E6E;
	private static final int EGG_HIGHLIGHT_COLOR = 0x63E1FF;

	public static final Item ORE_LIZARD_SPAWN_EGG = Registry.register(
			BuiltInRegistries.ITEM,
			ORE_LIZARD_SPAWN_EGG_KEY,
			new SpawnEggItem(ModEntities.ORE_LIZARD, EGG_BACKGROUND_COLOR, EGG_HIGHLIGHT_COLOR,
					new Item.Properties().setId(ORE_LIZARD_SPAWN_EGG_KEY)));

	private ModItems() {
	}

	public static void register() {
		// class-load trigger only; registration happens via the static field above
	}
}
