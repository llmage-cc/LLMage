/**
 * Behavioural checks for the end-of-game presentation of a terminal LLM-seat
 * failure.
 *
 * Run with:  bash scripts/verify-endgame-presentation.sh
 *
 * (Compile-only agent: this harness is WRITTEN but deliberately NOT RUN — the
 * the parent change runs it. Same rolldown-bundle pattern as
 * verify-games-list.sh.)
 *
 * Assertions:
 *  A. The discriminator. isTerminalFailureLine matches the engine's exact
 *     fatal line (LLMPlayer.java:2200) — plain and HTML-wrapped — and rejects
 *     ordinary narration.
 *  B. endDialogKind: a 2-seat WIN with the fatal line in the log is
 *     'technical-victory' ("Victory (by forfeit)"); a 2-seat WIN WITHOUT it is
 *     'victory'; a loss is 'defeat'; a >2-seat game declines the inference
 *     (never mislabels a played-out win).
 *  C. filterPostLossNoise over the EXACT contradictory tail:
 *     drops the dead seat's post-loss mulligan/shuffle lines AND the duplicate
 *     same-seat "has lost the game" framing, KEEPS the fatal line, the winner's
 *     "has won the game", and everything before the failure; a DIFFERENT
 *     seat's loss survives; talk lines are never touched.
 *  D. logLineClass tints ONLY the fatal line (log-fatal on top of the usual
 *     log-entry log-<type>).
 *  E. The real REPLAY slice (replayLogSliceFor) drops the same tail from a
 *     captured save, and the real LIVE store (appendEvent) → filter pipeline
 *     yields the same cleaned list.
 *  F. GameEndDialog renders "Victory (by forfeit)" + the forfeit note for the
 *     technical win through the REAL component + store, and "Victory!" /
 *     "Defeat" otherwise.
 *  G. Glyph safety: WINNER_GLYPH is ASCII-only, and no winner-row / tournament
 *     source still contains the tofu-prone 🏆 emoji (it rendered as "▯").
 */
import { readFileSync } from 'node:fs'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import { replayLogSliceFor } from '../src/store/slices/replaySlice'
import { GameEndDialog } from '../src/components/GameEndDialog'
import {
  END_DIALOG_TECHNICAL_NOTE,
  END_DIALOG_TITLES,
  WINNER_GLYPH,
  endDialogKind,
  filterPostLossNoise,
  isPostLossSeatNoise,
  isTerminalFailureLine,
  logHasTerminalFailure,
  logLineClass,
  terminalFailureSeat,
} from '../src/utils/endGame'
import type { GameEvent, GameState } from '../src/types'

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

// ── A. the discriminator ──
const FATAL = "glm-5.3-flash-dana loses the game (LLM provider failure)."
check('A1: fatal line recognized (plain)', isTerminalFailureLine(FATAL))
check('A2: fatal line recognized through the server <font> colouring',
  isTerminalFailureLine("<font color='#20B2AA'>glm-5.3-flash-dana</font> loses the game (LLM provider failure)."))
check('A3: ordinary lines are NOT fatal',
  !isTerminalFailureLine('glm-5.3-flash-dana has lost the game')
    && !isTerminalFailureLine('BugHunterkbg has won the game')
    && !isTerminalFailureLine('glm-5.3-flash-dana decides to take mulligan')
    && !isTerminalFailureLine(undefined))
check('A4: seat name extracted from the fatal line',
  terminalFailureSeat(FATAL) === 'glm-5.3-flash-dana'
    && terminalFailureSeat('not a fatal line') === null)
check('A5: logHasTerminalFailure over a list',
  logHasTerminalFailure([ev(1, 'hi'), ev(2, FATAL)])
    && !logHasTerminalFailure([ev(1, 'hi')]))

// ── B. dialog kind ──
check('B1: 2-seat win + fatal line in log -> technical-victory',
  endDialogKind(true, 2, true) === 'technical-victory')
