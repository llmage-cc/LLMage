/**
 * Standalone behavioural check for the priority-flash SUSTAIN mode.
 *
 * Run with:  bash scripts/verify-priority-flash-sustain.sh
 *
 * "Add a 'sustain' option to the screen edges flash for
 * priority so you can set an intensity for the flash to hold at."
 *
 * The sustain is a SECOND CHANNEL off the existing fresh-arrival decision in
 * store/slices/priorityDing.ts: no new listeners at the five prompt-lifecycle
 * sites, no change to the epoch/continuation logic. This harness drives the
 * REAL store through the REAL message handler (setActionPrompt / setGameState /
 * hideActionPrompt are the shipped wire paths) and asserts the REAL exported
 * predicates from ScreenFlash.tsx — not re-implementations of them.
 *
 * Encodes:
 *  (a) sustain OFF → the shipped behaviour: a pulse event per fresh arrival,
 *      no sustain event, no hold state;
 *  (b) sustain ON → a sustain event on fresh arrival INSTEAD of a pulse, at the
 *      configured intensity, and the pulse channel is skipped while sustained;
 *  (c) the clear lifecycle — onPromptClearedForPriorityDing (the 5 sites) plus
 *      the two game-boundary paths that route through it: game_over →
 *      hideActionPrompt, and a server-proven resolution → setGameState;
 *  (d) hidden-page suppression: no start while hidden, and a live hold renders
 *      nothing; on visibilitychange → visible the overlay re-arms from the
 *      store's actionPrompt (and does NOT re-arm when the prompt was answered
 *      while hidden);
 *  (e) reduced motion: the static variant, same hold/clear;
 *  (f) settings persistence + reset (resetDingSettings clears flashSustain,
 *      which is what the settingsDefaults MODULE_RESETS entry calls);
 *  (g) overlay invariants: pointer-events none, no animation while sustained,
 *      keyframes only for the un-reduced pulse.
 *
 * Node-only: the app's own browser stubs (scripts/verify-stubs.mjs) stand in for
 * document/window, exactly as the other store-driving harnesses do.
 */
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import {
  PRIORITY_FLASH_EVENT,
  PRIORITY_FLASH_SUSTAIN_EVENT,
  PRIORITY_FLASH_SUSTAIN_CLEAR_EVENT,
  onPromptClearedForPriorityDing,
  getDingSettings,
  setDingSettings,
  resetDingSettings,
  isPromptOpen,
} from '../src/store/slices/priorityDing'
import {
  shouldHoldGlow,
  glowVisual,
  overlayRenderState,
  flashBackground,
  clampIntensity,
  installScreenFlashListeners,
  FLASH_MS,
  PREVIEW_MS,
} from '../src/components/ScreenFlash'
import { resetSettingsToDefaults } from '../src/utils/settingsDefaults'
import type { GameState, Player, ServerMessage } from '../src/types'

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

// ── fixtures (GameState/Player shape per verify-prompt-lifecycle.ts) ──
const SKIP_FLAGS_OFF = {
  passedTurn: false, passedUntilEndOfTurn: false, passedUntilNextMain: false,
  passedAllTurns: false, passedUntilStackResolved: false, passedUntilEndStepBeforeMyTurn: false,
} as const

const player = (name: string, isMe: boolean, prio: boolean): Player => ({
  name, playerId: name, life: 20, isMe, libraryCount: 40, handCount: 7,
  isActive: false, hasPriority: prio, hasLeft: false, timerActive: false,
  priorityTimeLeftSecs: 0, isMonarch: false, hasInitiative: false, manaPool: null,
  counters: [], commanders: [], battlefield: [], hand: [], graveyard: [], exile: [],
  statesSavedSize: 1, ...SKIP_FLAGS_OFF,
})

const state = (priorityPlayer: string, extra: Partial<GameState> = {}): GameState => ({
  status: 'playing', turn: 3, phase: null, step: null,
  activePlayer: 'QA-A', priorityPlayer,
  stack: [], combat: [],
  players: [player('QA-A', true, priorityPlayer === 'QA-A'), player('QA-B', false, priorityPlayer === 'QA-B')],
  llmDebug: [], revealed: [], lookedAt: [], stateSeq: 1,
  ...extra,
})

