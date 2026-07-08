package com.dropratedisplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class RecentSpawnsTest
{
	private static final WorldPoint TILE_A = new WorldPoint(3200, 3200, 0);
	private static final WorldPoint TILE_B = new WorldPoint(3210, 3215, 0);

	@Test
	public void returnsTileWhenQueriedOnTheSameTick()
	{
		RecentSpawns spawns = new RecentSpawns();
		spawns.record(100, 4151, TILE_A);
		assertEquals(TILE_A, spawns.tileFor(100, 4151));
	}

	@Test
	public void returnsNullOnAdifferentTick()
	{
		RecentSpawns spawns = new RecentSpawns();
		spawns.record(100, 4151, TILE_A);
		assertNull("a spawn must not be matched to a later tick's kill", spawns.tileFor(101, 4151));
	}

	@Test
	public void recordingOnANewTickClearsTheOldTicksSpawns()
	{
		RecentSpawns spawns = new RecentSpawns();
		spawns.record(100, 4151, TILE_A);
		spawns.record(101, 526, TILE_B);
		assertNull("old tick's item is gone", spawns.tileFor(101, 4151));
		assertEquals(TILE_B, spawns.tileFor(101, 526));
	}

	@Test
	public void unknownItemReturnsNull()
	{
		RecentSpawns spawns = new RecentSpawns();
		spawns.record(100, 4151, TILE_A);
		assertNull(spawns.tileFor(100, 999));
	}

	@Test
	public void clearForgetsEverything()
	{
		RecentSpawns spawns = new RecentSpawns();
		spawns.record(100, 4151, TILE_A);
		spawns.clear();
		assertNull(spawns.tileFor(100, 4151));
	}
}
