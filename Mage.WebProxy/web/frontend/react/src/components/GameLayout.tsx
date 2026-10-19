import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useGameStore } from '@/store/gameStore'
import { StackZone } from './StackZone'
import { CombatArrows } from './CombatArrows'
import { PhaseStrip } from './PhaseStrip'
import { BATTLEFIELD_SPLIT_EVENT, battlefieldRowSplitApplies } from '@/utils/cardFit'
import {
  cycleFocus,
  focusableOpponents,
  resolveBoardMode,
} from '@/utils/boardModes'
import { avatarIdForSeat, avatarUrlFor } from '@/utils/avatars'
import { rootZoom } from '@/utils/viewport'
import type { Card, GameState, Player } from '@/types'

/**
 * Game layout — the SHARED layout shell for the live game board and the replay
 * viewer.
 *
 * WHY THIS EXISTS: `GameView` and `ReplayView` used to each own a copy of this
 * chrome. Only the BOARD was shared (`selectBoardState` + `PlayerBoard` /
 * `StackZone`); the shell — rail widths + drag, the 1v1 battlefield split,
 * auto-fit wiring, the phase strip, the left rail's revealed buttons — lived
 * inside GameView alone, so the replay viewer silently fell a version behind
 * (measured 2026-09-24: 14 commits touched GameView since the viewer was born,
 * only 2 also touched ReplayView — no rail drag, no split, no auto-fit, old
 * PhaseBar placement).
 *
 * The fix is structural, not a port: this component owns the grid, both
 * resize handles, the split, the strip and the rails. The two views differ in
 * exactly ONE axis — what a seat's board renders and which state feeds it —
 * so that is the only thing they pass in (`renderBoard` + `state`). A future
 * chrome change lands in both viewers by construction.
 *
 * The viewer is read-only via the `readOnly` flag (stack trigger-order menu
 * off, phase chips inert); the layout chrome itself stays fully interactive,
 * because resizing a replay is a viewer preference, not a game action.
 */

// ── Panel resize constants ──
// Rail widths are ROOT CSS px but must scale with the whole chrome, so the
// base constants are multiplied by --ui-scale (set on :root by App.tsx) at
// render/drag time. This component reads the LIVE scale from the store (not a
// constant) so dragging follows the user's UI Scale slider; it must stay in
// sync with the value App.tsx writes to :root as --ui-scale.
const LEFT_MIN = 75        // min left rail width (px @ scale 1) — stack must stay visible
const LEFT_MAX = 300       // max left rail width (px @ scale 1)
const RIGHT_MIN = 150      // min right rail width (px @ scale 1) — tabs/prompts must stay usable
const RIGHT_MAX = 600      // max right rail width (px @ scale 1) — raised from 370 so the
                           // chat/log can take "relatively more of the screen"
const LEFT_DEFAULT = 140   // px @ scale 1 — 189 rendered at the 1.35 default
const RIGHT_DEFAULT = 280  // px @ scale 1 — 378 rendered at the 1.35 default

/**
 * DOM event App.tsx's auto-fit listens for: rail widths changed (drag in progress
 * or drag finished). Drag-move events carry `detail: { left?, right?, scale }` with
 * the new RENDERED width(s) (base × scale) because localStorage is only written on
 * mouseup; the mouseup event has no detail and App falls back to localStorage.
 */
export const PANEL_W_EVENT = 'xmage-panel-w-changed'

// ── Battlefield split (opponent % of the board height) ──
// 1v1: vertical divider between the opponent board (top pane) and own board
// bottom pane) inside .game-center. 4 players:
// horizontal divider between the TOP grid row of boards and the BOTTOM row
// — the same percentage, persisted under the same key. Persisted as the
// TOP half's percentage of the board area height (the remaining % goes to
// the bottom half). No split for any other count (3 players keep the fixed
// grid: top row 2 / bottom row 1).
const SPLIT_MIN = 25      // % — never let either board collapse shut
const SPLIT_MAX = 75
const SPLIT_DEFAULT = 50

/** Clamp a value between min and max. */
const clamp = (v: number, min: number, max: number) => Math.max(min, Math.min(max, v))

/** Load a persisted panel width from localStorage, falling back to default. */
function loadWidth(key: string, fallback: number): number {
  try {
    const v = localStorage.getItem(key)
    if (v != null) return clamp(parseInt(v, 10), 50, 800)
  } catch { /* ignore */ }
  return fallback
}

/** Load the persisted battlefield split (% for the opponent pane), clamped. */
function loadBattlefieldSplit(): number {
  try {
    const v = localStorage.getItem('xmage-battlefield-split')
    if (v != null) {
      const n = parseFloat(v)
      if (Number.isFinite(n)) return clamp(n, SPLIT_MIN, SPLIT_MAX)
    }
  } catch { /* ignore */ }
  return SPLIT_DEFAULT
}

