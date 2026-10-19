import type { GameStore } from './types'
import type { Player, Card, Permanent } from '@/types'
import { isLikelyLand } from '@/utils/cardTypes'
import { isSocketDown } from '@/utils/socketDown'

/**
 * Derived selectors — pure functions that compute derived state from the store.
 * Prefer these over computing in components to avoid unnecessary re-renders.
 */

/** Get the current game state, or null if not in a game. */
export function selectGameState(state: GameStore): GameStore['gameState'] {
  return state.gameState
}

/** Get the current player (isMe = true), or null. */
export function selectCurrentPlayer(state: GameStore): Player | null {
  if (!state.gameState) return null
  return state.gameState.players.find((p) => p.isMe) ?? null
}

/** Get the opponent player(s) — all players where isMe is false. */
export function selectOpponents(state: GameStore): Player[] {
  if (!state.gameState) return []
  return state.gameState.players.filter((p) => !p.isMe)
}

/** Get the active player (whose turn it is), or null. */
export function selectActivePlayer(state: GameStore): Player | null {
  if (!state.gameState) return null
  return state.gameState.players.find((p) => p.name === state.gameState?.activePlayer) ?? null
}

/** Get the player with priority, or null. */
export function selectPriorityPlayer(state: GameStore): Player | null {
  if (!state.gameState) return null
  return state.gameState.players.find((p) => p.name === state.gameState?.priorityPlayer) ?? null
}

/** Get all permanents on the battlefield for a player. */
export function selectBattlefield(state: GameStore, playerName: string): Permanent[] {
  if (!state.gameState) return []
  const player = state.gameState.players.find((p) => p.name === playerName)
  return player?.battlefield ?? []
}

/** Get the stack (spells/abilities on the stack). */
export function selectStack(state: GameStore): Card[] {
  return state.gameState?.stack ?? []
}

/**
 * Get the ids of MY untapped lands — the legal `tap_mana` targets during a
 * mana payment prompt.
 *
 * ONLY the local player's battlefield is considered: tapping an opponent's
 * land is never legal, and XMage silently ignores it. Iterating every player
 * (the bug fixed here) made "Auto-Tap Lands" spam the opponent's first
 * untapped land forever, soft-locking the prompt.
 *
 * Prefer this over re-deriving the list at each call site — GameView's
 * highlight set and the mana-prompt tap buttons must agree on what is legal.
 */
export function selectMyUntappedLands(state: GameStore): Permanent[] {
  const me = selectCurrentPlayer(state)
  if (!me) return []
  return me.battlefield.filter((card) => !card.tapped && isLikelyLand(card))
}

/** Get the current action prompt, or null. */
export function selectActionPrompt(state: GameStore): GameStore['actionPrompt'] {
  return state.actionPrompt
}

/** Get the event log (game log entries). */
export function selectEventLog(state: GameStore): GameStore['eventLog'] {
  return state.eventLog
}

/** Check if currently connected to the XMage server. */
export function selectIsConnected(state: GameStore): boolean {
  return state.connected
}

/** Get the connection status for the banner. */
export function selectConnStatus(state: GameStore): string {
  return state.connStatus
}

/** Get the current view: 'connect' | 'lobby' | 'game' | 'tournament' | 'replay'. */
export function selectCurrentView(state: GameStore): 'connect' | 'lobby' | 'game' | 'tournament' | 'replay' {
  // A replay viewer outranks a live game: `inReplay` is only ever set while no
  // live game is attached (the proxy refuses replay_load during a game), and the
  // viewer must not be swapped out by a stray inGame flag.
  if (state.inReplay) return 'replay'
  if (state.inGame) return 'game'
  if (state.showTournamentRunning) return 'tournament'
  if (state.connected) return 'lobby'
  return 'connect'
}

/**
 * Whether the seated CREATOR may start the match: every seat filled and the table ready.
 *
 * This is the whole "Start Match" gate, and it is a pure function of store state on purpose
 * — the control used to compute it inside LobbyView from a `tables` array that only a 2 s
 * page timer refreshed. Chromium throttles timers in hidden tabs, so a backgrounded owner's
 * `tables` could sit 40–60 s stale and the button simply did not exist yet

 *
 * `tables` is now also refreshed by server pushes (the proxy's table watch emits `table_list`
 * whenever the watched table's seats/state change, and a WebSocket message is not throttled),
 * so this predicate must stay a pure function of state — do not reintroduce a timer or a ref
 * as its input.
 *
 * Server-side requirement mirrored here (do not loosen): `TableController.startMatch` only
 * proceeds for the table's owner and requires state READY_TO_START with all seats occupied.
 */
export function selectCanStartMatch(state: GameStore): boolean {
  const { myTableId, isTableOwner, tables } = state
  if (!isTableOwner || !myTableId) return false
  const myTable = tables.find((t) => t.id === myTableId)
  if (myTable === undefined) return false
  return (
    myTable.maxPlayers > 0 &&
    myTable.playerCount >= myTable.maxPlayers &&
    (myTable.state === 'READY_TO_START' || myTable.state === 'WAITING')
  )
}

/**
 * The board state the visible view should render: the replay frame while a
 * replay is open, the live game state otherwise. ReplayView passes this into
 * the shipped board components so they render one shape in both modes.
 */
export function selectBoardState(state: GameStore): GameStore['gameState'] {
  return state.inReplay ? state.replayState : state.gameState
}

/**
 * Whether the lobby's connection to the server is DOWN right now.
 *
 * With a dead websocket the lobby still
 * presented as connected and every action was silently dropped — the user
 * clicked into a void. The lobby must banner + disable when this is true.
 *
 * The rule itself lives in utils/socketDown.isSocketDown (pure, one
 * definition shared with the slice guards and the harness): `connected`
 * false, or connStatus 'reconnecting' (transport lost while the session is
 * still marked connected — App.tsx's onStatusChange sets exactly that) or
 * 'connection_lost' (the proxy lost its XMage-server link). Surfacing only:
 * reconnect/backoff policy is NOT decided here.
 */
export function selectSocketDown(state: GameStore): boolean {
  return isSocketDown(state)
}