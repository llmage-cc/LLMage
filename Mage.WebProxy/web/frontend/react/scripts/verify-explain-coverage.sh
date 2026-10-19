#!/usr/bin/env bash
# Explain-coverage gate: every interactive control that can be used must have an explanation.
#
#   bash scripts/verify-explain-coverage.sh
#
# Walks THREE synthetic DOM scenes (a game board, a prompt-heavy board, the lobby) through the
# REAL resolver (`src/hints/explain.ts`, bundled by rolldown exactly as the app bundles it) and
# fails when a visible control has no explanation.
#
# This is a hard gate, not a report: exit code 1 with the list of unexplained controls is how a
# new button that forgot its `data-explain` id is caught before review. The browser harness runs
# the same walk against the live app (window.__xmageHints.report()).
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"

OUT="/tmp/xmage-explain-coverage.mjs"
ENTRY="/tmp/xmage-explain-entry.ts"
CFG="/tmp/xmage-explain-rolldown.config.mjs"

cat > "$ENTRY" <<EOF
import '${HERE}/scripts/verify-explain-coverage.ts'
EOF
cat > "$CFG" <<EOF
export default {
  input: '${ENTRY}',
  resolve: { alias: { '@': '${HERE}/src' } },
  output: { file: '${OUT}', format: 'esm' },
  platform: 'node',
}
EOF

./node_modules/.bin/rolldown -c "$CFG" >/dev/null
node "$OUT"
