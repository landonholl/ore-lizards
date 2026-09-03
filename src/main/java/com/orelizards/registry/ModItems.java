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
	// SpawnEggItem and into the item model definition, and 1.21.5 removed the shared egg layers that
	// definition tinted, so they are baked into textures/item/ore_lizard_spawn_egg.png instead.
	//
	// Nor is the entity type a constructor argument any more: since 1.21.6 SpawnEggItem is a plain
	// item whose mob comes from a default data component, set through Item.Properties.spawnEgg.
	public static final Item ORE_LIZARD_SPAWN_EGG = Registry.register(
			BuiltInRegistries.ITEM,
			ORE_LIZARD_SPAWN_EGG_KEY,
			new SpawnEggItem(new Item.Properties().setId(ORE_LIZARD_SPAWN_EGG_KEY).spawnEgg(ModEntities.ORE_LIZARD)));

	private ModItems() {
	}

	public static void register() {
		// class-load trigger only; registration happens via the static field above
	}
}
