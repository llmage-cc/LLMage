import { useState } from 'react'
import { useGameStore } from '@/store/gameStore'
import { formatDurationWindow } from '@/utils/replayRows'
import { buildGameLogText, gameLogFileName } from '@/utils/gameLog'
import {
  END_DIALOG_TECHNICAL_NOTE,
  END_DIALOG_TITLES,
  endDialogKind,
  logHasTerminalFailure,
} from '@/utils/endGame'

/**
 * Game end dialog — mirrors the Java client's GameEndDialog.
 * Shows win/loss indicator, game info, match info, match score,
 * player life totals, and a compact statistics section with the
 * game and match durations (desktop: the Statistics tab pane,
 * GameEndDialog.java txtDurationGame/txtDurationMatch — the web dialog renders
 * them as a section; this dialog has no tab machinery).
 *
 * A win caused by an opponent's TERMINAL LLM-provider failure is presented
 * distinctly from a played-out win: the title reads
 * "Victory (by forfeit)" and a note names the cause. The discriminator is the
 * authoritative `endReason` the proxy puts on the rich game_over frame for the
 * viewer's own seat ("llm_failure" -> forfeit win, any other recorded reason ->
 * ordinary win). Only when the field is absent (a stock/older server, or a
 * watcher's null) does it fall back to the two-seat fatal-log-line inference —
 * see utils/endGame.ts for the full precedence.
 *
 * GLYPH SAFETY: the win/loss marker is a plain ASCII tag `[win]`/`[loss]`.
 * A trophy EMOJI has no guaranteed font on Linux hosts and rendered as the
 * tofu box in this dialog and the winner rows.
 */
