"""
Generates the bundled ``drop_rates.json`` file from the OSRS Wiki Bucket API.

This is a build-time tool, not part of the plugin runtime. Run it before a release to
refresh the bundled data.

Two modes:

  # Targeted: only the named sources (fast; used to build a curated sample).
  python generate_drop_rates.py --sources "Abyssal demon,Vorkath,Barrows" -o out.json

  # Full: scan every drop-table line on the wiki (slow; several hundred requests).
  python generate_drop_rates.py --full -o ../src/main/resources/drop_rates.json

Data model (verified against the live API, July 2026):

  * The drop bucket is ``dropsline`` (not ``drops_line``). Its only queryable columns are
    ``page_name`` (the source page the drop line lives on), ``page_name_sub``, ``item_name``,
    ``drop_json`` and ``rare_drop_table``.
  * All the useful fields are packed into ``drop_json``, a JSON blob with Title-Case keys:
    "Dropped item", "Dropped from", "Rarity", "Quantity Low", "Quantity High", "Rolls", ...
  * There is no item id anywhere in the drop data. Output is therefore keyed by item NAME;
    the plugin resolves ItemStack ids to names at runtime via RuneLite's ItemManager.
  * NPC ids come from the ``infobox_monster`` bucket: ``page_name`` -> ``id`` (a repeated
    array of id strings) plus ``combat_level``.
  * The API returns result rows under the ``bucket`` key.

Respects the wiki's request-rate courtesy (1 request/second).
Requires: requests (pip install requests)
"""

import argparse
import json
import sys
import time
from collections import defaultdict

import requests

WIKI_API = "https://oldschool.runescape.wiki/api.php"
USER_AGENT = "DropRateDisplay/1.0 (RuneLite plugin data generator; contact: Frailrain)"
HEADERS = {"User-Agent": USER_AGENT}
ROWS_PER_REQUEST = 500
REQUEST_DELAY = 1.0  # seconds between requests


def fetch_bucket(query, retries=4):
    """Runs one Bucket query and returns the list of result rows (under the 'bucket' key).

    Retries transient network/HTTP failures with backoff so a long unattended full scan is not
    lost to a single blip. A Bucket 'error' in the payload is a query bug, not transient, so it
    is raised immediately without retry.
    """
    last_error = None
    for attempt in range(retries):
        try:
            response = requests.get(
                WIKI_API,
                params={"action": "bucket", "format": "json", "query": query},
                headers=HEADERS,
                timeout=30,
            )
            response.raise_for_status()
            payload = response.json()
            if "error" in payload:
                raise RuntimeError(f"Bucket API error for query [{query}]: {payload['error']}")
            # Rows live under "bucket"; fall back to other keys defensively.
            return payload.get("bucket", payload.get("data", payload.get("rows", [])))
        except requests.RequestException as exc:
            last_error = exc
            wait = 2 ** attempt
            print(f"  request failed ({exc}); retry {attempt + 1}/{retries} in {wait}s", file=sys.stderr)
            time.sleep(wait)
    raise RuntimeError(f"Bucket query failed after {retries} attempts: {last_error}")


def _escape(value):
    """Escapes a single quote for embedding a literal in a bucket() query string."""
    return value.replace("'", "\\'")


def parse_drop_row(row):
    """Extracts (item_name, rate, quantity) from a dropsline row's drop_json blob, or None."""
    blob = row.get("drop_json")
    if not blob:
        return None
    try:
        dj = json.loads(blob)
    except (json.JSONDecodeError, TypeError):
        return None

    item_name = dj.get("Dropped item") or row.get("item_name")
    rarity = dj.get("Rarity", "")
    if not item_name or not rarity:
        return None
    if item_name.strip().lower() == "nothing" or rarity.strip().lower() == "n/a":
        return None

    qty_low = str(dj.get("Quantity Low", dj.get("Drop Quantity", "1"))).strip() or "1"
    qty_high = str(dj.get("Quantity High", qty_low)).strip() or qty_low
    quantity = qty_low if qty_low == qty_high else f"{qty_low}-{qty_high}"

    return item_name, rarity, quantity


def add_drop(drops, parsed):
    """Records a parsed drop under its item, keeping every distinct quantity as its own variant.

    Within one item, the first rate seen for a given quantity wins (e.g. Standard before Wilderness),
    which avoids rate churn; but different quantities of the same item (e.g. Numulite x2 vs x4, each
    with its own rate) are kept side by side so the plugin can show the rate for the exact stack the
    player received. ``drops`` holds the internal shape ``item_name -> [ {rate, quantity}, ... ]``;
    call finalize_drops() to convert it to the output shape.
    """
    item_name, rarity, quantity = parsed
    variants = drops.setdefault(item_name, [])
    for existing in variants:
        if existing["quantity"] == quantity:
            return  # same (item, quantity) already recorded; first rate seen wins
    variants.append({"rate": rarity, "quantity": quantity})


def finalize_drops(drops):
    """Converts the internal ``item_name -> [variant, ...]`` map into the output shape.

    The first variant becomes the entry's top-level ``rate``/``quantity`` -- this doubles as the
    fallback the plugin shows when a received quantity matches no row -- and any further quantities
    are listed under ``variants``:

        "Numulite": {"rate": "29/128", "quantity": "4", "variants": [{"rate": "...", "quantity": "2"}]}
    """
    out = {}
    for item_name, variants in drops.items():
        entry = {"rate": variants[0]["rate"], "quantity": variants[0]["quantity"]}
        if len(variants) > 1:
            entry["variants"] = variants[1:]
        out[item_name] = entry
    return out