check('B2: 2-seat win without a fatal line -> victory',
  endDialogKind(true, 2, false) === 'victory')
check('B3: loss -> defeat regardless of the log',
  endDialogKind(false, 2, true) === 'defeat' && endDialogKind(false, 2, false) === 'defeat')
check('B4: >2 seats declines the inference (never mislabels a played-out win)',
  endDialogKind(true, 3, true) === 'victory' && endDialogKind(true, 0, true) === 'victory')
check('B5: titles exact',
  END_DIALOG_TITLES['victory'] === 'Victory!'
    && END_DIALOG_TITLES['technical-victory'] === 'Victory (by forfeit)'
    && END_DIALOG_TITLES['defeat'] === 'Defeat')
check('B6: forfeit note names the cause',
  END_DIALOG_TECHNICAL_NOTE.includes('forfeit') && END_DIALOG_TECHNICAL_NOTE.includes('LLM provider failure'))

// ── C. the contradictory post-mortem log, verbatim ──
const SEAT = 'glm-5.3-flash-dana'
const contradictoryLog = [
  ev(1, `${SEAT}'s library is shuffled`),        // pre-failure: KEEP
  ev(2, `${SEAT} decides to take mulligan`),     // pre-failure: KEEP
  ev(3, FATAL),                                  // the fatal line: KEEP
  ev(4, 'BugHunterkbg has won the game'),         // winner announcement: KEEP
  ev(5, `${SEAT} has lost the game`),            // duplicate same-seat framing: DROP
  ev(6, `${SEAT} decides to take mulligan`),     // post-loss action: DROP
  ev(7, `${SEAT} mulligans down to 6 cards`),    // post-loss action: DROP
  ev(8, `${SEAT}'s library is shuffled`),        // post-loss action: DROP
]
const cleaned = filterPostLossNoise(contradictoryLog)
check('C1: dead seat post-loss narration + duplicate framing removed',
  cleaned.map((e) => e.seq).join(',') === '1,2,3,4', cleaned.map((e) => e.seq).join(','))
check('C2: the fatal line itself is preserved (and keeps its tint)',
  cleaned.some((e) => e.message === FATAL))
check('C3: the winner announcement is preserved',
  cleaned.some((e) => e.message === 'BugHunterkbg has won the game'))

// Same-seat scoping: a DIFFERENT seat's genuine loss is never eaten.
const multi = [ev(1, FATAL), ev(2, 'other-seat has lost the game'), ev(3, `${SEAT} has lost the game`)]
check('C4: only the SAME seat’s duplicate loss line is dropped',
  filterPostLossNoise(multi).map((e) => e.seq).join(',') === '1,2')
check('C5: talk lines are never filtered',
  filterPostLossNoise([ev(1, FATAL), ev(2, 'I lost to a bug', 'talk')]).length === 2)
check('C6: with no fatal line the log is untouched (pre-game mulligans stay)',
  filterPostLossNoise([ev(1, `${SEAT} decides to take mulligan`), ev(2, `${SEAT}'s library is shuffled`)]).length === 2)
check('C7: isPostLossSeatNoise covers the tail strings',
  isPostLossSeatNoise(`${SEAT} decides to take mulligan`)
    && isPostLossSeatNoise('A mulligans down to 6 cards')
    && isPostLossSeatNoise('A keeps hand')
    && isPostLossSeatNoise("A's library is shuffled")
    && !isPostLossSeatNoise('A casts Brainstorm'))

// ── D. fatal-line tint class ──
check('D1: fatal line gets log-fatal on top of the usual classes',
  logLineClass('action', FATAL) === 'log-entry log-action log-fatal')
check('D2: ordinary lines keep their old classes',
  logLineClass('action', 'draws a card') === 'log-entry log-action'
    && logLineClass('talk', 'hi') === 'log-entry log-talk')

