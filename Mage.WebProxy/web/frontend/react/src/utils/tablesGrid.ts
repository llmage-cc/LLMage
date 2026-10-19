import type { TableInfo } from '@/types'

/**
 * Pure row logic for the stock-style tables grid (webui-lobby-tables) —
 * the build / filter / sort / action model TablesGrid.tsx renders, extracted
 * so it can be exercised without a DOM (scripts/verify-tables-grid.ts).
 *
 * Row shape mirrors the stock desktop client's tables panel: Deck Type | Name |
 * Seats | Owner | Game Type | Status | Password | Created | Skill | Rated | Action.
 * Every field is display-ready: the component stays a dumb renderer.
 *
 * No React imports — this file must stay runnable under Node.
 */

/** Which kind of table the filter shows. 'all' is the default view. */
export type TableKindFilter = 'all' | 'matches' | 'tournaments'

/** Sortable columns of the grid. */
export type TableSortKey = 'created' | 'name' | 'seats' | 'gameType' | 'status'
export type SortDir = 'asc' | 'desc'

/** Filter inputs for filterRows. */
export interface TablesGridFilters {
  kind: TableKindFilter
  ratedOnly: boolean
  hidePassworded: boolean
}

/** One rendered row of the grid — a TableInfo plus derived display fields. */
export interface TableRow {
  table: TableInfo
  /** Seat badge text, "cur/max". */
  seats: string
  /** Badge class: 'joinable' (green) | 'full' (blue) | 'dueling' (grey). */
  seatsBadge: 'joinable' | 'full' | 'dueling'
  /** Derived create-age text, e.g. "44 s", "2 mi", "1 ho" — stock "Cre." column. */
  created: string
}

/**
 * Age text for the stock "Cre." column: compact duration since the table was
 * created, truncated to two characters like the desktop client ("44 s", "2 mi",
 * "1 ho", "3 d"). Null-safe (unknown shows "—"). Takes elapsed seconds as a
 * parameter so the formatting is testable without a clock.
 */
export function formatAge(createTimeMs: number | undefined | null, secondsElapsed: number): string {
  if (createTimeMs == null) return '—'
  const ageSec = Math.max(0, Math.floor(secondsElapsed - createTimeMs / 1000))
  if (ageSec < 60) return `${ageSec} s`
  if (ageSec < 3600) return `${Math.floor(ageSec / 60)} mi`
  if (ageSec < 86400) return `${Math.floor(ageSec / 3600)} ho`
  return `${Math.floor(ageSec / 86400)} d`
}

/** Seat badge class for a table: green joinable, blue full, grey dueling. */
export function seatsBadgeClass(table: TableInfo): TableRow['seatsBadge'] {
  if (table.state === 'DUELING') return 'dueling'
  if (table.state === 'WAITING' && table.playerCount < table.maxPlayers) return 'joinable'
  return 'full'
}

/** Map every table to its rendered row (with derived fields). */
export function buildRows(tables: TableInfo[], secondsElapsed: number): TableRow[] {
  return tables.map((table) => ({
    table,
    seats: `${table.playerCount}/${table.maxPlayers}`,
    seatsBadge: seatsBadgeClass(table),
    created: formatAge(table.createTimeMs, secondsElapsed),
  }))
}

/**
 * Filter rows: table kind (matches vs tournaments vs both), rated-only,
 * hide-passworded. A table without the isTournament field counts as a match
 * (the field is only absent from pre-extension servers, where no tournament
 * flag was ever sent).
 */
export function filterRows(rows: TableRow[], filters: TablesGridFilters): TableRow[] {
  return rows.filter((row) => {
    const t = row.table
    const isTournament = t.isTournament === true
    if (filters.kind === 'matches' && isTournament) return false
    if (filters.kind === 'tournaments' && !isTournament) return false
    if (filters.ratedOnly && !t.rated) return false
    if (filters.hidePassworded && t.passworded) return false
    return true
  })
}

/** Compare two rows on a sort key. Null/unknown values sink to the bottom. */
export function compareRows(a: TableRow, b: TableRow, key: TableSortKey, dir: SortDir): number {
  const mult = dir === 'asc' ? 1 : -1
  const rank = (r: TableRow) => rankValue(r, key)
  const ra = rank(a)
  const rb = rank(b)
  // Unknown (null) ranks sink in BOTH directions, like the replay browser's dates.
  if (ra === null && rb === null) return 0
  if (ra === null) return 1
  if (rb === null) return -1
  if (ra < rb) return -1 * mult
  if (ra > rb) return 1 * mult
  return 0
}

function rankValue(row: TableRow, key: TableSortKey): number | string | null {
  const t = row.table
  switch (key) {
    case 'created':
      return t.createTimeMs ?? null
    case 'name':
      return (t.name || '').toLowerCase()
    case 'seats':
      // Seat fill ratio sorts full tables first on desc.
      return t.maxPlayers > 0 ? t.playerCount / t.maxPlayers : 0
    case 'gameType':
      return (t.gameType || '').toLowerCase()
    case 'status':
      return t.state || ''
  }
}

