#!/usr/bin/env bash
# Phase-based feedback colouring: the action-prompt block is
# tinted by the CURRENT PHASE (blue main / red combat / green other), matching
# the Java HelperPanel's feedback-panel colouring.
#
#   bash scripts/verify-phase-color.sh
#
# Two layers:
#   1. Behavioural harness (real store + real phaseTint + real ActionPromptBar
#      under react-dom/server): the pure class derivation for every Phase enum
#      value, and the rendered rail's class for main/combat/other/null/no-state.
#   2. CSS greps (SSR markup cannot prove a stylesheet exists): the three tint
#      rules must be present in web/frontend/styles/popups.css and must consume
#      theme variables rather than hardcoded colours.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-phase-color-entry.ts <<EOF
import '/tmp/xmage-phase-color-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-phase-color.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-phase-color-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-phase-color-entry.ts --file /tmp/xmage-phase-color.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-phase-color.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-phase-color.mjs

# ── CSS wiring ──
fail=0
fail() { echo "FAIL: $*"; fail=1; }
CSS=../styles/popups.css
for cls in action-prompt--main action-prompt--combat action-prompt--other; do
  grep -q "^\.$cls {" "$CSS" || fail "$CSS has no .$cls rule (that phase renders untinted)"
done
# Theme variables, not hardcoded hex — the tint must follow dark/light.
grep -q 'var(--format-modern' "$CSS" || fail "$CSS main tint does not use the theme blue"
grep -q 'var(--color-danger' "$CSS" || fail "$CSS combat tint does not use the theme red"
grep -q 'var(--accent-success' "$CSS" || fail "$CSS other tint does not use the theme green"
# The base rule must still be the plain surface + border (untinted frames).
grep -q '^\.action-prompt {' "$CSS" || fail "$CSS lost the base .action-prompt rule"
# One grep per derivation branch on the component side.
grep -q "actionPromptClass" src/components/ActionPromptBar.tsx \
  || fail "ActionPromptBar.tsx does not derive the tint class"
grep -q "case 'C1'" src/utils/phaseTint.ts || fail "phaseTint.ts has no combat (C1) branch"
grep -q "case 'M1'" src/utils/phaseTint.ts || fail "phaseTint.ts has no main (M1) branch"
[ "$fail" -eq 0 ] && echo "CSS + component wiring checked (popups.css / ActionPromptBar.tsx / phaseTint.ts)"
exit $fail
