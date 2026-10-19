import type { FinishedMatch, ReplayFileMeta } from '@/types'

/**
 * Pure row logic for the replay browser (webui-replay-browser) — the merge,
 * dedupe, search, and sort model, extracted from ReplayBrowser.tsx so it can be
 * exercised without a DOM (scripts/verify-replay-browser.ts).
 *
 * Every replayable game — from the lobby's finished matches OR the server's
 * saved/*.game files — becomes one ReplayRow. Metadata quality varies by
 * source; legacy saves (no sidecar JSON) carry only the id + file date and are
 * marked so the UI can say so instead of showing empty columns.
 */

/** Unified row: one replayable game from either source. */
export interface ReplayRow {
  /** Game UUID to load. */
  gameId: string
  /** Which list the row came from (source badge + dedupe priority). */
  source: 'match' | 'file'
  /** Table/match name (finished matches) — saved files have none. */
  name: string
  gameType: string
  deckType: string
  /** Per seat, aligned arrays: names, deck names, match wins. */
  playerNames: string[]
  playerDecks: string[]
  wins: number[]
  /** Display score, e.g. "2–0" (highest seat wins first). Empty when unknown. */
  score: string
  /** Winner string from the sidecar, e.g. "Player X is the winner" (backfilled rows). */
  winner: string | null
  /** Row sort/search date: match end > match start > file mtime. */
  dateMs: number | null
  rated: boolean
  /** True when we know nothing but the game id (pre-sidecar save). */
  legacy: boolean
}

export type SortKey = 'date' | 'name' | 'players' | 'score' | 'deck' | 'type'
export type SortDir = 'asc' | 'desc'

/**
 * this fork: parse MatchView's display string ("Alice, Bob [quit]")
 * into player names for rows that lack structured playerList data (official
 * servers). Tolerates the "[quit]"/"[timer]"/"[idle]" suffixes; wins are
 * unknown for these rows (empty list → score renders blank).
 */
export function parsePlayersDisplay(players: string | null): string[] {
  if (!players) return []
  return players
    .split(',')
    .map((s) => s.trim().replace(/\s*\[(quit|timer|idle)\]\s*$/i, ''))
    .filter((s) => s.length > 0)
}

