package com.dropratedisplay;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemLayer;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.TextComponent;
import net.runelite.client.util.QuantityFormatter;

/**
 * Draws drop rates for monster floor loot.
 *
 * <p>In <b>merge</b> mode the rate is placed flush after each item's line, on the correct row, so it
 * reads as one line with the core Ground Items plugin ({@code Abyssal whip (1/512)}) even for a pile of
 * 2+ drops. Rather than read Ground Items' internal state, we <i>reproduce</i> its layout entirely from
 * public data: the tile's items come from the live scene
 * ({@link Tile#getGroundItems()}), the per-row order is its {@code HashBasedTable} iteration order (a
 * {@link HashMap} keyed by item id, i.e. bucket order {@code id & 15}), and each line is rebuilt as
 * {@code name (qty) (GE: x gp) (HA: y gp)} honouring the Ground Items {@code priceDisplayMode} config —
 * matching the exact string width Ground Items draws, which is where we anchor the rate.
 *
 * <p>In <b>standalone</b> mode (for players without Ground Items) it draws a self-labelled
 * {@code "Item (rate)"} line instead.
 *
 * <p>Either way, presence is taken from the live scene, so a rate vanishes the instant its item is picked
 * up. Rate format, rarity colour, outline and the rarity filter are applied every frame, so config
 * changes update what is already on screen. Styling is always ours — Ground Items' colours are never read.
 */
@Singleton
public class DropRateDisplayOverlay extends Overlay
{
	private static final long EXPIRY_MS = 30_000L;
	private static final int MAX_ENTRIES = 256;
	private static final int STRING_GAP = 15;   // Ground Items' per-row vertical gap
	private static final int OFFSET_Z = 20;     // Ground Items' text height offset
	private static final int GAP_PX = 4;        // gap between the item's line and our appended rate
	private static final int COINS = 995;       // Ground Items shows no price for coins
	private static final int HASH_MASK = 15;    // default HashMap capacity (16) - 1; reproduces GI's row order

	private final Client client;
	private final DropRateDisplayConfig config;
	private final ItemManager itemManager;
	private final ConfigManager configManager;

	/** Guarded by its own monitor; mutated from loot events and read from the render thread. */
	private final List<GroundRate> rates = new ArrayList<>();

	@Inject
	DropRateDisplayOverlay(Client client, DropRateDisplayConfig config, ItemManager itemManager,
		ConfigManager configManager)
	{
		this.client = client;
		this.config = config;
		this.itemManager = itemManager;
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

		// Group the visible rated drops by tile. Filter here (not at capture) so config changes apply live.
		final Map<WorldPoint, List<GroundRate>> byTile = new LinkedHashMap<>();
		for (GroundRate r : snapshot)
		{
			if (!RateParser.shouldDisplay(r.rate, config.minimumRarity(), config.showQualitativeRates(),
				config.showGuaranteedDrops()))
			{
				continue;
			}
			byTile.computeIfAbsent(r.point, k -> new ArrayList<>()).add(r);
		}
		if (byTile.isEmpty())
		{
			return null;
		}

		final boolean merge = config.mergeWithGroundItems();
		final PriceMode priceMode = merge ? readGroundItemsPriceMode() : PriceMode.OFF;
		final FontMetrics fm = graphics.getFontMetrics();

		for (Map.Entry<WorldPoint, List<GroundRate>> e : byTile.entrySet())
		{
			final LocalPoint localPoint = LocalPoint.fromWorld(client, e.getKey());
			if (localPoint == null)
			{
				continue;
			}

			final Tile tile = tileAt(e.getKey(), localPoint);
			if (tile == null)
			{
				continue;
			}

			// The pile as Ground Items rows it: one entry per id, ordered by its HashMap bucket order.
			final List<TileEntry> pile = orderedPile(tile);
			if (pile.isEmpty())
			{
				continue; // everything on this tile has been picked up
			}

			final int height = itemLayerHeight(tile);
			if (merge)
			{
				renderMerged(graphics, localPoint, height, pile, e.getValue(), priceMode, fm);
			}
			else
			{
				renderStandalone(graphics, localPoint, height, pile, e.getValue());
			}
		}

		return null;
	}

	/** Appends the rate flush after each rated item's reconstructed Ground Items line, on its own row. */
	private void renderMerged(Graphics2D graphics, LocalPoint localPoint, int height, List<TileEntry> pile,
		List<GroundRate> rated, PriceMode priceMode, FontMetrics fm)
	{
		final Map<Integer, GroundRate> rateById = byId(rated);
		for (int offset = 0; offset < pile.size(); offset++)
		{
			final TileEntry entry = pile.get(offset);
			final GroundRate r = rateById.get(entry.id);
			if (r == null)
			{
				continue;
			}

			final String line = buildGroundItemsLine(entry.id, entry.quantity, priceMode);
			final Point textPoint = Perspective.getCanvasTextLocation(client, graphics, localPoint, line, height + OFFSET_Z);
			if (textPoint == null)
			{
				continue;
			}

			final int x = textPoint.getX() + fm.stringWidth(line) + GAP_PX;
			final int y = textPoint.getY() - STRING_GAP * offset;
			drawRate(graphics, x, y, RateParser.format(r.rate, config.groundRateFormat()), r.rate);
		}
	}

