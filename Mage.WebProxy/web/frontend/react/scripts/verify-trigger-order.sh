#!/usr/bin/env bash
# Bundle + run the trigger-order prompt checks under Node (no browser).
#
#   bash scripts/verify-trigger-order.sh
#
# Covers the trigger-order prompt family:
#  (a) the null-targets trigger-order payload the proxy now sends produces
#      LABELLED, ENABLED ability options,
#  (b) a click dispatches `select_target` with that ability's id,
#  (c) the ordering prompt is not mis-framed as an "N targets" selection,
#  (d) other target prompts (fetchland/Surveil grids, plain targets) are unchanged.
#
# Same pattern as verify-prompt-lifecycle.sh: the REAL store, the REAL message
# handler and the REAL popup components are bundled with browser stubs.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-trigger-order-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-trigger-order.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-trigger-order-entry.tsx --file /tmp/xmage-trigger-order.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-trigger-order.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-trigger-order.mjs
