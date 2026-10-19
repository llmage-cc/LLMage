#!/usr/bin/env bash
# Bundle + run the combat UNDECLARE regression check under Node (no browser).
#
#   bash scripts/verify-undeclare.sh
#
# "undeclaring blockers is a huge pain and really doesn't work correctly."
# Clicking an already-declared creature must send the bare-UUID play_card
# answer the engine's declare loops interpret as "take it back"
# (HumanPlayer.selectAttackers → removeAttackerIfPossible, selectBlockers →
# combat.removeBlocker). Same pattern as verify-not-target-arrows.sh: the REAL
# store and REAL message handler are bundled with browser stubs.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-undeclare-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-undeclare.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-undeclare-entry.tsx --file /tmp/xmage-undeclare.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-undeclare.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
if old in s:
    open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-undeclare.mjs
