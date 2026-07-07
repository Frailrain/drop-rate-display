package com.dropratedisplay;

import java.awt.Dimension;
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
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.TextComponent;

/**
 * Draws drop rates for monster floor loot as a self-labelled {@code "Item (rate)"} line on the item's
 * tile (or just the rate, if the item name is turned off).
 *
 * <p>Presence is checked against the live scene ({@link Tile#getGroundItems()}) every frame, so the label
 * disappears the instant the item is picked up — no lingering onto another row, and no dependency on any
 * other plugin. Rate format, rarity colour, outline and the rarity filter are all applied at render time,
 * so changing any of those settings updates what is already on screen.
 */
@Singleton
public class DropRateDisplayOverlay extends Overlay
{
	private static final long EXPIRY_MS = 30_000L;
	private static final int MAX_ENTRIES = 256;
	private static final int STRING_GAP = 15;   // vertical gap between stacked rows on one tile
	private static final int OFFSET_Z = 20;     // text height above the tile

	private final Client client;
	private final DropRateDisplayConfig config;

	/** Guarded by its own monitor; mutated from loot events and read from the render thread. */
	private final List<GroundRate> rates = new ArrayList<>();

	@Inject
	DropRateDisplayOverlay(Client client, DropRateDisplayConfig config)
	{
		this.client = client;
		this.config = config;
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

		final boolean showName = config.showItemName();
		final Map<WorldPoint, Integer> stack = new HashMap<>();

		for (GroundRate rate : snapshot)
		{
			// Filter/format applied here (not at capture) so config changes update what's already shown.
			if (!RateParser.shouldDisplay(rate.rate, config.minimumRarity(),
				config.showQualitativeRates(), config.showGuaranteedDrops()))
			{
				continue;
			}

			final LocalPoint localPoint = LocalPoint.fromWorld(client, rate.point);
			if (localPoint == null)
			{
				continue;
			}

			// Only draw while the item is actually on the ground; once picked up it leaves the scene.
			if (!itemOnTile(rate.point, localPoint, rate.itemId))
			{
				continue;
			}

			final String rateText = RateParser.format(rate.rate, config.groundRateFormat());
			final String text = showName ? rate.itemName + " (" + rateText + ")" : rateText;
			final Point point = Perspective.getCanvasTextLocation(client, graphics, localPoint, text, OFFSET_Z);
			if (point == null)
			{
				continue;
			}

			final int offset = stack.merge(rate.point, 1, Integer::sum) - 1;
			drawRate(graphics, point.getX(), point.getY() - STRING_GAP * offset, text, rate.rate);
		}

		return null;
	}

	/** True while the scene still has this item on its tile (i.e. it has not been picked up). */
	private boolean itemOnTile(WorldPoint worldPoint, LocalPoint localPoint, int itemId)
	{
		final Tile[][][] tiles = client.getScene().getTiles();
		final int plane = worldPoint.getPlane();
		if (plane < 0 || plane >= tiles.length)
		{
			return false;
		}
		final Tile tile = tiles[plane][localPoint.getSceneX()][localPoint.getSceneY()];
		if (tile == null)
		{
			return false;
		}
		final List<TileItem> items = tile.getGroundItems();
		if (items == null)
		{
			return false;
		}
		for (TileItem item : items)
		{
			if (item.getId() == itemId)
			{
				return true;
			}
		}
		return false;
	}

	private void drawRate(Graphics2D graphics, int x, int y, String text, String rateForColour)
	{
		final TextComponent component = new TextComponent();
		component.setText(text);
		component.setColor(RarityColor.colour(rateForColour, config));
		component.setOutline(true);
		component.setPosition(x, y);
		component.render(graphics);
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
