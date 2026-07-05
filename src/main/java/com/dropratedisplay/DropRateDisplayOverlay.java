package com.dropratedisplay;

import com.dropratedisplay.GroundItemsReader.GroundItemInfo;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.TextComponent;
import net.runelite.client.util.QuantityFormatter;

/**
 * Draws drop rates for monster floor loot.
 *
 * <p>When the Ground Items plugin is drawing the same items, this reads its live item table (via
 * {@link GroundItemsReader}) to place our rate flush after each item's text on the correct row, so a
 * multi-item pile shows each rate against its own line. Styling is always our own (configured colour +
 * outline glow) — never Ground Items' colours.
 *
 * <p>When Ground Items is off (or its data is unavailable, or merge is disabled), it falls back to a
 * self-labelled {@code "Item (rate)"} line so the rate still has context.
 */
@Singleton
public class DropRateDisplayOverlay extends Overlay
{
	private static final long EXPIRY_MS = 30_000L;
	private static final int MAX_ENTRIES = 256;
	private static final int STRING_GAP = 15;   // Ground Items' per-row vertical gap
	private static final int OFFSET_Z = 20;     // Ground Items' text height offset
	private static final int GAP_PX = 5;        // gap between the item's line and our appended rate
	private static final int COINS_ID = 995;

	private final Client client;
	private final DropRateDisplayConfig config;
	private final GroundItemsReader groundItems;
	private final ConfigManager configManager;

	/** Guarded by its own monitor; mutated from loot events and read from the render thread. */
	private final List<GroundRate> rates = new ArrayList<>();

	@Inject
	DropRateDisplayOverlay(Client client, DropRateDisplayConfig config, GroundItemsReader groundItems,
		ConfigManager configManager)
	{
		this.client = client;
		this.config = config;
		this.groundItems = groundItems;
		this.configManager = configManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	void addGroundRate(WorldPoint point, int itemId, String itemName, String rate)
	{
		if (point == null || itemName == null || rate == null)
		{
			return;
		}

		synchronized (rates)
		{
			rates.add(new GroundRate(point, itemId, itemName, rate, System.currentTimeMillis() + EXPIRY_MS));
			while (rates.size() > MAX_ENTRIES)
			{
				rates.remove(0);
			}
		}
	}

	void clear()
	{
		synchronized (rates)
		{
			rates.clear();
		}
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showGroundItemRates())
		{
			return null;
		}

		final long now = System.currentTimeMillis();
		final List<GroundRate> snapshot;
		synchronized (rates)
		{
			rates.removeIf(r -> r.expiresAt <= now);
			if (rates.isEmpty())
			{
				return null;
			}
			snapshot = new ArrayList<>(rates);
		}

		final boolean merge = config.mergeWithGroundItems() && groundItems.isEnabled();
		final Map<WorldPoint, List<GroundItemInfo>> giItems =
			merge ? groundItems.itemsByTile() : java.util.Collections.emptyMap();
		final String priceMode = merge ? priceDisplayMode() : null;
		final FontMetrics fm = graphics.getFontMetrics();
		final Map<WorldPoint, Integer> fallbackStack = new HashMap<>();

		for (GroundRate rate : snapshot)
		{
			final LocalPoint localPoint = LocalPoint.fromWorld(client, rate.point);
			if (localPoint == null)
			{
				continue;
			}

			if (merge)
			{
				// Only annotate items Ground Items is currently drawing. Once an item is picked up (or
				// hidden), it leaves the table, so we draw nothing for it -- no lingering, no fallback
				// text landing on another item's row.
				final List<GroundItemInfo> tileItems = giItems.get(rate.point);
				if (tileItems != null)
				{
					renderMerged(graphics, localPoint, rate, tileItems, priceMode, fm);
				}
				continue;
			}

			// Ground Items off/unavailable: self-labelled fallback with its own stacking and expiry.
			final String text = rate.itemName + " (" + rate.rate + ")";
			final Point point = Perspective.getCanvasTextLocation(client, graphics, localPoint, text, OFFSET_Z);
			if (point == null)
			{
				continue;
			}
			final int offset = fallbackStack.merge(rate.point, 1, Integer::sum) - 1;
			drawRate(graphics, point.getX(), point.getY() - STRING_GAP * offset, text);
		}

		return null;
	}

	/** Appends the rate after this item's Ground Items line, on the same row. No-op if the item is gone. */
	private void renderMerged(Graphics2D graphics, LocalPoint localPoint, GroundRate rate,
		List<GroundItemInfo> tileItems, String priceMode, FontMetrics fm)
	{
		int offset = -1;
		GroundItemInfo info = null;
		for (int i = 0; i < tileItems.size(); i++)
		{
			if (tileItems.get(i).itemId == rate.itemId)
			{
				offset = i;
				info = tileItems.get(i);
				break;
			}
		}

		if (info == null)
		{
			return;
		}

		final String line = buildItemString(info, priceMode);
		final Point point = Perspective.getCanvasTextLocation(client, graphics, localPoint, line, OFFSET_Z);
		if (point == null)
		{
			return;
		}

		final int x = point.getX() + fm.stringWidth(line) + GAP_PX;
		final int y = point.getY() - STRING_GAP * offset;
		drawRate(graphics, x, y, rate.rate);
	}

	private void drawRate(Graphics2D graphics, int x, int y, String text)
	{
		final TextComponent component = new TextComponent();
		component.setText(text);
		component.setColor(config.rateColor());
		component.setOutline(true);
		component.setPosition(x, y);
		component.render(graphics);
	}

	private String priceDisplayMode()
	{
		final String mode = configManager.getConfiguration("grounditems", "priceDisplayMode");
		return mode == null ? "BOTH" : mode; // Ground Items' default is BOTH
	}

	/** Reconstructs the text Ground Items draws for an item, so we know where its line ends. */
	private String buildItemString(GroundItemInfo info, String priceMode)
	{
		final StringBuilder sb = new StringBuilder(info.name);
		if (info.quantity > 1)
		{
			sb.append(" (").append(QuantityFormatter.quantityToStackSize(info.quantity)).append(')');
		}

		if (info.itemId != COINS_ID && priceMode != null && !"OFF".equals(priceMode))
		{
			if ("BOTH".equals(priceMode))
			{
				if (info.gePrice > 0)
				{
					sb.append(" (GE: ").append(QuantityFormatter.quantityToStackSize(info.gePrice)).append(" gp)");
				}
				if (info.haPrice > 0)
				{
					sb.append(" (HA: ").append(QuantityFormatter.quantityToStackSize(info.haPrice)).append(" gp)");
				}
			}
			else
			{
				final int price = "GE".equals(priceMode) ? info.gePrice : info.haPrice;
				if (price > 0)
				{
					sb.append(" (").append(QuantityFormatter.quantityToStackSize(price)).append(" gp)");
				}
			}
		}

		return sb.toString();
	}

	private static final class GroundRate
	{
		private final WorldPoint point;
		private final int itemId;
		private final String itemName;
		private final String rate;
		private final long expiresAt;

		private GroundRate(WorldPoint point, int itemId, String itemName, String rate, long expiresAt)
		{
			this.point = point;
			this.itemId = itemId;
			this.itemName = itemName;
			this.rate = rate;
			this.expiresAt = expiresAt;
		}
	}
}
