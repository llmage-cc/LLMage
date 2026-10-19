#!/usr/bin/env python3
"""build-scryfall-map.py — build the webui's offline Scryfall image-name maps.

Downloads the current `oracle-cards` and `unique-artwork` bulk files from
Scryfall (the /bulk-data index hands out fresh download_uris; the files roll
over every ~12h), then emits two JSON resources into Mage/src/main/resources/
so they land in mage.jar (which is on the web proxy's Class-Path):

  scryfall-name-map.json   {"cards": {name -> uuid, ...}, "backs": [name, ...]}
  scryfall-token-map.json  {"tokens": {name -> [{id, set, num, set_name, released}, ...]}}

Why offline maps: api.scryfall.com is rate-limited (~2 req/s) and a full board
was a 30-60 request burst; cards.scryfall.io (the CDN the API 302s to) is NOT
rate-limited, and its URLs are derivable from the card uuid alone:

  https://cards.scryfall.io/{size}/front/{id[0]}/{id[1]}/{id}.jpg

so the proxy resolves a name to a uuid from these maps and builds the CDN URL
itself, never touching the API.

Traps (all measured against live data 2026-09-23/24 — do not "simplify" them away):

  1. `layout: art_series` entries are EXCLUDED from the card map. Their
     `//`-combined names collide with real cards and appear FIRST in the
     stream; a naive map maps "Lightning Bolt" to a Strixhaven art-series card.
  2. XMage names are accent-folded (Adewale vs Scryfall Adéwalé): both maps
     ALSO index the NFD-stripped form of every name.
  3. XMage knows FRONT-FACE names only ("Delver of Secrets"); oracle entries
     are `//`-combined and carry card_faces[]. Every face name is indexed to
     the entry's uuid. `backs` lists the FRONT-face names of layouts whose
     back face carries its own image (transform/modal_dfc/double_faced_token/
     reversible_card/battle), so the proxy can build the /back/ URL; both the
     combined name and the front-face name are listed (XMage compares against
     what it knows).
  4. Token art comes from unique-artwork, NOT oracle-cards: oracle has ~1 art
     per token name but the picker needs them all (Soldier: 42 vs 13). Each
     token art stores the scryfall uuid directly (plus set/number for the
     picker's engine-set default matching) — the old api.scryfall.com
     `t<set>/<n>` URL form is gone entirely.
  5. unique-artwork is one entry per distinct artwork, newest-first; arts are
     NOT deduped by illustration, so a reprint of an artwork under the
     engine's set still matches the engine-set default (the "Ravnica
     Soldier" rule).

  Self-check at the end asserts key sample resolutions and prints counts + gz
  sizes. Run from anywhere:  python3 Mage.WebProxy/scripts/build-scryfall-map.py
"""

import gzip
import json
import re
import sys
import unicodedata
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
OUT_DIR = REPO / "Mage" / "src" / "main" / "resources"
TOKENS_DB = OUT_DIR / "tokens-database.txt"

USER_AGENT = "XMageWebProxyMapBuilder/1.0"
BULK_INDEX_URL = "https://api.scryfall.com/bulk-data"

# Layouts whose back face carries its own image (recon: 488 XMage DFC names).
BACK_FACE_LAYOUTS = {
    "transform",
    "modal_dfc",
    "double_faced_token",
    "reversible_card",
    "battle",
}


def fetch_json(url: str):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT,
                                               "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        if e.code == 400:
            # Scryfall's API answers a bare 400 when the User-Agent is missing
            # an Accept header pair it trusts — retry once with both.
            req = urllib.request.Request(
                url, headers={"User-Agent": USER_AGENT,
                              "Accept": "application/json;q=0.9,*/*;q=0.8"})
            with urllib.request.urlopen(req, timeout=120) as r:
                return json.load(r)
        raise


def download_stream(url: str):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT,
                                               "Accept-Encoding": "gzip"})
    return urllib.request.urlopen(req, timeout=600)


