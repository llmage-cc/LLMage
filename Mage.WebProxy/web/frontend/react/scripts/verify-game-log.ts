/**
 * Behavioural checks for the game-end dialog's SAVE LOG.
 *
 * Run with:  bash scripts/verify-game-log.sh
 *
 * (Agent unit: this harness is WRITTEN but deliberately NOT RUN here — the
 * the parent change runs it first. Same rolldown-bundle + react-dom/server
 * pattern as verify-endgame-presentation.sh.)
 *
 * WHAT P2 ADDS. The desktop GameEndDialog saves the game log on game end
 * (`saveGameLog`, GameEndDialog.java:59-121) to a timestamped file, gated by
 * KEY_GAME_LOG_AUTO_SAVE. The web dialog only showed the result; the browser
 * log (RightRail Game Log tab) was lost when the session ended. P2 adds Copy
 * Log (clipboard) + Save Log (.txt download) to the dialog, both built from the
 * SAME post-loss-filtered text the tab renders.
 *
 * Assertions:
 *  A. utils/gameLog.buildGameLogText: empty log -> '' (never a header-only
 *     file); the post-loss noise filter runs (identical to the on-screen log);
 *     blank messages are dropped; the header carries the title + stamp.
 *  B. formatLogStamp / gameLogFileName shape.
 *  C. The REAL GameEndDialog renders the two labelled save controls, ENABLED
 *     when a log is present, and DISABLED with the "no log" note when it is
 *     empty — the controls are never silently useless.
 *  D. Source-scan regression: the dialog wires clipboard + download handlers.
 */
import { readFileSync } from 'node:fs'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import { GameEndDialog } from '../src/components/GameEndDialog'
import { buildGameLogText, gameLogFileName, formatLogStamp } from '../src/utils/gameLog'
import type { GameEvent } from '../src/types'

declare const process: { exit(code: number): void; env: Record<string, string | undefined> }

