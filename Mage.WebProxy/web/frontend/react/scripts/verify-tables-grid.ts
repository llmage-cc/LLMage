/**
 * Behavioural checks for the lobby tables grid's row model
 * (webui-lobby-tables).
 *
 * Run with:  bash scripts/verify-tables-grid.sh
 *
 * Exercises the REAL pure logic (utils/tablesGrid.ts) — the build/filter/sort/
 * action model TablesGrid.tsx renders — over fixture tables covering every
 * state and the new serialization fields (deckType, createTimeMs, rated,
 * passworded, spectatorsAllowed, isTournament, skillLevel, controllerName):
 *   - row building: seats text + badge class (green joinable / blue full /
 *     grey dueling) and the stock "Cre." age formatting,
 *   - filters: All|Matches|Tournaments, rated-only, hide-passworded,
 *   - sorting: created (undated sinks), name, seats, gameType, status,
 *     asc + desc,
 *   - action mapping: join (waiting, open seat), watch (dueling),
 *     enter (seated at it), none (full/finished).
 */
import {
  actionForTable,
  buildRows,
  compareRows,
  filterRows,
  formatAge,
  sortRows,
} from '../src/utils/tablesGrid'
import type { TableInfo } from '../src/types'

let failures = 0
function check(name: string, ok: boolean, detail = '') {
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

/** Fixture factory: a WAITING 1/2 match table with every new field set. */
const table = (over: Partial<TableInfo> = {}): TableInfo => ({
  id: 't1',
  name: 'brac 3',
  gameType: 'Commander Free For All',
  playerCount: 1,
  maxPlayers: 4,
  state: 'WAITING',
  seatNames: ['Ketchupgeek'],
  deckType: 'Commander',
  createTimeMs: 1_000_000,
  rated: false,
  passworded: true,
  spectatorsAllowed: true,
  isTournament: false,
  skillLevel: 'Casual',
  controllerName: 'Ketchupgeek',
  ...over,
})

// ── 1. row building ──
const NOW = 1_000_000 // seconds
const rows = buildRows([table({ createTimeMs: (NOW - 30) * 1000 })], NOW)
check('row: seats text is cur/max', rows[0]!.seats === '1/4', rows[0]!.seats)
check('row: open waiting table is joinable (green)', rows[0]!.seatsBadge === 'joinable')
check('row: created age rendered', rows[0]!.created === '30 s', rows[0]!.created)

const full = buildRows([table({ id: 't2', playerCount: 4, maxPlayers: 4 })], NOW)[0]!
check('row: full waiting table is full (blue)', full.seatsBadge === 'full')
const dueling = buildRows([table({ id: 't3', state: 'DUELING', playerCount: 2 })], NOW)[0]!
check('row: dueling table is grey regardless of seats', dueling.seatsBadge === 'dueling')

// ── 2. age formatting (stock "Cre." column, seconds-elapsed param) ──
const T = table({ createTimeMs: 0 }) // epoch 0 → age == secondsElapsed
check('age: 44s -> "44 s"', formatAge(T.createTimeMs, 44) === '44 s')
check('age: 2min -> "2 mi"', formatAge(T.createTimeMs, 120) === '2 mi')
check('age: 1h -> "1 ho"', formatAge(T.createTimeMs, 3600) === '1 ho')
check('age: 25h -> "1 d"', formatAge(T.createTimeMs, 25 * 3600) === '1 d')
check('age: null createTime -> em dash', formatAge(undefined, 44) === '—')
check('age: create in the future clamps to 0', formatAge(1_000_000_000, 0) === '0 s')

// ── 3. filters ──
const mixed = buildRows(
  [
    table({ id: 'm1', name: 'match table', isTournament: false }),
    table({ id: 't1x', name: 'tour table', isTournament: true, maxPlayers: 8 }),
    table({ id: 'r1', name: 'rated', rated: true }),
    table({ id: 'p1', name: 'passworded', passworded: true }),
    table({ id: 'o1', name: 'open', passworded: false }),
  ],
  NOW
)
check('filter all: everything shown', filterRows(mixed, { kind: 'all', ratedOnly: false, hidePassworded: false }).length === 5)
check('filter matches: tournaments excluded',
  filterRows(mixed, { kind: 'matches', ratedOnly: false, hidePassworded: false }).every((r) => r.table.isTournament !== true))
check('filter tournaments: only tournaments',
  filterRows(mixed, { kind: 'tournaments', ratedOnly: false, hidePassworded: false }).every((r) => r.table.isTournament === true))
check('filter ratedOnly: only rated rows',
  filterRows(mixed, { kind: 'all', ratedOnly: true, hidePassworded: false }).map((r) => r.table.id).join(',') === 'r1')
check('filter hidePassworded: passworded dropped',
  filterRows(mixed, { kind: 'all', ratedOnly: false, hidePassworded: true }).every((r) => !r.table.passworded))
check('filters compose: rated-only + hide-passworded',
  filterRows(mixed, { kind: 'all', ratedOnly: true, hidePassworded: true }).length === 0)

// ── 4. sorting ──
const byCreated = [
  table({ id: 'old', createTimeMs: 500_000 }),
  table({ id: 'new', createTimeMs: 900_000 }),
  table({ id: 'nodate', createTimeMs: undefined }),
]
const rowsForSort = buildRows(byCreated, NOW)
check('sort created desc: newest first, undated sinks',
  sortRows(rowsForSort, 'created', 'desc').map((r) => r.table.id).join(',') === 'new,old,nodate')
check('sort created asc: oldest first, undated still sinks',
  sortRows(rowsForSort, 'created', 'asc').map((r) => r.table.id).join(',') === 'old,new,nodate')
check('sort name asc: alphabetical',
  sortRows(buildRows([table({ id: 'b', name: 'Zed' }), table({ id: 'a', name: 'Alice' })], NOW), 'name', 'asc')[0]!.table.id === 'a')
check('sort seats desc: fullest first',
  sortRows(buildRows([table({ id: 'h', playerCount: 1, maxPlayers: 4 }), table({ id: 'f', playerCount: 3, maxPlayers: 3 })], NOW), 'seats', 'desc')[0]!.table.id === 'f')
check('sort gameType asc',
  sortRows(buildRows([table({ id: 'z', gameType: 'Two Player Duel' }), table({ id: 'y', gameType: 'Commander' })], NOW), 'gameType', 'asc')[0]!.table.id === 'y')
check('sort status asc: DUELING < WAITING',
  sortRows(buildRows([table({ id: 'w', state: 'WAITING' }), table({ id: 'd', state: 'DUELING' })], NOW), 'status', 'asc')[0]!.table.id === 'd')
check('sort does not mutate input',
  (() => { const src = buildRows([table({ id: 'b', name: 'B' }), table({ id: 'a', name: 'A' })], NOW); sortRows(src, 'name', 'asc'); return src[0]!.table.id === 'b' })())

// ── 5. action mapping (join / watch / enter / none) ──
check('action: waiting with open seat -> join', actionForTable(table(), null) === 'join')
check('action: full waiting table -> none', actionForTable(table({ playerCount: 4, maxPlayers: 4 }), null) === null)
check('action: dueling table -> watch', actionForTable(table({ state: 'DUELING' }), null) === 'watch')
check('action: finished table -> none', actionForTable(table({ state: 'FINISHED' }), null) === null)
check('action: ready-to-start (all seats filled) -> none', actionForTable(table({ state: 'READY_TO_START' }), null) === null)
check('action: seated at my table -> enter (beats watch)', actionForTable(table({ state: 'DUELING' }), 't1') === 'enter')
check('action: seated at my waiting table -> enter', actionForTable(table(), 't1') === 'enter')
check('action: another table while seated elsewhere -> watch still offered',
  actionForTable(table({ state: 'DUELING' }), 'other-table') === 'watch')
// Creator holding NO seat at their own WAITING table with an open seat (the
// [AI (Mad) "You" seat, HUMAN open] dialog shape, 2026-09-26): 'enter' was a
// no-op refresh and the open seat was unfillable — the grid must offer 'join'.
check('action: my own waiting table, I hold no seat, open seat -> join',
  actionForTable(table({ seatNames: ['AI-0'], playerCount: 1, maxPlayers: 2 }), 't1', 'me') === 'join')
check('action: my own waiting table, I am seated in it -> enter (no double-join)',
  actionForTable(table({ seatNames: ['me', 'AI-1'], playerCount: 2, maxPlayers: 4 }), 't1', 'me') === 'enter')
check('action: my own full waiting table, I hold no seat -> enter',
  actionForTable(table({ seatNames: ['AI-0', 'AI-1'], playerCount: 2, maxPlayers: 2 }), 't1', 'me') === 'enter')
check('action: my own dueling table, I hold no seat -> enter (no join mid-game)',
  actionForTable(table({ state: 'DUELING', seatNames: ['AI-0', 'AI-1'], playerCount: 2, maxPlayers: 2 }), 't1', 'me') === 'enter')
check('action: username unknown -> enter preserved (fail closed)',
  actionForTable(table({ seatNames: ['AI-0'], playerCount: 1, maxPlayers: 2 }), 't1') === 'enter')

// Candidate 2 (2026-09-27): the server-authoritative `owner` flag on the wire. The
// non-seated owner of a table that reached READY_TO_START used to get NO action at all
// (full table -> null): offer Start Match. Everything else keeps the old mapping.
check('action: owned full WAITING table (I hold no seat) -> start',
  actionForTable(table({ owner: true, seatNames: ['AI-0'], playerCount: 2, maxPlayers: 2 }), null, 'me') === 'start')
check('action: owned READY_TO_START table -> start',
  actionForTable(table({ owner: true, state: 'READY_TO_START', seatNames: ['AI-0', 'Bob'], playerCount: 2, maxPlayers: 2 }), null, 'me') === 'start')
check('action: owned table with an OPEN seat -> join, not start (start is a guaranteed reject)',
  actionForTable(table({ owner: true, seatNames: ['AI-0'], playerCount: 1, maxPlayers: 2 }), null, 'me') === 'join')
check('action: owned dueling table -> watch (past the start window)',
  actionForTable(table({ owner: true, state: 'DUELING', seatNames: ['AI-0', 'Bob'], playerCount: 2, maxPlayers: 2 }), null, 'me') === 'watch')
check('action: NOT owned, full READY_TO_START -> null (unchanged: no control for joiners)',
  actionForTable(table({ state: 'READY_TO_START', seatNames: ['AI-0', 'Bob'], playerCount: 2, maxPlayers: 2 }), null, 'me') === null)
check('action: owner flag absent -> no start (fail closed)',
  actionForTable(table({ seatNames: ['AI-0', 'Bob'], playerCount: 2, maxPlayers: 2 }), null, 'me') === null)
check('action: owner flag false -> no start',
  actionForTable(table({ owner: false, seatNames: ['AI-0', 'Bob'], playerCount: 2, maxPlayers: 2 }), null, 'me') === null)
// Reload shape: myTableId was re-adopted from the owned row, then the same full table
// arrives again — the owner (still holding no seat) must keep the Start control.
check('action: reloaded owner (myTableId set from the flag), full table -> start',
  actionForTable(table({ owner: true, state: 'READY_TO_START', seatNames: ['AI-0', 'Bob'], playerCount: 2, maxPlayers: 2 }), 't1', 'me') === 'start')
check('action: reloaded owner at their own WAITING table with an open seat -> join',
  actionForTable(table({ owner: true, seatNames: ['AI-0'], playerCount: 1, maxPlayers: 2 }), 't1', 'me') === 'join')
check('action: SEATED owner of a full table -> enter (waiting panel owns Start)',
  actionForTable(table({ owner: true, state: 'READY_TO_START', seatNames: ['me', 'Bob'], playerCount: 2, maxPlayers: 2 }), 't1', 'me') === 'enter')
check('action: owned 0-seat table -> no start (degenerate maxPlayers)',
  actionForTable(table({ owner: true, playerCount: 0, maxPlayers: 0 }), null, 'me') === null)

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)