/**
 * Audio trigger wiring — the ONLY place that decides which game
 * event plays which sound. Every call site is a one-liner hook call added
 * additively to an EXISTING store action or wire-message path (the same
 * pattern store/slices/priorityDing.ts uses for the ding), so the audited
 * message/state logic is untouched and each trigger is unit-drivable through
 * the real paths.
 *
 * The sound → event-source table (with the desktop call sites it mirrors and
 * the WHY per trigger) lives in the `audioManager.ts` module header; this file
 * carries the wiring rationale and the guards.
 *
 * ── PROMPT-ARRIVAL (the one deliberate NON-trigger) ──
 * "A prompt needs your answer" already has a sound: the priority ding
 * (store/slices/priorityDing.ts, fired from setActionPrompt). It is epoch-
 * deduped and suppresses engine re-prompts, and the settings dialog documents
 * it as THE turn alarm. Adding a second cue here would double-fire, so no hook
 * in this file plays on prompt arrival — the ding is the single owner of that
 * moment. (Verified by the harness: a fresh prompt fires a ding variant and NO
 * event sound.)
 *
 * ── FAILURE POSTURE ──
 * `play()` throws only when the browser has no HTML `Audio` constructor (Fail
 * Fast — see audioManager.ts). Every hook funnels through {@link playOrReport},
 * which surfaces that as the visible error toast, matching the ding's own
 * catch → showError posture; a sound problem must never be a silent no-op.
 */

import type { GameStore } from '@/store/types'
import type { GameEvent, GameState, TableInfo } from '@/types'
import { play, type SoundId } from './audioManager'

/** Play, surfacing a hard failure through the store's visible error toast. */
function playOrReport(store: GameStore, soundId: SoundId): void {
  try {
    play(soundId)
  } catch (e) {
    const errorText = e instanceof Error ? e.message : String(e)
    console.error(`[audio] play failed (${soundId}):`, e)
    store.showError(`Audio error: ${errorText}`)
  }
}

/**
 * `start_game` announced (network/messageHandlers.ts, the `start_game` case).
 *
 * WHY only a NEW game (`isNewGame`): the message is re-announced when a client
 * rejoins the SAME game (`markGameStart` returns false), and a rejoin is not a
 * game start. Mirrors the desktop's playYourGameStarted (GamePanel.java:867)
 * without its joinGame callback.
 */
export function onGameStartAnnounced(store: GameStore, isNewGame: boolean): void {
  if (!isNewGame) return
  playOrReport(store, 'game-start')
}

/**
 * `game_over` with an AUTHORITATIVE result (network/messageHandlers.ts).
 *
 * WHY the `hasWon` guard: the proxy sends two payload flavors (see
 * WebProxyClient.handleGameOver) — the rich END_GAME_INFO one carries
 * `hasWon` and `complete:true`; the partial GAME_OVER/GameClientMessage one
 * carries only a message string and `complete:false`. Only the rich payload can
 * pick a win/loss sound, so the hook is called from that branch alone; a
 * partial never plays (there is no verdict to sound). Mirrors
 * GameEndDialog.java:82-84 (playPlayerWon / playPlayerLost).
 */
export function onGameOverAnnounced(store: GameStore, hasWon: boolean): void {
  playOrReport(store, hasWon ? 'player-won' : 'player-lost')
}

/**
 * A game-state push landed (store/slices/gameplaySlice.ts `setGameState`, which
 * every live channel routes through: game_init, game_update, game_delta).
 *
 * WHY stack LENGTH INCREASE only: the desktop's playStackNew fires when a new
 * object appears on the stack, which the web client can detect without any
 * per-card board diff — the stack array is a top-level field of every pushed
 * state. Growth is the trigger; a resolve (length decrease) is silent, exactly
 * like the desktop (it plays OnStackNew on new objects, not on resolution).
 * A first push (prev === null: joining mid-game, spectating) is NOT a new
 * object — it must not sound.
 */
export function onGameStatePushed(store: GameStore, prev: GameState | null, next: GameState): void {
  if (prev == null) return
  const grew = next.stack.length > prev.stack.length
  if (!grew) return
  playOrReport(store, 'stack-new')
}

/** Seated player count of a table row, from the proxy's own fields. */
function seatCount(t: TableInfo): number {
  return Array.isArray(t.seatNames) ? t.seatNames.length : t.playerCount
}

/**
 * The lobby table list was refreshed (store/slices/lobbySlice.ts `setTables` —
 * the proxy pushes `table_list` on every seat/state change of the watched
 * table, and the lobby polls it too).
 *
 * WHY this source: the desktop's TableWaitingDialog counts seats on its 1s
 * TableView poll and sounds game-can-start / player-joined / player-left
 * (TableWaitingDialog.java:414-431). The web client has the same three
 * signals on every table_list — for the table THIS session is seated at
 * (`myTableId`), not for every row in the grid. Mirrors the desktop's order:
 * "can start" (full) wins over "someone joined"; a decrease is "left".
 *
 * Guards: no baseline row (first observation of the table) or a table that
 * disappeared → silent; a previous count of 0 → silent (the desktop's
 * `count > 0` guard — the first seat is not "someone joined" you can hear).
 */
export function onTableListUpdated(store: GameStore, prev: TableInfo[], next: TableInfo[]): void {
  const tableId = store.myTableId
  if (!tableId) return
  const before = prev.find((t) => t.id === tableId)
  const after = next.find((t) => t.id === tableId)
  if (!before || !after) return
  const prevCount = seatCount(before)
  const nextCount = seatCount(after)
  if (nextCount === prevCount) return
  if (prevCount <= 0) return
  const maxPlayers = after.maxPlayers
  if (maxPlayers > 0 && nextCount >= maxPlayers) {
    playOrReport(store, 'game-can-start')
  } else if (nextCount > prevCount) {
    playOrReport(store, 'player-joined')
  } else {
    playOrReport(store, 'player-left')
  }
}

/**
 * One `event` wire frame landed (network/messageHandlers.ts, the `event` case —
 * the live channel only; `history_backfill` deliberately does NOT route here,
 * so a rejoin replay of old chat never rings).
 *
 * WHY `whisper_from` only: the proxy forwards the raw server chatType and the
 * server sets SoundToPlay.PlayerWhispered on the RECEIVING side of a private
 * message only (ChatSession.java:132, WHISPER_FROM; the desktop plays it at
 * CallbackClientImpl.java:189). A whisper I sent echoes as `whisper_to` and
 * must stay silent — I already know about it.
 */
export function onChatEvent(store: GameStore, event: GameEvent): void {
  if (event.chatType !== 'whisper_from') return
  playOrReport(store, 'player-whispered')
}

/**
 * A skip was armed (store/slices/gameplaySlice.ts `skipPriority`, after the
 * send). WHY here and not in the rail component: every path that arms a skip —
 * the six rail buttons, the F-key shortcuts and the F6 skip-stack combo — goes
 * through this one action, so the audible ack covers all of them and none can
 * double-wire. Mirrors GamePanel.java:2873-2949 (playOnSkipButton on every skip
 * button activation).
 */
export function onSkipPriorityClicked(store: GameStore): void {
  playOrReport(store, 'skip-armed')
}

/**
 * Skips were cancelled (store/slices/gameplaySlice.ts `cancelSkip`, after the
 * send). Covers the armed-skip toggle click and the F3 "Cancel All Skips"
 * button, both of which call this action. Mirrors GamePanel.java:2957
 * (playOnSkipButtonCancel on the cancel button).
 */
export function onCancelSkipClicked(store: GameStore): void {
  playOrReport(store, 'skip-cancelled')
}
