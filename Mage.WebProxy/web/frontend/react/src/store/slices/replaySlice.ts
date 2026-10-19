import type { StateCreator } from 'zustand'
import type { GameStore } from '../types'
import type {
  FinishedMatch,
  GameState,
  LLMDebugEntry,
  ReplayFileMeta,
} from '@/types'
import { stripServerHtml } from '@/utils/textFormat'
import { filterPostLossNoise } from '@/utils/endGame'

/**
 * Replay slice — finished-match list, on-disk replay file list, and the
 * read-only replay viewer state.
 *
 * READ-ONLY DESIGN: the replay board reuses the SHIPPED board components
 * (PlayerBoard / StackZone / PhaseBar / CombatArrows) and renders
 * `replayState`. The single flag that makes those components inert is
 * `inReplay`, which:
 *   - routes App.tsx to ReplayView instead of GameView,
 *   - makes selectCurrentView return 'replay' (so `inGame` is never set and no
 *     game action flows through the gameplay slice),
 *   - suppresses the global ActionPromptBar / DecisionPopup / CardSelectPopup,
 *     which are the only places that send game actions from the board's own
 *     affordances.
 * The action handlers in gameplaySlice are never reachable from the replay view:
 * the replay view passes NO onCardClick / onManaClick to the board, and the
 * request-path guard in the proxy rejects player-only messages while a replay is
 * loaded anyway (spectator flag).
 *
 * `replayState` is kept SEPARATE from `gameState`: the replay GameView is built
 * for no particular player (myPlayerId null → spectator shape), and merging the
 * two would let a live game's board be replaced by a replay frame.
 */

/**
 * One frame's LLM conversation data, retained as it arrived.
 *
 * The replay viewer is a step-driven scrubber: any frame can be revisited, and
 * the LLM tab needs "the conversation AS OF frame N", which no single wire
 * payload carries — each frame carries only ITS OWN state-index bucket
 * (ReplaySession.attachLLMData looks up exactly one bucket). Keeping the
 * per-frame buckets in arrival order lets the rail rebuild any cursor position
 * with a pure filter (replayLLMHistoryFor).
 */
export interface ReplayLLMFrame {
  /** The frame's true position in the saved game (server-sent replayStateIndex). */
  stateIndex: number
  /**
   * The frame's own llmDebug, keyed by player name. Only non-empty buckets
   * arrive here — a frame between LLM decisions carries none, and its key is
   * simply never written (no empty placeholder buckets, no dedupe heuristics:
   * a re-delivered frame overwrites the same key with the same content).
   */
  entries: LLMDebugEntry[]
}

