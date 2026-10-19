#!/usr/bin/env python3
"""
Live coverage probe for token art — measures CORRECT art, not merely "an image loads".

WHY THIS WAS REWRITTEN (2026-09-21)
-----------------------------------
The previous version walked synthesized URLs of the form
`api.scryfall.com/cards/t<engineSet>/<engineImageNumber>` and counted a token resolved
when the image returned 200. It reported 89.1% coverage.

That number was meaningless, and worse than meaningless — it hid the bug it was meant to
catch. The engine's `imageNumber` is NOT a Scryfall collector number: it is XMage's own
index into that set's printings of that name, and 1937 of the 2529 TOK rows leave it
EMPTY (so every one of them collapsed to `<set>/1` — the set's FIRST token, whoever that
is). Measured live:

    RTR|Soldier|   -> trtr/1 = "Bird"     (the Ravnica Soldier is trtr/3)
    M13|Soldier|   -> tm13/1 = "Cat"
    WAR|Servo|     -> twar/1 = "Spirit"
    XLN|Treasure|  -> txln/1 = "Vampire"
    a 46-row sample -> 40 WRONG tokens, 3 correct, 3 dead links

A wrong token's art is a perfectly good image. So "did an image load?" answers YES for
almost every broken tile, and the probe congratulated the implementation on the very
tiles that were showing the wrong creature.

WHAT THIS MEASURES NOW
----------------------
For every runtime token name, resolve it the way the proxy does (one Scryfall name
search, `unique=art`) and assert the returned printing is REALLY THAT TOKEN:

  * correct  — the top candidate's card name matches the token name (this is what the
               tile renders, since a token with no preference shows the engine's default
               or the first candidate)
  * wrong    — a candidate resolved but it is a different creature (the old bug)
  * none     — no printing anywhere; the card-back placeholder is the correct outcome

It also reports how many names WOULD have resolved to the wrong creature under the old
synthesized-URL scheme, so the fix's value is visible in the same run.

Run:  python3 token-art-coverage.py [--limit N] [--jobs N] [--min 0.85]
Exit: 0 when correct-art coverage is at or above --min, 1 otherwise.
"""
import argparse
import concurrent.futures
import glob
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


REPO = os.environ.get("XMAGE_REPO", os.path.expanduser("~/xmage"))
DB = os.path.join(REPO, "Mage/src/main/resources/tokens-database.txt")
TOKEN_SRC = os.path.join(REPO, "Mage/src/main/java/mage/game/permanent/token")
UA = "XMageWebProxy/1.0"
TIMEOUT = 15

# Scryfall allows < 10 req/s. This probe is deliberately single-threaded by default:
# the point is a trustworthy number, not a fast one, and a 429 mid-run would turn real
# coverage into a transport artefact.
DEFAULT_JOBS = 1
MIN_INTERVAL = 0.12

_rate_lock = None


def normalize(name):
    bare = re.sub(r"(?i)\s+Token$", "", name).strip()
    return bare or None


def build_engine():
    """name(bare) -> [(setCode, imageNumber)], mirroring the engine's own data."""
    by_name = {}
    with open(DB, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line.startswith("|"):
                continue
            p = [x.strip() for x in line.split("|")]
            if len(p) != 6 or not p[1].startswith("TOK:"):
                continue
            name, set_code, img = p[2], p[1].split(":", 1)[1], p[3]
            if not name or not set_code:
                continue
            by_name.setdefault(name, []).append((set_code, img or "1"))
    return by_name


def runtime_names():
    names = set()
    for f in glob.glob(os.path.join(TOKEN_SRC, "*.java")):
        with open(f, encoding="utf-8", errors="ignore") as fh:
            m = re.search(r'super\(\s*"((?:[^"\\]|\\.)*)"', fh.read())
        if m:
            names.add(m.group(1))
    return sorted(names)


def fetch_json(url, tries=4):
    """
    Returns (status, body). A transport failure is NOT a 404 — report it as 'ERR' so the
    caller can tell "could not ask" from "nothing exists", exactly as the service does.
    (Conflating the two undercounted this probe's own coverage on the first run: real
    tokens like Black Lotus and Blood Token were reported as having no art when Scryfall
    had simply rate-limited the run.)
    """
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    last = "ERR"
    for attempt in range(tries):
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
                return r.status, json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 429:
                time.sleep(2 + attempt * 4)   # Scryfall's 60 s cooldown, backed off
                last = 429
                continue
            if e.code == 404:
                return 404, {}
            last = e.code
            time.sleep(0.5)
        except Exception as ex:
            last = "ERR: %s" % ex
            time.sleep(0.5)
    return last, {}


def search(name, extra=" t:token"):
    """
    One name search, mirroring TokenArtService.searchScryfall.
    Returns (cards, transport_ok): transport_ok is False when Scryfall could not be
    reached at all, so an empty result can be reported honestly.
    """
    q = '!"%s"%s' % (name, extra)
    status, body = fetch_json(
        "https://api.scryfall.com/cards/search?q=" + urllib.parse.quote(q)
        + "&unique=art&order=released&dir=desc")
    if status == 200 and body.get("data"):
        return body["data"], True
    return [], (status in (200, 404))


def image_ok(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "image/*,*/*"})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            return r.status == 200 and r.headers.get("Content-Type", "").startswith("image/")
    except Exception:
        return False


