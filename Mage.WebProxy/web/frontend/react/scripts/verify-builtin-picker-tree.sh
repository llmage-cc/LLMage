#!/usr/bin/env bash
# Regression harness for the New-Game full-app blank (2026-09-26): the
# BuiltInDeckPicker tree build destroyed every subcategory list (subs.clear()
# then subs.set(k, subs.get(k)!)) and threw "Cannot read properties of
# undefined (reading 'sort')" on the first data render, unmounting the whole
# app. This runs buildTree from the REAL component module over the REAL
# generated web/frontend/decks/builtin.json.
#
#   bash scripts/verify-builtin-picker-tree.sh [path-to-builtin.json]
# Default path: ../decks/builtin.json (relative to the react dir).
#
# Same bundling pattern as verify-random-deck.sh / verify-builtin-decks.sh:
# bundle the .ts with the repo's rolldown and run under Node (no jsdom dep).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."

cat > /tmp/xmage-bpt-entry.ts <<EOF
import '$HERE/verify-builtin-picker-tree.ts'
EOF

./node_modules/.bin/rolldown /tmp/xmage-bpt-entry.ts --file /tmp/xmage-builtin-picker-tree.mjs --format esm --platform node >/dev/null
node /tmp/xmage-builtin-picker-tree.mjs "$@"