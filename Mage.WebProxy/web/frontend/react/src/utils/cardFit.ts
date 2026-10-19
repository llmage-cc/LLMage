/**
 * Battlefield auto-fit card sizing (item 4: "Scaling for the battlefield is
 * awful, you have to scroll to see anything").
 *
 * Two sizing modes share this module:
 *
 *  - LEGACY (auto-fit OFF): the historical viewport heuristic — card width from
 *    viewport width, clamped by a height budget for 2 battlefield rows + 1 hand row.
 *    Kept byte-for-byte in behaviour for the toggle-off case.
 *  - AUTO-FIT (auto-fit ON, default): compute the largest card width (≤ the user's
 *    cardScale preference) at which EVERY player's battlefield rows AND hand row fit
 *    the space actually available: live left/right rail widths, uiScale-scaled chrome,
 *    the phase strip, and the REAL board contents (occupied battlefield rows from the
 *    same grouping the renderer uses, hand size). Zero vertical battlefield scrolling
 *    in normal games; the internal battlefield scroll stays as the last resort for
 *    genuinely huge boards.
 *
 * All math is in ROOT CSS px (the zoom-compensated space the layout lives in — see
 * utils/viewport.ts): with html { zoom: 0.75 } both card widths and chrome scale by
 * the zoom, so ratios are zoom-invariant as long as the viewport itself is expressed
 * in CSS px (window.innerWidth / zoom).
 *
 * TWO split geometries share the per-pane machinery:
 *  - 1v1 (exactly 2 players): the split trades the two vertical PANES' heights
 *    (opponent pane on top, own pane below; GameLayout's .battlefield-split).
 * 4 players: the same percentage trades the two GRID
 *    ROWS' heights of the 2-column multiplayer grid (top row = the first two
 *    boards of the views' sort, bottom row = the last two). Column widths stay
 *    equal, so the split can only trade HEIGHT — cards re-fit per row from their
 *    row's height, exactly like the 1v1 panes.
 * The split engages for EXACTLY 2 or 4 players: 3 players (and every other
 * count) keep the fixed grid — no divider, no split state.
 */
import type { Player, Permanent } from '@/types'
import { groupCardsByKey, MAX_VISUAL_STACK_DEPTH } from './cardGrouping'
import { isLikelyLand } from './cardTypes'

/** MTG card aspect (height / width) — same 1.4 convention App.tsx has always used. */
const CARD_ASPECT = 1.4
/** Hand cards render at --card-width * 1.1875 (game-renderer.css .hand-section override). */
const HAND_WIDTH_FACTOR = 1.1875
/** Vertical overlap inside stacked groups: margin-top = -0.875 × card height (98px at the historical 112px height). */
const STACK_OVERLAP_FRACTION = 0.875
/** Gap between cards/groups in battlefield rows (game-renderer.css .cards-grid gap: 6px). */
const BF_GAP = 6
/** Gap between hand cards (.hand-cards-row gap: 4px). */
const HAND_GAP = 4

export interface CardSizing {
  cardWidth: number
  cardHeight: number
  smallWidth: number
  smallHeight: number
  /** Vertical overlap inside stacked groups (--card-overlap). */
  overlap: number
}

/**
 * The historical responsive-sizing formula, kept exactly for auto-fit OFF.
 * Inputs are REAL px (window.innerWidth/innerHeight) — that is what the original
 * code consumed, and the toggle-off path must reproduce today's behaviour.
 */
export function legacyResponsiveSizing(viewportW: number, viewportH: number, cardScale: number): CardSizing {
  const widthBased = Math.floor(viewportW / 18)
  // 2 bf rows + 1 hand row + chrome in half the viewport (1v1).
  const halfVh = viewportH / 2
  const maxCardForHeight = Math.floor((halfVh - 120) / 3.2)
  const baseWidth = Math.max(50, Math.min(120, widthBased, maxCardForHeight))
  const cardWidth = Math.max(40, Math.min(200, Math.round(baseWidth * cardScale)))
  const cardHeight = Math.round(cardWidth * CARD_ASPECT)
  const smallWidth = Math.max(30, Math.floor(cardWidth * 0.7))
  return {
    cardWidth,
    cardHeight,
    smallWidth,
    smallHeight: Math.round(smallWidth * CARD_ASPECT),
    overlap: 98,
  }
}

