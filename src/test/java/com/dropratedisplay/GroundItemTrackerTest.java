package com.dropratedisplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class GroundItemTrackerTest
{
	private static final WorldPoint TILE = new WorldPoint(3200, 3200, 0);
	private static final WorldPoint OTHER = new WorldPoint(3201, 3200, 0);

	@Test
	public void tracksItemsPerTile()
	{
		GroundItemTracker t = new GroundItemTracker();
		t.add(TILE, 526, 1);   // Bones
		t.add(TILE, 995, 5);   // Coins
		t.add(OTHER, 886, 15); // Steel arrow, different tile

		assertEquals(2, t.orderedIds(TILE).size());
		assertTrue(t.orderedIds(TILE).containsAll(Arrays.asList(526, 995)));
		assertEquals(Arrays.asList(886), t.orderedIds(OTHER));
	}

	@Test
	public void sameIdSumsQuantityWithoutDuplicatingTheRow()
	{
		GroundItemTracker t = new GroundItemTracker();
		t.add(TILE, 526, 1);
		t.add(TILE, 526, 2); // second stack of the same id
		assertEquals(Arrays.asList(526), t.orderedIds(TILE));
		// Needs to remove the full summed quantity (3) before the row clears.
		t.remove(TILE, 526, 2);
		assertEquals(Arrays.asList(526), t.orderedIds(TILE));
		t.remove(TILE, 526, 1);
		assertEquals(0, t.orderedIds(TILE).size());
	}

	@Test
	public void clearEmptiesEverything()
	{
		GroundItemTracker t = new GroundItemTracker();
		t.add(TILE, 526, 1);
		t.add(OTHER, 995, 1);
		t.clear();
		assertEquals(0, t.orderedIds(TILE).size());
		assertEquals(0, t.orderedIds(OTHER).size());
	}
}