export interface ReplaySlice {
  /** Finished matches of the main room (desktop: completed-matches table). */
  finishedMatches: FinishedMatch[]
  /** Replay files present in the server's saved/ directory (UUID only). */
  replayFiles: string[]
  /** Directory the file list came from, for the UI label. */
  replayFilesDirectory: string
  /** Per-file searchable metadata (sizes, dates, sidecar JSON), keyed by game id. */
  replayFileMeta: Record<string, ReplayFileMeta>
  /** True while a replay is loaded/being viewed. */
  inReplay: boolean
  /** The game id of the loaded replay (from replay_game / replay_init). */
  replayGameId: string | null
  /** Current replay frame. Null until replay_init arrives. */
  replayState: GameState | null
  /**
   * Per-frame LLM conversation buckets, in arrival order. One entry per frame
   * that actually carried LLM data — the frame's own llmDebug verbatim
   * (ReplaySession attaches one state-index bucket per frame). Filtered by the
   * frame cursor for the LLM panel's cumulative "history as of this frame" view
   * (replayLLMHistoryFor); reset whenever a different replay loads.
   */
  replayLLMFrames: ReplayLLMFrame[]
  /** The replay game id the accumulated buckets belong to. Null = no buckets. */
  replayLLMGameId: string | null
  /**
   * Game-log lines to render for the CURRENT replay frame: entries 0..count-1 of
   * the saved game's final log, where count is the server-reported per-frame
   * count.
   * Empty when the frame carries no log (a save written before log capture) —
   * the Game Log tab stays hidden for those rather than showing an empty panel.
   * Distinct from `eventLog`, which is the live channel's stream.
   */
  replayLogSlice: string[]
  /**
   * True between `replay_load` and the server's first response (replay_game /
   * replay_init / replay_done). The server's load blocks for seconds on a large
   * save, so the viewer shows a load notice with the byte size instead of an
   * apparently dead screen.
   */
  replayLoading: boolean
  /** Save size in bytes from `replay_loading`; null when the proxy cannot read it. */
  replayLoadingBytes: number | null
  /** The game id currently loading (for the per-row spinner in the browser). */
  replayLoadingGameId: string | null
  /** Frame counter — increments on every replay_init/replay_update, so the UI
   *  can show "frame N" and effects can key off it. */
  replayFrame: number
  /**
   * True position in the saved game's state list (server-sent replayStateIndex).
   * -1 when the connected proxy predates the field. Unlike replayFrame this
   * moves BACKWARD on replay_previous — use it for the "Frame N" indicator.
   */
  replayStateIndex: number
  /**
   * Total states in the saved game (server-sent replayStateCount) — the length
   * of the position control. -1 when the connected proxy predates the field
   * (2026-09-21), in which case the control renders a single-frame track.
   */
  replayStateCount: number
  /** True while playback is running (the play button's repeat-timer). */
  replayPlaying: boolean
  /**
   * Last REPLAY_DONE reason. 'boundary' = stepped off either end of the replay
   * (the session stays alive, so stepping back still works); 'stopped' = the
   * user quit; 'error' = the server could not load the file.
   */
  replayDone: { reason: 'stopped' | 'boundary' | 'error'; result: string } | null
  /**
   * Direction of the last playback navigation. ReplaySession reports BOTH
   * boundaries with the SAME payload ("end of replay"), so the direction the
   * user was stepping is the only way to know which end was hit — the viewer's
   * boundary notice reads it.
   */
  replayLastDirection: 'forward' | 'backward' | null

  requestFinishedMatches: () => void
  requestReplayFiles: () => void
  /** Load a replay by game UUID (must be one of replayFiles or a match game id). */
  loadReplay: (gameId: string) => void
  /** Start playback of the loaded replay (first frame). */
  startReplay: () => void
  /** Stop the replay and leave the viewer. */
  stopReplay: () => void
  replayNext: () => void
  replayPrevious: () => void
  replaySkip: (moves: number) => void
  /** Jump to a saved-state index (the position control's drag/click). */
  replaySeek: (stateIndex: number) => void
  /** Toggle the play button's step timer. */
  setReplayPlaying: (playing: boolean) => void

  setFinishedMatches: (matches: FinishedMatch[]) => void
  setReplayFiles: (gameIds: string[], directory: string, fileMeta: Record<string, ReplayFileMeta>) => void
  /** REPLAY_GAME — the server created the replay session; open the viewer. */
  onReplayGame: (gameId: string | null) => void
  /** REPLAY_INIT / REPLAY_UPDATE — a new frame. `stateIndex` is the server-reported
   *  position in the saved state list, `stateCount` its length (absent = legacy proxy). */
  setReplayState: (state: GameState, gameId: string | null, stateIndex?: number, stateCount?: number) => void
  /** `replay_loading` — the proxy acknowledged replay_load; show the load notice. */
  onReplayLoading: (gameId: string, bytes: number | null) => void
  /**
   * A proxy `error` frame arrived while a replay load was pending: the load will
   * never complete (e.g. the live-game guard refusing it while a stale game id is
   * held). Drops the optimistic load notice so the clicked row stops rendering
   * "⏳ loading…" forever; the error itself is surfaced as a toast by the caller.
   */
  replayLoadFailed: () => void
  /** REPLAY_DONE — see replayDone. */
  onReplayDone: (reason: 'stopped' | 'boundary' | 'error', result: string, gameId: string | null) => void
}

