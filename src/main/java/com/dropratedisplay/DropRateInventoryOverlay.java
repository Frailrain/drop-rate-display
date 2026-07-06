package com.dropratedisplay;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.client.ui.overlay.components.TextComponent;

/**
 * Briefly draws the drop rate on inventory items that were just received from a non-floor source
 * (pickpocket, salvage, chest reward). Entries expire after {@link #EXPIRY_MS}. Useful for salvage,
 * where the loot never touches the ground so the ground overlay can't show it.
 */
@Singleton
public class DropRateInventoryOverlay extends WidgetItemOverlay
{
	private static final long EXPIRY_MS = 30_000L;

	private final DropRateDisplayConfig config;

	/** itemId -&gt; (rate text, expiry). Written from loot events, read from the render thread. */
	private final Map<Integer, TimedRate> rates = new ConcurrentHashMap<>();

	@Inject
	DropRateInventoryOverlay(DropRateDisplayConfig config)
	{
		this.config = config;
		// Inventory only: reward-interface pop-ups are handled by RewardInterfaceOverlay (drawn directly
		// from the source table), so we do not also register the interfaces here and double-draw.
		showOnInventory();
	}

	void addRate(int itemId, String rate)
	{
		rates.put(itemId, new TimedRate(rate, System.currentTimeMillis() + EXPIRY_MS));
	}

	void clear()
	{
		rates.clear();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		if (!config.showInventoryRates())
		{
			return;
		}

		TimedRate rate = rates.get(itemId);
		if (rate == null)
		{
			return;
		}
		if (rate.expiresAt <= System.currentTimeMillis())
		{
			rates.remove(itemId);
			return;
		}

		Rectangle bounds = widgetItem.getCanvasBounds();
		if (bounds == null)
		{
			return;
		}

		TextComponent text = new TextComponent();
		text.setText(rate.rate);
		text.setColor(RarityColor.resolve(rate.rate, config.rateColor(), config.colourByRarity()));
		text.setOutline(true);
		// Bottom-left of the slot, clear of the top-left quantity numbers.
		text.setPosition(bounds.x - 1, bounds.y + bounds.height - 1);
		text.render(graphics);
	}

	private static final class TimedRate
	{
		private final String rate;
		private final long expiresAt;

		private TimedRate(String rate, long expiresAt)
		{
			this.rate = rate;
			this.expiresAt = expiresAt;
		}
	}
}
