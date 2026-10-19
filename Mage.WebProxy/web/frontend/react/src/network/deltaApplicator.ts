import type { GameState, GameStateDelta } from '@/types'

/**
 * Apply a state delta to the current game state.
 *
 * The backend (WebProxyClient.computeStateDelta) does a top-level key diff:
 * for each changed key, it sends the ENTIRE new value for that key.
 * So a shallow merge is correct — but we do it explicitly and typed,
 * with clear semantics for each field.
 *
 * This function is the single source of truth for how deltas merge.
 * If the backend later evolves to send finer-grained deltas (e.g., partial
 * player objects), this is the only place that needs to change.
 *
 * Semantics:
 * - Keys present in `delta` replace the corresponding key in `current`.
 * - Keys absent in `delta` are left unchanged.
 * - `null` in a delta field means "set to null" (e.g., combat cleared).
 */
export function applyDelta(current: GameState, delta: GameStateDelta): GameState {
  // Start with a shallow copy of current, then overlay delta keys.
  // We do this field-by-field rather than a spread to make the merge
  // explicit and to give TypeScript a chance to catch type errors.
  const next: GameState = { ...current }

  if ('status' in delta) next.status = delta.status!
  if ('turn' in delta) next.turn = delta.turn!
  if ('isSpectator' in delta) next.isSpectator = delta.isSpectator!
  if ('phase' in delta) next.phase = delta.phase!
  if ('step' in delta) next.step = delta.step!
  if ('activePlayer' in delta) next.activePlayer = delta.activePlayer!
  if ('priorityPlayer' in delta) next.priorityPlayer = delta.priorityPlayer!
  if ('stack' in delta) next.stack = delta.stack!
  if ('combat' in delta) next.combat = delta.combat!
  if ('players' in delta) next.players = delta.players!
  if ('llmDebug' in delta) next.llmDebug = delta.llmDebug!
  // The root-level UNFILTERED flag (live LLM-tab gate). The backend's delta is a
  // top-level key diff, so it carries this key whenever the flag changes (e.g.
  // the LLM's first exchange turns it true) — whitelisting it here is what lets
  // the rail's tab gate follow the live flag without a full-state resend.
  if ('hasLlmPlayers' in delta) next.hasLlmPlayers = delta.hasLlmPlayers!
  // Controlled/watched hands. Control of another player's turn STARTS and
  // ENDS mid-game, so this key flips repeatedly — without the whitelist the
  // browser would keep a stale switch affordance after control was lost (or miss
  // it when control began) until some unrelated full-state push.
  if ('controlledHands' in delta) next.controlledHands = delta.controlledHands!
  if ('revealed' in delta) next.revealed = delta.revealed!
  if ('lookedAt' in delta) next.lookedAt = delta.lookedAt!
  // Watched hands — a grant/revoke changes this key, and the top-level
  // key diff then ships the whole new list. Without this line the granted hand
  // would only appear on a full-state push (game_init/resync), i.e. never
  // until a reload.
  if ('watchedHands' in delta) next.watchedHands = delta.watchedHands!
  if ('stateSeq' in delta) next.stateSeq = delta.stateSeq!

  return next
}