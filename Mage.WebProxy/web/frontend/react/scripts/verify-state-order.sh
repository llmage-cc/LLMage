#!/usr/bin/env bash
# Bundle + run the out-of-order / stale game-state frame checks under Node.
#   bash scripts/verify-state-order.sh
#
# Pins the CURRENT behaviour of the state-frame order path
# (websocket.ts onStateVersionReceived + messageHandlers game_update/game_delta
# + the store) for the "header briefly shows an older turn/phase"
# symptom. No browser, no server: the REAL store, the REAL message handler and
# the REAL GameWebSocket version hook are bundled with browser stubs.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-state-order-entry.ts <<EOF
import '/tmp/xmage-state-order-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-state-order.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-state-order-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-state-order-entry.ts --file /tmp/xmage-state-order.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-state-order.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-state-order.mjs
