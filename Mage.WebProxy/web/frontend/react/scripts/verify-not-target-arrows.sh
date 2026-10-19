#!/usr/bin/env bash
# Bundle + run the REAL-targets arrows regression check under Node (no browser).
#
#   bash scripts/verify-not-target-arrows.sh
#
# "They're supposed to show the targets of spells, abilities,
# and attacks, not potential targets of them." The PRIOR direction (draw arrows
# from the prompt's candidates during selection) is REVERSED:
#  - a target prompt open (genuine or NOT_TARGET) ⇒ NO arrows at all; the
#    candidates are potential targets and surface as selectable highlights only;
#  - with no selection prompt, arrows are drawn from REAL data only:
#    StackItem.targets (GameStateSerializer.serializeCard forwards the engine's
#    CardView.targets — the spell/ability's already-chosen targets) and the
#    ability owner's name from `source_card`. Attack arrows remain
#    CombatArrows' job (the serializer's combat groups ARE the live
#    declaration; the engine re-serializes on every declaration change).
#
# Same pattern as verify-only-selectable.sh: the REAL store and the REAL
# message handler are bundled with browser stubs.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-not-target-arrows-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-not-target-arrows.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-not-target-arrows-entry.tsx --file /tmp/xmage-not-target-arrows.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-not-target-arrows.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-not-target-arrows.mjs
