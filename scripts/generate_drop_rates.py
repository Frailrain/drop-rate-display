"""
Generates the bundled ``drop_rates.json`` file from the OSRS Wiki Bucket API.

This is a build-time tool, not part of the plugin runtime. Run it before a release to
refresh the bundled data. The monthly ``refresh-drop-data`` GitHub Action runs it in
``--full --non-interactive`` mode and opens a pull request when the data has changed.

Modes:

  # Targeted: only the named sources (fast; used to build a curated sample).
  python generate_drop_rates.py --sources "Abyssal demon,Vorkath,Barrows" -o out.json

  # Full: scan every drop-table line on the wiki (slow; several hundred requests).
  python generate_drop_rates.py --full -o ../src/main/resources/drop_rates.json

  # Offline: re-run the compare/summary logic against previously generated data, no requests.
  python generate_drop_rates.py --input fetched.json -o ../src/main/resources/drop_rates.json

Change detection: the ``version`` field is the generation date and would make every run look
like a change. The script therefore compares the freshly built ``sources`` against the
``sources`` in the existing output file and only rewrites the file (bumping ``version``) when
they actually differ. A change summary (sources added / removed / changed) is printed to
stdout and optionally written to ``--summary-file`` for the workflow's PR description.

Safety: if any API call fails after retries, returns empty data, or the result is implausibly
smaller than the existing file, the script exits non-zero without touching the output file.

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
import os
import sys
import time
from collections import defaultdict

import requests

WIKI_API = "https://oldschool.runescape.wiki/api.php"
USER_AGENT = (
    "DropRateDisplay/1.0 (RuneLite plugin data generator; "
    "https://github.com/Frailrain/drop-rate-display; contact: Frailrain)"
)
HEADERS = {"User-Agent": USER_AGENT}
ROWS_PER_REQUEST = 500
REQUEST_DELAY = 1.0  # minimum seconds between requests (wiki courtesy limit)

# A fresh full scan returning fewer than this fraction of the existing source count is treated
# as a partial/broken result rather than a real change, so the good data is never overwritten.
MIN_SOURCE_RATIO = 0.9

# How many names to list per category in the change summary before truncating.
SUMMARY_LIST_LIMIT = 40

_last_request_at = 0.0


class GenerationError(Exception):
    """Raised when the data could not be fetched or fails a sanity check."""


def _pace():
    """Sleeps so that consecutive requests are at least REQUEST_DELAY seconds apart."""
    global _last_request_at
    wait = REQUEST_DELAY - (time.monotonic() - _last_request_at)
    if wait > 0:
        time.sleep(wait)
    _last_request_at = time.monotonic()


def fetch_bucket(query, retries=4):
    """Runs one Bucket query and returns the list of result rows (under the 'bucket' key).

    Retries transient network/HTTP failures with backoff so a long unattended full scan is not
    lost to a single blip. A Bucket 'error' in the payload is a query bug, not transient, so it
    is raised immediately without retry. Every attempt is paced to the courtesy limit.
    """
    last_error = None
    for attempt in range(retries):
        _pace()
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
                raise GenerationError(f"Bucket API error for query [{query}]: {payload['error']}")
            # Rows live under "bucket"; fall back to other keys defensively.
            rows = payload.get("bucket", payload.get("data", payload.get("rows")))
            if not isinstance(rows, list):
                raise GenerationError(f"Unexpected Bucket response shape for query [{query}]")
            return rows
        except (requests.RequestException, ValueError) as exc:
            last_error = exc
            wait = 2 ** attempt
            print(f"  request failed ({exc}); retry {attempt + 1}/{retries} in {wait}s", file=sys.stderr)
            time.sleep(wait)
    raise GenerationError(f"Bucket query failed after {retries} attempts: {last_error}")


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
        npc_ids = fetch_source_npc_ids(source)
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
    if not ids:
        raise GenerationError("infobox_monster scan returned no rows")
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


def load_sources(path):
    """Reads the ``sources`` dict from a previously generated file, or None if absent."""
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    sources = data.get("sources")
    if not isinstance(sources, dict):
        raise GenerationError(f"{path} has no 'sources' object")
    return sources


def diff_sources(old, new):
    """Returns (added, removed, changed): sorted lists of source names."""
    old = old or {}
    added = sorted(name for name in new if name not in old)
    removed = sorted(name for name in old if name not in new)
    changed = sorted(name for name in new if name in old and old[name] != new[name])
    return added, removed, changed


def _format_list(title, names):
    lines = [f"### {title} ({len(names)})"]
    if not names:
        lines.append("_none_")
        return lines
    for name in names[:SUMMARY_LIST_LIMIT]:
        lines.append(f"- {name}")
    if len(names) > SUMMARY_LIST_LIMIT:
        lines.append(f"- ...and {len(names) - SUMMARY_LIST_LIMIT} more")
    return lines


def format_summary(old, new, added, removed, changed):
    """Builds the Markdown change summary used for the pull request body."""
    old_count = len(old) if old is not None else 0
    old_drops = sum(len(s["drops"]) for s in (old or {}).values())
    new_drops = sum(len(s["drops"]) for s in new.values())
    lines = ["## Drop data change summary", ""]
    if not (added or removed or changed):
        lines.append(f"No changes: {len(new)} sources, {new_drops} drops match the existing data.")
        return "\n".join(lines) + "\n"
    lines.append(f"Sources: {old_count} -> {len(new)}. Drops: {old_drops} -> {new_drops}.")
    lines.append("")
    lines.extend(_format_list("Sources added", added))
    lines.append("")
    lines.extend(_format_list("Sources removed", removed))
    lines.append("")
    lines.extend(_format_list("Sources with changed drops", changed))
    return "\n".join(lines) + "\n"


def check_sanity(old, new, allow_shrink):
    """Rejects empty or implausibly small results so partial data never replaces good data."""
    if not new:
        raise GenerationError("no sources were produced; refusing to write an empty data file")
    if old and not allow_shrink:
        floor = int(len(old) * MIN_SOURCE_RATIO)
        if len(new) < floor:
            raise GenerationError(
                f"new data has {len(new)} sources but the existing file has {len(old)} "
                f"(below {MIN_SOURCE_RATIO:.0%}); this looks like a partial result. "
                f"Re-run with --allow-shrink if the drop is genuine."
            )


def main():
    parser = argparse.ArgumentParser(description="Generate drop_rates.json from the OSRS Wiki.")
    parser.add_argument("--full", action="store_true", help="Scan every drop line on the wiki.")
    parser.add_argument("--sources", help="Comma-separated source page names (targeted mode).")
    parser.add_argument(
        "--input",
        help="Skip fetching; load 'sources' from this previously generated JSON file "
        "(for testing the compare logic offline).",
    )
    parser.add_argument("-o", "--out", default="drop_rates.json", help="Output path.")
    parser.add_argument(
        "--non-interactive",
        action="store_true",
        help="Never prompt or wait for input (for CI). The script currently has no prompts; "
        "the flag is accepted so automation is stable if one is ever added.",
    )
    parser.add_argument(
        "--summary-file", help="Also write the Markdown change summary to this path."
    )
    parser.add_argument(
        "--allow-shrink",
        action="store_true",
        help="Allow a result with far fewer sources than the existing file (see MIN_SOURCE_RATIO).",
    )
    args = parser.parse_args()

    try:
        existing = load_sources(args.out)

        if args.input:
            sources = load_sources(args.input)
            if sources is None:
                raise GenerationError(f"--input file not found: {args.input}")
        elif args.full:
            sources = build_full()
        elif args.sources:
            names = [s.strip() for s in args.sources.split(",") if s.strip()]
            sources = build_targeted(names)
        else:
            parser.error("specify --full, --sources or --input")
            return

        check_sanity(existing, sources, args.allow_shrink)
    except GenerationError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        print(f"{args.out} was not modified.", file=sys.stderr)
        sys.exit(1)

    added, removed, changed = diff_sources(existing, sources)
    summary = format_summary(existing, sources, added, removed, changed)
    print(summary, end="")
    if args.summary_file:
        with open(args.summary_file, "w", encoding="utf-8") as f:
            f.write(summary)

    total_drops = sum(len(s["drops"]) for s in sources.values())
    if existing is not None and not (added or removed or changed):
        print(f"{args.out} unchanged ({len(sources)} sources, {total_drops} drops)", file=sys.stderr)
        return

    output = {"version": time.strftime("%Y-%m-%d"), "sources": sources}
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(output, f, separators=(",", ":"), ensure_ascii=False)
    print(f"Wrote {args.out}: {len(sources)} sources, {total_drops} drops", file=sys.stderr)


if __name__ == "__main__":
    main()