/** Epoch-ms date -> "2026-09-19 14:32". Null-safe (legacy rows show "—"). */
export function formatWhen(ms: number | null): string {
  if (ms == null || Number.isNaN(ms)) return '—'
  const d = new Date(ms)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/**
 * Duration between two epoch-ms instants, mm:ss — h:mm:ss once it crosses an
 * hour (GameEndDialog stats). Negative/inverted window or
 * null/infinite input -> null (the caller hides the row; never shows 0:00 for
 * data it does not have). Mirrors the desktop Format.getDuration granularity,
 * seconds-resolution, without its fixed hhh: leading zero.
 */
export function formatDurationWindow(startMs: number | undefined, endMs: number | undefined): string | null {
  if (startMs == null || endMs == null) return null
  if (!Number.isFinite(startMs) || !Number.isFinite(endMs)) return null
  const seconds = Math.floor((endMs - startMs) / 1000)
  if (seconds < 0) return null
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  const p = (n: number) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${p(m)}:${p(s)}` : `${p(m)}:${p(s)}`
}

/** "2–0" style score from the per-seat win counts (highest first). */
function scoreFromWins(wins: number[]): string {
  if (!wins.length) return ''
  const sorted = [...wins].sort((a, b) => b - a)
  return sorted.join('–')
}

/** Flatten a finished match into one row per replayable game. */
export function rowFromMatch(m: FinishedMatch, gameId: string): ReplayRow {
  // this fork: an OFFICIAL server's MatchView carries no playerList
  // rows (that is our fork's addition, read back as an empty list). Fall back
  // to the display string ("p1 2 / p2 1" style) so search + the player column
  // still work on stock-server data.
  const names = m.playerList.length ? m.playerList.map((p) => p.name) : parsePlayersDisplay(m.players)
  const decks = m.playerList.map((p) => p.deckName ?? '')
  const wins = m.playerList.length ? m.playerList.map((p) => p.wins) : []
  return {
    gameId,
    source: 'match',
    name: m.name || 'Untitled',
    gameType: m.gameType || '',
    deckType: m.deckType || '',
    playerNames: names,
    playerDecks: decks,
    wins,
    score: scoreFromWins(wins),
    winner: null,
    dateMs: m.endTime ?? m.startTime ?? null,
    rated: m.rated,
    legacy: false,
  }
}

/** Flatten a saved file's sidecar metadata into a row. */
export function rowFromFile(id: string, meta: ReplayFileMeta | undefined): ReplayRow {
  const players = meta?.players ?? []
  // Backfilled sidecars carry wins=null (match wins are not in a save).
  const wins = players.map((p) => p.wins ?? 0)
  // ISO strings from the sidecar; fall back to the .game file's mtime.
  const isoMs = (iso: string | null | undefined) => {
    if (!iso) return null
    const t = Date.parse(iso)
    return Number.isNaN(t) ? null : t
  }
  const dateMs = isoMs(meta?.endTime) ?? isoMs(meta?.startTime) ?? meta?.savedAtMs ?? null
  return {
    gameId: id,
    source: 'file',
    name: meta?.tableName || '',
    gameType: meta?.gameType || '',
    deckType: meta?.deckType || '',
    playerNames: players.map((p) => p.name),
    playerDecks: players.map((p) => p.deck ?? ''),
    wins,
    // Score only when at least one seat has a real win count (backfilled
    // rows know the winner, not the per-seat score).
    score: players.some((p) => p.wins != null) ? scoreFromWins(wins) : '',
    winner: meta?.winner ?? null,
    dateMs,
    rated: false,
    // No sidecar (or a sidecar with no players): nothing searchable beyond id+date.
    legacy: !players.length,
  }
}

/**
 * Merge both sources into deduped rows. A finished match's game ALSO exists as
 * a saved file; the match row wins (richer metadata). Files with no lobby
 * record (server restarted, match list capped at 25) come through alone.
 */
export function buildReplayRows(
  finishedMatches: FinishedMatch[],
  replayFiles: string[],
  fileMeta: Record<string, ReplayFileMeta>,
  sourceFilter: 'all' | 'match' | 'file',
): ReplayRow[] {
  const matchGameIds = new Set<string>()
  const matchRows: ReplayRow[] = []
  for (const m of finishedMatches) {
    if (!m.replayAvailable || m.games.length === 0) continue
    for (const g of m.games) {
      matchGameIds.add(g)
      matchRows.push(rowFromMatch(m, g))
    }
  }
  if (sourceFilter === 'match') return matchRows
  const fileRows = replayFiles
    .filter((id) => sourceFilter === 'file' || !matchGameIds.has(id))
    .map((id) => rowFromFile(id, fileMeta[id]))
  return sourceFilter === 'all' ? [...matchRows, ...fileRows] : fileRows
}

/** Case-insensitive substring across a row's searchable text. */
export function rowMatches(r: ReplayRow, q: string): boolean {
  if (!q) return true
  const needle = q.toLowerCase()
  const haystack = [r.name, r.gameType, r.deckType, r.winner ?? '', ...r.playerNames, ...r.playerDecks]
  return haystack.some((s) => s.toLowerCase().includes(needle))
}

export function compareRows(a: ReplayRow, b: ReplayRow, key: SortKey, dir: SortDir): number {
  const mul = dir === 'asc' ? 1 : -1
  const str = (s: string) => s.toLowerCase()
  switch (key) {
    case 'date': {
      // Rows without a date always sink to the bottom regardless of direction.
      if (a.dateMs == null && b.dateMs == null) return 0
      if (a.dateMs == null) return 1
      if (b.dateMs == null) return -1
      return (a.dateMs - b.dateMs) * mul
    }
    case 'name':
      return mul * str(a.name || a.gameId).localeCompare(str(b.name || b.gameId))
    case 'players':
      return mul * str(a.playerNames.join(', ')).localeCompare(str(b.playerNames.join(', ')))
    case 'score': {
      const max = (w: number[]) => (w.length ? Math.max(...w) : -1)
      return mul * (max(a.wins) - max(b.wins))
    }
    case 'deck':
      return mul * str(a.playerDecks.join(', ')).localeCompare(str(b.playerDecks.join(', ')))
    case 'type':
      return mul * str(a.gameType).localeCompare(str(b.gameType))
  }
}
