#!/usr/bin/env bash
# Bundle + run the "Only selectable" prompt-filter checks under Node (no browser).
#
#   bash scripts/verify-only-selectable.sh
#
# Covers the fetch-land / library-search grid (CardSelectPopup's TargetSelectBody):
#  (a) the switch defaults OFF and every candidate renders,
#  (b) with it ON exactly the selectable cards render (count stays honest),
#  (c) the preference survives a new prompt,
#  (d) the ANSWER PATH IS UNTOUCHED — a click still dispatches `select_target` with
#      that card's own id,
#  (e) trigger-order prompts and the all-hidden case behave.
#
# Same pattern as verify-trigger-order.sh: the REAL store, the REAL message handler
# and the REAL popup component are bundled with browser stubs.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-only-selectable-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-only-selectable.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-only-selectable-entry.tsx --file /tmp/xmage-only-selectable.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-only-selectable.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-only-selectable.mjs
