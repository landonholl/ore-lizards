package com.orelizards.entity;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public enum OreVariant {
	COAL(0x2B2B2B, Items.COAL, 0),
	IRON(0xB8B1AA, Items.RAW_IRON, 10),
	GOLD(0xFFED00, Items.RAW_GOLD, 10),
	REDSTONE(0x9C0D0D, Items.REDSTONE, 10),
	LAPIS(0x1A51B8, Items.LAPIS_LAZULI, 10),
	DIAMOND(0x54BFD9, Items.DIAMOND, 25),
	EMERALD(0x30A758, Items.EMERALD, 25),
	COPPER(0xA75A2C, Items.RAW_COPPER, 10);

	private final int tintColor;
	private final Item dropItem;
	// Weight used only when picking a variant for a lizard that spawned on deepslate:
	// coal (0) never spawns there, diamond/emerald are weighted well above the rest.
	private final int deepslateWeight;

	OreVariant(int tintColor, Item dropItem, int deepslateWeight) {
		this.tintColor = tintColor;
		this.dropItem = dropItem;
		this.deepslateWeight = deepslateWeight;
	}

	public int getTintColor() {
		return this.tintColor;
	}

	public Item getDropItem() {
		return this.dropItem;
	}

	/**
	 * Uniform across every variant - used when the lizard spawned on regular stone.
	 */
	public static OreVariant random(RandomSource random) {
		OreVariant[] values = values();
		return values[random.nextInt(values.length)];
	}

	/**
	 * Weighted towards diamond/emerald and excludes coal entirely - used when the lizard
	 * spawned on deepslate.
	 */
	public static OreVariant randomDeepslate(RandomSource random) {
		int totalWeight = 0;
		for (OreVariant variant : values()) {
			totalWeight += variant.deepslateWeight;
		}

		int roll = random.nextInt(totalWeight);
		for (OreVariant variant : values()) {
			roll -= variant.deepslateWeight;
			if (roll < 0) {
				return variant;
			}
		}

		throw new IllegalStateException("Unreachable: deepslate weights did not cover the full roll range");
	}
}
