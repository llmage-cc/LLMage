#!/usr/bin/env bash
# Bundle + run the battlefield-split INDEPENDENT-scaling behavioural checks.
#   bash scripts/verify-battlefield-split-scale.sh
#
# Bundles the REAL src/utils/cardFit with browser stubs so the per-pane fit
# maths is asserted against shipped code, not a copy. The wiring claims read
# App.tsx / GameView.tsx / styles/layout.css from disk directly — no browser.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."
cat > /tmp/xmage-verify-split-scale-entry.ts <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
;(globalThis as any).__XMAGE_VERIFY_DIR = '$HERE'
import '$HERE/verify-battlefield-split-scale.ts'
EOF
cp "$HERE/verify-stubs.mjs" /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-verify-split-scale-entry.ts --file /tmp/xmage-split-scale-verify.mjs --format esm --platform node >/dev/null
XMAGE_VERIFY_DIR="$HERE" node /tmp/xmage-split-scale-verify.mjs
