#!/usr/bin/env bash
# Bundle + run the whisper-direction chat-label harness under Node (no browser).
#
#   bash scripts/verify-chat-labels.sh
#
# Covers whisper-direction labels on both web chat surfaces:
#  (a) the desktop wording ("Whisper from " / "Whisper to ") and the server's
#      per-direction `from` population are read from the REAL Java sources
#      (repo root exported to the harness), so the parity claim is grounded,
#  (b) chatFromLabel's pure mapping (talk/unknown/absent → no invented prefix),
#  (c) the REAL message handler routes one wire `event` frame into both lists
#      without duplicating it into the game log,
#  (d) the REAL LobbyChat and RightRail components rendered through
#      react-dom/server carry the labels.
#
# Same pattern as verify-provider-dropdown.sh / verify-lobby-chat-users.sh:
# rolldown bundle + the shared browser stubs (verify-stubs.mjs) + the store's
# dev-only window export neutralised for Node.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO_ROOT="$(cd "$(dirname "$0")/../../../../.." && pwd)"
export XMAGE_REPO_ROOT="$REPO_ROOT"
HERE="$(pwd)"
cat > /tmp/xmage-chat-labels-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-chat-labels.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-chat-labels-entry.tsx --file /tmp/xmage-chat-labels.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-chat-labels.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-chat-labels.mjs
