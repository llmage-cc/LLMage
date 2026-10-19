#!/usr/bin/env bash
# Bundle + run the board-modes checks.
#
#   bash scripts/verify-board-modes.sh
#
# Bundles the REAL modules (utils/boardModes, store, GameLayout, SettingsDialog)
# with browser stubs (scripts/verify-stubs.mjs) and renders via react-dom/server.
# The handler wiring SSR cannot carry is asserted by source greps at the end.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"

cat > /tmp/xmage-board-modes-entry.tsx <<EOF
import '/tmp/xmage-board-modes-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-board-modes.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-board-modes-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-board-modes-entry.tsx --file /tmp/xmage-board-modes.mjs --format esm --platform node >/dev/null
# The dev-only window export in the store references import.meta.env, which does
# not exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-board-modes.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
# The harness resolves the react root from XMAGE_VERIFY_DIR (the bundled module
# lives in /tmp, so its import.meta.url fallback resolves sources from / — the
# established runner convention: export the scripts dir, like verify-prompt-text).
export XMAGE_VERIFY_DIR="$HERE/scripts"
node /tmp/xmage-board-modes.mjs