/** How many frames the skip-forward button advances (desktop uses 10). */
export const REPLAY_SKIP_MOVES = 10

/**
 * Does this replay CARRY a game log at all? (Drives the Game Log tab's
 * visibility in RightRail.)
 *
 * The test is on the frame's full log, NOT on the current frame's slice: every
 * frame carries the same complete log once (flat capture) and frame 0's slice is
 * legitimately 0 entries, so gating on the slice would hide the tab on the first
 * frame and pop it in on the second — flickering the tab bar. A save written
 * before the capture work carries no `gameLog` at all, and that is the case the
 * tab must stay hidden for.
 *
 * Exported pure so the behavioural harness asserts the same rule the component uses.
 */
export function replayHasGameLog(state: GameState | null): boolean {
  return (state?.gameLog?.length ?? 0) > 0
}

/**
 * The game-log lines to render for a replay frame: entries 0..count-1 of the
 * frame's final game log.
 *
 * Flat capture: every
 * frame carries the SAME final log once plus the count of entries that existed
 * at that frame, so this slice is exact for frames taken after the last
 * truncation, and honestly capped (never fabricated) for frames taken before a
 * rollback/undo truncated and re-played the log — the server computes the cap
 * (GameState.getGameLogSafeSlice).
 *
 * Exported pure so the behavioural harness can assert the slicing without a DOM.
 * Returns [] when the frame has no log (a save written before capture) — the
 * rail keeps the Game Log tab hidden for those.
 */
export function replayLogSliceFor(state: GameState | null): string[] {
  const log = state?.gameLog
  if (!log || log.length === 0) return []
  const count = state?.gameLogCount ?? log.length
  // Server log lines carry HTML the same way live events do (player-name
  // colouring, request text) — strip it exactly as appendEvent does for the
  // live panel, or the replay renders raw <font> markup.
  const lines = log.slice(0, Math.max(0, Math.min(count, log.length))).map((line) => stripServerHtml(line))
  // Drop the dead seat's post-mortem mulligan/shuffle narration exactly as the
  // LIVE rail does: the
  // saved log captured by LLMGameLogCollector holds the same
  // terminal-failure-then-contradictory-tail sequence, so the replay panel would
  // otherwise replay the contradiction. The fatal line itself is preserved.
  return filterPostLossNoise(lines.map((message) => ({ type: 'action', message }))).map((e) => e.message)
}

/**
 * The cumulative LLM conversation view for a replay frame — every exchange
 * recorded at or before `cursor`, ordered by frame.
 *
 * WHY client-side accumulation:
 * each replay frame carries only the exchanges recorded at ONE GameState index
 * — typically 1–2, i.e. a single decision point — so a frame on the opponent's
 * turn carries no bucket at all and the panel went blank ("No LLM players in
 * this game", misleading in a replay). Accumulating the per-frame buckets
 * client-side gives "history as of this frame" at zero wire cost:
 *  - ordered by frame index, filtered to `frame.stateIndex <= cursor`, so
 *    scrubbing BACKWARD drops the later exchanges and forward re-adds them —
 *    correct in both directions;
 *  - the cursor filter makes seeking O(1): no replay of the frame stream;
 *  - a re-delivered frame overwrites its own state-index key (idempotent
 *    merge), so no duplicates appear on any path.
 *
 * Merges all buckets per player name, in frame order. A player absent from a
 * frame's bucket simply contributes nothing for it — the same player's
 * conversation is continuous across the merge. Exported pure so the harness
 * asserts the same accumulation the rail renders.
 *
 * System-prompt dedupe: buildReplayViews attaches the game's system prompt as
 * the LEADING message of every bucket it emits (single-frame mode relies on
 * that — any one frame must be self-sufficient). The cumulative merge must
 * therefore keep only the FIRST system message per player, or the merged view
 * interleaves the same system prompt between every exchange (2026-09-30,
 * prod-visible with the toggle ON after the pre-game commit 5c53cc567e).
 */
