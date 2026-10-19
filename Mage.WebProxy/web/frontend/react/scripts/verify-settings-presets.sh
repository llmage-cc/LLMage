#!/usr/bin/env bash
# Bundle + run the settings-presets behavioural checks under Node.
#   bash scripts/verify-settings-presets.sh
#
# Same approach as scripts/verify-prompt-lifecycle.sh: bundle the REAL modules
# (utils/settingsPresets + store + settingsDefaults + priorityDing + tokenArt)
# with browser stubs from scripts/verify-stubs.mjs (which provides the
# localStorage stub), then drive them through the REAL setters — no browser.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-settings-presets-entry.ts <<EOF
import '/tmp/xmage-settings-presets-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-settings-presets.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-settings-presets-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-settings-presets-entry.ts --file /tmp/xmage-settings-presets.mjs --format esm --platform node >/dev/null
# The dev-only window export in the store references import.meta.env, which
# does not exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-settings-presets.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
export XMAGE_REACT_SRC="$HERE/src"
node /tmp/xmage-settings-presets.mjs
