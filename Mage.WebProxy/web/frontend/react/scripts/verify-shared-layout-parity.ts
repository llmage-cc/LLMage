/**
 * Behavioural + structural check: the replay viewer and the live board share ONE
 * layout shell.
 *
 * Run with:  bash scripts/verify-shared-layout-parity.sh
 *
 * WHY: `ReplayView.tsx` used to carry its own frozen copy of GameView's layout
 * chrome (3-column grid, rail drag, 1v1 battlefield split, phase strip, revealed
 * buttons). 14 commits touched GameView between 2026-09-16 and 2026-09-24 and
 * only 2 also touched ReplayView, so the replay silently lost rail drag, the
 * battlefield split, auto-fit sizing and the centered phase strip — and the
 * `--ui-scale` multiplier, rendering its rails 26 % narrower than a live game.
 * NOTHING tested parity, which is why five features diverged unnoticed.
 *
 * This harness asserts the structural fix (one shared shell, both views mount it,
 * neither re-implements the chrome) AND the two behaviour contracts that were
 * broken — no file-location claims that a future refactor would break silently,
 * except where the claim IS the point.
 *
 * Structural claims are deliberately written against the SHIPPED files, because
 * the failure mode being guarded is "a future chrome change lands in one view
 * only" — a pure-math test cannot see that.
 *
 * Not part of the app build (scripts/ is outside tsconfig "include": ["src"]).
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

declare const process: { env: Record<string, string | undefined>; exit(code: number): void }
const HERE = process.env.XMAGE_VERIFY_DIR ?? dirname(fileURLToPath(import.meta.url))
const REACT = join(HERE, '..')

const failures: string[] = []
const pass = (label: string) => console.log(`  PASS  ${label}`)
const fail = (label: string, detail: string) => {
  failures.push(label)
  console.log(`  FAIL  ${label}\n        ${detail}`)
}
const check = (ok: boolean, label: string, detail = '') => ok ? pass(label) : fail(label, detail)

const read = (rel: string) => readFileSync(join(REACT, rel), 'utf8')
const gameLayout = read('src/components/GameLayout.tsx')
const gameView = read('src/components/GameView.tsx')
const replayView = read('src/components/ReplayView.tsx')
const app = read('src/App.tsx')

// ── 1. both views mount the shared shell ─────────────────────────────────────
console.log('\n## both viewers mount the SAME layout shell')
check(/<GameLayout\b/.test(gameView), 'GameView renders <GameLayout>')
check(/<GameLayout\b/.test(replayView), 'ReplayView renders <GameLayout>')
check(/from '\.\/GameLayout'/.test(gameView) && /from '\.\/GameLayout'/.test(replayView),
  'both import GameLayout from the same module')

// ── 2. the chrome lives in the shell and ONLY there ──────────────────────────
console.log('\n## the chrome is defined once (neither viewer re-implements it)')
for (const [needle, what] of [
  ['className="battlefield-split"', 'the 1v1 battlefield split container'],
  ['onMouseDown={startDrag', 'the rail drag wiring'],
  ['onMouseDown={startSplitDrag', 'the battlefield split drag wiring'],
  ['resize-handle', 'the resize handles'],
  // The strip's own markup/class lives in PhaseStrip.tsx; what the shell owns is
  // the PLACEMENT (between the two boards, and in the multiplayer grid row).
  ['<PhaseStrip', 'the phase strip placement'],
] as const) {
  check(gameLayout.includes(needle), `GameLayout owns ${what}`)
}
// The old forks: these must NOT reappear in either viewer. `resize-handle` is the
// strongest single signal — it is what rendered-but-was-inert in the replay.
for (const [file, src] of [['GameView.tsx', gameView], ['ReplayView.tsx', replayView]] as const) {
  check(!src.includes('resize-handle'), `${file} does not re-implement a resize handle`)
  check(!src.includes('battlefield-split'), `${file} does not re-implement the split`)
  check(!/--left-col-w/.test(src), `${file} does not hardcode rail widths`)
}
// The hardcoded widths were the 26 %-narrow bug: the shell must derive them from
// the base width × --ui-scale.
check(/leftWidth = leftWidthBase \* scale/.test(gameLayout),
  'GameLayout multiplies the persisted rail width by --ui-scale (the -26 % drift fix)')

// ── 3. read-only boundary: the shell neutralizes game actions, not layout ────
console.log('\n## readOnly neutralizes game actions but keeps the layout interactive')
check(/readOnly\?: boolean/.test(gameLayout), 'GameLayout accepts readOnly')
check(/<StackZone stack=\{state\.stack\} readOnly=\{readOnly\}/.test(gameLayout),
  'the trigger-order menu is gated by readOnly (a game action)')
check(/<PhaseStrip gameState=\{state\} readOnly=\{readOnly\}/.test(gameLayout),
  'the phase chips are gated by readOnly (stop settings are a game action)')
check(/onMouseDown=\{startDrag\('left'\)\}/.test(gameLayout) && !/readOnly \?/.test(gameLayout.split('startDrag')[1]?.split('\n')[0] ?? ''),
  'rail drag is NOT gated by readOnly (resizing a replay is a viewer preference)')
check(/\breadOnly\b/.test(replayView), 'ReplayView passes readOnly')
check(!/\breadOnly\b/.test(gameView), 'GameView does NOT pass readOnly (live game)')

// ── 4. the read-only board renderer is the ONLY per-view difference ──────────
console.log('\n## the per-view difference is exactly the board renderer')
check(/renderBoard=\{renderBoard\}/.test(gameView) && /renderBoard=\{renderBoard\}/.test(replayView),
  'both views pass a renderBoard callback')
// The replay must wire no game-action props; the live board must wire them.
for (const prop of ['onCardClick', 'onManaClick', 'playableCardIds', 'targetIds', 'manaTappableIds'] as const) {
  check(replayView.includes(prop) === false || !new RegExp(`${prop}=`).test(replayView),
    `ReplayView passes no ${prop} (read-only)`)
}
check(/onCardClick=\{handleCardClick\}/.test(gameView), 'GameView wires onCardClick')

// ── 5. auto-fit reads the VISIBLE board, not the raw live game ───────────────
console.log('\n## auto-fit follows the visible board (the legacy-sizing regression)')
check(/selectBoardState\(state\)/.test(app),
  'App.tsx auto-fit resolves the board via selectBoardState (replay frames included)')
check(!/state\.autoFitBattlefield && state\.gameState/.test(app),
  'auto-fit is NOT gated on raw gameState (that made every replay take the legacy path)')
check(/players: board\.players/.test(app), 'auto-fit fits the resolved board\'s players')

// ── 6. the popup that derives live zone contents reads the visible board ─────
console.log('\n## zone popup derives contents from the visible board')
const zonePopup = read('src/components/ZonePopup.tsx')
check(/selectBoardState/.test(zonePopup),
  'ZonePopup uses selectBoardState (an open graveyard updates while stepping a replay)')

// ── 7. no view-owned copies of the shared event contracts ────────────────────
console.log('\n## shared layout event contracts are exported from the shell')
check(/export const PANEL_W_EVENT/.test(gameLayout), 'PANEL_W_EVENT is exported by GameLayout')
check(!/export const PANEL_W_EVENT/.test(gameView), 'GameView does not re-declare PANEL_W_EVENT')
check(/from '@\/components\/GameLayout'/.test(app), 'App.tsx imports PANEL_W_EVENT from GameLayout')

if (failures.length > 0) {
  console.log(`\n${failures.length} FAILURE(S)`)
  process.exit(1)
}
console.log('\nALL CHECKS PASSED')
process.exit(0)
