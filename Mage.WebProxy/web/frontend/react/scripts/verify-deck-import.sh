#!/usr/bin/env bash
# Bundle + run the deck-import parser checks (annotated-export format fix,
# 2026-09-19) under Node.
#   bash scripts/verify-deck-import.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."
cat > /tmp/xmage-di-entry.ts <<EOF
import '/tmp/xmage-di-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '$HERE/verify-deck-import.ts'
EOF
cp "$HERE/verify-stubs.mjs" /tmp/xmage-di-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-di-entry.ts --file /tmp/xmage-di-verify.mjs --format esm --platform node >/dev/null
# Neutralise the store's dev-only window export (import.meta.env does not exist under Node).
python3 - <<'PY'
p = '/tmp/xmage-di-verify.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-di-verify.mjs