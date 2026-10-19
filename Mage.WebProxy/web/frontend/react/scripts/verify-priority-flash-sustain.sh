#!/usr/bin/env bash
# Bundle + run the priority-flash SUSTAIN regression check under Node (no browser).
#
#   bash scripts/verify-priority-flash-sustain.sh
#
# "Add a 'sustain' option to the screen edges flash for priority
# so you can set an intensity for the flash to hold at." With sustain on, a fresh
# prompt arrival raises a HOLD (edge glow at the set intensity) instead of the
# 500ms pulse; the hold is taken down by onPromptClearedForPriorityDing (the same
# five prompt-lifecycle sites that reset the ding's dedupe memory — game end and
# new game included) and never starts, or survives, on a hidden page. See
# scripts/verify-priority-flash-sustain.ts for the encoded cases.
#
# Same pattern as verify-pass-priority-window.sh: the REAL store, the REAL
# message handler and the REAL ScreenFlash predicates are bundled with browser
# stubs.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-pfs-entry.ts <<EOF
import '/tmp/xmage-pfs-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-priority-flash-sustain.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-pfs-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-pfs-entry.ts --file /tmp/xmage-pfs-verify.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-pfs-verify.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-pfs-verify.mjs
