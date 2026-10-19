/**
 * Standalone behavioural check for the phase-bar turn ordering.
 *
 * Run with:  bash scripts/verify-phasebar-order.sh
 *
 * "cleanup and end step are out of order in the phases bar (cleanup comes
 * after endstep in the actual game)". The engine's PhaseStep enum
 * (Mage/src/main/java/mage/constants/PhaseStep.java) orders END_TURN (11) then
 * CLEANUP (12) — cleanup is the LAST step of the turn. PhaseBar.tsx rendered
 * Cleanup before End Step; this verifies the bar renders the full phase list in
 * true turn order with Cleanup last, and highlights the step the server reports.
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { PhaseBar } from '../src/components/PhaseBar'
import type { GameState } from '../src/types'

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

/** Extract the rendered phase labels in their DOM order from PhaseBar markup. */
function renderedLabels(gameState: Partial<GameState>): string[] {
  const html = renderToStaticMarkup(<PhaseBar gameState={gameState as GameState} readOnly />)
  return [...html.matchAll(/phase-btn-label">([^<]+)</g)].map((m) => m[1]!)
}

section('phase bar renders the full turn in true MTG order, cleanup last')

const labels = renderedLabels({})
const expected = [
  'Untap', 'Upkeep', 'Draw', 'Main 1',
  'Begin Combat', 'Attackers', 'Blockers', 'Damage', 'End Combat',
  'Main 2', 'End Step', 'Cleanup',
]
check('all 12 phases render', labels.length === 12, String(labels.length))
check(
  'phase order matches the engine PhaseStep order with CLEANUP last',
  JSON.stringify(labels) === JSON.stringify(expected),
  labels.join(' | '))
check('Cleanup is the LAST button in the bar', labels[labels.length - 1] === 'Cleanup', labels.slice(-2).join('|'))
check('End Step comes directly before Cleanup', labels.indexOf('Cleanup') === labels.indexOf('End Step') + 1, labels.join('|'))

section('current-phase highlight follows the server step enum')

for (const [stepKey, label] of [
  ['END_TURN', 'End Step'],
  ['CLEANUP', 'Cleanup'],
  ['POSTCOMBAT_MAIN', 'Main 2'],
  ['UNTAP', 'Untap'],
] as const) {
  const html = renderToStaticMarkup(<PhaseBar gameState={{ step: stepKey } as GameState} readOnly />)
  const active = [...html.matchAll(/phase-active" title="([^"]+?) \(replay/g)].map((m) => m[1]!)
  check(`step ${stepKey} highlights "${label}" (exactly one)`,
    active.length === 1 && active[0] === label, active.join('|'))
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
