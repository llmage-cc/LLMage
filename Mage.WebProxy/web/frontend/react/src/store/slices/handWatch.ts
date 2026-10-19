/**
 * Watched-hand switching — which GRANTED hand the board's hand
 * viewer is currently showing.
 *
 * WHY THIS MODULE EXISTS: the desktop client keeps ONE hand container and a
 * "Switch Hand" button (GamePanel.btnSwitchHands) that cycles between YOUR hand
 * and hands it has permission for — `handCards` is seeded from
 * `GameView.getWatchedHands()` and the opponent-hands map
 * (GamePanel.updateGame:1090-1134); the button is visible when more than one
 * hand is available (`handCards.size() > 1`). The web client's board renders
 * each seat's own hand section, so the least-invasive mirror is a viewer that
 * shows the granted hand ON TOP of the board, with one switch button per
 * granted seat — no change to PlayerBoard's layout or to the live hand's
 * click/play affordances (the granted hand is READ-ONLY: the engine never
 * offers its card ids as playable choices, and the desktop's watched-hand
 * container is inert for the same reason — it renders a hand you may look at,
 * not act from).
 *
 * State lives in module scope — the same pattern as viewDeck.ts / tokenArt.ts:
 * the store's declared shape (src/store/types.ts) is a shared file other
 * workstreams edit, and this feature needs exactly one selected-seat string
 * plus a subscribe pair. The HANDS themselves are NOT stored here: they arrive
 * on every state push as `gameState.watchedHands` (GameStateSerializer), and
 * this module only remembers WHICH one the user chose. Deriving the cards from
 * live state means a revoke or a drawn card updates the open viewer without any
 * extra bookkeeping.
 */
import { useSyncExternalStore } from 'react'

/** Which granted hand the viewer is showing; null = the viewer is closed. */
let selectedName: string | null = null
const listeners = new Set<() => void>()

function notify(): void {
  for (const l of listeners) l()
}

/** Subscribe to viewer changes (useSyncExternalStore contract). */
export function subscribeHandWatch(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** Current selection (stable between changes — safe as a getter). */
export function getSelectedHand(): string | null {
  return selectedName
}

/**
 * Show one seat's granted hand. `name` is the seat name the server keys
 * `watchedHands` by. Fail Fast on an empty name: it could never match a hand,
 * so opening the viewer on it would render a permanently empty panel.
 */
export function selectHand(name: string): void {
  if (!name) {
    throw new Error('selectHand: empty seat name — cannot select a hand that cannot exist')
  }
  if (selectedName === name) return
  selectedName = name
  notify()
}

/** Close the viewer (the × button, or the grant it showed was revoked). */
export function closeHandView(): void {
  if (selectedName === null) return
  selectedName = null
  notify()
}

/** React binding for the selected seat name. */
export function useSelectedHand(): string | null {
  return useSyncExternalStore(subscribeHandWatch, getSelectedHand, getSelectedHand)
}

// ── Allow-toggle intent (optimistic mirror of PERMISSION_REQUESTS_ALLOWED_*) ──
//
// WHY: the engine flips UserData.allowRequestShowHandCards but fires NO state
// push (GameController's PERMISSION_REQUESTS_ALLOWED_{ON,OFF} lands in
// PlayerImpl.sendPlayerAction and nothing calls updateGame), so the serialized
// `player.allowHandRequests` flag the checkbox reads stays stale until the next
// natural push — in a quiet two-player game that can be a full turn away, and a
// second click then re-sends the SAME value and the engine (idempotently)
// ignores it. The desktop has the same information gap and solves it
// CLIENT-side: the JCheckBoxMenuItem is constructed from the stored preference
// (PreferencesDialog KEY_GAME_ALLOW_REQUEST_SHOW_HAND_CARDS), not from the
// server view, so it always toggles. This module mirrors that: remember the
// value the user last SENT for their own seat; the serialized flag stays
// authoritative once a push arrives carrying a value that differs (a rejoin or
// a server-side reset wins — read `resolveAllowHandRequests` below).
let allowIntent: boolean | null = null

/** The value last sent for YOUR own seat's allow toggle (null = never sent). */
export function getAllowIntent(): boolean | null {
  return allowIntent
}

/** Record the value just sent (called by the store action, not by components). */
export function setAllowIntent(enabled: boolean): void {
  allowIntent = enabled
  notify()
}

/** React binding for the allow-toggle intent. */
export function useAllowIntent(): boolean | null {
  return useSyncExternalStore(subscribeHandWatch, getAllowIntent, getAllowIntent)
}

/**
 * The checkbox's value: the local intent if one was sent THIS session, else the
 * serialized server flag. Pass the live player flag; `undefined` (an older
 * payload) reads as false — the engine's own default for a fresh seat.
 */
export function resolveAllowHandRequests(serverFlag: boolean | undefined, intent: boolean | null): boolean {
  return intent != null ? intent : (serverFlag ?? false)
}
