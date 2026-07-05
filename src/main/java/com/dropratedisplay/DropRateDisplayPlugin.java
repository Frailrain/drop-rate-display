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
		+ "pickpocket, salvage and chest loot show the rate in chat.",
	tags = {"drops", "loot", "rate", "rarity", "wiki"}
)
public class DropRateDisplayPlugin extends Plugin
{
	// Verified against LootTrackerPlugin source. "the" is optional; possessive may be absent ('s?);
	// trailing text is permitted (.*). Named group: target.
	private static final Pattern PICKPOCKET_PATTERN = Pattern.compile("You pick (the )?(?<target>.+)'s? pocket.*");
	private static final Pattern SALVAGE_PATTERN = Pattern.compile("You sort through the\\s+(?<tier>\\S+)\\s+salvage.*");
	private static final Pattern CLUE_COMPLETED_PATTERN = Pattern.compile("You have completed [0-9]+ ([a-z]+) Treasure Trails?\\.");

	// Number of game ticks a pending loot trigger waits for the item container to change before expiring.
	private static final int PENDING_TIMEOUT_TICKS = 2;

	@Inject
	private Client client;

	@Inject
	DropRateDisplayConfig config;

	@Inject
	DropRateDataStore dataStore;

	@Inject
	ItemManager itemManager;

	@Inject
	DropRateDisplayOverlay overlay;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	// Non-NPC loot detection state. A trigger (chat/widget) sets a pending source and snapshots the
	// inventory; the next inventory change is diffed against the snapshot to find what arrived.
	private String pendingSource;
	private Map<Integer, Integer> preLootInventory;
	private int pendingTick;
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
		resetPending();
		log.debug("Drop Rate Display started ({} sources)", dataStore.getSourceCount());
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlay.clear();
		resetPending();
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

			overlay.addGroundRate(location, itemName + " (" + RateParser.formatRate(entry.getRate()) + ")");
		}
	}

	// --- Non-NPC loot: chat/widget triggers, then inventory diff -----------------------------------

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
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
			beginPending(Text.removeTags(pickpocket.group("target")));
			return;
		}

		Matcher salvage = SALVAGE_PATTERN.matcher(message);
		if (salvage.matches())
		{
			beginPending(Text.removeTags(salvage.group("tier")) + " salvage");
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
		if (pendingSource == null || event.getContainerId() != InventoryID.INV)
		{
			return;
		}

		Map<Integer, Integer> current = toItemMap(event.getItemContainer());
		Map<Integer, Integer> added = InventoryDiff.added(preLootInventory, current);

		String source = pendingSource;
		resetPending();

		if (!added.isEmpty())
		{
			processInventoryLoot(source, added);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Drop a stale trigger that never produced an inventory change so it can't mis-attribute later loot.
		if (pendingSource != null && client.getTickCount() - pendingTick > PENDING_TIMEOUT_TICKS)
		{
			resetPending();
		}
	}

	private void beginPending(String source)
	{
		if (!config.showChatRates())
		{
			return;
		}

		pendingSource = source;
		pendingTick = client.getTickCount();
		preLootInventory = toItemMap(client.getItemContainer(InventoryID.INV));
	}

	private void processInventoryLoot(String source, Map<Integer, Integer> added)
	{
		SourceDropTable table = dataStore.getSource(source);
		if (table == null)
		{
			return;
		}

		for (Integer itemId : added.keySet())
		{
			String itemName = itemManager.getItemComposition(itemId).getName();
			DropRateEntry entry = table.getDrop(itemName);
			if (entry == null || !shouldDisplay(entry.getRate()))
			{
				continue;
			}

			sendChatMessage(itemName, source, RateParser.formatRate(entry.getRate()));
		}
	}

	// --- Shared helpers ----------------------------------------------------------------------------

	/**
	 * Whether a rate should be shown given the user's filters: never for guaranteed drops, numeric
	 * rates gated by the minimum-rarity threshold, and qualitative labels only when opted in.
	 */
	private boolean shouldDisplay(String rate)
	{
		if (rate == null || rate.trim().isEmpty() || RateParser.isAlways(rate))
		{
			return false;
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

	private void resetPending()
	{
		pendingSource = null;
		preLootInventory = null;
	}
}
