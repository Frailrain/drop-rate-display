package com.dropratedisplay;

/**
 * How a drop rate is written for display.
 *
 * <ul>
 *   <li>{@link #EXACT} — the wiki's own fraction, e.g. {@code 100/2,440}, {@code 3/128}, {@code 1/512}.</li>
 *   <li>{@link #ONE_IN_X} — normalised to "1 in N" with a decimal, e.g. {@code 1/24.4} (as the wiki renders it).</li>
 *   <li>{@link #ONE_IN_X_ROUNDED} — "1 in N" rounded to a whole number, e.g. {@code 1/24}.</li>
 * </ul>
 */
public enum RateFormat
{
	EXACT("Exact"),
	ONE_IN_X("1 in X"),
	ONE_IN_X_ROUNDED("1 in X (rounded)");

	private final String label;

	RateFormat(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
