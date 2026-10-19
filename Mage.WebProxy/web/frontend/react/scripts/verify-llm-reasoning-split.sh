#!/usr/bin/env bash
# Bundle + run the LLM-tab reasoning-split harness under Node (no browser).
#
#   bash scripts/verify-llm-reasoning-split.sh
#
# Covers the web LLM tab rendering BOTH assistant-message shapes —
# the new [model_reasoning] fence and the legacy
# "Decision: …/Reasoning: …" text of old saves and replays. The pure split
# (RightRail.splitAssistantMessage) is asserted directly, and the REAL
# RightRail LLM tab is rendered through react-dom/server for both a live board
# and the replay cumulative-history path.
#
# Same pattern as verify-chat-labels.sh: rolldown bundle + the shared browser
# stubs (verify-stubs.mjs) + the store's dev-only window export neutralised for
# Node. Serializes with other harness runs through the sweep's npm lock.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-llm-reasoning-split-entry.tsx <<EOF
import '/tmp/xmage-llm-reasoning-split-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-llm-reasoning-split.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-llm-reasoning-split-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-llm-reasoning-split-entry.tsx --file /tmp/xmage-llm-reasoning-split.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-llm-reasoning-split.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-llm-reasoning-split.mjs
