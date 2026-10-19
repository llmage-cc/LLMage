/**
 * Auto-Tap Lands — the RUN behind one press that pays the WHOLE cost.
 *
 * WHY THIS MODULE EXISTS (reproduced
 * twice live: Ajani {1}{W} and Amped Raptor {1}{R} each needed two presses).
 *
 * The engine pays ONE mana source per `tap_mana`, then re-prompts:
 *   - `ManaCostsImpl.pay()` (Mage/src/main/java/mage/abilities/costs/mana/ManaCostsImpl.java:148)
 *     loops `while (player.canRespond() && !isPaid())`, calling `player.playMana(...)` and
 *     re-assigning from the mana pool after each answer.
 *   - `HumanPlayer.playManaHandling()` (Mage.Player.Human, line 1636) opens exactly ONE response
 *     (`waitForResponse`) per iteration and consumes ONE `responseId` (the land id); the next
 *     iteration re-prompts `GAME_PLAY_MANA` with the REDUCED unpaid cost.
 *
 * The client's planner (`planAutoTap` in utils/cardTypes.ts) is deliberately single-step for
 * exactly that reason — it re-derives from live state on every call. What was missing is the
 * BRIDGE across the server's re-prompts: a press tapped one land and nothing ever asked for the
 * next step. Such a bridge existed (module-level `autoPayState` + a `setTimeout` auto-continue,
 * 2026-07-24) and was deleted in ca51ec07da together with the auto-answer of `choose_ability`;
 * the 2026-09-14 correctness rewrite (2b636096b1) kept the one-tap-per-press contract without
 * restoring it. Hence the fix: colour awareness, engine-derived `manaProduction`, hybrid/phyrexian
 * pip parsing and the no-cancel invariant are all intact — the sequence simply never ran to the
 * end of the cost.
 *
 * HOW IT WORKS. A press opens a RUN and takes its first step. The store's prompt hook
 * (`onPromptSetForAutoTapRun`, called from `setActionPrompt`) takes the next step every time the
 * server re-prompts `play_mana` with the remaining cost, so ONE press drives the whole sequence.
 * Every step is still planned against LIVE state: the land the server just tapped is gone from
 * the candidate list and the cost text has shrunk, so the single-step planner remains the only
 * authority on WHO may be tapped.
 *
 * INVARIANTS — this module must compose with the earlier mana fixes, never weaken them:
 *  1. WHO may be tapped stays entirely in `planAutoTap`: a permanent whose production is
 *     KNOWN (engine-derived `manaProduction`) to produce a needed colour of the CURRENT
 *     unpaid cost, OR a source the ENGINE itself flagged payable for this exact cost
 *     (`manaPayableForThisCost` — conditional any-colour sources such as Secluded Courtyard,
 *     whose production cannot be characterised from rule text). Unknown still means never
 * tapped (
 * option 2).
 *  2. Exactly ONE tap per server re-prompt, never a burst — the engine consumes one response per
 *     prompt, so an extra send would land on a LATER prompt as a misdelivered answer.
 *     The run is driven by incoming prompts, so it cannot outpace the server.
 *  3. Never the same land twice inside one run. A step whose plan asks for a land this run already
 *     tapped means the server did not move: STOP and say so. Re-sending it is the documented
 *     livelock shape (2026-09-12, 2026-09-13).
 *  4. `booleanChoice(...)` is NEVER sent from here. During GAME_PLAY_MANA it cancels the payment
 *     and the server re-offers the identical cast (the six-cycle livelock) — cancelling stays an
 *     explicit user action; stopping a run is a purely local decision.
 *  5. Stop honestly: when the run cannot continue, the notice names the reason AND the still
 *     unpaid cost, and the run ENDS. No silent stop, no retry loop, no guessing.
 *  6. A run is scoped to the payment it started on. It ends when the prompt is cleared or
 *     withdrawn, when a prompt for anything other than the mana payment arrives, and when the
 *     payment belongs to a different object. A later cast can never be auto-tapped without the
 *     user asking.
 *
 * State lives in module scope — the same pattern as the prompt-lifecycle globals in
 * gameplaySlice.ts. The dependency runs one way (slices -> this module); this module never
 * imports gameplaySlice.ts, so there is no import cycle.
 */
import type { GameStore } from '../types'
import type { PlayManaPrompt } from '@/types'
import { selectMyUntappedLands } from '../selectors'
import { planAutoTap } from '@/utils/cardTypes'

/** Snapshot of the run — stable identity until something changes. */
export interface AutoTapRunSnapshot {
  /** True while a press of "Auto-Tap Lands" is still paying a cost. */
  active: boolean
  /** Land ids this run has already sent a `tap_mana` for. */
  tappedIds: readonly string[]
  /** Honest reason the run could not continue. Null = nothing to report. */
  notice: string | null
  /**
   * Identity of the payment the notice describes (`<object name>\n<unpaid cost>`). A notice whose
   * identity no longer matches the live prompt is dropped, so the cost it quotes is never stale.
   */
  noticeKey: string | null
  /** Bumped on every change so renderers re-read the snapshot. */
  version: number
}

