/**
 * The one definition of "the lobby cannot reach the server right now".
 *
 * After the websocket
 * dies the lobby still presented as connected and silently dropped every
 * action — the store kept `connected === true` (only the proxy's `connected`
 * frame or a logout clears it), and `GameWebSocket.send` warns to the console
 * and returns when the socket is not OPEN (src/network/websocket.ts:118-124).
 * The user clicked New Game / Refresh Tables / Start Match into a void.
 *
 * The lobby must treat ALL of these as "down" and disable + banner the lobby:
 *  - `connected === false` — no server session (connect overlay covers this in
 *    the normal flow, but a mid-session reset must never leave the lobby up).
 *  - `connStatus === 'reconnecting'` — transport lost; App.tsx's
 *    onStatusChange('disconnected') sets this exactly when `connected` is
 *    still true (App.tsx:143-148), and it persists through the whole
 *    auto-reconnect backoff ('connecting' does not touch it) until a real
 *    `connected` frame (setConnected clears connStatus) or an explicit
 *    disconnect/logout. NO reconnect/backoff policy lives here — this is
 * surfacing only.
 *  - `connStatus === 'connection_lost'` — the proxy told us its XMage-server
 *    link died (`disconnected` / `callback_error` frames,
 *    src/network/messageHandlers.ts:437-455).
 *
 * `resyncing` is deliberately healthy: a resync rides a live socket. (The
 * enum value exists in ConnStatusBanner's labels but nothing sets it today.)
 *
 * Pure so the store selector (store/selectors.ts selectSocketDown), the
 * slice guards (store/slices/lobbySlice.ts) and the Node harness all test the
 * exact same predicate — there must be no second opinion about "down".
 */
import type { ConnectionStatus } from '@/types'

export interface SocketDownState {
  connected: boolean
  connStatus: ConnectionStatus
}

/** True when lobby actions cannot reach the server and must be disabled. */
export function isSocketDown(s: SocketDownState): boolean {
  return !s.connected || s.connStatus === 'reconnecting' || s.connStatus === 'connection_lost'
}

/**
 * The lobby banner text — one source so the harness pins the exact wording
 * and LobbyView cannot drift. Surfacing contract: the banner must
 * (a) say the connection is down, (b) say what happened to the user's clicks
 * (they were dropped), and (c) say that reconnection is automatic (policy
 * unchanged — the auth-reconnect checks own it).
 */
export const LOBBY_SOCKET_DOWN_BANNER =
  'Connection to the server was lost. Your last clicks were NOT sent — lobby actions are disabled.'
  + ' The client is reconnecting automatically; reload the page if it does not recover.'

/** setupError text for dialog-launched sends (Start Game / Join) while down. */
export const SOCKET_DOWN_SETUP_ERROR =
  'Not connected to the server — the request was not sent. The client is reconnecting; try again once the connection is back.'

/** Toast text for lobby actions outside a dialog (Start Match) while down. */
export const SOCKET_DOWN_ACTION_ERROR =
  'Cannot start the match: connection to the server is down. The client is reconnecting — try again once it recovers.'