/** Write a CardSizing into the :root CSS custom properties the stylesheets consume. */
export function applyCardSizing(sizing: CardSizing): void {
  const root = document.documentElement
  root.style.setProperty('--card-width', `${sizing.cardWidth}px`)
  root.style.setProperty('--card-height', `${sizing.cardHeight}px`)
  root.style.setProperty('--card-overlap', `${Math.round(sizing.overlap)}px`)
  root.style.setProperty('--card-width-sm', `${sizing.smallWidth}px`)
  root.style.setProperty('--card-height-sm', `${sizing.smallHeight}px`)
  root.style.setProperty('--card-sm-width', `${sizing.smallWidth}px`)
}

/**
 * Write the PER-PANE overrides for the 1v1 battlefield split (or clear them).
 * Each pane re-scopes the global --card-* variables to its own size, so the
 * two battlefields scale independently against each other: as one gets
 * bigger, the other gets smaller. `null` clears the overrides: the panes fall
 * back to the single shared sizing (multiplayer, no split, auto-fit off).
 */
export function applyPaneCardSizing(
  sizing: { opponent: CardSizing; me: CardSizing } | null,
): void {
  const root = document.documentElement
  if (sizing == null) {
    for (const key of ['opponent', 'me'] as const) {
      root.style.removeProperty(`--pane-card-${key}-width`)
      root.style.removeProperty(`--pane-card-${key}-height`)
      root.style.removeProperty(`--pane-card-${key}-overlap`)
      root.style.removeProperty(`--pane-card-${key}-width-sm`)
      root.style.removeProperty(`--pane-card-${key}-height-sm`)
      root.style.removeProperty(`--pane-card-${key}-sm-width`)
    }
    return
  }
  const put = (sizing: CardSizing, key: 'opponent' | 'me') => {
    root.style.setProperty(`--pane-card-${key}-width`, `${sizing.cardWidth}px`)
    root.style.setProperty(`--pane-card-${key}-height`, `${sizing.cardHeight}px`)
    root.style.setProperty(`--pane-card-${key}-overlap`, `${Math.round(sizing.overlap)}px`)
    root.style.setProperty(`--pane-card-${key}-width-sm`, `${sizing.smallWidth}px`)
    root.style.setProperty(`--pane-card-${key}-height-sm`, `${sizing.smallHeight}px`)
    root.style.setProperty(`--pane-card-${key}-sm-width`, `${sizing.smallWidth}px`)
  }
  put(sizing.opponent, 'opponent')
  put(sizing.me, 'me')
}

/**
 * Reduce a player's battlefield to the column model BattlefieldZone renders, as a
 * list of visual stack depths (in card-heights): lands stack by name, non-lands
 * group by full visual key (groupCardsByKey — the same function the renderer uses),
 * auras/equipment fold into their target's depth instead of taking their own column.
 */
