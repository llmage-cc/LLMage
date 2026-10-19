/**
 * GamesList — the lobby's "Current & Past Games" grid (webui-lobby gameslist).
 *
 * ONE dense table over ALL watchable games, the stock desktop lobby's bottom
 * completed-games panel extended with live rows on top:
 *   - live DUELING tables  → Kind badge LIVE, status instead of a result,
 *     Spectate (watchGame(tableId), the same action LobbyView's Watch calls),
 *   - finished matches     → one row PER replayable game (games[]), Kind
 *     REPLAY, score/winner result, Watch Replay (loadReplay(gameId) — exactly
 *     what ReplayBrowser's per-row Replay button calls),
 *   - saved/<uuid>.game files → Kind REPLAY, Watch Replay.
 *
 * Rows, merge/dedupe (match wins over same-id file), search, and sort live in
 * utils/gamesList.ts (pure, harness-tested; the replay half reuses
 * utils/replayRows.ts). Sorting headers follow ReplayBrowser's SortHeader
 * pattern; the default sort is 'started' desc with LIVE rows always first.
 *
 * NOTE (integration): the lead replaces ReplayBrowser with this component in
 * LobbyView. ReplayBrowser is intentionally left untouched here.
 */

import { useEffect, useMemo, useState } from 'react'
import { useGameStore } from '@/store/gameStore'
import {
  actionForRow,
  buildGameRows,
  compareGameRows,
  durationDisplay,
  formatWhen,
  gameRowMatches,
  resultDisplay,
  type GameRow,
  type GameSortKey,
  type LobbyTableInfo,
} from '@/utils/gamesList'
import type { SortDir } from '@/utils/replayRows'
import { WINNER_GLYPH } from '@/utils/endGame'

/** Live elapsed re-renders at this cadence while the list is shown. */
const LIVE_TICK_MS = 30_000

function SortHeader({
  label,
  sortKey,
  sort,
  onSort,
}: {
  label: string
  sortKey: GameSortKey
  sort: { key: GameSortKey; dir: SortDir }
  onSort: (k: GameSortKey) => void
}) {
  const active = sort.key === sortKey
  return (
    <button
      className="games-list-sort-header"
      onClick={() => onSort(sortKey)}
      title={`Sort by ${label}`}
    >
      {label}
      {active ? (sort.dir === 'asc' ? ' ▲' : ' ▼') : ''}
    </button>
  )
}

export default function GamesList() {
  const tables = useGameStore((s) => s.tables) as LobbyTableInfo[]
  const finishedMatches = useGameStore((s) => s.finishedMatches)
  const replayFiles = useGameStore((s) => s.replayFiles)
  const replayFileMeta = useGameStore((s) => s.replayFileMeta)
  const requestFinishedMatches = useGameStore((s) => s.requestFinishedMatches)
  const requestReplayFiles = useGameStore((s) => s.requestReplayFiles)
  const watchGame = useGameStore((s) => s.watchGame)
  const loadReplay = useGameStore((s) => s.loadReplay)
  const connected = useGameStore((s) => s.connected)
  // Load progress mirror (ReplayBrowser parity): the clicked row shows what is
  // happening instead of looking dead during the server's blocking load.
  const replayLoading = useGameStore((s) => s.replayLoading)
  const replayLoadingGameId = useGameStore((s) => s.replayLoadingGameId)

  const [query, setQuery] = useState('')
  const [sort, setSort] = useState<{ key: GameSortKey; dir: SortDir }>({ key: 'started', dir: 'desc' })
  // Ticking clock for the live rows' running elapsed ("12 minutes"). Kept as
  // an offset-only value: a hidden tab's throttled timer cannot make a live
  // game LOOK finished — stale elapsed is corrected on the next tick.
  const [nowMs, setNowMs] = useState(() => Date.now())

  useEffect(() => {
    if (!connected) return
    requestFinishedMatches()
    requestReplayFiles()
  }, [connected, requestFinishedMatches, requestReplayFiles])

  useEffect(() => {
    const t = setInterval(() => setNowMs(Date.now()), LIVE_TICK_MS)
    return () => clearInterval(t)
  }, [])

  // ── Build the unified row list ──
  // buildGameRows merges live tables + finished matches + saved files (match
  // row wins the dedupe, replayRows rule), then search + sort on top.
  const rows = useMemo(() => {
    const all = buildGameRows(tables, finishedMatches, replayFiles, replayFileMeta)
    return all
      .filter((r) => gameRowMatches(r, query.trim()))
      .sort((a, b) => compareGameRows(a, b, sort.key, sort.dir))
  }, [tables, finishedMatches, replayFiles, replayFileMeta, query, sort])

  const onSort = (key: GameSortKey) =>
    setSort((s) =>
      s.key === key
        ? { key, dir: s.dir === 'asc' ? 'desc' : 'asc' }
        : { key, dir: key === 'started' ? 'desc' : 'asc' },
    )

  return (
    <div className="games-list">
      <div className="games-list-toolbar">
        <input
          type="search"
          className="games-list-search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search player, deck, type, name…"
          data-explain="games-list-search"
        />
        <span className="games-list-count">
          {rows.length} game{rows.length === 1 ? '' : 's'}
        </span>
      </div>

      {rows.length === 0 ? (
        <div className="games-list-empty" data-explain="games-list-empty">
          {query.trim() ? 'No games match the search.' : 'No live games or replays yet.'}
        </div>
      ) : (
        <div className="games-list-grid">
          {/* header */}
          <div className="games-list-row games-list-header-row">
            <span>Kind</span>
            <SortHeader label="Players" sortKey="players" sort={sort} onSort={onSort} />
            <SortHeader label="Game Type" sortKey="type" sort={sort} onSort={onSort} />
            <span>Result / Status</span>
            <SortHeader label="Duration" sortKey="duration" sort={sort} onSort={onSort} />
            <SortHeader label="Started" sortKey="started" sort={sort} onSort={onSort} />
            <span>Action</span>
          </div>
          {rows.map((r) => (
            <GameRowView
              key={`${r.source}:${r.gameId}`}
              row={r}
              nowMs={nowMs}
              onSpectate={watchGame}
              onWatchReplay={loadReplay}
              loading={replayLoading}
              loadingGameId={replayLoadingGameId}
            />
          ))}
        </div>
      )}
    </div>
  )
}

