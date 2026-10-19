#!/usr/bin/env bash
# Bundle + run the auto-tap behavioural checks under Node.
#
#   bash scripts/verify-autotap-full-cost.sh
#
# Bundles the REAL store (rolldown) with browser stubs (scripts/verify-stubs.mjs) and drives it
# through the real message handler — no browser, no server, seconds to run.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"

OUT="/tmp/xmage-autotap-verify.mjs"
ENTRY="/tmp/xmage-autotap-entry.ts"
CFG="/tmp/xmage-autotap-rolldown.config.mjs"

# The entry imports the harness by ABSOLUTE path (rolldown resolves relative imports from /tmp
# otherwise), and `@/` is aliased to src/ exactly as vite.config.ts does.
cat > "$ENTRY" <<EOF
import '/tmp/xmage-autotap-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-autotap-full-cost.ts'
EOF
cat > "$CFG" <<EOF
export default {
  input: '${ENTRY}',
  resolve: { alias: { '@': '${HERE}/src' } },
  output: { file: '${OUT}', format: 'esm' },
  platform: 'node',
}
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-autotap-stubs.mjs

./node_modules/.bin/rolldown -c "$CFG" >/dev/null

# The store's dev-only window export references import.meta.env, which does not exist under Node —
# neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-autotap-verify.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
if old in s:
    s = s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;")
    open(p, 'w').write(s)
PY

node "$OUT"
