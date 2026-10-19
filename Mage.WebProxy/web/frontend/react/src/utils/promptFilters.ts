/**
 * Display-only filters for the prompt popups — currently the "Only selectable"
 * switch on CardSelectPopup's library-search grid (fetch lands, Surveil, scry,
 * deck search).
 *
 * WHY A SEPARATE MODULE
 *  - The preference must outlive one prompt: every search is a fresh prompt and
 *    the popup body unmounts while no prompt is outstanding, so component state
 *    alone would forget the choice between two fetch lands of the same game.
 *  - It must not ride along in uiSlice's persisted settings blob (owned
 *    elsewhere), so the read/write pair lives here with its own key.
 *  - The predicate lives here too, so the filter and any future consumer agree
 *    on exactly one definition of "selectable".
 */
import { isCandidateSelectable } from '@/utils/targetCandidates'
import type { LookAtCard, TargetPrompt } from '@/types'

/** localStorage key for the "Only selectable" switch. Absent = OFF (the default). */
export const ONLY_SELECTABLE_KEY = 'xmage.promptFilter.onlySelectable'

/**
 * Whether the "Only selectable" filter HIDES this candidate.
 *
 * The filter must mirror the click gate EXACTLY (isCandidateSelectable — the same
 * predicate clickCandidate() uses to decide whether a click dispatches), because
 * both directions of a mismatch are defects:
 *  - a card the filter keeps but the gate refuses is a dead tile the player will
 *    click at and get no response from;
 *  - a card the filter hides but the gate would accept is a legal answer the
 *    player can no longer find.
 *
 * The wire carries the same information twice — `cards[].isValidTarget` and the
 * prompt-level `lookAtCardsValidIds` — and the proxy derives each per-card flag
 * from that id set (WebProxyClient.handleGameTarget: validTargetIdStrs.contains),
 * so the two never disagree. The gate is therefore the honest single source of
 * truth here, and trigger-order prompts (whose candidates are ALL legal answers)
 * filter to no-op for free.
 */
export function hidesCandidate(prompt: Pick<TargetPrompt, 'triggerOrder'>, card: LookAtCard): boolean {
  return !isCandidateSelectable(prompt, card)
}

/**
 * Read the persisted preference. Missing or unreadable storage is OFF.
 *
 * A blocked/unavailable localStorage (private mode, hard site restrictions) must
 * never take the game down over a display preference, hence the catch — same
 * trade-off uiSlice's settings loader makes.
 */
export function readOnlySelectablePref(): boolean {
  try {
    return localStorage.getItem(ONLY_SELECTABLE_KEY) === '1'
  } catch {
    return false
  }
}

/** Persist the preference (best-effort, matching uiSlice's settings writes). */
export function writeOnlySelectablePref(on: boolean): void {
  try {
    localStorage.setItem(ONLY_SELECTABLE_KEY, on ? '1' : '0')
  } catch {
    /* storage unavailable — the switch still works for the current prompt */
  }
}
