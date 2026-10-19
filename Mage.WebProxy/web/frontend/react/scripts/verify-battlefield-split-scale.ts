/**
 * Behavioural check: battlefield split — INDEPENDENT per-half card scaling.
 *
 * Run with:  bash scripts/verify-battlefield-split-scale.sh
 *
 * "When both battlefields should scale separately. As one gets
 * bigger, the other should get smaller." Before the fix the auto-fit bound its
 * fit budget to the SMALLER pane and wrote ONE shared --card-width, so a drag
 * on the horizontal divider rescaled every card in BOTH battlefields together.
 * The fix: cardFit.splitPaneWidths computes each pane's fit width from its OWN
 * pane height, and App.tsx (applyPaneCardSizing) writes them as
 * --pane-card-opponent-* / --pane-card-me-* which the split panes re-scope the
 * global --card-* variables from (styles/layout.css [data-split-pane=...] rules).
 *
 * The SAME machinery now
 * serves the 4-player ROW split — the percentage trades the two GRID ROWS of the
 * 2-column multiplayer grid (top row = the first two boards of the views' sort,
 * bottom row the rest), the phase strip's `auto` row stays between them and
 * hosts the drag divider, and each row re-scopes --card-* from its own pane
 * variables (layout.css #players-grid.players-row-split rules). Sections 6-7
 * assert the 4p math AND the negative controls: 1v1 frozen byte-for-byte, and
 * every count other than 2/4 carries NO split state (3 players keep the fixed
 * grid — a persisted 1v1 percentage must not size them).
 *
 * Asserts the REAL pure fit math under Node with browser stubs, plus structural
 * claims about the wiring (App/GameLayout/layout.css read from disk). Not part of
 * the app build (scripts/ is outside tsconfig "include": ["src"]).
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import {
  splitPaneWidths,
  paneCardSizing,
  applyPaneCardSizing,
  computeAutoFitCardWidth,
  legacyResponsiveSizing,
  battlefieldSplitApplies,
  battlefieldRowSplitApplies,
  type AutoFitArgs,
} from '../src/utils/cardFit'
import type { Player, Permanent } from '../src/types'

declare const process: { env: Record<string, string | undefined>; exit(code: number): void }
const HERE = process.env.XMAGE_VERIFY_DIR ?? dirname(fileURLToPath(import.meta.url))
const REACT = join(HERE, '..')
const FRONTEND = join(REACT, '..')

const failures: string[] = []
const pass = (label: string) => console.log(`  PASS  ${label}`)
const fail = (label: string, detail: string) => {
  failures.push(label)
  console.log(`  FAIL  ${label}\n        ${detail}`)
}
const check = (ok: boolean, label: string, detail = '') => ok ? pass(label) : fail(label, detail)

// ── fixtures ──
const perm = (id: string, name = 'Grizzly Bears'): Permanent =>
  ({ id, name, tapped: false, damage: 0 }) as unknown as Permanent
const player = (name: string, isMe: boolean, bfCount: number, handCount = 7): Player =>
  ({
    name, playerId: name, life: 20, isMe, libraryCount: 40, handCount,
    isActive: false, hasPriority: false, hasLeft: false, timerActive: false,
    priorityTimeLeftSecs: 0, isMonarch: false, hasInitiative: false, manaPool: null,
    counters: [], commanders: [],
    battlefield: Array.from({ length: bfCount }, (_, i) => perm(`${name}-${i}`)),
    hand: Array.from({ length: handCount }, (_, i) => perm(`${name}-h${i}`)),
    graveyard: [], exile: [],
    passedTurn: false, passedUntilEndOfTurn: false, passedUntilNextMain: false,
    passedAllTurns: false, passedUntilEndStepBeforeMyTurn: false,
  }) as unknown as Player

const args = (opponentSplitPct: number | undefined, cardScale = 1): AutoFitArgs => ({
  viewportW: 1280, viewportH: 800, uiScale: 1, fontScale: 1,
  leftColW: 260, rightColW: 280, cardScale,
  players: [player('QA-OPP', false, 4, 3), player('QA-ME', true, 5, 7)],
  opponentSplitPct,
})

// 4-player fixtures. Sort order as the views produce it: opponents
// first (stable), `me` last — GameLayout renders the grid in this order, so the
// FIRST TWO boards are the top grid row and the rest the bottom row.
//   fourArgs:      heavy board (~5 bf / 7 hand) is ME, i.e. the BOTTOM row.
//   fourTopHeavy:  the heavy board is a TOP-row seat (the mirrored fixture —
//                  row membership must follow position, not the isMe flag).
const fourPlayers = (heavyIndex: number): Player[] =>
  [0, 1, 2, 3].map((i) =>
    player(
      i === 3 ? 'QA-ME' : `QA-OPP${i + 1}`,
      i === 3,
      i === heavyIndex ? 5 : 4,
      i === heavyIndex ? 7 : 3,
    ),
  )
const fourArgs = (opponentSplitPct: number | undefined, cardScale = 1): AutoFitArgs => ({
  ...args(opponentSplitPct, cardScale),
  players: fourPlayers(3), // heavy in the BOTTOM row
})
const fourTopHeavy = (opponentSplitPct: number | undefined): AutoFitArgs => ({
  ...args(opponentSplitPct),
  players: fourPlayers(0), // heavy in the TOP row
})
/** n players (n-1 opponents then me) with identical light boards. */
const nPlayers = (n: number, opponentSplitPct: number | undefined): AutoFitArgs => ({
  ...args(opponentSplitPct),
  players: Array.from({ length: n }, (_, i) => player(`P${i}`, i === n - 1, 4, 3)),
})

