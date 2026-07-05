package com.dropratedisplay;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.Getter;

/**
 * The drop table for a single loot source (a monster, a salvage tier, a reward casket, etc.), as
 * loaded from {@code drop_rates.json}.
 *
 * <p>Drops are keyed by item name. A case-insensitive index is built at load time so lookups still
 * succeed when the in-game item name differs from the wiki spelling only by case.
 */
public class SourceDropTable
{
	/** OSRS Wiki monster ids for this source (used to disambiguate same-named NPC variants). May be null. */
	@Getter
	private List<Integer> npcIds;

	/** The wiki source name (the JSON map key). Populated at load time; not serialised. */
	@Getter
	private transient String sourceName;

	/** Drops keyed by item name. */
	private Map<String, DropRateEntry> drops;

	private transient Map<String, DropRateEntry> lowerCaseIndex;

	void setSourceName(String sourceName)
	{
		this.sourceName = sourceName;
	}

	/**
	 * Builds the case-insensitive lookup index and back-fills each entry's item name from its key.
	 * Called once after Gson deserialisation.
	 */
	void index()
	{
		lowerCaseIndex = new HashMap<>();
		if (drops == null)
		{
			return;
		}

		for (Map.Entry<String, DropRateEntry> e : drops.entrySet())
		{
			DropRateEntry entry = e.getValue();
			if (entry != null)
			{
				entry.setItemName(e.getKey());
				lowerCaseIndex.put(e.getKey().toLowerCase(Locale.ROOT), entry);
			}
		}
	}

	/**
	 * Looks up a drop by item name (exact match first, then case-insensitive).
	 *
	 * @return the matching entry, or null if this source does not drop the item (or has no known rate).
	 */
	public DropRateEntry getDrop(String itemName)
	{
		if (itemName == null || drops == null)
		{
			return null;
		}

		DropRateEntry entry = drops.get(itemName);
		if (entry == null && lowerCaseIndex != null)
		{
			entry = lowerCaseIndex.get(itemName.toLowerCase(Locale.ROOT));
		}
		return entry;
	}

	public boolean matchesNpcId(int npcId)
	{
		return npcIds != null && npcIds.contains(npcId);
	}
}
