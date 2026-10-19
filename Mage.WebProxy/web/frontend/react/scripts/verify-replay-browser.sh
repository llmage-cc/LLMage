#!/usr/bin/env bash
# Bundle + run the replay-browser row-model checks under Node.
#   bash scripts/verify-replay-browser.sh
#
# Same harness pattern as verify-replay-store.sh: bundle the TS harness with
# rolldown (no tsx dependency), stub the browser APIs, run under Node.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO_ROOT="$(cd "$(dirname "$0")/../../../../.." && pwd)"
cat > /tmp/xmage-replay-browser-verify-entry.ts <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${REPO_ROOT}/Mage.WebProxy/web/frontend/react/scripts/verify-replay-browser.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-replay-browser-verify-entry.ts --file /tmp/xmage-replay-browser-verify.mjs --format esm --platform node >/dev/null
node /tmp/xmage-replay-browser-verify.mjs