/**
 * One opponent chip: avatar + name + life in a compact pill.
 *
 * RENDERED in two informational grades:
 *  - maximize mode (`focusMode=false`): every seated opponent, purely
 *    informational — clicks are swallowed (click-to-focus lives only when the
 *    Focus toggle is on, re-checked live inside the handler).
 *  - focus mode (`focusMode=true`): the NOT-focused seated opponents; a click
 *    pins the focus to that seat, the active-turn chip is highlighted.
 * The avatar uses the seat resolver chain (the same one PlayerBoard's header
 * uses) so computer seats draw i64.jpg and unknown ids the default — never a
 * broken image.
 */
interface OpponentChipProps {
  player: Player
  active: boolean
  focusMode: boolean
  onChipClick: (name: string) => void
}

function OpponentChip({ player, active, focusMode, onChipClick }: OpponentChipProps) {
  const myAvatarId = useGameStore((s) => s.avatarId)
  const seatAvatarId = avatarIdForSeat(player.avatarId, !!player.isMe, myAvatarId)
  return (
    <div
      className={`opponent-chip${active ? ' chip-active-turn' : ''}`}
      role="listitem"
      data-chip-player={player.name}
      onClick={focusMode ? () => onChipClick(player.name) : undefined}
      // Focus-mode chips are buttons in behaviour; maximize chips are inert.
      style={focusMode ? { cursor: 'pointer' } : undefined}
      title={focusMode ? `Focus ${player.name}` : undefined}
    >
      <img
        className="opponent-chip-avatar"
        src={avatarUrlFor(seatAvatarId)}
        alt=""
        width={20}
        height={20}
        data-avatar-id={seatAvatarId}
      />
      <span className="opponent-chip-name">{player.name}</span>
      <span className="opponent-chip-life">{player.life}</span>
    </div>
  )
}

export interface GameLayoutProps {
  /** Board state to render — the live `gameState` or a replay frame. */
  state: GameState
  /**
   * Players to lay out, ALREADY sorted by the caller (opponents first, "me"
   * last). The sort reads viewer-specific flags, so it stays in the views.
   */
  players: Player[]
  /**
   * Read-only viewer (replay): the stack's trigger-order menu and the phase
   * chips' stop settings are game actions, so both are neutralized. The layout
   * chrome itself (rail drag, split) stays live in BOTH modes.
   */
  readOnly?: boolean
  /**
   * Renders ONE seat's board. The only thing the live game and the replay
   * viewer differ in — the live board wires click handlers and playable/target
   * sets, the replay passes none. `isTopPlayer` is resolved by the shell from
   * the layout (1v1: the top pane's seat).
   */
  renderBoard: (player: Player, isTopPlayer: boolean) => React.ReactNode
  /** Right rail content (tabs; the live game appends its action prompt bar). */
  rightRail: React.ReactNode
}

