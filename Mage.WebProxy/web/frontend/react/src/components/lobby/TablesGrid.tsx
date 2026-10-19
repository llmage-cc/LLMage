import { useMemo, useState } from 'react'
import { useGameStore } from '@/store/gameStore'
import { selectSocketDown } from '@/store/selectors'
import type { TableInfo } from '@/types'
import {
  actionForTable,
  buildRows,
  filterRows,
  sortRows,
  type SortDir,
  type TableKindFilter,
  type TableRow,
  type TableSortKey,
} from '@/utils/tablesGrid'

/**
 * Tables grid — stock-XMage-lobby-style dense table of every open and running
 * table (webui-lobby-tables). Columns mirror the desktop client's tables panel:
 * Deck Type | Name | Seats | Owner | Game Type | Status | Password | Created |
 * Skill | Rated | Action.
 *
 * Pure row logic (build/filter/sort/action mapping) lives in utils/tablesGrid.ts
 * and is exercised headlessly by scripts/verify-tables-grid.ts; this component
 * only renders rows and forwards clicks to the store.
 *
 * Filters: [All|Matches|Tournaments] segmented control, rated-only and
 * hide-passworded checkboxes. Data comes from the existing lobby store
 * `tables` state; the card list in LobbyView is untouched (lead wires this in).
 */
