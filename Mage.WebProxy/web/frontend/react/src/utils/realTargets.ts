import type { StackItem } from '@/types'

/**
 * REAL targeting arrows data: "They're supposed to show the
 * targets of spells, abilities, and attacks, not potential targets of them."
 *
 * The engine already exposes the ACTUAL chosen targets of every stack object:
 * CardView.targets (mage.view.CardView) is filled from the spell/ability's
 * resolved Target objects while it sits on the stack, GameStateSerializer.
 * serializeCard forwards that list as `targets` (and `source_card` for
 * abilities), and the store carries both on StackItem — no wire change needed,
 * this helper only picks the data up for rendering.
 *
 * Deliberately READS NOTHING from the open prompt: while a target prompt is
 * open the candidates are valid targets-to-be (potential), and arrows must
 * NEVER preview them. Potential targets surface as the selectable highlight
 * only (targetIds → card-target), which is the existing selects path.
 */
export interface StackTargetArrow {
  /** Id of the stack item — the stack tile's data-card-id. */
  sourceId: string
  /**
   * Name of the ability's source card (serializer's source_card). Fallback
   * arrow origin when the item itself renders no tile with its id.
   */
  sourceName: string
  /** UUIDs of the already-chosen targets (cards on board/stack). */
  targetIds: string[]
}

/**
 * Source→target pairs for the current stack. Pure so the behavioural harness
 * (scripts/verify-not-target-arrows.tsx) asserts the exact decision the
 * TargetingArrows effect renders from, with no DOM.
 */
export function realTargetArrows(
  stack: StackItem[] | null | undefined,
): StackTargetArrow[] {
  const out: StackTargetArrow[] = []
  if (!stack) return out
  for (const item of stack) {
    if (!item || !item.targets || item.targets.length === 0) continue
    if (!item.id && !item.source_card) continue // unanchored — cannot draw an arrow
    out.push({
      sourceId: item.id ?? '',
      sourceName: item.source_card ?? item.name ?? '',
      targetIds: [...item.targets],
    })
  }
  return out
}
