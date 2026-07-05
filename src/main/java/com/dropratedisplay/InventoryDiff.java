package com.dropratedisplay;

import java.util.HashMap;
import java.util.Map;

/**
 * Computes which items were added to a container between two snapshots.
 *
 * <p>Used to detect inventory-received loot (pickpockets, salvage, chest rewards): snapshot the
 * inventory when a loot trigger fires, then diff against the inventory after the container changes.
 */
public final class InventoryDiff
{
	private InventoryDiff()
	{
	}

	/**
	 * Returns item id &rarr; quantity added, i.e. the positive per-item deltas from {@code before} to
	 * {@code after}. Items whose count did not increase are omitted.
	 */
	public static Map<Integer, Integer> added(Map<Integer, Integer> before, Map<Integer, Integer> after)
	{
		Map<Integer, Integer> result = new HashMap<>();
		if (after == null)
		{
			return result;
		}

		for (Map.Entry<Integer, Integer> entry : after.entrySet())
		{
			int previous = before == null ? 0 : before.getOrDefault(entry.getKey(), 0);
			int delta = entry.getValue() - previous;
			if (delta > 0)
			{
				result.put(entry.getKey(), delta);
			}
		}
		return result;
	}
}
