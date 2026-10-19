import type { FinishedMatch, ReplayFileMeta, TableInfo } from '@/types'
import { rowFromMatch, rowFromFile, type ReplayRow, type SortDir } from './replayRows'
import { WINNER_GLYPH } from './endGame'

/**
 * Pure row logic for the lobby's "Current & Past Games" list (webui-lobby
 * gameslist) — ONE grid merging the desktop lobby's two bottom panels:
 *   - live tables (stock: the tables list, DUELING rows offer Watch) →
 *     kind 'live', Spectate action,
 *   - finished matches (stock: the bottom completed-games table) → one row
 *     PER replayable game, kind 'replay', Watch Replay action,
 *   - saved/<uuid>.game files (stock: MageFrame's "Load Replay") → kind
 *     'replay', Watch Replay action.
 *
 * The replay half REUSES utils/replayRows.ts additively: rowFromMatch /
 * rowFromFile build the per-game rows (same field conventions, same
 * match-wins-dedupe rule), so the two tables can never drift apart. This
 * module adds what the unified list needs on top: the live-table row, the
 * recorded duration, the spectator gate, and the pure action mapping.
 *
 * No React imports — exercised without a DOM by scripts/verify-games-list.ts.
 */

/**
 * The parallel TableInfo change has landed in types/messages.ts, so this
 * interface is now a plain alias: the shared interface already carries all
 * the lobby fields (deckType/createTimeMs/rated/spectatorsAllowed/...).
 * Kept as a name so the row builder + harness keep their vocabulary.
 */
export type LobbyTableInfo = TableInfo

/** Unified row: one live game OR one replayable game. */
export interface GameRow extends Omit<ReplayRow, 'source'> {
  /**
   * Which list the row came from: replayRows' 'match' | 'file' for replays,
   * 'live' for live tables (the badge dedupe key uses it).
   */
  source: 'live' | 'match' | 'file'
  /** LIVE vs REPLAY — the Kind badge and the action column. */
  kind: 'live' | 'replay'
  /** Live rows: the table to spectate. Null for replay rows. */
  tableId: string | null
  /**
   * Live rows: when the game started (createTimeMs) — elapsed renders from it.
   * Replays: match start / sidecar start; null when unknown.
   */
  startedMs: number | null
  /** Replay rows: recorded length (end − start) in ms. Null when unknown. */
  durationMs: number | null
  /** Live rows: false ONLY when the table explicitly disables spectators. */
  spectatorsAllowed: boolean
  /** Live rows: the table's state text ('Dueling'). Replays: ''. */
  state: string
}

export type GameSortKey = 'started' | 'duration' | 'players' | 'type'
export type GameActionId = 'spectate' | 'watch-replay' | 'none'

export interface GameAction {
  id: GameActionId
  label: string
}

/** Epoch-ms date -> "2026-09-19 14:32" (replayRows.formatWhen conventions). */
export { formatWhen } from './replayRows'

/**
 * Humanized recorded length — the stock lobby's Duration column
 * ("21 minutes", "1.2 hours"). Null/non-positive -> '' (unknown, not zero).
 */
export function formatDuration(ms: number | null): string {
  if (ms == null || !Number.isFinite(ms) || ms <= 0) return ''
  if (ms < 3_600_000) {
    const minutes = Math.floor(ms / 60_000)
    return minutes < 1 ? '<1 minute' : `${minutes} minute${minutes === 1 ? '' : 's'}`
  }
  return `${(ms / 3_600_000).toFixed(1)} hours`
}

/** Duration cell text: live rows show RUNNING elapsed (now − start), replays the recorded length. */
export function durationDisplay(row: GameRow, nowMs: number): string {
  if (row.kind === 'live') {
    return row.startedMs != null ? formatDuration(nowMs - row.startedMs) : ''
  }
  return formatDuration(row.durationMs)
}

/** Result/Status cell: live -> state text ('Dueling'); replay -> score, else the winner string. */
export function resultDisplay(row: GameRow): string {
  if (row.kind === 'live') return row.state
  if (row.score) return row.score
  if (row.winner) return `${WINNER_GLYPH} ${row.winner}`
  return '—'
}

/**
 * Pure action mapping for the button column. id 'none' means "render the
 * label disabled with a tooltip" — a live table that disabled spectators has
 * no usable action, and replay rows are always watchable.
 */
export function actionForRow(row: GameRow): GameAction {
  if (row.kind === 'live') {
    if (row.spectatorsAllowed === false) return { id: 'none', label: 'Spectate' }
    return { id: 'spectate', label: 'Spectate' }
  }
  return { id: 'watch-replay', label: 'Watch Replay' }
}