	/** Self-labelled {@code "Item (rate)"} rows, for players not running Ground Items. */
	private void renderStandalone(Graphics2D graphics, LocalPoint localPoint, int height, List<TileEntry> pile,
		List<GroundRate> rated)
	{
		final Map<Integer, GroundRate> rateById = byId(rated);
		int offset = 0;
		for (TileEntry entry : pile)
		{
			final GroundRate r = rateById.get(entry.id);
			if (r == null)
			{
				continue;
			}

			final String rateText = RateParser.format(r.rate, config.groundRateFormat());
			final String text = r.itemName + " (" + rateText + ")";
			final Point textPoint = Perspective.getCanvasTextLocation(client, graphics, localPoint, text, height + OFFSET_Z);
			if (textPoint == null)
			{
				continue;
			}

			drawRate(graphics, textPoint.getX(), textPoint.getY() - STRING_GAP * offset, text, r.rate);
			offset++;
		}
	}

	/**
	 * The tile's items as Ground Items would row them: merged to one entry per id (it sums same-id stacks),
	 * then ordered by its {@code HashBasedTable} iteration order. That inner map is a {@link HashMap} keyed
	 * by item id, so iteration is bucket order — for a realistic (&lt;13-item) pile that is {@code id & 15}
	 * ascending, ties by insertion/spawn order, which a stable sort over the scene order reproduces.
	 */
	private List<TileEntry> orderedPile(Tile tile)
	{
		final List<TileItem> items = tile.getGroundItems();
		if (items == null || items.isEmpty())
		{
			return Collections.emptyList();
		}

		final Map<Integer, Integer> qtyById = new LinkedHashMap<>();
		for (TileItem it : items)
		{
			qtyById.merge(it.getId(), it.getQuantity(), Integer::sum);
		}

		final List<TileEntry> pile = new ArrayList<>(qtyById.size());
		for (Map.Entry<Integer, Integer> en : qtyById.entrySet())
		{
			pile.add(new TileEntry(en.getKey(), en.getValue()));
		}
		pile.sort((a, b) -> Integer.compare(a.id & HASH_MASK, b.id & HASH_MASK));
		return pile;
	}

	/** Rebuilds the exact text Ground Items draws for an item, so we know where its line ends. */
	private String buildGroundItemsLine(int id, int quantity, PriceMode priceMode)
	{
		final ItemComposition comp = itemManager.getItemComposition(id);
		final StringBuilder sb = new StringBuilder(comp.getName());
		if (quantity > 1)
		{
			sb.append(" (").append(QuantityFormatter.quantityToStackSize(quantity)).append(')');
		}

		if (id != COINS && priceMode != PriceMode.OFF)
		{
			final int realId = comp.getNote() != -1 ? comp.getLinkedNoteId() : id;
			final int ge = itemManager.getItemPrice(realId) * quantity;
			final int ha = comp.getHaPrice() * quantity;
			if (priceMode == PriceMode.BOTH)
			{
				if (ge > 0)
				{
					sb.append(" (GE: ").append(QuantityFormatter.quantityToStackSize(ge)).append(" gp)");
				}
				if (ha > 0)
				{
					sb.append(" (HA: ").append(QuantityFormatter.quantityToStackSize(ha)).append(" gp)");
				}
			}
			else
			{
				final int price = priceMode == PriceMode.GE ? ge : ha;
				if (price > 0)
				{
					sb.append(" (").append(QuantityFormatter.quantityToStackSize(price)).append(" gp)");
				}
			}
		}

		return sb.toString();
	}

	private PriceMode readGroundItemsPriceMode()
	{
		final String v = configManager.getConfiguration("grounditems", "priceDisplayMode");
		if (v == null)
		{
			return PriceMode.BOTH; // Ground Items' default
		}
		switch (v)
		{
			case "OFF":
				return PriceMode.OFF;
			case "GE":
				return PriceMode.GE;
			case "HA":
				return PriceMode.HA;
			default:
				return PriceMode.BOTH;
		}
	}

	private Tile tileAt(WorldPoint worldPoint, LocalPoint localPoint)
	{
		final Tile[][][] tiles = client.getScene().getTiles();
		final int plane = worldPoint.getPlane();
		if (plane < 0 || plane >= tiles.length)
		{
			return null;
		}
		final int x = localPoint.getSceneX();
		final int y = localPoint.getSceneY();
		if (x < 0 || y < 0 || x >= tiles[plane].length || y >= tiles[plane][x].length)
		{
			return null;
		}
		return tiles[plane][x][y];
	}

	private static int itemLayerHeight(Tile tile)
	{
		final ItemLayer layer = tile.getItemLayer();
		return layer != null ? layer.getHeight() : 0;
	}

	private static Map<Integer, GroundRate> byId(List<GroundRate> rated)
	{
		final Map<Integer, GroundRate> map = new HashMap<>();
		for (GroundRate r : rated)
		{
			map.putIfAbsent(r.itemId, r);
		}
		return map;
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

	private enum PriceMode
	{
		OFF, GE, HA, BOTH
	}

	private static final class TileEntry
	{
		private final int id;
		private final int quantity;

		private TileEntry(int id, int quantity)
		{
			this.id = id;
			this.quantity = quantity;
		}
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
