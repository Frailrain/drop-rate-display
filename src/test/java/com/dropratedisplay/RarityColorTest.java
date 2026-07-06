package com.dropratedisplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import java.awt.Color;
import org.junit.Test;

public class RarityColorTest
{
	@Test
	public void tiersEscalateByDenominator()
	{
		Color common = RarityColor.forRate("1/2");
		Color uncommon = RarityColor.forRate("1/300");
		Color rare = RarityColor.forRate("1/512");
		Color ultra = RarityColor.forRate("1/6000");

		// Boundaries land in the expected tier.
		assertEquals(common, RarityColor.forRate("1/50"));
		assertEquals(uncommon, RarityColor.forRate("1/500"));
		assertEquals(rare, RarityColor.forRate("1/5000"));

		// Each tier is a distinct colour.
		assertNotEquals(common, uncommon);
		assertNotEquals(uncommon, rare);
		assertNotEquals(rare, ultra);
	}

	@Test
	public void nullForAlwaysAndQualitative()
	{
		assertNull(RarityColor.forRate("Always"));
		assertNull(RarityColor.forRate("Rare"));
		assertNull(RarityColor.forRate(null));
	}

	@Test
	public void resolveRespectsToggle()
	{
		Color base = Color.WHITE;
		assertEquals(base, RarityColor.resolve("1/512", base, false));        // off -> base
		assertNotEquals(base, RarityColor.resolve("1/512", base, true));      // on  -> tier colour
		assertEquals(base, RarityColor.resolve("Always", base, true));        // no tier -> base
	}
}