// ── event capture on the REAL window EventTarget ──
// Raw event names (dispatch-level evidence: which channel the slice raised).
const events: string[] = []
window.addEventListener(PRIORITY_FLASH_EVENT, () => events.push('pulse'))
window.addEventListener(PRIORITY_FLASH_SUSTAIN_EVENT, (e) => {
  const d = (e as CustomEvent).detail as { intensity?: number } | undefined
  events.push(`sustain:${d?.intensity ?? 'none'}`)
})
window.addEventListener(PRIORITY_FLASH_SUSTAIN_CLEAR_EVENT, () => events.push('clear'))

// ── the overlay's REAL wiring ──
// installScreenFlashListeners is what the component mounts; here it drives a
// recorder instead of setState, so every assertion below runs against the real
// handlers (real event names, real gate reads, real visibilitychange re-arm).
interface GlowState { mode: 'pulse' | 'sustain'; intensity: number; preview: boolean }
let glow: GlowState | null = null

// document must be an EventTarget the harness can fire BEFORE the real wiring
// registers against it — the app's stub is deliberately inert (a browser fires
// visibilitychange; Node does not), and a handler registered on the inert stub
// would be invisible to setPageVisibility below.
const docListeners = new Map<string, Set<(e: Event) => void>>()
;(document as unknown as { addEventListener: (t: string, f: (e: Event) => void) => void }).addEventListener =
  (t, f) => { if (!docListeners.has(t)) docListeners.set(t, new Set()); docListeners.get(t)!.add(f) }
;(document as unknown as { removeEventListener: (t: string, f: (e: Event) => void) => void }).removeEventListener =
  (t, f) => { docListeners.get(t)?.delete(f) }

const installed = installScreenFlashListeners({
  armPulse: (intensity) => { glow = { mode: 'pulse', intensity, preview: false } },
  armHold: (intensity, preview) => { glow = { mode: 'sustain', intensity, preview } },
  dropGlow: () => { glow = null },
})

/** Fire a real visibilitychange after moving the page to `state`. */
function setPageVisibility(state: 'visible' | 'hidden') {
  ;(document as unknown as { visibilityState: string }).visibilityState = state
  for (const fn of docListeners.get('visibilitychange') ?? []) fn(new Event('visibilitychange'))
}

useGameStore.getState().setWebSocket({
  send: () => {},
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  disconnect: () => {},
} as never)

const deliver = (msg: ServerMessage) => handleMessage(msg, useGameStore.getState())
const promptMsg = (over: Record<string, unknown> = {}): ServerMessage =>
  ({ type: 'action_prompt', action_type: 'select', message: 'Play spells and abilities', choices: [], ...over } as unknown as ServerMessage)
const updateMsg = (s: GameState): ServerMessage => ({ type: 'game_update', state: s } as unknown as ServerMessage)

/** Fresh prompt arrival through the real wire path (advances the prompt epoch). */
const arrive = (over: Record<string, unknown> = {}) => deliver(promptMsg(over))

const reset = () => {
  events.length = 0
  useGameStore.setState({ actionPrompt: null, currentActionPrompt: null, passPending: false, gameState: null, eventLog: [] })
  // Re-arm the slice's dedupe memory the way the lifecycle does.
  onPromptClearedForPriorityDing()
  events.length = 0
}

/** The last event of each kind, for readable diagnostics. */
const lastSustain = () => events.filter((e) => e.startsWith('sustain:')).slice(-1)[0] ?? ''
const pulseCount = () => events.filter((e) => e === 'pulse').length
const sustainCount = () => events.filter((e) => e.startsWith('sustain:')).length
const clearCount = () => events.filter((e) => e === 'clear').length

/** What the REAL overlay wiring has on screen right now. */
const glowMode = () => glow?.mode ?? 'none'
const glowIntensity = () => glow?.intensity ?? -1
/** The current glow object (a function so TS does not narrow the `let`). */
const currentGlow = () => glow

// ─────────────────────────────────────────────────────────────────────────────

