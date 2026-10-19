import { useEffect, useMemo, useRef } from 'react'
import { useGameStore } from '@/store/gameStore'
import { REPLAY_SKIP_MOVES, REPLAY_TICK_MS } from '@/store/slices/replaySlice'
import { formatBytes } from '@/utils/formatBytes'
import { isTypingTarget } from '@/utils/keybindings'
import { GameLayout } from './GameLayout'
import { PlayerBoard } from './PlayerBoard'
import { RightRail } from './RightRail'
import { ZonePopup } from './ZonePopup'
import { RevealedPopup } from './RevealedPopup'
import { CardContextMenu } from './CardContextMenu'
import { TokenArtMenu } from './TokenArtMenu'
import type { Card, Player } from '@/types'

/** What a replay-view keypress means, given which overlay (if any) owns the keyboard. */
export type ReplayHotkey = 'play' | 'next' | 'previous' | 'exit' | 'close-settings' | null

/**
 * Map a keypress to the viewer's action — pure and exported so the harness pins
 * the matrix, the same split `LLMConfigTabs.tabAfterKey` uses.
 *
 * The viewer's hotkeys (Space / arrows / Escape) are only its own while nothing
 * else needs those keys:
 *  - The settings dialog (opened from this view's header) holds sliders, selects
 *    and a text field that use these keys themselves: while it is up, Space and
 *    the arrows stand down, and Escape CLOSES THE DIALOG (standard dismissal)
 *    rather than dropping the viewer out from under it.
 *  - An open context menu (card/token menus work in a replay) dismisses on
 *    Escape through the shared menu shell; the viewer stands down for that
 *    press so one Escape cannot both close the menu AND stop the replay.
 */
export function replayHotkey(
  key: string,
  opts: { showSettings: boolean; contextMenuOpen: boolean; typing: boolean }
): ReplayHotkey {
  if (opts.showSettings) return key === 'Escape' ? 'close-settings' : null
  if (opts.typing) return null
  if (key === 'Escape') return opts.contextMenuOpen ? null : 'exit'
  switch (key) {
    case ' ': return 'play'
    case 'ArrowRight': return 'next'
    case 'ArrowLeft': return 'previous'
  }
  return null
}

/**
 * True when a keypress is going into a text field — those keys belong to the
 * field (the replay rail carries a chat input; the settings dialog carries the
 * preset-name input).
 *
 * RE-EXPORTED from utils/keybindings.ts: the predicate now
 * has ONE definition, shared by this viewer's keyboard hand-back and the
 * priority rail's rebindable hotkeys, so the two surfaces can never disagree
 * about where a keypress is "typing". Public behavior is unchanged — same
 * function, same signature, still pinned from here by verify-replay-settings.
 */
export { isTypingTarget }

/**
 * Replay viewer — read-only playback of a saved game (desktop parity:
 * GamePanel.pnlReplay + GamePane's "Replaying <gameId>" title).
 *
 * READ-ONLY BY CONSTRUCTION:
 *  - it renders `replayState`, never `gameState`;
 *  - its board renderer passes NO onCardClick / onManaClick / playable/target
 *    sets and the shell gets `readOnly`, so nothing on the board is
 *    interactive beyond card previews and the trigger-order menu is suppressed;
 *  - the global ActionPromptBar / DecisionPopup / CardSelectPopup are suppressed
 *    for this view in App.tsx (they are what turn an action prompt into a
 *    request), and the proxy rejects player-only requests while a replay is
 *    loaded anyway.
 *  - Playback is step-driven: "Play" runs a 1 s interval of replay_next, exactly
 *    like the desktop client's ReplayTask (which sleeps 1 s between nextPlay
 *    calls). At a boundary the server sends REPLAY_DONE 'end of replay' and
 *    KEEPS the session alive, so Play stops and stepping back still works.
 *
 * LAYOUT PARITY: the chrome (3-column grid, rail drag, 1v1 battlefield split,
 * phase strip, revealed buttons) comes from the SHARED `GameLayout` — the same
 * component the live board mounts. This file used to carry its own frozen copy
 * of that chrome, which is how it silently fell a version behind (no rail drag,
 * no split, pre-auto-fit sizing — measured 2026-09-24). The layout stays fully
 * INTERACTIVE here: resizing a replay is a viewer preference, not a game action.
 */
