/**
 * Phase-based feedback colour derivation for the action-prompt block
 * (Java reference: HelperPanel's "wait player's action —
 * colorize feedback panel (depends on current phase)" block, which tints the
 * feedback panel blue while the main phases are running, red during combat and
 * green otherwise: ACTIVE_FEEDBACK_BACKGROUND_COLOR_MAIN (0,0,255,50),
 * _BATTLE (255,0,0,50), _OTHER (0,255,0,50)).
 *
 * The web client derives a CLASS (not a colour) so the tint itself stays in CSS
 * (styles/popups.css) where the theme variables live: blue main / red combat /
 * green other.
 *
 * ── WHICH PHASE ──
 * The current phase comes from `gameState.phase` (Phase enum, src/types/enums.ts:
 * M1/C1/M2/E1/C2/M3/E2; the backend maps Java TurnPhase -> these short strings in
 * GameStateSerializer.mapPhaseToClient). Action prompts do NOT carry a phase
 * field, so there is no "phase of the prompt" to prefer — the rule is simply:
 *   - phase M1 or M2 (pre/post-combat main)  -> action-prompt--main
 *   - phase C1 (combat)                      -> action-prompt--combat
 *   - anything else (E1/C2/M3/E2, or no game state / no phase known) -> --other
 * With no phase available the block keeps the neutral `--other` class rather
 * than guessing a colour: `--other` is the class whose tint is the subtlest.
 */

/** The three tint classes the action-prompt block can carry. */
export type PhaseTintClass = 'action-prompt--main' | 'action-prompt--combat' | 'action-prompt--other'

/**
 * The tint class for a phase value. Accepts `null`/`undefined` (no game state,
 * or a frame whose phase is unknown) — those fall back to '--other'.
 */
export function phaseTintClass(phase: string | null | undefined): PhaseTintClass {
  switch (phase) {
    case 'M1':
    case 'M2':
      return 'action-prompt--main'
    case 'C1':
      return 'action-prompt--combat'
    default:
      return 'action-prompt--other'
  }
}

/** The full className for the action-prompt block, given the current phase. */
export function actionPromptClass(phase: string | null | undefined): string {
  return `action-prompt ${phaseTintClass(phase)}`
}
