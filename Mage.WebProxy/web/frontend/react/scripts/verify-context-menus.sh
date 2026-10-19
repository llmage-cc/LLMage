#!/usr/bin/env bash
# Shared context-menu gate (shared registry,
# card menu, phase toggle, player menu; hover-preview suppression generalized).
#
#   bash scripts/verify-context-menus.sh
#
# Two layers:
#   1. Behavioural harness (real store under Node): registry replace semantics,
#      phase-stop tracked config (the PhaseStopSettings silent-revert fix),
#      player-menu LLM index resolution contract.
#   2. Wiring greps on the real render sites: every menu goes through the
#      shared useContextMenu registry, the three new menus are rendered in
#      GameView (card menu also in ReplayView), PhaseBar opens the phase menu
#      right-click path, PlayerBoard exposes the header context-menu prop.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-ctx-menus-entry.ts <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-context-menus.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-ctx-menus-entry.ts --file /tmp/xmage-ctx-menus.mjs --format esm --platform node >/dev/null
python3 - <<'PY'
p = '/tmp/xmage-ctx-menus.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-ctx-menus.mjs

# ── wiring greps on the real render sites ──
fail=0
fail() { echo "FAIL: $*"; fail=1; }
GV=src/components/GameView.tsx
for f in "$GV" src/components/ReplayView.tsx; do
  grep -q '<CardContextMenu />' "$f" || fail "$f does not render CardContextMenu"
  grep -q '<TokenArtMenu />' "$f" || fail "$f does not render TokenArtMenu (hoisted single mount)"
done
grep -q '<PhaseContextMenu />' "$GV" || fail "GameView does not render PhaseContextMenu"
grep -q '<PlayerContextMenu />' "$GV" || fail "GameView does not render PlayerContextMenu"
grep -q "useContextMenu('phase')" src/components/PhaseBar.tsx \
  || fail "PhaseBar does not open the phase menu through the shared registry"
grep -q 'onContextMenu={readOnly ? undefined : (e) => phaseMenu.openAt' src/components/PhaseBar.tsx \
  || fail "PhaseBar right-click does not open the phase menu (or replay is not read-only)"
grep -q "useContextMenu('player')" "$GV" \
  || fail "GameView does not open the player menu through the shared registry"
grep -q 'onPlayerContextMenu={openPlayerMenu(player)}' "$GV" \
  || fail "GameView does not wire the player-header context menu into PlayerBoard"
grep -q 'onPlayerContextMenu' src/components/PlayerBoard.tsx \
  || fail "PlayerBoard does not accept/expose onPlayerContextMenu"
grep -q "useContextMenu('trigger-order')" src/components/StackZone.tsx \
  || fail "StackZone trigger-order menu is not on the shared registry"
grep -q "useContextMenu('token-art')" src/components/CardThumb.tsx \
  || fail "CardThumb token menu is not on the shared registry"
grep -q 'phaseStopConfig' src/components/PhaseStopSettings.tsx \
  || fail "PhaseStopSettings does not initialize from the tracked config (silent-revert fix missing)"
[ "$fail" -eq 0 ] && echo "wiring checked: GameView/ReplayView/PhaseBar/PlayerBoard/StackZone/CardThumb/PhaseStopSettings"
exit $fail
