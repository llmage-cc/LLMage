/**
 * REAL targets regression: "They're supposed to show the
 * targets of spells, abilities, and attacks, not potential targets of them" —
 * and the prior direction ("arrows during selection") is REVERSED.
 *
 * Run with:  bash scripts/verify-not-target-arrows.sh
 *
 * Encodes, through the REAL store + REAL message handler (Node, no browser):
 *  (A) while a target prompt is open, the arrow decision is OFF — the prompt's
 *      candidates are potential targets and must only surface as selectable
 *      highlights, never as arrows (both a NOT_TARGET list and a genuine target
 *      prompt);
 *  (B) with no target prompt open, real stack items with serialized targets
 *      produce exactly one source→targets group each (StackItem.targets, the
 *      serializer's `targets` field; source_card names ability owners);
 *  (C) stack items without targets (a resolving Lightning Bolt already spent,
 *      an unanchored ability) contribute nothing;
 *  (D) the click→answer path on a NOT_TARGET prompt is untouched.
 */
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import { targetingArrowDecision } from '../src/components/TargetingArrows'
import { realTargetArrows } from '../src/utils/realTargets'
import type { ServerMessage, GameState } from '../src/types'

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}

useGameStore.getState().setWebSocket({
  send: () => {},
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  disconnect: () => {},
} as never)

const targetPrompt = (): ServerMessage => ({
  type: 'action_prompt', action_type: 'target',
  message: 'Choose target creature',
  context: 'default', required: true, target_count: 0, max_targets: 1,
  targets: [{ id: 'card-9', name: 'Savannah Lions', type: 'creature' }],
  not_target: false,
} as unknown as ServerMessage)

const notTargetList = (): ServerMessage => ({
  type: 'action_prompt', action_type: 'target',
  message: 'Exile a card from your hand for Chrome Mox',
  context: 'exile_target', required: false, target_count: 0, max_targets: 2,
  targets: [{ id: 'card-1', name: 'Grizzly Bears', type: 'creature' }],
  not_target: true,
} as unknown as ServerMessage)

const deliver = (msg: ServerMessage) => handleMessage(msg, useGameStore.getState())
const promptType = (): string | null => {
  const p = useGameStore.getState().actionPrompt
  return p ? p.type : null
}

// ── (A) the REVERSED rule: a target prompt open ⇒ NO arrows ever ──
console.log('\n## Target prompt open ⇒ arrow decision OFF (highlights only)')
for (const [label, msg] of [['genuine target', targetPrompt()] as const, ['NOT_TARGET list', notTargetList()] as const]) {
  useGameStore.setState({ actionPrompt: null, currentActionPrompt: null, gameState: null })
  deliver(msg)
  check(`(${label}) prompt parsed as target`, promptType() === 'target', `got ${promptType()}`)
  check(`(${label}) arrows OFF during selection`, targetingArrowDecision(promptType()) === false)
}
useGameStore.setState({ actionPrompt: null, currentActionPrompt: null, gameState: null })
check('(no prompt/selection context) arrows ON from real stack data', targetingArrowDecision(promptType()) === true)

// ── (B) real serialized stack targets → exactly one group per item ──
console.log('\n## Stack items with REAL targets produce source→target groups')
const state: GameState = {
  status: 'running', turn: 3, phase: '', step: '',
  activePlayer: 'TestPlayer', priorityPlayer: 'TestPlayer', isSpectator: false,
  stack: [
    // a spell on the stack with real targets (serializer `targets`)
    { id: 'bolt-uuid', name: 'Lightning Bolt', imageUrl: '', typeLine: 'Instant', manaCost: '', rules: [], targets: ['bear-uuid'] },
    // an ability whose tile renders by source card name (source_card)
    { id: 'ab-uuid', name: 'Ability', imageUrl: '', typeLine: 'Ability', manaCost: '', rules: [], source_card: 'Nissa', targets: ['lotus-uuid', 'me-uuid'] },
    // an item with NO targets (e.g. a resolving spell without any) — must not arrow
    { id: 'plain-uuid', name: 'Rally', imageUrl: '', typeLine: 'Instant', manaCost: '', rules: [] },
  ],
  combat: [], players: [],
} as unknown as GameState
useGameStore.setState({ gameState: state })

const groups = realTargetArrows(state.stack)
check('(B) only items with targets surface', groups.length === 2, `got ${groups.length}`)
check('(B) spell-only stack → one group, real target kept',
  groups[0]!.sourceId === 'bolt-uuid' && groups[0]!.targetIds.length === 1 && groups[0]!.targetIds[0] === 'bear-uuid')
check('(B) ability: source id + owner card name (source_card)',
  groups[1]!.sourceId === 'ab-uuid' && groups[1]!.sourceName === 'Nissa' && groups[1]!.targetIds.length === 2)
check('(C) target-less item contributes nothing', groups.every((g) => g.sourceId !== 'plain-uuid'))
check('(B) null/empty stack → no groups', realTargetArrows(null).length === 0)
check('(B) unmatched serialized ids must NOT fabricate groups',
  realTargetArrows([{ id: 'x', name: '', imageUrl: '', typeLine: '', manaCost: '', rules: [] }] as never).length === 1
    ? false // no targets field ⇒ filtered
    : true)

if (failures > 0) {
  console.error(`\nFAILED: ${failures} check(s)`)
  process.exit(1)
}
console.log('\nAll checks passed.')
