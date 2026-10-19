/**
 * Standalone behavioural check for the mulligan local-hide gate
 * ("Mulligan question doesn't go away until opponents answer").
 *
 * Run with:  bash scripts/verify-mulligan-hide.sh
 *
 * Not part of the app build (scripts/ is excluded from tsconfig "include": ["src"]).
 * Every case drives the REAL store through the REAL message handler
 * (handleMessage -> setActionPrompt / setGameState), so the assertions cover the
 * shipped code path, not a re-implementation.
 *
 * Wire shape proven against the server stack:
 *  - WebProxyClient.handleGameAsk writes `context: "mulligan"` for every
 *    keep/hand wording (chooseMulligan fires GAME_ASK per player per round);
 *  - Mulligan.executeMulliganPhase loops over the players BEFORE any state is
 *    sent, so after our boolean_choice(false) the server is silent until every
 *    opponent has answered — the prompt must be withdrawn LOCALLY, and the rail
 *    falls back to its "Waiting for <player>…"/priority state.
 *  - Each later question arrives as a FRESH action_prompt, and setActionPrompt
 *    supersedes whatever was outstanding (F1), so the hide cannot swallow the
 *    next keep/mulligan question (or any later prompt).
 */
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import type { GameState, Player, ServerMessage } from '../src/types'

// ── minimal but type-complete fixtures (same shape as verify-prompt-lifecycle) ──
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
  status: 'playing', turn: 1, phase: null, step: null,
  activePlayer: 'QA-A', priorityPlayer,
  // The pre-game mulligan leaves priority unset ("Priority: —" in live
  // transcripts) while the mulligan prompt is up.
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
  useGameStore.setState({ actionPrompt: null, currentActionPrompt: null, eventLog: [], gameState: null, passPending: false })
}

// exact boolean payload the proxy writes (WebProxyClient.handleGameAsk) —
// labels are the engine's option texts now (HumanPlayer.chooseMulligan:403-405),
// no longer hardcoded "Yes"/"No"
const mulliganAsk = (message: string): ServerMessage =>
  ({ type: 'action_prompt', action_type: 'boolean', message, context: 'mulligan', yes_label: 'Mulligan', no_label: 'Keep' }) as unknown as ServerMessage
const yesNoAsk = (message: string): ServerMessage =>
  ({ type: 'action_prompt', action_type: 'boolean', message, context: 'yes_no', yes_label: 'Heads', no_label: 'Tails' }) as unknown as ServerMessage

const update = (priorityPlayer: string, extra: Partial<GameState> = {}) =>
  handleMessage({ type: 'game_update', state: state(priorityPlayer, extra) } as ServerMessage, useGameStore.getState())

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

// ─────────────────────────────────────────────────────────────────────────────
section('mulligan boolean answer hides the prompt locally, wire got exactly one boolean_choice(false)')
{
  reset()
  handleMessage(mulliganAsk('Mulligan down to 6 cards?'), useGameStore.getState())
  const p = useGameStore.getState().actionPrompt
  check('mulligan prompt renders as a boolean', p?.type === 'boolean' && p.context === 'mulligan', JSON.stringify(p))
  useGameStore.getState().booleanChoice(false)
  check('exactly one boolean_choice(false) went out', sent.filter(s => s === 'boolean_choice').length === 1, sent.join(','))
  check('prompt is withdrawn locally after the answer', useGameStore.getState().actionPrompt == null)
  check('currentActionPrompt is withdrawn too', useGameStore.getState().currentActionPrompt == null)
}

section('a subsequent state push does NOT resurrect the hidden mulligan prompt')
{
  reset()
  // Pre-game mulligan shape: priority is deliberately unset ("Priority: —" in
  // live transcripts) while the mulligan prompt is up.
  update('')
  handleMessage(mulliganAsk('Mulligan for free, draw another 7 cards?'), useGameStore.getState())
  useGameStore.getState().booleanChoice(false)
  check('hidden after the answer', useGameStore.getState().actionPrompt == null)
  // the proxy keeps pushing game state while the opponents answer — none of it
  // carries a new action_prompt for us until it is OUR turn to answer again
  update('')      // priority still unset (engine is asking the opponents)
  update('QA-B')  // a push that names the opponent (next player asked)
  check('state push with unset priority does not resurrect it', useGameStore.getState().actionPrompt == null)
  check('state push naming the opponent does not resurrect it', useGameStore.getState().actionPrompt == null)
  // and the repeat-answer guard stays armed: re-clicking the SAME answer that
  // already went out must NOT re-send it — that is exactly the F-A2 12-duplicate
  // burst shape (the hidden prompt's buttons are gone, but the keyboard path and
  // any in-flight clicks must still be guarded). Neither an unset-priority nor an
  // opponent-priority push advances the epoch (only an own-priority push re-arms
  // the controls), so the recorded answer stays guarded across them.
  const before = sent.length
  useGameStore.getState().booleanChoice(false)
  check('the SAME answer is not re-sent after the hide (repeat-answer guard intact)', sent.length === before, sent.join(','))
  // A push that names US re-arms the controls by design (the rail
  // must be actionable when the server says we hold priority and no prompt is
  // rendered); assert it does not resurrect a prompt either.
  update('QA-A')
  check('an own-priority push re-arms the controls but does not resurrect the prompt', useGameStore.getState().actionPrompt == null)
}

