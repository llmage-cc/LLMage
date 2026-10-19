#!/usr/bin/env bash
# Bundle + run the tables-grid row-model checks under Node.
#   bash scripts/verify-tables-grid.sh
#
# Same harness pattern as verify-replay-browser.sh: bundle the TS harness with
# rolldown (no tsx dependency), stub the browser APIs, run under Node. Uses the
# lock protocol (/tmp/xmage-verify.lock) so parallel agents don't collide on the
# fixed /tmp filenames.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO_ROOT="$(cd "$(dirname "$0")/../../../../.." && pwd)"

# Serialize harness runs — the bundler writes fixed /tmp filenames shared by
# every verify-* script variant.
LOCK=/tmp/xmage-verify.lock
while ! mkdir "$LOCK" 2>/dev/null; do sleep 5; done
trap 'rmdir "$LOCK" 2>/dev/null' EXIT

cat > /tmp/xmage-tables-grid-verify-entry.ts <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${REPO_ROOT}/Mage.WebProxy/web/frontend/react/scripts/verify-tables-grid.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-tables-grid-verify-entry.ts --file /tmp/xmage-tables-grid-verify.mjs --format esm --platform node >/dev/null
node /tmp/xmage-tables-grid-verify.mjs