// ── 1. the widths are computed independently and move against each other ──
console.log('## per-pane fit widths: one grows as the other shrinks')
const even = splitPaneWidths(args(50))
const oppBig = splitPaneWidths(args(70))
const meBig = splitPaneWidths(args(30))
check(even != null && oppBig != null && meBig != null, 'per-pane widths exist for a live 1v1 split')
if (even && oppBig && meBig) {
  check(oppBig.opponent > even.opponent, `split 70 grows the OPPONENT card (${even.opponent} → ${oppBig.opponent})`)
  check(oppBig.me < even.me, `split 70 shrinks the PLAYER card (${even.me} → ${oppBig.me})`)
  check(meBig.me > even.me, `split 30 grows the PLAYER card (${even.me} → ${meBig.me})`)
  check(meBig.opponent < even.opponent, `split 30 shrinks the OPPONENT card (${even.opponent} → ${meBig.opponent})`)
  // Inverse coupling: the panes trade space, so the bigger pane always fits bigger cards.
  check(
    oppBig.opponent > oppBig.me && meBig.me > meBig.opponent,
    'the BIGGER pane always fits the BIGGER cards (scales move against each other)',
    `70: opp=${oppBig.opponent} me=${oppBig.me}; 30: opp=${meBig.opponent} me=${meBig.me}`,
  )
  // Monotonicity across the whole drag range: more % ⇒ opponent width never
  // shrinks and my width never grows.
  let monotonic = true
  let prev = splitPaneWidths(args(26))!
  for (let pct = 27; pct <= 74; pct++) {
    const widths = splitPaneWidths(args(pct))!
    if (widths.opponent < prev.opponent || widths.me > prev.me) { monotonic = false; break }
    prev = widths
  }
  check(monotonic, 'dragging the divider moves each pane width MONOTONICALLY against the other')
}

// ── 2. pane sizing derived from those widths ──
console.log('## paneCardSizing derives per-pane CardSizing')
const sizing70 = paneCardSizing(args(70))
check(sizing70 != null, 'paneCardSizing returns per-pane sizing for a live split')
if (sizing70) {
  check(sizing70.opponent.cardWidth > sizing70.me.cardWidth, 'at split 70 the opponent pane is sized BIGGER')
  check(sizing70.opponent.cardHeight === Math.round(sizing70.opponent.cardWidth * 1.4), 'per-pane height follows the 1.4 aspect')
  check(sizing70.me.overlap === sizing70.me.cardHeight + 18, 'per-pane overlap keeps the +18px slack')
}
const capped = paneCardSizing(args(30, 0.5)) // cardScale 0.5 → 100px cap
check(capped != null && capped.opponent.cardWidth <= Math.round(200 * 0.5) && capped.me.cardWidth <= Math.round(200 * 0.5),
  'cardScale caps each pane (the preference is a cap, not an override)')
check(paneCardSizing(args(undefined)) == null, 'no live split → no per-pane sizing (falls back to the shared scale)')
const not1v1 = paneCardSizing({ ...args(70), players: [player('A', true, 1), player('B', false, 1), player('C', false, 1)] })
check(not1v1 == null, 'multiplayer (3 players) → no per-pane sizing')

