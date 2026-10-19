#!/usr/bin/env bash
# Bundle + run the prompt-text behavioural checks under Node.
#   bash scripts/verify-prompt-text.sh
#
# Bundles the REAL src modules (utils/textFormat) with browser stubs so the
# separator/entity behaviour is asserted against shipped code, not a copy.
# CSS/source-structure claims read the files on disk directly — no browser.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."
# The entry file is written to /tmp, so both the import and the harness's own
# notion of "where am I" must be absolute — derive them from this script's
# location instead of hardcoding a machine path.
cat > /tmp/xmage-verify-text-entry.ts <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
;(globalThis as any).__XMAGE_VERIFY_DIR = '$HERE'
import '$HERE/verify-prompt-text.ts'
EOF
cp "$HERE/verify-stubs.mjs" /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-verify-text-entry.ts --file /tmp/xmage-text-verify.mjs --format esm --platform node >/dev/null
XMAGE_VERIFY_DIR="$HERE" node /tmp/xmage-text-verify.mjs