function battlefieldStackDepths(cards: Permanent[]): number[] {
  const attachmentCount = new Map<string, number>()
  for (const c of cards) {
    if (c.attachedTo && c.id) {
      attachmentCount.set(c.attachedTo, (attachmentCount.get(c.attachedTo) ?? 0) + 1)
    }
  }
  const attachedIds = new Set<string>()
  for (const c of cards) {
    if (c.attachedTo && c.id) attachedIds.add(c.id)
  }

  const landCounts = new Map<string, number>()
  const standaloneDepths: number[] = [] // non-land groups + anything carrying attachments
  const nonLandUngrouped: Permanent[] = []
  for (const c of cards) {
    if (c.id && attachedIds.has(c.id)) continue // renders under its target, folded below
    const extra = c.id ? (attachmentCount.get(c.id) ?? 0) : 0
    if (extra > 0) {
      // Attached-to card: its group is unique (renderer flags _hasAttachments) and
      // its column holds the target plus its auras stacked above it.
      standaloneDepths.push(Math.min(MAX_VISUAL_STACK_DEPTH, 1 + extra))
      continue
    }
    if (isLikelyLand(c)) {
      const name = c.name || 'Unknown'
      landCounts.set(name, (landCounts.get(name) ?? 0) + 1)
    } else {
      nonLandUngrouped.push(c)
    }
  }
  for (const group of groupCardsByKey(nonLandUngrouped)) {
    standaloneDepths.push(Math.min(MAX_VISUAL_STACK_DEPTH, group.cards.length))
  }
  for (const count of landCounts.values()) {
    standaloneDepths.push(Math.min(MAX_VISUAL_STACK_DEPTH, count))
  }
  return standaloneDepths
}

/** Greedy wrap: rows of `perRow` items, row height = tallest item in the row. */
function packRowHeights(heights: number[], perRow: number): number {
  let total = 0
  for (let i = 0; i < heights.length; i += perRow) {
    let rowH = 0
    for (let j = i; j < Math.min(i + perRow, heights.length); j++) {
      rowH = Math.max(rowH, heights[j] ?? 0)
    }
    total += rowH
  }
  return total
}

/** Clamp a value between min and max (numerics only — no NaN path here). */
const clamp = (v: number, min: number, max: number) => Math.max(min, Math.min(max, v))

export interface AutoFitArgs {
  /** Viewport in ROOT CSS px (window.innerWidth / rootZoom()). */
  viewportW: number
  /** Viewport in ROOT CSS px (window.innerHeight / rootZoom()). */
  viewportH: number
  uiScale: number
  fontScale: number
  /** Live left rail width in root CSS px (drag state × uiScale — never the CSS default). */
  leftColW: number
  /** Live right rail width in root CSS px. */
  rightColW: number
  /**
   * The user's card-scale preference (a CAP, not an override). Auto-fit only
   * needs it for the per-pane sizing cap; the whole-board path reads it via
   * App.tsx's autoFitSizing. Optional so the fit functions stay pure.
   */
  cardScale?: number | undefined
  players: Player[]
  /**
   * Live battlefield split: the TOP half's percentage of the board area
   * (GameLayout's --split-pct for 1v1 / the split grid-row fraction for 4p,
   * clamped 25–75). When provided, each board's fit budget is its ACTUAL half's
   * height (min of the two, since the smaller half binds): the 1v1 opponent
   * pane / the 4p TOP grid row vs the own pane / BOTTOM row; when omitted both
   * halves are assumed equal (the no-split layout). Split drags re-run the fit
   * math via BATTLEFIELD_SPLIT_EVENT. Explicit `| undefined` — exactOptionalPropertyTypes
   * makes `?: number` reject an explicitly-passed undefined.
   */
  opponentSplitPct: number | undefined
}

/** DOM event App.tsx's auto-fit listens for: the 1v1 battlefield split moved. */
export const BATTLEFIELD_SPLIT_EVENT = 'xmage-battlefield-split-changed'

/**
 * Largest card width (px) at which every player's battlefield and hand fit without
 * vertical scrolling. Returns 40 (the stylesheet floor) when nothing fits or there
 * are no players.
 */
