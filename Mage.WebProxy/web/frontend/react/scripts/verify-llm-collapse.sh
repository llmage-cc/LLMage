#!/usr/bin/env bash
# Bundle + run the LLM-tab collapse / replay pre-game harness under Node (no browser).
#
#   bash scripts/verify-llm-collapse.sh
#
# Covers 2026-09-30: every LLM-tab text block is collapsible (system prompt,
# conversation messages, reasoning sub-blocks, pre/post-game sections), and the
# replay viewer renders the pre-game blocks (system prompt, deck assessment,
# match review) that LLMPlayerHelper now emits in the stateIndex-0 bucket.
#
# Same pattern as verify-llm-reasoning-split.sh: rolldown bundle + the shared
# browser stubs (verify-stubs.mjs) + the store's dev-only window export
# neutralised for Node.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-llm-collapse-entry.tsx <<EOF
import '/tmp/xmage-llm-collapse-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-llm-collapse.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-llm-collapse-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-llm-collapse-entry.tsx --file /tmp/xmage-llm-collapse.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-llm-collapse.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-llm-collapse.mjs
