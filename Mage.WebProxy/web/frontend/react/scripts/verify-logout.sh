#!/usr/bin/env bash
# Bundle + run the logout checks under Node (no browser).
#
#   bash scripts/verify-logout.sh
#
# Covers the lobby "Log out" control's store behavior:
#  (a) saved credentials are cleared (breaks the auto-reconnect loop),
#  (b) the socket is torn down but the manager stays in the store,
#  (c) lobby seat state resets,
#  (d) routing falls to 'connect' and a NEW session routes back to 'lobby',
#  (e) double logout is safe.
#
# Same pattern as verify-connect-target-toggle.sh: the REAL store slices are
# bundled with browser stubs and a recording WS stub.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-logout-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
import '${HERE}/scripts/verify-logout.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-logout-entry.tsx --file /tmp/xmage-logout.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-logout.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-logout.mjs