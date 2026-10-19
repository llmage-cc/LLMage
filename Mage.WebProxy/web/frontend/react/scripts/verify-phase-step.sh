#!/usr/bin/env bash
# Bundle + run the separately labelled step/phase header checks under Node.
#   bash scripts/verify-phase-step.sh
#
# GameHeader.tsx used to render ONE combined
# `Turn N | <Phase/Step> | priority` string (formatPhaseStep). The desktop shows
# step and phase as separately labelled fields (GamePanel lblStep/txtStep +
# lblPhase/txtPhase). This harness renders the REAL GameHeader through
# react-dom/server and asserts the split + the pure formatters.
#
# Same bundling pattern as verify-endgame-presentation.sh: rolldown (no tsx
# dependency), stub the browser APIs, run under Node. Exports XMAGE_REACT_SRC so
# the source-scan assertions read THIS worktree's src/.
#
# SERIALIZE with other agents' harness runs: the harnesses use fixed /tmp
# filenames, so take /tmp/xmage-verify.lock first.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
REPO_ROOT="$(cd "$(dirname "$0")/../../../../.." && pwd)"

export XMAGE_REACT_SRC="$HERE/src"

LOCK=/tmp/xmage-verify.lock
until mkdir "$LOCK" 2>/dev/null; do
  echo "waiting for /tmp/xmage-verify.lock (another harness running)…" >&2
  sleep 30
done
trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT

cat > /tmp/xmage-phase-step-entry.ts <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${REPO_ROOT}/Mage.WebProxy/web/frontend/react/scripts/verify-phase-step.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-phase-step-entry.ts --file /tmp/xmage-phase-step.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-phase-step.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
if old in s:
    s = s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;")
# zustand v5 hands react-dom/server `selector(api.getInitialState())`; React 19's
# useSyncExternalStore takes THAT snapshot for renderToStaticMarkup, so an SSR
# render would read creation-time state. The GameHeader reads gameState from
# PROPS (not the store), so this is belt-and-braces only.
ssr = "() => selector(api.getInitialState())"
if ssr in s:
    s = s.replace(ssr, "() => selector(api.getState())")
open(p, 'w').write(s)
PY
node /tmp/xmage-phase-step.mjs
