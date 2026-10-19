#!/usr/bin/env bash
# Bundle + run the game-end duration stats checks under Node.
#   bash scripts/verify-gameend-durations.sh
#
# Same harness pattern as verify-games-list.sh: bundle the TS harness with
# rolldown (no tsx dependency), stub the browser APIs, run under Node.
#
# SERIALIZE with other agents' harness runs: the harnesses use fixed /tmp
# filenames, so take /tmp/xmage-verify.lock first (see AGENTS harness notes).
set -euo pipefail
cd "$(dirname "$0")/.."
REPO_ROOT="$(cd "$(dirname "$0")/../../../../.." && pwd)"

# Lock: other harness scripts reuse these fixed /tmp names.
LOCK=/tmp/xmage-verify.lock
until mkdir "$LOCK" 2>/dev/null; do
  echo "waiting for /tmp/xmage-verify.lock (another harness running)…" >&2
  sleep 30
done
trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT

cat > /tmp/xmage-gameend-durations-entry.ts <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${REPO_ROOT}/Mage.WebProxy/web/frontend/react/scripts/verify-gameend-durations.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-gameend-durations-entry.ts --file /tmp/xmage-gameend-durations.mjs --format esm --platform node >/dev/null
node /tmp/xmage-gameend-durations.mjs