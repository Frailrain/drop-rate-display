package com.dropratedisplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class RateParserTest
{
	private static final double DELTA = 0.001;

	@Test
	public void parsesSimpleFraction()
	{
		assertEquals(512.0, RateParser.parseDenominator("1/512"), DELTA);
	}

	@Test
	public void parsesMultiNumeratorFraction()
	{
		assertEquals(128.0 / 3.0, RateParser.parseDenominator("3/128"), DELTA);
	}

	@Test
	public void parsesDecimalDenominator()
	{
		assertEquals(25.6, RateParser.parseDenominator("1/25.6"), DELTA);
	}

	@Test
	public void alwaysIsNegativeOne()
	{
		assertEquals(-1.0, RateParser.parseDenominator("Always"), DELTA);
		assertEquals(-1.0, RateParser.parseDenominator("1/1"), DELTA);
	}

	@Test
	public void qualitativeAndBlankAreZero()
	{
		assertEquals(0.0, RateParser.parseDenominator("Uncommon"), DELTA);
		assertEquals(0.0, RateParser.parseDenominator(""), DELTA);
		assertEquals(0.0, RateParser.parseDenominator(null), DELTA);
	}

	@Test
	public void ignoresFootnoteWhenParsing()
	{
		assertEquals(128.0, RateParser.parseDenominator("1/128 (with ring of wealth)"), DELTA);
	}

	@Test
	public void handlesThousandsSeparatorCommas()
	{
		assertEquals(2448.0, RateParser.parseDenominator("1/2,448"), DELTA);
		assertEquals("1/2,448", RateParser.formatRate("1/2,448"));
		assertTrue(RateParser.isRareEnough("1/2,448", 100));
	}

	@Test
	public void isAlwaysMatchesGuaranteedDrops()
	{
		assertTrue(RateParser.isAlways("Always"));
		assertTrue(RateParser.isAlways("1/1"));
		assertFalse(RateParser.isAlways("1/512"));
	}

	@Test
	public void isQualitativeMatchesLabels()
	{
		assertTrue(RateParser.isQualitative("Rare"));
		assertTrue(RateParser.isQualitative("very rare"));
		assertFalse(RateParser.isQualitative("1/512"));
	}

	@Test
	public void isRareEnoughRespectsThreshold()
	{
		assertTrue(RateParser.isRareEnough("1/512", 10));
		assertFalse(RateParser.isRareEnough("1/5", 10));
		assertFalse(RateParser.isRareEnough("Always", 10));
		assertFalse(RateParser.isRareEnough("Common", 10));
		assertTrue(RateParser.isRareEnough("1/5", 0));
	}

	@Test
	public void shouldDisplayAppliesEveryFilter()
	{
		// Numeric rates are gated purely by the minimum-rarity threshold.
		assertTrue(RateParser.shouldDisplay("1/512", 10, false, false));
		assertFalse(RateParser.shouldDisplay("1/5", 10, false, false));
		assertTrue(RateParser.shouldDisplay("1/5", 0, false, false));      // 0 = show everything

		// Guaranteed drops only show when explicitly enabled.
		assertFalse(RateParser.shouldDisplay("Always", 0, false, false));
		assertTrue(RateParser.shouldDisplay("Always", 0, false, true));

		// Qualitative labels only show when explicitly enabled.
		assertFalse(RateParser.shouldDisplay("Rare", 0, false, false));
		assertTrue(RateParser.shouldDisplay("Rare", 0, true, false));

		assertFalse(RateParser.shouldDisplay(null, 0, true, true));
	}

	@Test
	public void formatStripsFootnoteAndKeepsLabels()
	{
		assertEquals("1/512", RateParser.formatRate("1/512"));
		assertEquals("1/128", RateParser.formatRate("1/128 (with ring of wealth)"));
		assertEquals("1/50", RateParser.formatRate("  1/50  "));
		assertEquals("Rare", RateParser.formatRate("Rare"));
	}

	@Test
	public void formatRoundsToWholeOneInN()
	{
		assertEquals("1/24", RateParser.formatRate("100/2,440"));
		assertEquals("1/8", RateParser.formatRate("300/2440"));
		assertEquals("1/43", RateParser.formatRate("3/128"));
		assertEquals("1/512", RateParser.formatRate("1/512"));
		assertEquals("1/26", RateParser.formatRate("1/25.6"));
		assertEquals("Always", RateParser.formatRate("Always"));
	}

	@Test
	public void formatFullKeepsWikiFraction()
	{
		assertEquals("100/2,440", RateParser.formatRateFull("100/2,440"));
		assertEquals("3/128", RateParser.formatRateFull("3/128"));
		assertEquals("1/512", RateParser.formatRateFull("1/512"));
		assertEquals("Always", RateParser.formatRateFull("Always"));
		assertEquals("Rare", RateParser.formatRateFull("Rare"));
	}

	@Test
	public void formatDispatchesByRateFormat()
	{
		assertEquals("100/2,440", RateParser.format("100/2,440", RateFormat.EXACT));
		assertEquals("1/24.4", RateParser.format("100/2,440", RateFormat.ONE_IN_X));
		assertEquals("1/24", RateParser.format("100/2,440", RateFormat.ONE_IN_X_ROUNDED));
		assertEquals("1/512", RateParser.format("1/512", RateFormat.ONE_IN_X)); // whole -> no ".0"
		assertEquals("Always", RateParser.format("Always", RateFormat.ONE_IN_X));
	}
}
