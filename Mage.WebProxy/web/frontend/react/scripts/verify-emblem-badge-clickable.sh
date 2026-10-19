#!/usr/bin/env bash
# Emblem badge clickability gate (Momir Basic;
# unactivatable-web.md). The frontend half of the fix: an emblem badge whose id
# has a playable select choice must be clickable, visibly playable, and reach
# GameView's click path.
#
#   bash scripts/verify-emblem-badge-clickable.sh
#
# Two layers:
#   1. Behavioural harness (real PlayerBoard under react-dom/server): the badge
#      gains player-badge-playable + role=button + tabIndex + pointer cursor
#      ONLY when playableCardIds holds the emblem's own id, and stays inert
#      otherwise.
#   2. Wiring greps (SSR markup cannot contain event handlers): the onClick and
#      keyboard path on the REAL render site (PlayerBoard.tsx), the
#      GameView.handleCardClick choice lookup that turns the click into
#      select_choice, and the CSS affordance class.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"

cat > /tmp/xmage-emblem-badge-entry.tsx <<EOF
import '/tmp/xmage-verify-stubs.mjs'
;(globalThis as any).import_meta_env_DEV = false
import '${HERE}/scripts/verify-emblem-badge-clickable.tsx'
EOF
cp scripts/verify-stubs.mjs /tmp/xmage-verify-stubs.mjs
./node_modules/.bin/rolldown /tmp/xmage-emblem-badge-entry.tsx --file /tmp/xmage-emblem-badge.mjs --format esm --platform node >/dev/null
# The store's dev-only window export references import.meta.env, which does not
# exist under Node — neutralise that one line in the throwaway bundle.
python3 - <<'PY'
p = '/tmp/xmage-emblem-badge.mjs'
s = open(p).read()
old = "if (import.meta.env.DEV) window.__gameStore = useGameStore;"
if old in s:
    open(p, 'w').write(s.replace(old, "if (globalThis.import_meta_env_DEV) globalThis.window.__gameStore = useGameStore;"))
PY
node /tmp/xmage-emblem-badge.mjs

# ── wiring on the real render sites ──
echo
echo "## wiring on the render sites"

python3 - <<'PY'
import re, sys

def body(path, marker):
    s = open(path).read()
    i = s.index(marker)
    return s[i:i + 3200]

fails = []

# 1. PlayerBoard: the emblem badge must wire onClick to onCardClick and gate on
#    the playable set; role/tabIndex make it a real control.
pb = body('src/components/PlayerBoard.tsx', 'player.emblems && player.emblems.length > 0')
checks = [
    ('emblem badge computes isPlayable from playableCardIds',
     'playableCardIds?.has(emblemId ?? \'\')' in pb),
    ('emblem badge adds the playable class conditionally',
     'player-badge-playable' in pb),
    ('emblem badge calls onCardClick with the emblem id',
     'onCardClick?.(emblemId)' in pb),
    ('emblem badge is a labelled control when playable',
     "role={isPlayable ? 'button' : undefined}" in pb and 'tabIndex={isPlayable ? 0 : undefined}' in pb),
    ('emblem badge is keyboard-activatable (Enter/Space)',
     "'Enter' || e.key === ' '" in pb and 'e.preventDefault()' in pb),
    ('emblem badge shows a pointer cursor when playable',
     "cursor: 'pointer'" in pb),
]
for name, ok in checks:
    print(('  PASS  ' if ok else '  FAIL  ') + name)
    if not ok:
        fails.append(name)

# 2. GameView: the click path must remain the choice lookup (the emblem choice
#    id is IN actionPrompt.choices, so this path is what sends sendPlayerUUID).
#    After the combat-identity refactor the lookup lives in
#    utils/combatClick.planCardClick (GameView delegates; combat choices answer
#    by UUID, non-combat choices by index — the emblem choice is 'activate', so
#    it still takes the index path). Accept either shape; assert the INTENT.
gv = body('src/components/GameView.tsx', 'const handleCardClick')
combatclick = open('src/utils/combatClick.ts').read()
inline = "actionPrompt.choices.find((c) => c.id === cardId)" in gv and 'selectChoice(choice.index)' in gv
delegated = 'planCardClick(actionPrompt, cardId, combatDeclaredIds)' in gv \
    and 'prompt.choices.find((c) => c.id === cardId)' in combatclick \
    and "return { kind: 'index', index: choice.index }" in combatclick
if inline or delegated:
    print('  PASS  GameView.handleCardClick resolves the choice by id → answer')
else:
    print('  FAIL  GameView.handleCardClick choice lookup missing')
    fails.append('gameview click path')

# 3. GameView must hand PlayerBoard the click handler (it always did — asserted
#    so a prop rename cannot silently orphan the badge).
if 'onCardClick={handleCardClick}' in open('src/components/GameView.tsx').read():
    print('  PASS  GameView passes onCardClick={handleCardClick} to PlayerBoard')
else:
    print('  FAIL  GameView no longer passes onCardClick to PlayerBoard')
    fails.append('onCardClick prop')

# 4. CSS: the playable affordance class exists in the stylesheet the page loads.
css = open('../game-renderer.css').read()
if '.player-badge-playable' in css and 'cursor: pointer' in css[css.index('.player-badge-playable'):css.index('.player-badge-playable') + 600]:
    print('  PASS  game-renderer.css defines .player-badge-playable with a pointer cursor')
else:
    print('  FAIL  .player-badge-playable missing from game-renderer.css')
    fails.append('css affordance')

# 5. explain coverage: the badge has an explanation rule (role=button puts it in
#    EXPLAIN_TARGET_SELECTOR, so a missing rule would turn the gate red once the
#    badge is on screen in a game with an emblem).
ex = open('src/hints/explain.ts').read()
if "player-badge-playable" in ex:
    print('  PASS  explain.ts has a rule for player-badge-playable')
else:
    print('  FAIL  explain.ts has no rule for player-badge-playable (gate would fail live)')
    fails.append('explain rule')

if fails:
    print(f"\nWIRING GATE FAILED: {len(fails)} check(s)")
    sys.exit(1)
print("\nWIRING GATE PASSED")
PY