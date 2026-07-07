package com.dropratedisplay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Singleton;
import net.runelite.api.coords.WorldPoint;

/**
 * Mirrors the core Ground Items plugin's per-tile item table so our merged rates land on the exact rows
 * it draws — without reflection.
 *
 * <p>Ground Items keeps its items in a Guava {@code HashBasedTable}, whose per-tile column map is a
 * {@link HashMap} created with initial capacity 1 (Guava's {@code newHashMapWithExpectedSize(0)}). Its row
 * order is therefore that {@code HashMap}'s key-iteration order — which depends on the whole insert/resize
 * history, not a simple {@code id & 15} bucket, so it cannot be reproduced by a formula once the map has
 * grown. Instead we keep an identical {@code HashMap<Integer, ?>(1)} per tile and apply the same
 * insert/remove operations from the same {@code ItemSpawned}/{@code ItemDespawned} events (mirroring
 * Ground Items' own handlers: insert on spawn, subtract/remove on despawn, drop the row when it empties).
 * Replaying identical operations on an identical structure yields an identical iteration order.
 *
 * <p>Per-tile column maps are independent, so stale entries from other tiles never affect a tile's order;
 * {@link #clear()} on region load just bounds memory and keeps a revisited tile's map fresh, matching
 * Ground Items clearing on load.
 */
@Singleton
class GroundItemTracker
{
	private final Map<WorldPoint, Map<Integer, Integer>> byTile = new HashMap<>();

	/** Insert on spawn. Matches Ground Items: a new id is added (possibly growing the map); a repeat id
	 *  only sums its quantity, which leaves the key — and thus the order — untouched. */
	void add(WorldPoint tile, int itemId, int quantity)
	{
		if (tile == null)
		{
			return;
		}
		byTile.computeIfAbsent(tile, k -> new HashMap<>(1)).merge(itemId, quantity, Integer::sum);
	}

	/** On despawn, subtract; when the id is depleted remove it, and drop the row when it empties. */
	void remove(WorldPoint tile, int itemId, int quantity)
	{
		if (tile == null)
		{
			return;
		}
		final Map<Integer, Integer> column = byTile.get(tile);
		if (column == null)
		{
			return;
		}
		final Integer current = column.get(itemId);
		if (current == null)
		{
			return;
		}
		if (current <= quantity)
		{
			column.remove(itemId);
			if (column.isEmpty())
			{
				byTile.remove(tile);
			}
		}
		else
		{
			column.put(itemId, current - quantity);
		}
	}

	void clear()
	{
		byTile.clear();
	}

	/** Item ids on the tile in Ground Items' own row order; empty if none are tracked. */
	List<Integer> orderedIds(WorldPoint tile)
	{
		final Map<Integer, Integer> column = byTile.get(tile);
		return column == null ? Collections.emptyList() : new ArrayList<>(column.keySet());
	}
}