/** Build the live half: one row per DUELING table. */
export function rowFromTable(table: LobbyTableInfo): GameRow {
  return {
    // ReplayRow base fields (rowFromMatch/rowFromFile shape — see replayRows.ts).
    gameId: table.id,
    source: 'live',
    name: table.name || 'Untitled',
    gameType: table.gameType || '',
    deckType: table.deckType ?? '',
    playerNames: table.seatNames ?? [],
    playerDecks: [],
    wins: [],
    score: '',
    winner: null,
    dateMs: table.createTimeMs ?? null,
    rated: table.rated ?? false,
    legacy: false,
    // GameRow extensions.
    kind: 'live',
    tableId: table.id,
    startedMs: table.createTimeMs ?? null,
    durationMs: null, // live duration is elapsed time — rendered from startedMs
    spectatorsAllowed: table.spectatorsAllowed !== false,
    state: table.state,
  }
}

/**
 * Merge all three sources into unified rows. Dedupe follows the SAME rule as
 * replayRows.buildReplayRows: a finished match's game id that also exists as a
 * saved file keeps the MATCH-sourced row (richer metadata); only orphan files
 * (server restart, match list cap) come through from the file list. Live
 * tables are never deduped against replays — spectating a live table and
 * watching its recording are different actions on the same game.
 */
export function buildGameRows(
  tables: LobbyTableInfo[],
  finishedMatches: FinishedMatch[],
  replayFiles: string[],
  fileMeta: Record<string, ReplayFileMeta>,
): GameRow[] {
  const rows: GameRow[] = []
  // Live: only DUELING tables — waiting/ready tables have nothing to watch yet.
  for (const t of tables) {
    if (t.state === 'DUELING') rows.push(rowFromTable(t))
  }
  // Finished matches: one row per replayable game, duration from the match
  // window (rowFromMatch's dateMs alone cannot yield end − start).
  const matchGameIds = new Set<string>()
  for (const m of finishedMatches) {
    if (!m.replayAvailable || m.games.length === 0) continue
    const duration = m.startTime != null && m.endTime != null ? m.endTime - m.startTime : null
    for (const g of m.games) {
      matchGameIds.add(g)
      rows.push(toReplayGameRow(rowFromMatch(m, g), m.startTime, duration))
    }
  }
  // Saved files: skip ids already covered by a match row (dedupe rule above).
  for (const id of replayFiles) {
    if (matchGameIds.has(id)) continue
    const meta = fileMeta[id]
    const start = isoToMs(meta?.startTime)
    const end = isoToMs(meta?.endTime)
    rows.push(toReplayGameRow(rowFromFile(id, meta), start, start != null && end != null ? end - start : null))
  }
  return rows
}

function toReplayGameRow(base: ReplayRow, startedMs: number | null, durationMs: number | null): GameRow {
  return {
    ...base,
    kind: 'replay',
    tableId: null,
    startedMs,
    durationMs,
    spectatorsAllowed: true,
    state: '',
  }
}

function isoToMs(iso: string | null | undefined): number | null {
  if (!iso) return null
  const t = Date.parse(iso)
  return Number.isNaN(t) ? null : t
}

/** Case-insensitive substring across a row's searchable text (rowMatches parity). */
export function gameRowMatches(r: GameRow, q: string): boolean {
  if (!q) return true
  const needle = q.toLowerCase()
  const haystack = [r.name, r.gameType, r.deckType, r.winner ?? '', ...r.playerNames, ...r.playerDecks]
  return haystack.some((s) => s.toLowerCase().includes(needle))
}

/**
 * Column sort. 'started' (the default) is the ONE key with a hard rule: LIVE
 * rows always come first, in either direction — this is the "current & past"
 * list, and a running game outranks every record. Within each group, undated
 * rows sink (replayRows' date rule) and direction applies. The other keys are
 * pure column sorts over all rows.
 */
export function compareGameRows(a: GameRow, b: GameRow, key: GameSortKey, dir: SortDir): number {
  const mul = dir === 'asc' ? 1 : -1
  const str = (s: string) => s.toLowerCase()
  switch (key) {
    case 'started': {
      if (a.kind !== b.kind) return a.kind === 'live' ? -1 : 1
      if (a.dateMs == null && b.dateMs == null) return 0
      if (a.dateMs == null) return 1
      if (b.dateMs == null) return -1
      return (a.dateMs - b.dateMs) * mul
    }
    case 'duration': {
      // Rows without a known duration (live rows, undated saves) sink.
      if (a.durationMs == null && b.durationMs == null) return 0
      if (a.durationMs == null) return 1
      if (b.durationMs == null) return -1
      return (a.durationMs - b.durationMs) * mul
    }
    case 'players':
      return mul * str(a.playerNames.join(', ')).localeCompare(str(b.playerNames.join(', ')))
    case 'type':
      return mul * str(a.gameType).localeCompare(str(b.gameType))
  }
}