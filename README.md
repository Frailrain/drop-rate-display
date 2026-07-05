# Drop Rate Display

A [RuneLite](https://runelite.net) plugin that shows the drop rate of loot you receive, sourced from
the [OSRS Wiki](https://oldschool.runescape.wiki).

- **Monster drops** — the rate is drawn on the ground where the monster died, next to the item.
  Example: `Abyssal whip (1/512)`
- **Pickpocket, salvage and chest loot** — the rate is printed in chat.
  Example: `[Drop Rate] Dharok's helm from Barrows: 1/2,448`

There is **no dependency on the Loot Tracker plugin** — monster drops are detected through RuneLite's
core `LootManager`, and other loot is detected directly from game messages and reward interfaces.

## Features

| Loot source | How it's detected | Where the rate shows |
|---|---|---|
| Monster kills | `NpcLootReceived` (core `LootManager`) | On the ground item |
| Pickpockets | Game message + inventory diff | Chat |
| Ship salvage | Game message + inventory diff | Chat |
| Barrows chest | Reward widget + inventory diff | Chat |
| Clue caskets | Reward widget + inventory diff | Chat |

## Configuration

- **Show rates on ground items** — toggle the ground overlay.
- **Show rates in chat** — toggle chat messages for inventory-received loot.
- **Minimum rarity to display** — only show rates for items `1/N` or rarer (default `10`; `0` shows all).
- **Rate text colour** — colour of the ground overlay text.
- **Show qualitative rates** — also show `Uncommon` / `Rare` etc. when an exact rate is unknown.

## Data

Drop rates are bundled in [`src/main/resources/drop_rates.json`](src/main/resources/drop_rates.json),
generated from the OSRS Wiki's `dropsline` Bucket. Because the wiki does not expose numeric item ids in
its drop data, the file is keyed by item **name**, and the plugin resolves an `ItemStack`'s id to a name
at runtime via `ItemManager`.

Rates are stored exactly as the wiki displays them (`1/512`, `1/25.6`, `Always`, `Uncommon`) — they are
never converted to decimals.

### Regenerating the data

[`scripts/generate_drop_rates.py`](scripts/generate_drop_rates.py) rebuilds the bundled file. It respects
the wiki's 1 request/second courtesy limit.

```sh
pip install requests

# Refresh only specific sources (fast):
python scripts/generate_drop_rates.py --sources "Abyssal demon,Vorkath,Chest (Barrows)" \
    -o src/main/resources/drop_rates.json

# Full rebuild of every drop table on the wiki (slow, several hundred requests):
python scripts/generate_drop_rates.py --full -o src/main/resources/drop_rates.json
```

The bundled sample covers a representative set of sources; run a `--full` rebuild for complete coverage.

## Building

```sh
./gradlew build      # compile + run tests
./gradlew run        # launch a RuneLite dev client with the plugin loaded
```

Requires JDK 11.

## Known limitations (v1)

- Chest/clue detection relies on the loot landing in your inventory; a full bank-bound reward is not
  captured.
- Same-named NPC variants with different drop tables are matched by name. NPC ids are bundled for future
  id-based disambiguation.
- Coverage is limited to what the wiki's `dropsline` template records (it excludes, e.g., bird-nest seed
  sub-tables and skilling success rates).

## Licence

BSD 2-Clause. See [LICENSE](LICENSE).
