#!/usr/bin/env bash
# Behavioural checks for the three lobby/proxy defects of the 2026-09-14 batch:
#   (A) hidden-tab throttling made the owner's Start Match button appear 40-60 s late
#   (C) stripServerHtml ate <br> with no separator (word gluing)
# ((B) duplicate start_game idempotency is Java — see Mage.WebProxy/scripts/verify-lobby-proxy-units.sh)
#
# Run with:  bash scripts/verify-lobby-defects.sh
#
# Drives the REAL store through the REAL message handler and the REAL text util under
# Node with browser stubs (same technique as verify-prompt-lifecycle.sh), so the
# assertions cover shipped code, not a re-implementation.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."
cat > /tmp/xmage-lobby-entry.ts <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '$HERE/verify-lobby-defects.ts'
EOF
cp "$HERE/verify-stubs.mjs" /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-lobby-entry.ts --file /tmp/xmage-lobby-verify.mjs --format esm --platform node >/dev/null
python3 - <<'PY'
p = '/tmp/xmage-lobby-verify.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-lobby-verify.mjs
