package com.dropratedisplay;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
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

	// Skilling minigames announce loot then deposit it to the inventory; the region disambiguates which.
	private static final String FOUND_LOOT_MESSAGE = "You found some loot: ";
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

	// Reward interfaces that hold their loot in a dedicated container: on load we read that container
	// directly (source name resolved via the data store's overrides). Interface + container IDs mirror
	// how RuneLite's own Loot Tracker reads them, using core gameval constants (no runtime dependency).
	private static final Map<Integer, RewardInterface> REWARD_INTERFACES = new HashMap<>();

	static
	{
		REWARD_INTERFACES.put(InterfaceID.BARROWS_REWARD, new RewardInterface("Barrows", InventoryID.TRAIL_REWARDINV));
		REWARD_INTERFACES.put(InterfaceID.TRAIL_REWARDSCREEN, new RewardInterface(null, InventoryID.TRAIL_REWARDINV));
		REWARD_INTERFACES.put(InterfaceID.RAIDS_REWARDS, new RewardInterface("Chambers of Xeric", InventoryID.RAIDS_REWARDS));
		REWARD_INTERFACES.put(InterfaceID.TOB_CHESTS, new RewardInterface("Theatre of Blood", InventoryID.TOB_CHESTS));
		REWARD_INTERFACES.put(InterfaceID.TOA_CHESTS, new RewardInterface("Tombs of Amascut", InventoryID.TOA_CHESTS));
		REWARD_INTERFACES.put(InterfaceID.PMOON_REWARD, new RewardInterface("Lunar Chest", InventoryID.PMOON_REWARDINV));
		REWARD_INTERFACES.put(InterfaceID.COLOSSEUM_REWARD_CHEST_2, new RewardInterface("Fortis Colosseum", InventoryID.COLOSSEUM_REWARDS));
		REWARD_INTERFACES.put(InterfaceID.TRAWLER_REWARD, new RewardInterface("Fishing Trawler", InventoryID.TRAWLER_REWARDINV));
		REWARD_INTERFACES.put(InterfaceID.FOSSIL_DRIFTNET, new RewardInterface("Drift Net", InventoryID.MACRO_CERTER));
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
	private String lastClueTier;

	// Last item set read from each reward container, so a re-fired reward interface is not re-announced.
	private final Map<Integer, List<Integer>> lastRewardItems = new HashMap<>();

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
		resetState();
		log.debug("Drop Rate Display started ({} sources)", dataStore.getSourceCount());
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(inventoryOverlay);
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
			if (entry == null || !shouldDisplay(entry.getRate()))
			{
				continue;
			}

			// Ground rows have room (the rate sits after the item's full line), so show the exact wiki
			// rate; only the space-constrained inventory icon uses the rounded 1/N form.
			overlay.addGroundRate(location, item.getId(), itemName, RateParser.formatRateFull(entry.getRate()));
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
			lastClueTier = clue.group(1);
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
	public void onWidgetLoaded(WidgetLoaded event)
	{
		RewardInterface reward = REWARD_INTERFACES.get(event.getGroupId());
		if (reward == null)
		{
			return;
		}

		String source = reward.source;
		if (source == null)
		{
			// Clue casket: name the source from the most recently completed clue tier.
			source = lastClueTier != null ? "Reward casket (" + lastClueTier + ")" : "Reward casket";
		}
		processRewardContainer(source, reward.containerId);
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
			if (entry == null || !shouldDisplay(entry.getRate()))
			{
				continue;
			}

			if (config.showChatRates())
			{
				sendChatMessage(itemName, displaySource, RateParser.formatRateFull(entry.getRate()));
			}
			if (config.showInventoryRates())
			{
				inventoryOverlay.addRate(itemId, RateParser.formatRate(entry.getRate()));
			}
		}
	}

	/** Reads a reward interface's own item container (raids, Barrows, Moons, etc.) and shows its rates. */
	private void processRewardContainer(String source, int containerId)
	{
		if (!config.showChatRates() && !config.showInventoryRates())
		{
			return;
		}

		SourceDropTable table = dataStore.getSource(source);
		if (table == null)
		{
			log.debug("[Drop Rate] no drop table for reward source '{}'", source);
			return;
		}

		ItemContainer container = client.getItemContainer(containerId);
		if (container == null)
		{
			return;
		}

		List<Integer> itemIds = new ArrayList<>();
		for (Item item : container.getItems())
		{
			if (item.getId() >= 0)
			{
				itemIds.add(item.getId());
			}
		}
		// Reward interfaces can re-fire their WidgetLoaded; only act when the contents actually change.
		if (itemIds.isEmpty() || itemIds.equals(lastRewardItems.get(containerId)))
		{
			return;
		}
		lastRewardItems.put(containerId, itemIds);

		String displaySource = table.getSourceName() != null ? table.getSourceName() : source;
		for (Item item : container.getItems())
		{
			if (item.getId() < 0)
			{
				continue;
			}
			String itemName = itemManager.getItemComposition(item.getId()).getName();
			DropRateEntry entry = table.getDrop(itemName);
			if (entry == null || !shouldDisplay(entry.getRate()))
			{
				continue;
			}

			if (config.showChatRates())
			{
				sendChatMessage(itemName, displaySource, RateParser.formatRateFull(entry.getRate()));
			}
			if (config.showInventoryRates())
			{
				inventoryOverlay.addRate(item.getId(), RateParser.formatRate(entry.getRate()));
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

	/**
	 * Whether a rate should be shown given the user's filters: never for guaranteed drops, numeric
	 * rates gated by the minimum-rarity threshold, and qualitative labels only when opted in.
	 */
	private boolean shouldDisplay(String rate)
	{
		if (rate == null || rate.trim().isEmpty())
		{
			return false;
		}

		if (RateParser.isAlways(rate))
		{
			return config.showGuaranteedDrops();
		}

		double denominator = RateParser.parseDenominator(rate);
		if (denominator > 0)
		{
			int minimum = config.minimumRarity();
			return minimum <= 0 || denominator >= minimum;
		}

		return RateParser.isQualitative(rate) && config.showQualitativeRates();
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
		lastClueTier = null;
		inventorySnapshot = new HashMap<>();
		lastRewardItems.clear();
	}

	/** A reward interface's source name (null = derive, e.g. clue caskets) and the container to read. */
	private static final class RewardInterface
	{
		private final String source;
		private final int containerId;

		private RewardInterface(String source, int containerId)
		{
			this.source = source;
			this.containerId = containerId;
		}
	}
}
