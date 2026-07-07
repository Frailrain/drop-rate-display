package com.dropratedisplay;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Singleton;
import net.runelite.api.coords.WorldPoint;

/**
 * Mirrors the core Ground Items plugin's per-tile item table so our merged rates land on the exact rows it
 * draws — without reflection.
 *
 * <p>Ground Items rows its items in the iteration order of a Guava {@link HashBasedTable} keyed by tile and
 * item id. That order is <b>not</b> any simple function of the id (neither {@code id & 15} ascending nor
 * descending matches — it depends on the table's internal bucket layout and insert history), so it can't be
 * reproduced by sorting. Instead we keep an <i>identical</i> {@code HashBasedTable}, fed the same
 * {@code ItemSpawned}/{@code ItemDespawned} operations (insert on spawn, subtract/remove on despawn) from
 * the same events. The same class plus the same operations yields the same per-tile key iteration order by
 * construction — {@code row(tile).keySet()} equals Ground Items' row order.
 */
@Singleton
class GroundItemTracker
{
	// HashBasedTable is not thread-safe; all access is guarded on this monitor.
	private final Table<WorldPoint, Integer, Integer> table = HashBasedTable.create();

	/** Insert on spawn (same as Ground Items: new id added; repeat id just sums, leaving the key in place). */
	synchronized void add(WorldPoint tile, int itemId, int quantity)
	{
		if (tile == null)
		{
			return;
		}
		final Integer existing = table.get(tile, itemId);
		table.put(tile, itemId, existing == null ? quantity : existing + quantity);
	}

	/** On despawn, subtract; remove the id when it is depleted (Ground Items drops the cell too). */
	synchronized void remove(WorldPoint tile, int itemId, int quantity)
	{
		if (tile == null)
		{
			return;
		}
		final Integer existing = table.get(tile, itemId);
		if (existing == null)
		{
			return;
		}
		if (existing <= quantity)
		{
			table.remove(tile, itemId);
		}
		else
		{
			table.put(tile, itemId, existing - quantity);
		}
	}

	synchronized void clear()
	{
		table.clear();
	}

	/** Item ids on the tile in Ground Items' row order; empty if none are tracked. */
	synchronized List<Integer> orderedIds(WorldPoint tile)
	{
		if (!table.containsRow(tile))
		{
			return Collections.emptyList();
		}
		return new ArrayList<>(table.row(tile).keySet());
	}

	/** True while the tile still holds this id (i.e. it has not been fully picked up / despawned). */
	synchronized boolean contains(WorldPoint tile, int itemId)
	{
		return table.contains(tile, itemId);
	}
}