export function computeAutoFitCardWidth(a: AutoFitArgs): number {
  const n = a.players.length
  if (n === 0) return 40
  const cols = n === 2 ? 1 : 2
  const rows = Math.ceil(n / cols)

  // ── Chrome budget (root CSS px, mirroring the stylesheets) ──
  const boardArea = autoFitGeometry(a)
  if (boardArea.boardAreaW < 160 || boardArea.boardAreaH < 200) return 40
  const boardW = boardArea.boardAreaW - 8 // players-grid padding
  // Per-board fit budget: with a live split the two halves are UNEQUAL
  // (opponentSplitPct vs the rest) — the shared/root card sizes bind to the
  // SMALLER half (the conservative size). The split layouts override each
  // half's own size via paneCardSizing()/splitPaneWidths — see CardSizing.
  // The split applies to exactly 2 players (the panes) and 4 (the grid rows);
  // a persisted percentage must NOT size any other count (3 players keep the
  // fixed grid — negative control).
  const splitPct =
    a.opponentSplitPct != null && battlefieldSplitApplies(n) && rows === 2
      ? Math.min(Math.max(a.opponentSplitPct, 25), 75) / 100
      : null
  const boardH = splitPct
    ? (boardArea.boardAreaH - boardArea.stripH - (rows - 1) * 4) * Math.min(splitPct, 1 - splitPct)
    : (boardArea.boardAreaH - boardArea.stripH - (rows - 1) * 4) / rows
  const mainZonesW = Math.max(120, boardW - boardArea.sideZonesW)
  const mainZonesH = boardH - boardArea.playerHeaderH - boardArea.statsH
  const bfAvailW = Math.max(80, mainZonesW - 12) // .battlefield-zone padding

  return fitWidthForBoards(bfAvailW, mainZonesH, a.fontScale, boardsOf(a))
}

/** Chrome/board geometry (root CSS px) shared by the whole-board and per-pane fit maths. */
function autoFitGeometry(a: AutoFitArgs) {
  const rem = 16 * a.fontScale
  const headerH = 2.75 * rem * a.uiScale // .game-header-bar min-height (border-box)
  const stripH = 40 * a.uiScale // phase strip grid row (auto)
  const playerHeaderH = 27 * a.fontScale // .player-header padding + text + border
  const statsH = 32 * a.fontScale // .player-stats-bar (life/mana/badges row)
  const sideZonesW = 95 * a.uiScale + 4 // .side-zones width + flex gap
  const handleW = 6 * a.uiScale // resize-handle columns
  const boardAreaW = a.viewportW - a.leftColW - a.rightColW - 2 * handleW - 8
  const boardAreaH = a.viewportH - headerH - 8
  return { stripH, playerHeaderH, statsH, sideZonesW, boardAreaW, boardAreaH }
}

/** Reduce the boards to what the fit loop consumes (hand size + stack depths). */
function boardsOf(a: AutoFitArgs): { handCount: number; depths: number[] }[] {
  return a.players.map(boardOf)
}

/**
 * The views' player order, replicated for the 4p row split: opponents first
 * (stable), `me` last — exactly GameView/ReplayView's sort, which GameLayout
 * renders the grid from. Array.prototype.sort is stable, so this is a stable
 * partition: non-me seats keep their order, then me seats. The 1v1 pane math
 * has always picked `find(!isMe)` / `find(isMe)` itself and is deliberately
 * NOT routed through this helper — its inputs are single seats.
 */
function layoutSortedPlayers(players: Player[]): Player[] {
  const others: Player[] = []
  const mine: Player[] = []
  for (const p of players) {
    if (p.isMe) mine.push(p)
    else others.push(p)
  }
  return [...others, ...mine]
}

/** Reduce one player's board to what the fit loop consumes (hand size + stack depths). */
function boardOf(p: Player): { handCount: number; depths: number[] } {
  return {
    handCount: Math.max(p.handCount ?? 0, p.hand?.length ?? 0),
    depths: battlefieldStackDepths(p.battlefield ?? []),
  }
}

/** Clamp constant shared with GameLayout's split drag (25–75%). */
export const BATTLEFIELD_SPLIT_MIN = 25
export const BATTLEFIELD_SPLIT_MAX = 75

/**
 * The battlefield split engages for EXACTLY these player counts: 2 (the 1v1
 * panes) and 4 (the row split, option 1 approved 2026-09-28 — two grid rows in
 * the 2-column multiplayer grid). Every other count keeps the fixed grid and
 * must carry NO split state: 3 players (top row 2 / bottom row 1) stay fixed,
 * so a persisted 1v1 percentage must not silently size their boards.
 */
