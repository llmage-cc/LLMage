#!/usr/bin/env bash
# Bundle + run the end-of-game presentation checks under Node.
#   bash scripts/verify-endgame-presentation.sh
#
# Same harness pattern as verify-games-list.sh / verify-replay-llm-history.sh:
# bundle the TS harness with rolldown (no tsx dependency), stub the browser
# APIs, run under Node. Exports XMAGE_REACT_SRC so the harness's source-scan
# (glyph-safety assertions) reads THIS worktree's src/.
#
# SERIALIZE with other agents' harness runs: the harnesses use fixed /tmp
# filenames, so take /tmp/xmage-verify.lock first (see AGENTS harness notes).
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
REPO_ROOT="$(cd "$(dirname "$0")/../../../../.." && pwd)"

export XMAGE_REACT_SRC="$HERE/src"

# Lock: other harness scripts reuse these fixed /tmp names.
LOCK=/tmp/xmage-verify.lock
until mkdir "$LOCK" 2>/dev/null; do
  echo "waiting for /tmp/xmage-verify.lock (another harness running)…" >&2
  sleep 30
done
trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT

cat > /tmp/xmage-endgame-presentation-entry.ts <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${REPO_ROOT}/Mage.WebProxy/web/frontend/react/scripts/verify-endgame-presentation.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-endgame-presentation-entry.ts --file /tmp/xmage-endgame-presentation.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-endgame-presentation.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
s = s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;")
# zustand v5 hands react-dom/server `selector(api.getInitialState())`; React 19.2's
# useSyncExternalStore takes THAT snapshot for renderToStaticMarkup, so an SSR
# render reads the CREATION-TIME state (gameEnd:null) and every dialog leg would
# see hasWon=false. The browser bundle is CSR-only and never takes this path.
ssr = "() => selector(api.getInitialState())"
assert ssr in s, 'zustand ssr-snapshot call not found'
s = s.replace(ssr, "() => selector(api.getState())")
open(p, 'w').write(s)
PY
node /tmp/xmage-endgame-presentation.mjs
