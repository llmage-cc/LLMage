#!/usr/bin/env bash
# Replay-view settings button + keyboard hand-back gate

#
#   bash scripts/verify-replay-settings.sh
#
# Two layers, same split as verify-emblem-badge-clickable.sh:
#   1. Behavioural harness: the pure key matrix (replayHotkey/isTypingTarget) —
#      the dialog / context-menu / typing hand-back rules — plus SSR renders of
#      the REAL ReplayView showing the ⚙ Settings button in the header in both
#      the loaded and the loading state (real store, real component, no browser).
#   2. Source-wiring greps on the real files (SSR markup cannot carry handlers):
#      the button's store call, the keydown effect's hand-back arguments, the
#      dialog's global mount in App.tsx, and the live header's gear untouched.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"

cat > /tmp/xmage-replay-settings-entry.tsx <<EOF
import '/tmp/xmage-replay-settings-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-replay-settings.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-replay-settings-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-replay-settings-entry.tsx --file /tmp/xmage-replay-settings.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-replay-settings.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
if old in s:
    open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
XMAGE_VERIFY_DIR="$HERE/scripts" node /tmp/xmage-replay-settings.mjs
