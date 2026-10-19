#!/usr/bin/env bash
# builtin-decks.sh — generate web/frontend/decks/builtin.json for the web
# client's built-in deck picker.
#
# Sources:
#   1. the mtgtop8 majors corpus (--majors, default ~/mtgtop8-majors;
#      main-event decks only — _side-events/, *.ILLEGAL.txt and *.UNAVAILABLE.txt excluded)
#   2. the XMage sample decks (--samples, default ../Mage.Client/release/sample-decks
#      relative to Mage.WebProxy; every .dck, incl. Momir Basic)
#
# EVERY shipped deck is validated through the exact web path first:
# DeckLoader.parseDeckList + per-card resolution against the version-matched
# card DB (plain names via findCards, sample [SET:NUM] codes via findCard) —
# anything that would die at join time ("Card not found") is quarantined
# loudly and excluded; the run FAILS when anything was quarantined.
#
# Usage (from the repo root):
#   bash Mage.WebProxy/scripts/builtin-decks.sh
#
# Requirements: the Mage.WebProxy module is built (target/classes + target/lib
# populated) and Mage.WebProxy/db/cards.h2.mv.db is a staged, populated card DB
# (scripts/stage-test-db.sh). The generator runs with CWD=Mage.WebProxy because
# CardRepository opens ./db/cards.h2 relative to the CWD.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
MODULE="$(cd "$HERE/.." && pwd)"          # Mage.WebProxy/
REPO="$(cd "$MODULE/.." && pwd)"          # repo root

MAJORS="${1:-${XMAGE_MAJORS:-$HOME/mtgtop8-majors}}"
SAMPLES="${2:-$MODULE/../Mage.Client/release/sample-decks}"

OUT_DEFAULT="web/frontend/decks/builtin.json"

if [ ! -d "$MODULE/target/classes" ]; then
  echo "ERROR: Mage.WebProxy/target/classes missing — build the module first:" >&2
  echo "  flock /tmp/waved-maven.lock mvn -q -nsu -pl Mage.WebProxy install -DskipTests" >&2
  exit 1
fi
if [ ! -f "$MODULE/db/cards.h2.mv.db" ]; then
  echo "ERROR: $MODULE/db/cards.h2.mv.db missing — stage it first:" >&2
  echo "  bash Mage.WebProxy/scripts/stage-test-db.sh" >&2
  exit 1
fi

# CWD MUST be Mage.WebProxy (CardRepository resolves ./db relative to it).
cd "$MODULE"

# Compile the validator harness to a scratch dir (it lives in scripts/, outside
# src/main/java, so it never ships in mage-web-proxy.jar — same pattern as
# GameHistoryHarness).
OUT_CLS="$(mktemp -d)"
trap 'rm -rf "$OUT_CLS"' EXIT
javac -nowarn -cp "target/classes:target/lib/*" -d "$OUT_CLS" "$HERE/BuiltinDeckValidator.java"

java -cp "$OUT_CLS:target/classes:target/lib/*" mage.webproxy.BuiltinDeckValidator \
  --majors "$MAJORS" \
  --samples "$SAMPLES" \
  --out "$OUT_DEFAULT"

echo "builtin.json regenerated: $(pwd)/$OUT_DEFAULT"