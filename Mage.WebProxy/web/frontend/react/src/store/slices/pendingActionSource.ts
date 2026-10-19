/**
 * Pending action source — the card the player just clicked to produce the
 * prompt now open.
 *
 * The proxy's target prompt carries only the candidate list; the prompt's
 * SOURCE (the bolt in hand, the activated permanent) is not transmitted as an
 * id. The client DOES know
 * which card the player activated: play_card / select_choice fires right
 * before the target prompt arrives. That id lives here (module scope, same
 * pattern as promptEpoch in gameplaySlice.ts) and TargetingArrows reads it to
 * draw source→target arrows; clearPendingActionSource() is called wherever the
 * prompt lifecycle proves resolution.
 *
 * Kept OUT of gameplaySlice's set() so the epoch/lifecycle guard semantics
 * there stay untouched (the prompt slice is additive-only).
 */

/** Card id of the source the player activated (play_card / select_choice). */
let pendingActionSourceId: string | null = null

/** When the click happened — setActionPrompt keeps a source only if its
 *  click is recent: the click→prompt window is one round-trip, and a prompt
 *  that arrives WITHOUT a preceding click (server-initiated ability prompt)
 *  must not inherit a minutes-old source from an unrelated earlier prompt. */
let pendingActionSourceAt = 0

/** Server-side source NAME when the prompt itself names one
 *  (WebProxyClient appends "... for <sourceName>" via options.secondMessage). */
let pendingActionSourceName: string | null = null

/** Click→prompt round-trip window the pending source stays valid for. */
const PENDING_SOURCE_TTL_MS = 5000

export function setPendingActionSource(cardId: string): void {
  pendingActionSourceId = cardId
  pendingActionSourceAt = Date.now()
}

export function setPendingActionSourceName(name: string): void {
  pendingActionSourceName = name
}

export function getPendingActionSourceId(): string | null {
  if (pendingActionSourceId == null) return null
  if (Date.now() - pendingActionSourceAt > PENDING_SOURCE_TTL_MS) return null
  return pendingActionSourceId
}

export function getPendingActionSourceName(): string | null {
  return pendingActionSourceName
}

/** The prompt resolved (state push, hide, game end) — forget it. */
export function clearPendingActionSource(): void {
  pendingActionSourceId = null
  pendingActionSourceName = null
  pendingActionSourceAt = 0
}