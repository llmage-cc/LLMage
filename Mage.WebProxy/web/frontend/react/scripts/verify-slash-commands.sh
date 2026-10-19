#!/usr/bin/env bash
# Bundle + run the slash-command harness under Node.
#   bash scripts/verify-slash-commands.sh
#
# Same harness pattern as verify-lobby-chat-users.sh: bundle the TS harness with
# rolldown (no tsx dependency), stub the browser APIs (verify-stubs.mjs),
# neutralise the store's dev-only window export, run under Node.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO_ROOT="$(cd "$(dirname "$0")/../../../../.." && pwd)"
export XMAGE_REPO_ROOT="$REPO_ROOT"
cat > /tmp/xmage-slash-commands-verify-entry.ts <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${REPO_ROOT}/Mage.WebProxy/web/frontend/react/scripts/verify-slash-commands.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-slash-commands-verify-entry.ts --file /tmp/xmage-slash-commands-verify.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-slash-commands-verify.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-slash-commands-verify.mjs