def fetch_source_drops(source):
    """Targeted: all drop lines whose page_name == source."""
    drops = {}
    offset = 0
    while True:
        query = (
            f"bucket('dropsline')"
            f".select('page_name','page_name_sub','item_name','drop_json','rare_drop_table')"
            f".where('page_name','{_escape(source)}')"
            f".limit({ROWS_PER_REQUEST}).offset({offset}).run()"
        )
        rows = fetch_bucket(query)
        for row in rows:
            parsed = parse_drop_row(row)
            if parsed:
                add_drop(drops, parsed)
        if len(rows) < ROWS_PER_REQUEST:
            break
        offset += ROWS_PER_REQUEST
        time.sleep(REQUEST_DELAY)
    return finalize_drops(drops)


def fetch_source_npc_ids(source):
    """Targeted: union of monster ids for a source page (empty if it is not a monster)."""
    query = (
        f"bucket('infobox_monster')"
        f".select('page_name','id','combat_level')"
        f".where('page_name','{_escape(source)}')"
        f".limit({ROWS_PER_REQUEST}).offset(0).run()"
    )
    ids = set()
    for row in fetch_bucket(query):
        ids.update(_coerce_ids(row.get("id")))
    return sorted(ids)


def _coerce_ids(raw):
    """infobox_monster 'id' is a repeated field: normalise list/scalar to a set of ints."""
    if raw is None:
        return set()
    values = raw if isinstance(raw, list) else [raw]
    out = set()
    for value in values:
        try:
            out.add(int(value))
        except (ValueError, TypeError):
            pass
    return out


def build_targeted(sources):
    result = {}
    for source in sources:
        print(f"Fetching '{source}'...", file=sys.stderr)
        drops = fetch_source_drops(source)
        time.sleep(REQUEST_DELAY)
        npc_ids = fetch_source_npc_ids(source)
        time.sleep(REQUEST_DELAY)
        if not drops:
            print(f"  (no drop lines found for '{source}' -- skipped)", file=sys.stderr)
            continue
        entry = {}
        if npc_ids:
            entry["npcIds"] = npc_ids
        entry["drops"] = drops
        result[source] = entry
        print(f"  {len(drops)} drops, {len(npc_ids)} npc id(s)", file=sys.stderr)
    return result


def fetch_all_monster_ids():
    """Full: page_name -> sorted list of monster ids, across the whole infobox_monster bucket."""
    ids = defaultdict(set)
    offset = 0
    while True:
        query = (
            f"bucket('infobox_monster')"
            f".select('page_name','id','combat_level')"
            f".orderBy('page_name','asc')"
            f".limit({ROWS_PER_REQUEST}).offset({offset}).run()"
        )
        rows = fetch_bucket(query)
        for row in rows:
            page = row.get("page_name")
            if page:
                ids[page].update(_coerce_ids(row.get("id")))
        print(f"  monster ids: {offset + len(rows)} rows", file=sys.stderr)
        if len(rows) < ROWS_PER_REQUEST:
            break
        offset += ROWS_PER_REQUEST
        time.sleep(REQUEST_DELAY)
    return ids


def build_full():
    """Full: scan every dropsline row, group by page_name, join monster ids."""
    print("Fetching all monster ids...", file=sys.stderr)
    monster_ids = fetch_all_monster_ids()

    print("Scanning all drop lines...", file=sys.stderr)
    sources = defaultdict(lambda: {"drops": {}})
    offset = 0
    while True:
        query = (
            f"bucket('dropsline')"
            f".select('page_name','page_name_sub','item_name','drop_json','rare_drop_table')"
            f".orderBy('page_name','asc')"
            f".limit({ROWS_PER_REQUEST}).offset({offset}).run()"
        )
        rows = fetch_bucket(query)
        for row in rows:
            page = row.get("page_name")
            parsed = parse_drop_row(row)
            if page and parsed:
                add_drop(sources[page]["drops"], parsed)
        print(f"  drops: {offset + len(rows)} rows, {len(sources)} sources", file=sys.stderr)
        if len(rows) < ROWS_PER_REQUEST:
            break
        offset += ROWS_PER_REQUEST
        time.sleep(REQUEST_DELAY)

    result = {}
    for page, entry in sources.items():
        if not entry["drops"]:
            continue
        out = {}
        if page in monster_ids and monster_ids[page]:
            out["npcIds"] = sorted(monster_ids[page])
        out["drops"] = finalize_drops(entry["drops"])
        result[page] = out
    return result


def main():
    parser = argparse.ArgumentParser(description="Generate drop_rates.json from the OSRS Wiki.")
    parser.add_argument("--full", action="store_true", help="Scan every drop line on the wiki.")
    parser.add_argument("--sources", help="Comma-separated source page names (targeted mode).")
    parser.add_argument("-o", "--out", default="drop_rates.json", help="Output path.")
    args = parser.parse_args()

    if args.full:
        sources = build_full()
    elif args.sources:
        names = [s.strip() for s in args.sources.split(",") if s.strip()]
        sources = build_targeted(names)
    else:
        parser.error("specify --full or --sources")
        return

    output = {"version": time.strftime("%Y-%m-%d"), "sources": sources}
    total_drops = sum(len(s["drops"]) for s in sources.values())

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(output, f, separators=(",", ":"), ensure_ascii=False)

    print(f"Wrote {args.out}: {len(sources)} sources, {total_drops} drops", file=sys.stderr)


if __name__ == "__main__":
    main()
