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

	@Test
	public void bundledResourceLoadsAndIsQueryable()
	{
		DropRateDataStore bundled = new DropRateDataStore(new Gson());
		bundled.load();
		assertTrue("bundled drop_rates.json should contain many sources", bundled.getSourceCount() > 100);

		DropRateEntry whip = bundled.lookup("Abyssal demon", "Abyssal whip");
		assertNotNull("bundled data should contain Abyssal demon / Abyssal whip", whip);
		assertEquals("1/512", whip.getRate());
	}

	/**
	 * Every reward-interface / minigame source the plugin wires up must resolve (via overrides) to a real
	 * table in the bundled data. This fails the build if a source name or override drifts out of sync,
	 * rather than silently showing nothing mid-raid.
	 */
	@Test
	public void wiredRewardSourcesResolveInBundledData()
	{
		DropRateDataStore bundled = new DropRateDataStore(new Gson());
		bundled.load();

		String[] wiredSources = {
			"Barrows", "Chambers of Xeric", "Theatre of Blood", "Tombs of Amascut",
			"Lunar Chest", "Fortis Colosseum", "Fishing Trawler", "Drift Net",
			"Tempoross", "Guardians of the Rift", "Wintertodt", "Reward casket (hard)"
		};
		for (String source : wiredSources)
		{
			assertNotNull("wired source has no drop table: " + source, bundled.getSource(source));
		}

		// Spot-check that a signature unique resolves all the way through the override to a rate.
		assertNotNull("CoX Twisted bow", bundled.lookup("Chambers of Xeric", "Twisted bow"));
		assertNotNull("ToB Ghrazi rapier", bundled.lookup("Theatre of Blood", "Ghrazi rapier"));
		assertNotNull("ToA Osmumten's fang", bundled.lookup("Tombs of Amascut", "Osmumten's fang"));
		assertNotNull("Wintertodt Bruma torch", bundled.lookup("Wintertodt", "Bruma torch"));
	}
}
