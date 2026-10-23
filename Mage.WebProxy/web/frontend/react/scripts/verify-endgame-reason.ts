/**
 * Runtime check for the authoritative end-of-game reason.
 *
 * Run with:  node scripts/verify-endgame-reason.ts
 * (Node >= 22.6 type-strips this file; Node 26 runs it as-is. No bundle, no
 * browser, no server — the module under test is pure and imports nothing.)
 *
 * The proxy now ships `endReason` on the rich `game_over` frame for the
 * viewer's OWN seat (mage.game.GameEndReason: "llm_failure" | "concede" |
 * "timeout" | "idle" | "quit" | "unknown"; null for a watcher, absent for an
 * older server). utils/endGame.ts::endDialogKind must:
 *   - never turn a LOSS into a victory of any flavour;
 *   - call `llm_failure` a technical victory at ANY seat count (the old log
 *     heuristic declined above 2 seats and mislabelled those games);
 *   - treat every OTHER present reason as a played-out win, even with a stale
 *     terminal-failure line still sitting in the log rail;
 *   - keep the 2-seat fatal-log-line inference ONLY when the field is absent
 *     (stock/older server, watcher), and still decline above 2 seats there.
 *
 * The last leg is the regression guard for the bug this replaces: a stale fatal
 * line from a previous game must not outvote a `concede` from the wire.
 */
import {
  END_DIALOG_TECHNICAL_NOTE,
  END_DIALOG_TITLES,
  endDialogKind,
} from '../src/utils/endGame.ts'

// No @types/node in this project (the browser bundle must not see Node globals);
// the harness runs under Node, so declare the one global it uses.
declare const process: { exit(code: number): void }

let checks = 0
let failures = 0
function check(name: string, ok: boolean, detail = ''): void {
  checks++
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

const STALE_LOG = true // the log carries the terminal-failure line

// ── 1+2. the wire field: llm_failure is a technical win at ANY seat count ──
{
  const two = endDialogKind(true, 2, false, 'llm_failure')
  check("llm_failure + win + 2 seats -> technical", two === 'technical-victory', two)
  const four = endDialogKind(true, 4, false, 'llm_failure')
  check('llm_failure + win + 4 seats -> technical (field beats the seat-count limit)',
    four === 'technical-victory', four)
  const noLog = endDialogKind(true, 3, false, 'llm_failure')
  check('llm_failure + win + 3 seats WITHOUT the log line -> technical (no log needed)',
    noLog === 'technical-victory', noLog)
}

// ── 3. any other present reason wins over the inference ──
{
  const conceding = endDialogKind(true, 2, STALE_LOG, 'concede')
  check('concede + win + 2 seats + STALE terminal log -> victory (field wins)',
    conceding === 'victory', conceding)
  for (const reason of ['timeout', 'idle', 'quit', 'unknown']) {
    const got = endDialogKind(true, 2, STALE_LOG, reason)
    check(`${reason} + win + 2 seats + STALE terminal log -> victory (not a provider failure)`,
      got === 'victory', got)
  }
  const multi = endDialogKind(true, 4, STALE_LOG, 'concede')
  check('concede + win + 4 seats -> victory', multi === 'victory', multi)
}

// ── 4. absent field: the stock/older-server fallback is unchanged ──
{
  const undef = endDialogKind(true, 2, STALE_LOG, undefined)
  check('undefined + win + 2 seats + terminal log -> technical (fallback preserved)',
    undef === 'technical-victory', undef)
  const nul = endDialogKind(true, 2, STALE_LOG, null)
  check('null (watcher / Gson-dropped) + win + 2 seats + terminal log -> technical (fallback)',
    nul === 'technical-victory', nul)
  const undef4 = endDialogKind(true, 4, STALE_LOG, undefined)
  check('undefined + win + 4 seats + terminal log -> victory (fallback still declines >2)',
    undef4 === 'victory', undef4)
  const clean = endDialogKind(true, 2, false, undefined)
  check('undefined + win + 2 seats + NO terminal log -> victory', clean === 'victory', clean)
}

// ── 5. a loss is a loss, whatever the reason says ──
{
  for (const reason of ['llm_failure', 'concede', 'timeout', 'idle', 'quit', 'unknown']) {
    check(`loss + ${reason} -> defeat`, endDialogKind(false, 2, STALE_LOG, reason) === 'defeat')
  }
  check('loss + absent reason -> defeat', endDialogKind(false, 2, false, undefined) === 'defeat')
}

// ── 6. the strings the dialog renders ──
{
  check('technical title is the forfeit title',
    END_DIALOG_TITLES['technical-victory'] === 'Victory (by forfeit)')
  check('technical note still names the LLM provider failure',
    END_DIALOG_TECHNICAL_NOTE.includes('LLM provider failure')
      && END_DIALOG_TECHNICAL_NOTE.includes('forfeit'))
}

console.log(`\n${checks - failures}/${checks} checks passed`)
if (failures > 0) process.exit(1)