// ── E. real replay slice + real live store pipeline ──
const replayFrame = {
  status: 'finished', turn: 1, phase: null, step: null,
  activePlayer: 'A', priorityPlayer: '',
  stack: [], combat: [], players: [], llmDebug: [],
  revealed: [], lookedAt: [], stateSeq: 1,
  gameLog: contradictoryLog.map((e) => e.message),
  gameLogCount: contradictoryLog.length,
} as unknown as GameState
const replayLines = replayLogSliceFor(replayFrame)
check('E1: replay slice drops the dead seat’s post-mortem tail (fatal line kept)',
  replayLines.length === 4 && replayLines[2] === FATAL && replayLines[3] === 'BugHunterkbg has won the game',
  JSON.stringify(replayLines))

useGameStore.setState({ eventLog: [] })
for (const e of contradictoryLog) {
  useGameStore.getState().appendEvent(e)
}
const liveCleaned = filterPostLossNoise(useGameStore.getState().eventLog)
check('E2: real live eventLog (appendEvent) filters to the same clean list',
  liveCleaned.map((e) => e.seq).join(',') === '1,2,3,4',
  liveCleaned.map((e) => e.seq).join(','))

// ── F. the real dialog component ──
// FIXTURE NOTE: zustand v5 wires react-dom/server's snapshot to
// ` => selector(api.getInitialState())` (bundled from node_modules/zustand/
// esm/react.mjs:9). React 19.2's useSyncExternalStore takes THAT snapshot for
// renderToStaticMarkup, so without help every leg reads the creation-time state
// (gameEnd:null) and sees hasWon=false. The shipped browser app is a pure CSR
// bundle and never takes this path, so the .sh runner rewrites that one call to
// `api.getState()` in the throwaway bundle before Node runs it.
const TWO_SEATS = [{ name: 'BugHunterkbg', life: 20 }, { name: SEAT, life: 20 }]
useGameStore.getState().setGameOver('You won the game on turn 1.', { hasWon: true, players: TWO_SEATS })
const techHtml = renderToStaticMarkup(createElement(GameEndDialog, { result: 'You won the game on turn 1.' }))
check('F1: technical win dialog shows the forfeit title + note',
  techHtml.includes('Victory (by forfeit)') && techHtml.includes('forfeit'))

// Same win, but no fatal line in the log -> a played-out victory.
useGameStore.setState({ eventLog: [] })
const plainHtml = renderToStaticMarkup(createElement(GameEndDialog, { result: 'You won the game on turn 1.' }))
check('F2: played-out win keeps the plain "Victory!" title',
  plainHtml.includes('Victory!') && !plainHtml.includes('by forfeit'))

// Loss -> Defeat.
useGameStore.getState().setGameOver('You lost the game on turn 3.', { hasWon: false, players: TWO_SEATS })
const lossHtml = renderToStaticMarkup(createElement(GameEndDialog, { result: 'You lost the game on turn 3.' }))
check('F3: loss still renders "Defeat"',
  lossHtml.includes('Defeat') && !lossHtml.includes('by forfeit'))

// ── G. glyph safety ──
check('G1: WINNER_GLYPH is ASCII-only (cannot tofu)',
  /^[\x20-\x7e]+$/.test(WINNER_GLYPH))
// Fail Fast (AGENTS.md): the source-scan must read THIS worktree's src, not a
// guessed path — the runner (.sh) exports it exactly; a missing value is a bug.
const SRC = process.env.XMAGE_REACT_SRC
if (!SRC) throw new Error('XMAGE_REACT_SRC is not set — run via scripts/verify-endgame-presentation.sh')
for (const f of [
  'utils/gamesList.ts',
  'components/lobby/GamesList.tsx',
  'components/ReplayBrowser.tsx',
  'components/GameEndDialog.tsx',
]) {
  const src = readFileSync(`${SRC}/${f}`, 'utf8')
  check(`G2: ${f} carries no trophy emoji (it renders as the tofu box)`,
    !src.includes('🏆'), 'contains the tofu-prone trophy emoji')
}

console.log(`\n${checks - failures}/${checks} checks passed`)
if (failures > 0) process.exit(1)