/** Sort rows on a key/direction (returns a new array, does not mutate). */
export function sortRows(rows: TableRow[], key: TableSortKey, dir: SortDir): TableRow[] {
  return [...rows].sort((a, b) => compareRows(a, b, key, dir))
}

/**
 * Which action the Action column offers for a table — the pure mapping the
 * component renders, so the join/watch/enter wiring is testable.
 *
 *   - seated at this table (myTableId === id): 'enter' — switch back to the
 *     waiting room (the store has no leave/return flow; the waiting panel is
 *     driven by joinWaiting, so Enter only refreshes the table list, matching
 *     the LobbyView "Refresh Tables" semantics).
 *     EXCEPTION (2026-09-26): the creator may hold NO seat at their own table —
 *     choosing an AI type for the "You" seat (e.g. [AI (Mad), HUMAN open]) only
 *     auto-joins the AI seat, and `enter` (a no-op refresh) left the open seat
 *     unfillable from the UI while the match could never start. The server
 *     ACCEPTS the creator's own join into that open seat (verified live
 *     2026-09-26: same-session join -> table_joined, and the pending auto-start
 *     fires once the table fills), so offer 'join' while we are seated nowhere
 *     at our own WAITING table that has a free seat.
 *   - WAITING/LISTED table with an open seat: 'join' (opens the join overlay).
 *   - DUELING table not seated at: 'watch'.
 *   - OWNED (row.owner, from the server-authoritative `owner` flag on the wire) and
 *     ready to start with every seat filled: 'start'.
 * The owner of a table
 *     they hold NO seat at otherwise has no start control anywhere: the waiting panel
 *     is gated on being seated (joinWaiting), and a full READY_TO_START row used to
 *     offer no action at all. The server accepts the start from the owning session —
 *     `TableController.startMatch(UUID)` proceeds exactly when `isOwner(userId)` — and
 *     the proxy's `start_match` verb carries this session's own user id, so a
 *     non-seated owner can start it. Gating mirrors `selectCanStartMatch`: owned +
 *     WAITING/READY_TO_START + all seats filled (a start over an open seat is a
 *     guaranteed server reject). Note the seated-owner case never reaches here — the
 *     waiting panel owns that path.
 *   - anything else (full, starting, finished): no action.
 *
 * Seat names: "am I seated here" can no longer be answered by the login
 * username alone — the creator may have given their own seat a typed name
 * (`playerConfigs[].name`), which is what the server reports back. `mySeatNames`
 * carries the names THIS session seated itself under (store.mySeatNames,
 * recorded when the table was created), so a renamed own seat still reads as
 * "I am seated" instead of re-offering Join for a seat we already hold. Both
 * inputs are optional: when neither matches, the check falls back to 'enter'
 * (fail closed — never offer Join we cannot verify).
 */
export function actionForTable(
  table: TableInfo,
  myTableId: string | null,
  myUsername?: string | null,
  mySeatNames?: readonly string[]
): 'join' | 'watch' | 'enter' | 'start' | null {
  const mine = myTableId != null && table.id === myTableId
  const seatNames = table.seatNames || []
  // Every identity this session could be seated under: the login username
  // (the proxy's derived default) plus any typed own-seat names.
  const knownNames = [...(mySeatNames || []), ...(myUsername != null ? [myUsername] : [])]
  const iAmSeated = knownNames.some((n) => seatNames.includes(n))
  // Candidate 2: startable
  // from the grid when the SERVER says the row is ours, the table has not started and
  // every seat is filled — the same shape `selectCanStartMatch` accepts for the seated
  // owner. The `owner` flag is authoritative (TableController.isOwner behind the
  // tableIsOwner RPC) and rides the table list, so this survives a reload; an absent
  // flag means "not yours/unknown" and keeps the old mapping (fail closed).
  // Tournaments are refused outright: their start is a DIFFERENT verb
  // (start_tournament), and the proxy's resolver never flags them — this is the
  // belt-and-braces half of that contract.
  const startable = table.owner === true
    && table.isTournament !== true
    && (table.state === 'WAITING' || table.state === 'READY_TO_START')
    && table.maxPlayers > 0
    && table.playerCount >= table.maxPlayers
  if (mine) {
    const open = table.state === 'WAITING' && table.playerCount < table.maxPlayers
    if (open && knownNames.length > 0 && !iAmSeated) {
      return 'join'
    }
    // The creator who holds NO seat (e.g. [Computer-LLM, Human] with a friend joining)
    // never gets the waiting panel, so a FULL table they own must offer Start here —
    // 'enter' (a bare list refresh) is not a start control. Seated owners keep the old
    // 'enter': their waiting panel already carries Start Match.
    if (startable && !iAmSeated && knownNames.length > 0) {
      return 'start'
    }
    return 'enter'
  }
  if (startable) return 'start'
  if (table.state === 'DUELING') return 'watch'
  if (table.state === 'WAITING' && table.playerCount < table.maxPlayers) return 'join'
  return null
}