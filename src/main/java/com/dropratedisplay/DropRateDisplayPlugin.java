package com.dropratedisplay;

import com.google.inject.Provides;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Drop Rate Display",
	description = "Shows drop rates for received loot. Monster drops show the rate on the ground item; "
		+ "pickpocket, salvage and chest loot show the rate in chat and on the received item.",
	tags = {"drops", "loot", "rate", "rarity", "wiki"}
)
public class DropRateDisplayPlugin extends Plugin
{
	// Verified against LootTrackerPlugin source. "the" is optional; possessive may be absent ('s?);
	// trailing text is permitted (.*). Named group: target.
	private static final Pattern PICKPOCKET_PATTERN = Pattern.compile("You pick (the )?(?<target>.+)'s? pocket.*");
	private static final Pattern SALVAGE_PATTERN = Pattern.compile("You sort through the\\s+(?<tier>\\S+)\\s+salvage.*");
	private static final Pattern CLUE_COMPLETED_PATTERN = Pattern.compile("You have completed [0-9]+ ([a-z]+) Treasure Trails?\\.");

	// Loot arriving in the inventory is matched to its trigger within this many game ticks either way,
	// so it works regardless of whether the chat message or the container change fires first.
	private static final int MATCH_WINDOW_TICKS = 1;

	// Opened loot containers: most name their source after the item itself, so we default to the item
	// name and only override where the wiki page differs. Triggering on any of these menu options and
	// letting the data-store lookup self-filter keeps this open-ended without a giant item-id table.
	private static final String FOUND_LOOT_MESSAGE = "You found some loot: ";
	private static final String HERBIBOAR_MESSAGE = "You harvest herbs from the herbiboar, whereupon it escapes.";
	private static final String UNSIRED_MESSAGE = "You place the Unsired into the Font of Consumption...";
	private static final Map<Integer, String> OPENED_ITEM_SOURCES = new HashMap<>();

	static
	{
		OPENED_ITEM_SOURCES.put(ItemID.SEEDBOX, "Seed pack");
		OPENED_ITEM_SOURCES.put(ItemID.CONSTRUCTION_SUPPLY_CRATE, "Supply crate (Mahogany Homes)");
		OPENED_ITEM_SOURCES.put(ItemID.SOUL_WARS_SPOILS, "Spoils of war");
	}

	private static final int TEMPOROSS_REGION = 12588;
	private static final int GUARDIANS_OF_THE_RIFT_REGION = 14484;
	private static final int WINTERTODT_REGION = 6461;

	// World chests: a "looted" message plus the region identifies the chest; loot lands in the inventory.
	private static final Pattern GRUBBY_CHEST_PATTERN =
		Pattern.compile("You find treasure(?: and supplies|, supplies, and a weirdly coloured egg sac) within the chest\\.");
	private static final Map<Integer, String> CHEST_REGIONS = new HashMap<>();

	static
	{
		CHEST_REGIONS.put(5179, "Brimstone Chest");
		CHEST_REGIONS.put(11573, "Crystal Chest");
		CHEST_REGIONS.put(12093, "Larran's big chest");
		CHEST_REGIONS.put(13113, "Larran's small chest");
		CHEST_REGIONS.put(13151, "Elven Crystal Chest");
		CHEST_REGIONS.put(5277, "Stone chest");
		CHEST_REGIONS.put(10835, "Dorgesh-Kaan Chest");
		CHEST_REGIONS.put(10834, "Dorgesh-Kaan Chest");
		CHEST_REGIONS.put(7323, "Grubby Chest");
		CHEST_REGIONS.put(8593, "Isle of Souls Chest");
		CHEST_REGIONS.put(7827, "Dark Chest");
		CHEST_REGIONS.put(13117, "Rogues' Chest");
		CHEST_REGIONS.put(13156, "Chest (Ancient Vault)");
		CHEST_REGIONS.put(12348, "Muddy Chest");
		CHEST_REGIONS.put(5422, "Chest (Aldarin Villas)");
		CHEST_REGIONS.put(6550, "Chest (Moon key)");
		CHEST_REGIONS.put(5521, "Chest (Alchemist's signet)");
		CHEST_REGIONS.put(12073, "Rusty chest");
		CHEST_REGIONS.put(7470, "Rusty chest");
		CHEST_REGIONS.put(6187, "Tarnished chest");
		CHEST_REGIONS.put(6953, "Tarnished chest");
		CHEST_REGIONS.put(7743, "Reinforced chest");
		CHEST_REGIONS.put(8758, "Reinforced chest");
	}

	@Inject
	Client client;

	@Inject
	DropRateDisplayConfig config;

	@Inject
	DropRateDataStore dataStore;

	@Inject
	ItemManager itemManager;

	@Inject
	DropRateDisplayOverlay overlay;

	@Inject
	DropRateInventoryOverlay inventoryOverlay;

