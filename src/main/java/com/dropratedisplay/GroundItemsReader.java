package com.dropratedisplay;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

/**
 * Reads the core Ground Items plugin's live item table by reflection, so our rate text can be placed on
 * the same per-tile rows it draws (matching its draw order and hidden-item handling).
 *
 * <p>Read-only and for positioning only — we never read or apply Ground Items' colours. Every failure
 * degrades gracefully to "unavailable"; the overlay then falls back to its own self-labelled rendering.
 * This is the one piece coupling us to Ground Items' internals; it is deliberately isolated here so it is
 * easy to remove.
 */
@Slf4j
@Singleton
class GroundItemsReader
{
	private final PluginManager pluginManager;

	private boolean resolved;
	private Plugin groundItems;
	private Method getCollected;
	private Method tableCellSet;
	private Method cellRowKey;
	private Method cellColumnKey;
	private Method cellValue;

	private boolean groundItemMethodsResolved;
	private Method giGetName;
	private Method giGetQuantity;
	private Method giGetGePrice;
	private Method giGetHaPrice;
	private Method giIsHidden;

	@Inject
	GroundItemsReader(PluginManager pluginManager)
	{
		this.pluginManager = pluginManager;
	}

	boolean isEnabled()
	{
		resolve();
		return groundItems != null && getCollected != null && pluginManager.isPluginEnabled(groundItems);
	}

	/** Visible ground items per tile, in Ground Items' own draw order. Empty if unavailable or disabled. */
	Map<WorldPoint, List<GroundItemInfo>> itemsByTile()
	{
		if (!isEnabled())
		{
			return Collections.emptyMap();
		}

		try
		{
			Object table = getCollected.invoke(groundItems);
			Object cells = tableCellSet.invoke(table);
			Map<WorldPoint, List<GroundItemInfo>> out = new HashMap<>();
			for (Object cell : (Iterable<?>) cells)
			{
				Object item = cellValue.invoke(cell);
				resolveGroundItemMethods(item);
				if (giIsHidden != null && Boolean.TRUE.equals(giIsHidden.invoke(item)))
				{
					continue;
				}

				WorldPoint tile = (WorldPoint) cellRowKey.invoke(cell);
				int itemId = (Integer) cellColumnKey.invoke(cell);
				String name = giGetName != null ? (String) giGetName.invoke(item) : "";
				int quantity = giGetQuantity != null ? (Integer) giGetQuantity.invoke(item) : 1;
				int gePrice = giGetGePrice != null ? (Integer) giGetGePrice.invoke(item) : 0;
				int haPrice = giGetHaPrice != null ? (Integer) giGetHaPrice.invoke(item) : 0;
				out.computeIfAbsent(tile, k -> new ArrayList<>())
					.add(new GroundItemInfo(itemId, name, quantity, gePrice, haPrice));
			}
			return out;
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			log.debug("Ground Items read failed; disabling inline merge", e);
			getCollected = null;
			return Collections.emptyMap();
		}
	}

	private void resolve()
	{
		if (resolved)
		{
			return;
		}
		resolved = true;
		try
		{
			for (Plugin plugin : pluginManager.getPlugins())
			{
				if ("GroundItemsPlugin".equals(plugin.getClass().getSimpleName()))
				{
					groundItems = plugin;
					break;
				}
			}
			if (groundItems == null)
			{
				return;
			}

			getCollected = groundItems.getClass().getMethod("getCollectedGroundItems");
			getCollected.setAccessible(true);

			Class<?> tableClass = Class.forName("com.google.common.collect.Table");
			tableCellSet = tableClass.getMethod("cellSet");
			Class<?> cellClass = Class.forName("com.google.common.collect.Table$Cell");
			cellRowKey = cellClass.getMethod("getRowKey");
			cellColumnKey = cellClass.getMethod("getColumnKey");
			cellValue = cellClass.getMethod("getValue");
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			log.debug("Ground Items reflection unavailable", e);
			getCollected = null;
		}
	}

	private void resolveGroundItemMethods(Object groundItem)
	{
		if (groundItemMethodsResolved || groundItem == null)
		{
			return;
		}
		groundItemMethodsResolved = true;
		Class<?> c = groundItem.getClass();
		giGetName = tryMethod(c, "getName");
		giGetQuantity = tryMethod(c, "getQuantity");
		giGetGePrice = tryMethod(c, "getGePrice");
		giGetHaPrice = tryMethod(c, "getHaPrice");
		giIsHidden = tryMethod(c, "isHidden");
	}

	private static Method tryMethod(Class<?> c, String name)
	{
		try
		{
			Method m = c.getDeclaredMethod(name);
			m.setAccessible(true);
			return m;
		}
		catch (NoSuchMethodException e)
		{
			return null;
		}
	}

	/** A minimal snapshot of one Ground Items entry, enough to reconstruct its rendered line width. */
	static final class GroundItemInfo
	{
		final int itemId;
		final String name;
		final int quantity;
		final int gePrice;
		final int haPrice;

		GroundItemInfo(int itemId, String name, int quantity, int gePrice, int haPrice)
		{
			this.itemId = itemId;
			this.name = name;
			this.quantity = quantity;
			this.gePrice = gePrice;
			this.haPrice = haPrice;
		}
	}
}