export function replayLLMHistoryFor(
  frames: ReplayLLMFrame[],
  cursor: number,
): LLMDebugEntry[] {
  const ordered = [...frames]
    .filter((f) => f.stateIndex <= cursor)
    .sort((a, b) => a.stateIndex - b.stateIndex)
  // Merge buckets per player name in frame order. The frame views carry only
  // that frame's messages; exchangeCount/totalContextChars ride each frame's
  // own view (they are per-frame counts of the bucket, so the newest frame
  // that has the player wins).
  const byPlayer = new Map<string, LLMDebugEntry>()
  const seenSystem = new Set<string>()
  for (const frame of ordered) {
    for (const entry of frame.entries) {
      const prev = byPlayer.get(entry.playerName)
      let incoming = entry.messages
      if (seenSystem.has(entry.playerName)) {
        incoming = incoming.filter((m) => m.role !== 'system')
      } else if (incoming.some((m) => m.role === 'system')) {
        seenSystem.add(entry.playerName)
      }
      byPlayer.set(entry.playerName, prev
        ? { ...prev, messages: [...prev.messages, ...incoming] }
        : { ...entry, messages: [...incoming] })
    }
  }
  return [...byPlayer.values()]
}

/** Playback tick interval — one frame per second, matching the desktop ReplayTask. */
export const REPLAY_TICK_MS = 1000

export const createReplaySlice: StateCreator<
  GameStore,
  [],
  [],
  ReplaySlice
