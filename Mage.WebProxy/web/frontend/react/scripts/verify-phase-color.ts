/**
 * Phase-based feedback colouring check (Java reference:
 * HelperPanel's "wait player's action — colorize feedback panel (depends on
 * current phase)" block: MAIN=blue, BATTLE=red, OTHER=green).
 *
 * Run with:  bash scripts/verify-phase-color.sh
 *
 * Layers:
 *   (a) the PURE class derivation (utils/phaseTint) for every Phase enum value
 *       from src/types/enums.ts, plus null/undefined/unknown — those must land
 *       on 'other', never on a guess;
 *   (b) the REAL component wiring: ActionPromptBar is SSR-rendered through
 *       react-dom/server with the store's initial-state object patched in
 *       lockstep (the technique verify-token-preview-art.tsx documents), so the
 *       rendered markup — not the helper alone — is asserted to carry the class
 *       the current phase implies. Built with createElement so this harness
 *       stays a .ts file like its non-JSX siblings;
 *   (c) the .sh side of the pair greps styles/popups.css for the three tint
 *       rules, because SSR markup cannot prove a stylesheet exists.
 */
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import { ActionPromptBar } from '../src/components/ActionPromptBar'
import { actionPromptClass, phaseTintClass } from '../src/utils/phaseTint'
import type { GameState } from '../src/types'

let failures = 0
function check(name: string, cond: boolean, detail = ''): void {
  if (!cond) failures++
  console.log(`  [${cond ? 'PASS' : 'FAIL'}] ${name}${detail ? ' :: ' + detail : ''}`)
}
function section(t: string): void {
  console.log(`\n## ${t}`)
}

// react-dom/server reads the store's INITIAL state object; patch it in lockstep
// with setState so each render sees the delivered scenario.
const ssrSnapshot = (
  useGameStore as unknown as { getInitialState: () => Record<string, unknown> }
).getInitialState()
const setLive = (patch: Record<string, unknown>) => {
  useGameStore.setState(patch as never)
  Object.assign(ssrSnapshot, patch)
}

// ── (a) the pure derivation ──

section('a. phase -> tint class (Phase enum from src/types/enums.ts)')

const MAIN_PHASES = ['M1', 'M2'] as const
const COMBAT_PHASES = ['C1'] as const
// E1 (end), C2 (cleanup) and the two values the backend never emits (M3, E2)
// are all "other" per the Java default branch.
const OTHER_PHASES = ['E1', 'C2', 'M3', 'E2'] as const

for (const p of MAIN_PHASES) {
  check(`phase ${p} -> action-prompt--main`, phaseTintClass(p) === 'action-prompt--main', phaseTintClass(p))
}
for (const p of COMBAT_PHASES) {
  check(`phase ${p} -> action-prompt--combat`, phaseTintClass(p) === 'action-prompt--combat', phaseTintClass(p))
}
for (const p of OTHER_PHASES) {
  check(`phase ${p} -> action-prompt--other`, phaseTintClass(p) === 'action-prompt--other', phaseTintClass(p))
}
check('null phase -> other (no game state)', phaseTintClass(null) === 'action-prompt--other')
check('undefined phase -> other', phaseTintClass(undefined) === 'action-prompt--other')
check('unknown phase string -> other (never a guessed tint)', phaseTintClass('PRECOMBAT_MAIN') === 'action-prompt--other')
check(
  'exactly three distinct classes exist across every Phase enum value',
  new Set(['M1', 'C1', 'M2', 'E1', 'C2', 'M3', 'E2'].map(phaseTintClass)).size === 3,
)
check('actionPromptClass keeps the base class', actionPromptClass('M1').startsWith('action-prompt '))

// ── (b) rendered ActionPromptBar carries the class ──

section('b. rendered ActionPromptBar markup')

/** A minimal live-looking game state the rail renders against. */
const gameStateWithPhase = (phase: GameState['phase']): Partial<GameState> => ({
  status: 'DUELING',
  turn: 3,
  phase,
  step: 'PRECOMBAT_MAIN',
  activePlayer: 'QA-A',
  priorityPlayer: 'QA-A',
  stack: [],
  combat: [],
  players: [],
  llmDebug: [],
  revealed: [],
  lookedAt: [],
  stateSeq: 1,
})

function renderRail(): string {
  return renderToStaticMarkup(createElement(ActionPromptBar))
}

function renderedClass(phase: GameState['phase']): string {
  setLive({ actionPrompt: null, gameState: gameStateWithPhase(phase) })
  const m = /<div class="([^"]*action-prompt[^"]*)"/.exec(renderRail())
  return m ? m[1]! : '(no action-prompt block rendered)'
}

check('phase M1 renders the main tint', renderedClass('M1') === 'action-prompt action-prompt--main', renderedClass('M1'))
check('phase M2 renders the main tint', renderedClass('M2') === 'action-prompt action-prompt--main', renderedClass('M2'))
check('phase C1 renders the combat tint', renderedClass('C1') === 'action-prompt action-prompt--combat', renderedClass('C1'))
check('phase E1 renders the other tint', renderedClass('E1') === 'action-prompt action-prompt--other', renderedClass('E1'))
check('phase C2 renders the other tint', renderedClass('C2') === 'action-prompt action-prompt--other', renderedClass('C2'))
check('phase null renders the other tint', renderedClass(null) === 'action-prompt action-prompt--other', renderedClass(null))

// No game state at all: the rail renders nothing (the rail's early return), which is
// the honest fallback — there is no block to tint, so no class is claimed.
setLive({ actionPrompt: null, gameState: null })
check('no game state renders no block at all', renderRail() === '')

// The tint must not depend on the PROMPT (the rule stated in phaseTint.ts:
// prompts carry no phase, so gameState.phase is the source).
setLive({
  actionPrompt: {
    type: 'boolean', yesLabel: 'Yes', noLabel: 'No', context: 'default',
    message: 'Cast Shock?', rawMessage: 'Cast Shock?',
  },
  gameState: gameStateWithPhase('C1'),
})
const withPrompt = renderRail()
check('with a prompt outstanding, the tint still follows gameState.phase',
  withPrompt.includes('action-prompt--combat'), /<div class="action-prompt[^"]*"/.exec(withPrompt)?.[0] ?? '')

// Spectator frames deliver a phase too — same derivation.
setLive({ actionPrompt: null, gameState: { ...gameStateWithPhase('M2'), isSpectator: true } })
check('spectator frame keeps the phase tint', renderRail().includes('action-prompt--main'))

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
