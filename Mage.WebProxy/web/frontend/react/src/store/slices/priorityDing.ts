/**
 * Priority alert — tell the player their turn arrived: sound AND screen flash.
 *
 * Feature request: "Add optional ding for when you have priority. Should be
 * customizable (volume, sound, etc, if window is out of focus vs always, etc)".
 * Follow-up: every centered prompt window is annoying
 * on pass-priority waits — add an edge-glow screen flash "alongside the ding".
 *
 * ── WHEN IT FIRES ──
 * Once per NEW action-prompt arrival. The hook is called from `setActionPrompt`
 * AFTER the prompt is installed — the same point the auto-tap run is driven
 * from (`onPromptSetForAutoTapRun`). It is never reachable from a React render:
 * the call site is the wire-message handler, so "fire on every re-render" is
 * structurally impossible. On top of that, the caller passes the
 * prompt-lifecycle EPOCH (gameplaySlice's per-prompt counter): the epoch
 * advances exactly once per prompt arrival, so a duplicate invocation for the
 * same installed prompt (future refactor hazard) is dropped by the epoch
 * comparison rather than alerting twice.
 *
 * Internal re-prompts the player did not observe as a new question do NOT
 * alert: the mana-payment family re-fires `play_mana` after EVERY tapped land
 * (engine `ManaCostsImpl.pay()` loop), and an in-flight auto-tap run drives
 * those re-prompts invisibly. A `play_mana` whose previous prompt was the SAME
 * payment (same subject name, or an auto-tap run with taps already recorded)
 * is a continuation, not a fresh "your turn" moment.
 *
 * ── THE TWO CHANNELS ──
 * ONE fresh-arrival decision (epoch dedupe + continuation suppression below)
 * fans out to both channels:
 *  - SOUND ("ding"): gated by the ding's own focus mode (settings-ding-focus).
 *  - SCREEN FLASH: dispatched as the PRIORITY_FLASH_EVENT DOM event; the
 *    ScreenFlash overlay component renders the edge glow. A flash is only
 *    useful when the page is VISIBLE, so the event is suppressed while
 *    document.visibilityState is 'hidden' (it deliberately does NOT follow the
 *    sound's focus mode — a visible-but-unfocused window is exactly where the
 *    flash helps). Respects prefers-reduced-motion (the overlay component
 *    checks; see ScreenFlash.tsx).
 * Either channel can be off; the decision logic runs once regardless so
 * disabling one never desyncs the other's dedupe state.
 *
 * ── SUSTAIN ──
 * "Add a 'sustain' option to the screen edges flash for priority so you can
 * set an intensity for the flash to hold at." The flash gains a SECOND mode on
 * the SAME decision point — no second listener, no new store field:
 *  - `flashSustain: true` makes the SCREEN FLASH channel HOLD instead of pulse:
 *    on a fresh arrival the glow comes up at `flashIntensity` and STAYS up until
 *    the prompt is cleared (`onPromptClearedForPriorityDing`, the same five
 *    lifecycle sites that already reset the dedupe memory), or the page is
 *    hidden. While sustained the 500ms pulse is SKIPPED (the glow is already up —
 *    re-pulsing it would read as a flicker on every re-render of the same wait).
 *  - Hidden page: no start, and a glow already up is cleared (nothing to see).
 *    On `visibilitychange` back to visible the overlay re-arms itself from
 *    "is a prompt open?" — that question is answered by the store's
 *    `actionPrompt`, so the store owns the truth and the component only asks.
 *  - Game end / new game clear it for free: those paths go through
 *    `hideActionPrompt` (game_over, start_game) and `promptResolvedBy`
 *    (setGameState), all of which call the clear hook above.
 * Epoch/continuation logic is untouched: the sustain is an OUTPUT of the same
 * fresh-arrival decision, never an input to it.
 *
 * ── NOT INTERFERING WITH THE AUTO-TAP RUN ──
 * Purely additive: this module never sends a message and never sets store
 * state. It READS the run snapshot's public getter only; the run never reads
 * this module. The dependency runs one way (slices -> this module); there is
 * deliberately no import back into gameplaySlice (the epoch is a parameter for
 * exactly that reason — same no-cycle rule as autoTapRun.ts).
 *
 * State lives in module scope — the same pattern as the prompt-lifecycle
 * globals in gameplaySlice.ts and the auto-tap snapshot in autoTapRun.ts.
 */
