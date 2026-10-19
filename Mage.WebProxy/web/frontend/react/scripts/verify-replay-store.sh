#!/usr/bin/env bash
# Bundle + run the replay-slice behavioural checks under Node.
#   bash scripts/verify-replay-store.sh
#
# Same harness pattern as verify-prompt-lifecycle.sh: bundle the TS harness with
# rolldown (no tsx dependency), stub the browser APIs, neutralise the store's
# dev-only window export, run under Node.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO_ROOT="$(cd "$(dirname "$0")/../../../../.." && pwd)"
cat > /tmp/xmage-replay-verify-entry.ts <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${REPO_ROOT}/Mage.WebProxy/web/frontend/react/scripts/verify-replay-store.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-replay-verify-entry.ts --file /tmp/xmage-replay-verify.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-replay-verify.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-replay-verify.mjs
