package com.dropratedisplay;

import java.util.List;
import lombok.Data;

/**
 * A single drop-table entry as loaded from {@code drop_rates.json}.
 *
 * <p>Entries are stored keyed by item <b>name</b> (the OSRS Wiki does not expose numeric item ids in
 * its drop data), so {@link #itemName} is populated from the map key at load time and is not present
 * in the JSON itself.
 *
 * <p>A handful of items are dropped at several different quantities, each with its own rate (e.g. an
 * Ammonite Crab drops Numulite &times;4 at 29/128 but &times;2 at 8/128). The primary {@link #rate}/
 * {@link #quantity} hold the first such row; the rest live in {@link #variants}. Use
 * {@link #selectForQuantity(int)} to pick the row matching the stack the player actually received.
 */
@Data
public class DropRateEntry
{
	private static final int MATCH_NONE = 0;
	private static final int MATCH_RANGE = 1;
	private static final int MATCH_EXACT = 2;

	/** Drop rate string exactly as the wiki displays it, e.g. {@code "1/512"}, {@code "Always"}, {@code "Uncommon"}. */
	private String rate;

	/** Quantity string, e.g. {@code "1"}, {@code "1-3"}, or the non-numeric {@code "Unknown"}/{@code "Varies"}. */
	private String quantity;

	/**
	 * Other rows for the same item at different quantities (each with its own rate + quantity). Null for
	 * the overwhelming majority of items, which drop at a single quantity. A variant's own {@code variants}
	 * list is always null -- the nesting is only one level deep.
	 */
	private List<DropRateEntry> variants;

	/** The item name (the JSON map key). Populated at load time; not serialised. */
	private transient String itemName;

	/**
	 * Chooses the row whose quantity best matches {@code received}: an exact single-quantity row wins,
	 * else a row whose range contains it, else this entry itself (the fallback for quantities the wiki
	 * does not list). Returns {@code this} immediately when there are no variants, the case for almost
	 * every item.
	 */
	DropRateEntry selectForQuantity(int received)
	{
		if (variants == null || variants.isEmpty())
		{
			return this;
		}

		DropRateEntry rangeMatch = null;
		int selfKind = quantityMatch(received);
		if (selfKind == MATCH_EXACT)
		{
			return this;
		}
		if (selfKind == MATCH_RANGE)
		{
			rangeMatch = this;
		}

		for (DropRateEntry variant : variants)
		{
			int kind = variant.quantityMatch(received);
			if (kind == MATCH_EXACT)
			{
				return variant;
			}
			if (kind == MATCH_RANGE && rangeMatch == null)
			{
				rangeMatch = variant;
			}
		}
		return rangeMatch != null ? rangeMatch : this;
	}

	/** Classifies how this row's quantity spec matches a received count: exact single, in-range, or none. */
	private int quantityMatch(int received)
	{
		if (quantity == null)
		{
			return MATCH_NONE;
		}

		String q = quantity.trim();
		int dash = q.indexOf('-');
		try
		{
			if (dash > 0)
			{
				int low = Integer.parseInt(q.substring(0, dash).trim());
				int high = Integer.parseInt(q.substring(dash + 1).trim());
				return (received >= low && received <= high) ? MATCH_RANGE : MATCH_NONE;
			}
			return Integer.parseInt(q) == received ? MATCH_EXACT : MATCH_NONE;
		}
		catch (NumberFormatException e)
		{
			return MATCH_NONE; // non-numeric quantities ("Unknown", "Varies") never match a specific count
		}
	}
}
