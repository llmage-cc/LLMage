/**
 * Standalone behavioural check for the prompt-lifecycle / click-handling fixes.
 *
 * Run with:  flock /tmp/xmage-npm.lock npx tsx scripts/verify-prompt-lifecycle.ts
 *
 * Not part of the app build (scripts/ is excluded from tsconfig "include": ["src"]).
 * Every case drives the REAL store through the REAL message handler, so the
 * assertions cover the shipped code path, not a re-implementation.
 */
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import type { GameState, Player, ServerMessage } from '../src/types'

// ── minimal but type-complete fixtures ──
const player = (name: string, isMe: boolean, prio: boolean): Player => ({
  name, playerId: name, life: 20, isMe, libraryCount: 40, handCount: 7,
  isActive: false, hasPriority: prio, hasLeft: false, timerActive: false,
  priorityTimeLeftSecs: 0, isMonarch: false, hasInitiative: false, manaPool: null,
  passedTurn: false, passedUntilEndOfTurn: false, passedUntilNextMain: false,
  passedAllTurns: false, passedUntilStackResolved: false, passedUntilEndStepBeforeMyTurn: false,
  counters: [], commanders: [], battlefield: [], hand: [], graveyard: [], exile: [],
  statesSavedSize: 1,
})

const state = (priorityPlayer: string, extra: Partial<GameState> = {}): GameState => ({
  status: 'playing', turn: 3, phase: null, step: null,
  activePlayer: 'QA-A', priorityPlayer,
  stack: [], combat: [], players: [player('QA-A', true, priorityPlayer === 'QA-A'), player('QA-B', false, priorityPlayer === 'QA-B')],
  llmDebug: [], revealed: [], lookedAt: [], stateSeq: 1,
  ...extra,
})

// record what actually goes on the wire
const sent: string[] = []
useGameStore.getState().setWebSocket({
  send: (m: unknown) => { sent.push((m as { type: string }).type) },
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  disconnect: () => {},
} as never)

const reset = () => {
  sent.length = 0
  useGameStore.setState({ actionPrompt: null, currentActionPrompt: null, eventLog: [], gameState: null })
}

// ── helpers mirroring what the UI does ──
const prompt = (over: Record<string, unknown>) => {
  handleMessage({ type: 'action_prompt', action_type: 'select', message: 'Play spells and abilities', choices: [], ...over } as ServerMessage, useGameStore.getState())
}
const update = (priorityPlayer: string, extra: Partial<GameState> = {}) =>
  handleMessage({ type: 'game_update', state: state(priorityPlayer, extra) } as ServerMessage, useGameStore.getState())

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

// ─────────────────────────────────────────────────────────────────────────────
section('F1 — resolved prompt stops accepting clicks')
{
  reset()
  prompt({ action_type: 'boolean', action_type_alias: undefined, choices: undefined })
  useGameStore.setState({ actionPrompt: { type: 'boolean', message: 'Mulligan?', context: 'mulligan', rawMessage: 'Mulligan?', yesLabel: 'Yes', noLabel: 'No' } })
  update('QA-A')                                   // still our priority -> prompt stays
  check('live prompt survives a state that still names me', useGameStore.getState().actionPrompt != null)
  useGameStore.getState().booleanChoice(false)     // answer it
  check('first answer goes out', sent.join(',') === 'boolean_choice', sent.join(','))
  update('QA-B')                                   // server moved on
  check('answered prompt is cleared when priority moves away', useGameStore.getState().actionPrompt == null)
  const before = sent.length
  useGameStore.getState().booleanChoice(true)      // stale click
  check('stale click after resolution sends nothing', sent.length === before, sent.join(','))
}

section('F1 — genuinely live prompts keep working (no regression)')
{
  reset()
  update('QA-A')
  prompt({ action_type: 'select', message: 'Declare attackers', choices: [{ index: 0, id: 'c1', name: 'Elf', action: 'attack' }] })
  useGameStore.getState().selectChoice(0)
  check('first answer of a live prompt is sent', sent.join(',') === 'select_choice', sent.join(','))
  // combat re-prompt: server sends a NEW prompt for the next attacker
  prompt({ action_type: 'select', message: 'Declare attackers', choices: [{ index: 0, id: 'c1', name: 'Elf', action: 'attack' }, { index: 1, id: 'c2', name: 'Bear', action: 'attack' }] })
  useGameStore.getState().selectChoice(1)
  check('the next prompt accepts its answer', sent.join(',') === 'select_choice,select_choice', sent.join(','))
  // a state push that still names me must NOT clear a prompt
  prompt({ action_type: 'select', message: 'Priority', choices: [] })
  update('QA-A')
  check('state naming me does not clear a live prompt', useGameStore.getState().actionPrompt != null)
}

