/**
 * Candidate 2 — the
 * non-seated owner's Start Match control in the lobby tables grid, end to end
 * through the REAL shipped modules (no browser):
 *
 *   - the REAL store (lobbySlice.setTables / setTableCreated / setTableJoined /
 *     startMatch) through the REAL message handler (messageHandlers.handleMessage),
 *   - the REAL row model + action mapping (utils/tablesGrid),
 *   - the REAL grid component (components/lobby/TablesGrid) rendered through
 *     react-dom/server, asserting the actual button markup + data-explain id.
 *
 * Run with:  bash scripts/verify-non-seated-owner.sh
 *
 * The wire shape under test (proxy side, pinned by Mage.WebProxy's
 * TableOwnershipTest / WebProxyResolveOwnedTablesTest): the table list carries
 * `"owner": true` on rows the connected account owns — non-tournament
 * WAITING/READY_TO_START only — and nothing at all for other people's tables.
 *
 * Scenarios:
 *  (A) the incident: [Computer-LLM, Human-open] table, owner holds NO seat, a
 *      friend fills seat 2 → the owner's grid row MUST show Start Match, and
 *      clicking it sends exactly one `start_match` for that table;
 *  (B) reload persistence: state is wiped like a fresh page (myTableId null,
 *      isTableOwner false, tables empty), then the SAME table list arrives →
 *      the Start control is back WITHOUT any table_created frame;
 *  (C) the row still does not clobber the seated/creator semantics:
 *      setTables keeps myTableId/isTableOwner when they are already recorded,
 *      and setTableJoined's idempotent creator guard still holds;
 *  (D) negative control: a non-owner (no flag on the row, someone else's
 *      READY_TO_START table) sees NO start button — and neither does a viewer
 *      of an owned row that has an open seat or already started.
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import TablesGrid from '../src/components/lobby/TablesGrid'
import { actionForTable } from '../src/utils/tablesGrid'
import { EXPLAIN } from '../src/hints/explain'
import type { ServerMessage, TableInfo } from '../src/types'

// react-dom/server reads zustand's getServerSnapshot (the INITIAL state object) —
// mirror the live store into it before every render so server renders see the
// scenario. Same lever as verify-seat-names.tsx / verify-thinking-shape-warning.tsx,
// extended with a full copy: handleMessage mutates the live store directly, so the
// explicit-patch mirroring those harnesses use would miss table_list updates.
const ssrSnapshot = (useGameStore as unknown as {
  getInitialState: () => Record<string, unknown>
}).getInitialState()
const setLive = (patch: Record<string, unknown>) => {
  useGameStore.setState(patch as never)
  Object.assign(ssrSnapshot, patch)
}
const syncSnapshot = () => {
  Object.assign(ssrSnapshot, useGameStore.getState())
}

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

// ── wire capture ──
const sent: Array<Record<string, unknown>> = []
useGameStore.getState().setWebSocket({
  send: (m: unknown) => { sent.push(m as Record<string, unknown>) },
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  disconnect: () => {},
  noteConnectAccepted: () => {},
} as never)

/** The incident's table: seat 0 = AI (joined by the proxy), seat 1 = the friend. */
const ownedReady = (over: Partial<TableInfo> = {}): TableInfo => ({
  id: 'T-OWN',
  name: 'Web Game',
  gameType: 'Two Player Duel',
  playerCount: 2,
  maxPlayers: 2,
  state: 'READY_TO_START',
  seatNames: ['AI-0', 'Cyber'],
  deckType: 'Constructed - Freeform',
  createTimeMs: 1_000_000,
  rated: false,
  passworded: false,
  isTournament: false,
  controllerName: 'Bronze, AI-0, Cyber',
  owner: true,
  ...over,
})

const theirs = (over: Partial<TableInfo> = {}): TableInfo => ({
  id: 'T-THEIRS',
  name: 'Their table',
  gameType: 'Two Player Duel',
  playerCount: 2,
  maxPlayers: 2,
  state: 'READY_TO_START',
  seatNames: ['Ketchupgeek', 'Bob'],
  createTimeMs: 1_000_000,
  controllerName: 'Ketchupgeek, Bob',
  ...over,
})

const tableList = (tables: TableInfo[]) => ({ type: 'table_list', tables } as ServerMessage)

