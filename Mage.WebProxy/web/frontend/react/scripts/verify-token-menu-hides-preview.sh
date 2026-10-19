#!/usr/bin/env bash
# Token-art menu vs hover-preview gate (right-clicking a token
# opened the art browser UNDER the hover card — #card-preview z-index 9999 vs
# the menu's inline 3000 — so the menu was invisible behind the preview panel).
#
#   bash scripts/verify-token-menu-hides-preview.sh
#
# Two layers:
#   1. Behavioural harness (real store + real components under react-dom/server):
#      while the store's tokenArtMenuOpen flag is up, CardPreview renders NOTHING,
#      and closing the menu restores the still-stored preview.
#   2. Handler-binding grep in the verify-card-grid-preview pattern: SSR markup
#      cannot contain event handlers, so the wiring is asserted on the REAL
#      render sites — CardThumb must raise the flag on the token right-click
#      path and clear it on menu close, and TokenArtMenu callers must pass an
#      onClose that clears it.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-token-menu-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-token-menu-hides-preview.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-token-menu-entry.tsx --file /tmp/xmage-token-menu.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-token-menu.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-token-menu.mjs

# ── handler wiring on the real render sites ──
fail=0
fail() { echo "FAIL: $*"; fail=1; }
CT=src/components/CardThumb.tsx
grep -q "useContextMenu('token-art')" "$CT" \
  || fail "$CT does not open the token-art menu through the shared useContextMenu registry"
grep -q "useContextMenu('card')" "$CT" \
  || fail "$CT does not open the card menu through the shared useContextMenu registry"
grep -q 'cardMenu.openAt(e, { card, onClick, isPlayable })' "$CT" \
  || fail "$CT right-click path does not register the card menu payload"
# The token payload MUST carry the token + its engine set — that is what lets the
# panel be mounted once instead of per tile.
grep -q 'tokenArtMenu.openAt(e, { tokenName: cardName, engineSet: engineTokenSetCode(card) })' "$CT" \
  || fail "$CT token right-click does not register tokenName + engineSet in the payload"
# REGRESSION GUARD: CardThumb must NOT render the panel. A per-tile render site
# with the registry's global isOpen put one panel on every tile of the board.
grep -q '<TokenArtMenu' "$CT" \
  && fail "$CT renders <TokenArtMenu> per tile — the panel must be mounted once (GameView/ReplayView)"
grep -q 'import { TokenArtMenu }' "$CT" \
  && fail "$CT still imports TokenArtMenu — it must only open the registry entry"
grep -q 'setOpenContextMenu(null)' "$CT" \
  || fail "$CT unmount cleanup does not clear a stuck open-menu entry"
# Scoped cleanup: the
# unmount cleanup must consult menuOwnedByTile so an unrelated tile's unmount
# cannot close a menu still anchored elsewhere. The old unscoped shape
# (menuWasOpenRef + blanket setOpenContextMenu(null)) is a regression.
grep -q 'menuOwnedByTile' "$CT" \
  || fail "$CT unmount cleanup is unscoped — must guard with menuOwnedByTile (unrelated unmounts close open menus)"
grep -q 'menuWasOpenRef' "$CT" \
  && fail "$CT still has the unscoped menuWasOpenRef cleanup guard (regression to mechanism 1)"
grep -q 'export function menuOwnedByTile' src/utils/menuScope.ts \
  || fail "src/utils/menuScope.ts does not export the menuOwnedByTile ownership guard"
# Exactly one mount point per view, next to the other registry-driven menus.
for f in src/components/GameView.tsx src/components/ReplayView.tsx; do
  grep -q '<TokenArtMenu />' "$f" \
    || fail "$f does not mount <TokenArtMenu /> (one mount point per game/replay view)"
done
grep -q '!!s.openContextMenu' src/components/CardPreview.tsx \
  || fail "CardPreview.tsx does not suppress on the shared openContextMenu registry"
grep -q 'contextMenuOpen) return null' src/components/CardPreview.tsx \
  || fail "CardPreview.tsx does not suppress the panel while any menu is open"
grep -q 'tokenArtMenuOpen' src/store/types.ts src/store/slices/uiSlice.ts \
  && fail "stale tokenArtMenuOpen flag still exists in the store (superseded by openContextMenu)"
[ "$fail" -eq 0 ] && echo "handler wiring checked on CardThumb.tsx / CardPreview.tsx / GameView.tsx / ReplayView.tsx"
exit $fail
