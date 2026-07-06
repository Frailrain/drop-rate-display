package com.dropratedisplay;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.client.ui.overlay.components.TextComponent;

/**
 * Draws drop-rate text directly over the items shown on a reward interface, rather than in chat.
 *
 * <p>A reward screen (unlike a monster kill) renders every possible reward as an item widget, so the
 * rate can be looked up straight from that interface's drop-table source and painted on the item — no
 * loot detection needed. {@link WidgetItemOverlay} invokes {@link #renderItemOverlay} once per item
 * widget on the registered interfaces; the item's widget id tells us which interface, hence which source.
 *
 * <p>Clue caskets reuse one interface across all tiers, so their source is set dynamically by the plugin.
 */
@Singleton
public class RewardInterfaceOverlay extends WidgetItemOverlay
{
	/**
	 * Reward interface group id &rarr; the drop-table source it represents. Activity names here are mapped
	 * to the real wiki page by {@link DropRateDataStore}'s overrides (e.g. Chambers of Xeric &rarr; Ancient
	 * chest). Clue caskets are handled separately because their source depends on the completed tier.
	 */
	private static final Map<Integer, String> INTERFACE_SOURCES = new HashMap<>();

	static
	{
		INTERFACE_SOURCES.put(InterfaceID.BARROWS_REWARD, "Barrows");
		INTERFACE_SOURCES.put(InterfaceID.RAIDS_REWARDS, "Chambers of Xeric");
		INTERFACE_SOURCES.put(InterfaceID.TOB_CHESTS, "Theatre of Blood");
		INTERFACE_SOURCES.put(InterfaceID.TOA_CHESTS, "Tombs of Amascut");
		INTERFACE_SOURCES.put(InterfaceID.PMOON_REWARD, "Lunar Chest");
		INTERFACE_SOURCES.put(InterfaceID.COLOSSEUM_REWARD_CHEST_2, "Fortis Colosseum");
		INTERFACE_SOURCES.put(InterfaceID.TRAWLER_REWARD, "Fishing Trawler");
		INTERFACE_SOURCES.put(InterfaceID.FOSSIL_DRIFTNET, "Drift Net");
	}

	private final DropRateDisplayConfig config;
	private final DropRateDataStore dataStore;
	private final ItemManager itemManager;
	private final TextComponent textComponent = new TextComponent();

	/** Clue caskets share one interface for every tier; the plugin updates this on clue completion. */
	private volatile String clueCasketSource;

	@Inject
	RewardInterfaceOverlay(DropRateDisplayConfig config, DropRateDataStore dataStore, ItemManager itemManager)
	{
		this.config = config;
		this.dataStore = dataStore;
		this.itemManager = itemManager;

		int[] groups = new int[INTERFACE_SOURCES.size() + 1];
		int i = 0;
		for (int group : INTERFACE_SOURCES.keySet())
		{
			groups[i++] = group;
		}
		groups[i] = InterfaceID.TRAIL_REWARDSCREEN;
		showOnInterfaces(groups);
	}

	void setClueCasketSource(String source)
	{
		this.clueCasketSource = source;
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		if (!config.showRewardInterfaceRates())
		{
			return;
		}

		int group = widgetItem.getWidget().getId() >>> 16;
		String source = group == InterfaceID.TRAIL_REWARDSCREEN ? clueCasketSource : INTERFACE_SOURCES.get(group);
		if (source == null)
		{
			return;
		}

		SourceDropTable table = dataStore.getSource(source);
		if (table == null)
		{
			return;
		}

		String itemName = itemManager.getItemComposition(itemId).getName();
		DropRateEntry entry = table.getDrop(itemName);
		if (entry == null
			|| !RateParser.shouldDisplay(entry.getRate(), config.minimumRarity(), config.showQualitativeRates(),
			config.showGuaranteedDrops()))
		{
			return;
		}

		Rectangle bounds = widgetItem.getCanvasBounds();
		if (bounds == null)
		{
			return;
		}

		String text = RateParser.format(entry.getRate(), config.rateFormat());
		FontMetrics metrics = graphics.getFontMetrics();
		int x = bounds.x + (bounds.width - metrics.stringWidth(text)) / 2;
		int y = bounds.y + bounds.height;

		textComponent.setText(text);
		textComponent.setColor(RarityColor.resolve(text, config.rateColor(), config.colourByRarity()));
		textComponent.setOutline(true);
		textComponent.setPosition(new Point(x, y));
		textComponent.render(graphics);
	}
}
