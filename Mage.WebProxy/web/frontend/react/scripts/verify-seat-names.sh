#!/usr/bin/env bash
# Bundle + run the seat-name checks under Node (no browser).
#
#   bash scripts/verify-seat-names.sh
#
# The seat
# dialogs gained an optional Name field. The checks drive the REAL store slices
# (lobbySlice.startGame / startTournament build the create_table /
# create_tournament frames), the REAL validator (utils/seatNames.ts) and the
# REAL seat row (PlayerSlotRow through react-dom/server):
#   (a) no name typed -> playerConfigs entries carry NO `name` key (the proxy
#       then derives its default — username for the You seat, AI-N for an AI
#       seat), so the previous wire frame is preserved byte-for-byte,
#   (b) a typed name rides the wire (trimmed) for the You seat and AI/LLM seats,
#   (c) validation is fail-fast: over-length / illegal-character names block the
#       send and surface the reason in the dialog error line — never truncated,
#   (d) the tournament payload behaves identically,
#   (e) the row renders the Name field for the You + AI/LLM seats (placeholder =
#       the derived default) and NOT for an open HUMAN opponent seat (the
#       documented scope decision: that seat's name arrives with its joiner),
#   (f) the lobby grid still recognizes a RENAMED own seat as "seated" (no bogus
#       Join offered on the creator's own table),
#   (g) the llm_setup_warning seat match follows the typed name (the proxy names
#       the warned seat with `pc.name` when the seat sent one).
#
# Same pattern as verify-thinking-shape-warning.sh: the REAL modules are bundled
# with browser stubs and rendered through react-dom/server.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"

cat > /tmp/xmage-seat-names-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-seat-names.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-seat-names-entry.tsx --file /tmp/xmage-seat-names.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-seat-names.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-seat-names.mjs
