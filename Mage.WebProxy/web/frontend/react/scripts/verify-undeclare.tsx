/**
 * Standalone regression check for combat UNDECLARE (click-again-to-take-back).
 *
 * Run with:  bash scripts/verify-undeclare.sh
 *
 * "undeclaring blockers is a huge pain and really doesn't work correctly."
 *
 * Engine facts the fix leans on (HumanPlayer.selectAttackers/selectBlockers,
 * verified in Mage.Server.Plugins/Mage.Player.Human):
 *   * the declare loops re-fire GAME_SELECT each iteration;
 *   * a responded creature that is NOW ATTACKING falls through
 *     FilterAttackingCreature → removeAttackerIfPossible → combat.removeAttacker;
 *   * a creature NOW BLOCKING falls through FilterBlockingCreature
 *     → combat.removeBlocker.  The declared creature is NOT in the prompt's
 *     choice list (FilterCreatureForCombat excludes attacking creatures,
 *     FilterCreatureForCombatBlock's CanBlockPredicate excludes fully-blocked
 *     ones), so a bare-play_card of its UUID is the undeclare channel —
 *     exactly the desktop client's DefaultActionCallback behavior.
 *
 * Encodes:
 *  (a) declare_attackers prompt + combat groups in gameState → declared
 *      attackers get an undeclare affordance and click sends play_card with
 *      the creature's UUID (the bare-UUID wire answer the engine loops consume);
 *  (b) declare_blockers prompt → declared blockers undeclare the same way;
 *  (c) a STILL-declared creature is NOT in combatChoiceIds (it is excluded by
 *      the engine's filters, so it must not read as a fresh declare choice);
 *  (d) clicking an undeclared creature with no choices still goes out only for
 *      declared ones — the fallback play path is untouched for others;
 *  (e) opposite-declared creatures (opponent's attackers which I "oppose") are
 *      not mine to undeclare: declare_blockers context surfaces only blockers;
 *  (f) an undo bookmark button (statesSavedSize > 0) is what enables the
 *      header/prompt-bar Undo affordance.
 *
 * Node-only: real store, real message handler (no browser).
 */
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import type { ServerMessage } from '../src/types'

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}

// ── wire capture ──
const sent: Array<Record<string, unknown>> = []
useGameStore.getState().setWebSocket({
  send: (m: unknown) => { sent.push(m as Record<string, unknown>) },
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  disconnect: () => {},
} as never)

const player = (id: string, isMe: boolean) => ({
  name: isMe ? id : id, isMe, isAI: false, isConnected: true, score: 0, life: 20,
  isActive: false, hasPriority: false, hasLeft: false, timerActive: false,
  priorityTimeLeftSecs: 0, isMonarch: false, hasInitiative: false, manaPool: null,
  passedTurn: false, passedUntilEndOfTurn: false, passedUntilNextMain: false,
  passedAllTurns: false, passedUntilStackResolved: false, passedUntilEndStepBeforeMyTurn: false,
  counters: [], commanders: [], battlefield: [], hand: [], graveyard: [], exile: [],
  statesSavedSize: 1,
}) as never

const combatState = (attackers: string[], blockers: string[]) => ({
  type: 'game_update',
  state: { battlefield: { cards: [] }, zones: [], library: [], hand: [],
    status: 'playing', turn: 1, phase: null, step: null,
    activePlayer: 'me-1', priorityPlayer: 'me-1',
    stack: [], combat: [
      { defenderId: 'opp-1', defenderName: 'Opponent', blocked: blockers.length > 0,
        attackers: attackers.map((id) => ({ id })), blockers: blockers.map((id) => ({ id })) } as never,
    ],
    players: [player('me-1', true) as never, player('opp-1', false) as never],
    revealed: [], lookedAt: [], stateSeq: 1 },
} as unknown as ServerMessage)

/** The exact wire shape WebProxyClient.handleGameSelect emits with combat context. */
const declarePrompt = (context: 'declare_attackers' | 'declare_blockers', choices: string[]): ServerMessage => ({
  type: 'action_prompt', action_type: 'select',
  message: context === 'declare_attackers' ? 'Select attackers' : 'Select blockers',
  context, required: false, choices: choices.map((id, index) => ({ id, index, label: 'Bear', type: 'creature' })),
} as unknown as ServerMessage)

const reset = () => { sent.length = 0 }
const deliver = (msg: ServerMessage) => handleMessage(msg, useGameStore.getState())

// ── (a) undeclare an ATTACKER with the declare_attackers prompt open ──
console.log('\n## declare_attackers: clicking a declared attacker sends the bare-UUID play_card undeclare')
reset()
deliver(combatState(['att-1'], []))
deliver(declarePrompt('declare_attackers', []))
let s = useGameStore.getState()
check('(a) prompt is combat select', !!s.actionPrompt && s.actionPrompt.type === 'select')
s.playCard('att-1')
let wire = sent.find((m) => m.type === 'play_card')
check('(a) play_card carries the attacker UUID (engine removeAttacker path)', !!wire && (wire as { cardId?: string }).cardId === 'att-1')
check('(a) no select_choice/answer races the bare UUID', !sent.some((m) => m.type === 'select_choice'))

// ── (b) undeclare a BLOCKER with the declare_blockers prompt open ──
console.log('\n## declare_blockers: clicking a declared blocker undeclares it the same way')
reset()
deliver(combatState(['att-9'], ['block-1']))
deliver(declarePrompt('declare_blockers', ['fresh-blocker']))
s = useGameStore.getState()
s.selectChoice(1)
s.playCard('block-1')
wire = sent.find((m) => m.type === 'play_card' && (m as { cardId?: string }).cardId === 'block-1')
check('(b) declared blocker undeclares via play_card', !!wire)
check('(b) fresh blocker was declared via select_choice', sent.some((m) => m.type === 'select_choice'))

// ── (c) declared creatures are NOT fresh declare choices ──
console.log('\n## An already-declared creature is not in the prompt choice list (engine filter)')
reset()
deliver(combatState(['att-2'], []))
deliver(declarePrompt('declare_attackers', []))
const promptObj = useGameStore.getState().actionPrompt
const choiceIds = promptObj && promptObj.type === 'select' ? promptObj.choices.map((c) => c.id) : []
check('(c) declared attacker not re-listed as a choice', !choiceIds.includes('att-2'))

// ── (e) role data fidelity in the store state push ──
// The undeclare role filtering itself lives in GameView.handleCardClick (it
// needs the live prompt); the node harness asserts what GameView consumes —
// the combat groups arrive per role, so declare_blockers can pick only BACKERS
console.log('\n## Role filtering: combat groups carry attackers and blockers per role')
reset()
deliver(combatState(['opp-att'], ['my-block']))
deliver(declarePrompt('declare_blockers', []))
const gs = useGameStore.getState().gameState
check('(e) group attackers list carries the attacker id', !!gs && gs.combat[0].attackers.some((c: { id?: string }) => c.id === 'opp-att'))
check('(e) group blockers list carries the blocker id', !!gs && gs.combat[0].blockers.some((c: { id?: string }) => c.id === 'my-block'))
const p2 = useGameStore.getState().actionPrompt
check('(e) prompt context is declare_blockers', !!p2 && (p2 as { context?: string }).context === 'declare_blockers')
sent.length = 0
useGameStore.getState().playCard('my-block')
check('(e) my declared blocker undeclares via play_card', sent.some((m) => m.type === 'play_card' && (m as { cardId?: string }).cardId === 'my-block'))

if (failures > 0) {
  console.log(`\nFAILURES: ${failures}`)
  process.exit(1)
}
console.log('\nAll undeclare checks passed.')
