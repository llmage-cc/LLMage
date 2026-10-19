#!/usr/bin/env bash
# Bundle + run the 2026-10-04 lobby-polish checks under Node (no browser).
#
#   bash scripts/verify-lobby-polish.sh
#
# Covers the LOW/polish items fixed frontend-only
#: true zero-state tables gate,
# lobby-only MotD strip, deck-upload confirmation, decision-popup ↔ phase-bar
# clearance, LLM-rail empty copy, users-panel self marking, hand-card playable
# markup. See verify-lobby-polish.tsx for the full assertion list.
#
# Same pattern as verify-seat-names.sh: the REAL modules are bundled with
# browser stubs and rendered through react-dom/server.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"

LOCK=/tmp/xmage-verify.lock
while ! mkdir "$LOCK" 2>/dev/null; do sleep 5; done
trap 'rmdir "$LOCK" 2>/dev/null' EXIT

cat > /tmp/xmage-lobby-polish-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-lobby-polish.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-lobby-polish-entry.tsx --file /tmp/xmage-lobby-polish.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-lobby-polish.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-lobby-polish.mjs