def load_bulk(url: str) -> list:
    """Load one bulk file. Scryfall serves them gzip-compressed. Two formats
    exist side by side on the index: a JSON array (`download_uri`) and
    newline-delimited JSON (`jsonl_download_uri`) — the index above now points
    both at the JSONL one, so handle either."""
    with download_stream(url) as r:
        raw = r.read()
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    raw = raw.strip()
    if raw[:1] == b"[":
        return json.loads(raw)
    return [json.loads(line) for line in raw.split(b"\n") if line.strip()]


def fold(name: str) -> str:
    """Accent-fold: NFD + strip combining marks (Adéwalé -> Adewale)."""
    return "".join(
        ch for ch in unicodedata.normalize("NFD", name)
        if unicodedata.category(ch) != "Mn"
    )


def cn_leading_int(s: str):
    m = re.match(r"^(\d+)", s or "")
    return int(m.group(1)) if m else None


def main() -> int:
    print("fetching bulk-data index …", flush=True)
    index = fetch_json(BULK_INDEX_URL)
    uris = {}
    for entry in index.get("data", []):
        btype = entry.get("type", "").replace("_", "-")
        uri = entry.get("download_uri") or entry.get("jsonl_download_uri")
        if btype and uri:
            uris[btype] = uri
    for t in ("oracle-cards", "unique-artwork"):
        if t not in uris or not uris[t]:
            sys.exit(f"FATAL: bulk-data index has no download_uri for {t}")

    # ── Card map from oracle-cards ─────────────────────────────────────
    print("downloading oracle-cards …", flush=True)
    data = load_bulk(uris["oracle-cards"])
    print(f"oracle-cards: {len(data):,} entries", flush=True)
    cards = {}
    backs = set()
    stats = {"entries": 0, "art_series": 0, "token_entries": 0, "skipped_no_faces": 0}
    for e in data:
        stats["entries"] += 1
        layout = e.get("layout")
        if layout == "art_series":
            stats["art_series"] += 1
            continue
        cid = e.get("id")
        name = e.get("name")
        if not cid or not name:
            stats["skipped_no_faces"] += 1
            continue
        # Every name this entry can be looked up by: combined name + each face.
        names = [name]
        faces = e.get("card_faces")
        if isinstance(faces, list):
            for f in faces:
                fn = f.get("name")
                if fn:
                    names.append(fn)
        if layout == "token":
            stats["token_entries"] += 1
        for n in dict.fromkeys(names):
            cards.setdefault(n, cid)
            f = fold(n)
            if f != n:
                cards.setdefault(f, cid)
        if layout in BACK_FACE_LAYOUTS:
            backs.add(name)
            if isinstance(faces, list) and faces and faces[0].get("name"):
                backs.add(faces[0]["name"])
    del data

    name_map = {"cards": dict(sorted(cards.items())), "backs": sorted(backs)}
    name_path = OUT_DIR / "scryfall-name-map.json"
    name_path.write_text(json.dumps(name_map, separators=(",", ":"),
                                    ensure_ascii=False), encoding="utf-8")

    # ── Token map from unique-artwork ──────────────────────────────────
    print("downloading unique-artwork …", flush=True)
    udata = load_bulk(uris["unique-artwork"])
    print(f"unique-artwork: {len(udata):,} entries", flush=True)
    arts_by_name: dict[str, list[dict]] = {}
    seen_ill: dict[str, set] = {}
    tok_stats = {"entries": 0, "token_entries": 0, "no_image": 0}
    for e in udata:
        tok_stats["entries"] += 1
        if e.get("layout") == "art_series":
            continue
        if e.get("layout") != "token":
            continue
        tok_stats["token_entries"] += 1
        cid = e.get("id")
        name = e.get("name")
        if not cid or not name:
            continue
        scry_name = name[:-7] if name.endswith(" (Token)") else name
        # Face-less / double-faced token entries carry no usable image.
        if not e.get("image_uris") and not e.get("card_faces"):
            tok_stats["no_image"] += 1
            continue
        # NO illustration dedupe: unique-artwork is already one entry per
        # distinct artwork, and the engine-set default match below needs every
        # SET that printed a distinct art for this token.
        arts_by_name.setdefault(scry_name, []).append({
            "id": cid,
            "set": e.get("set"),
            "num": e.get("collector_number"),
            "set_name": e.get("set_name"),
            "released": e.get("released_at"),
        })
    del udata

    # XMage's runtime token names come from tokens-database.txt: map EVERY
    # engine-known name, plus the Scryfall names that have no engine row.
    engine_names = set()
    tok_line = re.compile(r"^\|TOK:([^|]+)\|([^|]*)\|")
    try:
        with open(TOKENS_DB, encoding="utf-8", errors="replace") as f:
            for line in f:
                m = tok_line.match(line.strip())
                if m and m.group(2).strip():
                    engine_names.add(m.group(2).strip())
    except FileNotFoundError:
        sys.exit(f"FATAL: {TOKENS_DB} not found — tokens-database.txt ships in the Mage module")

    tokens = {}
    for ename in sorted(engine_names):
        for candidate in (ename, fold(ename)):
            arts = arts_by_name.get(candidate)
            if arts:
                tokens[ename] = arts
                break
    for sname, arts in sorted(arts_by_name.items()):
        if sname not in tokens and fold(sname) not in engine_names and sname not in engine_names:
            tokens[sname] = arts
    # Folded aliases for Scryfall-only names with accents (same contract as cards).
    for sname in list(tokens):
        f = fold(sname)
        if f != sname and f not in tokens:
            tokens[f] = tokens[sname]

    token_map = {"tokens": dict(sorted(tokens.items()))}
    token_path = OUT_DIR / "scryfall-token-map.json"
    token_path.write_text(json.dumps(token_map, separators=(",", ":"),
                                     ensure_ascii=False), encoding="utf-8")

    # ── Sizes ──────────────────────────────────────────────────────────
    def gz_len(p: Path) -> int:
        data = p.read_bytes()
        return len(gzip.compress(data, compresslevel=9))

    name_gz = gz_len(name_path)
    token_gz = gz_len(token_path)

    # ── Self-check ─────────────────────────────────────────────────────
    fails = []

    def check(cond: bool, label: str):
        print(f"  {'ok  ' if cond else 'FAIL'} {label}", flush=True)
        if not cond:
            fails.append(label)

    check(bool(re.fullmatch(r"[0-9a-f-]{36}", cards.get("Lightning Bolt", ""))),
          "'Lightning Bolt' maps to a uuid (art_series excluded)")
    check(cards.get("Adéwalé, Breaker of Chains") is not None,
          "'Adéwalé, Breaker of Chains' resolves (accented form)")
    check(cards.get("Adewale, Breaker of Chains") is not None,
          "'Adewale, Breaker of Chains' (accent-fold) resolves")
    check(bool(re.fullmatch(r"[0-9a-f-]{36}", cards.get("Delver of Secrets", ""))),
          "'Delver of Secrets' resolves")
    check("Delver of Secrets" in backs, "'Delver of Secrets' is a DFC (in backs)")
    soldier = tokens.get("Soldier")
    check(soldier is not None and len(soldier) > 1,
          f"'Soldier' has multiple arts ({len(soldier) if soldier else 0})")
    check(all(re.fullmatch(r"[0-9a-f-]{36}", a["id"]) for a in (soldier or [])[:5]),
          "token arts carry scryfall uuids")
    check(all(a["set"] for a in (soldier or [])[:5]), "token arts carry set codes")
    check(stats["art_series"] > 2000, f"art_series entries were excluded ({stats['art_series']})")

    print(f"\nname map : {len(cards)} keys (+{len(backs)} back-face names), gz {name_gz:,} B")
    print(f"token map: {len(tokens)} names "
          f"({sum(len(v) for v in tokens.values())} arts), gz {token_gz:,} B")
    print(f"oracle   : {stats}")
    print(f"unique-artwork tokens: {tok_stats}")
    if fails:
        print(f"\nSELF-CHECK FAILED: {fails}", file=sys.stderr)
        return 1
    print("self-check: all ok", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