/** A fresh-page lobby state: nothing recorded, no tables. */
function freshPage(extra: Record<string, unknown> = {}) {
  setLive({
    connected: true, connStatus: '', joinWaiting: false, showSetup: false,
    showTournamentSetup: false, myTableId: null, isTableOwner: false,
    mySeatNames: [], tables: [], proxyUsername: 'Bronze', ...extra,
  })
  sent.length = 0
}

/** Render the grid and return its markup (grid hides itself is NOT its concern). */
function gridMarkup(): string {
  syncSnapshot()
  return renderToStaticMarkup(<TablesGrid />)
}

/** The number of Start Match buttons in the rendered grid. */
function startButtons(markup: string): number {
  return markup.split('data-explain="lobby-grid-start-match"').length - 1
}

// ═════════════════════════════════════════════════════════════════════════════
section('A — the incident: non-seated owner sees Start Match and can start')
// ═════════════════════════════════════════════════════════════════════════════
{
  freshPage()
  // The owner session created the table earlier (create-time frame), then the proxy
  // auto-joined the AI seat and left the human one open; the friend joined it, so the
  // table reached READY_TO_START. The owner holds NO seat.
  handleMessage({ type: 'table_created', tableId: 'T-OWN', tableName: 'Web Game' } as ServerMessage, useGameStore.getState())
  check('creator recorded before the join', useGameStore.getState().isTableOwner === true)

  handleMessage(
    { type: 'table_joined', tableId: 'T-OWN', tableName: 'Web Game' } as ServerMessage,
    useGameStore.getState()
  )
  check('table_joined keeps the creator as owner (idempotent guard intact)',
    useGameStore.getState().isTableOwner === true && useGameStore.getState().myTableId === 'T-OWN')

  // The push that carries the filled table — this is the frame that used to offer nothing.
  handleMessage(tableList([ownedReady()]), useGameStore.getState())
  check('row carries the server flag', useGameStore.getState().tables[0]!.owner === true)

  const row = useGameStore.getState().tables[0]!
  const action = actionForTable(row, useGameStore.getState().myTableId, 'Bronze', [])
  check('action for the non-seated owner is start', action === 'start', String(action))
  check('an unseated owner IS offered start while myTableId is set (creator path)', action === 'start')

  const markup = gridMarkup()
  check('grid renders the Start Match control', startButtons(markup) === 1)
  check('control carries the lobby-grid-start-match explain id',
    markup.includes('data-explain="lobby-grid-start-match"'))
  check('explain registry covers the new control', typeof EXPLAIN['lobby-grid-start-match'] === 'string'
    && EXPLAIN['lobby-grid-start-match']!.length > 40)
  check('control is not disabled (live socket)', !markup.includes('lobby-grid-start-match" disabled'))

  // Click it: the store action must send exactly one start_match for that table.
  useGameStore.getState().startMatch('T-OWN')
  const starts = sent.filter((m) => m.type === 'start_match')
  check('click sends exactly one start_match', starts.length === 1, JSON.stringify(sent))
  check('start_match targets the owned table', starts[0]!.tableId === 'T-OWN')
}

// ═════════════════════════════════════════════════════════════════════════════
section('B — reload persistence: the flag rides the wire, no browser memory needed')
// ═════════════════════════════════════════════════════════════════════════════
{
  // Full page reload: every browser-side ownership field is gone. The proxy keeps the
  // XMage session (cleanup() keep for owned unstarted tables) and the reconnect push
  // carries the same table list with owner:true.
  freshPage()
  check('fresh page has no ownership memory',
    useGameStore.getState().isTableOwner === false && useGameStore.getState().myTableId === null)

  handleMessage(tableList([ownedReady()]), useGameStore.getState())
  check('setTables re-adopts ownership from the wire flag',
    useGameStore.getState().isTableOwner === true)
  check('setTables re-adopts the owned table id', useGameStore.getState().myTableId === 'T-OWN')

  const reloadedRow = useGameStore.getState().tables[0]!
  check('reloaded action is still start',
    actionForTable(reloadedRow, useGameStore.getState().myTableId, 'Bronze', []) === 'start')
  check('reloaded grid still renders the Start control', startButtons(gridMarkup()) === 1)

  // And the start works from the reloaded state (myTableId was the missing input).
  sent.length = 0
  useGameStore.getState().startMatch(useGameStore.getState().myTableId!)
  check('reloaded click sends start_match for the right table',
    sent.length === 1 && sent[0]!.type === 'start_match' && sent[0]!.tableId === 'T-OWN')
}

