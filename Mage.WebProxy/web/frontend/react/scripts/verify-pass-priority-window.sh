#!/usr/bin/env bash
# Bundle + run the pass-priority window regression check under Node (no browser).
#
#   bash scripts/verify-pass-priority-window.sh
#
# "Can you remove the popup for pass priority from the webui?"
# The centered DecisionPopup window no longer opens for the pass-priority wait —
# a `select` prompt whose rail button list would be empty (no choices, or every
# choice is a card clickable on the board). The ding + screen flash announce the
# handoff; the rail keeps the message line + Pass Priority. Combat selects, a
# special button (All attack), off-board plays and every other prompt type keep
# the window. See scripts/verify-pass-priority-window.tsx for the encoded cases.
#
# Same pattern as verify-trigger-order.sh: the REAL store, the REAL message
# handler and the REAL popup components are bundled with browser stubs.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-ppw-entry.tsx <<EOF
import '/tmp/xmage-ppw-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-pass-priority-window.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-ppw-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-ppw-entry.tsx --file /tmp/xmage-ppw-verify.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-ppw-verify.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-ppw-verify.mjs