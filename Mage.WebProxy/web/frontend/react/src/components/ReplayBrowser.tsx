import { useEffect, useMemo, useState } from 'react'
import { useGameStore } from '@/store/gameStore'
import { formatBytes } from '@/utils/formatBytes'
import {
  buildReplayRows,
  compareRows,
  formatWhen,
  rowMatches,
  type ReplayRow,
  type SortDir,
  type SortKey,
} from '@/utils/replayRows'
import { WINNER_GLYPH } from '@/utils/endGame'

/**
 * Replay browser — searchable/sortable table over BOTH replay sources:
 *  - finished matches of the main room (desktop parity:
 *    TablesPanel.tableCompleted + MatchesTableModel), and
 *  - saved/<uuid>.game files on the server (desktop parity: MageFrame's
 *    "Load Replay" JFileChooser — web lists what the SERVER can load,
 *    see WebProxySession's replay section comment).
 *
 * Rows, merge/dedupe, search, and sort live in utils/replayRows.ts (pure,
 * harness-tested); this component is the table over them. Metadata quality
 * varies: finished matches carry everything (MatchView), saved files carry
 * whatever the server's sidecar JSON recorded (ReplayMetadata) — rows from
 * BEFORE the sidecar existed show their id + file date and a "legacy" marker.
 *
 * Search matches on: table/match name, game type, deck type, player names,
 * deck names. Sortable columns: date, name, players, score, deck, type.
 * Default sort: newest first.
 *
 * Refreshed when the section is opened and on demand (nothing is polled: a
 * replay list is stale-tolerant by nature).
 */

function SortHeader({
  label,
  sortKey,
  sort,
  onSort,
}: {
  label: string
  sortKey: SortKey
  sort: { key: SortKey; dir: SortDir }
  onSort: (k: SortKey) => void
}) {
  const active = sort.key === sortKey
  return (
    <button
      className="replay-sort-header"
      onClick={() => onSort(sortKey)}
      title={`Sort by ${label}`}
      style={{
        background: 'none',
        border: 'none',
        cursor: 'pointer',
        color: 'var(--text-secondary)',
        fontWeight: active ? 700 : 400,
        padding: 0,
        textAlign: 'left',
      }}
    >
      {label}{active ? (sort.dir === 'asc' ? ' ▲' : ' ▼') : ''}
    </button>
  )
}

/**
 * The load-progress line shown under whichever row the user clicked. The server's
 * deserialization cannot be cancelled or measured from here, so this reports what
 * IS known: the save's size, and that stepping will be available shortly.
 */
function LoadingNotice({ bytes }: { bytes: number | null }) {
  const size = formatBytes(bytes)
  return (
    <span style={{ color: 'var(--text-muted)', fontSize: '0.75rem', marginLeft: '0.5rem' }}>
      ⏳ Loading{size ? ` ${size}` : ''}… a large save takes a few seconds
    </span>
  )
}

