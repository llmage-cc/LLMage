#!/usr/bin/env bash
# Bundle + run the seat-join error harness under Node.
#
#   bash scripts/verify-seat-join-error.sh          ← SELF-LOCKS (npm cache lock); run bare
#
# Covers the fix: a refused create/join-table seat
# used to reach the browser as `Seat N failed to join: ` with an EMPTY reason (the
# proxy read only xmageSession.getLastError(), which is set only on connect
# failures) and the player saw nothing actionable. The proxy now forwards the
# server's own SHOW_USERMESSAGE reason verbatim, and this harness pins (a) the
# frontend surfaces it, (b) it is never empty, (c) it does not disturb models
# state, and (d) the Java source really forwards the reason on both create paths.
#
# Same bundling recipe as verify-models-error.sh (rolldown + browser stubs + the
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

cat > /tmp/xmage-seat-join-error-entry.ts <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-seat-join-error.ts'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-seat-join-error-entry.ts --file /tmp/xmage-seat-join-error.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-seat-join-error.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-seat-join-error.mjs
