#!/usr/bin/env bash
# Bundle + run the LLM seat-config tab checks under Node (no browser).
#
#   bash scripts/verify-llm-config-tabs.sh
#
# Covers the seat config tab split:
# "the LLM config menu is awful to deal with, should be split into tabs like
# the java client is"):
#  (a) the five tabs are the desktop LLMConfigDialog's, in order, and every
#      LLM_CONFIG_SECTIONS field sits in exactly one of them with its exact
#      label and control type,
#  (b) the REAL seat row renders role=tablist/tab/tabpanel, one visible panel,
#      roving tabIndex, aria-selected on exactly the active tab,
#  (c) switching: every tab can be made active and then exactly one panel — that
#      tab's aria-controls target — is visible (plus the keyboard map),
#  (d) Fail Fast on an unknown / duplicate / missing section or an unknown tab,
#  (e) field behaviour unchanged: model -> modelContextWindow auto-fill, endpoint
#      change and ↻ refresh routing browser-direct vs proxy by key presence,
#  (f) the Provider dropdown (Other… + free text) still works inside the Core tab,
#  (g) the seat gating (LLM seat on an LLM-capable server) is unchanged.
#
# The click/keydown bindings cannot be asserted through react-dom/server (React
# strips event handlers from SSR markup), so step B greps the REAL component
# source for them — the split of duties used by verify-card-grid-preview.sh.
#
# Same pattern as verify-provider-dropdown.sh: the REAL components + the REAL
# provider/section tables are bundled with browser stubs and rendered through
# react-dom/server.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"

# ── A. behaviour, through the real modules ────────────────────────────────────
cat > /tmp/xmage-llm-config-tabs-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-llm-config-tabs.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-llm-config-tabs-entry.tsx --file /tmp/xmage-llm-config-tabs.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-llm-config-tabs.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-llm-config-tabs.mjs

# ── B. handler bindings, from the REAL source ─────────────────────────────────
# react-dom/server drops onClick/onKeyDown, so the wiring is asserted here.
# Every one of these is a line the component must keep for the tab bar to work
# in a browser (a tab that selects, and the tablist key map).
echo
echo "## tab handler bindings (source grep — SSR markup cannot carry them)"
fail=0
fail() { echo "FAIL: $*"; fail=1; }
SRC="src/components/LLMConfigTabs.tsx"
grep -q 'role="tablist"' "$SRC"            || fail "$SRC: no role=\"tablist\" container"
grep -q 'onClick={() => setActiveTab(name)}' "$SRC" || fail "$SRC: tabs do not select themselves on click"
grep -q "onKeyDown={(e) => {" "$SRC"        || fail "$SRC: tab bar has no keydown handler"
grep -q 'tabAfterKey(activeTab, e.key)' "$SRC" || fail "$SRC: keydown does not go through the pure tabAfterKey() map"
grep -q 'activate(next)' "$SRC"            || fail "$SRC: keyboard navigation does not move the selection"
grep -q '\.focus()' "$SRC"                 || fail "$SRC: keyboard navigation does not move focus with the selection"
grep -q 'hidden={name !== activeTab}' "$SRC" || fail "$SRC: panels are not gated on the active tab"
grep -q 'buildLlmConfigTabs(LLM_CONFIG_SECTIONS)' "$SRC" || fail "$SRC: the tab bar does not validate LLM_CONFIG_SECTIONS"
grep -q '<LLMConfigTabs' src/components/PlayerSlotRow.tsx || fail "PlayerSlotRow.tsx: the seat row does not render LLMConfigTabs"
[ "$fail" -eq 0 ] && echo "all tab handler bindings present"
exit "$fail"
