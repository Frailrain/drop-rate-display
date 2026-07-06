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

		// Diagnostic: surface any salvage-related message that did not match, so the pattern can be fixed.
		if (log.isDebugEnabled() && message.toLowerCase().contains("salvage"))
		{
			log.debug("[Drop Rate] unmatched salvage-ish message: '{}'", message);
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		int group = event.getGroupId();
		if (group == InterfaceID.BARROWS_REWARD)
		{
			beginPending("Barrows");
		}
		else if (group == InterfaceID.TRAIL_REWARDSCREEN)
		{
			beginPending(lastClueTier != null ? "Reward casket (" + lastClueTier + ")" : "Reward casket");
		}
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
	}
}