section('F2 — repeat activation of the same choice is dropped')
{
  reset()
  update('QA-A')
  prompt({ action_type: 'select', message: 'Priority', choices: [{ index: 0, id: 'card-1', name: 'Plains', action: 'land' }] })
  useGameStore.getState().selectChoice(0)
  useGameStore.getState().selectChoice(0) // double click
  check('double-click sends once', sent.join(',') === 'select_choice', sent.join(','))
  useGameStore.getState().selectChoice(1) // a DIFFERENT choice on the same prompt is fine
  check('a different choice still goes out', sent.join(',') === 'select_choice,select_choice', sent.join(','))
  // same card on the NEXT prompt must be allowed again
  prompt({ action_type: 'select', message: 'Priority 2', choices: [{ index: 0, id: 'card-1', name: 'Plains', action: 'land' }] })
  useGameStore.getState().selectChoice(0)
  check('same choice allowed on the next prompt', sent.join(',') === 'select_choice,select_choice,select_choice', sent.join(','))
  // and a tap_mana repeat is dropped, while a different land works
  reset()
  prompt({ action_type: 'play_mana', message: 'Pay {G}', mana_options: [], remaining_cost: '{G}', auto_pay_available: true, special_available: false })
  useGameStore.getState().tapMana('land-1')
  useGameStore.getState().tapMana('land-1')
  check('same land tapped once', sent.join(',') === 'tap_mana', sent.join(','))
  useGameStore.getState().tapMana('land-2')
  check('a different land still taps', sent.join(',') === 'tap_mana,tap_mana', sent.join(','))
}

section('F3 — priority / waiting state drives the rail (store-side facts)')
{
  reset()
  update('QA-A')
  check('priorityPlayer names me', useGameStore.getState().gameState?.priorityPlayer === 'QA-A')
  useGameStore.getState().passPriority() // the wedge: no prompt, my priority
  check('pass works with no prompt while my priority', sent.join(',') === 'pass_priority', sent.join(','))
  reset()
  update('QA-B')
  check('priorityPlayer names the opponent', useGameStore.getState().gameState?.priorityPlayer === 'QA-B')
  useGameStore.getState().passPriority()
  check('a repeat pass is not re-sent while the server has not moved', sent.join(',') === '', sent.join(','))
  // next priority window (state names me again) must accept a pass again
  update('QA-A')
  useGameStore.getState().passPriority()
  check('the next priority window accepts a pass', sent.join(',') === 'pass_priority', sent.join(','))
}

section('F3 — rejoin wedge: no prompt, my priority, controls must be live')
{
  // The F-A22 repro shaped exactly: rejoin lands, a prompt was answered before
  // the rejoin, then the server says it is my priority with no prompt rendered.
  reset()
  update('QA-A')
  prompt({ action_type: 'select', message: 'Priority', choices: [{ index: 0, id: 'c1', name: 'Plains', action: 'land' }] })
  useGameStore.getState().selectChoice(0)
  update('QA-B')                       // server moved on -> prompt resolved, refuses clicks
  handleMessage({ type: 'game_init', state: state('QA-A') } as ServerMessage, useGameStore.getState())
  useGameStore.getState().passPriority()
  check('rejoin + my priority: Pass Priority still works', sent.slice(-1)[0] === 'pass_priority', sent.join(','))
  // and an ordinary game_update with no prompt + my priority also clears the guard
  reset()
  update('QA-B')
  useGameStore.getState().passPriority()
  update('QA-A')
  useGameStore.getState().passPriority()
  check('a new priority window after the opponent re-arms the pass', sent.join(',') === 'pass_priority', sent.join(','))
}

section('F1 — answered prompt survives while the server is still processing')
{
  reset()
  update('QA-A')
  prompt({ action_type: 'target', message: 'Choose a target', targets: [{ id: 't1', name: 'Bear', type: 'creature' }], required: true, target_count: 1, min: 1, max: 1 })
  useGameStore.getState().selectTarget('t1')
  update('QA-A') // state push arrives before the server consumed the answer
  check('answered prompt is not cleared while priority is still mine', useGameStore.getState().actionPrompt != null)
  check('the answer was sent once', sent.filter(s => s === 'select_target').length === 1, sent.join(','))
}

