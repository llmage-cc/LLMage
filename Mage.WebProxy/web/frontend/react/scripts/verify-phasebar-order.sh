#!/usr/bin/env bash
# Bundle + run the phase-bar turn-order checks under Node (no browser).
#
#   bash scripts/verify-phasebar-order.sh
#
# "cleanup and end step are out of order in the phases bar (cleanup comes
# after endstep in the actual game)". Asserts the rendered phase list is in true
# engine order (PhaseStep: ...END_TURN=11, CLEANUP=12) with Cleanup LAST.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-phasebar-order-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-phasebar-order.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-phasebar-order-entry.tsx --file /tmp/xmage-phasebar-order.mjs --format esm --platform node >/dev/null
# The dev-only window export in the store references import.meta.env, which does
# not exist under Node — the same neutralisation verify-trigger-order.sh applies.
python3 - <<'PY'
p = '/tmp/xmage-phasebar-order.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-phasebar-order.mjs
