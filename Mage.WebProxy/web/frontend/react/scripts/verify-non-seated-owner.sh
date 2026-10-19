#!/usr/bin/env bash
# Bundle + run the candidate-2 (non-seated owner Start Match) checks under Node.
#   bash scripts/verify-non-seated-owner.sh
#
# Drives the REAL store + message handler + grid component (react-dom/server) and
# the REAL row model, so the assertions cover shipped code, not a re-implementation.
# Same bundling pattern as verify-tables-grid.sh / verify-lobby-defects.sh: bundle
# the TSX harness with rolldown (no tsx dependency), stub the browser APIs, run
# under Node. Uses the lock protocol (/tmp/xmage-verify.lock) so parallel agents
# don't collide on the fixed /tmp filenames.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO_ROOT="$(cd "$(dirname "$0")/../../../../.." && pwd)"

LOCK=/tmp/xmage-verify.lock
while ! mkdir "$LOCK" 2>/dev/null; do sleep 5; done
trap 'rmdir "$LOCK" 2>/dev/null' EXIT

cat > /tmp/xmage-non-seated-owner-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${REPO_ROOT}/Mage.WebProxy/web/frontend/react/scripts/verify-non-seated-owner.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-non-seated-owner-entry.tsx --file /tmp/xmage-non-seated-owner-verify.mjs --format esm --platform node >/dev/null
# The store module carries a dev-only export line the bundler keeps verbatim
# (`import.meta.env.DEV` is undefined under plain Node) — rewrite it to the
# global flag the entry set, exactly like verify-lobby-defects.sh does.
python3 - <<'PY'
p = '/tmp/xmage-non-seated-owner-verify.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-non-seated-owner-verify.mjs
