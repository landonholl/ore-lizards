package com.orelizards.registry;

import com.orelizards.OreLizardsMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;

public final class ModItems {
	// Stone-grey body with a bright crystal highlight, matching the mob's own read.
	private static final int EGG_BACKGROUND_COLOR = 0x6E6E6E;
	private static final int EGG_HIGHLIGHT_COLOR = 0x63E1FF;

	public static final Item ORE_LIZARD_SPAWN_EGG = Registry.register(
			BuiltInRegistries.ITEM,
			new ResourceLocation(OreLizardsMod.MOD_ID, "ore_lizard_spawn_egg"),
			new SpawnEggItem(ModEntities.ORE_LIZARD, EGG_BACKGROUND_COLOR, EGG_HIGHLIGHT_COLOR, new Item.Properties()));

	private ModItems() {
	}

	public static void register() {
		// class-load trigger only; registration happens via the static field above
	}
}
