import type { ActionType, ActionContext } from './enums'
import type { Card } from './gameState'

/**
 * Action prompt types — derived from WebProxyClient.java handleGame* methods
 * and app.js handleActionPrompt / _build*Prompt methods.
 */

/** A selectable choice in a select prompt — from handleGameSelect(). */
export interface ActionChoice {
  index: number
  id: string
  name: string
  action?: 'land' | 'cast' | 'activate' | 'attack' | 'block'
  manaCost?: string
}

/** A target for target selection — from handleGameTarget(). */
export interface ActionTarget {
  id: string
  name: string
  type: string
  /** For trigger-order candidates: the engine-rendered trigger text of the ability
   *  (AbilityView.rules), so the entry can be labelled card + ability text. */
  abilityText?: string
}

/** Mana payment option — from handleGamePlayMana(). */
export interface ManaOption {
  landId: string
  landName: string
  colors: string[]
  auto: boolean
}

/** Pile for Fact or Fiction — from handleGameChoosePile(). */
export interface Pile {
  index: number
  cards: Card[]
}

/** Multi-amount distribution entry — from handleGameGetMultiAmount(). */
export interface MultiAmountEntry {
  id: string
  name: string
  min: number
  max: number
  value: number
}

/** Base fields common to all action prompts. */
export interface BaseActionPrompt {
  message: string
  type: ActionType
  context: ActionContext
  /** Raw message from the backend, before HTML stripping / player colorization. */
  rawMessage: string
}

/** Action prompt variants — discriminated union on `type`. */
export interface SelectPrompt extends BaseActionPrompt {
  type: 'select'
  choices: ActionChoice[]
  /** True when this is a declare-attackers/blockers prompt. */
  isCombat: boolean
  /** IDs of cards that are valid attackers/blockers (for highlighting). */
  combatChoiceIds: string[]
  /** Server-provided special button label (e.g. "All attack") — sends sendPlayerString("special"). */
  specialButton?: string | undefined
}

export interface BooleanPrompt extends BaseActionPrompt {
  type: 'boolean'
  yesLabel: string
  noLabel: string
  /**
   * Auto-answer key material for this ask, straight off the proxy's
   * boolean payload. `undefined` on a source-less ask (chooseMulligan): the ID-variant
   * affordance is then not offered, exactly like the desktop client disables its
   * "same TEXT and ABILITY" items when originalId == null (HelperPanel.java:531-539).
   */
  originalId?: string | undefined
  /**
   * The engine's own filtered message — the key BOTH variants store under. The proxy
   * always writes it for a GAME_ASK (falling back to the displayed question, matching
   * FeedbackPanel.java:106-111), so absence means a malformed/hand-built payload: the
   * auto-answer affordances are then NOT offered rather than storing a dead key.
   */
  autoAnswerMessage?: string | undefined
}

export interface LookAtCard {
  name: string
  typeLine: string
  manaCost: string
  rules: string[]
  id: string | null
  imageUrl: string
  isValidTarget: boolean
  /** For ability candidates (trigger order): the card that owns the ability. */
  sourceName?: string
  /** For ability candidates (trigger order): the source card's image. */
  sourceImageUrl?: string
  /** For ability candidates (trigger order): the engine-rendered ability text. */
  abilityText?: string
}

export interface TargetPrompt extends BaseActionPrompt {
  type: 'target'
  targets: ActionTarget[]
  /** How many targets the prompt claims must be selected.
   *
   *  The engine transmits no target min/max, so 0 means "no number is being
   *  claimed" — for those prompts the header must not announce a count at all (the
   *  desktop client relies on the engine's Done button instead). Only the trigger-order
   *  family carries a real claim (1). */
  targetCount: number
  /** Upper bound of clickable candidates right now (the engine's candidate list size).
   *  0 when the engine offered no candidates. NOT the number to select. */
  maxTargets?: number
  /** Valid target types for highlighting. */
  validTargetTypes: string[]
  /** Currently selected target IDs. */
  selectedIds: string[]
  /** Whether more targets can be selected. */
  required: boolean
  min: number
  max: number
  /** Cards being looked at (e.g. Surveil/scry top of library). Server sends
   * these via cardsView1 so the player can see what they're choosing. */
  lookAtCards?: LookAtCard[]
  /** Valid target IDs within lookAtCards (for highlighting). */
  lookAtCardsValidIds?: string[]
  /** Server-provided finish button label for any-number target selection
   *  (e.g. "Done"). Set once min targets are met. */
  doneButtonText?: string | undefined
  /** IDs already chosen in this multi-target sequence. */
  chosenTargetIds?: string[]
  /** How many targets already chosen (mirrors chosenTargetIds.length). */
  chosenCount?: number
  /** True for the trigger-order prompt family: `targets` lists the orderable
   *  abilities (id = ability UUID), each labelled card + ability text, and one click
   *  picks one. Never treat it as an "N targets" selection (that was the
   *  "Select 2 targets" header on a one-pick prompt). */
  triggerOrder: boolean
  /** True when this prompt is a NOT_TARGET card list (Target.isNotTarget — "choose a
   *  card" lists like Chrome Mox imprint): nothing is being TARGETED, so TargetingArrows
   * must not draw source→target arrows for it. */
  notTarget: boolean
}

export interface ChooseChoicePrompt extends BaseActionPrompt {
  type: 'choose_choice'
  choices: { index: number; name: string; order?: number }[]
}

export interface ChooseAbilityPrompt extends BaseActionPrompt {
  type: 'choose_ability'
  choices: { index: number; name: string; abilityText: string }[]
}

export interface AmountPrompt extends BaseActionPrompt {
  type: 'amount'
  min: number
  max: number
  /** Default amount (e.g., X value). */
  defaultValue: number
}

export interface PlayManaPrompt extends BaseActionPrompt {
  type: 'play_mana'
  manaOptions: ManaOption[]
  /**
   * Per-source engine verdicts for THIS payment: permanent id → "the engine says
   * this source's mana qualifies for the current unpaid cost". Copied verbatim
   * from the prompt payload's `mana_payable_for_this_cost`
   * (WebProxyClient.handleGamePlayMana).
   *
   * Absent = the server did not evaluate (older proxy/server): the auto-tapper
   * then only uses sources whose production it can read from the rule text
   * (its `planAutoTap` fallback). A source absent from a PRESENT map, or mapped
   * to `false`, must never be tapped on the strength of this data.
   */
  manaPayableForThisCost?: Record<string, boolean>
  /** Remaining cost to pay. */
  remainingCost: string
  /** Whether auto-pay is available. */
  autoPayAvailable: boolean
  /** Whether special mana actions are available (fetch lands, convoke, delve).
   * Mirrors gameView.getSpecial() from the Java client. */
  specialAvailable: boolean
}

export interface ChoosePilePrompt extends BaseActionPrompt {
  type: 'choose_pile'
  pile1: Pile
  pile2: Pile
}

export interface MultiAmountPrompt extends BaseActionPrompt {
  type: 'multi_amount'
  entries: MultiAmountEntry[]
  total: number
  min: number
  max: number
}

/** Discriminated union of all action prompt types. */
export type ActionPrompt =
  | SelectPrompt
  | BooleanPrompt
  | TargetPrompt
  | ChooseChoicePrompt
  | ChooseAbilityPrompt
  | AmountPrompt
  | PlayManaPrompt
  | ChoosePilePrompt
  | MultiAmountPrompt