import type { GameStore } from '../types'
import { playSoundVariant, isSoundVariant, type SoundVariant } from '@/audio/dingSynth'

/** Re-exported for the store types and the settings dialog. */
export type { SoundVariant } from '@/audio/dingSynth'
import { getAutoTapRun } from './autoTapRun'
import { manaPromptSubject } from './autoTapRun'

// ── Settings shape + DEFAULTS ──
// One object so the sibling "Reset to default" feature can reset them trivially.

export type DingFocusMode =
  /** Ding ONLY when the window is out of focus (the "call me back" mode). */
  | 'unfocused'
  /** Ding every prompt arrival, focused or not. */
  | 'always'
  /** Never ding while the window is out of focus (in-focus only). */
  | 'muted-unfocused'

export interface PriorityDingSettings {
  enabled: boolean
  /** 0..1 master volume of the ding. */
  volume: number
  /** Which synthesized sound. */
  variant: SoundVariant
  /** The "window out of focus vs always" control. */
  focusMode: DingFocusMode
  /** Edge-glow screen flash alongside the sound. */
  flashEnabled: boolean
  /** Flash intensity 0..1 — drives the glow's opacity. */
  flashIntensity: number
  /**
   * Sustain mode: hold the edge glow at
   * `flashIntensity` for as long as a prompt is open, instead of pulsing once.
   * Off by default — the shipped behaviour stays the one-shot pulse.
   */
  flashSustain: boolean
  /** The centered prompt window remembers where you last dragged it. */
  rememberPromptPosition: boolean
}

/** The one defaults object — exported for the upcoming "Reset to default" feature. */
export const PRIORITY_DING_DEFAULTS: PriorityDingSettings = {
  enabled: false,
  volume: 0.5,
  variant: 'ding',
  focusMode: 'always',
  flashEnabled: true,
  flashIntensity: 0.5,
  flashSustain: false,
  rememberPromptPosition: false,
}

function isDingFocusMode(v: unknown): v is DingFocusMode {
  return v === 'unfocused' || v === 'always' || v === 'muted-unfocused'
}

// ── Live settings (module state, persisted to localStorage) ──

const DING_SETTINGS_KEY = 'xmage-priority-ding'

/**
 * Loaded from localStorage at module init — the same pattern as uiSlice's
 * loadSettings, under its OWN key so this blob never trades unknown-key
 * behaviour with the main settings object.
 */
let settings: PriorityDingSettings = loadDingSettings()

function loadDingSettings(): PriorityDingSettings {
  try {
    const raw = localStorage.getItem(DING_SETTINGS_KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<PriorityDingSettings>
      return {
        enabled: parsed.enabled ?? PRIORITY_DING_DEFAULTS.enabled,
        volume: typeof parsed.volume === 'number' ? parsed.volume : PRIORITY_DING_DEFAULTS.volume,
        variant: isSoundVariant(parsed.variant) ? parsed.variant : PRIORITY_DING_DEFAULTS.variant,
        focusMode: isDingFocusMode(parsed.focusMode) ? parsed.focusMode : PRIORITY_DING_DEFAULTS.focusMode,
        flashEnabled: parsed.flashEnabled ?? PRIORITY_DING_DEFAULTS.flashEnabled,
        flashIntensity:
          typeof parsed.flashIntensity === 'number'
            ? Math.min(1, Math.max(0, parsed.flashIntensity))
            : PRIORITY_DING_DEFAULTS.flashIntensity,
        flashSustain: parsed.flashSustain ?? PRIORITY_DING_DEFAULTS.flashSustain,
        rememberPromptPosition:
          parsed.rememberPromptPosition ?? PRIORITY_DING_DEFAULTS.rememberPromptPosition,
      }
    }
  } catch { /* localStorage unavailable (test harness) */ }
  return PRIORITY_DING_DEFAULTS
}

/** Read the live ding settings. */
export function getDingSettings(): PriorityDingSettings {
  return settings
}

/** Apply a settings patch and persist it. Called by the store actions from the settings dialog. */
export function setDingSettings(patch: Partial<PriorityDingSettings>): PriorityDingSettings {
  settings = { ...settings, ...patch }
  try {
    localStorage.setItem(DING_SETTINGS_KEY, JSON.stringify(settings))
  } catch { /* localStorage unavailable (test harness) */ }
  return settings
}