function GameRowView({
  row,
  nowMs,
  onSpectate,
  onWatchReplay,
  loading,
  loadingGameId,
}: {
  row: GameRow
  nowMs: number
  onSpectate: (tableId: string) => void
  onWatchReplay: (gameId: string) => void
  loading: boolean
  loadingGameId: string | null
}) {
  const action = actionForRow(row)
  const spectateBlocked = action.id === 'none'
  const seats =
    row.playerNames.length > 0
      ? row.playerNames.map((n, i) => (
          <div key={i} className="games-list-player">
            {n}
            {(row.wins[i] ?? 0) > 0 && <span className="games-list-wins"> [{row.wins[i]}]</span>}
            {row.playerDecks[i] && <span className="games-list-deck"> — {row.playerDecks[i]}</span>}
          </div>
        ))
      : row.kind === 'live'
        ? [<span key="empty" className="games-list-muted">waiting…</span>]
        : [<span key="legacy" className="games-list-muted">no player data (legacy save)</span>]

  const started =
    row.dateMs != null
      ? formatWhen(row.dateMs)
      : row.kind === 'live'
        ? 'running'
        : <span className="games-list-id" title={row.gameId}>{row.gameId.slice(0, 8)}…</span>

  const loadingThisRow = loading && loadingGameId === row.gameId

  return (
    <div className={`games-list-row games-list-item${row.kind === 'live' ? ' games-list-live' : ''}`}>
      <span className="games-list-kind">
        <span className={`games-list-badge ${row.kind === 'live' ? 'games-list-badge-live' : 'games-list-badge-replay'}`}>
          {row.kind === 'live' ? 'LIVE' : 'REPLAY'}
        </span>
      </span>
      <span className="games-list-players">{seats}</span>
      <span className="games-list-type">
        <span className="games-list-gametype" title={row.gameType}>{row.gameType || '—'}</span>
        {row.deckType && <span className="games-list-decktype">{row.deckType}</span>}
        {row.rated && (
          <span className="games-list-rated" title="Rated match">
            ★
          </span>
        )}
      </span>
      <span className="games-list-result" title={row.kind === 'live' ? row.state : row.winner ?? row.score}>
        {resultDisplay(row)}
        {row.kind === 'replay' && row.winner && row.score && (
          <div className="games-list-winner" title={row.winner}>
            {WINNER_GLYPH} {row.winner}
          </div>
        )}
      </span>
      <span className="games-list-duration">
        {loadingThisRow ? <span className="games-list-muted">⏳ loading…</span> : durationDisplay(row, nowMs) || (row.kind === 'live' ? 'running' : '—')}
      </span>
      <span className="games-list-started">{started}</span>
      <span className="games-list-action">
        {action.id === 'spectate' && row.tableId && (
          <button
            className="btn btn-secondary btn-sm"
            onClick={() => onSpectate(row.tableId!)}
            data-explain="games-list-spectate"
            title={`Spectate ${row.name} — you watch as an observer and cannot act`}
          >
            Spectate
          </button>
        )}
        {action.id === 'spectate' && !row.tableId && (
          <button className="btn btn-secondary btn-sm" disabled title="Spectate">
            Spectate
          </button>
        )}
        {spectateBlocked && (
          <button
            className="btn btn-secondary btn-sm"
            disabled
            data-explain="games-list-spectate-disabled"
            title="This table does not allow spectators"
          >
            Spectate
          </button>
        )}
        {action.id === 'watch-replay' && (
          <button
            className="btn btn-secondary btn-sm"
            onClick={() => onWatchReplay(row.gameId)}
            data-explain="games-list-watch-replay"
            title={`Load and replay game ${row.gameId}`}
          >
            Watch Replay
          </button>
        )}
        {action.id === 'none' && !spectateBlocked && <span className="games-list-muted">—</span>}
      </span>
    </div>
  )
}