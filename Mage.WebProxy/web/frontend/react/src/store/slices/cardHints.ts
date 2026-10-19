/**
 * Card hints helper — the modal's open/closed state.
 *
 * WHY A MODULE STORE (not the main store): the open flag is reachable from the
 * game header (the desktop launches the dialog from the player panel's "Hints"
 * button) while the modal itself mounts at App level next to ViewDeckModal. The
 * store's declared shape (src/store/types.ts) is a shared file other workstreams
 * edit, so this feature keeps its one flag in module scope — the same pattern as
 * viewDeck.ts / autoTapRun.ts.
 *
 * The MODAL's own view state (group-by, player filter, search text) deliberately
 * does NOT live here: the modal unmounts when closed, so every open starts from
 * the desktop dialog's fresh defaults (GROUP_BY_HINTS, FILTER_ALL, empty search).
 */

let open = false
const listeners = new Set<() => void>()

function notify(): void {
  for (const l of listeners) l()
}

/** Subscribe to open-state changes (useSyncExternalStore contract). */
export function subscribeCardHints(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** Whether the hints helper modal is open (stable — safe as a uSES getter). */
export function getCardHintsOpen(): boolean {
  return open
}

/** Open the helper (the header's "Hints" button — desktop btnToolHintsHelper). */
export function openCardHints(): void {
  if (open) return
  open = true
  notify()
}

/** Close the helper (its Close button). */
export function closeCardHints(): void {
  if (!open) return
  open = false
  notify()
}
