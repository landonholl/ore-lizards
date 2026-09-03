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
	// constructor derives the description id from it and throws "Item id not set" without one - and
	// since 1.21.4 that key also names the item model definition the client loads:
	// assets/orelizards/items/ore_lizard_spawn_egg.json.
	private static final ResourceKey<Item> ORE_LIZARD_SPAWN_EGG_KEY = ResourceKey.create(Registries.ITEM,
			ResourceLocation.fromNamespaceAndPath(OreLizardsMod.MOD_ID, "ore_lizard_spawn_egg"));

	// The egg's colours - stone-grey body 0x6E6E6E with a bright crystal highlight 0x63E1FF, matching
	// the mob's own read - are no longer constructor arguments. 1.21.4 moved spawn egg tinting out of
	// SpawnEggItem and into the item model definition, so they live as two constant tint sources in
	// the JSON named above (written the way vanilla writes its own eggs: signed ARGB ints,
	// -9539986 and -10231297).
	public static final Item ORE_LIZARD_SPAWN_EGG = Registry.register(
			BuiltInRegistries.ITEM,
			ORE_LIZARD_SPAWN_EGG_KEY,
			new SpawnEggItem(ModEntities.ORE_LIZARD, new Item.Properties().setId(ORE_LIZARD_SPAWN_EGG_KEY)));

	private ModItems() {
	}

	public static void register() {
		// class-load trigger only; registration happens via the static field above
	}
}
