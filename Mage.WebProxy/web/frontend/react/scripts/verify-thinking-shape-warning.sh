#!/usr/bin/env bash
# Bundle + run the setup-time thinking-shape warning checks under Node (no browser).
#
#   bash scripts/verify-thinking-shape-warning.sh
#
# Checks:
#  (a) the pure classification (utils/thinkingShape.ts) mirrors the engine's
#      request-builder gate (LLMClient.emitsThinkingObject): Ollama-classified
#      endpoint or budget dialect = thinking{} object; otherwise bare
#      reasoning_effort; disabled = off,
#  (b) the seat form renders the warning NEXT TO the Thinking Enabled checkbox
#      for strict-classified endpoints with thinking enabled (proxy verdict or
#      the seat's own classification), and never for Ollama/off/other fields,
#  (c) the proxy's llm_setup_warning surfaces in the NAMED seat's row only,
#  (d) messageHandlers records the models_list verdict and routes the warning;
#      classification-less models_list messages (older proxies) don't wipe it,
#  (e) the dialog lifecycle clears the warning.
#
# Same pattern as verify-llm-config-tabs.sh: the REAL components + store are
# bundled with browser stubs and rendered through react-dom/server.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"

cat > /tmp/xmage-thinking-shape-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-thinking-shape-warning.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-thinking-shape-entry.tsx --file /tmp/xmage-thinking-shape.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-thinking-shape.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-thinking-shape.mjs