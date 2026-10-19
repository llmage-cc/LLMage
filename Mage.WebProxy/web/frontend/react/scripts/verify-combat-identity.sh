#!/usr/bin/env bash
# Bundle + run the combat-click IDENTITY regression check under Node (no browser).
#
#   bash scripts/verify-combat-identity.sh
#
# "selecting/unselecting attackers/blockers is bugged
# especially with lots of tokens."
#
# Combat declares answer by creature UUID, never by positional index — the
# engine renumbers the choice list after every declare and the browser can be
# one render behind, so a stale index declares the WRONG creature (measured:
# clicked 48154915…, engine declared 01e9234a…) or dies as "Invalid choice
# index". Same harness shape as verify-undeclare.sh: the REAL store and REAL
# message handler are bundled with browser stubs.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"
cat > /tmp/xmage-combat-identity-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-combat-identity.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-combat-identity-entry.tsx --file /tmp/xmage-combat-identity.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-combat-identity.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
if old in s:
    open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-combat-identity.mjs
