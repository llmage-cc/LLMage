import type { ClientMessage, ServerMessage, ConnectRequest } from '@/types'

export type ConnectionStatus = 'disconnected' | 'connecting' | 'connected'

export interface WebSocketConfig {
  url: string
  onMessage: (message: ServerMessage) => void
  onStatusChange: (status: ConnectionStatus) => void
  onError: (error: Event) => void
  reconnectDelay?: number
}

/** Reconnect backoff cap — matches app.js _MAX_RECONNECT_DELAY_MS. */
const MAX_RECONNECT_DELAY_MS = 30_000

/**
 * Application close code for a deliberate auth rejection (proxy's 4401 — see
 * WebProxySession.CLOSE_CODE_AUTH_REJECTED). The tab must NOT auto-reconnect:
 * the auto-reconnect payload never carries the auth token (the
 * token is deliberately not persisted), so every retry can only be rejected
 * again. Show the form (the proxy already sent connect_error) and stop.
 */
const CLOSE_CODE_AUTH_REJECTED = 4401

/** Liveness probe timeout — matches app.js _LIVENESS_TIMEOUT_MS. */
const LIVENESS_TIMEOUT_MS = 5_000

/** Keepalive ping interval — matches app.js (2 minutes). */
const KEEPALIVE_INTERVAL_MS = 120_000

/**
 * WebSocket manager for XMage WebProxy communication.
 *
 * Ported from app.js connect/_doConnect/_scheduleReconnect/_forceReconnect/
 * _discardSocket/_startLivenessCheck/_setupRecoveryHandlers.
 *
 * Handles:
 * - Connection lifecycle (connect, disconnect, reconnect)
 * - Automatic reconnection with exponential backoff (capped at 30s, retries indefinitely)
 * - Message serialization/deserialization
 * - Keepalive ping every 2 minutes
 * - Tab visibility / network-online recovery (reconnects dead sockets, probes half-open ones)
 * - State version gap detection + resync requests
 */
export class GameWebSocket {
  private ws: WebSocket | null = null
  private config: WebSocketConfig
  private reconnectCount = 0
  private reconnectTimeout: ReturnType<typeof setTimeout> | null = null
  private isIntentionallyClosed = false

  /** Last received state version (stateSeq) from the server. */
  private lastStateVersion: number | null = null
  /** Whether resync has already been requested (debounce). */
  private resyncRequested = false

  /** Bound handlers for cleanup. */
  private visibilityHandler: (() => void) | null = null
  private onlineHandler: (() => void) | null = null
  private keepaliveInterval: ReturnType<typeof setInterval> | null = null

  /** Timestamp of the last message received from the server (liveness signal). */
  private lastMessageAt = 0
  /** Pending liveness verdict after a ping probe. */
  private livenessTimeout: ReturnType<typeof setTimeout> | null = null

  /** Connect form data — set by the UI, sent as the first message on WS open. */
  pendingConnectData: ConnectRequest | null = null

  /**
   * Register form data — set when the register dialog is used before any
   * connect attempt, sent as the first message on WS open.
   *
   * Why this exists: the register dialog lives on the connect overlay, where the
   * transport has usually never been opened. A bare send() would be dropped by
   * the readyState guard below (console warning only) and the dialog would sit on
   * "Registering…" until the user reloads — observed in the unit's live run.
   */
  pendingRegisterData: ClientMessage | null = null

  constructor(config: WebSocketConfig) {
    this.config = { reconnectDelay: 1000, ...config }
  }