	@Inject
	RewardInterfaceOverlay rewardOverlay;

	@Inject
	ChatMessageManager chatMessageManager;

	@Inject
	private OverlayManager overlayManager;

	// Non-NPC loot detection: a rolling inventory snapshot is kept up to date, and a trigger
	// (chat message or reward widget) names the source. When both a source and a fresh set of added
	// items exist within MATCH_WINDOW_TICKS, they are paired -- order-independent.
	private Map<Integer, Integer> inventorySnapshot = new HashMap<>();
	private Map<Integer, Integer> recentAdded;
	private int recentAddedTick = Integer.MIN_VALUE;
	private String pendingSource;
	private int pendingTick = Integer.MIN_VALUE;

	@Provides
	DropRateDisplayConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DropRateDisplayConfig.class);
	}

	@Override
	protected void startUp()
	{
		dataStore.load();
		overlayManager.add(overlay);
		overlayManager.add(inventoryOverlay);
		overlayManager.add(rewardOverlay);
		resetState();
		log.debug("Drop Rate Display started ({} sources)", dataStore.getSourceCount());
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(inventoryOverlay);
		overlayManager.remove(rewardOverlay);
		overlay.clear();
		inventoryOverlay.clear();
		resetState();
	}

	// --- Monster kills: floor drops rendered on the ground -----------------------------------------

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		if (!config.showGroundItemRates())
		{
			return;
		}

		NPC npc = event.getNpc();
		if (npc == null)
		{
			return;
		}

		SourceDropTable table = dataStore.getSource(npc.getName());
		if (table == null)
		{
			return;
		}

		WorldPoint location = npc.getWorldLocation();
		if (location == null)
		{
			return;
		}

		for (ItemStack item : event.getItems())
		{
			String itemName = itemManager.getItemComposition(item.getId()).getName();
			DropRateEntry entry = table.getDrop(itemName);
			if (entry == null)
			{
				continue;
			}

			// Store the raw wiki rate; the overlay applies the format, rarity filter and colour each frame,
			// so changing any of those settings updates what is already on screen.
			overlay.addGroundRate(location, item.getId(), itemName, entry.getRate());
		}
	}

	// --- Non-NPC loot: chat/widget triggers paired with the inventory change -----------------------

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		ChatMessageType type = event.getType();
		if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM)
		{
			return;
		}

		String message = event.getMessage();

		// Remember the most recently completed clue tier so we can name the reward casket source.
		Matcher clue = CLUE_COMPLETED_PATTERN.matcher(message);
		if (clue.find())
		{
			// The clue reward screen reuses one interface across tiers; tell the overlay which casket so
			// it can draw the right table when the reward interface opens.
			rewardOverlay.setClueCasketSource("Reward casket (" + clue.group(1) + ")");
			return;
		}

		if (message.equals(HERBIBOAR_MESSAGE))
		{
			beginPending("Herbiboar");
			return;
		}
		if (message.equals(UNSIRED_MESSAGE))
		{
			beginPending("Unsired");
			return;
		}

		Matcher pickpocket = PICKPOCKET_PATTERN.matcher(message);
		if (pickpocket.matches())
		{
			String source = Text.removeTags(pickpocket.group("target"));
			log.debug("[Drop Rate] pickpocket trigger -> '{}'", source);
			beginPending(source);
			return;
		}

		Matcher salvage = SALVAGE_PATTERN.matcher(message);
		if (salvage.matches())
		{
			String source = Text.removeTags(salvage.group("tier")) + " salvage";
			log.debug("[Drop Rate] salvage trigger -> '{}'", source);
			beginPending(source);
			return;
		}

		// Skilling minigames (Tempoross / Guardians of the Rift / Wintertodt) announce loot with a common
		// message then deposit it to the inventory; the region tells us which activity it is.
		if (message.contains(FOUND_LOOT_MESSAGE))
		{
			String source = minigameSourceForRegion();
			if (source != null)
			{
				log.debug("[Drop Rate] minigame loot trigger -> '{}'", source);
				beginPending(source);
			}
			return;
		}

		// World chests: the region identifies which chest was looted; the loot lands in the inventory.
		if (isChestLootMessage(message))
		{
			String source = CHEST_REGIONS.get(playerRegion());
			if (source != null)
			{
				log.debug("[Drop Rate] chest trigger -> '{}'", source);
				beginPending(source);
			}
		}
	}

	private static boolean isChestLootMessage(String message)
	{
		return message.equals("You find some treasure in the chest!")
			|| message.equals("You steal some loot from the chest.")
			|| message.equals("You find treasure inside!")
			|| message.equals("You take some loot from inside.")
			|| message.startsWith("You open the chest and find")
			|| GRUBBY_CHEST_PATTERN.matcher(message).matches();
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!event.isItemOp())
		{
			return;
		}

		String option = event.getMenuOption();
		if (!option.equals("Open") && !option.equals("Search") && !option.equals("Take") && !option.equals("Take-all"))
		{
			return;
		}

		// Opening a loot container deposits its contents to the inventory. Name the source after the item
		// (overridden where the wiki page differs), then let the inventory diff + data lookup self-filter:
		// non-loot items simply resolve to no drop table and show nothing.
		int itemId = event.getItemId();
		String source = OPENED_ITEM_SOURCES.get(itemId);
		if (source == null)
		{
			source = itemManager.getItemComposition(itemId).getName();
		}
		beginPending(source);
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.INV)
		{
			return;
		}
		handleInventoryChange(toItemMap(event.getItemContainer()));
	}

	/** Core inventory-diff step, split out from the event handler so it can be driven directly in tests. */
	void handleInventoryChange(Map<Integer, Integer> current)
	{
		Map<Integer, Integer> added = InventoryDiff.added(inventorySnapshot, current);
		inventorySnapshot = current;

		if (!added.isEmpty())
		{
			recentAdded = added;
			recentAddedTick = client.getTickCount();
		}

		tryMatch();
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		int tick = client.getTickCount();
		if (pendingSource != null && tick - pendingTick > MATCH_WINDOW_TICKS)
		{
			pendingSource = null;
		}
		if (recentAdded != null && tick - recentAddedTick > MATCH_WINDOW_TICKS)
		{
			recentAdded = null;
		}
	}

	void beginPending(String source)
	{
		if (!config.showChatRates() && !config.showInventoryRates())
		{
			return;
		}

		pendingSource = source;
		pendingTick = client.getTickCount();
		tryMatch();
	}

	/** Pairs a pending source with recently added items when both are present within the match window. */
	private void tryMatch()
	{
		if (pendingSource == null || recentAdded == null)
		{
			return;
		}
		if (Math.abs(pendingTick - recentAddedTick) > MATCH_WINDOW_TICKS)
		{
			return;
		}

		String source = pendingSource;
		Map<Integer, Integer> added = recentAdded;
		pendingSource = null;
		recentAdded = null;
		processInventoryLoot(source, added);
	}

	private void processInventoryLoot(String source, Map<Integer, Integer> added)
	{
		SourceDropTable table = dataStore.getSource(source);
		if (table == null)
		{
			log.debug("[Drop Rate] no drop table for inventory source '{}'", source);
			return;
		}

		String displaySource = table.getSourceName() != null ? table.getSourceName() : source;

		for (Integer itemId : added.keySet())
		{
			String itemName = itemManager.getItemComposition(itemId).getName();
			DropRateEntry entry = table.getDrop(itemName);
			if (entry == null)
			{
				continue;
			}

			// Chat is one-shot, so it is filtered and formatted now. The inventory icon stores the raw
			// rate and lets the overlay filter / format / colour it live.
			if (config.showChatRates() && shouldDisplay(entry.getRate()))
			{
				sendChatMessage(itemName, displaySource, RateParser.format(entry.getRate(), config.chatRateFormat()));
			}
			if (config.showInventoryRates())
			{
				inventoryOverlay.addRate(itemId, entry.getRate());
			}
		}
	}

	private String minigameSourceForRegion()
	{
		int region = playerRegion();
		if (region == TEMPOROSS_REGION)
		{
			return "Tempoross";
		}
		if (region == GUARDIANS_OF_THE_RIFT_REGION)
		{
			return "Guardians of the Rift";
		}
		if (region == WINTERTODT_REGION)
		{
			return "Wintertodt";
		}
		return null;
	}

	private int playerRegion()
	{
		Player player = client.getLocalPlayer();
		if (player == null || player.getWorldLocation() == null)
		{
			return -1;
		}
		return player.getWorldLocation().getRegionID();
	}

	// --- Shared helpers ----------------------------------------------------------------------------

	private boolean shouldDisplay(String rate)
	{
		return RateParser.shouldDisplay(rate, config.minimumRarity(), config.showQualitativeRates(),
			config.showGuaranteedDrops());
	}

	private void sendChatMessage(String itemName, String source, String rate)
	{
		String message = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("[Drop Rate] ")
			.append(ChatColorType.NORMAL)
			.append(itemName)
			.append(" from ")
			.append(source)
			.append(": ")
			.append(ChatColorType.HIGHLIGHT)
			.append(rate)
			.build();

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(message)
			.build());
	}

	private static Map<Integer, Integer> toItemMap(ItemContainer container)
	{
		Map<Integer, Integer> map = new HashMap<>();
		if (container == null)
		{
			return map;
		}

		for (Item item : container.getItems())
		{
			if (item.getId() >= 0)
			{
				map.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		return map;
	}

	private void resetState()
	{
		pendingSource = null;
		recentAdded = null;
		inventorySnapshot = new HashMap<>();
		rewardOverlay.setClueCasketSource(null);
	}
}
