import { useEffect, useCallback, useRef, useState } from 'react'
import { useGameStore } from '@/store/gameStore'
import { selectCanStartMatch, selectSocketDown } from '@/store/selectors'
import { LOBBY_SOCKET_DOWN_BANNER } from '@/utils/socketDown'
import { rootZoom } from '@/utils/viewport'
import { GRID_POLL_MS, shouldPollGrid } from '@/utils/lobbyPoll'

/**
 * Lobby view — stock-lobby layout: actions row, two-column body (tables grid
 * + Current & Past Games list on the left; users panel + lobby chat on the
 * right), MOTD strip pinned at the bottom.
 *
 * The tables grid and games list live in components/lobby/ (pure row logic in
 * utils/tablesGrid.ts and utils/gamesList.ts, both harness-tested). Join/waiting
 * semantics (Start Match gating, Refresh Tables) are unchanged from the
 * table-card era — see the waiting-panel comments below.
 */
// ── Lobby pane resize constants (GameView.tsx's rail-drag pattern) ──
// Persisted values are ROOT CSS px (zoom-compensated: drag deltas are divided
// by the page zoom before use). The rail must never collapse and never crowd
// the games list off-screen; 640px cap leaves a usable main column at 1280.
const RAIL_MIN = 200
const RAIL_MAX = 640
const RAIL_DEFAULT = 280
// Users pane share of the rail height (%). Chat always keeps a usable
// minimum: 70% users max ⇒ 30% rail for chat; 10% min keeps the users
// list from vanishing.
const USERS_MIN = 10
const USERS_MAX = 70
const USERS_DEFAULT = 30

/** Clamp a value between min and max. */
const clamp = (v: number, min: number, max: number) => Math.max(min, Math.min(max, v))

/** Load a persisted number from localStorage, clamped, with fallback. */
function loadLobbySize(key: string, fallback: number, min: number, max: number): number {
  try {
    const v = localStorage.getItem(key)
    if (v != null) {
      const n = parseFloat(v)
      if (Number.isFinite(n)) return clamp(n, min, max)
    }
  } catch { /* ignore */ }
  return fallback
}

