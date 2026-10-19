/**
 * View deck (limited pool) — the read-only deck modal's state.
 *
 * WHY THIS MODULE EXISTS: the desktop client offers "View deck (me|playerName)" on a
 * player's in-game menu and opens a deck editor in VIEW_LIMITED_DECK mode
 * (PlayAreaPanel.java:436-447 → CallbackClientImpl → MageFrame.showDeckEditor). The web
 * client's `view_limited_deck` handler was a console.log stub. The proxy now serializes
 * the server's VIEW_LIMITED_DECK callback (WebProxyClient.sendLimitedDeck — the deck
 * resolved for the requesting viewer), and this module holds the answer until the modal
 * is closed.
 *
 * State lives in module scope — the same pattern as autoTapRun.ts / tokenArt.ts: the
 * store's declared shape (src/store/types.ts) is a shared file other workstreams edit,
 * and this feature needs exactly one nullable payload plus a subscribe pair.
 *
 * The answer carries NO separate seat name: the server ships
 * MatchPlayer.getDeckForViewer(), which REPLACES the deck name with the seat's own name
 * (upstream does this so Tiny Leaders cannot leak the real deck name), so `deckName` is
 * the seat name the user right-clicked.
 */
import type { DeckEntryLite } from '@/types'
import { useGameStore } from '../gameStore'

/** One received view-deck answer, normalized for rendering. */
export interface ViewDeckPayload {
  /** DeckView.getName() as the server sent it — the SEAT name for this path. */
  seatName: string
  cards: DeckEntryLite[]
  sideboard: DeckEntryLite[]
}

let current: ViewDeckPayload | null = null
const listeners = new Set<() => void>()

function notify(): void {
  for (const l of listeners) l()
}

/** Subscribe to view-deck changes (useSyncExternalStore contract). */
export function subscribeViewDeck(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** Current payload (stable between changes — safe as a useSyncExternalStore getter). */
export function getViewDeck(): ViewDeckPayload | null {
  return current
}

/**
 * The server answered a view-deck request — open the modal on this deck.
 * Called from the `view_limited_deck` message handler.
 */
export function receiveViewDeck(payload: ViewDeckPayload): void {
  current = payload
  notify()
}

/** Close the modal (Close button, or the deck it showed is no longer relevant). */
export function closeViewDeck(): void {
  if (current === null) return
  current = null
  notify()
}

/**
 * Ask the server for one seat's deck (the menu's "View deck" item).
 *
 * The store holds the live socket (`GameStore.ws`, the handle every slice's actions
 * use via `get().ws`) — read it directly, the same pattern CardThumb/TokenArtMenu use
 * for one-off sends. Fail Fast: with no socket the click would otherwise be a silent
 * no-op.
 */
export function requestViewDeck(playerId: string): void {
  const ws = useGameStore.getState().ws as
    | { send?: (m: Record<string, unknown>) => void }
    | null
    | undefined
  if (!ws || typeof ws.send !== 'function') {
    useGameStore.getState().showError('Not connected — cannot view that deck')
    return
  }
  ws.send({ type: 'view_limited_deck', playerId })
}
