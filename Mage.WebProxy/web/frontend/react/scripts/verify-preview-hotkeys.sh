#!/usr/bin/env bash
# Bundle + run the card-preview Alt+E / Alt+S checks under Node.
#   bash scripts/verify-preview-hotkeys.sh
#
# CardPreview gained Alt+E (enlarge) and Alt+S (alternate source
# — the card's other face / copy source, the desktop's EnlargeMode ALTERNATE).
# Before this, `grep -rn "altKey" src/` was 0 hits. This harness exercises the
# real pure helpers and renders the REAL CardPreview through react-dom/server.
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

cat > /tmp/xmage-preview-hotkeys-entry.ts <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${REPO_ROOT}/Mage.WebProxy/web/frontend/react/scripts/verify-preview-hotkeys.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-preview-hotkeys-entry.ts --file /tmp/xmage-preview-hotkeys.mjs --format esm --platform node >/dev/null
python3 - <<'PY'
p = '/tmp/xmage-preview-hotkeys.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
s = s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;")
ssr = "() => selector(api.getInitialState())"
assert ssr in s, 'zustand ssr-snapshot call not found'
s = s.replace(ssr, "() => selector(api.getState())")
open(p, 'w').write(s)
PY
node /tmp/xmage-preview-hotkeys.mjs
