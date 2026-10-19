#!/usr/bin/env bash
# Token art in the hover preview (changing a token's art during
# a replay changed it on screen, but the large hover preview showed "a blank
# space"). The panel resolved tokens through the generic card route only — and
# 'Soldier Token' is not a key in the offline Scryfall name map (XMage runtime
# names carry the " Token" suffix; Scryfall's do not) — and never consulted the
# token-art preference chain.
#
#   bash scripts/verify-token-preview-art.sh
#
# Two layers:
#   1. Behavioural harness (real store + real CardPreview + real tokenArt module
#      under react-dom/server): the pure chain (previewArtCandidates) and the
#      rendered panel markup — token art shows, a chosen art wins, art-less
#      tokens show the placeholder, non-tokens keep working.
#   2. Wiring greps (SSR markup cannot contain event handlers): the owner
#      context must ride the CardThumb hover payload, the payload type must
#      declare it, and the CSS placeholder must exist.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-token-preview-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-token-preview-art.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-token-preview-entry.tsx --file /tmp/xmage-token-preview.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-token-preview.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-token-preview.mjs

# ── wiring on the real render sites ──
fail=0
fail() { echo "FAIL: $*"; fail=1; }
CT=src/components/CardThumb.tsx
CP=src/components/CardPreview.tsx
# The owner context (which preference layer applies) must ride the hover payload.
grep -q 'ownerPlayerId, isMine }' "$CT" \
  || fail "$CT hover payload does not carry ownerPlayerId/isMine (preference layer unreachable)"
grep -q "ownerPlayerId?: string | null | undefined" src/store/types.ts \
  || fail "store/types.ts cardPreview payload does not declare ownerPlayerId"
grep -q "isMine?: boolean | undefined" src/store/types.ts \
  || fail "store/types.ts cardPreview payload does not declare isMine"
# The panel must resolve through the token chain, not just resolveCardImage.
grep -q 'previewArtCandidates' "$CP" \
  || fail "$CP does not use the shared preview chain"
grep -q 'preferTokenArt' "$CP" \
  || fail "$CP does not consult token-art preferences (chosen art would not show)"
grep -q 'fetchTokenVersions' "$CP" \
  || fail "$CP does not warm the token-versions cache on hover"
grep -q 'getTokenVersions' "$CP" \
  || fail "$CP does not read the cached printings"
# The placeholder contract: never a broken <img>.
grep -q 'preview-art-fallback' "$CP" \
  || fail "$CP has no card-back placeholder for art-less cards"
grep -q '#card-preview .preview-art-fallback' ../game-renderer.css \
  || fail "game-renderer.css has no .preview-art-fallback rule (placeholder would collapse)"
# A replay must not offer a Share box that cannot relay.
grep -q 'Replay viewing' src/components/TokenArtMenu.tsx \
  || fail "TokenArtMenu does not surface the replay (no table to share with) state"
[ "$fail" -eq 0 ] && echo "handler wiring checked on CardThumb.tsx / CardPreview.tsx / TokenArtMenu.tsx / store/types.ts / game-renderer.css"
exit $fail