let failures = 0
let checks = 0
function check(name: string, ok: boolean, detail = '') {
  checks++
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

const ev = (seq: number, message: string, type: 'action' | 'talk' = 'action'): GameEvent =>
  ({ seq, type, message })

const FATAL = 'A loses the game (LLM provider failure).'
// ── A. buildGameLogText ──
check('A1: an empty log produces an empty string (no header-only file)',
  buildGameLogText({ entries: [], generatedAt: 1_758_900_000_000 }) === '')
check('A2: blank messages are dropped',
  buildGameLogText({ entries: [ev(1, ''), ev(2, '   '), ev(3, 'draws a card')] })
    .endsWith('draws a card'))
check('A3: the post-loss noise filter runs (dead-seat mulligan tail removed)',
  (() => {
    const txt = buildGameLogText({
      entries: [
        ev(1, 'A mulligans down to 6 cards'),   // pre-failure? no fatal yet -> KEPT
        ev(2, FATAL),
        ev(3, 'A mulligans down to 5 cards'),   // post-loss -> DROPPED
        ev(4, 'A has lost the game'),           // duplicate framing -> DROPPED
        ev(5, 'B has won the game'),
      ],
    })
    return txt.includes('mulligans down to 6 cards')
      && !txt.includes('mulligans down to 5 cards')
      && !txt.includes('A has lost the game')
      && txt.includes('B has won the game')
      && txt.includes(FATAL)
  })())
check('A4: header carries the title and the stamp when supplied',
  (() => {
    const txt = buildGameLogText({
      entries: [ev(1, 'x')],
      title: 'Test Match',
      generatedAt: 1_758_900_000_000,
    })
    const lines = txt.split('\n')
    return lines[0] === 'XMage game log' && lines[1] === 'Test Match' && /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/.test(lines[2]!)
  })())
check('A5: a blank title is omitted (no empty header line)',
  (() => {
    const lines = buildGameLogText({ entries: [ev(1, 'x')], title: '  ', generatedAt: 1_758_900_000_000 }).split('\n')
    return lines[0] === 'XMage game log' && /^\d{4}-/.test(lines[1]!)
  })())
check('A6: without generatedAt the stamp line is omitted (pure, no clock read)',
  buildGameLogText({ entries: [ev(1, 'x')] }).split('\n')[1] === '')

// ── B. stamp + filename ──
check('B1: formatLogStamp formats and rejects junk',
  /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/.test(formatLogStamp(1_758_900_000_000)!)
    && formatLogStamp(undefined) === null && formatLogStamp(Number.NaN) === null)
check('B2: gameLogFileName is safe and deterministic',
  gameLogFileName(1_758_900_000_000) === 'xmage-game-log_202609191432.txt'
    || /^xmage-game-log_\d{12}\.txt$/.test(gameLogFileName(1_758_900_000_000)),
  gameLogFileName(1_758_900_000_000))
check('B3: unknown time still yields a usable filename',
  gameLogFileName(undefined) === 'xmage-game-log.txt')

// ── C. the real dialog ──
// FIXTURE NOTE: zustand v5 wires react-dom/server's snapshot to
// ` => selector(api.getInitialState())`; React 19's useSyncExternalStore takes
// THAT snapshot for renderToStaticMarkup, so without the bundle rewrite every leg
// reads the creation-time state. The .sh runner rewrites that one call.
const withLog: GameEvent[] = [ev(1, 'A draws a card'), ev(2, FATAL), ev(3, 'B has won the game')]
useGameStore.setState({
  eventLog: withLog,
  gameEnd: { result: 'You won the game.', hasWon: true, matchInfo: 'Test Match' },
})
const htmlLog = renderToStaticMarkup(createElement(GameEndDialog, { result: 'You won the game.' }))
check('C1: the copy + save controls render', htmlLog.includes('Copy Log') && htmlLog.includes('Save Log'), htmlLog)
check('C2: with a log present the controls are ENABLED (no disabled attr on them)', (() => {
  // crude but exact: the button tag carrying data-explain="game-end-copy-log"
  // must not contain disabled.
  const m = /<button[^>]*data-explain="game-end-copy-log"[^>]*>/.exec(htmlLog)
  const m2 = /<button[^>]*data-explain="game-end-save-log"[^>]*>/.exec(htmlLog)
  return !!m && !!m2 && !/disabled/.test(m[0]) && !/disabled/.test(m2[0])
})(), htmlLog)
check('C3: the "no log" note is NOT shown when a log exists',
  !htmlLog.includes('No game log recorded'), htmlLog)

useGameStore.setState({ eventLog: [] })
const htmlEmpty = renderToStaticMarkup(createElement(GameEndDialog, { result: 'You won the game.' }))
check('C4: with an empty log the controls are DISABLED', (() => {
  const m = /<button[^>]*data-explain="game-end-copy-log"[^>]*>/.exec(htmlEmpty)
  const m2 = /<button[^>]*data-explain="game-end-save-log"[^>]*>/.exec(htmlEmpty)
  return !!m && !!m2 && /disabled/.test(m[0]) && /disabled/.test(m2[0])
})(), htmlEmpty)
check('C5: with an empty log the explanatory note renders',
  htmlEmpty.includes('No game log recorded'), htmlEmpty)

// ── D. source-scan regression ──
const SRC = process.env.XMAGE_REACT_SRC
if (!SRC) throw new Error('XMAGE_REACT_SRC is not set — run via scripts/verify-game-log.sh')
const dialogSrc = readFileSync(`${SRC}/components/GameEndDialog.tsx`, 'utf8')
check('D1: the dialog calls navigator.clipboard.writeText', dialogSrc.includes('navigator.clipboard'))
check('D2: the dialog builds a download blob', dialogSrc.includes('URL.createObjectURL') && dialogSrc.includes('a.download'))
check('D3: both build the SAME pure log text helper', dialogSrc.includes('buildGameLogText'))
check('D4: both controls carry an explain id',
  dialogSrc.includes('data-explain="game-end-copy-log"') && dialogSrc.includes('data-explain="game-end-save-log"'))

console.log(`\n${checks - failures}/${checks} checks passed`)
if (failures > 0) process.exit(1)
