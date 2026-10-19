#!/usr/bin/env bash
# Bundle + run the Provider-dropdown checks under Node (no browser).
#
#   bash scripts/verify-provider-dropdown.sh
#
# Covers the LLM seat config's endpoint dropdown

#  (a) provider table <-> relay routing consistency (browser-direct vs /llm-relay),
#  (b) stored endpoint -> select value (server default / provider / Other…),
#  (c) the pure select transition incl. Fail-Fast on unknown/disabled options,
#  (d) the REAL field component: provider preselected, Other… reveals the
#      free-text box, Anthropic disabled with its reason,
#  (e) the REAL seat row rendering the dropdown for an LLM seat.
#
# Same pattern as verify-connect-target-toggle.sh: the REAL components + the
# REAL provider table are bundled with browser stubs and rendered through
# react-dom/server.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-provider-dropdown-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-provider-dropdown.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-provider-dropdown-entry.tsx --file /tmp/xmage-provider-dropdown.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-provider-dropdown.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-provider-dropdown.mjs
