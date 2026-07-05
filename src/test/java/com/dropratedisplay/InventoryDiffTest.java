package com.dropratedisplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class InventoryDiffTest
{
	private static Map<Integer, Integer> map(int... idQtyPairs)
	{
		Map<Integer, Integer> m = new HashMap<>();
		for (int i = 0; i < idQtyPairs.length; i += 2)
		{
			m.put(idQtyPairs[i], idQtyPairs[i + 1]);
		}
		return m;
	}

	@Test
	public void detectsNewItem()
	{
		Map<Integer, Integer> added = InventoryDiff.added(map(995, 100), map(995, 100, 4151, 1));
		assertEquals(1, added.size());
		assertEquals(Integer.valueOf(1), added.get(4151));
	}

	@Test
	public void detectsIncreasedStack()
	{
		Map<Integer, Integer> added = InventoryDiff.added(map(4151, 1), map(4151, 3));
		assertEquals(Integer.valueOf(2), added.get(4151));
	}

	@Test
	public void ignoresRemovedItems()
	{
		Map<Integer, Integer> added = InventoryDiff.added(map(4151, 2), map(4151, 1));
		assertTrue(added.isEmpty());
	}

	@Test
	public void treatsNullBeforeAsEmpty()
	{
		Map<Integer, Integer> added = InventoryDiff.added(null, map(4151, 1));
		assertEquals(Integer.valueOf(1), added.get(4151));
	}

	@Test
	public void nullAfterYieldsEmpty()
	{
		assertTrue(InventoryDiff.added(map(4151, 1), null).isEmpty());
	}
}
