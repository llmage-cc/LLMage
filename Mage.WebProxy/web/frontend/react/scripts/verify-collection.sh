#!/usr/bin/env bash
# Bundle + run the deck-editor Collection-tab behavioural checks under Node (N1-N7).
#   bash scripts/verify-collection.sh
#
# Same approach as scripts/verify-prompt-lifecycle.sh: bundle the REAL store with
# browser stubs, then drive it through the REAL message handler — no browser needed.
# The parts that genuinely need a browser (lazy-image loading in a layout engine,
# localStorage round-trips in a real page) are listed in the script's footer.
set -euo pipefail
cd "$(dirname "$0")/.."

HERE="$(pwd)"
cat > /tmp/xmage-collection-entry.ts <<EOF
import '/tmp/xmage-collection-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-collection.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-collection-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-collection-entry.ts --file /tmp/xmage-collection-verify.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-collection-verify.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
export XMAGE_REACT_SRC="$HERE/src"
export XMAGE_REACT_ROOT="$HERE"
node /tmp/xmage-collection-verify.mjs