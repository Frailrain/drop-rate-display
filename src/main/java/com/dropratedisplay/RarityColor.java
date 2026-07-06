package com.dropratedisplay;

import java.awt.Color;

/**
 * Escalating colours by rarity, so a glance at the colour tells you roughly how rare a drop is. Tiers are
 * chosen for legibility on any background (paired with a text outline): common drops are the calm yellow
 * you see most, and the scale climbs to an alarm red for the rarest.
 */
final class RarityColor
{
	private static final Color COMMON = new Color(255, 224, 66);      // <= 1/50    yellow
	private static final Color UNCOMMON = new Color(86, 214, 86);     // <= 1/500   green
	private static final Color RARE = new Color(190, 120, 255);       // <= 1/5,000 purple
	private static final Color ULTRA_RARE = new Color(255, 74, 74);   // > 1/5,000  red

	private RarityColor()
	{
	}

	/** Tier colour for a rate by its effective "1 in N" denominator, or null for Always / qualitative rates. */
	static Color forRate(String rate)
	{
		double denominator = RateParser.parseDenominator(rate);
		if (denominator <= 0)
		{
			return null;
		}
		if (denominator <= 50)
		{
			return COMMON;
		}
		if (denominator <= 500)
		{
			return UNCOMMON;
		}
		if (denominator <= 5000)
		{
			return RARE;
		}
		return ULTRA_RARE;
	}

	/** Resolves the text colour: the rarity tier when enabled (falling back to {@code base}), else {@code base}. */
	static Color resolve(String rate, Color base, boolean colourByRarity)
	{
		if (!colourByRarity)
		{
			return base;
		}
		Color tier = forRate(rate);
		return tier != null ? tier : base;
	}
}
