#!/usr/bin/env bash
# Hand-switch harness (the desktop's btnSwitchHands).
#
#   bash scripts/verify-hand-switch.sh
#
# Bundles scripts/verify-hand-switch.tsx with the REAL store, message handler,
# PlayerBoard/HandSwitch/handSwitch modules and explanation registry, and runs it
# under Node (the same pattern as verify-offhand-affordance.sh / verify-replay-
# settings.sh). Layers covered — see the .tsx header:
#
#  (a) the delta path (control started / lost) through the real handler;
#  (b) one hand → the normal single-hand view, no switch UI;
#  (c) two hands → the toggle lists both;
#  (d) switching swaps the rendered hand (SSR of the REAL PlayerBoard);
#  (e) control lost → the view collapses back, the stale key falls back;
#  (f) the Java serializer's wire contract (field names / kinds / unchanged `hand`);
#  (g) the control's explanation id and its real registry entry.
#
# CSS note: HandSwitch and PlayerBoard import .css files, and rolldown's CLI
# refuses to bundle CSS (UNSUPPORTED_FEATURE since the experimental support was
# removed). The config below stubs every .css import with an empty module — the
# harness asserts markup and behaviour, never styling.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"

OUT=/tmp/xmage-hand-switch.mjs
ENTRY=/tmp/xmage-hand-switch-entry.tsx
CFG=/tmp/xmage-hand-switch-rolldown.config.mjs

cat > "$ENTRY" <<EOF
import '/tmp/xmage-hand-switch-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-hand-switch.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-hand-switch-stubs.mjs

cat > "$CFG" <<EOF
export default {
  input: '${ENTRY}',
  resolve: { alias: { '@': '${HERE}/src' } },
  plugins: [{
    name: 'css-stub',
    resolveId(id) { return id.endsWith('.css') ? '\0css-stub' : null },
    load(id) { return id === '\0css-stub' ? 'export default {}' : null },
  }],
  output: { file: '${OUT}', format: 'esm' },
  platform: 'node',
}
EOF

./node_modules/.bin/rolldown -c "$CFG" >/dev/null

# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle (the same
# fix verify-offhand-affordance.sh applies).
python3 - <<'PY'
p = '/tmp/xmage-hand-switch.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
if old in s:
    open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY

XMAGE_VERIFY_DIR="$HERE" node "$OUT"