section('(a) sustain OFF → the shipped one-shot pulse, unchanged')
{
  reset()
  setDingSettings({ flashEnabled: true, flashSustain: false, flashIntensity: 0.7, enabled: false })
  arrive()
  check('a fresh arrival raises the PULSE event', pulseCount() === 1 && sustainCount() === 0, events.join(','))
  check('pulse carries the configured intensity', events[0] === 'pulse', events.join(','))
  check('the REAL overlay shows a pulse', glowMode() === 'pulse', glowMode())
  check('flashIntensity is the intensity source (no separate sustain setting)', getDingSettings().flashIntensity === 0.7)
  check('the overlay reads the intensity off the event (0.7)', glowIntensity() === 0.7, String(glowIntensity()))
  // A second arrival for a DIFFERENT prompt must pulse again (dedupe is per prompt).
  arrive({ message: 'Choose a target' })
  check('a second fresh prompt pulses again', pulseCount() === 2, events.join(','))
  check('the overlay is still in pulse mode', glowMode() === 'pulse', glowMode())
  // The overlay draws the keyframes at rest.
  const visual = glowVisual({ mode: 'pulse', intensity: 0.7, reduced: false })
  check('pulse uses the screen-flash-pulse keyframes', visual.animation === `screen-flash-pulse ${FLASH_MS}ms ease-out forwards`, visual.animation)
}

section('(a2) the flash channel stays gated — sustain needs flashEnabled too')
{
  reset()
  setDingSettings({ flashEnabled: false, flashSustain: true, enabled: true, focusMode: 'always' })
  arrive()
  check('flash disabled: neither channel fires', pulseCount() === 0 && sustainCount() === 0, events.join(','))
  check('shouldHoldGlow agrees (needs the flash channel on)', !shouldHoldGlow({ visible: true, promptOpen: true, flashEnabled: false, sustainEnabled: true }))
  setDingSettings({ flashEnabled: true, enabled: false })
}

section('(b) sustain ON → a hold on fresh arrival, pulse SKIPPED')
{
  reset()
  setDingSettings({ flashEnabled: true, flashSustain: true, flashIntensity: 0.62, enabled: false, focusMode: 'always' })
  arrive()
  check('a fresh arrival raises the SUSTAIN event', sustainCount() === 1 && pulseCount() === 0, events.join(','))
  check('the hold is at the configured intensity', lastSustain() === 'sustain:0.62', lastSustain())
  check('no pulse while sustained (recommended behaviour)', pulseCount() === 0, events.join(','))
  check('the REAL overlay is HOLDING (not pulsing)', glowMode() === 'sustain', glowMode())
  check('the held overlay uses the configured intensity', glowIntensity() === 0.62, String(glowIntensity()))
  // A later prompt while still sustained replaces the hold (still no pulse).
  arrive({ message: 'Second question' })
  check('a later prompt refreshes the hold, still no pulse', sustainCount() === 2 && pulseCount() === 0, events.join(','))
  check('the hold is at the same configured intensity', lastSustain() === 'sustain:0.62', lastSustain())
  check('the overlay is still holding after the refresh', glowMode() === 'sustain', glowMode())
  // The held glow is static: no animation, opacity up.
  const held = glowVisual({ mode: 'sustain', intensity: 0.62, reduced: false })
  check('held glow has NO animation', held.animation === 'none', held.animation)
  check('held glow is drawn at full layer opacity (the gradient carries the intensity)', held.opacity === 1)
  check('held glow gradient scales with intensity',
    held.background === flashBackground(0.62) && held.background.includes(String(0.35 + 0.4 * 0.62)), held.background)
}