const NO_IDS: readonly string[] = Object.freeze([])

let snapshot: AutoTapRunSnapshot = Object.freeze({
  active: false,
  tappedIds: NO_IDS,
  notice: null,
  noticeKey: null,
  version: 0,
})

/** Identity of the payment the current run is paying for. */
let runSubject: string | null = null

const listeners = new Set<() => void>()

function emit(next: {
  active: boolean
  tappedIds: readonly string[]
  notice: string | null
  noticeKey: string | null
}): void {
  snapshot = Object.freeze({ ...next, version: snapshot.version + 1 })
  for (const listener of [...listeners]) listener()
}

/** Subscribe to run changes (for `useSyncExternalStore`). Returns an unsubscribe function. */
export function subscribeAutoTapRun(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** Current run snapshot (stable between changes — safe as a `useSyncExternalStore` getter). */
export function getAutoTapRun(): AutoTapRunSnapshot {
  return snapshot
}

export function isAutoTapRunActive(): boolean {
  return snapshot.active
}

/**
 * End the run.
 *
 * @param notice    what the player must be told, or null when there is nothing to report (the
 *                  payment finished, the user took over, the prompt was withdrawn).
 * @param noticeKey the payment identity the notice describes (see {@link noticeKeyOf}).
 *
 * Idempotent: a no-op when it would change nothing, so ending on every unrelated prompt does not
 * churn renders.
 */
export function stopAutoTapRun(notice: string | null, noticeKey: string | null = null): void {
  runSubject = null
  if (!snapshot.active && snapshot.notice === notice && snapshot.noticeKey === noticeKey) return
  emit({ active: false, tappedIds: NO_IDS, notice, noticeKey })
}

/**
 * Record that this run sent a `tap_mana` for `landId`.
 * @returns false when this run ALREADY tapped that land — the server did not move (stale state
 *          or an ignored tap), so the caller must stop instead of re-sending.
 */
export function recordAutoTap(landId: string): boolean {
  if (snapshot.tappedIds.includes(landId)) return false
  emit({ active: snapshot.active, tappedIds: [...snapshot.tappedIds, landId], notice: snapshot.notice, noticeKey: snapshot.noticeKey })
  return true
}

/** What an incoming prompt means for an active run. */
export function autoTapPromptAction(type: string): 'continue' | 'pause' | 'end' {
  if (type === 'play_mana') return 'continue'
  // A multi-ability land mid-payment raises `choose_ability` (GAME_CHOOSE_ABILITY). That choice is
  // the USER's — it can decide a colour, and this client has no data to pick one for them (the
  // "First Mana Ability" toggle is the server-side opt-in, matching the desktop client). PAUSE
  // rather than end, so the rest of the cost still pays without a second press once the question
  // is answered.
  if (type === 'choose_ability') return 'pause'
  return 'end'
}

/**
 * The unpaid-cost text a player should see, from the prompt's raw server message.
 *
 * `rawMessage` is `"Pay " + unpaid.getText() + "<div …>" + objectName` (ManaUtil
 * .addSpecialManaPayAbilities), so the cost is everything before the name block, minus the
 * verb — the prompt already carries the "Pay" framing, and a notice that reads "remaining
 * cost: Pay {1}{W}" is noise. (The run-on popup title "Pay {1}Amped Raptor" is the separate
 * cosmetic defect — this helper only keeps notices readable; it does not change the title.)
 */
export function unpaidCostText(rawMessage: string): string {
  const withoutNameBlock = rawMessage.split('<')[0] ?? rawMessage
  const cost = withoutNameBlock.trim()
  return cost.replace(/^Pay\s*/i, '').trim()
}

/**
 * The object a mana prompt is paying for, from the raw message's name block
 * (`<div style='font-size:11pt'>Ajani, Nacatl Pariah</div>`). Null when the server sent no name
 * block — then the client cannot tell one payment from another and must not pretend it can.
 */
export function manaPromptSubject(rawMessage: string): string | null {
  const match = /<div[^>]*>([^<]*)<\/div>/.exec(rawMessage)
  const name = match?.[1]?.trim()
  return name ? name : null
}

/** Identity of a payment — the pair a notice is valid for. */
function noticeKeyOf(rawMessage: string): string {
  return `${manaPromptSubject(rawMessage) ?? '?'}\n${unpaidCostText(rawMessage)}`
}

/**
 * The honest stop message: the reason, the cost still outstanding, and the ways out.
 * Never a silent stop (invariant 5).
 */
export function autoTapStopNotice(reason: string, remainingCost: string): string {
  return `${reason} — remaining cost: ${remainingCost || 'unknown'}. Tap a land yourself, or use Cancel Spell to give up the cast.`
}

/**
 * One step of a run: tap the single land `planAutoTap` assigns to the first unpaid pip of the
 * CURRENT cost text, or end the run with an honest notice.
 *
 * Called by the press (through {@link startAutoTapAllLands}) and by the store's prompt hook on
 * every `play_mana` re-prompt. Never sends more than one `tap_mana`, and never sends
 * `boolean_choice` (invariant 4).
 */
export function driveAutoTapStep(store: GameStore, prompt: PlayManaPrompt): void {
  const raw = prompt.rawMessage || prompt.message
  const lands = selectMyUntappedLands(store)
  const me = store.gameState?.players.find((p) => p.isMe)
  // The engine's per-source verdicts for THIS payment ride the prompt (Courtyard
  // autotap option 2). They are the ONLY authority on conditional/uncharacterised
  // sources: `undefined` (older server) means the planner keeps its rule-text-only
  // behaviour, never a guess.
  const plan = planAutoTap(lands, raw, me?.manaPool ?? null, prompt.manaPayableForThisCost)
  const remaining = unpaidCostText(raw)
  const key = noticeKeyOf(raw)

  switch (plan.kind) {
    case 'tap':
      if (!recordAutoTap(plan.cardId)) {
        // The plan wants a land this run already tapped: the server did not accept that tap and
        // state has not moved. Re-sending is the documented livelock — stop and say so.
        stopAutoTapRun(
          autoTapStopNotice(`${plan.reason}, but this press already tapped that land and the server has not moved`, remaining),
          key,
        )
        return
      }
      store.tapMana(plan.cardId)
      return
    case 'blocked':
      stopAutoTapRun(autoTapStopNotice(plan.reason, remaining), key)
      return
    case 'nothing-to-do':
      // Nothing left to tap: the pool covers the remainder, or the pips left are X / life-only.
      // End the run without a tap — the server dismisses the prompt itself once the cost is paid.
      stopAutoTapRun(`${plan.reason} — remaining cost: ${remaining || 'none'}.`, key)
      return
  }
}

/**
 * The press entry point for "Auto-Tap Lands" (both mana-payment UIs call this).
 *
 * Fail Fast: the button only exists inside a `play_mana` prompt, so anything else is a caller bug
 * and must crash loudly rather than silently do nothing (AGENTS.md).
 */
export function startAutoTapAllLands(store: GameStore): void {
  const prompt = store.actionPrompt
  if (!prompt || prompt.type !== 'play_mana') {
    throw new Error(
      `startAutoTapAllLands called without a play_mana prompt (got: ${prompt === null ? 'none' : prompt.type})`
    )
  }
  // A press is explicit consent to start over from live state: clear any previous notice and the
  // already-tapped set.
  runSubject = manaPromptSubject(prompt.rawMessage)
  emit({ active: true, tappedIds: NO_IDS, notice: null, noticeKey: null })
  driveAutoTapStep(store, prompt)
}

/**
 * Store hook: a prompt was just installed (called from `setActionPrompt` AFTER the store update).
 * Continues an active run across the server's mana re-prompts, pauses it on a mid-payment ability
 * question, and ends it on anything else (invariant 6).
 */
export function onPromptSetForAutoTapRun(store: GameStore): void {
  const prompt = store.actionPrompt
  if (!prompt) {
    // Defensive: the callers only invoke this after installing a prompt.
    stopAutoTapRun(null)
    return
  }

  // A notice quotes the payment it was computed against. Once the live prompt is a different
  // payment (other object, other remaining cost) the notice is stale UI and must go, even though
  // the RUN ended — otherwise a reason shown for one failed payment would sit over the next one.
  if (snapshot.notice !== null) {
    const key = noticeKeyOf(prompt.rawMessage || prompt.message)
    if (key !== snapshot.noticeKey) {
      emit({ active: snapshot.active, tappedIds: snapshot.tappedIds, notice: null, noticeKey: null })
    }
  }

  if (!snapshot.active) return

  const action = autoTapPromptAction(prompt.type)
  if (action === 'pause') return
  if (action === 'end') {
    stopAutoTapRun(null)
    return
  }
  if (prompt.type !== 'play_mana') {
    // Unreachable: autoTapPromptAction returns 'continue' only for play_mana.
    throw new Error(`auto-tap run asked to continue on a '${prompt.type}' prompt`)
  }
  // Invariant 6: a mana prompt for a DIFFERENT object is a different payment. Only compare when
  // both sides name an object — an absent name block is not evidence either way.
  const subject = manaPromptSubject(prompt.rawMessage)
  if (runSubject !== null && subject !== null && subject !== runSubject) {
    stopAutoTapRun(null)
    return
  }
  driveAutoTapStep(store, prompt)
}

/**
 * Store hook: the prompt was cleared or withdrawn (resolved by server state, a local hide, a new
 * game, or a build error). A run cannot outlive its prompt — ending here is what keeps a LATER
 * cast from being auto-tapped without the user asking (invariant 6).
 */
export function onPromptClearedForAutoTapRun(): void {
  if (!snapshot.active) return
  stopAutoTapRun(null)
}