// ── 3. the whole-board/root sizes stay conservative (they bind to the smaller pane) ──
console.log('## shared/root sizing stays conservative alongside the per-pane sizes')
const whole70 = computeAutoFitCardWidth(args(70))
const whole30 = computeAutoFitCardWidth(args(30))
const wholeEven = computeAutoFitCardWidth(args(50))
check(whole70 === 40 && whole30 === 40,
  'an UNEQUAL drag pushes the whole-board/root size to the 40 floor (it can never grow with the drag)',
  `whole(70)=${whole70}, whole(30)=${whole30}`)
check(wholeEven >= Math.max(whole70, whole30),
  'the root card size binds to the SMALLER pane (largest at the 50/50 split)',
  `whole(50)=${wholeEven}`)
const legacy = legacyResponsiveSizing(1280, 800, 1)
check(legacy.cardWidth >= 40 && legacy.cardWidth <= 120, 'legacy (auto-fit off) formula unchanged')

// ── 4. applyPaneCardSizing writes / clears the :root overrides ──
// The Node stub's documentElement.style is a plain object — the harness swaps
// setProperty/removeProperty for recording fakes around each call.
const doc = (globalThis as unknown as { document?: { documentElement?: { style?: Record<string, unknown> } } }).document
const realSet = doc?.documentElement?.style?.setProperty as ((k: string, v: string) => void) | undefined
const realRemove = doc?.documentElement?.style?.removeProperty as ((k: string) => void) | undefined
const written = new Map<string, string>()
const removed = new Set<string>()
if (doc?.documentElement?.style) {
  doc.documentElement.style.setProperty = (k: string, v: string) => { written.set(k, v); removed.delete(k) }
  doc.documentElement.style.removeProperty = (k: string) => { removed.add(k); written.delete(k) }
}
applyPaneCardSizing(sizing70)
check(written.get('--pane-card-opponent-width') === `${sizing70?.opponent.cardWidth}px`, 'writes --pane-card-opponent-width')
check(written.get('--pane-card-me-width') === `${sizing70?.me.cardWidth}px`, 'writes --pane-card-me-width (MISSING = the old shared-scale bug)')
check((sizing70?.opponent.cardWidth ?? 0) !== (sizing70?.me.cardWidth ?? 1), 'the two panes are written DIFFERENT values (independent scaling)')
applyPaneCardSizing(null)
check(removed.has('--pane-card-opponent-width') && removed.has('--pane-card-me-width'), 'null clears the overrides (fallback to shared sizing)')
if (realSet && doc?.documentElement?.style) doc.documentElement.style.setProperty = realSet
if (realRemove && doc?.documentElement?.style) doc.documentElement.style.removeProperty = realRemove

// ── 5. wiring on disk: the panes really consume the per-pane variables ──
// The split panes + rail chrome live in the SHARED GameLayout (both the live
// board and the replay viewer mount it) — see GameLayout's header.
console.log('## structural wiring (shipped files on disk)')
const gameLayout = readFileSync(join(REACT, 'src/components/GameLayout.tsx'), 'utf8')
const layoutCss = readFileSync(join(FRONTEND, 'styles/layout.css'), 'utf8')
const app = readFileSync(join(REACT, 'src/App.tsx'), 'utf8')
check(/applyPaneCardSizing/.test(app), 'App.tsx calls applyPaneCardSizing on every auto-fit recompute')
check(/pane-card-opponent-width/.test(gameLayout) && /pane-card-me-width/.test(gameLayout),
  'GameLayout maps the opponent/me pane variables onto the split panes')
check(layoutCss.includes('--card-width: var(--pane-width)') && layoutCss.includes('--card-width: var(--pane2-width)'),
  'layout.css re-scopes --card-width per pane ([data-split-pane=opponent]/[data-split-pane=me])')
