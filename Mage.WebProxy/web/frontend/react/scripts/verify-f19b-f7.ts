/**
 * Standalone behavioural check for the trigger-window and skip fixes.
 *
 * Run with:  bash scripts/verify-f19b-f7.sh
 *
 * Drives the REAL store through the REAL message handler (same pattern as
 * verify-prompt-lifecycle.ts), plus the pure skip-mapping util the rail uses.
 * Not part of the app build (scripts/ is outside tsconfig "include": ["src"]).
 *
 * Observed exchange (proxy log, 2026-09-16):
 *   15:09:26.383 GAME_SELECT (trigger on stack, 0 playables)  -> "Nothing to play"
 *   15:11:53.436 browser pass_priority                        -> the pass that resolves it
 *   15:11:55.894 GAME_ASK  "Pay {E}{E}{E}?"
 *   15:12:35.997 boolean_choice true
 * The window in between had NO state push, so the old rail kept offering a
 * Pass Priority button whose every later click the epoch guard silently dropped.
 *
 * Evidence shape: engine flags passed* reach the client; a FIRED skip clears
 * its flag, so the highlight must clear and the re-click must ARM (not cancel).
 */
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import { armedSkipButton, skipClickIntent, SKIP_WIRE_TYPE, SKIP_BUTTON_IDS } from '../src/utils/skipButtons'
import type { GameState, Player, ServerMessage } from '../src/types'

// ── fixtures ──
const SKIP_FLAGS_OFF = {
  passedTurn: false,
  passedUntilEndOfTurn: false,
  passedUntilNextMain: false,
  passedAllTurns: false,
  passedUntilStackResolved: false,
  passedUntilEndStepBeforeMyTurn: false,
} as const

const player = (name: string, isMe: boolean, prio: boolean, skip: Partial<typeof SKIP_FLAGS_OFF> = {}): Player => ({
  name, playerId: name, life: 20, isMe, libraryCount: 40, handCount: 7,
  isActive: false, hasPriority: prio, hasLeft: false, timerActive: false,
  priorityTimeLeftSecs: 0, isMonarch: false, hasInitiative: false, manaPool: null,
  counters: [], commanders: [], battlefield: [], hand: [], graveyard: [], exile: [],
  statesSavedSize: 1,
  ...SKIP_FLAGS_OFF, ...skip,
})

