package com.orelizards.entity;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.Random;

/**
 * <p>Tint colors are measured, not eyeballed: each one is the mean of every pixel in that mineral's
 * solid block texture from the 1.20.1 client jar. Not the <em>ore</em> block, which is mostly its
 * stone matrix and averages out grey. For the two metals that have one there, the <em>raw</em>
 * block is used ({@code raw_iron_block}, {@code raw_gold_block}), since raw metal is what those
 * variants drop on 1.20.1 and it looks nothing like the refined bar - raw iron is a tan-brown,
 * where an iron block is near-white. The remaining five have no raw form, so they come from
 * {@code coal_block}, {@code redstone_block}, {@code lapis_block}, {@code diamond_block} and
 * {@code emerald_block}. The mean is used rather than the most common pixel because it is what the
 * block reads as at a glance, once its speckling blurs together.
 *
 * <p>1.16.5 has no raw ores, so the iron and gold variants drop ingots instead - the nearest thing
 * to "the metal, unrefined" that exists here, and the same value a furnace would have turned the
 * raw ore into. Their tints are deliberately left as the raw-block means rather than re-derived
 * from the ingot textures, so a lizard reads the same colour on every version. Copper is absent
 * altogether: neither the ore nor the metal exists before 1.17.
 *
 * <p>These land on the model almost exactly as written: the tint multiplies the shard texture,
 * whose pixels average 229/255, and the eye texture, which averages 252/255 - so there is no
 * meaningful darkening to compensate for. Note the emissive pass then adds another
 * {@code GLOW_STRENGTH} of the same color on top, so what finally reaches the screen is brighter
 * and less saturated than the value here; that knob, not these, is what to turn if the glow washes
 * the hues out.
 */
public enum OreVariant {
	COAL(0x101010, Items.COAL, 0, DropTier.BULK),
	IRON(0xA6886B, Items.IRON_INGOT, 10, DropTier.BULK),
	GOLD(0xDEA92F, Items.GOLD_INGOT, 10, DropTier.PRECIOUS),
	REDSTONE(0xB01905, Items.REDSTONE, 10, DropTier.BULK),
	LAPIS(0x1F438C, Items.LAPIS_LAZULI, 10, DropTier.BULK),
	DIAMOND(0x62EDE4, Items.DIAMOND, 25, DropTier.PRECIOUS),
	EMERALD(0x2ACB58, Items.EMERALD, 25, DropTier.PRECIOUS);

	/**
	 * How much a variant drops. Split in two because the cheap ores are only worth killing for if
	 * you get a proper handful, whereas the valuable ones would be worth killing for at any amount -
	 * so those pay out smaller, with an occasional windfall instead.
	 */
	private enum DropTier {
		/** Coal, iron, redstone, lapis. */
		BULK(4, 6, 0),
		/** Gold, diamond, emerald. */
		PRECIOUS(2, 4, 2);

		/** What a {@link #jackpotChancePercent} roll pays out, in place of the normal range. */
		private static final int JACKPOT_COUNT = 6;

		private final int minCount;
		private final int maxCount;
		private final int jackpotChancePercent;

		DropTier(int minCount, int maxCount, int jackpotChancePercent) {
			this.minCount = minCount;
			this.maxCount = maxCount;
			this.jackpotChancePercent = jackpotChancePercent;
		}

		private int rollCount(Random random) {
			if (this.jackpotChancePercent > 0 && random.nextInt(100) < this.jackpotChancePercent) {
				return JACKPOT_COUNT;
			}
			return this.minCount + random.nextInt(this.maxCount - this.minCount + 1);
		}
	}

	private final int tintColor;
	private final Item dropItem;
	// Weight used only when picking a variant for a lizard that spawned on deepslate:
	// coal (0) never spawns there, diamond/emerald are weighted well above the rest. 1.16.5 has no
	// deepslate, so on this version the table is kept for parity with the other builds but is
	// never consulted - see OreLizardEntity.finalizeSpawn.
	private final int deepslateWeight;
	private final DropTier dropTier;

	OreVariant(int tintColor, Item dropItem, int deepslateWeight, DropTier dropTier) {
		this.tintColor = tintColor;
		this.dropItem = dropItem;
		this.deepslateWeight = deepslateWeight;
		this.dropTier = dropTier;
	}

	public int getTintColor() {
		return this.tintColor;
	}

	public Item getDropItem() {
		return this.dropItem;
	}

	/**
	 * How many items this variant drops on death. Bulk ores roll 4-6; the precious ones roll 2-4,
	 * with a 2% chance of paying out 6 instead.
	 */
	public int rollDropCount(Random random) {
		return this.dropTier.rollCount(random);
	}

	/**
	 * Looks a variant up by its {@link #name()}, for reading one back out of saved NBT. Returns
	 * {@code null} for anything unrecognised, which is what a world saved by a later version of the
	 * mod - one that has since had a variant renamed or removed - would hand back. On 1.16.5 that
	 * includes {@code COPPER}, which the other versions have and this one cannot.
	 */
	@Nullable
	public static OreVariant byName(String name) {
		for (OreVariant variant : values()) {
			if (variant.name().equals(name)) {
				return variant;
			}
		}
		return null;
	}

	/**
	 * Uniform across every variant - used when the lizard spawned on regular stone.
	 */
	public static OreVariant random(Random random) {
		OreVariant[] values = values();
		return values[random.nextInt(values.length)];
	}

	/**
	 * Weighted towards diamond/emerald and excludes coal entirely - used when the lizard
	 * spawned on deepslate. Unreachable on 1.16.5 (no deepslate); kept so the variant table reads
	 * the same as on every other version.
	 */
	public static OreVariant randomDeepslate(Random random) {
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