section('F1 — an UNANSWERED live prompt survives a state push naming the opponent')
{
  // Observed live: a client held an unanswered GAME_SELECT while state pushes
  // naming the opponent arrived (the opponent's own pass produces them). The
  // engine only reassigns priorityPlayerId when the NEXT player is asked, so
  // these pushes are not evidence our prompt resolved. Clearing here would
  // delete a live prompt.
  reset()
  update('QA-A')
  prompt({ action_type: 'select', message: 'Priority', choices: [{ index: 0, id: 'c1', name: 'Plains', action: 'land' }] })
  update('QA-B')
  check('unanswered prompt is kept when a push names the opponent', useGameStore.getState().actionPrompt != null)
  useGameStore.getState().selectChoice(0)
  check('and it still accepts its answer', sent.join(',') === 'select_choice', sent.join(','))
}

section('F4 — undo gating inputs are present in state')
{
  reset()
  update('QA-A', { stack: [] })
  const me = useGameStore.getState().gameState?.players.find(p => p.isMe)
  check('statesSavedSize is available to gate Undo', me?.statesSavedSize === 1)
  update('QA-A', { stack: [{ id: 's', name: 'Spell', imageUrl: '', typeLine: 'Instant', manaCost: '{U}', rules: [] }] })
  check('stack non-empty is observable for the gate', useGameStore.getState().gameState?.stack.length === 1)
}

section('F5 — game log resets for a new game, survives a rejoin')
{
  reset()
  const ev = (message: string): ServerMessage => ({ type: 'event', event: { seq: 1, type: 'action', message } })
  handleMessage({ type: 'start_game', gameId: 'GAME-1' } as ServerMessage, useGameStore.getState())
  handleMessage(ev('QA-B has won the game'), useGameStore.getState())
  check('game 1 logs its events', useGameStore.getState().eventLog.length === 1)
  handleMessage({ type: 'start_game', gameId: 'GAME-2' } as ServerMessage, useGameStore.getState())
  handleMessage(ev('QA-A draws seven cards'), useGameStore.getState())
  check('new game starts with a clean log', useGameStore.getState().eventLog.length === 1 && useGameStore.getState().eventLog[0]!.message === 'QA-A draws seven cards',
    JSON.stringify(useGameStore.getState().eventLog.map(e => e.message)))
  handleMessage(ev('QA-A plays Forest'), useGameStore.getState())
  check('the new game keeps logging', useGameStore.getState().eventLog.length === 2)
  // rejoin of the SAME game must NOT wipe the log
  handleMessage({ type: 'start_game', gameId: 'GAME-2' } as ServerMessage, useGameStore.getState())
  handleMessage(ev('QA-A has reconnected'), useGameStore.getState())
  check('rejoin keeps the existing log', useGameStore.getState().eventLog.length === 3,
    JSON.stringify(useGameStore.getState().eventLog.map(e => e.message)))
}

section('F5 — page-reload rejoin (fresh module state) does not harm')
{
  // A reload mid-game re-announces the SAME gameId through `start_game`, but the
  // module-level game key is fresh, so it counts as "new". The log is empty at
  // that point anyway (the reconnect-wipes-history defect is a separate, unfixed
  // item), so the armed reset is harmless — assert it does not throw or drop the
  // events that DO arrive after the rejoin.
  reset()
  handleMessage({ type: 'start_game', gameId: 'GAME-X' } as ServerMessage, useGameStore.getState())
  handleMessage({ type: 'event', event: { seq: 1, type: 'action', message: 'QA-A has rejoined the game' } } as ServerMessage, useGameStore.getState())
  check('post-reload rejoin logs normally', useGameStore.getState().eventLog.length === 1,
    JSON.stringify(useGameStore.getState().eventLog.map(e => e.message)))
}

section('F6 — choose_choice reads the wire `label` field')
{
  reset()
  // exact shape captured live from the proxy (WebProxyClient.handleGameChooseChoice)
  handleMessage({
    type: 'action_prompt', action_type: 'choose_choice', message: 'Choose a color',
    options: [{ index: 0, key: 'W', label: 'White' }, { index: 1, key: 'G', label: 'Green' }],
  } as unknown as ServerMessage, useGameStore.getState())
  const p = useGameStore.getState().actionPrompt
  const names = p?.type === 'choose_choice' ? p.choices.map(c => c.name) : []
  check('labels render as the option text', names.join('|') === 'White|Green', names.join('|'))
  // modal mode prompts arrive via the choose_ability path, which uses `name`
  reset()
  handleMessage({
    type: 'action_prompt', action_type: 'choose_ability', message: 'Choose mode',
    abilities: [{ index: 0, id: 'm1', name: '1. Boros Charm deals 4 damage to target player or planeswalker.' }],
  } as unknown as ServerMessage, useGameStore.getState())
  const q = useGameStore.getState().actionPrompt
  check('modal mode (choose_ability path) unaffected', q?.type === 'choose_ability' && q.choices[0]!.name.startsWith('1. Boros Charm'))
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
