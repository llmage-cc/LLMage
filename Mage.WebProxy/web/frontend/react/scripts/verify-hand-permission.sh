#!/usr/bin/env bash
# Hand-request / permission management gate (initiate half).
#
#   bash scripts/verify-hand-permission.sh
#
# Two layers:
#   1. Behavioural harness (real store, real message handler, real delta
#      applicator, real handWatch module under Node via rolldown):
#        - requestHandPermission / revokeHandPermission / allowHandRequests wire
#          payloads, and the dead-socket fail-fast on request;
#        - watchedHands riding a game_delta (grant appears without a reload);
#        - the handWatch viewer key (select/close/no-op/empty-name refusal) and
#          the revoked-grant condition the component effect uses.
#   2. Wiring greps on the REAL components — SSR markup cannot contain handlers,
#      so the mount point, the menu items and the explain ids are asserted on
#      the source. Every new control must also carry a data-explain id that
#      exists in the registry (the explain-coverage gate re-checks this at
#      bundle level).
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"

cat > /tmp/xmage-hand-permission-entry.tsx <<EOF
import '/tmp/xmage-hand-permission-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-hand-permission.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-hand-permission-stubs.mjs
# The harness renders the REAL WatchedHandsView, which imports its own CSS. Rolldown
# no longer bundles CSS (UNSUPPORTED_FEATURE) and a Node import of a .css file is an
# unknown extension, so externalise stylesheet imports and strip the leftover import
# statements below — the harness asserts markup, not paint.
cat > /tmp/xmage-hand-permission-rolldown.config.mjs <<'CFG'
export default {
  input: '/tmp/xmage-hand-permission-entry.tsx',
  external: [/\.css$/],
  output: { file: '/tmp/xmage-hand-permission.mjs', format: 'esm' },
  platform: 'node',
}
CFG
./node_modules/.bin/rolldown -c /tmp/xmage-hand-permission-rolldown.config.mjs >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle. Also drop
# the externalised CSS imports.
python3 - <<'PY'
import re
p = '/tmp/xmage-hand-permission.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
s = s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;")
s = re.sub(r'^\s*import\s+["\'][^"\']*\.css["\'];?\s*$', '', s, flags=re.M)
open(p, 'w').write(s)
PY
node /tmp/xmage-hand-permission.mjs

# ── wiring on the real components ──
fail=0
fail() { echo "FAIL: $*"; fail=1; }

MENU=src/components/PlayerContextMenu.tsx
VIEW=src/components/WatchedHandsView.tsx
GAME=src/components/GameView.tsx

# The player menu carries the desktop's items, desktop-verbatim wording.
grep -q 'Request permission to see the hand cards' "$MENU" \
  || fail "$MENU does not offer the desktop's 'Request permission to see the hand cards' item"
grep -q 'Request permission to see hand cards (' "$MENU" \
  || fail "$MENU does not offer the WATCHER wording 'Request permission to see hand cards (name)'"
grep -q 'Allow hand requests from other users' "$MENU" \
  || fail "$MENU does not offer the desktop's allow-requests checkbox"
grep -q 'Revoke all permission(s) to see your hand cards' "$MENU" \
  || fail "$MENU does not offer the desktop's revoke item"
grep -q 'Watchers or other players can request your hand cards once per game' "$MENU" \
  || fail "$MENU lost the desktop checkbox tooltip"
# Non-self gating: the request item must never be offered on your own seat
# (the engine refuses a self-request).
grep -q '!player.isMe' "$MENU" \
  || fail "$MENU does not gate the request item on a non-self seat"
# The allow/revoke pair is own-seat only.
grep -q 'player.isMe' "$MENU" \
  || fail "$MENU does not gate the allow/revoke pair on your own seat"
# Store verbs, not raw ws.send.
grep -q 'requestHandPermission(player.playerId!' "$MENU" \
  || fail "$MENU does not call the requestHandPermission store action"
grep -q 'revokeHandPermission()' "$MENU" \
  || fail "$MENU does not call the revokeHandPermission store action"
grep -q 'allowHandRequests(' "$MENU" \
  || fail "$MENU does not call the allowHandRequests store action"
# The granted-hand opener rides the handWatch selector.
grep -q 'selectHand(player.name)' "$MENU" \
  || fail "$MENU does not open the granted hand through the handWatch module"

# Viewer: one mount point, real cards, read-only, switch rail.
grep -q '<WatchedHandsView />' "$GAME" \
  || fail "$GAME does not mount <WatchedHandsView /> (one mount point per game view)"
grep -q 'selectBoardState' "$VIEW" \
  || fail "$VIEW does not read the shared board state (replay frames must render too)"
grep -q 'watchedHands' "$VIEW" \
  || fail "$VIEW does not read gameState.watchedHands"
grep -q 'useFloatWindow' "$VIEW" \
  || fail "$VIEW does not use the shared float-window chrome (drag/resize/persisted size)"
grep -q 'closeHandView' "$VIEW" \
  || fail "$VIEW does not close through the handWatch module"
# READ-ONLY contract: a granted hand must never carry a click handler onto the
# board's card dispatch (the desktop's watched-hand container is inert too).
grep -q 'onClick={() => handleCardClick' "$VIEW" \
  && fail "$VIEW wires clicks to the board dispatch — a granted hand is read-only"

# Every new control resolves an explanation (registry ids present here; the
# explain-coverage gate re-checks bundle-level).
EXPLAIN=src/hints/explain.ts
for id in player-ctx-request-hand player-ctx-view-granted-hand player-ctx-allow-hand \
         player-ctx-revoke-hand hand-watch-switch hand-watch-close; do
  grep -q "'$id':" "$EXPLAIN" \
    || fail "$EXPLAIN is missing the '$id' explanation entry"
  grep -rq "data-explain=\"$id\"" src/components/ \
    || fail "no control carries data-explain=\"$id\""
done

[ "$fail" -eq 0 ] && echo "handler wiring checked on PlayerContextMenu.tsx / WatchedHandsView.tsx / GameView.tsx / explain.ts"
exit $fail
