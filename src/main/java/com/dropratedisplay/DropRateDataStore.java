package com.dropratedisplay;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads the bundled {@code drop_rates.json} resource and answers drop-rate lookups.
 *
 * <p>The data is keyed by source name (matching an NPC name, salvage tier, casket name, etc.) and
 * then by item name within each source. A small override map translates the handful of loot event
 * names that do not match their wiki page title.
 */
@Slf4j
@Singleton
public class DropRateDataStore
{
	private static final String RESOURCE = "/drop_rates.json";

	/**
	 * Loot-event source names that differ from the wiki page name the data is keyed by.
	 * Most sources match exactly; add entries here as mismatches are discovered through testing.
	 */
	private static final Map<String, String> SOURCE_NAME_OVERRIDES = new HashMap<>();

	static
	{
		// Clue caskets: the completed-clue tier the plugin derives already matches the wiki
		// "Reward casket (tier)" page name, so no override is needed there. Entries below cover
		// activity names whose loot page differs from the loot event name.
		SOURCE_NAME_OVERRIDES.put("Barrows", "Chest (Barrows)");
		SOURCE_NAME_OVERRIDES.put("Theatre of Blood", "Theatre of Blood/Rewards");
		SOURCE_NAME_OVERRIDES.put("Chambers of Xeric", "Chambers of Xeric/Rewards");
		SOURCE_NAME_OVERRIDES.put("Tombs of Amascut", "Tombs of Amascut/Rewards");
	}

	private final Gson gson;

	private Map<String, SourceDropTable> sources = Collections.emptyMap();
	private Map<String, SourceDropTable> lowerCaseSources = Collections.emptyMap();

	@Getter
	private String version = "unknown";

	@Inject
	DropRateDataStore(Gson gson)
	{
		this.gson = gson;
	}

	/** Loads the bundled data file from the classpath. Safe to call again to reload. */
	public void load()
	{
		try (InputStream is = DropRateDataStore.class.getResourceAsStream(RESOURCE))
		{
			if (is == null)
			{
				log.warn("drop_rates.json not found on the classpath; drop rate lookups will be empty");
				return;
			}

			try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8))
			{
				loadFromReader(reader);
				log.debug("Loaded drop rate data version {} with {} sources", version, sources.size());
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Failed to load drop rate data", e);
		}
	}

	/** Package-private hook so tests can load data without a classpath resource. */
	void loadFromReader(Reader reader)
	{
		DropRateData data = gson.fromJson(reader, DropRateData.class);
		if (data == null || data.sources == null)
		{
			return;
		}

		Map<String, SourceDropTable> lower = new HashMap<>();
		for (Map.Entry<String, SourceDropTable> entry : data.sources.entrySet())
		{
			SourceDropTable table = entry.getValue();
			if (table != null)
			{
				table.setSourceName(entry.getKey());
				table.index();
				lower.putIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), table);
			}
		}

		this.sources = data.sources;
		this.lowerCaseSources = lower;
		this.version = data.version != null ? data.version : "unknown";
	}

	/**
	 * Resolves a loot-event source name to its drop table, applying name overrides and stripping any
	 * {@code #variant} anchor as a fallback.
	 */
	public SourceDropTable getSource(String sourceName)
	{
		if (sourceName == null)
		{
			return null;
		}

		String key = SOURCE_NAME_OVERRIDES.getOrDefault(sourceName, sourceName);
		SourceDropTable table = lookupKey(key);

		if (table == null)
		{
			int hash = key.indexOf('#');
			if (hash > 0)
			{
				table = lookupKey(key.substring(0, hash));
			}
		}
		return table;
	}

	/** Exact match first, then case-insensitive (chat gives lowercase source names like "man" / "martial"). */
	private SourceDropTable lookupKey(String key)
	{
		SourceDropTable table = sources.get(key);
		if (table == null)
		{
			table = lowerCaseSources.get(key.toLowerCase(Locale.ROOT));
		}
		return table;
	}

	/** Convenience: look up a single item's drop entry for a source. */
	public DropRateEntry lookup(String sourceName, String itemName)
	{
		SourceDropTable table = getSource(sourceName);
		return table == null ? null : table.getDrop(itemName);
	}

	public int getSourceCount()
	{
		return sources.size();
	}

	/** Top-level shape of {@code drop_rates.json}. */
	private static class DropRateData
	{
		private String version;
		private Map<String, SourceDropTable> sources;
	}
}
