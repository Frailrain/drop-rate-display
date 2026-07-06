# Drop Rate Display

A [RuneLite](https://runelite.net) plugin that shows the OSRS Wiki drop rate of loot you receive,
wherever it appears — on the ground, on a reward screen, or in your inventory.

There is **no dependency on the Loot Tracker plugin**. Monster drops come from RuneLite's core
`LootManager`; everything else is detected directly from game messages, reward interfaces and menu
actions.

## Where the rate shows

- **Floor drops** (monsters and bosses) — the rate is drawn on the ground next to the item. When the
  core **Ground Items** plugin is on, it's appended to that item's line (`Feather (5) (GE: 10 gp) 1/2`);
  otherwise it's shown self-labelled (`Ensouled goblin head (1/35)`).
- **Reward screens** — the rate is painted directly on each item on the reward interface: Barrows,
  Chambers of Xeric, Theatre of Blood, Tombs of Amascut, Perilous Moons (Lunar Chest), Fortis Colosseum,
  Fishing Trawler, Drift Net and clue caskets.
- **Inventory loot** — a chat line plus the rate on the item's icon for ~30 seconds. Covers:
  - Pickpockets and ship salvage
  - World chests — Brimstone, Crystal, Larran's, Elven, Grubby, Muddy, Ancient Vault, and more
  - Opened containers — caskets, lockboxes, gem bags, Camdozaal/frozen caches, potion packs, the
    Mahogany Homes supply crate, Soul Wars spoils, seed packs, and any other item whose name has a
    wiki drop table
  - Skilling minigames — Tempoross, Guardians of the Rift, Wintertodt
  - Herbiboar and the Unsired

Rates read from the wiki (`1/512`, `100/2,440`, `Always`, `Uncommon`). On the roomy ground line and in
chat the full wiki rate is shown; on the space-constrained item icons it's normalised to a rounded
`1/N` (`100/2,440` → `1/24`).

## Configuration

- **Show rates on ground items** — the ground overlay for monster drops.
- **Show rates in chat** — chat lines for inventory-received loot.
- **Show rates on inventory items** — the ~30s rate on a received item's icon.
- **Show rates on reward interfaces** — draw rates over reward-screen items.
- **Minimum rarity to display** — only show items `1/N` or rarer (default `10`; `0` shows everything).
- **Rate format** — how rates read: `Exact` (`100/2,440`), `1 in X` (`1/24.4`, as on the wiki), or
  `1 in X (rounded)` (`1/24`).
- **Colour by rarity** — colour the rate by how rare it is: yellow (common) → green → purple → red
  (ultra-rare). Off falls back to a single colour.
- **Rate text colour** — the single colour used when *Colour by rarity* is off.
- **Show qualitative rates** — also show `Uncommon` / `Rare` etc. when an exact rate is unknown.
- **Show guaranteed drops** — also show `Always` for 100% drops (bones, ashes…).
- **Merge with Ground Items** — append to each Ground Items line instead of a separate labelled line.

## Data

Drop rates are bundled in [`src/main/resources/drop_rates.json`](src/main/resources/drop_rates.json)
(~2,000 sources, ~34,000 drops), generated from the OSRS Wiki's `dropsline` Bucket. The wiki does not
expose numeric item ids in its drop data, so the file is keyed by item **name** and the plugin resolves
an item id to a name at runtime via `ItemManager`. Activity names are mapped to their real wiki page
(e.g. Chambers of Xeric → `Ancient chest`, Wintertodt → `Reward Cart`).

### Regenerating the data

[`scripts/generate_drop_rates.py`](scripts/generate_drop_rates.py) rebuilds the bundled file, respecting
the wiki's 1 request/second courtesy limit.

```sh
pip install requests

# Refresh only specific sources (fast):
python scripts/generate_drop_rates.py --sources "Abyssal demon,Vorkath,Chest (Barrows)" \
    -o src/main/resources/drop_rates.json

# Full rebuild of every drop table on the wiki (slow, several hundred requests):
python scripts/generate_drop_rates.py --full -o src/main/resources/drop_rates.json
```

## Building

```sh
./gradlew build      # compile + run the tests
./gradlew run        # launch a RuneLite dev client with the plugin loaded
```

Requires JDK 11.

## Known limitations

- Coverage is limited to what the wiki's `dropsline` template records (it excludes, e.g., bird-nest seed
  sub-tables and skilling success rates). Point/ticket shops (Mahogany Homes points, Soul Wars, Castle
  Wars) are intentionally out of scope — their supplies are bought, not random loot.
- Same-named NPC variants with different drop tables are matched by name; NPC ids are bundled for future
  id-based disambiguation.

## Licence

BSD 2-Clause. See [LICENSE](LICENSE).