export default function LobbyView() {
  const tables = useGameStore((s) => s.tables)
  const finishedMatches = useGameStore((s) => s.finishedMatches)
  const replayFiles = useGameStore((s) => s.replayFiles)
  const joinWaiting = useGameStore((s) => s.joinWaiting)
  const connected = useGameStore((s) => s.connected)
  const requestTables = useGameStore((s) => s.requestTables)
  const showGameSetup = useGameStore((s) => s.showGameSetup)
  const showSetup = useGameStore((s) => s.showSetup)
  const showTournamentSetup = useGameStore((s) => s.showTournamentSetup)
  const showTournamentSetupDialog = useGameStore((s) => s.showTournamentSetupDialog)
  const startMatch = useGameStore((s) => s.startMatch)
  const myTableId = useGameStore((s) => s.myTableId)
  const isTableOwner = useGameStore((s) => s.isTableOwner)

  // After the websocket dies the lobby used
  // to keep rendering as connected while EVERY action was silently dropped —
  // GameWebSocket.send warns to the console and returns, so New Game /
  // Refresh Tables / Start Match clicked into a void (verified live: the
  // proxy never received the create_table frames, and WebSocket.prototype.send
  // instrumentation showed no send at all).
  //
  // Surfacing fix (no reconnect-policy change — the auth-reconnect checks
  // own that): while the socket is down show an explicit red banner at the top of
  // the lobby and disable the sends-the-server-actions buttons. Deck Editor
  // (local surface) and Settings stay enabled; Log out stays enabled as the
  // escape hatch. The banner clears on the next `connected` frame
  // (setConnected resets connStatus) — recovery needs no code here.
  const socketDown = useGameStore(selectSocketDown)

  // Offer "Start Match" only to the table's CREATOR, and only once every seat is filled —
  // the server rejects the start otherwise (`changeTableStateToStarting` requires state
  // READY_TO_START + all seats occupied). Mirrors the Swing client's TableWaitingDialog,
  // which the web client previously had no equivalent for.
  //
  // The predicate lives in the store selector so it is a pure function of `tables` — see
  // selectCanStartMatch for why it must never depend on a timer again.
  const canStartMatch = useGameStore(selectCanStartMatch)

  // Logout confirmation — only when seated at a table (myTableId set): leaving
  // mid-game/waiting abandons the seat server-side, so that case asks first.
  // Unseated logout exits immediately (no dialog). The store action is
  // stable across renders — no selector subscription needed.
  const [showLogoutConfirm, setShowLogoutConfirm] = useState(false)
  const logout = useGameStore.getState().logout

  // ── Pane resize state (GameView.tsx's rail-drag pattern) ──
  // railWidth: games list ↔ users/chat split. usersPercent: users ↔ chat
  // split inside the rail. Both persist to localStorage and survive reloads.
  const [railWidth, setRailWidth] = useState(() =>
    loadLobbySize('xmage-lobby-rail-w', RAIL_DEFAULT, RAIL_MIN, RAIL_MAX))
  const [usersPercent, setUsersPercent] = useState(() =>
    loadLobbySize('xmage-lobby-users-h', USERS_DEFAULT, USERS_MIN, USERS_MAX))
  const railRef = useRef<HTMLDivElement | null>(null)
  const railWidthRef = useRef(railWidth)
  railWidthRef.current = railWidth
  const usersPercentRef = useRef(usersPercent)
  usersPercentRef.current = usersPercent
  // Vertical drag state: rail column. Horizontal drag state: users/chat split.
  const railDragRef = useRef(false)
  const railDragStartX = useRef(0)
  const railDragStartW = useRef(0)
  const railDragStartZoom = useRef(1)
  const usersDragRef = useRef(false)
  const usersDragStartY = useRef(0)
  const usersDragStartPct = useRef(0)
  const usersDragStartZoom = useRef(1)

  const startRailDrag = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    railDragRef.current = true
    railDragStartX.current = e.clientX
    railDragStartW.current = railWidthRef.current
    railDragStartZoom.current = rootZoom()
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
  }, [])

  const startUsersDrag = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    usersDragRef.current = true
    usersDragStartY.current = e.clientY
    usersDragStartPct.current = usersPercentRef.current
    usersDragStartZoom.current = rootZoom()
    document.body.style.cursor = 'row-resize'
    document.body.style.userSelect = 'none'
  }, [])

  // Document-level mousemove/mouseup (registered once): the cursor leaves the
  // thin handles during a drag. Zoom compensation per GameView's rails.
  useEffect(() => {
    function onMouseMove(e: MouseEvent) {
      if (railDragRef.current) {
        // The rail hugs the viewport's right edge: cursor LEFT ⇒ rail WIDER.
        const delta = (railDragStartX.current - e.clientX) / railDragStartZoom.current
        setRailWidth(clamp(railDragStartW.current + delta, RAIL_MIN, RAIL_MAX))
        return
      }
      if (usersDragRef.current) {
        const railH = railRef.current?.clientHeight ?? 0
        if (railH <= 0) return
        // Down = taller users pane = higher users %.
        const deltaPct = ((e.clientY - usersDragStartY.current) / usersDragStartZoom.current / railH) * 100
        setUsersPercent(clamp(usersDragStartPct.current + deltaPct, USERS_MIN, USERS_MAX))
      }
    }
    function onMouseUp() {
      if (railDragRef.current) {
        railDragRef.current = false
        document.body.style.cursor = ''
        document.body.style.userSelect = ''
        try {
          localStorage.setItem('xmage-lobby-rail-w', String(railWidthRef.current))
        } catch { /* ignore */ }
      }
      if (usersDragRef.current) {
        usersDragRef.current = false
        document.body.style.cursor = ''
        document.body.style.userSelect = ''
        try {
          localStorage.setItem('xmage-lobby-users-h', String(usersPercentRef.current))
        } catch { /* ignore */ }
      }
    }
    document.addEventListener('mousemove', onMouseMove)
    document.addEventListener('mouseup', onMouseUp)
    return () => {
      document.removeEventListener('mousemove', onMouseMove)
      document.removeEventListener('mouseup', onMouseUp)
    }
  }, [])

  // ── Grid auto-refresh ──
  // The grid refreshes itself every GRID_POLL_MS while it is the visible
  // surface, so another player's new table shows up without a manual click.
  // The 2 s seated backstop below stays as-is (mutually exclusive: this effect
  // only runs while NOT seated). Chromium degrades background-tab timers to
  // ~1 tick/min, so nothing here is load-bearing — the visibilitychange
  // catch-up refetches the moment the tab is looked at again (same contract
  // as the seated backstop).
  useEffect(() => {
    if (!shouldPollGrid({ connected, joinWaiting, showSetup, showTournamentSetup })) return
    const interval = setInterval(() => requestTables(), GRID_POLL_MS)
    const onVisible = () => {
      if (document.visibilityState === 'visible') requestTables()
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      clearInterval(interval)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [connected, joinWaiting, showSetup, showTournamentSetup, requestTables])

  // While seated and waiting, ask the table list to re-read. This is a BACKSTOP only: the
  // proxy now pushes a `table_list` whenever the watched table's seats/state change (a WS
  // message, not a timer, so hidden tabs are not throttled) and that is what actually makes
  // the Start Match button appear. Chromium degrades this interval to roughly one tick per
  // minute in background tabs, so nothing here may be load-bearing

  //
  // The visibilitychange handler is the other half: a tab that was hidden while its last
  // reply arrived catches up the moment it is looked at, instead of waiting out the throttle.
  useEffect(() => {
    if (!joinWaiting) return
    const interval = setInterval(() => requestTables(), 2000)
    const onVisible = () => {
      if (document.visibilityState === 'visible') requestTables()
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      clearInterval(interval)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [joinWaiting, requestTables])

  return (
    <div className="lobby-view">
      {/* Explicit visible failure while the socket is down — the
          user must never sit in a lobby that looks alive but drops every
          click. role="alert" so it reaches the a11y tree (plain divs are
          pixels-only for screen readers), red bg so it is unmissable. */}
      {socketDown && (
        <div
          role="alert"
          data-explain="lobby-socket-down"
          style={{
            background: 'var(--error-color, #b00020)',
            color: 'white',
            padding: '8px 16px',
            fontWeight: 600,
            textAlign: 'center',
          }}
        >
          {LOBBY_SOCKET_DOWN_BANNER}
        </div>
      )}
      <div className="lobby-shell">
        <div className="lobby-header-row">
          {/* Neutral branding in stock builds. */}
          <h2>{__LLMAGE__ ? 'LLMage Web Client' : 'XMage Web Client'}</h2>
          <div className="lobby-actions">
        <button className="btn btn-primary" onClick={() => showGameSetup()}
        data-explain="lobby-new-game"
        disabled={socketDown}
        title={socketDown ? 'Connection is down — reconnecting' : undefined}>
          New Game
        </button>
        <button className="btn btn-primary" onClick={() => showTournamentSetupDialog()}
        data-explain="lobby-new-tournament"
        disabled={socketDown}
        title={socketDown ? 'Connection is down — reconnecting' : undefined}>
          New Tournament
        </button>
        <button className="btn btn-secondary" onClick={() => requestTables()}
        data-explain="lobby-refresh-tables"
        disabled={socketDown}
        title={socketDown ? 'Connection is down — reconnecting' : undefined}>
          Refresh Tables
        </button>
        <button className="btn btn-secondary" onClick={() => useGameStore.getState().openEditor()}
        data-explain="lobby-deck-editor">
          🛠 Deck Editor
        </button>
        <button className="btn btn-secondary" onClick={() => useGameStore.getState().setShowSettings(true)}
        data-explain="lobby-settings">
          ⚙ Settings
        </button>
        <button className="btn btn-secondary" onClick={() => (myTableId ? setShowLogoutConfirm(true) : logout())}
        data-explain="lobby-logout">
          Log out
        </button>
          </div>
        </div>

        <div className="lobby-columns">
        <div className="lobby-main">
          {/* Tables grid — stock-lobby-style dense table with filters
              (replaces the old table-card list; join/watch semantics unchanged).
              The "No tables available" line is a TRUE ZERO STATE only: the
              GamesList below shows live tables + finished matches + saved
              replays, so while ANY of those exist the empty line must NOT render
              above a populated list (bug-hunt: it appeared over a 19-game list).
              The list's own empty message ("No live games or replays yet.")
              covers the zero case. */}
          {!joinWaiting && !showSetup && !showTournamentSetup && (
            tables.length > 0 ? (
              <TablesGrid />
            ) : (
              finishedMatches.length === 0 && replayFiles.length === 0 && (
                <div style={{ textAlign: 'center', padding: '2rem', color: 'var(--text-secondary)' }}>
                  No tables available. Click "New Game" to create one.
                </div>
              )
            )
          )}

          {/* Joined table — waiting panel (Start Match gating unchanged). While seated,
              the grid is hidden to mirror the old table-card behavior. */}
          {joinWaiting && (
            <div style={{ textAlign: 'center', padding: '2rem', color: 'var(--text-secondary)' }}>
              <p style={{ fontSize: '1.1rem', marginBottom: '0.5rem' }}>
                Joined table — waiting for match to start...
              </p>
              <p style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>
                {canStartMatch
                  ? 'All seats are filled. Start the match when you are ready.'
                  : isTableOwner
                    ? 'Waiting for another player to join, then you can start the match.'
                    : 'Waiting for the table owner to start the match.'}
              </p>
              {canStartMatch && (
                <button
                  className="btn btn-primary"
                  style={{ marginTop: '1rem', marginRight: '0.5rem' }}
                  onClick={() => myTableId && startMatch(myTableId)}
                  data-explain="lobby-start-match"
                  disabled={socketDown}
                  title={socketDown ? 'Connection is down — reconnecting' : undefined}
                >
                  Start Match
                </button>
              )}
              <button
                className="btn btn-secondary"
                style={{ marginTop: '1rem' }}
                data-explain="lobby-refresh-tables"
                disabled={socketDown}
                title={socketDown ? 'Connection is down — reconnecting' : undefined}
                onClick={() => {
                  // Refresh the list WITHOUT dropping the waiting view: the user is
                  // still seated (myTableId set). Kept alongside the new Leave button
                  // A manual refresh is still a useful read of the table list,
                  // and removing it would strand the previous recovery habit.
                  requestTables()
                }}
              >
                Refresh Tables
              </button>
              {/* Leave the waiting table — the desktop TableWaitingDialog's
                  "Cancel" (SessionHandler.leaveTable). Before this there was NO
                  leave flow at all, so this panel could only be left by logging
                  out. The proxy's leave_table is PLAYER-ONLY and Fail-Fast; the
                  panel clears on the proxy's `table_left` confirmation. */}
              <button
                className="btn btn-danger"
                style={{ marginTop: '1rem', marginLeft: '0.5rem' }}
                data-explain="lobby-leave-table"
                disabled={socketDown}
                title={socketDown ? 'Connection is down — reconnecting' : undefined}
                onClick={() => useGameStore.getState().leaveTable()}
              >
                Leave
              </button>
            </div>
          )}

          {/* Current & Past Games — ONE list: live tables (Spectate) +
              finished matches + saved replay files (Watch Replay) */}
          {!joinWaiting && !showSetup && !showTournamentSetup && (
            <GamesList />
          )}
        </div>

        {/* Right rail — stock lobby's users column + chat panel (Talk/System tabs).
            Draggable: the vertical handle resizes the rail width, the horizontal
            one splits users ↔ chat height (both persisted, GameView pattern). */}
        <div
          className={`resize-handle lobby-resize-w${railDragRef.current ? ' dragging' : ''}`}
          onMouseDown={startRailDrag}
          data-explain="lobby-rail-resize"
        />
        <div className="lobby-right-col" ref={railRef} style={{ '--lobby-rail-w': `${railWidth}px` } as React.CSSProperties}>
          <div
            className="lobby-users-wrap"
            style={{ '--lobby-users-h': `${usersPercent}%` } as React.CSSProperties}
          >
            <UsersPanel />
          </div>
          <div
            className={`resize-handle resize-handle-h lobby-resize-h${usersDragRef.current ? ' dragging' : ''}`}
            onMouseDown={startUsersDrag}
            data-explain="lobby-users-chat-resize"
          />
          <LobbyChat />
        </div>
      </div>
      </div>

      {/* MOTD strip — blue bottom bar (renders nothing when the server has none) */}
      <MotdStrip />

      {showSetup && <GameSetupDialog />}

      {showTournamentSetup && <TournamentSetupDialog />}

      {/* Logout confirmation — only asked when seated (myTableId set): logging out
          from a table drops the seat server-side (the Leave button above is the
          direct path), so that case confirms first. Unseated logout exits immediately. */}
      {showLogoutConfirm && (
        <div className="modal-overlay" style={{ display: 'flex' }}>
          <div className="modal-content">
            <div className="modal-title">Log out?</div>
            <div className="modal-body" style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>
              You are seated at a table — logging out leaves your seat. The match will keep waiting for you on the server.
            </div>
            <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end', marginTop: '1rem' }}>
              <button className="btn btn-secondary btn-sm" onClick={() => setShowLogoutConfirm(false)}>
                Cancel
              </button>
              <button
                className="btn btn-danger btn-sm"
                onClick={() => {
                  setShowLogoutConfirm(false)
                  useGameStore.getState().logout()
                }}
                data-explain="lobby-logout-confirm"
              >
                Log out
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

// Import GameSetupDialog lazily — it's a heavy component
import GameSetupDialog from './GameSetupDialog'
import TournamentSetupDialog from './TournamentSetupDialog'
import TablesGrid from './lobby/TablesGrid'
import GamesList from './lobby/GamesList'
import UsersPanel from './lobby/UsersPanel'
import LobbyChat from './lobby/LobbyChat'
import MotdStrip from './lobby/MotdStrip'