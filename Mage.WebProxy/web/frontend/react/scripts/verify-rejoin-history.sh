#!/usr/bin/env bash
# Bundle + run the rejoin/reconnect history-backfill behavioural checks under Node.
#   bash scripts/verify-rejoin-history.sh
#
# Same bundling approach as verify-prompt-lifecycle.sh: the REAL store and the REAL
# message handler run under Node against browser stubs, so no browser is needed.
set -euo pipefail
cd "$(dirname "$0")/.."
HARNESS_DIR="$(pwd)"
cat > /tmp/xmage-rejoin-verify-entry.ts <<EOF
import '/tmp/xmage-rejoin-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '$HARNESS_DIR/scripts/verify-rejoin-history.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-rejoin-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-rejoin-verify-entry.ts --file /tmp/xmage-rejoin-verify.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-rejoin-verify.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-rejoin-verify.mjs
