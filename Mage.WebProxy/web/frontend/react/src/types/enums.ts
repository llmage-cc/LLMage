/**
 * Enum types matching XMage's Phase, Step, and action types.
 * Derived from GameStateSerializer.java (phase/step .name()) and
 * WebProxyClient.java (action_type = method.name().toLowerCase()).
 */

/** MTG phases, from mage.game.turn.Phase. */
export type Phase = 'M1' | 'C1' | 'M2' | 'E1' | 'C2' | 'M3' | 'E2'

/** MTG steps, from mage.game.turn.Step. */
export type Step =
  | 'UPKEEP'
  | 'DRAW'
  | 'PRECOMBAT_MAIN'
  | 'BEGIN_COMBAT'
  | 'DECLARE_ATTACKERS'
  | 'DECLARE_BLOCKERS'
  | 'FIRST_COMBAT_DAMAGE'
  | 'COMBAT_DAMAGE'
  | 'END_COMBAT'
  | 'POSTCOMBAT_MAIN'
  | 'END_TURN'
  | 'CLEANUP'

/** Action prompt types from WebProxyClient.java (method.name().toLowerCase()). */
export type ActionType =
  | 'select'
  | 'boolean'
  | 'target'
  | 'choose_choice'
  | 'choose_ability'
  | 'amount'
  | 'play_mana'
  | 'choose_pile'
  | 'multi_amount'

/** Event categories from WebProxyClient.categorizeEventMessage(). */
export type EventCategory =
  | 'draw'
  | 'damage'
  | 'destroy'
  | 'tap'
  | 'untap'
  | 'exile'
  | 'reveal'
  | 'counter'
  | 'sacrifice'
  | 'discard'
  | 'enter'
  | 'attack'
  | 'block'
  | 'life'
  | 'token'
  | 'generic'
  | 'action'
  | 'talk'

/** Context for action prompts (structured metadata from WebProxyClient). */
export type ActionContext =
  | 'default'
  | 'play_land'
  | 'cast_spell'
  | 'declare_attackers'
  | 'declare_blockers'
  | 'select_target'
  | 'choose_starter'
  | 'trigger_order'
  | 'mana_payment'
  | 'fact_or_fiction'
  | 'damage_distribution'

/** Connection status for the UI banner. */
export type ConnectionStatus =
  | ''
  | 'reconnecting'
  | 'resyncing'
  | 'connection_lost'