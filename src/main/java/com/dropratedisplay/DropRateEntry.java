package com.dropratedisplay;

import lombok.Data;

/**
 * A single drop-table entry as loaded from {@code drop_rates.json}.
 *
 * <p>Entries are stored keyed by item <b>name</b> (the OSRS Wiki does not expose numeric item ids in
 * its drop data), so {@link #itemName} is populated from the map key at load time and is not present
 * in the JSON itself.
 */
@Data
public class DropRateEntry
{
	/** Drop rate string exactly as the wiki displays it, e.g. {@code "1/512"}, {@code "Always"}, {@code "Uncommon"}. */
	private String rate;

	/** Quantity string, e.g. {@code "1"} or {@code "1-3"}. */
	private String quantity;

	/** The item name (the JSON map key). Populated at load time; not serialised. */
	private transient String itemName;
}
