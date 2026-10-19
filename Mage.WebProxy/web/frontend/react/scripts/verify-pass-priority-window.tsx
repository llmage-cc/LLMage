/**
 * Standalone behavioural check for the pass-priority window removal.
 *
 * Run with:  bash scripts/verify-pass-priority-window.sh
 *
 * "Can you remove the popup for pass priority from the webui?
 * It's useless now that we have the noise and screen flash reminder."
 *
 * The centered DecisionPopup window used to open for EVERY action prompt —
 * including the pass-priority wait: a `select` prompt with zero choices
 * ("Nothing to play — Pass Priority") or one whose every choice is a card the
 * player clicks on the board anyway. That window had NOTHING clickable: pure
 * noise on every priority handoff, now that the ding + edge-glow flash
 * (store/slices/priorityDing.ts) announce the handoff and the rail keeps the
 * message line + Pass Priority button.
 *
 * The fix gates DecisionPopup on isEmptySelectWindow(): a select prompt whose
 * RAIL BUTTON LIST would be empty (the exact selectChoicesKeptAsButtons
 * predicate SelectPrompt renders) keeps the window closed. Everything with
 * real content stays: combat selection (attack/block buttons), a special
 * button (All attack), off-board plays (graveyard/exile/command-zone
 * 'activate' — no clickable card), and every other prompt type.
 *
 * Encodes:
 *  (a) the no-choices select: gate suppresses the window (the regression),
 *  (b) a select whose choices are all board-clickable cards: suppressed,
 *  (c) the same payload with no gameState yet: window STAYS (the rail's
 *      null-state fallthrough keeps ALL choices as buttons),
 *  (d) combat select / special button keep the window; an off-board 'activate'
 *      whose card is NOT clickable anywhere (command zone object) keeps it too.
 *      NOTE 2026-09-26 offhand affordance: a flashback / play-from-exile choice
 *      whose card IS in MY graveyard/exile is now clickable on the board tiles
 *      (PlayerBoard) and in ZonePopup (offHandClickableCardIds), so it suppresses
 *      the window like a hand cast — d3 below pins the command-zone case that
 *      still has no clickable card.
 *  (e) non-select prompts (boolean, play_mana) keep the window,
 *  (f) the rail body agrees with the gate: hint when nothing to click, buttons
 *      otherwise, and Pass Priority still works from the rail after a pass,
 *  (g) the ding/flash channel is untouched by the gate.
 *
 * SSR LIMITATION (same one verify-trigger-order.tsx documents for
 * CardSelectPopup's store-driven shell): react-dom/server reads zustand's
 * INITIAL state snapshot, not the delivered prompt, so DecisionPopup — which
 * subscribes to the store — cannot be meaningfully SSR'd here. The gate
 * PREDICATE is asserted against the REAL store after the REAL wire messages
 * (game_update + action_prompt through handleMessage); DecisionPopup renders
 * from exactly that predicate, so the predicate is the behaviour. The full
 * render-level proof is the live smoke test after the bundle deploy.
 *
 * Node-only: real store, real message handler, real components (react-dom/server).
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import { isEmptySelectWindow } from '../src/components/DecisionPopup'
import { SelectPrompt, selectChoicesKeptAsButtons } from '../src/components/prompts/SelectPrompt'
import type { GameState, Player, ServerMessage, SelectPrompt as SelectPromptType } from '../src/types'

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

// ── fixtures (GameState/Player shape per verify-f19b-f7.ts) ──

const SKIP_FLAGS_OFF = {
  passedTurn: false, passedUntilEndOfTurn: false, passedUntilNextMain: false,
  passedAllTurns: false, passedUntilStackResolved: false, passedUntilEndStepBeforeMyTurn: false,
} as const

/** A battlefield permanent (Mountain, untapped) on MY side. */
const MY_LAND = {
  id: 'land-me-1', name: 'Mountain', imageUrl: '', typeLine: 'Basic Land — Mountain',
  manaCost: '', rules: [], tapped: false, damage: 0,
}

/** A hand card (Goblin Guide) only I can see. */
const HAND_CARD = {
  id: 'hand-me-1', name: 'Goblin Guide', imageUrl: '',
  typeLine: 'Creature — Goblin Scout', manaCost: '{R}', rules: ['Haste'],
}

const player = (name: string, isMe: boolean, prio: boolean, extra: Partial<Player> = {}): Player => ({
  name, playerId: name, life: 20, isMe, libraryCount: 40, handCount: 7,
  isActive: false, hasPriority: prio, hasLeft: false, timerActive: false,
  priorityTimeLeftSecs: 0, isMonarch: false, hasInitiative: false, manaPool: null,
  counters: [], commanders: [], battlefield: [], hand: [], graveyard: [], exile: [],
  statesSavedSize: 1, ...SKIP_FLAGS_OFF, ...extra,
})

