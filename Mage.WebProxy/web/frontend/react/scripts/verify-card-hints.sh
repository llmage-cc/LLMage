#!/usr/bin/env bash
# Card hints helper — behavioural + wiring gate.
#
#   bash scripts/verify-card-hints.sh
#
# Layer 1 (Node): the REAL parser/collector/groupers (utils/cardHints.ts), the
# REAL modal store (store/slices/cardHints.ts), the REAL modal component and the
# REAL game header through react-dom/server — see verify-card-hints.tsx.
#
# Layer 2 (greps): the wiring SSR markup cannot carry — the header button's
# onClick, the App-level mount, the CSS import, the desktop-parity scan list and
# the launch-affordance placement. Same bundling recipe as verify-seat-names.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"

# rolldown config: the modal imports its own CSS (`import './cardHints.css'`, the
# viewDeck.css convention). Node bundling has no CSS loader, so a tiny inline
# plugin resolves every .css import to an EMPTY module — the stylesheet itself is
# asserted by a grep in the wiring layer below, not by the Node run.
cat > /tmp/xmage-card-hints-rolldown.config.mjs <<EOF
export default {
  input: '/tmp/xmage-card-hints-entry.tsx',
  resolve: { alias: { '@': '${HERE}/src' } },
  output: { file: '/tmp/xmage-card-hints.mjs', format: 'esm' },
  platform: 'node',
  plugins: [{
    name: 'css-empty-module',
    resolveId(source) {
      if (source.endsWith('.css')) return '\0empty-css'
      return null
    },
    load(id) {
      if (id === '\0empty-css') return ''
      return null
    },
  }],
}
EOF
cat > /tmp/xmage-card-hints-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-card-hints.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown -c /tmp/xmage-card-hints-rolldown.config.mjs >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-card-hints.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
if old in s:
    open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-card-hints.mjs

# ── wiring on the real render sites ──
echo
echo "## wiring on the render sites"

python3 - <<'PY'
import sys

fails = []

def read(p):
    return open(p).read()

def has(p, needle, name):
    ok = needle in read(p)
    print(('  PASS  ' if ok else '  FAIL  ') + name)
    if not ok:
        fails.append(name)

def has_not(p, needle, name):
    ok = needle not in read(p)
    print(('  PASS  ' if ok else '  FAIL  ') + name)
    if not ok:
        fails.append(name)

# 1. GameHeader: the launch affordance (desktop PlayerPanelExt "Hints" button,
#    visible for the own seat only).
header = read('src/components/GameHeader.tsx')
has('src/components/GameHeader.tsx', 'openCardHints()', 'header button opens the helper (onClick -> store)')
has('src/components/GameHeader.tsx', 'data-explain="header-card-hints"', 'header button carries its explain id')
has('src/components/GameHeader.tsx', '!gameState.isSpectator', 'header button is gated like the desktop (isMe only)')
has('src/components/GameHeader.tsx', "from '@/store/slices/cardHints'", 'header imports the helper store')

# 2. App: one mount line, outside the view branches (like ViewDeckModal).
app = read('src/App.tsx')
has('src/App.tsx', "import CardHintsModal from '@/components/CardHintsModal'", 'App imports the modal')
has('src/App.tsx', '<CardHintsModal />', 'App mounts the modal')

# 3. The modal component: reads the on-screen board, has the empty state, the
#    three controls and the close button.
modal = read('src/components/CardHintsModal.tsx')
has('src/components/CardHintsModal.tsx', 'selectBoardState', 'modal reads the board on screen (live game OR replay)')
has('src/components/CardHintsModal.tsx', 'EMPTY_HINTS_WARNING', 'modal renders the desktop empty-state text')
has('src/components/CardHintsModal.tsx', 'collectCardHints(gameState)', 'modal collects from the wire rules')
has('src/components/CardHintsModal.tsx', "data-explain=\"card-hints-close\"", 'close button carries its explain id')
has('src/components/CardHintsModal.tsx', "import './cardHints.css'", 'modal imports its own CSS')

# 4. The scan list (desktop CardHintsHelperDialog.loadHints): every scanned zone
#    present in the collector, and the desktop's own zone labels.
util = read('src/utils/cardHints.ts')
for zone, name in [("'hand'", 'hand scanned'), ("'stack'", 'stack scanned'),
                   ("'battlefield'", 'battlefield scanned'), ("'command'", 'command scanned'),
                   ("'graveyard'", 'graveyard scanned'), ("'exile'", 'exile scanned')]:
    has('src/utils/cardHints.ts', zone, name)
has('src/utils/cardHints.ts', "HINT_START_MARK = '<br/><hintstart/>'", 'marker equals HintUtils.HINT_START_MARK exactly')
has('src/utils/cardHints.ts', 'MAX_CARD_IDS_PER_HINT = 3', 'grouping cap equals the desktop MAX_CARD_IDS_PER_HINT')
has('src/utils/cardHints.ts', "EMPTY_HINTS_WARNING = 'Game not started or nothing to show'", 'empty text equals the desktop EMPTY_HINTS_WARNING')
# The collector must NOT invent a helper-emblem zone (the desktop's "xmage"
# group): helper emblems are engine-side and not serialized, so a zone literal
# for them would be dead code. Check the SCAN BODY only (the module header
# explains why the zone is out of reach, so a whole-file grep would false-fail).
scan_body = util.split('export function collectCardHints')[1].split('\n}')[0]
ok = "'xmage'" not in scan_body and "'emblems'" not in scan_body and 'helpers' not in scan_body
print(('  PASS  ' if ok else '  FAIL  ') + 'no invented helper-emblem zone in the scan')
if not ok:
    fails.append('no invented helper-emblem zone in the scan')

# 5. The wire is untouched: this feature is frontend-only. Assert the serializer
#    still emits card rules verbatim (the data source the modal consumes).
has('../../../src/main/java/mage/webproxy/GameStateSerializer.java',
    'c.put("rules", card.getRules())', 'serializer still emits card rules (the data source)')

if fails:
    print(f"\nWIRING GATE FAILED: {len(fails)} check(s)")
    sys.exit(1)
print("\nWIRING GATE PASSED")
PY
