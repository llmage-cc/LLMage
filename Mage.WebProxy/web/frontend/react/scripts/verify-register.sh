#!/usr/bin/env bash
# Bundle + run the account-registration checks under Node.
#
#   bash scripts/verify-register.sh
#
# Covers the frontend half of the register flow with NO browser and NO proxy —
# the REAL validators + payload builder (src/utils/registerForm.ts), the REAL
# store slice through the REAL message handler, and the REAL RegisterDialog /
# ConnectOverlay rendered with react-dom/server:
#  (a) the payload mirrors the desktop RegisterUserDialog (username, password,
#      EMAIL) and follows the connect target rule (server/port only when the
#      form owns them),
#  (b) the desktop's own check is enforced client-side: the two passwords must
#      match; the name/email rules refuse before a dial,
#  (c) registerAccount fails fast with no socket instead of a silent no-op,
#  (d) the proxy's register_success / register_error land in registerResult where
#      the dialog renders them — never on the showError toast,
#  (e) the dialog renders every desktop field, the email note, the busy state and
#      the terminal success state; a pinned deployment hides server/port,
#  (f) the ConnectOverlay offers Register… beside Connect.
#
# Same bundling recipe as verify-connect-target-toggle.sh (rolldown + browser
# stubs + the import.meta.env neutraliser).
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"

cat > /tmp/xmage-register-entry.tsx <<EOF
import '/tmp/xmage-register-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-register.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-register-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-register-entry.tsx --file /tmp/xmage-register-verify.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-register-verify.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
assert old in s, 'dev-export line not found'
open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-register-verify.mjs
