#!/usr/bin/env bash
# Bundle + run the dead-socket lobby surfacing checks under Node.
#
#   bash scripts/verify-lobby-dead-socket.sh
#
# Covers dead-socket lobby surfacing only — reconnect
# policy is owned by the auth-reconnect checks asserted by
# verify-auth-reconnect.sh):
#  (a) isSocketDown truth table (down iff !connected, 'reconnecting' or
#      'connection_lost'; '' and 'resyncing' are healthy).
#  (b) mid-lobby transport loss flips connStatus to 'reconnecting' through the
#      App-parity onStatusChange handler and selectSocketDown to true — the
#      state that used to leave the lobby looking alive while every send was
#      silently dropped.
#  (c) the lobby slice guards (startGame/joinTable → setupError, startMatch →
#      error toast) surface instead of dropping the send.
#  (d) the next `connected` frame clears the down state and a valid
#      create_table flows again.
#  (e) the proxy-side `disconnected` frame ('connection_lost') is down too and
#      is cleared by a later `connected` frame.
#  (f) the banner text contract (names the drop + the automatic reconnect).
#
# Same pattern as verify-auth-reconnect.sh: the REAL store slices are bundled
# with browser stubs; a fake WebSocket drives the real GameWebSocket manager.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-lobbydeadsock-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
import '${HERE}/scripts/verify-lobby-dead-socket.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-lobbydeadsock-entry.tsx --file /tmp/xmage-lobbydeadsock.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-lobbydeadsock.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-lobbydeadsock.mjs