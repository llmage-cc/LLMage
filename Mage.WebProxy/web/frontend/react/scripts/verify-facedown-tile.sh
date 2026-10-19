#!/usr/bin/env bash
# Bundle + run the face-down tile branch checks under Node.
#   bash scripts/verify-facedown-tile.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."
cat > /tmp/xmage-verify-entry-fd.ts <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env = { DEV: false, MODE: 'production', PROD: true }
import '$HERE/verify-facedown-tile.ts'
EOF
cp "$HERE/verify-stubs.mjs" /tmp/xmage-verify-stubs.mjs
# rolldown keeps `import.meta.env` as-is (no vite define here): rolldown has a
# `--define import.meta.env.DEV:false` runtime shape? Simplest shim: replace the
# expression in the BUNDLED output before node runs it.

./node_modules/.bin/rolldown /tmp/xmage-verify-entry-fd.ts --file /tmp/xmage-facedown-verify.mjs --format esm --platform node >/dev/null
# rolldown does not run vite's define step, so the bundle keeps the raw
# `import.meta.env.DEV` read that vite would have inlined — patch it to a
# plain false before node loads the module.
sed -i 's/import\.meta\.env\.DEV/false/g; s/import\.meta\.env/({DEV:false,MODE:"production",PROD:true})/g' /tmp/xmage-facedown-verify.mjs
node /tmp/xmage-facedown-verify.mjs
