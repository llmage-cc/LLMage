#!/usr/bin/env bash
# Bundle + run the "suggested creature types" checks under Node (no browser).
#
#   bash scripts/verify-choice-suggested.sh
#
# Covers :
#  (a) the wire `order` the proxy forwards (engine Choice.getSortData: me = -100,
#      opponent = -10) lands on the prompt verbatim and is never invented,
#  (b) the REAL ChooseChoicePrompt renders a single "Suggested" section first,
#      priority-ascending/stable, rest in wire order; no heading when nothing is
#      ranked (alt-cost 0..n ordering is not relevance),
#  (c) the REAL splitChoiceOptions helper filters BOTH groups in place.
#
# Same pattern as verify-provider-dropdown.sh: the REAL store, the REAL message
# handler and the REAL prompt component are bundled with browser stubs and
# rendered through react-dom/server.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-choice-suggested-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-choice-suggested.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-choice-suggested-entry.tsx --file /tmp/xmage-choice-suggested.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-choice-suggested.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
XMAGE_VERIFY_DIR="$HERE/scripts" node /tmp/xmage-choice-suggested.mjs
