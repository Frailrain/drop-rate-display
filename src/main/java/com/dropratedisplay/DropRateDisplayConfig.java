package com.dropratedisplay;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(DropRateDisplayConfig.GROUP)
public interface DropRateDisplayConfig extends Config
{
	String GROUP = "dropratedisplay";

	@ConfigItem(
		keyName = "showGroundItemRates",
		name = "Show rates on ground items",
		description = "Display drop rates next to item names where a monster died",
		position = 1
	)
	default boolean showGroundItemRates()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showChatRates",
		name = "Show rates in chat",
		description = "Print drop rates in the chatbox for pickpocket, salvage and chest loot",
		position = 2
	)
	default boolean showChatRates()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showInventoryRates",
		name = "Show rates on inventory items",
		description = "Briefly show the drop rate on items received from pickpockets, salvage and chests (about 30 seconds)",
		position = 3
	)
	default boolean showInventoryRates()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showRewardInterfaceRates",
		name = "Show rates on reward interfaces",
		description = "Draw drop rates over the items shown on reward screens (Barrows, raids, Moons, caskets, etc.)",
		position = 4
	)
	default boolean showRewardInterfaceRates()
	{
		return true;
	}

	@ConfigItem(
		keyName = "minimumRarity",
		name = "Minimum rarity to display",
		description = "Only show rates for items rarer than this (e.g. 25 means 1/25 or rarer). Set to 0 to show all.",
		position = 5
	)
	default int minimumRarity()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "rateFormat",
		name = "Rate format",
		description = "How rates are written. Exact: 100/2,440. 1 in X: 1/24.4 (as on the wiki). "
			+ "1 in X (rounded): 1/24.",
		position = 6
	)
	default RateFormat rateFormat()
	{
		return RateFormat.ONE_IN_X;
	}

	@ConfigItem(
		keyName = "colourByRarity",
		name = "Colour by rarity",
		description = "Colour the rate by how rare it is: yellow (common), green, purple, then red (ultra-rare)",
		position = 7
	)
	default boolean colourByRarity()
	{
		return true;
	}

	@ConfigItem(
		keyName = "rateColor",
		name = "Rate text colour",
		description = "Rate text colour when 'Colour by rarity' is off (and the fallback for un-ranked rates)",
		position = 8
	)
	default Color rateColor()
	{
		return new Color(255, 215, 0); // Gold
	}

	@ConfigItem(
		keyName = "showQualitativeRates",
		name = "Show qualitative rates",
		description = "Display 'Uncommon', 'Rare' etc. when the exact rate is unknown",
		position = 9
	)
	default boolean showQualitativeRates()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showGuaranteedDrops",
		name = "Show guaranteed drops",
		description = "Also show 'Always' for 100% drops such as bones and ashes",
		position = 10
	)
	default boolean showGuaranteedDrops()
	{
		return false;
	}

	@ConfigItem(
		keyName = "mergeWithGroundItems",
		name = "Merge with Ground Items",
		description = "When the Ground Items plugin is enabled, append the rate to each item's line instead "
			+ "of drawing a separate labelled line",
		position = 11
	)
	default boolean mergeWithGroundItems()
	{
		return true;
	}
}
