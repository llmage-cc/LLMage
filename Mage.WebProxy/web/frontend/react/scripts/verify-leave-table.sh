#!/usr/bin/env bash
# Bundle + run the lobby leave-table flow checks under Node.
#   bash scripts/verify-leave-table.sh
#
# The waiting panel had NO leave flow (no leave_table request
# anywhere). This harness drives the REAL store (leaveTable/clearSeatState), the
# REAL message handler (table_left -> seat reset) and the REAL LobbyView waiting
# panel, plus source-scan legs on WebProxySession's leave_table verb.
#
# Same bundling pattern as verify-logout.sh / verify-endgame-presentation.sh
# (rolldown, browser stubs, Node). Exports XMAGE_REACT_SRC for the source scans.
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

cat > /tmp/xmage-leave-table-entry.ts <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${REPO_ROOT}/Mage.WebProxy/web/frontend/react/scripts/verify-leave-table.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-leave-table-entry.ts --file /tmp/xmage-leave-table.mjs --format esm --platform node >/dev/null
python3 - <<'PY'
p = '/tmp/xmage-leave-table.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
s = s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;")
ssr = "() => selector(api.getInitialState())"
assert ssr in s, 'zustand ssr-snapshot call not found'
s = s.replace(ssr, "() => selector(api.getState())")
open(p, 'w').write(s)
PY
node /tmp/xmage-leave-table.mjs
