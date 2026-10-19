#!/usr/bin/env bash
# Bundle + run the settings-dialog tab-split checks under Node (no browser).
#
#   flock /tmp/xmage-npm.lock bash scripts/verify-settings-tabs.sh
#   Run this harness as part of the normal verification pass.
#
# Covers the settings tab split:
# "the settings menu should be split into tabs so there isn't so much
# scrolling to do"):
#   (a) six canonical tabs (Display/Appearance/Alerts/Tokens/LLM Chat/Presets),
#       default tab Display,
#   (b) the REAL dialog renders role=tablist/tab/tabpanel with aria-selected,
#       aria-controls, roving tabIndex and exactly ONE visible panel,
#   (c) every flat-column data-explain id still renders and is registered,
#   (d) the key map + Fail-Fast helpers,
#   (e) the replay-history toggle lives in the LLM Chat tab on the SAME store
#       field the replay rail's in-panel checkbox edits,
#   (f) all panels stay mounted-hidden (the Presets name draft survives).
#
# The click/keydown bindings cannot be asserted through react-dom/server (React
# strips event handlers from SSR markup), so step B greps the REAL component
# source for them — the split of duties used by verify-llm-config-tabs.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"

# ── A. behaviour, through the real modules ────────────────────────────────────
cat > /tmp/xmage-settings-tabs-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-settings-tabs.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-settings-tabs-entry.tsx --file /tmp/xmage-settings-tabs.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-settings-tabs.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-settings-tabs.mjs

# ── B. handler bindings, from the REAL source ─────────────────────────────────
# react-dom/server drops onClick/onKeyDown, so the wiring is asserted here.
echo
echo "## tab handler bindings (source grep — SSR markup cannot carry them)"
fail=0
fail() { echo "FAIL: $*"; fail=1; }
SRC="src/components/SettingsDialog.tsx"
grep -q 'role="tablist"' "$SRC"                     || fail "$SRC: no role=\"tablist\" container"
grep -q 'onClick={() => setActiveTab(name)}' "$SRC" || fail "$SRC: tabs do not select themselves on click"
grep -q "onKeyDown={(e) => {" "$SRC"                || fail "$SRC: tab bar has no keydown handler"
grep -q 'settingsTabAfterKey(activeTab, e.key)' "$SRC" || fail "$SRC: keydown does not go through the pure settingsTabAfterKey() map"
grep -q 'activateTab(next)' "$SRC"                  || fail "$SRC: keyboard navigation does not move the selection"
grep -q '.focus()' "$SRC"                           || fail "$SRC: keyboard navigation does not move focus with the selection"
grep -q "hidden={activeTab !== 'Display'}" "$SRC"   || fail "$SRC: panels are not gated on the active tab"
grep -q 'data-explain="settings-tab"' "$SRC"        || fail "$SRC: tab buttons carry no explain id"
[ "$fail" -eq 0 ] && echo "all tab handler bindings present"
exit "$fail"
