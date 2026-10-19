#!/usr/bin/env bash
# Bundle + run the P2 (game-end dialog SAVE LOG) checks under Node.
#   bash scripts/verify-game-log.sh
#
# The game-end dialog gained Copy Log (clipboard) and Save Log
# (.txt download), both built from the SAME post-loss-filtered text the Game Log
# tab renders (utils/gameLog.buildGameLogText). The desktop saves the log on game
# end (GameEndDialog.java:59-121 saveGameLog). This harness exercises the real
# pure formatter and renders the REAL GameEndDialog through react-dom/server.
#
# Same bundling pattern as verify-endgame-presentation.sh (rolldown, browser
# stubs, Node). Exports XMAGE_REACT_SRC so the source-scan legs read THIS
# worktree's src/.
#
# SERIALIZE with other agents' harness runs: take /tmp/xmage-verify.lock first.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
REPO_ROOT="$(cd "$(dirname "$0")/../../../../.." && pwd)"

export XMAGE_REACT_SRC="$HERE/src"

LOCK=/tmp/xmage-verify.lock
until mkdir "$LOCK" 2>/dev/null; do
  echo "waiting for /tmp/xmage-verify.lock (another harness running)…" >&2
  sleep 30
done
trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT

cat > /tmp/xmage-game-log-entry.ts <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${REPO_ROOT}/Mage.WebProxy/web/frontend/react/scripts/verify-game-log.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-game-log-entry.ts --file /tmp/xmage-game-log.mjs --format esm --platform node >/dev/null
python3 - <<'PY'
p = '/tmp/xmage-game-log.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
s = s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;")
# zustand v5 hands react-dom/server `selector(api.getInitialState())`; React 19's
# useSyncExternalStore takes THAT snapshot for renderToStaticMarkup, so every
# dialog leg would read creation-time state. The browser bundle is CSR-only and
# never takes this path.
ssr = "() => selector(api.getInitialState())"
assert ssr in s, 'zustand ssr-snapshot call not found'
s = s.replace(ssr, "() => selector(api.getState())")
open(p, 'w').write(s)
PY
node /tmp/xmage-game-log.mjs
