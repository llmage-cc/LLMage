import type { Player } from '@/types'

/**
 * Auto-pass ("skip") button ↔ engine flag mapping (2026-09-16).
 *
 * The ENGINE owns skip state. Each skip action sets a per-player flag
 * (`PlayerImpl.sendPlayerAction`, Mage/src/main/java/mage/players/PlayerImpl.java:2732-2775)
 * and the engine CLEARS that flag the moment the skip fires
 * (`HumanPlayer.priority()`: `passedUntilNextMain = false` at HumanPlayer.java:1315,
 * `passedUntilEndOfTurn = false` at :1330, …) or when the player cancels
 * (`PASS_PRIORITY_CANCEL_ALL_ACTIONS` → `resetPlayerPassedActions()`,
 * PlayerImpl.java:2684-2697). Those flags reach the browser through
 * `PlayerView` (PlayerView.java:53-58:290-312) → the proxy's
 * `GameStateSerializer.serializePlayer` → `Player.passed*`.
 *
 * The rail must therefore derive its `skip-active` highlight from these flags —
 * exactly like the desktop client, which mirrors each skip button's pressed
 * state from the same fields on every update (`GamePanel.updatePressState`,
 * Mage.Client/src/main/java/mage/client/game/GamePanel.java:1417-1424).
 *
 * The rail previously used purely local state, so a skip that had already fired
 * stayed highlighted, and clicking it again sent `cancel_skip` — a no-op against
 * a player with no skip armed.
 */
export const SKIP_BUTTON_FLAG = {
  'skip-turn': 'passedTurn', //                             F4 PASS_PRIORITY_UNTIL_NEXT_TURN
  'skip-eot': 'passedUntilEndOfTurn', //                    F5 PASS_PRIORITY_UNTIL_TURN_END_STEP
  'skip-main': 'passedUntilNextMain', //                    F7 PASS_PRIORITY_UNTIL_NEXT_MAIN_PHASE
  'skip-my-turn': 'passedAllTurns', //                      F9 PASS_PRIORITY_UNTIL_MY_NEXT_TURN
  'skip-stack': 'passedUntilStackResolved', //              F10 PASS_PRIORITY_UNTIL_STACK_RESOLVED
  'skip-end-step': 'passedUntilEndStepBeforeMyTurn', //     F11 PASS_PRIORITY_UNTIL_END_STEP_BEFORE_MY_NEXT_TURN
} as const

export type SkipButtonId = keyof typeof SKIP_BUTTON_FLAG

/**
 * The `skip_priority` wire value each button sends — must match the `skipType`
 * switch in WebProxySession.handleSkipPriority (Mage.WebProxy/.../WebProxySession.java:1712-1736).
 */
export const SKIP_WIRE_TYPE: Record<SkipButtonId, string> = {
  'skip-turn': 'next_turn',
  'skip-eot': 'end_of_turn',
  'skip-main': 'next_main',
  'skip-my-turn': 'my_next_turn',
  'skip-stack': 'stack_resolved',
  'skip-end-step': 'end_step_before_my_turn',
}

/** Every skip button id, in the order the rail renders them. */
export const SKIP_BUTTON_IDS: readonly SkipButtonId[] = [
  'skip-eot',
  'skip-main',
  'skip-turn',
  'skip-my-turn',
  'skip-stack',
  'skip-end-step',
]

/**
 * Which skip button is armed right now, per the SERVER's view of this player.
 *
 * `sendPlayerAction` resets every passed flag before setting the requested one
 * (`resetPlayerPassedActions()` first in each case, PlayerImpl.java:2732-2775),
 * so at most one of these flags is set at a time; the mapping order is only a
 * defensive tie-break if the engine ever reports two.
 *
 * Returns null when no skip is armed — including the moment right AFTER a skip
 * fires, which is the whole point: the highlight must clear then.
 */
export function armedSkipButton(player: Pick<Player, (typeof SKIP_BUTTON_FLAG)[SkipButtonId]> | null | undefined): SkipButtonId | null {
  if (!player) return null
  for (const id of SKIP_BUTTON_IDS) {
    if (player[SKIP_BUTTON_FLAG[id]]) return id
  }
  return null
}

/**
 * What a click on `btnId` means, given which skip the server currently reports
 * as armed: clicking the ARMED skip cancels it (the rail's toggle — a real
 * cancel of a live skip), anything else arms that skip.
 *
 * Deriving this from the server flag is the fix for the second half: the old
 * local `activeSkip` claimed a fired skip was still armed, so the re-click sent
 * `cancel_skip` against a player with nothing to cancel.
 */
export function skipClickIntent(armed: SkipButtonId | null, btnId: SkipButtonId): 'cancel' | 'arm' {
  return armed === btnId ? 'cancel' : 'arm'
}