> = (set, get) => ({
  finishedMatches: [],
  replayFiles: [],
  replayFilesDirectory: 'saved',
  replayFileMeta: {},
  inReplay: false,
  replayGameId: null,
  replayState: null,
  replayLLMFrames: [],
  replayLLMGameId: null,
  replayLogSlice: [],
  replayLoading: false,
  replayLoadingBytes: null,
  replayLoadingGameId: null,
  replayFrame: 0,
  replayStateIndex: -1,
  replayStateCount: -1,
  replayPlaying: false,
  replayDone: null,
  replayLastDirection: null,

  requestFinishedMatches: () => {
    const { ws } = get()
    if (ws) ws.send({ type: 'get_finished_matches' })
  },

  requestReplayFiles: () => {
    const { ws } = get()
    if (ws) ws.send({ type: 'get_replay_files' })
  },

  loadReplay: (gameId) => {
    const { ws } = get()
    if (!ws) return
    // Fail Fast: refuse an empty id here rather than shipping a request the
    // proxy will reject with a field error the user cannot act on.
    if (!gameId) {
      get().showError('Cannot load replay: no game id')
      return
    }
    set({ replayDone: null, replayPlaying: false, replayLoading: true, replayLoadingBytes: null, replayLoadingGameId: gameId })
    ws.send({ type: 'replay_load', gameId })
  },

  startReplay: () => {
    const { ws, replayGameId } = get()
    if (!ws) return
    if (!replayGameId) {
      get().showError('Cannot start replay: no replay loaded')
      return
    }
    ws.send({ type: 'replay_start' })
  },

  stopReplay: () => {
    const { ws, replayGameId } = get()
    if (!ws) return
    // Leaving the viewer is LOCAL and immediate; the server-side session is
    // ended by replay_stop (its REPLAY_DONE lands afterwards and is harmless).
    set({
      inReplay: false,
      replayPlaying: false,
      replayState: null,
      replayLLMFrames: [],
      replayLLMGameId: null,
      replayLogSlice: [],
      replayGameId: null,
      replayDone: null,
      replayFrame: 0,
      replayStateIndex: -1,
      replayStateCount: -1,
      replayLastDirection: null,
      replayLoading: false,
      replayLoadingBytes: null,
      replayLoadingGameId: null,
    })
    if (replayGameId) {
      ws.send({ type: 'replay_stop' })
    }
  },

  replayNext: () => {
    const { ws, replayGameId } = get()
    if (!ws || !replayGameId) return
    set({ replayLastDirection: 'forward' })
    ws.send({ type: 'replay_next' })
  },

  replayPrevious: () => {
    const { ws, replayGameId } = get()
    if (!ws || !replayGameId) return
    set({ replayLastDirection: 'backward' })
    ws.send({ type: 'replay_previous' })
  },

  replaySkip: (moves) => {
    const { ws, replayGameId } = get()
    if (!ws || !replayGameId) return
    set({ replayLastDirection: 'forward' })
    ws.send({ type: 'replay_skip', moves })
  },

  replaySeek: (stateIndex) => {
    const { ws, replayGameId, replayStateIndex, replayStateCount } = get()
    if (!ws || !replayGameId) return
    // Fail Fast: the proxy/backend reject a bad index anyway; catch it here so a
    // wiring bug does not surface as a server error toast. The upper bound is
    // only known once a frame has arrived (replayStateCount === -1 before that).
    if (!Number.isInteger(stateIndex) || stateIndex < 0 || (replayStateCount >= 0 && stateIndex >= replayStateCount)) {
      get().showError(`Cannot seek replay: frame ${stateIndex} is outside 0..${replayStateCount - 1}`)
      return
    }
    if (stateIndex === replayStateIndex) return
    // Direction drives the boundary notice if the seek lands off an end.
    set({ replayLastDirection: stateIndex > replayStateIndex ? 'forward' : 'backward' })
    ws.send({ type: 'replay_seek', stateIndex })
  },

  setReplayPlaying: (playing) => set({ replayPlaying: playing }),

  setFinishedMatches: (matches) => set({ finishedMatches: matches }),

  setReplayFiles: (gameIds, directory, fileMeta) =>
    set({ replayFiles: gameIds, replayFilesDirectory: directory, replayFileMeta: fileMeta }),

  onReplayGame: (gameId) => {
    // The server created the replay session. Open the viewer and start playback
    // immediately — the desktop client's Replay action likewise auto-starts
    // (its ReplayTask), and a loaded-but-not-started replay is just a dead screen.
    //
    // The viewer opens on a placeholder until replay_init arrives (the GameView
    // only exists server-side after startReplay), so ReplayView renders a
    // "loading first frame" state from inReplay && replayState === null.
    set({ inReplay: true, replayGameId: gameId, replayState: null, replayLLMFrames: [], replayLLMGameId: null, replayLogSlice: [], replayFrame: 0, replayStateIndex: -1, replayStateCount: -1, replayDone: null, replayLastDirection: null })
    const { ws } = get()
    if (ws) ws.send({ type: 'replay_start' })
  },

  onReplayLoading: (gameId, bytes) => {
    // The proxy acknowledged replay_load and is now blocked deserializing the
    // save. Only meaningful while we are actually waiting for this
    // game: a late ack for a replay the user already abandoned must not flip the
    // notice back on (the load cannot be cancelled server-side, but the UI can
    // ignore its progress).
    const s = get()
    if (!s.replayLoading || s.replayLoadingGameId !== gameId) return
    set({ replayLoadingBytes: bytes })
  },

  replayLoadFailed: () => {
    // A proxy `error` frame while a load was pending means the load will never
    // complete — the request was refused before it ever reached the file (e.g.
    // "Cannot load a replay while in a game"), so neither replay_game nor
    // replay_init is coming. Without this the row the user clicked keeps its
    // optimistic "⏳ loading…" forever and the refusal is indistinguishable from a
    // slow load.
    // Only the pending-load state is dropped: the error itself is toasted by the
    // message handler, and a replay already being VIEWED is untouched (its frames
    // and session stay valid — dropping the notice must not close the viewer).
    if (!get().replayLoading) return
    set({ replayLoading: false, replayLoadingBytes: null, replayLoadingGameId: null })
  },

  setReplayState: (state, gameId, stateIndex, stateCount) =>
    set((s) => {
      // Accumulate this frame's LLM bucket by its true state index (the server
      // keys exchanges at the state index of the frame where the prompt
      // happened). Idempotent: a re-delivered frame overwrites its own key.
      const idx = stateIndex ?? s.replayStateIndex
      const prior = s.replayLLMGameId === gameId ? s.replayLLMFrames : []
      const kept = prior.filter((f) => f.stateIndex !== idx)
      let nextFrames =
        state.llmDebug && state.llmDebug.length > 0
          ? [...kept, { stateIndex: idx, entries: state.llmDebug }]
          : kept
      // Skipped-range backfill: on a forward jump (replay_skip / replay_seek) the
      // server delivers ONE frame plus the buckets of the indices in between
      // (`replayLLMBuckets`, keyed by TRUE state index). Merge each one under
      // its own index with the same idempotent overwrite — a re-delivered index
      // replaces its key, so no duplicates on any path. Absent on live frames
      // and older proxies.
      if (state.replayLLMBuckets && state.replayLLMBuckets.length > 0) {
        const withBackfill = nextFrames.filter(
          (f) => !state.replayLLMBuckets!.some((b) => b.stateIndex === f.stateIndex),
        )
        for (const bucket of state.replayLLMBuckets) {
          if (bucket.entries && bucket.entries.length > 0) {
            withBackfill.push({ stateIndex: bucket.stateIndex, entries: bucket.entries })
          }
        }
        nextFrames = withBackfill
      }

      return {
        replayState: state,
        replayFrame: s.replayFrame + 1,
        // Server-reported position in the saved state list. Falls back to the
        // legacy counter only when absent (proxy older than 2026-09-20).
        replayStateIndex: idx,
        // Track length from the server (proxy older than 2026-09-21: stays -1 and
        // the position control renders a single-frame track).
        replayStateCount: stateCount ?? s.replayStateCount,
        replayGameId: gameId ?? s.replayGameId,
        // Cumulative LLM buckets: this frame's own llmDebug plus any skipped-range
        // buckets, retained per frame (see ReplayLLMFrame). Reset when a different
        // replay loads.
        replayLLMFrames: nextFrames,
        replayLLMGameId: gameId ?? s.replayLLMGameId,
        // Flat game log: the frame carries the saved game's final log + this
        // frame's count; render entries 0..count-1 (see replayLogSlice). A frame
        // without the fields (a save from before log capture, or a proxy older
        // than this change) yields an empty slice and the tab stays hidden.
        replayLogSlice: replayLogSliceFor(state),
        // A frame arriving proves the replay is alive: clear a prior boundary
        // notice (the user stepped back from the end, or forward past a marker),
        // and end the load notice — the first frame IS the load completing.
        replayDone: null,
        replayLoading: false,
        replayLoadingBytes: null,
        replayLoadingGameId: null,
      }
    }),

  onReplayDone: (reason, result, gameId) => {
    if (reason === 'boundary') {
      // Stepped off one end of the replay — the session stays alive server-side
      // (ReplaySession.updateGame only notifies), so keep the viewer open and
      // let the user step back. Playback stops so the timer cannot spin.
      set({ replayDone: { reason, result }, replayPlaying: false })
      return
    }
    // 'stopped' (the user quit — possibly via the desktop client's
    // CLIENT_REPLAY_ACTION path) or 'error' (e.g. "Replay file not found or
    // corrupt"): the session is over. Leave the viewer. An error must be
    // SURFACED — never a silent return to the lobby.
    set({
      inReplay: false,
      replayPlaying: false,
      replayState: null,
      replayLLMFrames: [],
      replayLLMGameId: null,
      replayLogSlice: [],
      replayGameId: null,
      replayDone: null,
      replayFrame: 0,
      replayStateIndex: -1,
      replayStateCount: -1,
      replayLastDirection: null,
      replayLoading: false,
      replayLoadingBytes: null,
      replayLoadingGameId: null,
    })
    if (reason === 'error') {
      get().showError(result || `Replay failed (${gameId ?? 'unknown game'})`)
    }
  },
})