// ═════════════════════════════════════════════════════════════════════════════
section('C — the flag must not clobber the seated/creator semantics')
// ═════════════════════════════════════════════════════════════════════════════
{
  // Creator already recorded (e.g. table_created arrived, list has not yet) — a list
  // WITHOUT their table (the create → list race) must not clear it.
  freshPage()
  handleMessage({ type: 'table_created', tableId: 'T-OWN', tableName: 'Web Game' } as ServerMessage, useGameStore.getState())
  handleMessage(tableList([theirs()]), useGameStore.getState())
  check('a list without our table does not clear creator ownership',
    useGameStore.getState().isTableOwner === true && useGameStore.getState().myTableId === 'T-OWN')

  // Two owned rows (edge): myTableId stays the one already recorded.
  freshPage()
  handleMessage({ type: 'table_created', tableId: 'T-OWN', tableName: 'Web Game' } as ServerMessage, useGameStore.getState())
  handleMessage(tableList([ownedReady(), ownedReady({ id: 'T-OWN-2', name: 'Second' })]), useGameStore.getState())
  check('myTableId keeps the recorded table when several rows are owned',
    useGameStore.getState().myTableId === 'T-OWN')

  // The joiner path: NOT the owner — setTableJoined must not flip it.
  freshPage()
  handleMessage({ type: 'table_joined', tableId: 'T-THEIRS', tableName: 'Their table' } as ServerMessage, useGameStore.getState())
  check('a joiner is not made owner by an ordinary join',
    useGameStore.getState().isTableOwner === false && useGameStore.getState().myTableId === 'T-THEIRS')

  // ...and a later owned-row push must not retro-flip the joiner's myTableId.
  handleMessage(tableList([ownedReady()]), useGameStore.getState())
  check('adoption sets ownership but never repoints myTableId away from the joiner',
    useGameStore.getState().myTableId === 'T-THEIRS')

  // log out / game over clears it (the existing reset contract still applies).
  useGameStore.getState().logout()
  check('logout clears the re-adopted ownership',
    useGameStore.getState().isTableOwner === false && useGameStore.getState().myTableId === null)
}

// ═════════════════════════════════════════════════════════════════════════════
section('D — negative controls: no start control anywhere it would be wrong')
// ═════════════════════════════════════════════════════════════════════════════
{
  // The friend's view: someone else's full READY_TO_START table — no flag, no control.
  freshPage()
  handleMessage(tableList([theirs()]), useGameStore.getState())
  check('non-owner sees no ownership adopted', useGameStore.getState().isTableOwner === false)
  const d1 = useGameStore.getState().tables[0]!
  check('non-owner action is none', actionForTable(d1, null, 'Cyber', []) === null)
  const d1markup = gridMarkup()
  check('non-owner grid has no Start control', startButtons(d1markup) === 0)
  check('non-owner grid offers no action at all on the full table', !d1markup.includes('btn-sm'))

  // Owned but a seat is open — start would be a guaranteed server reject.
  freshPage()
  handleMessage(tableList([ownedReady({ playerCount: 1, seatNames: ['AI-0'], state: 'WAITING' })]), useGameStore.getState())
  check('owned + open seat -> no Start control', startButtons(gridMarkup()) === 0)
  check('owned + open seat -> Join is offered instead', gridMarkup().includes('data-explain="lobby-join-table"'))

  // Owned but the match already started.
  freshPage()
  handleMessage(tableList([ownedReady({ state: 'DUELING' })]), useGameStore.getState())
  check('owned + DUELING -> no Start control', startButtons(gridMarkup()) === 0)

  // A tournament row: the proxy contract never flags it (resolveOwnedTableIds skips
  // isTournament), so the wire fixture carries no flag — and even IF a future proxy set it,
  // the action mapping must not offer a match start on a tournament (different verb).
  freshPage()
  handleMessage(tableList([ownedReady({ isTournament: true })]), useGameStore.getState())
  check('tournament row -> no Start control', startButtons(gridMarkup()) === 0)
  check('tournament row -> action mapping refuses even a flagged row',
    actionForTable(ownedReady({ isTournament: true }), null, 'Bronze', []) === null)
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
