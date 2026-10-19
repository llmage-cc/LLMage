#!/usr/bin/env bash
# "Every built-in deck parses" check over the GENERATED web/frontend/decks/builtin.json

#   bash scripts/verify-builtin-decks.sh [path-to-builtin.json]
# Default path: ../decks/builtin.json (relative to the react dir).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."

cat > /tmp/xmage-bd-entry.ts <<EOF
import '$HERE/verify-builtin-decks.ts'
EOF

./node_modules/.bin/rolldown /tmp/xmage-bd-entry.ts --file /tmp/xmage-builtin-verify.mjs --format esm --platform node >/dev/null
node /tmp/xmage-builtin-verify.mjs "$@"