/**
 * Multiplayer board modes — the PURE
 * decision layer for "how does the shared GameLayout shell render a 3+ player
 * game".
 *
 * Two viewer preferences live in Settings → Display ("Multiplayer layout"):
 *
 *  - `boardModeMaximizeOpponents` (maximize): opponents render as a compact chip
 *    strip along the top of the center column; my board takes the rest of the
 *    height (Arena-style).
 *  - `boardModeFocusEnabled` (focus): pseudo-1v1 against ONE opponent — the
 *    existing 1v1 split markup with that opponent as the top pane — while the
 *    other opponents show as small chips (avatar + name + life) with on-board
 *    controls: click a chip to focus, ◀/▶ arrows cycle, ✕ exits to the all view.
 *  - `boardModeFollowActive` (sub-toggle, stored only meaningful with focus on):
 *    the focused opponent follows the ACTIVE player; the selection is otherwise
 *    pinned as clicked/cycled.
 *
 * Both toggles coexist — Focus wins while a focus target is set; maximize
 * applies otherwise (item 4 of the approved design).
 *
 * This module is shared by the live board AND the replay viewer by construction:
 * the shell component (GameLayout) resolves the mode HERE from the board state
 * it already receives (`selectBoardState`-fed), never from raw `gameState`, so a
 * replay frame resolves exactly like a live frame. Keep it pure (inputs in, view
 * model out) — the harness asserts it without a store.
 *
 * Defaults:
 *  - 2-player games: BOTH toggles inert (the existing 1v1 path, exactly).
 *  - Follow-active on MY turn: the last active opponent sticks, falling forward
 *    on the next opponent turn; before any opponent has been active, the first.
 *  - A focused opponent who is eliminated/dropped: focus falls back to the all
 *    view — one explicit rule here, no silent shim in the component.
 */

/** Which layout the shell renders. */
export type BoardMode = 'all' | 'maximize' | 'focus'

/** The three persisted viewer preferences, exactly as the store carries them. */
export interface BoardModeSettings {
  maximizeOpponents: boolean
  focusEnabled: boolean
  followActive: boolean
}

/**
 * Why the resolver landed on its mode — an assertion/debug surface for the
 * harness and the component (no UI reads it).
 */
export type BoardModeReason =
  | 'two-player'
  | 'no-players'
  | 'focus-target-set'
  | 'follow-active'
  | 'no-focus-target'
  | 'focused-seat-gone'
  | 'default'

/** The resolver's full answer: the mode, the focused seat (focus mode) and why. */
export interface BoardModeView {
  mode: BoardMode
  /**
   * The focused opponent's NAME (names are the wire-stable seat identity in
   * this client — `activePlayer`/`priorityPlayer` are names too). Null in
   * every non-focus mode.
   */
  focusedName: string | null
  reason: BoardModeReason
}

/** A seat is seated (not departed/eliminated) — the only chips we render. */
function seated(p: PlayerLike): boolean {
  return !p.hasLeft
}

/** Minimal structural shape the resolver needs (Player satisfies it). */
export interface PlayerLike {
  name: string
  isMe: boolean
  hasLeft: boolean
}

/** The opponents in the given (caller-sorted) list, in order, skipping me. */
function opponentsOf(players: PlayerLike[]): PlayerLike[] {
  return players.filter((p) => !p.isMe)
}

/**
 * The opponents the chips/cycle may address: seated only. A departed seat must
 * not be focusable (the fallback returns to 'all') nor take a cycle step.
 */
export function focusableOpponents(players: PlayerLike[]): PlayerLike[] {
  return opponentsOf(players).filter(seated)
}

/**
 * The next focusable opponent from `current`, in direction `dir`. Skips MY own
 * seat (never focusable) and departed seats, wrapping around a single time. An
 * exhausted list (no OTHER focusable opponent) returns `current` unchanged —
 * the caller renders the same focus, which is the truthful answer for a
 * 1-opponent-plus-DEPARTED-seats table.
 */
export function cycleFocus(
  players: PlayerLike[],
  current: string | null,
  dir: 'prev' | 'next',
): string | null {
  const focusable = focusableOpponents(players)
  if (focusable.length === 0) return null
  if (current == null) return focusable[dir === 'next' ? 0 : focusable.length - 1]!.name
  const idx = focusable.findIndex((p) => p.name === current)
  if (idx < 0) return focusable[dir === 'next' ? 0 : focusable.length - 1]!.name
  const step = dir === 'next' ? 1 : -1
  return focusable[(idx + step + focusable.length) % focusable.length]!.name
}

