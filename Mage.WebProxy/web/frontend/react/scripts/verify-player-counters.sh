#!/usr/bin/env bash
# Bundle + run the dedicated player-counter display checks under Node.
#   bash scripts/verify-player-counters.sh
#
# PlayerBoard showed one generic `name: count` line for every
# counter type; the desktop renders five dedicated labelled fields (poison /
# energy / experience / rad / ticket — PlayerPanelExt.java:553-676). This harness
# drives the real pure splitter (utils/playerCounters) and renders the REAL
# PlayerBoard through react-dom/server.
#
# Same bundling pattern as verify-endgame-presentation.sh (rolldown, browser
# stubs, Node). Exports XMAGE_REACT_SRC for the source-scan legs.
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

cat > /tmp/xmage-player-counters-entry.ts <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${REPO_ROOT}/Mage.WebProxy/web/frontend/react/scripts/verify-player-counters.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-player-counters-entry.ts --file /tmp/xmage-player-counters.mjs --format esm --platform node >/dev/null
python3 - <<'PY'
p = '/tmp/xmage-player-counters.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
if old in s:
    s = s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;")
ssr = "() => selector(api.getInitialState())"
if ssr in s:
    s = s.replace(ssr, "() => selector(api.getState())")
open(p, 'w').write(s)
PY
node /tmp/xmage-player-counters.mjs
