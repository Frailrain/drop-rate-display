package com.dropratedisplay;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(DropRateDisplayConfig.GROUP)
public interface DropRateDisplayConfig extends Config
{
	String GROUP = "dropratedisplay";

	@ConfigSection(
		name = "Displays",
		description = "Where drop rates are shown",
		position = 1
	)
	String DISPLAYS_SECTION = "displays";

	@ConfigSection(
		name = "Filters",
		description = "Which rates are shown",
		position = 2
	)
	String FILTERS_SECTION = "filters";

	@ConfigSection(
		name = "Rate format",
		description = "How the rate reads on each surface. Exact: 100/2,440. 1 in X: 1/24.4. Rounded: 1/24.",
		position = 3
	)
	String FORMAT_SECTION = "format";

	@ConfigSection(
		name = "Rarity colours",
		description = "The rate is coloured by how rare the drop is; set each tier's colour here",
		position = 4
	)
	String COLOUR_SECTION = "colours";

	// --- Displays ---------------------------------------------------------------------------------

	@ConfigItem(
		keyName = "showGroundItemRates",
		name = "Ground items",
		description = "Display drop rates where a monster died (appended to the Ground Items line when that plugin is on)",
		section = DISPLAYS_SECTION,
		position = 1
	)
	default boolean showGroundItemRates()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showChatRates",
		name = "Chat",
		description = "Print drop rates in the chatbox for pickpocket, salvage and chest loot",
		section = DISPLAYS_SECTION,
		position = 2
	)
	default boolean showChatRates()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showInventoryRates",
		name = "Inventory items",
		description = "Briefly show the drop rate on items received from pickpockets, salvage and chests (about 30 seconds)",
		section = DISPLAYS_SECTION,
		position = 3
	)
	default boolean showInventoryRates()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showRewardInterfaceRates",
		name = "Reward screens",
		description = "Draw drop rates over the items shown on reward screens (Barrows, raids, Moons, caskets, etc.)",
		section = DISPLAYS_SECTION,
		position = 4
	)
	default boolean showRewardInterfaceRates()
	{
		return true;
	}

	@ConfigItem(
		keyName = "mergeWithGroundItems",
		name = "Merge with Ground Items",
		description = "Place the rate flush after each item's Ground Items line (e.g. \"Abyssal whip (1/512)\"), "
			+ "aligned per item for 2+ drops. Turn off if you don't run the Ground Items plugin to get a "
			+ "self-labelled \"Item (rate)\" line instead.",
		section = DISPLAYS_SECTION,
		position = 5
	)
	default boolean mergeWithGroundItems()
	{
		return true;
	}

	// --- Filters ----------------------------------------------------------------------------------

	@ConfigItem(
		keyName = "minimumRarity",
		name = "Minimum rarity",
		description = "Only show rates for items rarer than this (e.g. 25 means 1/25 or rarer). Set to 0 to show all.",
		section = FILTERS_SECTION,
		position = 1
	)
	default int minimumRarity()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "showQualitativeRates",
		name = "Show qualitative rates",
		description = "Display 'Uncommon', 'Rare' etc. when the exact rate is unknown",
		section = FILTERS_SECTION,
		position = 2
	)
	default boolean showQualitativeRates()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showGuaranteedDrops",
		name = "Show guaranteed drops",
		description = "Also show 'Always' for 100% drops such as bones and ashes",
		section = FILTERS_SECTION,
		position = 3
	)
	default boolean showGuaranteedDrops()
	{
		return false;
	}

	// --- Rate format (roomy surfaces default exact, tiny icons rounded) ---------------------------

	@ConfigItem(
		keyName = "groundRateFormat",
		name = "Floor drops",
		description = "How the rate reads on ground items",
		section = FORMAT_SECTION,
		position = 1
	)
	default RateFormat groundRateFormat()
	{
		return RateFormat.EXACT;
	}

	@ConfigItem(
		keyName = "chatRateFormat",
		name = "Chat",
		description = "How the rate reads in chat",
		section = FORMAT_SECTION,
		position = 2
	)
	default RateFormat chatRateFormat()
	{
		return RateFormat.EXACT;
	}

	@ConfigItem(
		keyName = "rewardRateFormat",
		name = "Reward screens",
		description = "How the rate reads on reward-screen items",
		section = FORMAT_SECTION,
		position = 3
	)
	default RateFormat rewardRateFormat()
	{
		return RateFormat.ONE_IN_X_ROUNDED;
	}

	@ConfigItem(
		keyName = "inventoryRateFormat",
		name = "Inventory items",
		description = "How the rate reads on inventory item icons",
		section = FORMAT_SECTION,
		position = 4
	)
	default RateFormat inventoryRateFormat()
	{
		return RateFormat.ONE_IN_X_ROUNDED;
	}

	// --- Rarity colours (thresholds: <=1/50, <=1/500, <=1/5,000, then rarer) ----------------------

	@ConfigItem(
		keyName = "commonColour",
		name = "Common",
		description = "Colour for common drops (1/50 or more common)",
		section = COLOUR_SECTION,
		position = 1
	)
	default Color commonColour()
	{
		return new Color(255, 224, 66); // yellow
	}

	@ConfigItem(
		keyName = "uncommonColour",
		name = "Uncommon",
		description = "Colour for uncommon drops (rarer than 1/50, up to 1/500)",
		section = COLOUR_SECTION,
		position = 2
	)
	default Color uncommonColour()
	{
		return new Color(86, 214, 86); // green
	}

	@ConfigItem(
		keyName = "rareColour",
		name = "Rare",
		description = "Colour for rare drops (rarer than 1/500, up to 1/5,000)",
		section = COLOUR_SECTION,
		position = 3
	)
	default Color rareColour()
	{
		return new Color(190, 120, 255); // purple
	}

	@ConfigItem(
		keyName = "ultraRareColour",
		name = "Ultra-rare",
		description = "Colour for ultra-rare drops (rarer than 1/5,000)",
		section = COLOUR_SECTION,
		position = 4
	)
	default Color ultraRareColour()
	{
		return new Color(255, 74, 74); // red
	}
}
