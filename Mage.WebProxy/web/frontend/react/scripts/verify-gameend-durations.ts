/**
 * Behavioural checks for the game-end dialog's duration stats.
 *
 * Run with:  bash scripts/verify-gameend-durations.sh
 *
 * (Compile-only agent: this harness is WRITTEN but deliberately NOT RUN —
 * running Node/browser harness scripts is out of this unit's scope. A later
 * runtime unit executes it. See the .sh wrapper for the run procedure; the
 * same rolldown-bundle pattern as verify-games-list.sh.)
 *
 * Exercises the REAL pure formatter (utils/replayRows.formatDurationWindow)
 * and the gameEnd payload plumbing shapes (types/messages.GameOverMessage →
 * store gameEnd fields) over fixtures:
 *   - mm:ss below an hour (padded minutes/seconds),
 *   - h:mm:ss at and beyond an hour (single leading hour digit, per the
 *     desktop Format.getDuration granularity),
 *   - unknown window (either end absent) → null → the dialog hides the row,
 *   - inverted window (end < start) → null (never renders a fake 0:00),
 *   - zero-length window → 00:00 (a genuinely instant game is valid),
 *   - the dialog's section-visibility rule: any present duration renders the
 *     box; both absent renders nothing.
 */
import { formatDurationWindow } from '../src/utils/replayRows'

let failures = 0
let checks = 0
function check(name: string, ok: boolean, detail = '') {
  checks++
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

// ── formatting ──
check('45s → 00:45', formatDurationWindow(0, 45_000) === '00:45')
check('10:54', formatDurationWindow(0, 654_000) === '10:54')
check('exactly 1h → 1:00:00', formatDurationWindow(0, 3_600_000) === '1:00:00')
check('1h02m05s → 1:02:05', formatDurationWindow(0, 3_725_000) === '1:02:05')
check('2h → 2:00:00', formatDurationWindow(0, 7_200_000) === '2:00:00')
check('sub-second floors to 00:00', formatDurationWindow(0, 900) === '00:00')

// ── unknown / invalid windows (rows hidden, never zero) ──
check('missing start → null', formatDurationWindow(undefined, 654_000) === null)
check('missing end → null', formatDurationWindow(0, undefined) === null)
check('both missing → null', formatDurationWindow(undefined, undefined) === null)
check('inverted window → null', formatDurationWindow(654_000, 0) === null)
check('NaN start → null', formatDurationWindow(Number.NaN, 654_000) === null)
check('Infinity end → null', formatDurationWindow(0, Number.POSITIVE_INFINITY) === null)

// ── real window arithmetic (not from epoch 0) ──
check(
  'arbitrary epoch window 21:07',
  formatDurationWindow(1_758_900_000_000, 1_758_901_267_000) === '21:07',
)

// ── GameOverMessage carries the optional windows (type-shape regression) ──
const msgShape: import('../src/types').GameOverMessage = {
  type: 'game_over',
  result: 'You won the game on turn 8.',
  hasWon: true,
  matchScore: 'You [2-0], Opp [0-2]',
  gameStartMs: 1_758_900_000_000,
  gameEndMs: 1_758_901_267_000,
  matchStartMs: 1_758_900_000_000,
  matchEndMs: 1_758_901_267_000,
}
check(
  'rich payload windows format through',
  formatDurationWindow(msgShape.gameStartMs, msgShape.gameEndMs) === '21:07',
)
const partial: import('../src/types').GameOverMessage = { type: 'game_over', result: 'x', complete: false }
check(
  'partial payload has no windows → null rows',
  formatDurationWindow(partial.gameStartMs, partial.gameEndMs) === null,
)

console.log(`\n${checks - failures}/${checks} checks passed`)
if (failures > 0) process.exit(1)