section('the engine re-asks the SAME player next round: the fresh action_prompt still renders')
{
  reset()
  update('QA-A')
  handleMessage(mulliganAsk('Mulligan down to 6 cards?'), useGameStore.getState())
  useGameStore.getState().booleanChoice(true) // keep -> in real play the round ends; simulate a second-round ask anyway
  check('first answer went out', sent.filter(s => s === 'boolean_choice').length === 1, sent.join(','))
  // Mulligan.executeMulliganPhase loops to the next player with NO state in
  // between; when it comes back to us (or the next question is asked), it fires
  // a FRESH GAME_ASK — a new action_prompt must supersede the local hide (F1).
  handleMessage(mulliganAsk('Mulligan down to 5 cards?'), useGameStore.getState())
  const p = useGameStore.getState().actionPrompt
  check('fresh mulligan ask re-renders after the hide', p?.type === 'boolean' && p.message === 'Mulligan down to 5 cards?', JSON.stringify(p))
  useGameStore.getState().booleanChoice(true)
  check('the fresh ask accepts its answer', sent.filter(s => s === 'boolean_choice').length === 2, sent.join(','))
}

section('a fresh NON-mulligan boolean prompt still renders and can be answered')
{
  reset()
  update('QA-A')
  handleMessage(yesNoAsk('Search your graveyard for a card named Bear?'), useGameStore.getState())
  const p = useGameStore.getState().actionPrompt
  check('yes_no prompt renders', p?.type === 'boolean' && p.context === 'yes_no', JSON.stringify(p))
  useGameStore.getState().booleanChoice(true)
  check('its answer goes out', sent.filter(s => s === 'boolean_choice').length === 1, sent.join(','))
  // ...and the deliberate "choice responses do NOT hide the prompt" policy holds
  check('non-mulligan boolean prompt is NOT hidden (policy unchanged)', useGameStore.getState().actionPrompt != null)
  // a subsequent state push naming the opponent resolves it the old way
  update('QA-B')
  check('it still clears when the server proves resolution', useGameStore.getState().actionPrompt == null)
  const before = sent.length
  useGameStore.getState().booleanChoice(false)
  check('resolved prompt refuses further clicks', sent.length === before, sent.join(','))
}

section('a dropped repeat (double-click) does not hide the live prompt')
{
  // The hide must only follow an answer that actually WENT OUT: if the epoch
  // guard dropped the send, the prompt is still the live question.
  reset()
  update('QA-A')
  handleMessage(mulliganAsk('Mulligan down to 6 cards?'), useGameStore.getState())
  useGameStore.getState().booleanChoice(false)
  useGameStore.getState().booleanChoice(false) // double click — dropped by the guard
  check('the second click sends nothing', sent.filter(s => s === 'boolean_choice').length === 1, sent.join(','))
  check('no resurrection after the dropped repeat', useGameStore.getState().actionPrompt == null)
}

section('rail fallback state after the local hide (store-side facts)')
{
  reset()
  update('QA-A')
  handleMessage(mulliganAsk('Mulligan down to 6 cards?'), useGameStore.getState())
  useGameStore.getState().booleanChoice(false)
  const st = useGameStore.getState()
  // ActionPromptBar renders "Your priority" when priorityPlayer names us and
  // "Waiting for <player>…" otherwise — both need actionPrompt == null, which
  // the cases above already assert. The pre-game mulligan leaves priority
  // unset, so the waiting branch is the one the rail shows then:
  check('actionPrompt is null so the rail falls back', st.actionPrompt == null)
  check('gameState still present so the rail keeps rendering', st.gameState != null)
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)