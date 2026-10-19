#!/usr/bin/env bash
# Bundle + run the prompt-lifecycle behavioural checks under Node.
#   bash scripts/verify-prompt-lifecycle.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."
# The entry file is written to /tmp, so the import must be absolute — derive it
# from this script's own location instead of hardcoding a machine path.
cat > /tmp/xmage-verify-entry.ts <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '$HERE/verify-prompt-lifecycle.ts'
EOF
cp "$HERE/verify-stubs.mjs" /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-verify-entry.ts --file /tmp/xmage-prompt-verify.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-prompt-verify.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-prompt-verify.mjs
