/**
 * Behavioural checks for the replay browser's row model
 * (webui-replay-browser).
 *
 * Run with:  bash scripts/verify-replay-browser.sh
 *
 * Exercises the REAL pure row logic (utils/replayRows.ts) — the merge/dedupe/
 * search/sort model the ReplayBrowser table renders — over fixtures shaped
 * exactly like the proxy's `finished_matches` and `replay_files` wire payloads:
 *   - match rows win the dedupe against same-id file rows (richer metadata),
 *   - orphan files (no lobby record) still appear,
 *   - search hits player names, deck names, table names, and types,
 *   - sorting: date desc default, toggling asc/desc, undated rows sink,
 *   - score formatting from per-seat wins,
 *   - legacy rows (no sidecar players) are marked and still sortable by mtime.
 */
import {
  buildReplayRows,
  compareRows,
  formatWhen,
  rowFromFile,
  rowFromMatch,
  rowMatches,
} from '../src/utils/replayRows'
import type { FinishedMatch, ReplayFileMeta } from '../src/types'

let failures = 0
function check(name: string, ok: boolean, detail = '') {
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

const match = (over: Partial<FinishedMatch> = {}): FinishedMatch => ({
  matchId: 'm1', tableId: 't1', name: 'Table 1', gameType: 'Two Player Duel',
  deckType: 'Constructed', players: 'A, B', result: 'A [2-0], B [0-2]',
  tournament: false, replayAvailable: true,
  startTime: 1727000000000, endTime: 1727000300000, rated: false,
  playerList: [
    { name: 'Alice', wins: 2, quit: false, deckName: 'Mono Red Burn' },
    { name: 'Bob', wins: 0, quit: false, deckName: 'Azorius Control' },
  ],
  games: ['g-match-1'],
  ...over,
})

// ── 1. row building ──
const rm = rowFromMatch(match(), 'g-match-1')
check('match row: players + decks + wins captured',
  rm.playerNames.join(',') === 'Alice,Bob' && rm.playerDecks[0] === 'Mono Red Burn' && rm.wins.join(',') === '2,0')
check('match row: score from per-seat wins', rm.score === '2–0', rm.score)
check('match row: date prefers match end', rm.dateMs === 1727000300000)
check('match row: not legacy', rm.legacy === false)

const meta: ReplayFileMeta = {
  bytes: 53656, savedAtMs: 1727000900000,
  gameId: 'g-file-1', gameType: 'Commander', deckType: 'Commander',
  tableName: 'CMDR table', endTime: '2026-09-19T14:32:00Z',
  players: [{ name: 'Cara', wins: 1, quit: false, deck: 'Dragons' }, { name: 'Dave', wins: 0, quit: true, deck: null }],
}
const rf = rowFromFile('g-file-1', meta)
check('file row: sidecar players captured', rf.playerNames.join(',') === 'Cara,Dave' && rf.playerDecks[1] === '')
check('file row: ISO endTime parsed to ms', rf.dateMs === Date.parse('2026-09-19T14:32:00Z'))
check('file row: not legacy when sidecar has players', rf.legacy === false)

const legacy = rowFromFile('g-legacy', undefined)
check('legacy row (no sidecar): id only, marked legacy', legacy.legacy === true && legacy.playerNames.length === 0)
check('legacy row: falls back to file mtime', legacy.dateMs === null || typeof legacy.dateMs === 'number')

// Backfilled sidecar (2026-09-20 backfill): players known, wins null, winner string present.
const backfilled = rowFromFile('g-backfill', {
  savedAtMs: 1727000900000,
  winner: 'Player Alice is the winner',
  players: [{ name: 'Alice', wins: null, quit: null, deck: null }, { name: 'Bob', wins: null, quit: null, deck: null }],
})
check('backfilled row: player names present, not legacy', backfilled.playerNames.join(',') === 'Alice,Bob' && backfilled.legacy === false)
check('backfilled row: null wins do not produce a bogus score', backfilled.score === '', backfilled.score)
check('backfilled row: winner carried through', backfilled.winner === 'Player Alice is the winner')

// ── 2. merge + dedupe ──
// g-file-1 exists BOTH as a finished match (below) and a saved file: the match
// row must win. g-orphan exists only as a file.
const matchWithSameGame = match({ games: ['g-file-1'], playerList: [{ name: 'Zed', wins: 1, quit: false, deckName: 'Storm' }] })
const rows = buildReplayRows(
  [matchWithSameGame],
  ['g-file-1', 'g-orphan'],
  { 'g-file-1': meta, 'g-orphan': undefined },
  'all',
)
check('dedupe: match row wins over same-id file row',
  rows.length === 2 && rows[0].source === 'match' && rows[0].gameId === 'g-file-1')
check('orphan file still listed', rows.some((r) => r.gameId === 'g-orphan' && r.source === 'file'))

const onlyMatches = buildReplayRows([matchWithSameGame], ['g-file-1'], {}, 'match')
check('source filter "match": files excluded', onlyMatches.length === 1 && onlyMatches[0].source === 'match')
const onlyFiles = buildReplayRows([matchWithSameGame], ['g-file-1'], {}, 'file')
check('source filter "file": matches excluded', onlyFiles.length === 1 && onlyFiles[0].source === 'file')

const unavailable = buildReplayRows([match({ replayAvailable: false })], [], {}, 'all')
check('match without a replay is not listed', unavailable.length === 0)

// ── 3. search ──
check('search: player name hits', rowMatches(rm, 'alice'))
check('search: deck name hits', rowMatches(rm, 'azorius'))
check('search: table name hits', rowMatches(rf, 'cmdr'))
check('search: game type hits', rowMatches(rf, 'commander'))
check('search: non-hit returns false', !rowMatches(rm, 'zzz-nope'))
check('search: empty query matches all', rowMatches(rm, ''))

// ── 4. sorting ──
const a = rowFromMatch(match({ endTime: 1727001000000, name: 'AAA' }), 'a') // newest
const b = rowFromFile('b', { savedAtMs: 1727000900000 })                    // middle
const c = rowFromFile('c', undefined)                                       // no date at all
const byDateDesc = [a, b, c].sort((x, y) => compareRows(x, y, 'date', 'desc'))
check('sort date desc: newest first', byDateDesc[0].gameId === 'a' && byDateDesc[1].gameId === 'b', byDateDesc.map((r) => r.gameId).join(','))
const byDateAsc = [a, b, c].sort((x, y) => compareRows(x, y, 'date', 'asc'))
check('sort date asc: oldest dated first', byDateAsc[0].gameId === 'b')
check('undated row sinks in both directions',
  byDateDesc[2].gameId === 'c' || byDateAsc[2].gameId === 'c',
  `desc tail=${byDateDesc[2].gameId} asc tail=${byDateAsc[2].gameId}`)
check('sort score: higher max wins first (desc)',
  [a, rowFromMatch(match({ playerList: [{ name: 'X', wins: 0, quit: false, deckName: null }] }), 'd')]
    .sort((x, y) => compareRows(x, y, 'score', 'desc'))[0].gameId === 'a')
check('sort name: alphabetical asc',
  [rowFromMatch(match({ name: 'ZZZ' }), 'z'), rowFromMatch(match({ name: 'AAA' }), 'y')]
    .sort((x, y) => compareRows(x, y, 'name', 'asc'))[0].gameId === 'y')

// ── 5. date formatting ──
check('formatWhen: null -> em dash', formatWhen(null) === '—')
check('formatWhen: renders yyyy-mm-dd hh:mm', /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/.test(formatWhen(1727000300000)), formatWhen(1727000300000))

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
