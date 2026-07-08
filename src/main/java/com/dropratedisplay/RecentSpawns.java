package com.dropratedisplay;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;

/**
 * Remembers which tile each item id spawned on during the current game tick.
 *
 * <p>{@link net.runelite.client.events.NpcLootReceived} names the NPC and the items but not where the
 * loot landed, and RuneLite's {@code LootManager} gathers a boss's loot from wherever it spawns — which
 * for many bosses (the Leviathan, the Wilderness trio, Vorkath, Nex, …) is under the player, several
 * tiles from the NPC's own tile. Because the loot's {@code ItemSpawned} events fire before
 * {@code NpcLootReceived} on the same tick, recording them here lets the plugin anchor each rate to the
 * real drop tile instead of the NPC's tile.
 *
 * <p>Only the current tick's spawns are kept: the map is cleared as soon as a spawn arrives on a later
 * tick, and {@link #tileFor} returns null unless queried on the same tick the spawn was recorded — so an
 * item already lying on the ground can never be matched to a later kill.
 */
class RecentSpawns
{
	private final Map<Integer, WorldPoint> tileByItem = new HashMap<>();
	private int tick = Integer.MIN_VALUE;

	/** Records that {@code itemId} spawned on {@code tile} during {@code currentTick}. */
	void record(int currentTick, int itemId, WorldPoint tile)
	{
		if (currentTick != tick)
		{
			tileByItem.clear();
			tick = currentTick;
		}
		tileByItem.put(itemId, tile);
	}

	/** The tile {@code itemId} spawned on this tick, or null if it did not spawn on {@code currentTick}. */
	WorldPoint tileFor(int currentTick, int itemId)
	{
		return currentTick == tick ? tileByItem.get(itemId) : null;
	}

	void clear()
	{
		tileByItem.clear();
		tick = Integer.MIN_VALUE;
	}
}