/** Wipe persisted ding settings back to the defaults object (for "Reset to default"). */
export function resetDingSettings(): PriorityDingSettings {
  const next = setDingSettings(PRIORITY_DING_DEFAULTS)
  // Reset turns sustain OFF, so any glow it is holding on screen must come down
  // with it — otherwise a reset mid-prompt leaves a lit edge that nothing will
  // clear until the next prompt teardown. Fired unconditionally (clearing when
  // nothing is sustained is a no-op in the overlay).
  try {
    window.dispatchEvent(new CustomEvent(PRIORITY_FLASH_SUSTAIN_CLEAR_EVENT))
  } catch { /* no window (test harness) — nothing to clear */ }
  return next
}

// ── Focus gating ──

/**
 * The prompt-window position memory lives beside the flash event name because
 * both are consumed outside the store-driven render tree (ScreenFlash listens
 * on window; DecisionPopup reads localStorage directly at mount).
 */

/** DOM event: fire the edge-glow flash (detail: { intensity: 0..1 }). */
export const PRIORITY_FLASH_EVENT = 'xmage-priority-flash'

/**
 * DOM event: SUSTAIN the edge glow (detail: { intensity: 0..1 }) — raised
 * instead of PRIORITY_FLASH_EVENT when `flashSustain` is on. The overlay holds
 * the glow at that intensity until PRIORITY_FLASH_SUSTAIN_CLEAR_EVENT.
 */
export const PRIORITY_FLASH_SUSTAIN_EVENT = 'xmage-priority-flash-sustain'

/**
 * DOM event: the sustained glow is over (no detail) — the prompt was cleared,
 * the page went hidden, or sustain was switched off. Clearing when nothing is
 * sustained is a no-op, so the lifecycle sites can fire it unconditionally.
 */
export const PRIORITY_FLASH_SUSTAIN_CLEAR_EVENT = 'xmage-priority-flash-sustain-clear'

/**
 * Is a prompt currently OPEN? The truth for the sustain re-arm: the overlay
 * asks this on `visibilitychange → visible` instead of tracking its own flag
 * (an overlay-local "was sustained" would be stale after a hidden stretch in
 * which the prompt was answered).
 *
 * Reads `store.actionPrompt` — the same field the fresh-arrival decision above
 * reads as its own gate, so the two can never disagree. Null-safe on the store
 * argument for the module's own callers.
 */
export function isPromptOpen(store: { actionPrompt: unknown }): boolean {
  return store.actionPrompt != null
}

/** DOM event: the centered prompt window was dragged to a new position. */
export const PROMPT_POSITION_EVENT = 'xmage-prompt-position-changed'

/** localStorage key for the remembered prompt-window offset (root CSS px). */
export const PROMPT_POSITION_KEY = 'xmage-prompt-position'

/**
 * Is the browser window currently NOT focused? Two signals, OR-combined:
 *  - `document.visibilityState === 'hidden'` — tab hidden or window minimised.
 *  - `!document.hasFocus()` — the tab is VISIBLE but another window holds OS
 *    focus (second monitor, split screen). visibilityState alone misses exactly
 * that case, which is what "window is out of focus" means here.
 * Both are read at ding time — no listener bookkeeping to drift.
 */
function isWindowUnfocused(): boolean {
  return document.visibilityState === 'hidden' || !document.hasFocus()
}

// ── The hook ──

/** Epoch of the prompt the last ding decision was made for (-1 = none yet). */
let lastSeenEpoch = -1

/**
 * The payment the PREVIOUS prompt was asking mana for (`manaPromptSubject` of
 * its raw message), or null when the previous prompt was not a mana prompt.
 * A new play_mana with the SAME subject is the engine's per-land re-prompt —
 * a continuation of a payment the player is already working on, not a fresh
 * "your turn" moment.
 */
let previousManaSubject: string | null = null

/**
 * Store hook: a NEW action prompt was just installed (called from
 * `setActionPrompt` after the store update, right beside
 * `onPromptSetForAutoTapRun`). Sends nothing, mutates no store state.
 *
 * `promptEpoch` is the prompt-lifecycle counter, passed in by the caller
 * (gameplaySlice) — the module import would be a cycle, and the slice is the
 * only writer of that counter anyway.
 */