export default function ReplayBrowser() {
  const finishedMatches = useGameStore((s) => s.finishedMatches)
  const replayFiles = useGameStore((s) => s.replayFiles)
  const replayFileMeta = useGameStore((s) => s.replayFileMeta)
  const replayFilesDirectory = useGameStore((s) => s.replayFilesDirectory)
  const requestFinishedMatches = useGameStore((s) => s.requestFinishedMatches)
  const requestReplayFiles = useGameStore((s) => s.requestReplayFiles)
  const loadReplay = useGameStore((s) => s.loadReplay)
  const connected = useGameStore((s) => s.connected)
  // Load progress: a large save blocks server-side for several seconds, so the
  // clicked row reports what is happening instead of looking dead.
  const replayLoading = useGameStore((s) => s.replayLoading)
  const replayLoadingGameId = useGameStore((s) => s.replayLoadingGameId)
  const replayLoadingBytes = useGameStore((s) => s.replayLoadingBytes)

  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState<{ key: SortKey; dir: SortDir }>({ key: 'date', dir: 'desc' })
  // "All" merges both sources on one game id per row; the filters let the user
  // look at just the lobby's finished matches or just the on-disk files.
  const [sourceFilter, setSourceFilter] = useState<'all' | 'match' | 'file'>('all')

  // Fetch both lists the first time the panel is opened (and on an explicit
  // Refresh). Nothing is polled: a replay list is stale-tolerant by nature.
  useEffect(() => {
    if (!open || !connected) return
    requestFinishedMatches()
    requestReplayFiles()
  }, [open, connected, requestFinishedMatches, requestReplayFiles])

  // ── Build the unified row list ──
  // buildReplayRows dedupes (a finished match's game ALSO exists as a saved
  // file — the match row wins, it has richer metadata; orphan files with no
  // lobby record, e.g. after a restart, appear from the file list alone),
  // then the search filter and column sort are applied on top.
  const rows = useMemo(() => {
    const all = buildReplayRows(finishedMatches, replayFiles, replayFileMeta, sourceFilter)
    return all.filter((r) => rowMatches(r, query.trim()))
      .sort((a, b) => compareRows(a, b, sort.key, sort.dir))
  }, [finishedMatches, replayFiles, replayFileMeta, sourceFilter, query, sort])

  const onSort = (key: SortKey) =>
    setSort((s) => (s.key === key ? { key, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { key, dir: key === 'date' ? 'desc' : 'asc' }))

  return (
    <div className="replay-browser" style={{ marginTop: '1rem' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.5rem' }}>
        <button
          className="btn btn-secondary"
          onClick={() => setOpen((v) => !v)}
          data-explain="replay-browse"
          title="Finished matches and saved replay files"
        >
          {open ? '▼' : '▶'} Replays
        </button>
        {open && (
          <>
            <button
              className="btn btn-secondary btn-sm"
              onClick={() => {
                requestFinishedMatches()
                requestReplayFiles()
              }}
              data-explain="replay-refresh"
            >
              Refresh
            </button>
            <input
              type="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search name, player, deck, type…"
              data-explain="replay-search"
              style={{ flex: 1, minWidth: '10rem', maxWidth: '22rem' }}
            />
            {(['all', 'match', 'file'] as const).map((src) => (
              <button
                key={src}
                className="btn btn-sm"
                data-explain={`replay-source-${src}`}
                onClick={() => setSourceFilter(src)}
                style={{
                  border: '1px solid var(--border-color, #555)',
                  background: sourceFilter === src ? 'var(--bg-active, #3a3a3a)' : 'transparent',
                  color: 'var(--text-secondary)',
                }}
                title={src === 'all' ? 'Finished matches + saved files' : src === 'match' ? 'Finished matches only' : 'Saved files only'}
              >
                {src === 'all' ? 'All' : src === 'match' ? 'Matches' : 'Files'}
              </button>
            ))}
          </>
        )}
      </div>

      {open && (
        <>
          {rows.length === 0 ? (
            <div style={{ color: 'var(--text-muted)', fontSize: '0.85rem', padding: '0.3rem 0' }} data-explain="replay-empty">
              {query.trim()
                ? 'No replays match the search.'
                : 'No finished matches or saved replay files.'}
            </div>
          ) : (
            <div className="table-list">
              {/* header */}
              <div
                className="replay-row replay-header-row"
                style={{
                  display: 'grid',
                  gridTemplateColumns: '10rem 1fr 1fr 4.5rem 4.5rem',
                  gap: '0.5rem',
                  alignItems: 'baseline',
                  padding: '0.2rem 0.4rem',
                }}
              >
                <SortHeader label="Date" sortKey="date" sort={sort} onSort={onSort} />
                <SortHeader label="Players / decks" sortKey="players" sort={sort} onSort={onSort} />
                <SortHeader label="Name / type" sortKey="name" sort={sort} onSort={onSort} />
                <SortHeader label="Score" sortKey="score" sort={sort} onSort={onSort} />
                <SortHeader label="" sortKey="date" sort={{ key: 'date', dir: 'desc' }} onSort={() => {}} />
              </div>
              {rows.map((r) => (
                <ReplayRowView
                  key={`${r.source}:${r.gameId}`}
                  row={r}
                  onReplay={loadReplay}
                  loadingGameId={replayLoadingGameId}
                  loadingBytes={replayLoadingBytes}
                  loading={replayLoading}
                />
              ))}
            </div>
          )}
          <div style={{ color: 'var(--text-muted)', fontSize: '0.75rem', marginTop: '0.4rem' }}>
            {rows.length} replay{rows.length === 1 ? '' : 's'}
            {sourceFilter !== 'all' && ` (${sourceFilter === 'match' ? 'finished matches' : `saved files, ${replayFilesDirectory}/`})`}
            {' · '}dates are server-side
          </div>
        </>
      )}
    </div>
  )
}

function ReplayRowView({
  row,
  onReplay,
  loadingGameId,
  loadingBytes,
  loading,
}: {
  row: ReplayRow
  onReplay: (gameId: string) => void
  loadingGameId: string | null
  loadingBytes: number | null
  loading: boolean
}) {
  const seats = row.playerNames.length
    ? row.playerNames.map((n, i) => (
        <div key={i} style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
          {n}
          {(row.wins[i] ?? 0) > 0 && <span style={{ color: 'var(--text-muted)' }}> [{row.wins[i]}]</span>}
          {row.playerDecks[i] && (
            <span style={{ color: 'var(--text-muted)', fontStyle: 'italic' }}> — {row.playerDecks[i]}</span>
          )}
        </div>
      ))
    : [<span key="none" style={{ color: 'var(--text-muted)' }}>no player data (legacy save)</span>]
  return (
    <div
      className="table-card replay-row"
      style={{
        display: 'grid',
        gridTemplateColumns: '10rem 1fr 1fr 4.5rem 4.5rem',
        gap: '0.5rem',
        alignItems: 'center',
        padding: '0.3rem 0.4rem',
      }}
    >
      <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
        {formatWhen(row.dateMs)}
        {row.rated && <span title="Rated match"> ★</span>}
      </div>
      <div style={{ fontSize: '0.8rem', minWidth: 0 }}>
        {seats}
        {row.winner && (
          <div style={{ color: 'var(--text-muted)', fontSize: '0.75rem', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }} title={row.winner}>
            {WINNER_GLYPH} {row.winner}
          </div>
        )}
      </div>
      <div style={{ minWidth: 0 }}>
        <div className="table-name" style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
          {row.name || <span style={{ fontFamily: 'monospace', fontSize: '0.75rem' }}>{row.gameId.slice(0, 8)}…</span>}
          {row.legacy && <span style={{ color: 'var(--text-muted)', fontSize: '0.7rem', marginLeft: '0.3rem' }} title="Saved before replay metadata existed — only the id and file date are known">legacy</span>}
        </div>
        {(row.gameType || row.deckType) && (
          <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
            {[row.gameType, row.deckType].filter(Boolean).join(' — ')}
          </div>
        )}
      </div>
      <div style={{ fontSize: '0.8rem' }}>{row.score || '—'}</div>
      <div style={{ textAlign: 'right' }}>
        {loading && loadingGameId === row.gameId && <LoadingNotice bytes={loadingBytes} />}
        {!loading && (
          <button
            className="btn btn-secondary btn-sm"
            onClick={() => onReplay(row.gameId)}
            data-explain="replay-load"
            title={`Load and replay game ${row.gameId}`}
          >
            Replay
          </button>
        )}
      </div>
    </div>
  )
}