check(!/--card-width:\s*var\(--pane-card/.test(gameLayout),
  'panes never re-scope --card-width inline with a self-referential var (guaranteed-invalid cycle)')

// ── 6. 4-player ROW split ──
// The split percentage trades the two GRID ROWS of the 2-column multiplayer
// grid: top row = the first two boards of the views' sort (GameLayout renders
// `players` in GameView/ReplayView's opponents-first/me-last order), bottom row
// the rest. Same trade identity as the 1v1 panes; the frozen 1v1 pins are in
// section 7, so a regression in either geometry is caught.
console.log('## 4-player row split: the two grid rows trade height')
const s4_50 = splitPaneWidths(fourArgs(50))
const s4_70 = splitPaneWidths(fourArgs(70))
const s4_25 = splitPaneWidths(fourArgs(25))
check(s4_50 != null && s4_70 != null && s4_25 != null, '4p split widths exist for a live row split')
if (s4_50 && s4_70 && s4_25) {
  // Pinned values (frozen probe run against this code, 1280×800 fixture):
  // engaging the split must reproduce the no-split sizing exactly at 50/50.
  check(s4_50.opponent === 64 && s4_50.me === 64, `4p split 50: both rows at the no-split size (${s4_50.opponent}/${s4_50.me})`)
  check(s4_70.opponent > s4_50.opponent, `split 70 grows the TOP row cards (${s4_50.opponent} → ${s4_70.opponent})`)
  check(s4_70.me < s4_50.me, `split 70 shrinks the BOTTOM row cards (${s4_50.me} → ${s4_70.me})`)
  check(s4_25.me > s4_50.me, `split 25 grows the BOTTOM row cards (${s4_50.me} → ${s4_25.me})`)
  check(s4_25.opponent < s4_50.opponent, `split 25 shrinks the TOP row cards (${s4_50.opponent} → ${s4_25.opponent})`)
  // Monotonicity across the whole drag range (same invariant the 1v1 pane math holds).
  let monotonic4 = true
  let prev4 = splitPaneWidths(fourArgs(26))!
  for (let pct = 27; pct <= 74; pct++) {
    const widths = splitPaneWidths(fourArgs(pct))!
    if (widths.opponent < prev4.opponent || widths.me > prev4.me) { monotonic4 = false; break }
    prev4 = widths
  }
  check(monotonic4, 'dragging the row divider moves each row MONOTONICALLY against the other')
  // Clamping 25–75: everything below 25 / above 75 pins to the bound.
  check(JSON.stringify(splitPaneWidths(fourArgs(10))) === JSON.stringify(s4_25), '4p split clamps below 25 (10 ≡ 25)')
  check(JSON.stringify(splitPaneWidths(fourArgs(90))) === JSON.stringify(splitPaneWidths(fourArgs(75))),
    '4p split clamps above 75 (90 ≡ 75)')
  // Row membership follows POSITION (grid order), not who `isMe`: with the
  // heavy board in the TOP row the roles must swap (opponent size = top row).
  const heavyTop30 = splitPaneWidths(fourTopHeavy(30))
  const heavyTop70 = splitPaneWidths(fourTopHeavy(70))
  check(heavyTop70 != null && heavyTop70.opponent > heavyTop70.me && heavyTop30 != null && heavyTop30.me > heavyTop30.opponent,
    'the row with the bigger share always fits the bigger cards (top row is the opponent size, bottom the me size)',
    `30: opp=${heavyTop30?.opponent} me=${heavyTop30?.me}; 70: opp=${heavyTop70?.opponent} me=${heavyTop70?.me}`)
}

console.log('## 4p: whole-board/root sizing + paneCardSizing')
const whole4 = computeAutoFitCardWidth(fourArgs(undefined))
const whole4Even = computeAutoFitCardWidth(fourArgs(50))
const whole4Dragged = computeAutoFitCardWidth(fourArgs(70))
check(whole4 === 64 && whole4Even === 64,
  'engaging the row split at 50/50 cannot itself resize cards (row budgets sum to the fixed half)',
  `no-split=${whole4}, 50=${whole4Even}`)
check(whole4Dragged === 40, 'an UNEQUAL row drag pushes the shared/root size to the 40 floor', `whole(70)=${whole4Dragged}`)
const sizing4 = paneCardSizing(fourArgs(70))
check(sizing4 != null, 'paneCardSizing returns per-row sizing for a live 4p row split')
if (sizing4) {
  check(sizing4.opponent.cardWidth > sizing4.me.cardWidth, 'at split 70 the TOP row is sized BIGGER')
  check(sizing4.opponent.cardHeight === Math.round(sizing4.opponent.cardWidth * 1.4), '4p per-row height follows the 1.4 aspect')
  check(sizing4.me.overlap === sizing4.me.cardHeight + 18, '4p per-row overlap keeps the +18px slack')
}
const capped4 = paneCardSizing(fourArgs(30, 0.5))
check(capped4 != null && capped4.opponent.cardWidth <= Math.round(200 * 0.5) && capped4.me.cardWidth <= Math.round(200 * 0.5),
  '4p cardScale caps each row (the preference is a cap, not an override)')
check(paneCardSizing(fourArgs(undefined)) == null, '4p without a split pct → no per-row sizing (falls back to the shared scale)')

// ── 7. negative controls: 1v1 frozen, and NO split state outside 2/4 ──
console.log('## negative controls: 1v1 unchanged, no split state outside 2/4 players')
check(battlefieldSplitApplies(2) && battlefieldSplitApplies(4), 'the split applies to 2 and 4 players')
for (const n of [0, 1, 3, 5, 6] as const) {
  check(!battlefieldSplitApplies(n), `the split does NOT apply with ${n} players`)
}
check(battlefieldRowSplitApplies(4) && !battlefieldRowSplitApplies(3) && !battlefieldRowSplitApplies(5) && !battlefieldRowSplitApplies(2),
  'the ROW split is exactly 4 players (not >= 4: 3 and 5+ keep the fixed grid)')
for (const n of [1, 3, 5, 6] as const) {
  check(splitPaneWidths(nPlayers(n, 50)) == null && paneCardSizing(nPlayers(n, 50)) == null,
    `${n}p: no per-row/per-pane sizing (split must not engage)`)
  const pcts = [25, 50, 70].map((pct) => computeAutoFitCardWidth(nPlayers(n, pct)))
  const unset = computeAutoFitCardWidth(nPlayers(n, undefined))
  check(pcts.every((w) => w === unset),
    `${n}p: whole-grid sizing ignores a persisted percentage (fixed grid — the pre-existing 3p leak)`,
    `pcts=${pcts}, undefined=${unset}`)
}
// 1v1 frozen pins: values captured from the pre-change code (all 23 probe
// values identical pre/post) — the 1v1 branch must stay byte-for-byte.
check(JSON.stringify(splitPaneWidths(args(50))) === JSON.stringify({ opponent: 64, me: 64 }), '1v1 split(50) frozen at {64,64}')
check(JSON.stringify(splitPaneWidths(args(70))) === JSON.stringify({ opponent: 104, me: 40 }), '1v1 split(70) frozen at {104,40}')
check(JSON.stringify(splitPaneWidths(args(25))) === JSON.stringify({ opponent: 40, me: 68 }), '1v1 split(25) frozen at {40,68}')
check(computeAutoFitCardWidth(args(50)) === 64 && computeAutoFitCardWidth(args(70)) === 40 && computeAutoFitCardWidth(args(undefined)) === 64,
  '1v1 whole-grid sizing frozen (64 / 40 / 64)')
const one50 = paneCardSizing(args(50))
const one70 = paneCardSizing(args(70))
check(one50 != null && one50.opponent.cardWidth === 64 && one50.opponent.cardHeight === 90 && one50.opponent.overlap === 108,
  '1v1 paneSizing(50) frozen (64 / 90 / 108)')
check(one70 != null && one70.opponent.cardWidth === 104 && one70.me.cardWidth === 40 && one70.me.overlap === 74,
  '1v1 paneSizing(70) frozen (104 / 40 / 74)')

// ── 8. structural wiring: the 4p row split really renders the divider ──
console.log('## 4p structural wiring (shipped files on disk)')
check(/battlefieldRowSplitApplies/.test(gameLayout),
  'GameLayout gates the row split on the shared predicate (UI and math cannot disagree)')
check(/players-row-split/.test(gameLayout) && /players-row-split/.test(layoutCss),
  'the row-split container class is applied in GameLayout and styled in layout.css')
check(/\$\{splitPercent\}fr/.test(gameLayout) && /\$\{100 - splitPercent\}fr/.test(gameLayout),
  'gridTemplateRows for the row split interpolates the live split percentage')
check(/startSplitDrag/.test(gameLayout) && /data-explain="battlefield-split-handle"/.test(gameLayout)
  && (gameLayout.match(/data-explain="battlefield-split-handle"/g) ?? []).length >= 2,
  'the SAME split drag handler + explain id wires the 1v1 pane divider AND the 4p row divider')
check(/'auto'/.test(gameLayout) && /gridRows/.test(gameLayout) && /Array\(rowsBeforeStrip\)/.test(gameLayout),
  'the strip row stays `auto` between the two fractional board rows')
check(layoutCss.includes('#players-grid.players-row-split > article:nth-of-type(-n+2)')
  && layoutCss.includes('#players-grid.players-row-split > article:nth-of-type(n+3)'),
  'layout.css re-scopes --card-* per row (top two articles vs the rest)')
check(layoutCss.includes('.players-row-split-divider'),
  'layout.css gives the divider/strip row its own rule')

if (failures.length > 0) {
  console.log(`\n${failures.length} FAILURE(S)`)
  process.exit(1)
}
console.log('\nALL CHECKS PASSED')
process.exit(0)
