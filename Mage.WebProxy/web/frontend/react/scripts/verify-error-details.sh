#!/usr/bin/env bash
# Bundle + run the error-display (copy + collapsible details/stack) checks
# under Node.
#   bash scripts/verify-error-details.sh
#
# ErrorToast auto-hid at 5 s and showed the message only. It now
# offers Copy-to-clipboard + a collapsible details/stack section (the proxy's
# error frames may carry `details`), and the store holds the auto-hide while the
# cursor is over the toast / the details are expanded. This harness drives the
# REAL store + message handler + ErrorToast.
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

cat > /tmp/xmage-error-details-entry.ts <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${REPO_ROOT}/Mage.WebProxy/web/frontend/react/scripts/verify-error-details.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-error-details-entry.ts --file /tmp/xmage-error-details.mjs --format esm --platform node >/dev/null
python3 - <<'PY'
p = '/tmp/xmage-error-details.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
s = s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;")
ssr = "() => selector(api.getInitialState())"
assert ssr in s, 'zustand ssr-snapshot call not found'
s = s.replace(ssr, "() => selector(api.getState())")
open(p, 'w').write(s)
PY
node /tmp/xmage-error-details.mjs
