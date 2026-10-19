#!/usr/bin/env bash
# Bundle + run the models-error spinner harness under Node.
#
#   bash scripts/verify-models-error.sh          ← SELF-LOCKS (npm cache lock); run bare
#
# Covers the fetch-error fix: the models spinner
# (lobbySlice.modelsLoading) used to spin forever when a token-gated proxy
# rejected an unauthenticated `fetch_models` with the GENERIC `error` frame
# (only models_list/models_error cleared it).
#
# Same bundling recipe as verify-register.sh (rolldown + browser stubs + the
# import.meta.env neutraliser); the harness itself documents each check.
set -euo pipefail

# Self-lock: maven/npm jobs in this this run /tmp/npm.lock, so re-exec
# under flock when we are not already holding it.
if [ -z "${VERIFY_NPM_LOCK_HELD:-}" ]; then
  export VERIFY_NPM_LOCK_HELD=1
  exec flock -w 300 /tmp/llm-npm.lock "$0" "$@"
fi

cd "$(dirname "$0")/.."
REPO_ROOT="$(cd "$(dirname "$0")/../../../../.." && pwd)"
export XMAGE_REPO_ROOT="$REPO_ROOT"
HERE="$(pwd)"

cat > /tmp/xmage-models-error-entry.ts <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-models-error.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-models-error-entry.ts --file /tmp/xmage-models-error.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-models-error.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-models-error.mjs