export function GameEndDialog({ result }: { result: string }) {
  const setInGame = useGameStore((s) => s.setInGame)
  const requestTables = useGameStore((s) => s.requestTables)
  const gameEnd = useGameStore((s) => s.gameEnd)
  const eventLog = useGameStore((s) => s.eventLog)
  /**
   * Save-log feedback (P2): '' = idle, otherwise the transient result line
   * ("Copied…" / an error). Local state — it is presentation for one dialog
   * instance, nothing else consumes it.
   */
  const [logSaveNotice, setLogSaveNotice] = useState('')

  const hasWon = gameEnd?.hasWon ?? false
  const matchInfo = gameEnd?.matchInfo
  const additionalInfo = gameEnd?.additionalInfo
  const matchScore = gameEnd?.matchScore
  const players = gameEnd?.players
  const gameDuration = formatDurationWindow(gameEnd?.gameStartMs, gameEnd?.gameEndMs)
  const matchDuration = formatDurationWindow(gameEnd?.matchStartMs, gameEnd?.matchEndMs)

  // Technical/forfeit win vs played-out win — pure rule in utils/endGame.ts.
  // The end reason is the authoritative wire signal (rich game_over, own seat;
  // absent = stock/older server or watcher -> fallback). The player count is the
  // dialog's own life-total row (the seats this game had); the log flag is read
  // from the live event log the rail already fills.
  const kind = endDialogKind(
    hasWon,
    players?.length ?? 0,
    logHasTerminalFailure(eventLog),
    gameEnd?.endReason,
  )
  const isTechnical = kind === 'technical-victory'
  const titleColor = hasWon
    ? 'var(--accent-success, #4caf50)'
    : 'var(--color-danger, #f44336)'

  const handleClose = () => {
    useGameStore.setState({ gameEnd: null })
    setInGame(false)
    requestTables()
  }

  /**
   * P2 (SAVE LOG): the game log as plain text. Built at click time from the
   * dialog's own `eventLog` (the same list the Game Log tab renders), through
   * the post-loss filter — so the saved text matches what was read on screen.
   * `generatedAt` is stamped here (not at render) so the document time is the
   * moment of the save.
   */
  const logText = () => buildGameLogText({ entries: eventLog, title: matchInfo, generatedAt: Date.now() })

  /** True when there is a log worth saving — never offer a header-only file. */
  const hasLog = logText().length > 0

  const handleCopyLog = () => {
    const text = logText()
    if (text.length === 0) return
    if (!navigator.clipboard?.writeText) {
      setLogSaveNotice('Clipboard unavailable in this browser')
      return
    }
    navigator.clipboard.writeText(text).then(
      () => setLogSaveNotice('Log copied to clipboard'),
      (err) => setLogSaveNotice(`Copy failed: ${(err as Error).message}`),
    )
  }

  const handleDownloadLog = () => {
    const text = logText()
    if (text.length === 0) return
    // Same download mechanism the deck editor uses (ImportExportControls):
    // a temporary object URL on an anchor, revoked after the click.
    const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = gameLogFileName(Date.now())
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
    setLogSaveNotice('Log downloaded')
  }

  return (
    <div className="modal-overlay" style={{ display: 'flex' }}>
      <div className="modal-content" style={{ maxWidth: '500px' }}>
        <div className="modal-title" style={{ fontSize: '1.5rem', color: titleColor }}>
          {END_DIALOG_TITLES[kind]}
        </div>

        {isTechnical && (
          <div style={{
            margin: '0.5rem 0',
            padding: '0.4rem 0.6rem',
            borderLeft: '3px solid var(--color-danger, #f44336)',
            background: 'rgba(239, 68, 68, 0.08)',
            borderRadius: '4px',
            fontSize: '0.85rem',
            color: 'var(--text-primary)',
          }}>
            {END_DIALOG_TECHNICAL_NOTE}
          </div>
        )}

        {result && (
          <div style={{ margin: '0.5rem 0', fontSize: '0.95rem', color: 'var(--text-primary)' }}>
            {result}
          </div>
        )}

        {matchInfo && (
          <div style={{ margin: '0.25rem 0', fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
            {matchInfo}
          </div>
        )}

        {matchScore && (
          <div style={{ margin: '0.25rem 0', fontSize: '0.9rem', fontWeight: 'bold', color: 'var(--accent-primary)' }}>
            Match: {matchScore}
          </div>
        )}

        {players && players.length > 0 && (
          <div style={{ margin: '0.5rem 0', fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
            {players.map((p, i) => (
              <span key={i} style={{ marginRight: '1rem' }}>
                {p.name}: {p.life} life
              </span>
            ))}
          </div>
        )}

        {(gameDuration || matchDuration) && (
          <div style={{
            margin: '0.5rem 0',
            padding: '0.4rem 0.6rem',
            border: '1px solid var(--border-default, #2e2e38)',
            borderRadius: '6px',
            fontSize: '0.8rem',
            color: 'var(--text-secondary)',
          }}>
            {gameDuration && (
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span>Duration game:</span>
                <span style={{ fontVariantNumeric: 'tabular-nums' }}>{gameDuration}</span>
              </div>
            )}
            {matchDuration && (
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span>Duration match:</span>
                <span style={{ fontVariantNumeric: 'tabular-nums' }}>{matchDuration}</span>
              </div>
            )}
          </div>
        )}

        {additionalInfo && (
          <div style={{ margin: '0.25rem 0', fontSize: '0.8rem', color: 'var(--text-muted)' }}>
            {additionalInfo}
          </div>
        )}

        {/* SAVE LOG. The desktop GameEndDialog saves the game log
            on game end (GameEndDialog.java:59-121 saveGameLog → gamelogs/<ts>_*.html,
            gated by KEY_GAME_LOG_AUTO_SAVE). The web client keeps the log only in
            the browser, so this offers both a copy-to-clipboard and a .txt
            download of the SAME (post-loss-filtered) text the Game Log tab shows.
            The controls are disabled — never silently useless — when the log is
            empty, and the result line reports what happened. */}
        <div style={{ margin: '0.6rem 0 0.2rem' }}>
          <div style={{
            display: 'flex',
            gap: '0.5rem',
            alignItems: 'center',
            flexWrap: 'wrap',
          }}>
            <button
              className="btn btn-secondary btn-sm"
              onClick={handleCopyLog}
              disabled={!hasLog}
              data-explain="game-end-copy-log"
            >
              Copy Log
            </button>
            <button
              className="btn btn-secondary btn-sm"
              onClick={handleDownloadLog}
              disabled={!hasLog}
              data-explain="game-end-save-log"
            >
              Save Log
            </button>
            {logSaveNotice && (
              <span
                role="status"
                style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}
              >
                {logSaveNotice}
              </span>
            )}
          </div>
          {!hasLog && (
            <div style={{ marginTop: '0.3rem', fontSize: '0.72rem', color: 'var(--text-muted)' }}>
              No game log recorded for this game.
            </div>
          )}
        </div>

        <button className="btn btn-primary" style={{ marginTop: '1rem' }} onClick={handleClose}
        data-explain="game-end-close">
          Close
        </button>
      </div>
    </div>
  )
}