export function GameLayout({
  state,
  players,
  readOnly = false,
  renderBoard,
  rightRail,
}: GameLayoutProps) {
  // ── Panel resize state ──
  const uiScale = useGameStore((s) => s.uiScale)
  const uiScaleRef = useRef(uiScale)
  uiScaleRef.current = uiScale
  // Scale-aware rail widths: persisted values are px @ scale 1; the live state
  // carries px @ the CURRENT scale. On scale change the rail keeps its
  // scale-1 width — widths are re-derived in the render var below so both the
  // visual width and the drag clamps follow the same multiplier.
  const [leftWidthBase, setLeftWidthBase] = useState(() => loadWidth('xmage-left-col-w', LEFT_DEFAULT))
  const [rightWidthBase, setRightWidthBase] = useState(() => loadWidth('xmage-right-col-w', RIGHT_DEFAULT))
  const scale = uiScaleRef.current
  const leftWidth = leftWidthBase * scale
  const rightWidth = rightWidthBase * scale
  const dragRef = useRef<'left' | 'right' | null>(null)
  const dragStartX = useRef(0)
  const dragStartW = useRef(0)
  // Root zoom captured at drag start — clientX deltas are REAL px while the
  // rail widths are ROOT CSS px, so deltas must be divided by the zoom.
  const dragStartZoom = useRef(1)
  // Refs holding the latest widths for the mousemove/mouseup handlers
  const leftWidthBaseRef = useRef(leftWidthBase)
  const rightWidthBaseRef = useRef(rightWidthBase)
  leftWidthBaseRef.current = leftWidthBase
  rightWidthBaseRef.current = rightWidthBase

  // ── Battlefield split (1v1): opponent % of the board height ──
  const settingsResetAt = useGameStore((s) => s.settingsResetAt)
  const [splitPercent, setSplitPercent] = useState(loadBattlefieldSplit)
  const splitPercentRef = useRef(splitPercent)
  splitPercentRef.current = splitPercent
  // ── Battlefield split state ──
  // The split lives in 1v1 (vertical panes) and in 4-player games (the row
  // split, option 1 approved 2026-09-28): the phase-strip grid row between the
  // top and bottom rows doubles as the divider's home, exactly like the strip
  // row sits between the two 1v1 panes. Any other count (3 players included)
  // keeps the fixed grid and carries no split state.
  const [battlefieldAreaHeight, setBattlefieldAreaHeight] = useState(0)
  const boardAreaRef = useRef<HTMLDivElement | null>(null)
  const splitDragRef = useRef(false)
  const splitDragStartY = useRef(0)
  const splitDragStartPercent = useRef(0)
  // Root zoom captured at drag start — clientY deltas are REAL px while the
  // stored percentage is CSS-px based, so deltas must be divided by the zoom.
  const splitDragStartZoom = useRef(1)

  const startDrag = useCallback((which: 'left' | 'right') => (e: React.MouseEvent) => {
    e.preventDefault()
    dragRef.current = which
    dragStartX.current = e.clientX
    dragStartW.current = which === 'left' ? leftWidthBaseRef.current * uiScaleRef.current : rightWidthBaseRef.current * uiScaleRef.current
    dragStartZoom.current = rootZoom()
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
  }, [])

  const startSplitDrag = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    splitDragRef.current = true
    splitDragStartY.current = e.clientY
    splitDragStartPercent.current = splitPercentRef.current
    splitDragStartZoom.current = rootZoom()
    document.body.style.cursor = 'row-resize'
    document.body.style.userSelect = 'none'
  }, [])

  // Re-read standalone localStorage settings after "Reset to Default" — the
  // reset removes the keys, so this restores the code defaults without a
  // page reload (rail widths; the split via loadBattlefieldSplit).
  useEffect(() => {
    if (settingsResetAt === 0) return
    setLeftWidthBase(loadWidth('xmage-left-col-w', LEFT_DEFAULT))
    setRightWidthBase(loadWidth('xmage-right-col-w', RIGHT_DEFAULT))
    setSplitPercent(loadBattlefieldSplit())
  }, [settingsResetAt])

  // Measure the board area (the split container) for the split drag's % math.
  // Re-armed when the split mounts/unmounts (1v1 ↔ multiplayer transitions,
  // game join/leave, replay frame swaps) — `state` covers those; the split
  // element exists only in the 1v1 branch, so the observer simply no-ops
  // otherwise.
  useEffect(() => {
    const el = boardAreaRef.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(() => setBattlefieldAreaHeight(el.clientHeight))
    ro.observe(el)
    setBattlefieldAreaHeight(el.clientHeight)
    return () => ro.disconnect()
  }, [state])

  useEffect(() => {
    function onMouseMove(e: MouseEvent) {
      // Battlefield split drag
      if (splitDragRef.current) {
        if (battlefieldAreaHeight <= 0) return
        const deltaPx = (e.clientY - splitDragStartY.current) / splitDragStartZoom.current
        // Down = give the opponent pane more height = higher opponent %
        const deltaPercent = (deltaPx / battlefieldAreaHeight) * 100
        const nextSplit = clamp(splitDragStartPercent.current + deltaPercent, SPLIT_MIN, SPLIT_MAX)
        setSplitPercent(nextSplit)
        // Auto-fit reads the live split: boards are UNEQUAL after a drag, and the
        // smaller pane's height is their fit budget. Same DOM-event reasoning as
        // PANEL_W_EVENT below — mousemove-rate updates must not re-render the tree.
        window.dispatchEvent(
          new CustomEvent(BATTLEFIELD_SPLIT_EVENT, { detail: { opponentPct: nextSplit } })
        )
        return
      }
      if (!dragRef.current) return
      const which = dragRef.current
      const delta = (e.clientX - dragStartX.current) / dragStartZoom.current
      // scale captured at drag start (startDrag) — a slider change mid-drag is
      // an edge case not worth re-deriving clamps for
      const dragScale = uiScaleRef.current
      if (which === 'left') {
        const newW = clamp(dragStartW.current + delta, LEFT_MIN * dragScale, LEFT_MAX * dragScale)
        setLeftWidthBase(newW / dragScale)
        // Auto-fit reads the live rail widths: every drag step must re-run the fit
        // math or cards keep a stale size while the columns move. The event carries
        // the new RENDERED width (base × scale) because localStorage is only written
        // on mouseup. DOM event, not store state — drag updates fire at mousemove
        // rate and must not re-render the tree.
        window.dispatchEvent(
          new CustomEvent(PANEL_W_EVENT, { detail: { left: newW, scale: dragScale } })
        )
      } else {
        const newW = clamp(dragStartW.current - delta, RIGHT_MIN * dragScale, RIGHT_MAX * dragScale)
        setRightWidthBase(newW / dragScale)
        window.dispatchEvent(
          new CustomEvent(PANEL_W_EVENT, { detail: { right: newW, scale: dragScale } })
        )
      }
    }
    function onMouseUp() {
      if (splitDragRef.current) {
        splitDragRef.current = false
        document.body.style.cursor = ''
        document.body.style.userSelect = ''
        try {
          localStorage.setItem('xmage-battlefield-split', String(splitPercentRef.current))
        } catch { /* ignore */ }
        // mouseup: persistence done — plain event (no detail) tells auto-fit to
        // fall back to the persisted value, mirroring PANEL_W_EVENT's protocol.
        window.dispatchEvent(new Event(BATTLEFIELD_SPLIT_EVENT))
        return
      }
      if (!dragRef.current) return
      dragRef.current = null
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
      // Persist the scale-1 base widths (loadWidth multiplies by the live scale)
      try {
        localStorage.setItem('xmage-left-col-w', String(leftWidthBaseRef.current))
        localStorage.setItem('xmage-right-col-w', String(rightWidthBaseRef.current))
      } catch { /* ignore */ }
      window.dispatchEvent(new Event(PANEL_W_EVENT))
    }
    document.addEventListener('mousemove', onMouseMove)
    document.addEventListener('mouseup', onMouseUp)
    return () => {
      document.removeEventListener('mousemove', onMouseMove)
      document.removeEventListener('mouseup', onMouseUp)
    }
  }, [battlefieldAreaHeight])

  const is1v1 = players.length === 2
  // 4-player row split: exactly 4 players get the
  // horizontal divider between the two GRID ROWS of the 2-column grid. The
  // strip's own `auto` row stays between them (gridTemplateRows below), so the
  // handle rides in the strip row and both fractional rows trade height. The
  // "exactly 4" predicate is shared with the fit math (cardFit), so the UI and
  // the sizing can never disagree on which counts get a split.
  // ── Multiplayer board modes ──
  // The three persisted viewer preferences + the session-scoped focus pin.
  // Read here (ABOVE the row-split predicate) because that predicate suppresses
  // the split in the focus-awaiting grid state; the mode logic below shares them.
  const boardModeMaximizeOpponents = useGameStore((s) => s.boardModeMaximizeOpponents)
  const boardModeFocusEnabled = useGameStore((s) => s.boardModeFocusEnabled)
  const boardModeFollowActive = useGameStore((s) => s.boardModeFollowActive)
  const boardModeFocusTarget = useGameStore((s) => s.boardModeFocusTarget)
  // The row split is ALSO suppressed while the grid is the focus-awaiting
  // state (focus ON, nothing pinned, follow OFF) — the clickable chip strip owns
  // the top of the column there, exactly like the maximized layout's strip.
  // (The board-mode store reads live above this line, shared with the mode
  // logic below.)
  const hasRowSplit = battlefieldRowSplitApplies(players.length)
    && !(boardModeFocusEnabled && !is1v1 && boardModeFocusTarget == null && !boardModeFollowActive)

  // ── Multiplayer board modes ──
  // The shell resolves the ONE mode from the board state it already renders
  // (`state` — selectBoardState-fed by both views, so a replay frame resolves
  // exactly like a live frame), the three persisted viewer preferences, and the
  // user's pinned focus choice. `settingsResetAt` is declared above (the split's
  // re-read hook needs it); the pure math lives in utils/boardModes.ts.
  const setBoardModeFocusTarget = useGameStore((s) => s.setBoardModeFocusTarget)
  // Focus mode turned OFF (or a reset): the pinned target is meaningless — drop
  // it so focus cannot quietly re-engage while hidden.
  useEffect(() => {
    if (!boardModeFocusEnabled && boardModeFocusTarget != null) setBoardModeFocusTarget(null)
  }, [boardModeFocusEnabled, settingsResetAt, boardModeFocusTarget, setBoardModeFocusTarget])

  // Follow-active stickiness: when a frame's active seat is an OPPONENT, remember
  // it — the next frame (which may be MY turn) must keep focusing that seat and
  // fall FORWARD only when another opponent becomes active. The resolver reads
  // only the current frame (pure), so this ref is the shell's memory.
  const lastActiveOpponentRef = useRef<string | null>(null)
  const activeOpponentNow = focusableOpponents(state.players)
    .find((p) => p.name === state.activePlayer)
  if (activeOpponentNow) lastActiveOpponentRef.current = activeOpponentNow.name
  const boardStateRef = useRef(state)
  boardStateRef.current = state

  // Rising edge of the Focus toggle: a JUST-ENABLED focus mode
  // selects the FIRST seated opponent exactly once — an event-driven default
  // (like a select defaulting on open), not a per-render synthetic pin — so
  // the pane appears immediately, as measured live. The edge
  // never re-fires while the toggle stays on, so ✕'s null target STICKS
  // (measured: a per-render default pin made the exit unreachable).
  // Follow-active ON keeps the ladder in charge of the pane (no pin).
  const prevFocusEnabledRef = useRef(boardModeFocusEnabled)
  useEffect(() => {
    const rising = boardModeFocusEnabled && !prevFocusEnabledRef.current
    prevFocusEnabledRef.current = boardModeFocusEnabled
    if (!rising) return
    if (useGameStore.getState().boardModeFollowActive) return
    if (useGameStore.getState().boardModeFocusTarget != null) return
    const first = focusableOpponents(boardStateRef.current.players)[0]
    if (first) setBoardModeFocusTarget(first.name)
  }, [boardModeFocusEnabled, setBoardModeFocusTarget])

  // The focus ADDRESS is the user's explicit choice (chip click / cycle step) —
  // STORE state so it survives re-renders; session-scoped (never persisted, a
  // focused opponent must not survive into the next game/view), cleared when
  // the toggle turns off. null = NOT pinned: ✕ exit included —
  // a synthetic default pin here made the exit unreachable; measured live
  // 2026-10-03). Without a pin the follow-active ladder owns the 1v1 pane, so
  // with follow-active ON an exit keeps the pane (following the ladder) and the
  // ✕ control is HIDDEN — the controls belong to a pin; see the focus JSX.
  const boardMode = resolveBoardMode(
    { ...state, lastActiveOpponent: lastActiveOpponentRef.current },
    {
      maximizeOpponents: boardModeMaximizeOpponents,
      focusEnabled: boardModeFocusEnabled,
      followActive: boardModeFollowActive,
    },
    boardModeFocusTarget != null ? { name: boardModeFocusTarget, explicit: true } : null,
  )
  // A stale pin (its seat left, focus still on) is an IMPOSSIBLE state — the
  // resolver already fell back (all/maximize); normalize the dead address to
  // null so the awaiting strip (or the follow ladder) is reachable again.
  // State cleanup of garbage, not a re-pin: nothing is invented here.
  useEffect(() => {
    if (boardMode.reason === 'focused-seat-gone' && boardModeFocusTarget != null) {
      setBoardModeFocusTarget(null)
    }
  }, [boardMode.reason, boardModeFocusTarget, setBoardModeFocusTarget])
  const focusedPlayer =
    boardMode.mode === 'focus' && boardMode.focusedName != null
      ? players.find((p) => p.name === boardMode.focusedName) ?? null
      : null
  /**
   * The center column's LAYOUT (distinct from the mode): 'split' = the 1v1
   * element order (2-player games AND focus mode — the same split markup),
   * 'maximize' = chip strip + my board, 'grid' = the historical multiplayer
   * grid (the all view). Focus with a momentarily invalid target renders the
   * all grid (boardModes.resolveBoardMode already said 'all'). Kept as a plain
   * `string` union via effectiveLayout below — TS narrows `boardLayout` under
   * `||`, so the focus comparison must see the full union.
   */
  const boardLayout: 'split' | 'maximize' | 'focus' | 'grid' = ((): 'split' | 'maximize' | 'focus' | 'grid' => {
    if (is1v1) return 'split'
    if (boardMode.mode === 'focus') return focusedPlayer != null ? 'focus' : 'grid'
    if (boardMode.mode === 'maximize') return 'maximize'
    return 'grid'
  })()
  // The single board the maximize layout renders (the shell's me, found in the
  // caller's list — a game without a me seat, e.g. an all-AI replay, has no
  // maximized board and falls back to the grid below).
  const myBoardPlayer = players.find((p) => p.isMe === true) ?? null
  /**
   * Maximize with NO me seat renders the historical grid instead — a maximized
   * column needs a board to fill it, and the strip alone would be a broken
   * layout. (The 2-player inertness already covers every real 1v1 case.)
   */
  const effectiveLayout: 'split' | 'maximize' | 'focus' | 'grid' =
    boardLayout === 'maximize' && myBoardPlayer == null ? 'grid' : boardLayout
  // The chip list — seated opponents in the caller's sort:
  //   maximize: every seated opponent (informational strip)
  //   focus:    the NOT-focused seated opponents
  //   all + focus ON + nothing pinned + follow OFF (awaiting a choice): every
  //             seated opponent, clickable — the entry affordance INTO focus
  //             now that the mode no longer pins a synthetic first opponent
  //             (the strip hides once a pin is set).
  const gridAwaitingFocus = boardLayout === 'grid'
    && boardModeFocusEnabled && boardModeFocusTarget == null && !boardModeFollowActive
  const chipPlayers = useMemo((): Player[] => {
    const seated = focusableOpponents(players) as Player[]
    if (boardMode.mode === 'maximize') return seated
    if (boardMode.mode === 'focus' && focusedPlayer != null) {
      return seated.filter((p) => p.name !== focusedPlayer.name)
    }
    if (gridAwaitingFocus) return seated
    return []
  }, [players, boardMode.mode, focusedPlayer, gridAwaitingFocus])

  const startChipFocus = useCallback((name: string) => {
    // Click-to-focus is live only when the Focus toggle is ON (maximize chips
    // are informational); the resolver re-validates the target, so a stale
    // click on a departed seat cannot fabricate a focus pane.
    if (!useGameStore.getState().boardModeFocusEnabled) return
    setBoardModeFocusTarget(name)
  }, [setBoardModeFocusTarget])

  const cycleChipFocus = useCallback((dir: 'prev' | 'next') => {
    const current = pinnedFocusRef.current ?? renderedFocusRef.current
    const next = cycleFocus(players, current, dir)
    if (next != null) setBoardModeFocusTarget(next)
  }, [players, setBoardModeFocusTarget])

  const exitFocus = useCallback(() => setBoardModeFocusTarget(null), [setBoardModeFocusTarget])

  // Reference for cycle steps AFTER a user action in the same frame (the
  // callbacks close over render-time values; refs are stable identities, so
  // the callback reads the LATEST .current at invocation). pinnedFocusRef
  // carries the STORE pin; renderedFocusRef mirrors what the follow-active
  // ladder put on screen so the first ◀/▶ press from an unpinned pane
  // continues from the visible focus.
  const pinnedFocusRef = useRef(boardModeFocusTarget)
  pinnedFocusRef.current = boardModeFocusTarget
  const renderedFocusRef = useRef<string | null>(null)
  renderedFocusRef.current = boardMode.focusedName

  // Phase strip placement (see PhaseStrip): a full-width row between the top and
  // bottom halves of the boards. 1v1 stacks players in one column, so the strip
  // lands between the two boards; multiplayer uses the 2-column grid, so it
  // lands after the top half of the rows — boards keep equal `1fr` rows and the
  // strip's own row is reserved as `auto` (content height), so it never
  // overlays cards. This inline template overrides #players-grid.players-1v1's
  // `1fr 1fr` in game-renderer.css. 4 players: the two ROW fractions come from
  // the live split instead of `1fr` (the row split), the strip row stays `auto`
  // between them — with no split set they are `50fr`/`50fr`, i.e. identical to
  // the fixed `1fr 1fr` grid.
  const playerCols = is1v1 ? 1 : 2
  const playerRows = Math.ceil(players.length / playerCols)
  const rowsBeforeStrip = Math.ceil(playerRows / 2)
  const stripAfterIndex = rowsBeforeStrip * playerCols - 1
  const gridRows = [
    ...Array(rowsBeforeStrip).fill(hasRowSplit ? `${splitPercent}fr` : '1fr'),
    'auto',
    ...Array(playerRows - rowsBeforeStrip).fill(hasRowSplit ? `${100 - splitPercent}fr` : '1fr'),
  ].join(' ')

  // 1v1 battlefield split: each board renders in its own pane, joined by the
  // drag divider. Same card content as the multiplayer path — only the
  // container differs. (Both panes keep the `1fr` row the board expects.)
  const opponent = players.find((p) => p.isMe !== true) ?? players[0]
  const me = players.find((p) => p.isMe === true) ?? players[1]

  // Per-pane card-sizing intermediates (App.tsx applyPaneCardSizing writes the
  // --pane-card-* :root variables). Defined on whichever element is an ANCESTOR
  // of the boards that re-scope their --card-* vars from them: the 1v1
  // .battlefield-split container, and for the row split the #players-grid
  // element itself (its board rows are its own children). A custom property on
  // the re-scoping element itself may not reference its own override — that
  // self-reference is a cycle (guaranteed-invalid), hence the shared constant,
  // spread by BOTH split geometries so the two cannot drift apart.
  const SPLIT_PANE_VARS = {
    '--pane-width': 'var(--pane-card-opponent-width, var(--card-width))',
    '--pane-height': 'var(--pane-card-opponent-height, var(--card-height))',
    '--pane-overlap': 'var(--pane-card-opponent-overlap, var(--card-overlap))',
    '--pane-width-sm': 'var(--pane-card-opponent-width-sm, var(--card-width-sm))',
    '--pane-height-sm': 'var(--pane-card-opponent-height-sm, var(--card-height-sm))',
    '--pane-sm-width': 'var(--pane-card-opponent-sm-width, var(--card-sm-width))',
    '--pane2-width': 'var(--pane-card-me-width, var(--card-width))',
    '--pane2-height': 'var(--pane-card-me-height, var(--card-height))',
    '--pane2-overlap': 'var(--pane-card-me-overlap, var(--card-overlap))',
    '--pane2-width-sm': 'var(--pane-card-me-width-sm, var(--card-width-sm))',
    '--pane2-height-sm': 'var(--pane-card-me-height-sm, var(--card-height-sm))',
    '--pane2-sm-width': 'var(--pane-card-me-sm-width, var(--card-sm-width))',
  } as React.CSSProperties

  // Left rail: stack + the revealed/looked-at reopen buttons. Both views carry
  // `revealed`/`lookedAt` in their state (a replay frame is a full GameState),
  // so the affordance is shared — in a replay the buttons read the frame's own
  // reveal state.
  const revealedGroups = [
    ...(state.revealed ?? []).map((g) => ({ key: 'rev', name: g.name, cards: g.cards })),
    ...(state.lookedAt ?? []).map((g) => ({ key: 'look', name: g.name, cards: g.cards })),
  ]
  const setRevealedPopup = useGameStore((s) => s.setRevealedPopup)

  return (
    <div
      className="game-content"
      style={{
        '--left-col-w': `${leftWidth}px`,
        '--right-col-w': `${rightWidth}px`,
      } as React.CSSProperties}
    >
      {/* Left rail: stack */}
      <div className="game-left">
        <StackZone stack={state.stack} readOnly={readOnly} />
        {/* Revealed section — buttons to reopen revealed/lookedAt groups */}
        {revealedGroups.some((g) => g.cards.length > 0) && (
          <div className="revealed-section">
            <div className="zone-title">Revealed</div>
            <div className="revealed-buttons" style={{ display: 'flex', flexWrap: 'wrap', gap: '0.4rem' }}>
              {revealedGroups.map((g, idx) => (
                g.cards.length > 0 && (
                  <button
                    key={`${g.key}-${idx}`}
                    className="revealed-reopen-btn"
                    onClick={() => setRevealedPopup({ name: g.name, cards: g.cards as Card[] })}
                  >
                    {g.name}
                  </button>
                )
              ))}
            </div>
          </div>
        )}
      </div>

      {/* Resize handle: left rail ↔ center */}
      <div
        className={`resize-handle${dragRef.current === 'left' ? ' dragging' : ''}`}
        onMouseDown={startDrag('left')}
      />

      {/* Center: player boards with combat arrows overlay */}
      <div className="game-center">
        {effectiveLayout === 'split' ? (
          <div
            className="battlefield-split"
            ref={boardAreaRef}
            style={{
              '--split-pct': `${splitPercent}%`,
              '--split-rest': `${100 - splitPercent}%`,
              /* Per-pane card sizing (App.tsx applyPaneCardSizing): each pane
                 re-scopes the global --card-* vars to its OWN fit size, so
                 the two battlefields scale independently against each other.
                 Defined on the container (an ancestor) because a custom
                 property on the pane itself may not reference that same
                 pane's --card-* override (self-reference is a cycle). */
              ...SPLIT_PANE_VARS,
            } as React.CSSProperties}
          >
            {/* Opponent board (top pane) */}
            <div className="battlefield-split-pane" data-split-pane="opponent">
              <div className="players-area" style={{ position: 'relative' }}>
                <div
                  id="players-grid"
                  className="players-1v1"
                  style={{ gridTemplateRows: '1fr' }}
                >
                  {opponent && renderBoard(opponent, true)}
                </div>
                <CombatArrows combatData={state.combat} />
              </div>
            </div>

            {/* Phase strip keeps its place between the two boards (see
                PhaseStrip) — directly above the divider. */}
            <PhaseStrip gameState={state} readOnly={readOnly} />

            {/* Drag divider: opponent ↔ own board */}
            <div
              className={`resize-handle resize-handle-h${splitDragRef.current ? ' dragging' : ''}`}
              onMouseDown={startSplitDrag}
              data-explain="battlefield-split-handle"
            />

            {/* Own board (bottom pane) */}
            <div className="battlefield-split-pane" data-split-pane="me">
              <div className="players-area" style={{ position: 'relative' }}>
                <div
                  id="players-grid"
                  className="players-1v1"
                  style={{ gridTemplateRows: '1fr' }}
                >
                  {me && renderBoard(me, false)}
                </div>
              </div>
            </div>
          </div>
        ) : effectiveLayout === 'maximize' ? (
          /* ── Maximize: opponents as a compact chip strip along the
              top of the center column, my board the remaining full height.
              The strip's height is content-sized (auto rows); my board keeps
              the players grid the shell has always rendered, so every sibling
              chrome (rail drag, combat arrows, auto-fit) is untouched. Chips
              are INFORMATIONAL here — click-to-focus only lives when the
              Focus toggle is on (handled inside onChipClick). */
          <div className="board-mode-maximized">
            <div className="opponent-chips" role="list" aria-label="Opponents">
              {chipPlayers.map((p) => (
                <OpponentChip
                  key={p.playerId ?? p.name}
                  player={p}
                  active={p.name === state.activePlayer}
                  onChipClick={startChipFocus}
                  focusMode={false}
                />
              ))}
            </div>
            <div className="players-area" style={{ position: 'relative' }}>
              <div
                id="players-grid"
                style={{ gridTemplateColumns: '1fr', gridTemplateRows: '1fr' }}
              >
                {myBoardPlayer && renderBoard(myBoardPlayer, false)}
              </div>
              <CombatArrows combatData={state.combat} />
            </div>
          </div>
        ) : effectiveLayout === 'focus' && focusedPlayer != null ? (
          /* ── Focus: pseudo-1v1 vs the focused opponent — the SAME
              split markup as the 1v1 branch (this IS the 1v1 branch's JSX,
              reused verbatim), with the non-focused opponents as small chips
              (avatar + name + life) and the on-board controls ◀ ▶ ✕. The
              focused opponent is the TOP pane; per-pane card sizing applies
              unchanged (SPLIT_PANE_VARS + applyPaneCardSizing). */
          <div className="board-mode-focus">
            <div className="opponent-chips opponent-chips-focus" role="list" aria-label="Other opponents">
              {chipPlayers.map((p) => (
                <OpponentChip
                  key={p.playerId ?? p.name}
                  player={p}
                  active={p.name === state.activePlayer}
                  onChipClick={startChipFocus}
                  focusMode
                />
              ))}
              {chipPlayers.length > 0 && (
                <>
                  <button
                    type="button"
                    className="focus-control focus-prev"
                    onClick={() => cycleChipFocus('prev')}
                    aria-label="Previous opponent"
                    title="Previous opponent (cycles the chips)"
                  >
                    ◀
                  </button>
                  <button
                    type="button"
                    className="focus-control focus-next"
                    onClick={() => cycleChipFocus('next')}
                    aria-label="Next opponent"
                    title="Next opponent (cycles the chips)"
                  >
                    ▶
                  </button>
                </>
              )}
              {/* ✕ is a PIN control: it clears an explicit focus choice.
                  The follow-active ladder (no pin, follow ON) re-focuses by
                  design — showing ✕ there would promise an exit the resolver
                  immediately undoes, so the button only renders while pinned. */}
              {boardModeFocusTarget != null && (
                <button
                  type="button"
                  className="focus-control focus-exit"
                  onClick={exitFocus}
                  aria-label="Exit focus mode"
                  title="Exit focus — back to all players"
                >
                  ✕
                </button>
              )}
            </div>
            <div
              className="battlefield-split"
              ref={boardAreaRef}
              style={{
                '--split-pct': `${splitPercent}%`,
                '--split-rest': `${100 - splitPercent}%`,
                ...SPLIT_PANE_VARS,
              } as React.CSSProperties}
            >
              <div className="battlefield-split-pane" data-split-pane="opponent">
                <div className="players-area" style={{ position: 'relative' }}>
                  <div
                    id="players-grid"
                    className="players-1v1"
                    style={{ gridTemplateRows: '1fr' }}
                  >
                    {renderBoard(focusedPlayer, true)}
                  </div>
                  <CombatArrows combatData={state.combat} />
                </div>
              </div>

              <PhaseStrip gameState={state} readOnly={readOnly} />

              <div
                className={`resize-handle resize-handle-h${splitDragRef.current ? ' dragging' : ''}`}
                onMouseDown={startSplitDrag}
                data-explain="battlefield-split-handle"
              />

              <div className="battlefield-split-pane" data-split-pane="me">
                <div className="players-area" style={{ position: 'relative' }}>
                  <div
                    id="players-grid"
                    className="players-1v1"
                    style={{ gridTemplateRows: '1fr' }}
                  >
                    {me && renderBoard(me, false)}
                  </div>
                </div>
              </div>
            </div>
          </div>
        ) : (
          <div className="players-area" style={{ position: 'relative' }}>
            {/* Focus-awaiting strip: when the Focus toggle is
                ON but nothing is pinned and follow-active is OFF, the grid
                grows a clickable chip row above it — the entry affordance INTO
                focus (click a chip to pin). Hidden in every other state: the
                grid renders bare without the toggle, once pinned the
                focus layout owns the chips, and with follow-active ON the
                ladder focuses without a pin. */}
            {gridAwaitingFocus && chipPlayers.length > 0 && (
              <div className="opponent-chips" role="list" aria-label="Focus an opponent">
                {chipPlayers.map((p) => (
                  <OpponentChip
                    key={p.playerId ?? p.name}
                    player={p}
                    active={p.name === state.activePlayer}
                    focusMode
                    onChipClick={startChipFocus}
                  />
                ))}
              </div>
            )}
            {/* Strip row comes from gridRows (1fr auto 1fr in 1v1): reserved
                middle row, both boards keep equal 1fr halves. 4 players
                (hasRowSplit): the two row fractions come from the live split
                and the auto strip row also carries the drag divider, so the
                top and bottom rows trade height exactly like the 1v1 panes. */}
            <div
              id="players-grid"
              className={is1v1 ? 'players-1v1' : hasRowSplit ? 'players-row-split' : ''}
              ref={hasRowSplit ? boardAreaRef : undefined}
              style={{
                gridTemplateRows: gridRows,
                ...(hasRowSplit ? SPLIT_PANE_VARS : {}),
              }}
            >
              {players.map((player, idx) => (
                <Fragment key={player.playerId ?? player.name}>
                  {renderBoard(player, false)}
                  {idx === stripAfterIndex && (
                    hasRowSplit ? (
                      /* 4p row split: the strip's `auto` row is the divider's
                         home — strip first (between the boards, as always),
                         drag divider directly below it, mirroring the 1v1
                         element order (boards, strip, divider). */
                      <div className="players-row-split-divider">
                        <PhaseStrip gameState={state} readOnly={readOnly} />
                        <div
                          className={`resize-handle resize-handle-h${splitDragRef.current ? ' dragging' : ''}`}
                          onMouseDown={startSplitDrag}
                          data-explain="battlefield-split-handle"
                        />
                      </div>
                    ) : (
                      <PhaseStrip gameState={state} readOnly={readOnly} />
                    )
                  )}
                </Fragment>
              ))}
            </div>
            {/* Combat arrows SVG overlay */}
            <CombatArrows combatData={state.combat} />
          </div>
        )}
      </div>

      {/* Resize handle: center ↔ right rail */}
      <div
        className={`resize-handle${dragRef.current === 'right' ? ' dragging' : ''}`}
        onMouseDown={startDrag('right')}
      />

      {/* Right rail: game log, chat, LLM debug (+ the action prompt in a live game) */}
      <div className="game-right">
        {rightRail}
      </div>
    </div>
  )
}
