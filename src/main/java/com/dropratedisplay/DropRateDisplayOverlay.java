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
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Renders drop-rate text at the world tiles where monsters dropped notable loot.
 *
 * <p>Independent of the core Ground Items plugin: the text is drawn at the item's world position, so
 * it appears whether or not Ground Items is enabled. Entries expire after {@link #EXPIRY_MS}.
 *
 * <p>When {@linkplain #setMergeMode(boolean) merge mode} is on (the Ground Items plugin is enabled and
 * already draws the item name), only the rate is shown to avoid repeating the name.
 */
@Singleton
public class DropRateDisplayOverlay extends Overlay
{
	private static final long EXPIRY_MS = 30_000L;
	private static final int MAX_ENTRIES = 256;
	private static final int LINE_HEIGHT = 14;

	private final Client client;
	private final DropRateDisplayConfig config;

	/** Guarded by its own monitor; mutated from loot events and read from the render thread. */
	private final List<GroundRate> rates = new ArrayList<>();

	private volatile boolean mergeMode;

	@Inject
	DropRateDisplayOverlay(Client client, DropRateDisplayConfig config)
	{
		this.client = client;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	void addGroundRate(WorldPoint point, String itemName, String rate)
	{
		if (point == null || itemName == null || rate == null)
		{
			return;
		}

		synchronized (rates)
		{
			rates.add(new GroundRate(point, itemName, rate, System.currentTimeMillis() + EXPIRY_MS));
			while (rates.size() > MAX_ENTRIES)
			{
				rates.remove(0);
			}
		}
	}

	/** When true, render only the rate (Ground Items is already drawing the item name). */
	void setMergeMode(boolean mergeMode)
	{
		this.mergeMode = mergeMode;
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

		final boolean merge = mergeMode;

		// Stack multiple entries on the same tile so they don't overwrite each other.
		final Map<WorldPoint, Integer> perTileCount = new HashMap<>();
		for (GroundRate rate : snapshot)
		{
			LocalPoint localPoint = LocalPoint.fromWorld(client, rate.point);
			if (localPoint == null)
			{
				continue;
			}

			String display = merge ? rate.rate : rate.itemName + " (" + rate.rate + ")";
			Point textLocation = Perspective.getCanvasTextLocation(client, graphics, localPoint, display, 0);
			if (textLocation == null)
			{
				continue;
			}

			int index = perTileCount.merge(rate.point, 1, Integer::sum) - 1;
			Point stacked = new Point(textLocation.getX(), textLocation.getY() - index * LINE_HEIGHT);
			OverlayUtil.renderTextLocation(graphics, stacked, display, config.rateColor());
		}

		return null;
	}

	private static final class GroundRate
	{
		private final WorldPoint point;
		private final String itemName;
		private final String rate;
		private final long expiresAt;

		private GroundRate(WorldPoint point, String itemName, String rate, long expiresAt)
		{
			this.point = point;
			this.itemName = itemName;
			this.rate = rate;
			this.expiresAt = expiresAt;
		}
	}
}
