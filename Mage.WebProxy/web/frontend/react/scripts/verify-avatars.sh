#!/usr/bin/env bash
# Bundle + run the player-avatar checks under Node (no browser).
#
#   bash scripts/verify-avatars.sh
#
# The web equivalent of the desktop's avatar support
# (PlayerPanelExt.updateAvatar + PreferencesDialog "My Avatar").
#
# The REAL modules are bundled with browser stubs (scripts/verify-stubs.mjs) and
# rendered through react-dom/server — the same recipe as verify-settings-tabs.sh
# and verify-seat-names.sh. What is pinned: the resolver's single clamp (unknown
# id -> default, never a broken image), the shipped asset set, the Settings
# picker's grid + persistence, the connect/set_avatar/avatar_set wire, the seat
# avatars in the REAL PlayerBoard header, and the reset/preset coverage of the
# new setting (see scripts/verify-avatars.tsx for the full list).
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"

cat > /tmp/xmage-avatars-entry.tsx <<EOF
import '/tmp/xmage-avatars-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-avatars.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-avatars-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-avatars-entry.tsx --file /tmp/xmage-avatars.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-avatars.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-avatars.mjs