  /** Connect to the WebSocket server. */
  connect(): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      // An OPEN transport is a legitimate state to reach this method from —
      // the register dialog opens the socket before any connect attempt, and the
      // user then clicks Connect on the same page. The old bare return left that
      // Connect click dead (nothing ever sent pendingConnectData). Send it now if
      // one is parked; a duplicate connect with nothing parked stays a no-op.
      if (this.pendingConnectData) {
        this.send(this.pendingConnectData)
        return
      }
      console.warn('[WebSocket] Already connected')
      return
    }

    // Drop a stale CONNECTING/CLOSING socket so its late events can't fire
    // against the new connection.
    this.discardSocket()

    this.isIntentionallyClosed = false
    this.config.onStatusChange('connecting')

    try {
      this.ws = new WebSocket(this.config.url)
      this.setupEventHandlers()
    } catch (error) {
      console.error('[WebSocket] Failed to create WebSocket:', error)
      this.config.onStatusChange('disconnected')
      this.scheduleReconnect()
    }
  }

  /**
   * Send a `register` frame, opening the transport first when it is not up.
   *
   * The register dialog sits on the connect overlay, where the transport has
   * usually never been opened. Sending blindly would be dropped by the
   * readyState guard; opening blindly would discard an in-flight connect
   * attempt. So: direct send when OPEN, park + let onopen send it when
   * CONNECTING, otherwise open the transport first.
   */
  register(payload: ClientMessage): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.send(payload)
      return
    }
    this.pendingRegisterData = payload
    if (this.ws?.readyState === WebSocket.CONNECTING) {
      return // the pending onopen handler sends it
    }
    this.connect()
  }

  /** Intentionally disconnect — stops reconnection, cleans up all handlers. */
  disconnect(): void {
    this.isIntentionallyClosed = true
    this.cancelReconnect()
    this.cancelLivenessCheck()
    this.teardownRecoveryHandlers()
    this.stopKeepalive()
    this.discardSocket()

    // Reset the backoff counter: user teardown is a clean slate, so the next
    // explicit connect() starts its backoff from 1s (not mid-sequence from a
    // previous failure run). Safe for the auth-reject loop: isIntentionallyClosed
    // is already true, so nothing auto-reconnects from here.
    this.reconnectCount = 0
    this.lastStateVersion = null
    this.resyncRequested = false
    this.config.onStatusChange('disconnected')
  }

  /** Send a message to the server. */
  send(message: ClientMessage): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      console.warn('[WebSocket] Cannot send — socket not open:', message.type)
      return
    }
    this.ws.send(JSON.stringify(message))
  }

  /**
   * Client-side recovery for a proxy-side XMage-link loss (`disconnected` frame →
   * connStatus 'connection_lost'): the browser WS is still OPEN, but the proxy's
   * XMage session is dead — and the proxy only dials XMage inside handleConnect,
   * so nothing on the wire ever re-dials (the red banner's
   * "reconnecting automatically" was unreachable for this failure class).
   *
   * Closing the socket drops the (dead) proxy session — keepMySessionActive=false
   * when no game is live, so nothing is preserved that we would want — and the
   * caller re-connects through the EXISTING machinery: connect() → onopen sends
   * pendingConnectData → the proxy's handleConnect dials XMage fresh. The proxy
   * re-dial is thereby owned by the proxy's own connect path; nothing here
   * changes the browser's reconnect/backoff policy (the auth-reconnect checks
   * own it): a connect failure after the recycle backs off exactly as any other
   * connect failure does, and a success resets the counter via
   * noteConnectAccepted().
   *
   * No-op when the transport is already gone (that case is owned by onclose →
   * scheduleReconnect) or when the manager was deliberately torn down.
   */
  recycleSession(reason: string): void {
    if (this.isIntentionallyClosed) return
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return
    console.log(`[WebSocket] Recycling session (proxy-side link lost): ${reason}`)
    this.discardSocket()
    this.connect()
  }

  /** Send a ping to the server. */
  ping(): void {
    this.send({ type: 'ping' })
  }

  /** Request a full state resync from the proxy. */
  requestResync(): void {
    if (this.resyncRequested) return
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return
    this.resyncRequested = true
    console.log('[WebSocket] Requesting state resync from proxy')
    this.send({ type: 'request_resync' })
  }

  /**
   * Called when a game_update/game_init/game_delta message arrives with a stateSeq.
   * If the version skips, we request a full resync from the proxy.
   */
  onStateVersionReceived(version: number): void {
    if (version === undefined || version === null) return
    if (this.lastStateVersion !== null && version > this.lastStateVersion + 1) {
      console.warn(
        `[WebSocket] State version gap: expected ${this.lastStateVersion + 1}, got ${version}. Requesting resync.`
      )
      this.lastStateVersion = version
      this.requestResync()
      return
    }
    this.lastStateVersion = version
    this.resyncRequested = false
  }

  /** Clear the resync debounce flag (called on resync_complete). */
  clearResyncFlag(): void {
    this.resyncRequested = false
  }

  /**
   * Fix 1 (auth-reconnect loop): the connect attempt actually SUCCEEDED — the
   * server sent the `connected` frame. Only now may the backoff counter reset.
   * Called from messageHandlers' `connected` case (via the store) — this module
   * does not parse messages itself.
   */
  noteConnectAccepted(): void {
    this.reconnectCount = 0
  }

  // ── Internal: connection lifecycle ──

  private setupEventHandlers(): void {
    const ws = this.ws
    if (!ws) return

    ws.onopen = () => {
      console.log('[WebSocket] Connected')
      // Fix 1 (auth-reconnect loop): do NOT reset reconnectCount here. The
      // socket OPENING is transport success only — the proxy may still reject
      // the connect message and close (auth rejection), and resetting on open
      // made every such close restart the backoff at 1s, a permanent ~1.2s
      // retry storm (~50 rejects/min per stuck tab). The counter now resets
      // only when the connect actually SUCCEEDS — on the `connected` server
      // frame, via noteConnectAccepted().
      this.lastMessageAt = Date.now()
      this.lastStateVersion = null
      this.resyncRequested = false
      this.config.onStatusChange('connected')

      // Send the connect message with the user's XMage server credentials.
      // This must be the first message after WS open — the proxy expects it
      // before any other commands.
      if (this.pendingConnectData) {
        this.send(this.pendingConnectData)
      } else {
        console.warn('[WebSocket] No pendingConnectData — connect message not sent')
      }
      // A register frame parked while the transport was CONNECTING (the
      // register dialog used during an in-flight auto-connect) still has to
      // leave — dropping it here would leave the dialog on "Registering…" with
      // nothing on the wire, which is exactly the silent-drop class this unit
      // exists to avoid. The proxy dispatches verbs by type with no ordering
      // requirement, so it rides directly after the connect payload.
      if (this.pendingRegisterData) {
        this.send(this.pendingRegisterData)
        this.pendingRegisterData = null
      }

      this.startKeepalive()
      this.setupRecoveryHandlers()
    }

    ws.onmessage = (event: MessageEvent) => {
      this.lastMessageAt = Date.now()
      const msg = JSON.parse(event.data) as ServerMessage
      this.config.onMessage(msg)
    }

    ws.onerror = (event: Event) => {
      // Don't set error state here — onclose will fire and handle reconnection.
      // Only surface error if we haven't connected yet (initial connect failure).
      this.config.onError(event)
    }

    ws.onclose = (event: CloseEvent) => {
      console.log('[WebSocket] Closed')
      this.config.onStatusChange('disconnected')
      this.cancelLivenessCheck()
      this.teardownRecoveryHandlers()
      this.stopKeepalive()

      // Fix 3 (auth-reconnect loop): the proxy's 4401 close means "auth
      // rejected — do not retry". The auto-reconnect payload never carries
      // the token, so every retry would be rejected again;
      // the proxy has already sent connect_error, which surfaced the form.
      if (event.code === CLOSE_CODE_AUTH_REJECTED) {
        console.log('[WebSocket] Auth rejection close (4401) — not reconnecting')
        this.cancelReconnect()
        this.reconnectCount = 0
        return
      }

      if (!this.isIntentionallyClosed) {
        this.scheduleReconnect()
      }
    }
  }

  // ── Reconnection with exponential backoff ──

  private scheduleReconnect(): void {
    if (this.reconnectTimeout) return
    this.reconnectCount++
    const delay = Math.min(
      1000 * Math.pow(2, this.reconnectCount - 1),
      MAX_RECONNECT_DELAY_MS
    )
    const attempt = this.reconnectCount
    console.log(`[WebSocket] Reconnecting in ${delay}ms (attempt ${attempt})`)
    this.reconnectTimeout = setTimeout(() => {
      this.reconnectTimeout = null
      this.connect()
    }, delay)
  }

  private cancelReconnect(): void {
    if (this.reconnectTimeout) {
      clearTimeout(this.reconnectTimeout)
      this.reconnectTimeout = null
    }
  }

  /**
   * Force reconnect immediately, resetting backoff. Used by visibility/online
   * recovery.
   *
   * Fix 1 (auth-reconnect loop): the counter reset is dropped. Resetting here
   * re-armed the 1s hammer for a stuck auth-reject tab on every tab switch
   * (visibilitychange fires forceReconnect when the socket isn't OPEN), even
   * with the onopen reset removed. The counter now resets ONLY via
   * noteConnectAccepted() — a forced reconnect that succeeds resets normally;
   * one that keeps failing keeps backing off. The immediate retry intent is
   * preserved: cancelReconnect + connect() still reconnects right away.
   */
  private forceReconnect(reason: string): void {
    if (this.isIntentionallyClosed) return
    console.log(`[WebSocket] Forcing reconnect: ${reason}`)
    this.cancelReconnect()
    this.cancelLivenessCheck()
    this.discardSocket()
    this.connect()
  }

  /** Detach handlers and close the current socket without status side effects. */
  private discardSocket(): void {
    const old = this.ws
    if (!old) return
    this.ws = null
    old.onopen = null
    old.onclose = null
    old.onerror = null
    old.onmessage = null
    try {
      old.close()
    } catch {
      // already closed
    }
  }

  // ── Liveness probe: send ping, reconnect if no response in 5s ──

  private startLivenessCheck(): void {
    if (this.livenessTimeout) return
    const probeSentAt = Date.now()
    this.ping()
    this.livenessTimeout = setTimeout(() => {
      this.livenessTimeout = null
      if (this.lastMessageAt < probeSentAt) {
        this.forceReconnect('no server response to liveness probe')
      }
    }, LIVENESS_TIMEOUT_MS)
  }

  private cancelLivenessCheck(): void {
    if (this.livenessTimeout) {
      clearTimeout(this.livenessTimeout)
      this.livenessTimeout = null
    }
  }

  // ── Keepalive ping every 2 minutes ──

  private startKeepalive(): void {
    this.stopKeepalive()
    this.keepaliveInterval = setInterval(() => {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        this.ping()
      }
    }, KEEPALIVE_INTERVAL_MS)
  }

  private stopKeepalive(): void {
    if (this.keepaliveInterval) {
      clearInterval(this.keepaliveInterval)
      this.keepaliveInterval = null
    }
  }

  // ── Tab visibility + network online recovery ──

  private setupRecoveryHandlers(): void {
    this.teardownRecoveryHandlers()

    this.visibilityHandler = () => {
      if (document.visibilityState !== 'visible' || this.isIntentionallyClosed) return
      if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
        this.forceReconnect('tab became visible while disconnected')
        return
      }
      // Tab is visible and socket claims open — request resync to recover
      // any messages missed while backgrounded, and probe liveness since
      // a socket can sit half-open after OS sleep without firing close.
      console.log('[WebSocket] Tab became visible, requesting resync')
      this.requestResync()
      this.startLivenessCheck()
    }

    this.onlineHandler = () => {
      if (this.isIntentionallyClosed) return
      if (this.ws && this.ws.readyState === WebSocket.OPEN) return
      this.forceReconnect('network came back online')
    }

    document.addEventListener('visibilitychange', this.visibilityHandler)
    window.addEventListener('online', this.onlineHandler)
  }

  private teardownRecoveryHandlers(): void {
    if (this.visibilityHandler) {
      document.removeEventListener('visibilitychange', this.visibilityHandler)
      this.visibilityHandler = null
    }
    if (this.onlineHandler) {
      window.removeEventListener('online', this.onlineHandler)
      this.onlineHandler = null
    }
  }
}