const state = (priorityPlayer: string, extra: Partial<GameState> = {}): GameState => ({
  status: 'playing', turn: 5, phase: null, step: null,
  activePlayer: 'QA-A', priorityPlayer,
  stack: [], combat: [],
  players: [player('QA-A', true, priorityPlayer === 'QA-A'), player('QA-B', false, priorityPlayer === 'QA-B')],
  llmDebug: [], revealed: [], lookedAt: [], stateSeq: 1,
  ...extra,
})

const stateWithBoard = (): GameState => state('QA-A', {
  players: [
    player('QA-A', true, true, { hand: [HAND_CARD], battlefield: [MY_LAND] }),
    player('QA-B', false, false),
  ],
})

// ── wire capture ──
const sent: Array<Record<string, unknown>> = []
useGameStore.getState().setWebSocket({
  send: (m: unknown) => { sent.push(m as Record<string, unknown>) },
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  disconnect: () => {},
} as never)

const reset = () => {
  sent.length = 0
  useGameStore.setState({ actionPrompt: null, currentActionPrompt: null, passPending: false, eventLog: [], gameState: null })
}

const deliver = (msg: ServerMessage) => handleMessage(msg, useGameStore.getState())

const selectPrompt = (): SelectPromptType => {
  const p = useGameStore.getState().actionPrompt
  if (p == null || p.type !== 'select') throw new Error('no select prompt in store')
  return p
}

/** The exact wire shape WebProxyClient.handleGameSelect emits. */
const selectMsg = (choices: Array<{ id: string; name: string; action?: string }>, over: Record<string, unknown> = {}): ServerMessage =>
  ({ type: 'action_prompt', action_type: 'select', message: 'Play spells and abilities', ...over,
     choices: choices.map((c, i) => ({ index: i, ...c })) } as unknown as ServerMessage)

const updateMsg = (s: GameState): ServerMessage => ({ type: 'game_update', state: s } as unknown as ServerMessage)

// ─────────────────────────────────────────────────────────────────────────────

section('(a) the pass-priority WAIT: select with zero choices is suppressed')
{
  reset()
  deliver(updateMsg(state('QA-A')))
  deliver(selectMsg([]))
  const p = selectPrompt()
  check('prompt is the empty select', p.choices.length === 0)
  check('gate suppresses the window', isEmptySelectWindow(p))
}

section('(b) hand/battlefield-only choices: suppressed, cards stay clickable on the board')
{
  reset()
  // My hand has Goblin Guide; the select offers it as a cast choice.
  deliver(updateMsg(stateWithBoard()))
  deliver(selectMsg([{ id: 'hand-me-1', name: 'Goblin Guide', action: 'cast' }]))
  const p = selectPrompt()
  const gs: GameState | null = useGameStore.getState().gameState
  const meHand = gs?.players.find((pl: Player) => pl.isMe)?.hand ?? []
  check('the hand card IS clickable (in my hand)', meHand.some((c: { id: string | null }) => c.id === 'hand-me-1'))
  check('rail button list would be empty (same predicate SelectPrompt renders)',
    selectChoicesKeptAsButtons(p, gs ?? null).length === 0)
  check('gate suppresses the window', isEmptySelectWindow(p))
}

section('(c) no gameState yet: the window STAYS (null-state fallthrough keeps all choices)')
{
  reset()
  // Same cast choice, but no state push ever arrived (early-prompt edge).
  deliver(selectMsg([{ id: 'hand-me-1', name: 'Goblin Guide', action: 'cast' }]))
  check('store has no gameState', useGameStore.getState().gameState == null)
  const p = selectPrompt()
  check('gate keeps the window (cannot prove the card is clickable)', !isEmptySelectWindow(p))
  // The rail body SelectPrompt renders with this prompt: all choices as buttons.
  const railHtml = renderToStaticMarkup(<SelectPrompt prompt={p} />)
  check('rail falls back to a button for the choice', railHtml.includes('Goblin Guide'), railHtml)
}

