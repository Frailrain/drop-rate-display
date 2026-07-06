package com.dropratedisplay;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
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
 * <p>Reward screens (unlike a monster kill) render every possible reward as an item widget, so the rate
 * can be painted on the item itself. {@link WidgetItemOverlay} invokes {@link #renderItemOverlay} once per
 * item widget on the registered interfaces; the item's own widget id tells us which interface — and thus
 * which drop-table source — it belongs to.
 */
@Singleton
public class RewardInterfaceOverlay extends WidgetItemOverlay
{
	/** Reward interface group id -&gt; the drop-table source name that interface represents. */
	private static final Map<Integer, String> INTERFACE_SOURCES = new HashMap<>();

	static
	{
		INTERFACE_SOURCES.put(InterfaceID.PMOON_REWARD, "Lunar Chest");
	}

	private final DropRateDisplayConfig config;
	private final DropRateDataStore dataStore;
	private final ItemManager itemManager;
	private final TextComponent textComponent = new TextComponent();

	@Inject
	RewardInterfaceOverlay(DropRateDisplayConfig config, DropRateDataStore dataStore, ItemManager itemManager)
	{
		this.config = config;
		this.dataStore = dataStore;
		this.itemManager = itemManager;
		showOnInterfaces(INTERFACE_SOURCES.keySet().stream().mapToInt(Integer::intValue).toArray());
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		if (!config.showRewardInterfaceRates())
		{
			return;
		}

		int group = widgetItem.getWidget().getId() >>> 16;
		String source = INTERFACE_SOURCES.get(group);
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
			|| !RateParser.shouldDisplay(entry.getRate(), config.minimumRarity(), config.showQualitativeRates()))
		{
			return;
		}

		Rectangle bounds = widgetItem.getCanvasBounds();
		if (bounds == null)
		{
			return;
		}

		String text = RateParser.formatRate(entry.getRate());
		FontMetrics metrics = graphics.getFontMetrics();
		int x = bounds.x + (bounds.width - metrics.stringWidth(text)) / 2;
		int y = bounds.y + bounds.height;

		textComponent.setText(text);
		textComponent.setColor(config.rateColor());
		textComponent.setPosition(new java.awt.Point(x, y));
		textComponent.render(graphics);
	}
}
