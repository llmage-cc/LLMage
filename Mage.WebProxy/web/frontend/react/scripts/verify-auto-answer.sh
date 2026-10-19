#!/usr/bin/env bash
# Bundle + run the remembered-answer (auto-answer) behavioural checks under Node.
#   bash scripts/verify-auto-answer.sh
#
# WRITTEN but NOT RUN by the compile-only unit that authored it;
# first run belongs to the parent merge gate. Same bundling recipe as
# verify-mulligan-hide.sh (rolldown + stubs + the import.meta.env neutraliser).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."
# The entry file is written to /tmp, so the import must be absolute — derive it
# from this script's own location instead of hardcoding a machine path.
cat > /tmp/xmage-auto-answer-entry.ts <<EOF
import '/tmp/xmage-auto-answer-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '$HERE/verify-auto-answer.ts'
EOF
cp "$HERE/verify-stubs.mjs" /tmp/xmage-auto-answer-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-auto-answer-entry.ts --file /tmp/xmage-auto-answer-verify.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-auto-answer-verify.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-auto-answer-verify.mjs
