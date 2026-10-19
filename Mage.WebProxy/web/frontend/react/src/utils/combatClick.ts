import type { ActionPrompt, ActionChoice } from '@/types'

/**
 * Combat click routing — which wire answer a board/hand click sends.
 *
 * WHY this is a separate, pure function ("selecting/unselecting
 * attackers/blockers is bugged especially with lots of tokens"):
 *
 * The engine's declare loops (`HumanPlayer.selectAttackers` /
 * `selectBlockers`) re-fire GAME_SELECT after EVERY answer, and each re-prompt
 * carries a RENUMBERED choice list — the creature just declared is removed from
 * `possibleAttackers`/`possibleBlockers`, so every remaining creature's index
 * shifts down by one (measured live on a 12-token board: index 0→0, 1→1 is NOT
 * stable; a creature at index 5 moves to 4, then 3...).
 *
 * `select_choice` answers carry only that POSITION. The proxy rebuilds
 * `pendingChoiceBackings` for each re-prompt, so an index computed against an
 * older prompt resolves to a DIFFERENT creature on the newer one:
 *   - measured, 30 ms between two different-creature clicks: clicked
 *     `48154915…`, the engine declared `01e9234a…` (index 1 of the next list);
 *   - measured, 80–300 ms: the stale index fell outside the shrunken list →
 *     "Invalid choice index: N" error toasts for a normal click;
 *   - measured, 0 ms burst: the second `select_choice` hit the proxy after the
 *     first was consumed → stale-drop (the click silently died).
 *
 * The fix uses the engine's own UUID path for combat: the declare loops
 * dereference a responded UUID by IDENTITY (`selectDefender` /
 * `removeAttackerIfPossible` for attackers, `selectCombatGroup` /
 * `combat.removeBlocker` for blockers), so a renumbered list cannot misdeliver
 * it. This is exactly how the desktop client's DefaultActionCallback answers
 * combat (any clicked creature UUID goes to the server). Non-combat card
 * selection keeps the index path unchanged.
 */

/** True when the choice is a combat declare/undeclare answer (attack/block). */
export function isCombatChoice(choice: ActionChoice | undefined): boolean {
  return !!choice && (choice.action === 'attack' || choice.action === 'block')
}

/** The wire answer a click should send. `uuid` rides `play_card`. */
export type CardClickPlan = { kind: 'uuid'; cardId: string } | { kind: 'index'; index: number } | null

/**
 * Decide the wire answer for a click on `cardId` under `prompt`.
 *
 * - a COMBAT choice (action attack/block) → UUID (never a positional index);
 * - any other listed choice → its index (unchanged);
 * - an already-declared combat creature (not in the choice list — the engine
 *   lists only undeclared ones) → UUID: the server reads an already-attacking/
 *   already-blocking creature as "take it back" (see verify-undeclare.tsx);
 * - a non-combat prompt where the card is not a choice → UUID (play attempt,
 *   the desktop client's any-hand-click behavior);
 * - otherwise null: nothing to send.
 */
export function planCardClick(
  prompt: ActionPrompt | null | undefined,
  cardId: string | null | undefined,
  declaredCombatIds: ReadonlySet<string>,
): CardClickPlan {
  if (!cardId || !prompt) return null
  if (prompt.type !== 'select') return null
  const choice = prompt.choices.find((c) => c.id === cardId)
  if (choice) {
    // Combat answers go out as the creature UUID. The engine consumes one
    // answer per loop iteration and renumbers the list each time, so the index
    // is not a stable identity — the UUID is.
    if (isCombatChoice(choice)) return { kind: 'uuid', cardId }
    return { kind: 'index', index: choice.index }
  }
  if (declaredCombatIds.has(cardId)) return { kind: 'uuid', cardId }
  if (!prompt.isCombat) return { kind: 'uuid', cardId }
  return null
}
