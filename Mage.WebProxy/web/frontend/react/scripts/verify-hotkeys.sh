#!/usr/bin/env bash
# Bundle + run the keyboard-shortcut rebinding checks under Node (no browser).
#
#   bash scripts/verify-hotkeys.sh
#
# Java reference: PreferencesDialog's "Game - Hotkeys" tab:
# the rail's nine F-key shortcuts move from a hardcoded switch in
# ActionPromptBar.tsx into the rebindable registry src/utils/keybindings.ts,
# with a new Settings → Hotkeys tab on top of it. Covers default resolution
# (F5 → skip-eot; F8/F12 deliberately unmapped), rebind, duplicate refusal,
# reset (per row + through settingsDefaults.ts's Reset to Default), the typing /
# menu / settings / replay / capture guards, the replay viewer's keyboard
# hand-back, and the rail's wiring (live-state read, no hardcoded keys left).
#
# Same pattern as verify-undeclare.sh: the REAL store, REAL components and REAL
# registry are bundled with browser stubs. Step B greps the REAL component
# sources for handler bindings that react-dom/server strips from SSR markup.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-hotkeys-entry.ts <<EOF
import '/tmp/xmage-hotkeys-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-hotkeys.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-hotkeys-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-hotkeys-entry.ts --file /tmp/xmage-hotkeys.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-hotkeys.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
export XMAGE_VERIFY_DIR="$HERE/scripts"
node /tmp/xmage-hotkeys.mjs

# ── B. handler bindings + the Settings → Hotkeys capture wiring ───────────────
# react-dom/server drops onClick/onKeyDown, so the wiring is asserted here.
echo
echo "## capture wiring (source grep — SSR markup cannot carry handlers)"
fail=0
fail() { echo "FAIL: $*"; fail=1; }
SETTINGS="src/components/SettingsDialog.tsx"
grep -q "beginCapture()" "$SETTINGS"                || fail "$SETTINGS: a rebind row never arms the app-wide capture suspension"
grep -q "endCapture(token)" "$SETTINGS"             || fail "$SETTINGS: the capture is never released"
grep -q "window.addEventListener('keydown', onCaptureKeydown, true)" "$SETTINGS" \
  || fail "$SETTINGS: the capture does not swallow keys at window capture phase"
grep -q "captureKeyDecision(e.key)" "$SETTINGS"     || fail "$SETTINGS: the captured key does not go through the pure decision map"
grep -q "rebindKeybinding(target, decision.key)" "$SETTINGS" || fail "$SETTINGS: the captured key is never bound"
grep -q "resetKeybindings()" "$SETTINGS"            || fail "$SETTINGS: no Reset All Shortcuts path"
grep -q "hotkeys" "$SETTINGS"                       || fail "$SETTINGS: no Hotkeys panel"
RAIL="src/components/ActionPromptBar.tsx"
grep -q "matchHotkeyAction(" "$RAIL"                || fail "$RAIL: the rail does not resolve keys through the registry"
grep -q "isCaptureActive()" "$RAIL"                 || fail "$RAIL: the rail does not stand down while a rebind is armed"
if grep -qE "case 'F[0-9]+'" "$RAIL"; then fail "$RAIL: hardcoded F-key cases remain in the keydown handler"; fi
REG="src/utils/keybindings.ts"
grep -q "export function rebindKeybinding" "$REG"   || fail "$REG: no rebind API"
grep -q "export function resetKeybindings" "$REG"   || fail "$REG: no module reset (settingsDefaults integration point)"
grep -q "reason: 'conflict'" "$REG"                 || fail "$REG: duplicate bindings are not refused"
grep -q "resetKeybindings" src/utils/settingsDefaults.ts || fail "settingsDefaults.ts: Reset to Default does not reach the keybindings reset"
[ "$fail" -eq 0 ] && echo "all hotkey wiring present (capture, rail registry consumption, no hardcoded keys)"
exit "$fail"
