#!/usr/bin/env bash
# Bundle + run the Add Lands… dialog checks under Node (no browser).
#
#   bash scripts/verify-deck-add-land.sh
#
# Covers Add-land / basic lands in the deck editor, mirroring
# the swing AddLandDialog):
#   A. the REAL dialog component renders every basic land with quantity controls,
#   B. the REAL store action resolves each land through `card_search` and adds
#      the returned search result via `addCard` (set/number/art from the DB),
#   C. Fail Fast — an unresolvable land rejects and adds nothing for it; Wastes
#      is looked up without the LAND rarity filter (mixed-rarity printings);
#      all-zero counts are a no-op; every new control carries an EXPLAIN id.
#
# Same pattern as verify-provider-dropdown.sh: REAL components + the REAL store
# are bundled with browser stubs and rendered through react-dom/server.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"

cat > /tmp/xmage-deck-add-land-entry.tsx <<EOF
import '/tmp/xmage-deck-add-land-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-deck-add-land.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-deck-add-land-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-deck-add-land-entry.tsx \
  --file /tmp/xmage-deck-add-land.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-deck-add-land.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
export XMAGE_REACT_SRC="$HERE/src"
export XMAGE_REACT_ROOT="$HERE"
node /tmp/xmage-deck-add-land.mjs
