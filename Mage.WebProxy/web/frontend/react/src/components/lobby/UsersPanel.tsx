import { useEffect } from 'react'
import { useGameStore } from '@/store/gameStore'
import { sortUsers } from '@/utils/lobbyChat'

/**
 * Online-users panel — stock parity with the Swing client's right-hand
 * users/flags column (TablesPanel's users list): one row per connected user
 * showing flag, name, ping and the match record. Self always sorts first.
 *
 * Data comes from the proxy's `users_list` response (lobbySlice.lobbyUsers,
 * mage.view.UsersView fields — see WebProxySession.serializeRoomUsers).
 *
 * Refresh model (mirrors the requestTables backstop in LobbyView):
 *  - requestUsers() on mount (instant first paint),
 *  - a 2s interval BACKSTOP only while this panel is mounted (the lobby is
 *    visible) — Chromium throttles background-tab timers to ~1/min, which is
 *    fine because nothing here is load-bearing,
 *  - a visibilitychange catch-up so a tab hidden during its last reply
 *    refreshes the moment it is looked at.
 */
export default function UsersPanel() {
  const lobbyUsers = useGameStore((s) => s.lobbyUsers)
  const proxyUsername = useGameStore((s) => s.proxyUsername)
  const requestUsers = useGameStore((s) => s.requestUsers)

  useEffect(() => {
    requestUsers()
    const interval = setInterval(() => requestUsers(), 2000)
    const onVisible = () => {
      if (document.visibilityState === 'visible') requestUsers()
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      clearInterval(interval)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [requestUsers])

  // Self-first, then case-insensitive by name (pure util — see sortUsers).
  const users = sortUsers(lobbyUsers, proxyUsername)

  return (
    <div className="lobby-users" data-explain="lobby-users">
      <div className="lobby-users-header">Users ({users.length})</div>
      <div className="lobby-users-list">
        {users.length === 0 && <div className="lobby-users-empty">No users online.</div>}
        {users.map((u) => {
          const isSelf = proxyUsername !== '' && u.userName.toLowerCase() === proxyUsername.toLowerCase()
          return (
            <div
              key={u.userName}
              className={`lobby-users-row${isSelf ? ' self' : ''}`}
              data-self={isSelf ? 'true' : undefined}
              title={`${u.matchHistory ?? ''} — quit ${u.matchQuitRatio}% — general ${u.generalRating}`}
            >
              <span className="lobby-users-flag" aria-hidden="true">
                {u.flagName ? u.flagName : '🌐'}
              </span>
              <span className="lobby-users-name">{u.userName}</span>
              {/* Mark your OWN row — the hunt could not tell which row was the
                  connected user (their own username never appeared). The tag is
                  additive and aria-hidden; the row already carries the `self`
                  class + data-self for styling/tests. */}
              {isSelf && (
                <span className="lobby-users-you" aria-hidden="true" title="This is you">
                  you
                </span>
              )}
              <span className="lobby-users-ping">{u.infoPing ?? ''}</span>
            </div>
          )
        })}
      </div>
    </div>
  )
}