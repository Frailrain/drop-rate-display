package com.dropratedisplay;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsing and formatting helpers for wiki drop-rate strings.
 *
 * <p>Wiki rates come in several shapes: fractional ({@code 1/512}, {@code 3/128}), decimal fractional
 * ({@code 1/25.6}), {@code Always}/{@code 1/1}, qualitative labels ({@code Common}, {@code Rare}...),
 * and fractions with trailing footnotes ({@code "1/128 (with ring of wealth)"}). These helpers extract
 * the leading fraction and ignore any footnote text.
 */
public final class RateParser
{
	/**
	 * Matches the first {@code numerator/denominator} fraction anywhere in the string. Each number may
	 * use thousands-separator commas ({@code 2,448}) and a decimal part ({@code 25.6}); commas are only
	 * allowed in proper 3-digit groups so trailing punctuation is not swallowed.
	 */
	private static final Pattern FRACTION = Pattern.compile(
		"(\\d+(?:,\\d{3})*(?:\\.\\d+)?)\\s*/\\s*(\\d+(?:,\\d{3})*(?:\\.\\d+)?)");

	private RateParser()
	{
	}

	/**
	 * Parses a rate string to its effective "1 in X" denominator for comparison.
	 *
	 * @return the denominator (e.g. {@code 512} for {@code "1/512"}, {@code 42.67} for {@code "3/128"}),
	 * {@code -1} for guaranteed drops ({@code "Always"} / {@code "1/1"}), or {@code 0} for
	 * qualitative or unparseable rates.
	 */
	public static double parseDenominator(String rate)
	{
		if (rate == null)
		{
			return 0;
		}

		String trimmed = rate.trim();
		if (trimmed.isEmpty())
		{
			return 0;
		}

		if (trimmed.equalsIgnoreCase("Always") || trimmed.equals("1/1"))
		{
			return -1;
		}

		Matcher matcher = FRACTION.matcher(trimmed);
		if (matcher.find())
		{
			try
			{
				double numerator = Double.parseDouble(stripCommas(matcher.group(1)));
				double denominator = Double.parseDouble(stripCommas(matcher.group(2)));
				if (numerator > 0)
				{
					return denominator / numerator;
				}
			}
			catch (NumberFormatException e)
			{
				return 0;
			}
		}

		return 0;
	}

	public static boolean isAlways(String rate)
	{
		if (rate == null)
		{
			return false;
		}
		String trimmed = rate.trim();
		return trimmed.equalsIgnoreCase("Always") || trimmed.equals("1/1");
	}

	/** True if the rate is a qualitative label ("Common", "Uncommon", "Rare", "Very rare") rather than a number. */
	public static boolean isQualitative(String rate)
	{
		if (rate == null)
		{
			return false;
		}
		String trimmed = rate.trim();
		return trimmed.equalsIgnoreCase("Common")
			|| trimmed.equalsIgnoreCase("Uncommon")
			|| trimmed.equalsIgnoreCase("Rare")
			|| trimmed.equalsIgnoreCase("Very rare");
	}

	/**
	 * True if a numeric rate is at least as rare as the given threshold.
	 * {@code threshold <= 0} shows everything; {@code "Always"} and qualitative rates return false.
	 */
	public static boolean isRareEnough(String rate, int threshold)
	{
		if (threshold <= 0)
		{
			return true;
		}

		double denominator = parseDenominator(rate);
		if (denominator <= 0)
		{
			return false;
		}
		return denominator >= threshold;
	}

	/**
	 * Formats a rate for display: returns the leading {@code numerator/denominator} fraction with any
	 * footnote stripped, or the trimmed label as-is for qualitative rates.
	 */
	public static String formatRate(String rate)
	{
		if (rate == null)
		{
			return "";
		}

		String trimmed = rate.trim();
		if (isAlways(trimmed))
		{
			return "Always";
		}

		Matcher matcher = FRACTION.matcher(trimmed);
		if (!matcher.find())
		{
			return trimmed;
		}

		try
		{
			double numerator = Double.parseDouble(stripCommas(matcher.group(1)));
			double denominator = Double.parseDouble(stripCommas(matcher.group(2)));
			if (numerator > 0 && denominator > 0)
			{
				// Normalise to "1 in N" so unwieldy wiki fractions like 100/2,440 read as 1/24.4.
				return "1/" + formatOneInN(denominator / numerator);
			}
		}
		catch (NumberFormatException ignored)
		{
			// fall through to the raw fraction
		}

		return matcher.group(1) + "/" + matcher.group(2);
	}

	/** "1 in N": whole numbers grouped with commas ("2,448"), otherwise one decimal place ("24.4"). */
	private static String formatOneInN(double n)
	{
		double rounded = Math.round(n * 10.0) / 10.0;
		if (rounded == Math.floor(rounded))
		{
			return String.format(Locale.US, "%,d", (long) rounded);
		}
		return String.format(Locale.US, "%.1f", rounded);
	}

	private static String stripCommas(String number)
	{
		return number.replace(",", "");
	}
}
