package com.dropratedisplay;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.awt.Color;
import org.junit.Test;

public class RarityColorTest
{
	@Test
	public void tierByDenominator()
	{
		assertEquals(RarityColor.Tier.COMMON, RarityColor.tierFor("1/2"));
		assertEquals(RarityColor.Tier.COMMON, RarityColor.tierFor("1/50"));
		assertEquals(RarityColor.Tier.UNCOMMON, RarityColor.tierFor("1/51"));
		assertEquals(RarityColor.Tier.UNCOMMON, RarityColor.tierFor("1/500"));
		assertEquals(RarityColor.Tier.RARE, RarityColor.tierFor("1/512"));
		assertEquals(RarityColor.Tier.RARE, RarityColor.tierFor("1/5000"));
		assertEquals(RarityColor.Tier.ULTRA_RARE, RarityColor.tierFor("1/6000"));
	}

	@Test
	public void tierByLabelAndAlways()
	{
		assertEquals(RarityColor.Tier.COMMON, RarityColor.tierFor("Always"));    // guaranteed = least rare
		assertEquals(RarityColor.Tier.UNCOMMON, RarityColor.tierFor("Uncommon"));
		assertEquals(RarityColor.Tier.RARE, RarityColor.tierFor("Rare"));
		assertEquals(RarityColor.Tier.ULTRA_RARE, RarityColor.tierFor("Very rare"));
		assertEquals(RarityColor.Tier.COMMON, RarityColor.tierFor(null));
	}

	@Test
	public void colourPicksTheConfiguredTier()
	{
		DropRateDisplayConfig config = mock(DropRateDisplayConfig.class);
		when(config.commonColour()).thenReturn(Color.YELLOW);
		when(config.uncommonColour()).thenReturn(Color.GREEN);
		when(config.rareColour()).thenReturn(Color.MAGENTA);
		when(config.ultraRareColour()).thenReturn(Color.RED);

		assertEquals(Color.YELLOW, RarityColor.colour("1/2", config));
		assertEquals(Color.GREEN, RarityColor.colour("1/300", config));
		assertEquals(Color.MAGENTA, RarityColor.colour("1/512", config));
		assertEquals(Color.RED, RarityColor.colour("1/9999", config));
	}
}
