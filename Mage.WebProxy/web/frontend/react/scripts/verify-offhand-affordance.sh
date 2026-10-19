#!/usr/bin/env bash
# Bundle + run the off-hand play affordance checks under Node (no browser).
#
#   bash scripts/verify-offhand-affordance.sh
#
# Covers the interaction rule (interface
# selection is PRIMARY, popups are FALLBACK):
#  (a) the proxy wire contract: an off-hand play arrives as a card-based choice
#      ('activate' / cast|land) whose id IS the card's own UUID — no proxy change;
#  (b) board tiles: my graveyard/exile tiles carry card-playable exactly when
#      playableCardIds holds the card id; the opponent's tiles never light up;
#  (c) wiring: the tiles click through GameView.handleCardClick (choice lookup →
#      select_choice) like a hand card, and CardThumb renders the highlight;
#  (d) zone window: ZonePopup marks the playable card popup-card-playable with
#      role="button"; opponents' windows and sideboard stay display-only; the
#      affordance follows the LIVE prompt;
#  (e) rail + window gate: the clickable graveyard flashback choice needs no
#      rail button and no DecisionPopup window; a command-zone activate (id in
#      no rendered zone) keeps both — popups remain fallback only;
#  (f) the answer path sends select_choice with the choice index — exactly what
#      the rail button sends.
#
# Same pattern as verify-pass-priority-window.sh / verify-emblem-badge-clickable.sh:
# the REAL store, the REAL message handler and the REAL components are bundled
# with browser stubs.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-offhand-entry.tsx <<EOF
import '/tmp/xmage-offhand-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-offhand-affordance.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-offhand-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-offhand-entry.tsx --file /tmp/xmage-offhand-verify.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-offhand-verify.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-offhand-verify.mjs