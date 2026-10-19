/**
 * Behavioural checks for the game header's SEPARATELY LABELLED step vs phase
 * fields.
 *
 * Run with:  bash scripts/verify-phase-step.sh
 *
 * (Agent unit: this harness is WRITTEN but deliberately NOT RUN here — the
 * the parent change runs it first. Same rolldown-bundle + react-dom/server
 * pattern as verify-endgame-presentation.sh.)
 *
 * WHAT THIS FIXES. GameHeader.tsx used to render ONE combined string through
 * `formatPhaseStep(phase, step)` (`Turn N | <Phase/Step> | priority`) — the step
 * WON whenever it was set, so the phase was never shown as its own field. The
 * desktop client shows them as two separately labelled fields: `lblStep`
 * ("Step:") + `txtStep` and `lblPhase` ("Phase:") + `txtPhase`
 * (GamePanel.java:1137-1149, 2394-2406). This harness asserts the split.
 *
 * Assertions:
 *  A. Pure formatters (exported): formatStepLabel / formatPhaseLabel map the
 *     real wire values (Step enum names, Phase abbrevs M1/C1/M2/E1 from
 *     GameStateSerializer.mapPhaseToClient) to their desktop-style labels, fall
 *     back to title-cased text for an unknown enum, and return '' on null.
 *     Crucially: the two are INDEPENDENT — the step never suppresses the phase.
 *  B. The REAL GameHeader component renders BOTH labels ("Step: …", "Phase: …")
 *     with a game state that has BOTH step and phase set — the exact case the
 *     old combined formatter hid.
 *  C. The null cases: step-null / phase-null still render their LABELLED field
 *     (with the em-dash placeholder), never an unlabelled blank; and a null
 *     turn/activePlayer keeps the pre-game label.
 *  D. Source-scan regression: GameHeader.tsx no longer calls formatPhaseStep
 *     (the collapsed formatter) and still renders the #turn-step / #turn-phase
 *     spans.
 */
import { readFileSync } from 'node:fs'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { GameHeader, formatStepLabel, formatPhaseLabel } from '../src/components/GameHeader'
import type { GameState } from '../src/types'

declare const process: { exit(code: number): void; env: Record<string, string | undefined> }

