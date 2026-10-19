#!/usr/bin/env bash
# Bundle + run the app-root error-boundary checks under Node (no browser).
#
#   bash scripts/verify-error-boundary.sh
#
# Regression harness for the blank-app residual: an uncaught render error emptied
# #root with NO visible error (the builtin-picker incident). The fix is
# RootErrorBoundary at the app root (main.tsx), logging via console.error and
# rendering a readable error card with a reload affordance.
#
# Covers:
#  (a) the mounting contract — main.tsx wraps <App /> in RootErrorBoundary, and
#      no other component mounts a subtree boundary (root-only, no catch-alls),
#  (b)-(e) the REAL boundary module's contract — getDerivedStateFromError
#      (Error + non-Error), componentDidCatch logging through console.error,
#      the fallback card (message + Reload + role=alert, children gone), and a
#      clean pass-through render.
#
# Same bundling pattern as verify-builtin-picker-tree.sh: the REAL component
# module is bundled with the repo's rolldown and run under Node.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"

cat > /tmp/xmage-error-boundary-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-error-boundary.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-error-boundary-entry.tsx --file /tmp/xmage-error-boundary.mjs --format esm --platform node >/dev/null
node /tmp/xmage-error-boundary.mjs