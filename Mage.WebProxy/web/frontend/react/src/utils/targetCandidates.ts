/**
 * Target-prompt candidate helpers — the answer set for a GAME_TARGET prompt.
 *
 * Two families reach the popup through the same `lookAtCards` channel:
 *
 *  1. Card candidates (fetchland / Surveil / scry / deck search): `isValidTarget`
 *     marks the legal ones; the rest are shown greyed out as context. Click gate:
 *     `isValidTarget` (unchanged behaviour).
 *
 * 2. Trigger-order candidates:
 *     the engine's `PICK_ABILITY` prompt ("Pick triggered ability (goes to the stack
 *     first)"). Each entry is an ABILITY (id = ability UUID, name = source card,
 *     abilityText = engine-rendered trigger text) and the whole list IS the answer
 *     set — every entry is a legal answer. The click gate must be "has an id", not
 *     `isValidTarget`; gating on a target-validity flag is what rendered both tiles
 *     disabled and made the prompt unanswerable for 13 minutes in a live game.
 *
 * These helpers are pure so the behavioural harness can drive them under Node
 * (scripts/verify-trigger-order.sh) — the popup component itself needs a DOM.
 */
import type { LookAtCard, TargetPrompt } from '@/types'

/**
 * Human-readable label for one candidate.
 *
 * For an ability candidate the payload carries the SOURCE CARD in `name` (the raw
 * AbilityView name is the literal "Ability" — see WebProxyClient.candidateDisplayName)
 * plus the trigger text in `abilityText`. Both are shown so the player can tell two
 * triggers apart, which is the whole point of the ordering prompt.
 *
 * `abilityText` is set ONLY for ability candidates (the proxy adds it for AbilityView
 * entries), so ordinary cards label as their plain name — `rules` is deliberately NOT
 * consulted, or every Surveil/scry card would render its full rules text as a title.
 */
export function candidateLabel(card: LookAtCard): string {
  const source = card.sourceName || card.name || ''
  const text = card.abilityText || ''
  if (!text) return source
  if (!source) return text
  return `${source} — ${text}`
}

/**
 * Whether clicking a candidate is a legal answer to this prompt.
 *
 * Trigger-order: every listed entry is a legal answer (the server sent exactly the
 * orderable set), so the only requirement is an id to send back.
 * Otherwise: unchanged `isValidTarget` gate.
 */
export function isCandidateSelectable(prompt: Pick<TargetPrompt, 'triggerOrder'>, card: LookAtCard): boolean {
  if (!card.id) return false
  if (prompt.triggerOrder) return true
  return card.isValidTarget
}

/**
 * Header line for a candidate grid. Trigger order is a ONE-pick prompt even though
 * it lists N candidates: the header must ask for an order, not announce
 * an N-target selection.
 */
export function candidateGridHint(
  prompt: Pick<TargetPrompt, 'triggerOrder' | 'targetCount'> & { maxTargets?: number },
): string {
  if (prompt.triggerOrder) {
    return 'Choose which triggered ability goes on the stack first'
  }
  // The engine transmits no target min/max, so the proxy
  // reports target_count = 0 for every prompt except trigger-order (a real one-pick
  // claim). A count is therefore NEVER announced from this side — in particular not from
  // the candidate list, which is a different thing (it is how many cards COULD be
  // clicked, not how many must be). The first version of this fix kept a
  // `targetCount > 1` branch that still said "Select 13 targets" on a Marsh Flats
  // search, because the proxy's fallback fed it the candidate count.
  const candidates = prompt.maxTargets ?? 0
  if (candidates > 1) {
    return `Click a highlighted card to select it (${candidates} legal choices)`
  }
  return 'Click a highlighted card to select it'
}

/** Store actions a candidate click needs (passed in so this stays testable). */
export interface CandidateClickActions {
  selectTarget: (targetId: string) => void
  hideActionPrompt: () => void
}

/**
 * The single click path for a candidate tile — shared by every popup that renders
 * candidates (CardSelectPopup's grid, the right-rail TargetPrompt list).
 *
 * Returns true when the click was a legal answer and was dispatched; false when the
 * tile is not a legal answer for this prompt (so an onClick on a disabled tile is a
 * no-op rather than a misdelivered answer).
 */
export function clickCandidate(
  prompt: Pick<TargetPrompt, 'triggerOrder'>,
  card: LookAtCard,
  actions: CandidateClickActions,
): boolean {
  if (!isCandidateSelectable(prompt, card) || !card.id) return false
  actions.selectTarget(card.id)
  actions.hideActionPrompt()
  return true
}
