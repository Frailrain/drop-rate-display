package com.dropratedisplay;

import com.google.gson.Gson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.io.StringReader;
import org.junit.Before;
import org.junit.Test;

public class DropRateDataStoreTest
{
	private static final String JSON = "{"
		+ "\"version\":\"test\",\"sources\":{"
		+ "\"Abyssal demon\":{\"npcIds\":[415,7241],\"drops\":{\"Abyssal whip\":{\"rate\":\"1/512\",\"quantity\":\"1\"}}},"
		+ "\"Martial salvage\":{\"drops\":{\"Rune full helm\":{\"rate\":\"1/25.6\",\"quantity\":\"1\"}}}"
		+ "}}";

	private DropRateDataStore store;

	@Before
	public void setUp()
	{
		store = new DropRateDataStore(new Gson());
		store.loadFromReader(new StringReader(JSON));
	}

	@Test
	public void loadsAllSources()
	{
		assertEquals(2, store.getSourceCount());
		assertEquals("test", store.getVersion());
	}

	@Test
	public void looksUpExactItemName()
	{
		DropRateEntry entry = store.lookup("Abyssal demon", "Abyssal whip");
		assertNotNull(entry);
		assertEquals("1/512", entry.getRate());
		assertEquals("Abyssal whip", entry.getItemName());
	}

	@Test
	public void looksUpItemNameCaseInsensitively()
	{
		DropRateEntry entry = store.lookup("Abyssal demon", "abyssal WHIP");
		assertNotNull(entry);
		assertEquals("1/512", entry.getRate());
	}

	@Test
	public void unknownSourceReturnsNull()
	{
		assertNull(store.getSource("Nonexistent monster"));
		assertNull(store.lookup("Nonexistent monster", "Abyssal whip"));
	}

	@Test
	public void unknownItemReturnsNull()
	{
		assertNull(store.lookup("Abyssal demon", "Dragon claws"));
	}

	@Test
	public void matchesNpcIds()
	{
		SourceDropTable table = store.getSource("Abyssal demon");
		assertNotNull(table);
		assertTrue(table.matchesNpcId(415));
		assertTrue(table.matchesNpcId(7241));
		assertFalse(table.matchesNpcId(999));
	}

	@Test
	public void salvageSourceWithoutNpcIds()
	{
		DropRateEntry entry = store.lookup("Martial salvage", "Rune full helm");
		assertNotNull(entry);
		assertEquals("1/25.6", entry.getRate());
		assertNull(store.getSource("Martial salvage").getNpcIds());
	}
}
