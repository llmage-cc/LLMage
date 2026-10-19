#!/usr/bin/env bash
# Bundle + run the grey art fallback checks under Node.
#   bash scripts/verify-art-fallback.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."
# The entry file is written to /tmp, so the import must be absolute — derive it
# from this script's own location instead of hardcoding a machine path.
cat > /tmp/xmage-verify-entry.ts <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '$HERE/verify-art-fallback.ts'
EOF
cp "$HERE/verify-stubs.mjs" /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-verify-entry.ts --file /tmp/xmage-art-fallback-verify.mjs --format esm --platform node >/dev/null
node /tmp/xmage-art-fallback-verify.mjs
