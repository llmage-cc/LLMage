#!/usr/bin/env bash
# Bundle + run the Bo3 winsNeeded / sideboard-recovery store checks under Node.
#   bash scripts/verify-sideboard-recovery.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."
cat > /tmp/xmage-sb-entry.ts <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '$HERE/verify-sideboard-recovery.ts'
EOF
cp "$HERE/verify-stubs.mjs" /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-sb-entry.ts --file /tmp/xmage-sb-verify.mjs --format esm --platform node >/dev/null
# Neutralise the store's dev-only window export (import.meta.env does not exist under Node).
python3 - <<'PY'
p = '/tmp/xmage-sb-verify.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-sb-verify.mjs
