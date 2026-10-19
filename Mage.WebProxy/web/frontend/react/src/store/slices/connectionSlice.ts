import type { StateCreator } from 'zustand'
import type { ConnectionState, GameStore } from '../types'
import { forgetGameKey } from './gameplaySlice'

/**
 * Delay before the client cycles its WebSocket after the proxy's XMage link is
 * reported dead (`disconnected` frame → connection_lost) while no game is live.
 *
 * Long enough for the proxy to finish tearing the dead session down (the WS
 * close maps to connectStop(false, keepMySessionActive=false) when no game is
 * live) so the fresh connect does not race a half-released username slot, short
 * enough that the red banner's "reconnecting automatically" reads as true.
 * The policy OWNERS (backoff, retry counts) are untouched — this delay only
 * spaces ONE deliberate recycle.
 */
export const CONNECTION_LOST_RECYCLE_DELAY_MS = 1500

/** Pending recycle timer — one at a time, module-scoped like uiSlice's toast timer. */
let recycleTimer: ReturnType<typeof setTimeout> | null = null

/** Cancel a pending connection_lost recycle (recovery happened / user left). */
function cancelRecycleTimer(): void {
  if (recycleTimer) {
    clearTimeout(recycleTimer)
    recycleTimer = null
  }
}

export const createConnectionSlice: StateCreator<
  GameStore,
  [],
  [],
  ConnectionState
