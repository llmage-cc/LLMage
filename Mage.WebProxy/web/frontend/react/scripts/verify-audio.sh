#!/usr/bin/env bash
# Bundle + run the event-sound system checks under Node (no browser).
#
#   bash scripts/verify-audio.sh
#
# The web equivalent of the desktop AudioManager. The REAL
# store, REAL message handler and REAL audio manager are bundled with browser
# stubs (scripts/verify-stubs.mjs) — same recipe as verify-priority-flash-sustain.sh
# and verify-undeclare.sh. HTMLAudioElement / AudioContext are recording fakes
# installed by the harness itself, so every assertion runs against the shipped
# trigger code (see scripts/verify-audio.ts for what is pinned).
#
# Autoplay unlock is verified at THIS stub level (the first pointerdown must
# let a sound through); a live browser check of the real autoplay grant is the
# left to the live-browser suite; no live stack is used here.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-audio-entry.ts <<EOF
import '/tmp/xmage-audio-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-audio.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-audio-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-audio-entry.ts --file /tmp/xmage-audio.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-audio.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-audio.mjs