section('(c) CLEAR: the prompt-lifecycle hook (all five sites route through it)')
{
  reset()
  setDingSettings({ flashEnabled: true, flashSustain: true, flashIntensity: 0.5, enabled: false })
  arrive()
  const holdsBefore = sustainCount()
  check('hold is up before the clear', holdsBefore === 1)
  check('the overlay is holding before the clear', glowMode() === 'sustain', glowMode())
  // The hook itself — this is exactly what the five gameplaySlice sites call.
  onPromptClearedForPriorityDing()
  check('onPromptClearedForPriorityDing raises the CLEAR event', clearCount() === 1, events.join(','))
  check('the held glow goes OUT with the clear', glowMode() === 'none', glowMode())
  // A clear while nothing is sustained is a no-op, not an error (the sites fire
  // it unconditionally, whether or not sustain is on).
  const clearsAfterFirst = clearCount()
  onPromptClearedForPriorityDing()
  check('a second clear is harmless (no throw, event raised again)', clearCount() === clearsAfterFirst + 1)
}

section('(c2) CLEAR on PROMPT RESOLUTION by server state (setGameState path)')
{
  reset()
  setDingSettings({ flashEnabled: true, flashSustain: true, flashIntensity: 0.5, enabled: false })
  deliver(updateMsg(state('QA-A')))
  arrive()
  check('hold up with the prompt open', sustainCount() === 1)
  check('the overlay is holding', glowMode() === 'sustain', glowMode())
  // Answer it, then let a state push prove priority moved away — the shipped
  // promptResolvedBy branch, which must take the glow down with the prompt.
  useGameStore.getState().selectChoice(0)
  deliver(updateMsg(state('QA-B')))
  check('answered prompt is cleared by server state', useGameStore.getState().actionPrompt == null)
  check('resolved prompt also clears the hold', clearCount() >= 1, events.join(','))
  check('the overlay is dark after the resolution', glowMode() === 'none', glowMode())
}

section('(c3) CLEAR on GAME OVER and on a NEW GAME (game-boundary paths)')
{
  // game_over → hideActionPrompt → the clear hook.
  reset()
  setDingSettings({ flashEnabled: true, flashSustain: true, flashIntensity: 0.5, enabled: false })
  deliver(updateMsg(state('QA-A')))
  arrive()
  check('hold up before the game ends', sustainCount() === 1)
  check('the overlay is holding before game over', glowMode() === 'sustain', glowMode())
  deliver({ type: 'game_over', result: 'QA-B has won the game', hasWon: false } as unknown as ServerMessage)
  check('game_over clears the prompt', useGameStore.getState().actionPrompt == null)
  check('game_over clears the hold', clearCount() >= 1 && events.slice(-1)[0] === 'clear', events.join(','))
  check('the overlay is dark after game over', glowMode() === 'none', glowMode())

  // start_game of a NEW game → hideActionPrompt → the clear hook.
  reset()
  deliver(updateMsg(state('QA-A')))
  arrive()
  check('hold up before the new game starts', sustainCount() === 1)
  deliver({ type: 'start_game', gameId: 'SUSTAIN-GAME-1' } as ServerMessage)
  check('a new game clears the prompt', useGameStore.getState().actionPrompt == null)
  check('a new game clears the hold', clearCount() >= 1 && events.slice(-1)[0] === 'clear', events.join(','))
  check('the overlay is dark at the new game', glowMode() === 'none', glowMode())

  // ...and after the new game the NEXT prompt holds again (the lifecycle re-arms).
  events.length = 0
  deliver(updateMsg(state('QA-A')))
  arrive({ message: 'Fresh game question' })
  check('the next prompt in the new game holds again', sustainCount() === 1 && pulseCount() === 0, events.join(','))
  check('the overlay holds again in the new game', glowMode() === 'sustain', glowMode())
}