section('(d) prompts with real content keep the window')
{
  // d1: combat select (declare attackers) — attack choices are buttons.
  reset()
  deliver(updateMsg(state('QA-A')))
  deliver(selectMsg([{ id: 'perm-1', name: 'Raging Goblin', action: 'attack' }]))
  {
    const p = selectPrompt()
    check('combat select: window stays', !isEmptySelectWindow(p))
    const railHtml = renderToStaticMarkup(<SelectPrompt prompt={p} />)
    check('combat select renders the attack button', railHtml.includes('Raging Goblin'), railHtml)
  }
  // d2: special button (All attack) — window stays even with no choices.
  reset()
  deliver(updateMsg(state('QA-A')))
  deliver(selectMsg([], { special_button: 'All attack' }))
  {
    check('special button: window stays', !isEmptySelectWindow(selectPrompt()))
  }
  // d3: off-board 'activate' whose card is clickable NOWHERE — this uses an id
  // absent from every rendered zone (a command-zone object; a graveyard card
  // id would now suppress the window — that is the offhand affordance, see
  // verify-offhand-affordance.tsx). No clickable card → button → window stays.
  reset()
  deliver(updateMsg(state('QA-A')))
  deliver(selectMsg([{ id: 'grave-1', name: 'Ancient Grudge', action: 'activate' }]))
  {
    const p = selectPrompt()
    check('off-board activate: window stays', !isEmptySelectWindow(p))
    const railHtml = renderToStaticMarkup(<SelectPrompt prompt={p} />)
    check('off-board activate renders a button', railHtml.includes('Ancient Grudge'), railHtml)
  }
}

section('(e) non-select prompts keep the window (gate is select-only)')
{
  reset()
  deliver(updateMsg(state('QA-A')))
  deliver({
    type: 'action_prompt', action_type: 'boolean', message: 'Pay {E}{E}{E}?',
    yes_label: 'Yes', no_label: 'No', context: 'yes_no',
  } as unknown as ServerMessage)
  {
    const p = useGameStore.getState().actionPrompt
    check('boolean prompt installed', p?.type === 'boolean')
    check('boolean: gate does not suppress', !isEmptySelectWindow(p!))
  }
  reset()
  deliver(updateMsg(state('QA-A')))
  deliver({
    type: 'action_prompt', action_type: 'play_mana', message: 'Pay {R}.',
    context: 'default',
  } as unknown as ServerMessage)
  {
    const p = useGameStore.getState().actionPrompt
    check('play_mana prompt installed', p?.type === 'play_mana')
    check('play_mana: gate does not suppress', !isEmptySelectWindow(p!))
  }
}

section('(f) one predicate drives rail AND window — the rail body agrees')
{
  // The no-choices prompt: rail button list empty in ANY state → the rail shows
  // the same hint the window would have shown, and nothing else.
  reset()
  deliver(updateMsg(state('QA-A')))
  deliver(selectMsg([]))
  {
    const p = selectPrompt()
    check('gate suppresses (wait)', isEmptySelectWindow(p))
    const railHtml = renderToStaticMarkup(<SelectPrompt prompt={p} />)
    check('rail shows "Nothing to play — Pass Priority" (the wait, in the rail)',
      railHtml.includes('Nothing to play — Pass Priority'), railHtml)
    check('rail renders no empty button list', !railHtml.includes('btn-prompt'), railHtml)
  }
  // Mixed list: one clickable hand card + one non-action choice → a rail button
  // remains → the window stays (the gate is exactly the rail's button list).
  reset()
  deliver(updateMsg(stateWithBoard()))
  deliver(selectMsg([
    { id: 'hand-me-1', name: 'Goblin Guide', action: 'cast' },
    { id: 'mystery-1', name: 'A weird option' },
  ]))
  {
    const p = selectPrompt()
    const gs = useGameStore.getState().gameState
    check('mixed list: window stays', !isEmptySelectWindow(p))
    check('rail keeps the non-card choice as a button',
      selectChoicesKeptAsButtons(p, gs ?? null).map((c) => c.name).join(',') === 'A weird option')
  }
  // And from the suppressed wait, Pass Priority still works from the rail
  // (same path verify-f19b-f7.ts covers at store level).
  reset()
  deliver(updateMsg(state('QA-A')))
  deliver(selectMsg([]))
  {
    useGameStore.getState().passPriority()
    check('passPriority() sends the pass', sent.map((m) => m.type).join(',') === 'pass_priority', JSON.stringify(sent))
    check('prompt cleared locally after the pass', useGameStore.getState().actionPrompt == null)
    check('passPending set — the rail can say "Pass sent"', useGameStore.getState().passPending === true)
  }
}

section('(g) the ding/flash channel is untouched by the gate')
{
  // The gate lives in DecisionPopup's RENDER only; the priority alert fires from
  // setActionPrompt (gameplaySlice) regardless of what the UI renders — a
  // suppressed wait still produced the prompt installation that triggers it.
  reset()
  deliver(selectMsg([]))
  check('prompt installed (ding hook runs in setActionPrompt, not the render)',
    useGameStore.getState().actionPrompt?.type === 'select')
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)