export default function ReplayView() {
  const replayState = useGameStore((s) => s.replayState)
  const replayGameId = useGameStore((s) => s.replayGameId)
  const replayFrame = useGameStore((s) => s.replayFrame)
  const replayStateIndex = useGameStore((s) => s.replayStateIndex)
  const replayStateCount = useGameStore((s) => s.replayStateCount)
  const replayPlaying = useGameStore((s) => s.replayPlaying)
  const replayLoading = useGameStore((s) => s.replayLoading)
  const replayLoadingGameId = useGameStore((s) => s.replayLoadingGameId)
  const replayLoadingBytes = useGameStore((s) => s.replayLoadingBytes)
  const replayDone = useGameStore((s) => s.replayDone)
  const replayLastDirection = useGameStore((s) => s.replayLastDirection)
  const setReplayPlaying = useGameStore((s) => s.setReplayPlaying)
  const replayNext = useGameStore((s) => s.replayNext)
  const replayPrevious = useGameStore((s) => s.replayPrevious)
  const replaySkip = useGameStore((s) => s.replaySkip)
  const replaySeek = useGameStore((s) => s.replaySeek)
  const stopReplay = useGameStore((s) => s.stopReplay)
  const setZonePopup = useGameStore((s) => s.setZonePopup)

  // Position control (scrubber): frame indices are 0-based server-side, the UI
  // shows 1-based frame numbers. The last index is the highest reachable state
  // (count - 1) — seeking to exactly `count` would run off the end of the saved
  // list and answer with a boundary notice instead of a frame.
  const lastIndex = replayStateCount > 0 ? replayStateCount - 1 : 0
  const sliderValue = Math.min(Math.max(replayStateIndex, 0), lastIndex)

  // Playback timer. The interval re-arms only when `replayPlaying` flips, and
  // each tick sends one replay_next; the server's REPLAY_UPDATE is what advances
  // the board. Stepping is driven by the SERVER (not a local cursor), so a slow
  // frame arrival cannot desync the viewer.
  const nextRef = useRef(replayNext)
  nextRef.current = replayNext
  useEffect(() => {
    if (!replayPlaying) return
    const id = setInterval(() => nextRef.current(), REPLAY_TICK_MS)
    return () => clearInterval(id)
  }, [replayPlaying])

  // Keyboard: space toggles play/pause, arrows step, Escape exits. Those keys
  // are the viewer's ONLY while nothing else owns them — the rail carries a
  // chat input, the settings dialog (opened from this view's header) carries
  // sliders/selects/text, and context menus dismiss on Escape. The whole
  // mapping lives in replayHotkey() above and is pinned by
  // scripts/verify-replay-settings.sh.
  useEffect(() => {
    function onKeydown(e: KeyboardEvent) {
      const st = useGameStore.getState()
      if (!st.inReplay) return
      switch (replayHotkey(e.key, {
        showSettings: st.showSettings,
        contextMenuOpen: st.openContextMenu != null,
        typing: isTypingTarget(e.target as { tagName?: string; isContentEditable?: boolean } | null),
      })) {
        case 'play':
          e.preventDefault()
          st.setReplayPlaying(!st.replayPlaying)
          break
        case 'next':
          e.preventDefault()
          st.replayNext()
          break
        case 'previous':
          e.preventDefault()
          st.replayPrevious()
          break
        case 'exit':
          e.preventDefault()
          st.stopReplay()
          break
        case 'close-settings':
          e.preventDefault()
          st.setShowSettings(false)
          break
      }
    }
    document.addEventListener('keydown', onKeydown)
    return () => document.removeEventListener('keydown', onKeydown)
  }, [])

  // Sort players: opponents first (top), "me" last — same rule as GameView. A
  // replay has no "me" (isMe is false for everyone), so this keeps seat order.
  const sortedPlayers = useMemo(() => {
    if (!replayState) return []
    return [...replayState.players].sort((a, b) => {
      if (a.isMe && !b.isMe) return 1
      if (!a.isMe && b.isMe) return -1
      return 0
    })
  }, [replayState])

  const isCommander = useMemo(
    () => (replayState ? replayState.players.some((p) => p.commanders.length > 0) : false),
    [replayState]
  )

  // One seat's board — READ-ONLY: no playable/target/mana sets, no click
  // dispatch, no player context menu. This is the single axis on which the
  // replay board differs from the live one; the shared shell supplies every
  // other part of the layout (grid, rail drag, split, strip, revealed buttons).
  const renderBoard = useMemo(
    () => (player: Player, isTopPlayer: boolean) => (
      <PlayerBoard
        player={player}
        isTopPlayer={isTopPlayer}
        isCommander={isCommander}
        isActiveTurn={player.name === replayState?.activePlayer}
        hasPriority={player.name === replayState?.priorityPlayer}
        onZoneClick={(zone) => {
          const cards: Card[] = zone === 'graveyard' ? player.graveyard
            : zone === 'exile' ? player.exile
            : zone === 'sideboard' ? (player.sideboard ?? [])
            : []
          setZonePopup({ zone, playerName: player.name, cards })
        }}
      />
    ),
    [isCommander, replayState, setZonePopup]
  )

  return (
    <div className="game-container">
      {/* Header — a replay has no undo/concede/stop-watching; the exit control
          is the Stop Replay button in the playback bar below. ⚙ Settings IS
          offered ("There is no settings button inside the Replay
          view"): display/highlight preferences apply to a replay board exactly
          as they do live, and SettingsDialog is a global mount — the same
          data-explain id as the live header's gear. */}
      <div className="game-header-bar">
        <div style={{ fontWeight: 600, color: 'var(--text-primary)' }}>
          {replayState ? `🎬 Replaying ${replayGameId ?? ''}` : `🎬 Loading replay ${replayGameId ?? ''}…`}
        </div>
        <div id="turn-info">
          {replayState
            ? `· Frame ${replayStateIndex >= 0 ? replayStateIndex + 1 : replayFrame} | Turn ${replayState.turn} | ${replayState.activePlayer || '—'}`
            : '· waiting for the first frame…'}
        </div>
        <div className="game-menu" style={{ marginLeft: 'auto', display: 'flex', gap: '0.5rem' }}>
          <button className="btn btn-secondary btn-sm" onClick={() => useGameStore.getState().setShowSettings(true)}
          data-explain="settings-gear">⚙ Settings</button>
          <button className="btn btn-danger btn-sm" onClick={() => stopReplay()} title="Escape"
          data-explain="replay-exit">
            ✕ Exit Replay
          </button>
        </div>
      </div>

      {/* Playback controls — desktop pnlReplay order: Prev, Play, Stop, Next, Skip */}
      <div
        className="replay-controls"
        style={{
          display: 'flex',
          gap: '0.4rem',
          alignItems: 'center',
          padding: '0.4rem 1rem',
          background: 'var(--bg-surface)',
          borderBottom: '1px solid var(--border-default)',
          flexShrink: 0,
        }}
      >
        <button
          className="btn btn-secondary btn-sm"
          onClick={() => replayPrevious()}
          data-explain="replay-prev"
          disabled={!replayState}
          title="Previous play (←)"
        >
          ⏮ Previous
        </button>
        <button
          className="btn btn-primary btn-sm"
          onClick={() => setReplayPlaying(!replayPlaying)}
          data-explain="replay-play"
          disabled={!replayState}
          title="Play / pause (space)"
        >
          {replayPlaying ? '⏸ Pause' : '▶ Play'}
        </button>
        <button
          className="btn btn-secondary btn-sm"
          onClick={() => replayNext()}
          data-explain="replay-next"
          disabled={!replayState}
          title="Next play (→)"
        >
          ⏭ Next
        </button>
        <button
          className="btn btn-secondary btn-sm"
          onClick={() => replaySkip(REPLAY_SKIP_MOVES)}
          data-explain="replay-skip"
          disabled={!replayState}
          title={`Skip ${REPLAY_SKIP_MOVES} plays forward`}
        >
          ⏩ Skip {REPLAY_SKIP_MOVES}
        </button>
        <button className="btn btn-danger btn-sm" onClick={() => stopReplay()}
        data-explain="replay-stop">
          ⏹ Stop Replay
        </button>
        {/* Boundary notice — stepping ran off an end. The session is still alive,
            so the user can step back; this must not look like a crash. The
            server reports BOTH ends with the same payload, so the label uses the
            direction of the last step (replayLastDirection). */}
        {replayDone?.reason === 'boundary' && (
          <span style={{ marginLeft: '0.5rem', color: 'var(--text-muted)', fontSize: '0.8rem' }}>
            {replayLastDirection === 'backward'
              ? 'Start of replay — step forward to continue'
              : 'End of replay — step back to continue'}
          </span>
        )}
        {/* Position control — drag or click to pan through the replay. It fills
            the empty space right of the step buttons. Frames are numbered
            1-based here (server indices are 0-based). The track is only as long
            as the save (replayStateCount); a proxy older than 2026-09-21 sends
            no count, so the control renders a single-frame track instead of a
            bogus length. */}
        <div className="replay-scrubber">
          <input
            type="range"
            className="replay-scrubber-range"
            min={0}
            max={lastIndex}
            step={1}
            value={sliderValue}
            disabled={!replayState || replayStateCount <= 0}
            onChange={(e) => replaySeek(Number(e.currentTarget.value))}
            data-explain="replay-scrubber"
            aria-label="Replay position"
          />
          <span className="replay-scrubber-label">
            {replayState
              ? replayStateCount > 0
                ? `${sliderValue + 1} / ${replayStateCount}`
                : `frame ${replayFrame}`
              : ''}
          </span>
        </div>
      </div>

      {!replayState && (
        <div style={{ padding: '2rem', textAlign: 'center', color: 'var(--text-secondary)' }}>
          {/* The server deserializes the whole save before the first frame, which
              takes seconds on a multi-MB replay. Say so (with the size, when the
              proxy could read it) rather than showing an apparently hung screen
              —  */}
          {replayLoading ? (
            <>
              <div style={{ fontSize: '1.1rem', marginBottom: '0.5rem' }}>
                ⏳ Loading replay{replayLoadingGameId ? ` ${replayLoadingGameId}` : ''}…
              </div>
              <div style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>
                {replayLoadingBytes
                  ? `${formatBytes(replayLoadingBytes)} save — the server is deserializing it. This takes a few seconds for a large game.`
                  : 'The server is deserializing the save. This takes a few seconds for a large game.'}
              </div>
            </>
          ) : (
            'Waiting for the first replay frame from the server…'
          )}
        </div>
      )}

      {replayState && (
        <>
          {/* Shared layout shell — identical chrome to the live board: the
              3-column grid, both rail drag handles, the 1v1 battlefield split,
              the centered phase strip and the revealed reopen buttons. `readOnly`
              suppresses the two game-action surfaces inside it (the stack's
              trigger-order menu, the phase chips' stop settings); rail/split drag
              stay live because resizing a replay is a viewer preference. */}
          <GameLayout
            state={replayState}
            players={sortedPlayers}
            readOnly
            renderBoard={renderBoard}
            rightRail={<RightRail />}
          />

          <ZonePopup />
          <RevealedPopup />
          {/* Read-only card context menu (Pin preview / Copy only — no Play,
              replays have no game actions). CardThumb opens it for non-tokens. */}
          <CardContextMenu />
          {/* Token-art picker — one mount point, reads the token from the store
              payload (CardThumb opens it for token tiles; local art choice is a
              viewer preference, so it works in a replay too). */}
          <TokenArtMenu />
        </>
      )}
    </div>
  )
}