def resolve_one(engine, name):
    """
    Resolve a token name the way the proxy does and judge the ART, not the HTTP status.

    Returns (name, verdict, detail) with verdict in
    {correct, wrong, none, deferred, unreachable}.
    """
    bare = normalize(name) or name
    cards, reachable = search(bare)
    if not cards and reachable:
        cards, reachable = search(bare, "")      # a token that copies a real card
    if not reachable:
        return (name, "unreachable", "Scryfall could not be asked (not 'no art')")
    if not cards:
        return (name, "none", "no printing on Scryfall (engine knows: %d sets)"
                % len(engine.get(bare, [])))

    top = cards[0]
    got = top.get("name")
    if got != bare:
        return (name, "wrong", "top printing is %r (%s), not the token"
                % (got, top.get("set_name")))

    url = (top.get("image_uris") or {}).get("small")
    if not url or not image_ok(url):
        return (name, "deferred", "top printing %s/%s has no loading image"
                % (top.get("set"), top.get("collector_number")))

    # How many distinct arts does this token offer? (the picker's choice count)
    return (name, "correct", "%d arts, newest %s/%s"
            % (len(cards), top.get("set"), top.get("collector_number")))


def old_scheme_verdict(engine, name):
    """
    What the PREVIOUS implementation would have rendered: the synthesized
    `t<engineSet>/<engineImageNumber>` URL. Returns True when that shows a DIFFERENT
    creature (the bug), so the run can quantify what was fixed.
    """
    bare = normalize(name) or name
    rows = engine.get(bare) or engine.get(name) or []
    if not rows:
        return None
    set_code, num = rows[0]
    status, body = fetch_json("https://api.scryfall.com/cards/t%s/%s"
                              % (set_code.lower(), num))
    if status != 200:
        return False           # 404: rendered nothing rather than the wrong art
    return body.get("name") != bare


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0, help="probe at most N names (0 = all)")
    ap.add_argument("--jobs", type=int, default=DEFAULT_JOBS,
                    help="parallel probes (keep at 1-2; Scryfall rate-limits)")
    ap.add_argument("--min", type=float, default=0.85, help="required correct-art ratio")
    ap.add_argument("--legacy-sample", type=int, default=0,
                    help="also check N names against the OLD synthesized-URL scheme")
    args = ap.parse_args()

    engine = build_engine()
    names = runtime_names()
    if args.limit:
        names = names[:args.limit]

    stats = {"correct": 0, "wrong": 0, "none": 0, "deferred": 0, "unreachable": 0}
    problems = []

    def work(n):
        r = resolve_one(engine, n)
        time.sleep(MIN_INTERVAL)      # stay under Scryfall's ~10 req/s
        return r

    with concurrent.futures.ThreadPoolExecutor(args.jobs) as ex:
        for name, verdict, detail in ex.map(work, names):
            stats[verdict] += 1
            if verdict in ("wrong", "none", "deferred", "unreachable"):
                problems.append((name, verdict, detail))

    total = len(names)
    # Rate-limited names are excluded from the DENOMINATOR: counting them as failures
    # would report a transport artefact as a coverage problem.
    judged = total - stats["unreachable"]
    ratio = stats["correct"] / judged if judged else 0.0

    print("runtime token names             : %d" % total)
    print("  correct art (REAL printings)  : %d" % stats["correct"])
    print("  WRONG creature                : %d" % stats["wrong"])
    print("  no art anywhere (placeholder) : %d" % stats["none"])
    print("  art present but image dead    : %d" % stats["deferred"])
    if stats["unreachable"]:
        print("  NOT JUDGED (rate-limited)     : %d  <- excluded from coverage" % stats["unreachable"])
    print("correct-art coverage            : %.1f%% (of %d judged)" % (100 * ratio, judged))

    if args.legacy_sample:
        sample = names[:args.legacy_sample]
        wrong_old = 0
        for n in sample:
            v = old_scheme_verdict(engine, n)
            if v:
                wrong_old += 1
            time.sleep(MIN_INTERVAL)
        print()
        print("--- what the OLD synthesized-URL scheme did (%d-name sample) ---" % len(sample))
        print("  rendered a DIFFERENT creature : %d/%d (%.0f%%)"
              % (wrong_old, len(sample), 100 * wrong_old / len(sample)))

    if problems:
        print("\n--- not showing correct art ---")
        for n, verdict, detail in sorted(problems):
            print("  %-26s %-9s %s" % (n, verdict, detail))

    verdict_s = "PASS" if ratio >= args.min else "FAIL"
    print("\n%s (required >= %.0f%%)" % (verdict_s, args.min * 100))
    return 0 if ratio >= args.min else 1


if __name__ == "__main__":
    sys.exit(main())
