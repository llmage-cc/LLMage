#!/usr/bin/env bash
# Bundle + run the connect-target-toggle checks under Node (no browser).
#
#   bash scripts/verify-connect-target-toggle.sh
#
# Covers the deployment toggle that hides the connect form's Server/Port fields
# (-Dwebproxy.hideServerTarget=true + -Dwebproxy.pinnedXmageTarget):
#  (a) GET /config decides whether the fields render,
#  (b) the connect message omits server/port only when they are hidden,
#  (c) an unusable /config fails fast instead of guessing,
#  (d) persisted creds never carry secrets and survive a hidden-target reconnect.
#
# Same pattern as verify-trigger-order.sh: the REAL component, the REAL payload
# builder and the REAL config loader are bundled with browser stubs.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-connect-toggle-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-connect-target-toggle.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-connect-toggle-entry.tsx --file /tmp/xmage-connect-toggle.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-connect-toggle.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-connect-toggle.mjs