export function battlefieldSplitApplies(playerCount: number): boolean {
  return playerCount === 2 || playerCount === 4
}

/**
 * The 4-player ROW split — the single source for "exactly 4" across the UI
 * gate (GameLayout's hasRowSplit) and the math (splitPaneWidths). NOT
 * `>= 4`: 3 players keep the fixed grid, 5/6+ are out of the approved
 * option's scope (their whole-grid sizing is untouched by any split).
 */
export function battlefieldRowSplitApplies(playerCount: number): boolean {
  return playerCount === 4
}

/**
 * INDEPENDENT per-half card sizing for the battlefield split ("when both
 * battlefields should scale separately. As one gets bigger, the other should
 * get smaller.").
 *
 * 1v1 (exactly 2 players): each PANE's fit budget is its OWN pane height (its
 * % of the board area, minus the phase strip once and its own chrome), so
 * growing the opponent pane grows the OPPONENT card width while the player
 * pane shrinks along with ITS cards — the two scales move against each other
 * instead of together.
 *
 * 4 players (row split, option 1 approved 2026-09-28): the same percentage
 * trades the two GRID ROWS of the 2-column multiplayer grid. The top row (the
 * first two boards of the views' sort — GameLayout renders `players` in
 * GameView/ReplayView's order) takes the "opponent" size, the bottom row the
 * "me" size; every board in a row shares the row's size (GameLayout re-scopes
 * the whole row from the pane variables). Row budgets trade the board area
 * minus the strip once, exactly like the 1v1 panes.
 *
 * Returns null when the split does not apply (no live split pct, or a player
 * count with no split — anything other than exactly 2 or 4; 3 players keep
 * the fixed grid, so a leftover persisted 1v1 percentage must not size them)
 * — callers then keep the single shared sizing.
 */
export function splitPaneWidths(a: AutoFitArgs): { opponent: number; me: number } | null {
  const n = a.players.length
  if (!battlefieldSplitApplies(n)) return null
  if (a.opponentSplitPct == null) return null
  const boardArea = autoFitGeometry(a)
  if (boardArea.boardAreaW < 160 || boardArea.boardAreaH < 200) return null
  const split = clamp(a.opponentSplitPct, BATTLEFIELD_SPLIT_MIN, BATTLEFIELD_SPLIT_MAX) / 100
  const boardW = boardArea.boardAreaW - 8 // players-grid padding
  // Width budget: the same full-board-column budget the multiplayer whole-grid
  // fit has always used (computeAutoFitCardWidth). The 4p row split trades
  // HEIGHT — the axis its divider actually controls — and intentionally keeps
  // that width convention, so at an even split both rows get the size the
  // no-split grid gives its boards today and a drag only trades rows apart.
  const mainZonesW = Math.max(120, boardW - boardArea.sideZonesW)
  const bfAvailW = Math.max(80, mainZonesW - 12) // .battlefield-zone padding

  if (battlefieldRowSplitApplies(n)) {
    // Row split: the strip's auto grid row sits BETWEEN the two board rows, so
    // the row budgets share the board area minus the strip once (the same
    // `- (rows - 1) * 4` chrome allowance computeAutoFitCardWidth's whole-grid
    // branch uses for the grid gaps) — at split 50 the two rows' budgets equal
    // the no-split half exactly, so engaging the split cannot itself resize
    // cards. Top/bottom membership mirrors the views' sort (opponents first,
    // `me` last) because the grid places boards in that order.
    const rows = layoutSortedPlayers(a.players)
    const sharedBoardH = boardArea.boardAreaH - boardArea.stripH - 4
    const opponentRow = fitWidthForBoards(
      bfAvailW,
      sharedBoardH * split - boardArea.playerHeaderH - boardArea.statsH,
      a.fontScale,
      rows.slice(0, 2).map(boardOf),
    )
    const meRow = fitWidthForBoards(
      bfAvailW,
      sharedBoardH * (1 - split) - boardArea.playerHeaderH - boardArea.statsH,
      a.fontScale,
      rows.slice(2).map(boardOf),
    )
    return { opponent: opponentRow, me: meRow }
  }

  // 1v1: board-area height MINUS the phase strip ONCE (the strip is a single
  // row between the two panes, not one per pane): the sum of the two budgets
  // is the full board area — the panes really trade space against each other.
  const sharedBoardH = boardArea.boardAreaH - boardArea.stripH - 4
  const opponent = fitWidthForBoards(
    bfAvailW,
    sharedBoardH * split - boardArea.playerHeaderH - boardArea.statsH,
    a.fontScale,
    [boardOf(a.players.find((p) => !p.isMe) ?? a.players[0]!)],
  )
  const me = fitWidthForBoards(
    bfAvailW,
    sharedBoardH * (1 - split) - boardArea.playerHeaderH - boardArea.statsH,
    a.fontScale,
    [boardOf(a.players.find((p) => p.isMe) ?? a.players[1]!)],
  )
  return { opponent, me }
}

