/**
 * Behavioural checks for the "Current & Past Games" unified list
 * (webui-lobby gameslist).
 *
 * Run with:  bash scripts/verify-games-list.sh
 *
 * Exercises the REAL pure row logic (utils/gamesList.ts) — the merge/dedupe/
 * search/sort/action model the GamesList grid renders — over fixtures shaped
 * exactly like the proxy's `table_list`, `finished_matches`, and
 * `replay_files` wire payloads:
 *   - one row per replayable game of a finished match (NOT one per match),
 *   - live DUELING tables become LIVE rows; waiting tables do not appear,
 *   - dedupe: a match game id that also exists as a saved file keeps the
 *     match-sourced row (the replayRows.buildReplayRows rule),
 *   - default 'started' sort: LIVE rows always first, newest first within
 *     each group, undated rows sink,
 *   - duration formatting ("21 minutes", "1.2 hours") + live running elapsed,
 *   - spectatorsAllowed === false gates the Spectate action (absent = allowed),
 *   - pure action mapping: spectate / watch-replay / none,
 *   - search across players, decks, game type, deck type, table name.
 */
import type { FinishedMatch, ReplayFileMeta, TableInfo } from '../src/types'
import type { LobbyTableInfo } from '../src/utils/gamesList'
import {
  actionForRow,
  buildGameRows,
  compareGameRows,
  durationDisplay,
  formatDuration,
  gameRowMatches,
  resultDisplay,
  rowFromTable,
} from '../src/utils/gamesList'

