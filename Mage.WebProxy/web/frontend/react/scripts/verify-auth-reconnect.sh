#!/usr/bin/env bash
# Bundle + run the auth-reconnect-loop checks under Node (no browser).
#
#   bash scripts/verify-auth-reconnect.sh
#
# Covers  fixes 1 + 3 (client half):
#  (a) FIX 1 — the backoff counter is NOT reset on socket open: open→close
#      cycles without a `connected` frame double the delay up to the 30s cap;
#      delivering `connected` (noteConnectAccepted) resets it.
#  (b) FIX 1 — visibility/online recovery (forceReconnect) does NOT reset the
#      counter either (a stuck auth-reject tab must not re-hammer on tab switches).
#  (c) FIX 1 — noteConnectAccepted is wired through the store: a `connected`
#      message through handleMessage resets the counter via the real slices.
#  (d) FIX 3 — onclose with the proxy's 4401 close code schedules NO reconnect;
#      other close codes reconnect as before.
#
# Same pattern as verify-logout.sh: the REAL store slices are bundled with
# browser stubs; a fake WebSocket drives the real GameWebSocket manager.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-authreconnect-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
import '${HERE}/scripts/verify-auth-reconnect.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-authreconnect-entry.tsx --file /tmp/xmage-authreconnect.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-authreconnect.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-authreconnect.mjs