#!/usr/bin/env bash
# Bundle + run the deck-open hydration checks under Node (no browser).
#
#   bash scripts/verify-deck-open-hydrates.sh
#
# Covers the secondary finding of :
# a deck opened from the saved-deck list must hydrate card art exactly like a
# text import does. Drives the REAL store's openDeck (the call
# ImportExportControls makes for the Open… list) with the REAL deckSlice's
# hydrateDeckImages path — same rolldown-bundle-with-stubs pattern as
# scripts/verify-collection.sh / verify-deck-import.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"

cat > /tmp/xmage-deck-open-hydrates-entry.ts <<EOF
import '/tmp/xmage-deck-open-hydrates-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-deck-open-hydrates.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-deck-open-hydrates-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-deck-open-hydrates-entry.ts \
  --file /tmp/xmage-deck-open-hydrates.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-deck-open-hydrates.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
export XMAGE_REACT_SRC="$HERE/src"
export XMAGE_REACT_ROOT="$HERE"
node /tmp/xmage-deck-open-hydrates.mjs