/**
 * Resolve the mode for ONE board state.
 *
 * `view` carries the state the shell renders (`selectBoardState` — live game or
 * replay frame alike). `settings` is the three persisted toggles. `focus` is
 * the user's EXPLICIT focus address (chip click / arrows / ✕): a set address
 * pins that seat (it outranks follow-active; the resolver re-validates the
 * name and falls back to the other toggles when the seat is gone), null means
 * the user is NOT pinning anyone.
 */
export interface BoardFocusAddress {
  /** The focused seat's NAME (the wire-stable identity). */
  name: string
  /** True for a chip click / cycle step (a real user choice), never synthetic. */
  explicit: boolean
}

export function resolveBoardMode(
  state: {
    players: PlayerLike[]
    activePlayer?: string | null
    /** The shell's follow-active memory: the last frame's active OPPONENT. */
    lastActiveOpponent?: string | null
  },
  settings: BoardModeSettings,
  focus?: BoardFocusAddress | null | undefined,
): BoardModeView {
  const n = state.players.length
  // Scope guard FIRST: both toggles are inert outside a 3+ player game — the
  // existing 1v1 path exactly (approved design item on 2-player inertness).
  if (n < 2) return { mode: 'all', focusedName: null, reason: 'no-players' }
  if (n === 2) return { mode: 'all', focusedName: null, reason: 'two-player' }

  const focusable = focusableOpponents(state.players)

  // ── Focus branch (wins over maximize while a target is set) ──
  if (settings.focusEnabled) {
    // An EXPLICIT user choice (chip click / cycle step) pins that seat as long
    // as it is still a valid opponent — it outranks follow-active's tracking.
    // A stale address (seat left / never an opponent) first degrades to
    // maximize when IT is wanted, else the all view: the choice is merely
    // lost, never re-invented onto someone the user did not pick.
    if (focus != null) {
      const pinned = focusable.find((p) => p.name === focus.name)
      if (pinned) {
        return { mode: 'focus', focusedName: pinned.name, reason: 'focus-target-set' }
      }
      // The address is no longer any seated opponent's name.
      if (settings.maximizeOpponents) {
        return { mode: 'maximize', focusedName: null, reason: 'focused-seat-gone' }
      }
      // Address gone and maximize off: explicit all-view fallback (never a
      // half-updated focus pane, never a silent re-pin to someone else).
      return { mode: 'all', focusedName: null, reason: 'focused-seat-gone' }
    }
    if (settings.followActive) {
      // Follow-active (fix): NO address here means the user EXITED
      // focus (✕ sets null) — exiting must stick. The focus PANE still shows an
      // opponent while follow-active is on (the active one, or the memory of
      // the last active opponent on my turn), but it does so through the
      // follow-active ladder below with reason 'follow-active' — the shell
      // renders the 1v1 view for a follow-active target WITHOUT a pin and
      // treats the address as null, so ✕ cannot be overridden by a re-pin.
      // D1 measured live: a synthetic default pin made the exit unreachable.
      const remembered = state.lastActiveOpponent ?? null
      const active = state.activePlayer ?? null
      const target = (active != null
        ? focusable.find((p) => p.name === active)
        : undefined)
        ?? (remembered != null
          ? focusable.find((p) => p.name === remembered)
          : undefined)
      if (target) {
        return { mode: 'focus', focusedName: target.name, reason: 'follow-active' }
      }
      // Nobody valid to follow this frame (pregame, or no opponent ever
      // active): the first opponent.
      if (focusable.length > 0) {
        return { mode: 'focus', focusedName: focusable[0]!.name, reason: 'follow-active' }
      }
      return { mode: 'all', focusedName: null, reason: 'focused-seat-gone' }
    }
    // Focus on, no address, follow off: nothing to focus — the resolver does
    // not invent a target (the design's controls are click/cycle/follow). The
    // caller's other preference applies: maximize, else the all view.
  }

  // ── Maximize branch (both toggles off, or focus without a target) ──
  if (settings.maximizeOpponents) {
    return { mode: 'maximize', focusedName: null, reason: 'no-focus-target' }
  }
  return { mode: 'all', focusedName: null, reason: 'default' }
}