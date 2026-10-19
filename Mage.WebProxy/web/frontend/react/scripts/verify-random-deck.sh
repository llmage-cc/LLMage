#!/usr/bin/env bash
# Bundle + run the random-deck generator checks under Node.
#   bash scripts/verify-random-deck.sh
#
# Drives the REAL pure core (utils/randomDeck.ts) and the REAL store slice
# (deckSlice.generateRandomDeck against a stubbed ws), mirroring
# verify-deck-import.sh.
#
#
# Fixture note: the store's dev-only window export reads import.meta.env.DEV,
# which does not exist under Node. Rather than neutralizing that one line in the
# throwaway bundle post-hoc (fragile against bundler churn), the bundle step
# defines import.meta.env.DEV=false — the same value production builds carry —
# so the line folds to `if (false)` and drops out.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."
cat > /tmp/xmage-rd-entry.ts <<EOF
import '/tmp/xmage-rd-stubs.mjs'
import '$HERE/verify-random-deck.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-rd-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-rd-entry.ts --file /tmp/xmage-rd.mjs --format esm --platform node \
  --transform.define "import.meta.env.DEV:false" >/dev/null
node /tmp/xmage-rd.mjs