/**
 * Derive the PER-PANE CSS overrides for a split layout: each pane gets its own
 * card width/height/small sizes/overlap, named --pane-card-* so the pane can
 * re-scope the global --card-* variables. Sums stay inside the pane: the width
 * is the pane's own fit width (≤ the board-wide cap), never wider than the
 * battlefield row.
 */
export function paneCardSizing(a: AutoFitArgs): { opponent: CardSizing; me: CardSizing } | null {
  const widths = splitPaneWidths(a)
  if (widths == null) return null
  const cap = 200 * (a.cardScale ?? 1)
  const paneSizing = (w: number): CardSizing => {
    const cardWidth = Math.max(40, Math.min(200, Math.round(Math.min(w, cap))))
    const cardHeight = Math.round(cardWidth * CARD_ASPECT)
    const smallWidth = Math.max(30, Math.floor(cardWidth * 0.7))
    return {
      cardWidth,
      cardHeight,
      smallWidth,
      smallHeight: Math.round(smallWidth * CARD_ASPECT),
      overlap: cardHeight + 18,
    }
  }
  return { opponent: paneSizing(widths.opponent), me: paneSizing(widths.me) }
}

/** Largest card width at which every board in `boards` fits `bfAvailW × mainZonesH`.
 *  `fontScale` scales the hand/battlefield chrome exactly as computeAutoFitCardWidth does. */
function fitWidthForBoards(
  bfAvailW: number,
  mainZonesH: number,
  fontScale: number,
  boards: { handCount: number; depths: number[] }[],
): number {
  const handChrome = 30 * fontScale // hand title + section padding/border/scrollbar
  const bfChrome = 30 * fontScale // battlefield title + zone padding + land separator
  for (let w = 200; w >= 40; w -= 2) {
    const h = w * CARD_ASPECT
    const handH = h * HAND_WIDTH_FACTOR
    const bfAvailH = mainZonesH - handChrome - handH - bfChrome
    if (bfAvailH < h * 0.5) continue // battlefield would be thinner than half a card — hopeless
    const perRow = Math.max(1, Math.floor((bfAvailW + BF_GAP) / (w + BF_GAP)))
    const stackHeight = (depth: number) => h * (1 + (depth - 1) * (1 - STACK_OVERLAP_FRACTION))
    let fits = true
    for (const b of boards) {
      if (
        b.handCount > 0 &&
        b.handCount * HAND_WIDTH_FACTOR * w + (b.handCount - 1) * HAND_GAP > bfAvailW
      ) {
        fits = false
        break
      }
      if (packRowHeights(b.depths.map(stackHeight), perRow) > bfAvailH) {
        fits = false
        break
      }
    }
    if (fits) return w
  }
  return 40
}