> = (set, get) => ({
  // State
  ws: null,
  connected: false,
  connecting: false,
  connectError: '',
  connStatus: '',
  proxyUsername: '',
  // Pre-capability default: behave as LLMage (the UI only downgrades once the
  // server's own capabilities arrive in the `connected` message).
  capabilities: { llm: true, p1: false, replayScrubber: true, replaySidecar: true, serverVersion: 'unknown', playerTypes: [] },
  // Register dialog: no request in flight and no answer held.
  registering: false,
  registerResult: null,

  // Actions
  setWebSocket: (ws) => set({ ws }),

  setConnected: (username) => {
    // A fresh `connected` frame means the proxy session recovered — cancel
    // any pending connection_lost recycle so it cannot tear down a live socket.
    cancelRecycleTimer()
    set({
      connected: true,
      connecting: false,
      connectError: '',
      connStatus: '',
      proxyUsername: username,
    })
  },

  setCapabilities: (caps) => set({ capabilities: caps }),

  setConnecting: (value) => set({ connecting: value }),

  setConnectError: (message) =>
    set({ connectError: message, connecting: false }),

  setConnStatus: (status) => set({ connStatus: status }),

  /**
   * Send a `register` request to the proxy.
   *
   * The register dialog lives on the connect overlay, where the transport has
   * usually never been opened: nothing on that screen calls connect() before the
   * user submits the connect form. So this path mirrors the connect form's own
   * mechanism — park the payload on the manager as pendingRegisterData and let
   * the manager's onopen send it — instead of a bare ws.send(), which the
   * readyState guard drops with only a console warning (observed live: the dialog
   * stuck on "Registering…" with zero frames on the wire).
   *
   * Fail Fast (the same shape the connect form's missing-ws guard uses): a
   * missing manager is surfaced inline where the dialog reads it — never a
   * silent no-op. The proxy answers exactly one register_success /
   * register_error frame, which messageHandlers routes back into `registerResult`.
   */
  registerAccount: (payload) => {
    const { ws } = get()
    if (!ws) {
      set({
        registering: false,
        registerResult: { ok: false, message: 'Connection manager not ready — reload the page and try again.' },
      })
      return
    }
    set({ registering: true, registerResult: null })
    // The manager opens the transport when needed (register before any connect
    // attempt) and sends on open; an already-open socket takes the direct path.
    ws.register(payload)
  },

  clearRegisterResult: () => set({ registering: false, registerResult: null }),

  setRegisterResult: (result) => set({ registering: false, registerResult: result }),

  /**
   * The proxy's XMage-server link died (`disconnected`
   * frame). The proxy only dials XMage inside handleConnect, so with the browser WS
   * still OPEN nothing ever re-dials — the red banner's "reconnecting automatically"
   * was unreachable for this failure class (T1 defect D2; proxy-bounce recovery was
   * verified working, so the session re-creation path is sound).
   *
   * Lobby-context-only client fix: when NO game is live (never cycle mid-game),
   * schedule ONE deliberate WebSocket cycle after a short delay — the close drops
   * the dead proxy session and the EXISTING connect machinery (pendingConnectData →
   * proxy handleConnect) re-creates the session, which re-dials XMage. No reconnect/
   * backoff policy change.
   *
   * Trigger is the TRANSITION into connection_lost: repeat `disconnected` frames
   * (or callback_error, which can fire while the link is alive) must not cycle a
   * possibly-healthy session again. A later `connected` frame cancels a pending
   * recycle — the session recovered without one.
   */
  proxyLinkLost: () => {
    const wasAlreadyLost = get().connStatus === 'connection_lost'
    set({ connStatus: 'connection_lost' })
    if (wasAlreadyLost) return
    // Never cycle mid-game — the seat hangs off this exact session.
    if (get().inGame) return
    if (recycleTimer) return
    recycleTimer = setTimeout(() => {
      recycleTimer = null
      const { ws, inGame: gameLive, connStatus: status } = get()
      // Re-check at fire time: a `connected` frame or a game start in the
      // meantime invalidates the recycle.
      if (!ws || gameLive || status !== 'connection_lost') return
      ws.recycleSession('proxy XMage link lost — re-creating the session')
    }, CONNECTION_LOST_RECYCLE_DELAY_MS)
  },

  disconnect: () => {
    cancelRecycleTimer()
    // The tab's session with the proxy is over — forget the game id this tab
    // knew (gameplaySlice.forgetGameKey). A later connect either rejoins a game
    // (the proxy re-offers it via `rejoin_game`, which records the id again) or
    // starts a new one; a leftover key would only be stale state.
    forgetGameKey()
    const { ws } = get()
    if (ws) {
      ws.disconnect()
    }
    set({
      connected: false,
      connecting: false,
      connStatus: '',
      ws: null,
    })
  },

  // Log out — the browser's "start a new session" control (LobbyView).
  // Order matters:
  //   1. Clear saved credentials FIRST (sessionStorage['xmage-connect-creds']):
  //      ConnectOverlay auto-connects on mount whenever a username is saved, so
  //      leaving them would immediately undo the logout on the next render.
  //   2. Reset lobby seat state (joinWaiting / pendingJoinTableId / myTableId /
  //      isTableOwner) — the same fields game_over clears after a match ends.
  //      Without this, a logout while seated shows a phantom "waiting" banner.
  //   3. Tear down the WebSocket but KEEP the GameWebSocket manager in the
  //      store (ws.disconnect() only; do NOT null it). App.tsx constructs the
  //      manager once per page load, so a nulled field leaves the connect
  //      overlay permanently "Connection manager not ready". connect() resets
  //      isIntentionallyClosed and re-arms all handlers, so the same instance
  //      is reusable across logout → connect cycles.
  // Game-state reset needs no action here: selectCurrentView routes on
  // `connected`, so the connect overlay shows immediately; and the next
  // successful connect re-runs game_init/start_game, which rebuild or clear
  // all per-game state (verified against messageHandlers).
  logout: () => {
    // Leaving the page's session also drops a pending recycle.
    cancelRecycleTimer()
    // The game id this tab knew is part of the session being left: forget it
    // alongside the credentials so a later login in this tab cannot mistake the
    // previous game for one it is rejoining (gameplaySlice.forgetGameKey).
    forgetGameKey()
    // sessionStorage unavailable (harness/privacy mode) is environmental, not a
    // logic failure — same try/catch convention as ConnectOverlay/DecisionPopup.
    try {
      sessionStorage.removeItem('xmage-connect-creds')
    } catch { /* ignore */ }
    set({
      joinWaiting: false,
      pendingJoinTableId: null,
      myTableId: null,
      isTableOwner: false,
      mySeatNames: [],
    })
    const { ws } = get()
    if (ws) ws.disconnect()
    set({ connected: false, connecting: false, connStatus: '' })
  },

  onStateVersionReceived: (version) => {
    const { ws } = get()
    if (ws) ws.onStateVersionReceived(version)
  },

  // Fix 1 (auth-reconnect loop): the `connected` server frame is the ONLY
  // legitimate reset of the WS manager's backoff counter. Called from
  // messageHandlers' `connected` case.
  noteConnectAccepted: () => {
    const { ws } = get()
    if (ws) ws.noteConnectAccepted()
  },

  requestResync: () => {
    const { ws } = get()
    if (ws) ws.requestResync()
  },

  clearResyncFlag: () => {
    const { ws } = get()
    if (ws) ws.clearResyncFlag()
    set({ connStatus: '' })
  },
})