let failures = 0
let checks = 0
function check(name: string, ok: boolean, detail = '') {
  checks++
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

const match = (over: Partial<FinishedMatch> = {}): FinishedMatch => ({
  matchId: 'm1', tableId: 't1', name: 'Table 1', gameType: 'Two Player Duel',
  deckType: 'Constructed', players: 'A, B', result: 'A [2-0], B [0-2]',
  tournament: false, replayAvailable: true,
  startTime: 1_727_000_000_000, endTime: 1_727_000_000_000 + 21 * 60_000, // 21 minutes
  rated: false,
  playerList: [
    { name: 'Alice', wins: 2, quit: false, deckName: 'Mono Red Burn' },
    { name: 'Bob', wins: 0, quit: false, deckName: 'Azorius Control' },
  ],
  games: ['g-match-1'],
  ...over,
})

const table = (over: Partial<LobbyTableInfo> = {}): LobbyTableInfo => ({
  id: 't-live-1',
  name: 'nick vs ry',
  gameType: 'Freeform Unlimited Commander',
  playerCount: 2,
  maxPlayers: 2,
  state: 'DUELING',
  seatNames: ['nicksunshi', 'ry'],
  createTimeMs: 1_727_000_000_000 + 5 * 60_000,
  ...over,
})

// ── 1. row building: one row per replayable game ──
const multiGame = match({ games: ['g-1', 'g-2', 'g-3'] })
const matchRows = buildGameRows([], [multiGame], [], {})
check('one row PER replayable game (3 games -> 3 rows)',
  matchRows.length === 3 && matchRows.every((r) => r.kind === 'replay' && r.source === 'match'),
  matchRows.map((r) => r.gameId).join(','))
check('match rows carry score + players + recorded duration',
  matchRows[0]!.score === '2–0' && matchRows[0]!.playerNames.join(',') === 'Alice,Bob'
    && matchRows[0]!.durationMs === 21 * 60_000)
check('match without a replay is not listed',
  buildGameRows([], [match({ replayAvailable: false })], [], {}).length === 0)

// ── 2. live rows ──
const live = table()
const liveRow = rowFromTable(live)
check('DUELING table -> live row with Spectate action',
  liveRow.kind === 'live' && liveRow.source === 'live' && liveRow.tableId === 't-live-1'
    && actionForRow(liveRow).id === 'spectate')
check('live row keeps state text + players from seats',
  resultDisplay(liveRow) === 'DUELING' && liveRow.playerNames.join(',') === 'nicksunshi,ry')
check('waiting table does NOT appear in the list',
  buildGameRows([table({ id: 'w1', state: 'WAITING' })], [], [], {}).length === 0)
check('live row started from createTimeMs; elapsed renders running',
  liveRow.startedMs === 1_727_000_000_000 + 5 * 60_000
    && durationDisplay(liveRow, 1_727_000_000_000 + 26 * 60_000) === '21 minutes')

// ── 3. merge + dedupe (replayRows rule: match wins) ──
// g-both exists as a finished match game AND a saved file; g-orphan only as a file.
const merged = buildGameRows(
  [live],
  [match({ games: ['g-both'] })],
  ['g-both', 'g-orphan'],
  {
    'g-both': { savedAtMs: 1_727_000_090_000, tableName: 'Sidecar table' },
    'g-orphan': undefined,
  },
)
check('merge: live + match + file rows all present',
  merged.length === 3
    && merged.filter((r) => r.kind === 'live').length === 1
    && merged.filter((r) => r.source === 'match').length === 1
    && merged.filter((r) => r.source === 'file').length === 1)
check('dedupe: match-sourced row wins over same-id saved file',
  merged.filter((r) => r.gameId === 'g-both').length === 1
    && merged.find((r) => r.gameId === 'g-both')?.source === 'match')
check('orphan file (no lobby record) still listed',
  merged.some((r) => r.gameId === 'g-orphan' && r.source === 'file'))

// ── 4. file rows: duration from sidecar start/end ──
const fileOnly = buildGameRows([], [], ['g-file-1'], {
  'g-file-1': {
    savedAtMs: 1_727_000_090_000,
    startTime: '2026-09-20T19:00:00Z',
    endTime: '2026-09-20T19:52:00Z',
    players: [{ name: 'Cara', wins: 1, quit: false, deck: 'Dragons' }],
  },
})
check('file row: duration from sidecar start/end (52 minutes)',
  fileOnly.length === 1 && fileOnly[0]!.durationMs === 52 * 60_000,
  String(fileOnly[0]?.durationMs))

// ── 5. default sort: live first, newest first, undated sink ──
const oldLive = rowFromTable(table({ id: 't-old', createTimeMs: 1_727_000_000_000 }))
const newLive = rowFromTable(table({ id: 't-new', createTimeMs: 1_727_000_100_000 }))
const newestReplay = buildGameRows([], [match({ games: ['r-new'], startTime: 1_727_000_200_000, endTime: 1_727_000_200_000 + 21 * 60_000 })], [], {})[0]!
const undatedReplay = buildGameRows([], [match({ games: ['r-undated'], startTime: null, endTime: null })], [], {})[0]!
const sorted = [undatedReplay, newestReplay, oldLive, newLive].sort((a, b) => compareGameRows(a, b, 'started', 'desc'))
check('default sort: LIVE rows always first (desc)',
  sorted[0]?.kind === 'live' && sorted[1]?.kind === 'live'
    && sorted[0]?.gameId === 't-new' && sorted[1]?.gameId === 't-old',
  sorted.map((r) => r.gameId).join(','))
check('default sort: replays newest-first after live, undated sinks',
  sorted[2]?.gameId === 'r-new' && sorted[3]?.gameId === 'r-undated')
const sortedAsc = [undatedReplay, newestReplay, oldLive, newLive].sort((a, b) => compareGameRows(a, b, 'started', 'asc'))
check('default sort: live STILL first in asc direction',
  sortedAsc[0]?.kind === 'live' && sortedAsc[1]?.kind === 'live' && sortedAsc[0]?.gameId === 't-old')

// ── 6. duration formatting ──
check('formatDuration: 21 minutes', formatDuration(21 * 60_000) === '21 minutes')
check('formatDuration: 1.2 hours', formatDuration(72 * 60_000) === '1.2 hours')
check('formatDuration: unknown -> empty string', formatDuration(null) === '' && formatDuration(0) === '')
check('durationDisplay: replay shows recorded length', durationDisplay(newestReplay, 0) === '21 minutes')
check('durationDisplay: live without createTimeMs -> blank', durationDisplay(rowFromTable(table({ id: 't-x', createTimeMs: null })), 5) === '')

// ── 7. spectatorsAllowed gating ──
check('spectate allowed when field absent',
  actionForRow(rowFromTable(table({ spectatorsAllowed: undefined }))).id === 'spectate')
const blocked = rowFromTable(table({ spectatorsAllowed: false }))
check('spectate blocked when spectatorsAllowed === false (id none, label kept)',
  actionForRow(blocked).id === 'none' && actionForRow(blocked).label === 'Spectate')
check('replay rows are always watchable (never gated)',
  actionForRow(newestReplay).id === 'watch-replay')

// ── 8. action mapping exhaustively ──
check('action ids cover all three cases',
  actionForRow(liveRow).id === 'spectate'
    && actionForRow(newestReplay).id === 'watch-replay'
    && actionForRow(blocked).id === 'none'
    && actionForRow(newestReplay).label === 'Watch Replay')

// ── 9. search ──
check('search: player name hits (live + replay)', gameRowMatches(liveRow, 'nick') && gameRowMatches(newestReplay, 'alice'))
check('search: deck name hits', gameRowMatches(newestReplay, 'azorius'))
check('search: game type hits', gameRowMatches(liveRow, 'commander'))
check('search: table name hits', gameRowMatches(liveRow, 'nick vs ry'))
check('search: deck type hits', gameRowMatches(newestReplay, 'constructed'))
check('search: non-hit returns false; empty query matches all',
  !gameRowMatches(newestReplay, 'zzz-nope') && gameRowMatches(newestReplay, ''))

// ── 10. other sort keys ──
const byPlayers = [newestReplay, liveRow].sort((a, b) => compareGameRows(a, b, 'players', 'asc'))
check('sort players: alphabetical asc across live + replay',
  byPlayers[0]?.gameId === newestReplay.gameId, byPlayers.map((r) => r.gameId).join(','))
const ffRow = buildGameRows([], [match({ gameType: 'Commander Free For All', games: ['ff'] })], [], {})[0]!
const byType = [newestReplay, ffRow].sort((a, b) => compareGameRows(a, b, 'type', 'asc'))
check('sort type: alphabetical asc', byType[0]?.gameId === 'ff', byType.map((r) => r.gameId).join(','))
const short = buildGameRows([], [match({ games: ['s'], startTime: 1_000, endTime: 1_000 + 8 * 60_000 })], [], {})[0]!
const byDuration = [newestReplay, short, undatedReplay].sort((a, b) => compareGameRows(a, b, 'duration', 'desc'))
check('sort duration: longest first, undated sinks', byDuration[0]?.gameId === newestReplay.gameId && byDuration[2]?.gameId === 'r-undated')

// ── 11. result cell conventions ──
check('result: replay prefers score, falls back to winner string, else em dash',
  resultDisplay(newestReplay) === '2–0'
    && resultDisplay(buildGameRows([], [], ['g-w'], { 'g-w': { winner: 'Player Alice is the winner' } })[0]!) === '[win] Player Alice is the winner'
    && resultDisplay(buildGameRows([], [], ['g-bare'], {})[0]!) === '—')
check('result: winner glyph is tofu-safe ASCII, never an emoji (🏆 renders as ▯)',
  resultDisplay(buildGameRows([], [], ['g-w'], { 'g-w': { winner: 'W' } })[0]!).startsWith('[win] ')
    && !resultDisplay(buildGameRows([], [], ['g-w'], { 'g-w': { winner: 'W' } })[0]!).includes('🏆'))

console.log(`\n${checks} checks: ${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)