export default function TablesGrid() {
  const tables = useGameStore((s) => s.tables)
  const myTableId = useGameStore((s) => s.myTableId)
  const myUsername = useGameStore((s) => s.proxyUsername)
  // Own-seat names this session created tables under — a renamed own seat
  // must still read as "I am seated" (see actionForTable).
  const mySeatNames = useGameStore((s) => s.mySeatNames)
  const joinWaiting = useGameStore((s) => s.joinWaiting)
  const showJoinOverlay = useGameStore((s) => s.showJoinOverlay)
  const watchGame = useGameStore((s) => s.watchGame)
  const requestTables = useGameStore((s) => s.requestTables)
  // Candidate 2: the grid's
  // Start Match path for a non-seated owner. Same store action the waiting panel uses
  // (owner-only server-side: TableController.startMatch(UUID) proceeds exactly when
  // isOwner(userId)), so the wire verb and its socket-down guard are shared.
  const startMatch = useGameStore((s) => s.startMatch)
  // Join/Watch/Enter all send over the socket — disable them while
  // it is down instead of letting the click vanish (the lobby banner above
  // the shell explains why; the title repeats it per button).
  const socketDown = useGameStore(selectSocketDown)

  const [kind, setKind] = useState<TableKindFilter>('all')
  const [ratedOnly, setRatedOnly] = useState(false)
  const [hidePassworded, setHidePassworded] = useState(false)
  const [sortKey, setSortKey] = useState<TableSortKey>('created')
  const [sortDir, setSortDir] = useState<SortDir>('desc')

  const rows = useMemo(
    () =>
      sortRows(
        filterRows(buildRows(tables, Date.now() / 1000), { kind, ratedOnly, hidePassworded }),
        sortKey,
        sortDir
      ),
    [tables, kind, ratedOnly, hidePassworded, sortKey, sortDir]
  )

  const onHeaderClick = (key: TableSortKey) => {
    if (key === sortKey) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))
    } else {
      setSortKey(key)
      setSortDir('asc')
    }
  }

  const doAction = (table: TableInfo, action: ReturnType<typeof actionForTable>) => {
    switch (action) {
      case 'join':
        showJoinOverlay(table.id)
        break
      case 'watch':
        watchGame(table.id)
        break
      case 'start':
        // Candidate 2: non-seated owner starts their own table from the grid (the
        // store action carries the socket-down guard + toast, same as the panel).
        startMatch(table.id)
        break
      case 'enter':
        // Seated here — switch back to the waiting panel. The waiting view is
        // driven by joinWaiting (see LobbyView), so re-reading the table list is
        // all the "enter" can do: it refreshes seats/state and, once the match
        // starts, the pushed game view takes over.
        requestTables()
        break
      default:
        break
    }
  }

  return (
    <div className="tables-grid-wrap">
      <div className="tables-grid-filters">
        <div className="tables-grid-kind" role="group" aria-label="Table kind filter">
          {(['all', 'matches', 'tournaments'] as const).map((k) => (
            <button
              key={k}
              type="button"
              className={`tables-grid-kind-btn${kind === k ? ' active' : ''}`}
              onClick={() => setKind(k)}
              data-explain="tables-grid-kind"
            >
              {k === 'all' ? 'All' : k === 'matches' ? 'Matches' : 'Tournaments'}
            </button>
          ))}
        </div>
        <label className="tables-grid-filter-check">
          <input
            type="checkbox"
            checked={ratedOnly}
            onChange={(e) => setRatedOnly(e.target.checked)}
          />
          Rated only
        </label>
        <label className="tables-grid-filter-check">
          <input
            type="checkbox"
            checked={hidePassworded}
            onChange={(e) => setHidePassworded(e.target.checked)}
          />
          Hide passworded
        </label>
        {joinWaiting && (
          <span className="tables-grid-seated-hint">Seated — waiting for the match to start</span>
        )}
      </div>

      <div className="tables-grid-scroll">
        <table className="tables-grid">
          <thead>
            <tr>
              {(
                [
                  ['deckType', 'Deck Type', false],
                  ['name', 'Name', true],
                  ['seats', 'Seats', true],
                  ['owner', 'Owner', false],
                  ['gameType', 'Game Type', true],
                  ['status', 'Status', true],
                  ['password', 'Password', false],
                  ['created', 'Created', true],
                  ['skill', 'Skill', false],
                  ['rated', 'Rated', false],
                ] as [string, string, boolean][]
              ).map(([key, label, sortable]) => (
                <th
                  key={key}
                  className={sortable ? 'tables-grid-sortable' : undefined}
                  onClick={sortable ? () => onHeaderClick(key as TableSortKey) : undefined}
                >
                  {label}
                  {sortKey === key ? (sortDir === 'asc' ? ' ▲' : ' ▼') : ''}
                </th>
              ))}
              <th>Action</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const action = actionForTable(row.table, myTableId, myUsername, mySeatNames)
              return (
                <TablesGridRow
                  key={row.table.id}
                  row={row}
                  mine={myTableId === row.table.id}
                  action={action}
                  onAction={() => doAction(row.table, action)}
                  socketDown={socketDown}
                />
              )
            })}
            {rows.length === 0 && (
              <tr>
                <td colSpan={11} className="tables-grid-empty">
                  No tables match the current filters.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}

/** One grid row: cells straight from the pure row model + the action button. */
function TablesGridRow({
  row,
  mine,
  action,
  onAction,
  socketDown,
}: {
  row: TableRow
  mine: boolean
  action: ReturnType<typeof actionForTable>
  onAction: () => void
  socketDown: boolean
}) {
  const t = row.table
  return (
    <tr className={`tables-grid-row${mine ? ' tables-grid-row-mine' : ''}`}>
      <td className="tables-grid-deck">{t.deckType || '—'}</td>
      <td className="tables-grid-name">{t.name || 'Untitled'}</td>
      <td>
        <span className={`tables-grid-badge tables-grid-badge-${row.seatsBadge}`}>{row.seats}</span>
      </td>
      <td className="tables-grid-owner">{t.controllerName || '—'}</td>
      <td className="tables-grid-type">{t.gameType}</td>
      <td className="tables-grid-status">{t.state}</td>
      <td className="tables-grid-password">{t.passworded ? 'YES' : ''}</td>
      <td className="tables-grid-created">{row.created}</td>
      <td className="tables-grid-skill">{t.skillLevel || '—'}</td>
      <td className="tables-grid-rated">{t.rated ? 'YES' : ''}</td>
      <td className="tables-grid-action">
        {action === 'join' && (
          <button
            type="button"
            className="btn btn-secondary btn-sm"
            onClick={onAction}
            data-explain="lobby-join-table"
            disabled={socketDown}
            title={socketDown ? 'Connection is down — reconnecting' : undefined}
          >
            Join
          </button>
        )}
        {action === 'watch' && (
          <button
            type="button"
            className="btn btn-secondary btn-sm"
            onClick={onAction}
            data-explain="lobby-watch-table"
            disabled={socketDown}
            title={socketDown ? 'Connection is down — reconnecting' : undefined}
          >
            Watch
          </button>
        )}
        {action === 'enter' && (
          <button
            type="button"
            className="btn btn-secondary btn-sm"
            onClick={onAction}
            data-explain="lobby-enter-table"
            disabled={socketDown}
            title={socketDown ? 'Connection is down — reconnecting' : undefined}
          >
            Enter
          </button>
        )}
        {action === 'start' && (
          <button
            type="button"
            className="btn btn-primary btn-sm"
            onClick={onAction}
            data-explain="lobby-grid-start-match"
            disabled={socketDown}
            title={socketDown ? 'Connection is down — reconnecting' : undefined}
          >
            Start Match
          </button>
        )}
      </td>
    </tr>
  )
}