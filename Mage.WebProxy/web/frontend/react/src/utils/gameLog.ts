/**
 * Game-log export (the game-end dialog's "SAVE LOG").
 *
 * WHY THIS MODULE EXISTS
 * ----------------------
 * The desktop client's GameEndDialog SAVES the game log to disk on game end
 * (GameEndDialog.java:59-62 `saveGameLog` → `gamePanel.getGameLog()`, written as
 * a timestamped `gamelogs/<ts>_<type>_<n>.html` file, gated by the
 * KEY_GAME_LOG_AUTO_SAVE preference). The web client keeps the log only in the
 * browser (the RightRail's Game Log tab renders the live `eventLog`), so once
 * the session ends the log is gone.
 *
 * This module turns the browser's own event log into ONE plain-text document
 * that can be copied to the clipboard or downloaded as a `.txt`/`.log` file.
 * It runs the SAME post-loss cleanup the log tab runs (filterPostLossNoise,
 * utils/endGame.ts) so the saved text matches what the player actually read —
 * no dead-seat mulligan tail, no duplicate framing line for the fatal seat.
 *
 * It is PURE (no DOM, no store) so the harness exercises the exact string the
 * dialog hands to the clipboard/download, the same convention as
 * previewArtCandidates / endDialogKind.
 */

import { filterPostLossNoise } from './endGame'

/** The minimum an entry must expose — GameEvent satisfies this. */
export interface GameLogEntryLike {
  type: string
  message: string
}

/**
 * Format an epoch-ms stamp as `YYYY-MM-DD HH:MM` (local). Null/NaN → null so a
 * caller can omit the line rather than print "Invalid Date".
 */
export function formatLogStamp(ms: number | undefined): string | null {
  if (ms == null || !Number.isFinite(ms)) return null
  const d = new Date(ms)
  if (Number.isNaN(d.getTime())) return null
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/**
 * Build the saveable game log as plain text.
 *
 *  - the post-loss noise filter runs first (identical to the on-screen log),
 *  - blank messages are dropped (the wire occasionally carries empty lines),
 *  - a two-line header names the log and stamps it when `generatedAt` is given
 *    (the desktop names its file with the game start time; a text file needs the
 *    stamp INSIDE it to stay self-describing once copied),
 *  - an empty body returns '' — the caller then disables the save controls and
 *    never produces a header-only file that looks like a real log.
 */
export function buildGameLogText(input: {
  entries: GameLogEntryLike[]
  /** Optional title line (e.g. the match/game name). Omitted when blank. */
  title?: string | undefined
  /** Epoch-ms of the export; supplied by the caller so the function stays pure. */
  generatedAt?: number | undefined
}): string {
  const body = filterPostLossNoise(input.entries)
    .map((e) => (e.message ?? '').trim())
    .filter((m) => m.length > 0)

  if (body.length === 0) return ''

  const head: string[] = ['XMage game log']
  const title = (input.title ?? '').trim()
  if (title.length > 0) head.push(title)
  const stamp = formatLogStamp(input.generatedAt)
  if (stamp != null) head.push(stamp)

  return [...head, '', ...body].join('\n')
}

/** A filesystem-safe filename for the downloaded log. */
export function gameLogFileName(generatedAt: number | undefined): string {
  const stamp = formatLogStamp(generatedAt)
  if (stamp == null) return 'xmage-game-log.txt'
  return `xmage-game-log_${stamp.replace(/[-: ]/g, '')}.txt`
}