let failures = 0
let checks = 0
function check(name: string, ok: boolean, detail = '') {
  checks++
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

// ── A. pure formatters ──
check('A1: step labels (desktop txtStep wording)', formatStepLabel('PRECOMBAT_MAIN') === 'Main Phase 1'
  && formatStepLabel('POSTCOMBAT_MAIN') === 'Main Phase 2'
  && formatStepLabel('DECLARE_ATTACKERS') === 'Declare Attackers'
  && formatStepLabel('END_TURN') === 'End Step'
  && formatStepLabel('UPKEEP') === 'Upkeep'
  && formatStepLabel('DRAW') === 'Draw Step')
check('A2: phase labels for every emitted abbrev (M1/C1/M2/E1)',
  formatPhaseLabel('M1') === 'Main Phase 1'
    && formatPhaseLabel('C1') === 'Combat Phase'
    && formatPhaseLabel('M2') === 'Main Phase 2'
    && formatPhaseLabel('E1') === 'End Phase')
check('A3: null step / null phase -> empty string (field blanks, not "null")',
  formatStepLabel(null) === '' && formatPhaseLabel(null) === '')
check('A4: unknown enum value title-cased, never dropped',
  formatStepLabel('SOME_NEW_STEP') === 'Some New Step'
    && formatPhaseLabel('Z9') === 'Z9')
check('A5: STEP and PHASE are independent — a set step does NOT suppress the phase',
  formatStepLabel('PRECOMBAT_MAIN') === 'Main Phase 1' && formatPhaseLabel('M1') === 'Main Phase 1'
    && formatPhaseLabel('C1') === 'Combat Phase')
// The old collapsed behaviour, for the record: step||phase picks the step. Assert
// the NEW formatters do not share that coupling.
check('A6: phase formatter never falls back to the step value',
  formatPhaseLabel(null) === '' && formatPhaseLabel('M2') === 'Main Phase 2')

// ── B/C. the real component ──
const baseState: GameState = {
  status: 'playing', turn: 5, phase: 'M1', step: 'PRECOMBAT_MAIN',
  activePlayer: 'QA-A', priorityPlayer: 'QA-A',
  stack: [], combat: [], players: [], llmDebug: [], revealed: [], lookedAt: [], stateSeq: 1,
}

const both = renderToStaticMarkup(createElement(GameHeader, { gameState: baseState, gameTitle: 'QA Game' }))
check('B1: BOTH labels render when step AND phase are set', both.includes('Step:') && both.includes('Phase:'),
  both)
check('B2: the step value is the labelled step text', both.includes('Step: Main Phase 1'), both)
check('B3: the phase value renders independently of the step (M1 -> Main Phase 1)',
  both.includes('Phase: Main Phase 1'), both)
check('B4: the turn/priority line is intact',
  (both.includes("QA-A's Turn 5") || both.includes('QA-A&#x27;s Turn 5')) && both.includes('Priority: QA-A'), both)

// The case that the OLD combined formatter hid: step COMBAT_DAMAGE, phase C1.
const combat: GameState = { ...baseState, phase: 'C1', step: 'COMBAT_DAMAGE' }
const combatHtml = renderToStaticMarkup(createElement(GameHeader, { gameState: combat, gameTitle: 'QA Game' }))
check('B5: combat step + combat phase both show (old formatter showed only the step)',
  combatHtml.includes('Step: Combat Damage') && combatHtml.includes('Phase: Combat Phase'), combatHtml)

// ── C. nulls stay labelled ──
const noStep: GameState = { ...baseState, step: null, phase: 'M2' }
const noStepHtml = renderToStaticMarkup(createElement(GameHeader, { gameState: noStep, gameTitle: 'QA Game' }))
check('C1: null step still renders a LABELLED field with the placeholder',
  noStepHtml.includes('Step: —') && noStepHtml.includes('Phase: Main Phase 2'), noStepHtml)

const noPhase: GameState = { ...baseState, phase: null, step: 'END_TURN' }
const noPhaseHtml = renderToStaticMarkup(createElement(GameHeader, { gameState: noPhase, gameTitle: 'QA Game' }))
check('C2: null phase still renders a LABELLED field with the placeholder',
  noPhaseHtml.includes('Phase: —') && noPhaseHtml.includes('Step: End Step'), noPhaseHtml)

const bothNull: GameState = { ...baseState, phase: null, step: null, turn: 0, activePlayer: '' }
const bothNullHtml = renderToStaticMarkup(createElement(GameHeader, { gameState: bothNull, gameTitle: 'QA Game' }))
check('C3: pre-game (no turn/active/step/phase) keeps the Pregame label and both fields labelled',
  bothNullHtml.includes('Pregame') && bothNullHtml.includes('Step: —') && bothNullHtml.includes('Phase: —'),
  bothNullHtml)

// ── D. source-scan regression ──
const SRC = process.env.XMAGE_REACT_SRC
if (!SRC) throw new Error('XMAGE_REACT_SRC is not set — run via scripts/verify-phase-step.sh')
const src = readFileSync(`${SRC}/components/GameHeader.tsx`, 'utf8')
// Compare against the source with comments stripped: the previous formatter is
// legitimately NAMED in a code comment; only an actual call/import is a regression.
const srcNoComments = src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '')
check('D1: the collapsed formatPhaseStep formatter is gone', !/\bformatPhaseStep\s*\(/.test(srcNoComments))
check('D2: both labelled spans are present', src.includes('id="turn-step"') && src.includes('id="turn-phase"'))
check('D3: Step: and Phase: are rendered as separate labels',
  /Step:\s*\{stepLabel/.test(src) && /Phase:\s*\{phaseLabel/.test(src))

console.log(`\n${checks - failures}/${checks} checks passed`)
if (failures > 0) process.exit(1)
