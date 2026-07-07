package com.dropratedisplay;

import java.awt.Color;
import java.util.Locale;

/**
 * Buckets a drop rate into a rarity tier and resolves the tier's configured colour, so a glance at the
 * colour tells you roughly how rare a drop is. Thresholds climb {@code 1/50 -> 1/500 -> 1/5,000 -> rarer};
 * guaranteed drops sit at the common end and qualitative labels map to the obvious tier.
 */
final class RarityColor
{
	enum Tier
	{
		COMMON, UNCOMMON, RARE, ULTRA_RARE
	}

	private RarityColor()
	{
	}

	/** The rarity tier for a rate by its effective "1 in N" denominator (or by its qualitative label). */
	static Tier tierFor(String rate)
	{
		double denominator = RateParser.parseDenominator(rate);
		if (denominator < 0)
		{
			// "Always" — guaranteed, i.e. the least rare thing there is.
			return Tier.COMMON;
		}
		if (denominator == 0)
		{
			// Qualitative label (or unparseable): map the label to the obvious tier.
			String label = rate == null ? "" : rate.trim().toLowerCase(Locale.ROOT);
			switch (label)
			{
				case "very rare":
					return Tier.ULTRA_RARE;
				case "rare":
					return Tier.RARE;
				case "uncommon":
					return Tier.UNCOMMON;
				default:
					return Tier.COMMON;
			}
		}
		if (denominator <= 50)
		{
			return Tier.COMMON;
		}
		if (denominator <= 500)
		{
			return Tier.UNCOMMON;
		}
		if (denominator <= 5000)
		{
			return Tier.RARE;
		}
		return Tier.ULTRA_RARE;
	}

	/** The configured colour for a rate's rarity tier. */
	static Color colour(String rate, DropRateDisplayConfig config)
	{
		switch (tierFor(rate))
		{
			case UNCOMMON:
				return config.uncommonColour();
			case RARE:
				return config.rareColour();
			case ULTRA_RARE:
				return config.ultraRareColour();
			case COMMON:
			default:
				return config.commonColour();
		}
	}
}