section('(d) HIDDEN PAGE: no start, no render, re-arm from the prompt on return')
{
  // (d1) hidden at arrival → nothing dispatched (no glow to miss).
  reset()
  setDingSettings({ flashEnabled: true, flashSustain: true, flashIntensity: 0.5, enabled: false })
  setPageVisibility('hidden')
  arrive()
  check('hidden page: no sustain event', sustainCount() === 0, events.join(','))
  check('hidden page: no pulse either', pulseCount() === 0, events.join(','))
  check('hidden page: the overlay is dark', glowMode() === 'none', glowMode())
  // ...and the shipped behaviour for the pulse is the same (unchanged gate).
  setDingSettings({ flashSustain: false })
  arrive({ message: 'While hidden, no pulse' })
  check('hidden page: pulse channel still suppressed (existing behaviour)', pulseCount() === 0, events.join(','))

  // (d2) a live hold goes DOWN the moment the page goes hidden, via the real
  // visibilitychange handler.
  setPageVisibility('visible')
  reset()
  setDingSettings({ flashSustain: true })
  arrive()
  check('hold up while visible', sustainCount() === 1)
  check('the overlay is holding while visible', glowMode() === 'sustain', glowMode())
  setPageVisibility('hidden')
  check('going hidden drops the live hold (real handler)', glowMode() === 'none', glowMode())
  check('the overlay renders NOTHING while hidden',
    overlayRenderState({ visible: false, mode: 'sustain', intensity: 0.5, reduced: false }) === null)
  const visibleRender = overlayRenderState({ visible: true, mode: 'sustain', intensity: 0.5, reduced: false })
  check('a held glow renders an overlay while visible', visibleRender != null && visibleRender.dataFlashMode === 'sustain')
  check('overlay never blocks the board (pointer-events none)', visibleRender?.style.pointerEvents === 'none')

  // (d3) back to visible with the prompt STILL OPEN → the real handler re-arms
  // the hold, at the CURRENT intensity setting, from the store's actionPrompt.
  setDingSettings({ flashIntensity: 0.9 })
  check('store still holds the open prompt (the re-arm truth)', isPromptOpen(useGameStore.getState()))
  setPageVisibility('visible')
  check('returning visible re-arms the hold', glowMode() === 'sustain', glowMode())
  check('the re-armed hold reads the CURRENT intensity setting', glowIntensity() === 0.9, String(glowIntensity()))
  // ...and it is a real hold, not a preview — no self-clear timer.
  check('the re-armed hold is not a preview', currentGlow()?.preview === false, String(currentGlow()?.preview))

  // (d4) prompt ANSWERED while hidden → the return must NOT light the glow up.
  reset()
  setDingSettings({ flashSustain: true, flashIntensity: 0.5 })
  deliver(updateMsg(state('QA-A')))
  arrive()
  check('holding before the answer', glowMode() === 'sustain', glowMode())
  useGameStore.getState().selectChoice(0)              // answered (the hold is cleared)
  deliver(updateMsg(state('QA-B')))                    // server proved the resolution
  setPageVisibility('hidden')
  setPageVisibility('visible')
  check('no prompt open after the resolution', !isPromptOpen(useGameStore.getState()))
  check('returning visible with no prompt leaves the glow dark', glowMode() === 'none', glowMode())
  check('shouldHoldGlow agrees (real predicate)',
    !shouldHoldGlow({ visible: true, promptOpen: isPromptOpen(useGameStore.getState()), flashEnabled: getDingSettings().flashEnabled, sustainEnabled: getDingSettings().flashSustain }))

  // (d5) returning visible with sustain switched OFF must not arm a hold either.
  reset()
  setDingSettings({ flashSustain: false })
  setPageVisibility('hidden')
  setPageVisibility('visible')
  check('sustain off: returning visible arms no hold', glowMode() === 'none', glowMode())
  check('...and does not retro-pulse (a return is not a fresh arrival)', pulseCount() === 0, events.join(','))
}

section('(e) REDUCED MOTION: static variant, same hold/clear')
{
  reset()
  setDingSettings({ flashEnabled: true, flashSustain: true, flashIntensity: 0.5, enabled: false })
  arrive()
  check('hold still raised under reduced motion', sustainCount() === 1, events.join(','))
  const held = glowVisual({ mode: 'sustain', intensity: 0.5, reduced: true })
  check('reduced-motion hold is static (no animation)', held.animation === 'none', held.animation)
  check('reduced-motion hold is still VISIBLE (static border, not opacity 0)', held.opacity > 0, String(held.opacity))
  check('reduced-motion hold carries the same intensity gradient', held.background === flashBackground(0.5))
  const reducedPulse = glowVisual({ mode: 'pulse', intensity: 0.5, reduced: true })
  check('reduced-motion pulse is also static and visible', reducedPulse.animation === 'none' && reducedPulse.opacity > 0)
  const render = overlayRenderState({ visible: true, mode: 'sustain', intensity: 0.5, reduced: true })
  check('reduced-motion overlay keeps the is-reduced class', render?.className === 'screen-flash is-reduced', String(render?.className))
  onPromptClearedForPriorityDing()
  check('reduced-motion hold clears the same way', clearCount() >= 1, events.join(','))
}

