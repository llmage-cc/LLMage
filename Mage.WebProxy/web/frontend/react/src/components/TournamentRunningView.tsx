import { useEffect } from 'react'
import { useGameStore } from '@/store/gameStore'

/**
 * Tournament running view — shows standings, matches/rounds, and Watch buttons
 * for in-progress games. Mirrors the Java client's TournamentPanel.
 *
 * Auto-refreshes every 5 seconds to pick up match results and new rounds.
 */
export function TournamentRunningView() {
  const tournamentData = useGameStore((s) => s.tournamentData)
  const tournamentId = useGameStore((s) => s.tournamentId)
  const refreshTournament = useGameStore((s) => s.refreshTournament)
  const watchTournamentTable = useGameStore((s) => s.watchTournamentTable)
  const setShowTournamentRunning = useGameStore((s) => s.setShowTournamentRunning)
  const inGame = useGameStore((s) => s.inGame)
  const requestTables = useGameStore((s) => s.requestTables)

  // Poll for tournament updates every 5 seconds
  useEffect(() => {
    if (!tournamentId) return
    const interval = setInterval(() => {
      refreshTournament()
    }, 5000)
    return () => clearInterval(interval)
  }, [tournamentId, refreshTournament])

  if (!tournamentData) {
    return (
      <div className="lobby-view">
        <h2>Loading tournament...</h2>
      </div>
    )
  }

  const tour = tournamentData

  return (
    <div className="lobby-view">
      {/* Tournament header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
        <div>
          <h2>{tour.name}</h2>
          <div style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
            {tour.type} — {tour.state}
          </div>
        </div>
        <div style={{ display: 'flex', gap: '0.5rem' }}>
          <button
            className="btn btn-secondary btn-sm"
            onClick={() => refreshTournament()}
            data-explain="tour-refresh"
            title="Refresh tournament data"
          >
            Refresh
          </button>
          <button
            className="btn btn-secondary btn-sm"
            onClick={() => {
              setShowTournamentRunning(false)
              requestTables()
            }}
            data-explain="tour-back"
          >
            Back to Lobby
          </button>
        </div>
      </div>

      {/* Standings */}
      <div style={{ marginBottom: '1.5rem' }}>
        <h3 style={{ fontSize: '1rem', marginBottom: '0.4rem' }}>Standings</h3>
        <table className="tournament-table">
          <thead>
            <tr>
              <th style={{ textAlign: 'left' }}>Player</th>
              <th style={{ textAlign: 'center' }}>Points</th>
              <th style={{ textAlign: 'left' }}>State</th>
              <th style={{ textAlign: 'left' }}>Results</th>
            </tr>
          </thead>
          <tbody>
            {tour.players.map((player, idx) => (
              <tr
                key={idx}
                className={player.quit ? 'tournament-player-quit' : ''}
                style={player.quit ? { opacity: 0.5 } : undefined}
              >
                <td style={{ fontWeight: 600 }}>{player.name}</td>
                <td style={{ textAlign: 'center' }}>{player.points}</td>
                <td style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>{player.state}</td>
                <td style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>{player.results}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Matches / Rounds */}
      <div>
        <h3 style={{ fontSize: '1rem', marginBottom: '0.4rem' }}>Matches</h3>
        {tour.rounds.map((round, roundIdx) => {
          const hasGames = round.games.length > 0
          if (!hasGames) return null
          return (
            <div key={roundIdx} style={{ marginBottom: '1rem' }}>
              <div style={{ fontSize: '0.85rem', color: 'var(--text-muted)', marginBottom: '0.3rem' }}>
                Round {round.games[0]?.roundNum ?? roundIdx + 1}
              </div>
              <div className="tournament-matches">
                {round.games.map((game, gameIdx) => (
                  <div
                    key={gameIdx}
                    className="tournament-match-card"
                    style={{
                      display: 'flex',
                      justifyContent: 'space-between',
                      alignItems: 'center',
                      padding: '0.5rem 0.75rem',
                      marginBottom: '0.3rem',
                      background: 'var(--bg-card, #1e1e30)',
                      borderRadius: '4px',
                      border: '1px solid var(--border-color, #333)',
                    }}
                  >
                    <div style={{ flex: 1 }}>
                      <div style={{ fontWeight: 600, fontSize: '0.85rem' }}>{game.players}</div>
                      <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
                        {game.state}
                        {game.result && ` — ${game.result}`}
                      </div>
                    </div>
                    {game.canWatch && tour.watchingAllowed && (
                      <button
                        className="btn btn-primary btn-sm"
                        onClick={() => {
                          if (game.tableId) {
                            watchTournamentTable(game.tableId)
                          }
                        }}
                        style={{ marginLeft: '0.5rem' }}
                      >
                        Watch
                      </button>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )
        })}
      </div>

      {/* If watching a game, the GameView will render instead (inGame=true) */}
      {inGame && (
        <div style={{ marginTop: '1rem', padding: '0.5rem', background: 'var(--bg-card, #1e1e30)', borderRadius: '4px', fontSize: '0.8rem', color: 'var(--text-muted)' }}>
          You are now watching a game. Close the game view to return here.
        </div>
      )}
    </div>
  )
}