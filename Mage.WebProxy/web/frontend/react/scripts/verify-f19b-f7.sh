#!/usr/bin/env bash
# Bundle + run the trigger-window and skip behavioural checks under Node.
#   bash scripts/verify-f19b-f7.sh
set -euo pipefail
cd "$(dirname "$0")/.."
cat > /tmp/xmage-f19b-entry.ts <<EOF
import '/tmp/xmage-f19b-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '$PWD/scripts/verify-f19b-f7.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-f19b-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-f19b-entry.ts --file /tmp/xmage-f19b-verify.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-f19b-verify.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-f19b-verify.mjs