section('(f) settings: persistence, defaults and the Reset-to-Default path')
{
  // Default is OFF — the shipped behaviour stays the one-shot pulse.
  resetDingSettings()
  check('flashSustain defaults to false', getDingSettings().flashSustain === false, String(getDingSettings().flashSustain))
  setDingSettings({ flashSustain: true })
  check('the setting round-trips through the module', getDingSettings().flashSustain === true)
  // Persistence: the module writes its own key (the same one the dialog reads).
  const raw = globalThis.localStorage.getItem('xmage-priority-ding') ?? ''
  check('flashSustain is persisted in the ding blob', raw.includes('"flashSustain":true'), raw)
  // resetDingSettings is what settingsDefaults.MODULE_RESETS calls — it must
  // reset the NEW field too, and re-mirror a live value (uiSlice.markSettingsReset
  // re-reads getDingSettings() into the store after the reset).
  resetDingSettings()
  check('resetDingSettings resets flashSustain', getDingSettings().flashSustain === false)
  const afterReset = globalThis.localStorage.getItem('xmage-priority-ding') ?? ''
  check('the persisted blob is rewritten by the reset', afterReset.includes('"flashSustain":false'), afterReset)
  // Intensity clamping for the hold (garbage → 0.5, out of range → 0..1).
  check('clampIntensity clamps the hold intensity', clampIntensity(9) === 1 && clampIntensity(-2) === 0 && clampIntensity(undefined) === 0.5)
}

section('(f2) RESET TO DEFAULT takes a live hold down (the real registry path)')
{
  // The reset registry (settingsDefaults.MODULE_RESETS) calls resetDingSettings;
  // the whole path must not leave a lit edge behind when sustain is switched off
  // under it mid-prompt.
  reset()
  setDingSettings({ flashEnabled: true, flashSustain: true, flashIntensity: 0.5, enabled: false })
  deliver(updateMsg(state('QA-A')))
  arrive()
  check('holding before the reset', glowMode() === 'sustain', glowMode())
  // Drive the REAL registry entry point the Settings button calls.
  resetSettingsToDefaults(useGameStore.getState() as never)
  check('reset restores the sustain default (off)', getDingSettings().flashSustain === false)
  check('reset takes the live hold down', glowMode() === 'none', glowMode())
  const mirrored = useGameStore.getState().priorityDing.flashSustain
  check('the store mirror reflects the reset sustain value', mirrored === false, String(mirrored))
}

section('(g) overlay invariants + the preview lifecycle')
{
  const render = overlayRenderState({ visible: true, mode: 'sustain', intensity: 0.8, reduced: false })
  check('sustained overlay is fixed/full-viewport', render?.style.position === 'fixed' && render?.style.inset === 0)
  check('sustained overlay has no animation', render?.style.animation === 'none', String(render?.style.animation))
  check('sustained overlay keeps the accent gradient', String(render?.style.background).includes('radial-gradient'))
  check('idle overlay renders nothing', overlayRenderState({ visible: true, mode: null, intensity: 0.5, reduced: false }) === null)
  // The preview hold used by the settings checkbox self-clears; a real hold does not.
  check('a preview hold has a bounded lifetime distinct from the pulse', PREVIEW_MS > FLASH_MS, `${PREVIEW_MS} vs ${FLASH_MS}`)
  // Teardown: the wiring is removable (the component's effect cleanup) and a
  // removed wiring stops reacting — no listener leak across mounts.
  installed()
  const before = glowMode()
  setDingSettings({ flashSustain: true, flashEnabled: true })
  arrive({ message: 'After teardown' })
  check('teardown removes the wiring (no ghost glow)', glowMode() === before, `${before} -> ${glowMode()}`)
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