const state = (priorityPlayer: string, mySkip: Partial<typeof SKIP_FLAGS_OFF> = {}, extra: Partial<GameState> = {}): GameState => ({
  status: 'playing', turn: 5, phase: null, step: null,
  activePlayer: 'QA-A', priorityPlayer,
  stack: [], combat: [],
  players: [player('QA-A', true, priorityPlayer === 'QA-A', mySkip), player('QA-B', false, priorityPlayer === 'QA-B')],
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
  useGameStore.setState({ actionPrompt: null, currentActionPrompt: null, passPending: false, eventLog: [], gameState: null })
}

const prompt = (over: Record<string, unknown>) => {
  handleMessage({ type: 'action_prompt', action_type: 'select', message: 'Play spells and abilities', choices: [], ...over } as ServerMessage, useGameStore.getState())
}
const update = (priorityPlayer: string, mySkip: Partial<typeof SKIP_FLAGS_OFF> = {}) =>
  handleMessage({ type: 'game_update', state: state(priorityPlayer, mySkip) } as ServerMessage, useGameStore.getState())

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

// ─────────────────────────────────────────────────────────────────────────────
section('the highlight is derived from the engine flag, not local state')
{
  // What the server reports for each armed skip maps to exactly one button.
  for (const [flag, id] of [
    ['passedTurn', 'skip-turn'], ['passedUntilEndOfTurn', 'skip-eot'],
    ['passedUntilNextMain', 'skip-main'], ['passedAllTurns', 'skip-my-turn'],
    ['passedUntilStackResolved', 'skip-stack'], ['passedUntilEndStepBeforeMyTurn', 'skip-end-step'],
  ] as const) {
    const armed = armedSkipButton(player('QA-A', true, true, { [flag]: true }))
    check(`server flag ${flag} highlights ${id}`, armed === id, String(armed))
  }
  check('no flags -> no highlight (this is the state AFTER a skip fires)', armedSkipButton(player('QA-A', true, true)) === null)
  check('no player (spectator) -> no highlight', armedSkipButton(null) === null)
  check('every button id is wired to a flag and a wire type',
    SKIP_BUTTON_IDS.every((id) => SKIP_WIRE_TYPE[id] !== undefined))
}

section('a fired skip re-arms on re-click instead of sending cancel_skip')
{
  // armed -> the click is a real cancel of a live skip
  check('click on the ARMED skip cancels it', skipClickIntent('skip-main', 'skip-main') === 'cancel')
  // fired (server reports nothing armed) -> the click must ARM, not cancel:
  // the old local-state rail sent cancel_skip here (the no-op).
  check('click on a FIRED skip re-arms (no cancel_skip)', skipClickIntent(null, 'skip-main') === 'arm')
  check('click on a DIFFERENT skip while one is armed re-arms that one',
    skipClickIntent('skip-eot', 'skip-main') === 'arm')
  // and the store-level consequences of each intent
  reset()
  update('QA-A', { passedUntilNextMain: true })
  useGameStore.getState().cancelSkip()
  check('cancel path sends cancel_skip', sent.join(',') === 'cancel_skip', sent.join(','))
  reset()
  update('QA-A') // nothing armed any more
  useGameStore.getState().skipPriority(SKIP_WIRE_TYPE['skip-main'])
  check('re-arm path sends skip_priority', sent.join(',') === 'skip_priority', sent.join(','))
}

section('server flag present in state, and a push that clears it clears the highlight')
{
  reset()
  update('QA-A', { passedUntilNextMain: true })
  let me = useGameStore.getState().gameState?.players.find((p) => p.isMe) ?? null
  check('armed skip is visible in store state', armedSkipButton(me) === 'skip-main')
  update('QA-A') // the skip fired: engine cleared the flag on the next push
  me = useGameStore.getState().gameState?.players.find((p) => p.isMe) ?? null
  check('after the push clears the flag there is no highlight', armedSkipButton(me) === null)
}

// ─────────────────────────────────────────────────────────────────────────────
section('the trigger window: pass is honest instead of a dead button')
{
  reset()
  // declare-attackers finished; the trigger is on the stack and we hold priority.
  update('QA-A')
  // 15:09:26.383 GAME_SELECT with 0 playables = the trigger's priority window
  prompt({ action_type: 'select', message: 'Play spells and abilities', choices: [] })
  check('window opens with a live Pass Priority (no passPending)', useGameStore.getState().passPending === false)
  const ok = useGameStore.getState().passPriority()
  check('passPriority() does not throw and the pass goes out', ok === undefined && sent.join(',') === 'pass_priority', sent.join(','))
  check('prompt cleared locally after a pass', useGameStore.getState().actionPrompt == null)
  check('passPending is set, so the rail can say "Pass sent — waiting"', useGameStore.getState().passPending === true)
  // the 2.5-minute window: further clicks must not silently vanish
  const before = sent.length
  useGameStore.getState().passPriority()
  useGameStore.getState().passPriority()
  check('repeat clicks send nothing (epoch guard) — the rail must say so', sent.length === before, sent.join(','))
  check('...and the rail is still in the honest "waiting" state', useGameStore.getState().passPending === true)
}

section('the ask is delivered and answerable after the pass (full sequence)')
{
  reset()
  update('QA-A')
  prompt({ action_type: 'select', message: 'Play spells and abilities', choices: [] }) // trigger on stack
  useGameStore.getState().passPriority()
  // 15:11:55.698 the trigger resolves and the engine asks
  update('QA-A')
  check('a state push clears passPending (controls live again)', useGameStore.getState().passPending === false)
  handleMessage({
    type: 'action_prompt', action_type: 'boolean', message: 'Pay {E}{E}{E}?',
    yes_label: 'Yes', no_label: 'No', context: 'yes_no',
  } as unknown as ServerMessage, useGameStore.getState())
  const ap = useGameStore.getState().actionPrompt
  check('GAME_ASK renders as a boolean prompt', ap?.type === 'boolean', String(ap?.type))
  check('ask text is the pay-cost question', ap?.type === 'boolean' && ap.message === 'Pay {E}{E}{E}?')
  useGameStore.getState().booleanChoice(true)
  check('answering Yes sends boolean_choice', sent.join(',') === 'pass_priority,boolean_choice', sent.join(','))
}

section('the NEXT priority window still accepts a pass (no regression)')
{
  reset()
  update('QA-A') // fresh state push: new epoch, nothing pending
  useGameStore.getState().passPriority()
  check('a new window accepts a pass again', sent.join(',') === 'pass_priority', sent.join(','))
}

section('passPending cannot leak across a rejoin (game_init)')
{
  reset()
  update('QA-A')
  useGameStore.getState().passPriority()
  check('passPending set before the rejoin', useGameStore.getState().passPending === true)
  handleMessage({ type: 'game_init', state: state('QA-A') } as ServerMessage, useGameStore.getState())
  check('game_init clears passPending', useGameStore.getState().passPending === false)
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