export function onPromptSetForPriorityDing(store: GameStore, promptEpoch: number): void {
  // Remember what THIS prompt is before any early return: it is the "previous
  // prompt" for the next arrival.
  const prompt = store.actionPrompt
  const myManaSubject = prompt && prompt.type === 'play_mana'
    ? manaPromptSubject(prompt.rawMessage || prompt.message)
    : null
  const prevManaSubject = previousManaSubject
  previousManaSubject = myManaSubject

  if (!prompt) return
  // The master gate is the UNION of both channels: the alert fires when EITHER
  // the sound or the flash is enabled. Each channel keeps its own gate below —
  // one off must not desync the other's dedupe/continuation state.
  if (!settings.enabled && !settings.flashEnabled) return

  // One decision per prompt arrival: the epoch advances exactly once per
  // installed prompt, so a same-epoch call is a duplicate invocation, not a
  // new prompt. (A cleared prompt re-arms this via onPromptClearedForPriorityDing.)
  if (promptEpoch === lastSeenEpoch) return
  lastSeenEpoch = promptEpoch

  // Internal re-prompts: the engine re-asks play_mana after every tapped land
  // (ManaCostsImpl.pay loop). Same payment again = the player is already on
  // it — an auto-tap run drives those re-prompts invisibly, and a manual
  // tapper is clicking through them. Never ding per land.
  if (myManaSubject !== null && prevManaSubject !== null && myManaSubject === prevManaSubject) return
  const run = getAutoTapRun()
  if (prompt.type === 'play_mana' && run.active && run.tappedIds.length > 0) return

  // Focus gating per the chosen mode (SOUND channel only — see module header:
  // the flash deliberately does not follow the sound's focus mode).
  const unfocused = isWindowUnfocused()
  switch (settings.focusMode) {
    case 'always':
      break
    case 'unfocused':
      if (!unfocused) return
      break
    case 'muted-unfocused':
      if (unfocused) return
      break
  }

  // SCREEN FLASH channel: edge glow via the ScreenFlash overlay. Suppressed
  // while the page is hidden (nothing to see) — visibilityState only, NOT the
  // sound's focus mode: visible-but-unfocused is where a flash helps most.
  // Two modes, one dispatch site:
  //  - sustain ON  → hold the glow (raise the sustain event; the overlay keeps
  //    it up until the clear event). The 500ms pulse is NOT fired: the glow is
  //    already up, and re-pulsing a live hold would flicker on every re-render/
  //    re-prompt of a wait the player is already looking at.
  //  - sustain OFF → the shipped one-shot pulse.
  if (settings.flashEnabled && document.visibilityState !== 'hidden') {
    if (settings.flashSustain) {
      window.dispatchEvent(
        new CustomEvent(PRIORITY_FLASH_SUSTAIN_EVENT, { detail: { intensity: settings.flashIntensity } })
      )
    } else {
      window.dispatchEvent(
        new CustomEvent(PRIORITY_FLASH_EVENT, { detail: { intensity: settings.flashIntensity } })
      )
    }
  }

  // SOUND channel.
  if (!settings.enabled) return
  try {
    playSoundVariant(settings.variant, settings.volume)
  } catch (e) {
    // Fail Fast: WebAudio missing/broken is a visible error, not a silent no-op.
    const errorText = e instanceof Error ? e.message : String(e)
    console.error('[priority-ding] play failed:', e)
    store.showError(`Priority ding error: ${errorText}`)
  }
}

/**
 * Store hook: the prompt was cleared or withdrawn (resolved by server state, a
 * local hide, a new game, a build error) — the same five sites that call
 * `onPromptClearedForAutoTapRun`. Forgets the continuation memory so a LATER
 * genuine payment (e.g. after a rollback re-asks) dings again, and re-arms the
 * epoch dedupe.
 *
 * Also the SUSTAIN-CLEAR channel: the sustained glow's whole lifecycle is
 * bounded by prompt-open, so it goes down with the prompt. Fired unconditionally
 * (a clear while nothing is sustained is a no-op in the overlay) — the five
 * sites stay one-liners and no site needs to know whether sustain is on. Game
 * end and new game are covered for free: `game_over` and `start_game` call
 * `hideActionPrompt`, and a server-proven resolution goes through
 * `setGameState`'s `promptResolvedBy` branch — all three call this hook.
 */
export function onPromptClearedForPriorityDing(): void {
  lastSeenEpoch = -1
  previousManaSubject = null
  try {
    window.dispatchEvent(new CustomEvent(PRIORITY_FLASH_SUSTAIN_CLEAR_EVENT))
  } catch { /* no window (test harness) — nothing to clear */ }
}
