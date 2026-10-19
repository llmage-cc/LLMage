/**
 * Card type classification helpers — ported from game-renderer.js.
 * Pure functions, no DOM, no React.
 */
import type { Card, Permanent } from '@/types'

/** A card-like object or a plain string name. */
type CardLike = Card | Permanent | { name: string; typeLine?: string; power?: string; toughness?: string; loyalty?: string; defense?: string } | string

function isString(val: unknown): val is string {
  return typeof val === 'string'
}

/** A battlefield permanent, as far as auto-tap is concerned. */
export interface ManaSource {
  id?: string | null
  name?: string
  manaProduction?: string[] | undefined
}

/**
 * The engine's per-source verdicts for the CURRENT payment: permanent id →
 * "this source's mana qualifies for the cost being paid right now". Produced by
 * `HumanPlayer.addManaPayableForThisCost` (engine-side `ConditionalMana.apply`
 * gate, the same check `PlayerImpl.canPayMinimumManaCost` uses), forwarded by
 * `WebProxyClient.handleGamePlayMana` and carried on the prompt.
 *
 * `undefined` = the server did not evaluate (older proxy/server, or a prompt
 * built by a harness). An empty object is NOT the same thing: it means the
 * engine evaluated and qualified nothing.
 */
export type PayableForThisCost = Record<string, boolean> | undefined

/**
 * Whether the ENGINE has stated that this source's mana can pay the current
 * unpaid cost. Only an explicit `true` qualifies — `undefined`, a missing id
 * and `false` all mean "not known to be payable", which the auto-tapper must
 * treat as "do not tap" (invariant 2 below).
 */
export function engineSaysPayableForThisCost(
  card: CardLike | ManaSource | null | undefined,
  payableForThisCost: PayableForThisCost,
): boolean {
  if (isString(card) || !card || !payableForThisCost) return false
  const id = (card as ManaSource).id
  if (typeof id !== 'string' || id.length === 0) return false
  return payableForThisCost[id] === true
}

/**
 * Colours a permanent's tap-only mana abilities are KNOWN to produce, from the
 * engine's own data as serialized by `GameStateSerializer.manaProductionOf`
 * (derived from the ability text XMage itself renders, never from the card name).
 *
 * Returns `null` for "unknown" — the permanent has no serialized mana production.
 * Unknown means the auto-tapper must not touch it: tapping it cannot be known to
 * pay the cost, and an off-colour tap is pure loss (it consumed the land and the
 * sequence then cancelled the cast — the livelock, and the
 * wrong-colour tap).
 *
 * The previous implementation was a hardcoded basic-lands-only name table, which
 * returned null for every dual/nonbasic and whose callers collapsed that null to
 * "the first untapped land of any colour". That is deleted: no name table can be
 * correct, because the frontend has no rule data and the names alone do not
 * determine production.
 */
export function knownManaProduction(card: CardLike | ManaSource | null | undefined): string[] | null {
  if (isString(card) || !card) return null
  const production = (card as ManaSource).manaProduction
  if (!production || production.length === 0) return null
  return production
}

/**
 * Mana-payment helpers shared by PlayManaPrompt.tsx and DecisionPopup.tsx.
 *
 * Both prompts used to duplicate a `/\{([WUBRG])\}/g` regex that could not see hybrid
 * ({B/G}) or phyrexian ({2/B}, {B/P}) pips, and both fell back to "tap the first
 * untapped land of any colour" — the wrong-colour tap and, when the list emptied, the
 * `booleanChoice(false)` cast-cancel livelock (2026-09-12,
 * 2026-09-13, 2026-09-14).
 *
 * The rules here are the acceptance invariant for auto-tap:
 *   1. never tap a permanent not KNOWN to produce a needed colour, UNLESS the ENGINE
 *      itself has flagged that permanent payable for the exact cost being paid
 *      (`HumanPlayer.addManaPayableForThisCost` → `manaPayableForThisCost` — the only
 *      way a conditional any-colour source such as Secluded Courtyard may ever be
 * auto-tapped; , option 2), and
 *   2. when no such permanent exists, report it and tap NOTHING (the caller must not
 *      cancel the cast as a fallback).
 */

const ALL_COLOURS = ['W', 'U', 'B', 'R', 'G'] as const

/** One pip parsed from the unpaid-cost text. */
interface ManaRequirement {
  /** Colour letters that can pay this pip by producing mana (may be empty). */
  colours: string[]
  /** True when generic mana can pay it (numeric side of {2/B}, or a plain {2}). */
  genericOk: boolean
  /** True when life can pay it (the "P" side of {B/P}). */
  lifeOk: boolean
  /**
   * True when the pip imposes no obligation we can act on: X may be 0 and the prompt
   * does not carry the announced value, so auto-tap must not spend a land on it.
   */
  optional: boolean
  /**
   * True when the pip is a real mana requirement we cannot characterise with the data
   * available (snow {S}). Auto-tap must refuse loudly rather than tap something blind.
   */
  unsatisfiable: boolean
}

/**
 * Parse every pip in a mana-cost message. Splits each `{...}` group on '/' so hybrid
 * ({B/G}) and phyrexian ({2/B}, {B/P}) forms are seen; a group that is not a mana
 * symbol (e.g. prose in braces) yields no requirement instead of a wrong one.
 *
 * Exported for testability; the components use `planAutoTap`.
 */
export function parseManaRequirements(message: string): ManaRequirement[] {
  const requirements: ManaRequirement[] = []
  const re = /\{([^}]+)\}/g
  let match: RegExpExecArray | null
  while ((match = re.exec(message)) !== null) {
    const group = match[1]
    if (group === undefined) continue
    const sides = group.split('/')
    const colours: string[] = []
    let genericOk = false
    let lifeOk = false
    let optional = false
    let unsatisfiable = false
    let isManaSymbol = true
    for (const side of sides) {
      if ((ALL_COLOURS as readonly string[]).includes(side)) {
        colours.push(side)
      } else if (side === 'C') {
        colours.push('C')
      } else if (/^\d+$/.test(side)) {
        // Numeric side: generic mana can pay it ({2}, or the {2} of {2/B}).
        genericOk = true
      } else if (side === 'P') {
        // Phyrexian life side ({B/P}).
        lifeOk = true
      } else if (side === 'X') {
        // X may be 0; its announced value is not in this text. No obligation.
        optional = true
      } else if (side === 'S') {
        // Snow mana: a real requirement, but no land's serialized production tells us
        // it can pay snow. Refuse loudly instead of tapping something blind.
        unsatisfiable = true
      } else {
        // Not a mana symbol (e.g. "{this}" prose). Ignore the whole group rather than
        // inventing a requirement from it.
        isManaSymbol = false
      }
    }
    if (!isManaSymbol) continue
    requirements.push({ colours, genericOk, lifeOk, optional, unsatisfiable })
  }
  return requirements
}

/** Outcome of planning one auto-tap step. */
export type AutoTapPlan =
  | { kind: 'tap'; cardId: string; reason: string }
  | { kind: 'blocked'; reason: string }
  | { kind: 'nothing-to-do'; reason: string }

/** Mana currently in my pool — `Player.manaPool` from GameStateSerializer. */
export interface PoolCounts {
  red: number
  blue: number
  green: number
  white: number
  black: number
  colorless: number
}

const POOL_KEY_BY_COLOUR: Record<string, keyof PoolCounts> = {
  W: 'white',
  U: 'blue',
  B: 'black',
  R: 'red',
  G: 'green',
  C: 'colorless',
}

/** A pip still needing a land after pool mana has been spent on it. */
interface UnpaidPip {
  colours: string[]
  genericOk: boolean
  lifeOk: boolean
  optional: boolean
  unsatisfiable: boolean
}

/**
 * Spend pool mana on the parsed pips, returning the pips that still need a land.
 * Pool mana is real state, so a cost already covered by the pool must not be reported
 * as "blocked" (and must not cause a tap at all).
 *
 * Coloured pips are settled FIRST. A generic pip can be paid by any colour, so letting
 * it consume the pool before the coloured pip is resolved would strand the coloured pip
 * and report a false "blocked" (e.g. pool {B} + cost {1}{B} would tap a land needlessly,
 * and pool {B} + cost {1}{B} with no lands would wrongly block).
 */
function applyPoolToPips(pips: ManaRequirement[], pool: PoolCounts | null | undefined): UnpaidPip[] {
  const left: UnpaidPip[] = []
  const available: PoolCounts | null = pool
    ? { red: pool.red, blue: pool.blue, green: pool.green, white: pool.white, black: pool.black, colorless: pool.colorless }
    : null
  const takeAny = (): boolean => {
    if (!available) return false
    const keys: (keyof PoolCounts)[] = ['white', 'blue', 'black', 'red', 'green', 'colorless']
    const key = keys.find((k) => available[k] > 0)
    if (key === undefined) return false
    available[key] -= 1
    return true
  }

  const colouredFirst = [...pips].sort((a, b) => Number(b.colours.length > 0) - Number(a.colours.length > 0))
  for (const pip of colouredFirst) {
    // A matching pool mana pays a coloured pip outright.
    const matched = pip.colours.find((col) => {
      const key = POOL_KEY_BY_COLOUR[col]
      return key !== undefined && available != null && available[key] > 0
    })
    if (matched !== undefined) {
      const key = POOL_KEY_BY_COLOUR[matched]
      if (key !== undefined && available) available[key] -= 1
      continue
    }
    // No pool mana of the right colour: generic/colourless pool mana can still pay the
    // generic branch of this pip ({2/B} paid with {2} from the pool).
    if (pip.genericOk && takeAny()) continue
    left.push({
      colours: pip.colours,
      genericOk: pip.genericOk,
      lifeOk: pip.lifeOk,
      optional: pip.optional,
      unsatisfiable: pip.unsatisfiable,
    })
  }
  return left
}

/**
 * Plan the next auto-tap step for the given untapped lands and unpaid-cost message.
 *
 * Returns at most ONE tap: after each `tap_mana` the server re-prompts with the
 * remaining cost, so the plan is re-derived from live state on every click.
 *
 * The whole remaining cost is validated before anything is tapped. If any pip cannot be
 * paid by (a) pool mana, (b) an untapped land known to produce the needed colour,
 * (c) an untapped land the ENGINE has confirmed payable for this exact cost, (d) the
 * generic branch, or (e) life, the plan is `blocked` and NO tap is sent — the caller must
 * show the honest state and never cancel the cast as a fallback.
 *
 * `payableForThisCost` are the engine's per-source verdicts for THIS payment
 * (`HumanPlayer.addManaPayableForThisCost`). They exist because a CONDITIONAL source
 * (Secluded Courtyard, Cavern of Souls …) cannot be characterised from its rule text —
 * its production depends on the object being paid. Such a source is tapped ONLY when the
 * engine marked it `true` for this exact cost; every other unknown source stays untapped
 * exactly as before.
 */
export function planAutoTap(
  untappedLands: readonly ManaSource[],
  message: string,
  pool?: PoolCounts | null,
  payableForThisCost?: PayableForThisCost,
): AutoTapPlan {
  const requirements = parseManaRequirements(message)
  if (requirements.length === 0) {
    return {
      kind: 'nothing-to-do',
      reason: 'could not read a mana cost from the prompt — tap a land yourself, or cancel',
    }
  }

  const unpaid = applyPoolToPips(requirements, pool)
  const actionable = unpaid.filter((p) => !p.optional)
  if (actionable.length === 0) {
    // Either the pool already covers the cost, or the only pips left are X (which may be
    // 0 and whose value this prompt does not carry).
    return { kind: 'nothing-to-do', reason: 'the remaining cost is already covered by your mana pool (or is X)' }
  }

  const candidates: { id: string; name: string | undefined; manaProduction: string[] }[] = []
  // Sources the ENGINE has confirmed can pay the CURRENT cost. They may have no
  // characterisable production at all (conditional any-colour) — that is exactly why the
  // verdict has to come from the engine. `false`/absent never lands here.
  const engineConfirmed: { id: string; name: string | undefined }[] = []
  for (const c of untappedLands) {
    if (typeof c.id !== 'string' || c.id.length === 0) continue
    const production = knownManaProduction(c)
    if (production !== null) {
      candidates.push({ id: c.id, name: c.name, manaProduction: production })
    }
    if (engineSaysPayableForThisCost(c, payableForThisCost)) {
      engineConfirmed.push({ id: c.id, name: c.name })
    }
  }

  // Assign a distinct land to every unpaid pip. Coloured pips are resolved before
  // generic ones so a dual is not spent on a {1} that a Plains could pay.
  const ordered = [...unpaid].sort((a, b) => Number(b.colours.length > 0) - Number(a.colours.length > 0))
  const used = new Set<string>()
  const assignment = new Map<UnpaidPip, { id: string; reason: string }>()
  for (const pip of ordered) {
    if (pip.unsatisfiable || (pip.colours.length === 0 && !pip.genericOk && !pip.lifeOk && !pip.optional)) {
      return {
        kind: 'blocked',
        reason: 'this cost contains a symbol no untapped land is known to pay (tap a land yourself, or cancel)',
      }
    }
    if (pip.optional) {
      // X cost with an unknown announced value: do not spend a land on it.
      continue
    }
    // Most specialised land first (fewest colours), so {W}{U} duals are kept for pips
    // that need them. A land ALREADY assigned in this run may take another pip without
    // a second tap when ONE of its activations produces every colour that pip needs
    // (Azorius Chancery {T}: Add {W}{U} settles {W} and {U} of Morska {G}{W}{U} with a
    // single tap — the engine spends the whole activation at once).
    const matched = candidates
      .filter((c) => pip.colours.some((col) => c.manaProduction.includes(col)))
      .filter((c) => used.has(c.id) ? pip.colours.every((col) => c.manaProduction.includes(col)) : true)
    const pick = matched.sort((a, b) => a.manaProduction.length - b.manaProduction.length)[0]
    if (pick) {
      used.add(pick.id)
      assignment.set(pip, { id: pick.id, reason: `${pick.name ?? 'land'} produces ${pip.colours.join('/')}` })
      continue
    }
    const anyUnused = candidates.find((c) => !used.has(c.id))
    if (anyUnused && pip.colours.length > 0 && anyUnused.manaProduction.some((col) => pip.colours.includes(col))) {
      // A land that produces SOME of a multi-colour pip can settle it (the engine pays the
      // rest of that pip from the pool, and one activation spends everything it makes at
      // once) — but a land producing NONE of the pip's colours must NOT be tapped for it:
      // tapping a Mountain for {W} is the off-colour tap defect this harness guards.
      used.add(anyUnused.id)
      assignment.set(pip, { id: anyUnused.id, reason: `${anyUnused.name ?? 'land'} produces ${anyUnused.manaProduction.join('/')} toward ${pip.colours.join('/')}` })
      continue
    }
    // The engine's own verdict: this source's mana qualifies for THIS cost even though the
    // rule text alone could not say so (conditional any-colour). Reached only when no
    // characterisable land can serve the pip — a known source is always preferred, and a
    // conditional one is thus kept for a pip only it can pay. Only an explicit engine
    // `true` reaches engineConfirmed: an uncharacterised source the engine did not vouch
    // for stays untouched, exactly as before.
    if (pip.genericOk && anyUnused) {
      used.add(anyUnused.id)
      assignment.set(pip, { id: anyUnused.id, reason: `${anyUnused.name ?? 'land'} can pay the generic part` })
      continue
    }
    const enginePick = engineConfirmed.find((c) => !used.has(c.id))
    if (enginePick) {
      used.add(enginePick.id)
      assignment.set(pip, {
        id: enginePick.id,
        reason: `${enginePick.name ?? 'land'} is confirmed by the engine to pay this cost`,
      })
      continue
    }
    if (pip.genericOk) {
      return { kind: 'blocked', reason: 'no untapped land is known to produce mana for the generic part' }
    }
    if (pip.lifeOk) {
      // e.g. {B/P} with no black source — payable with 2 life, not by tapping blind.
      continue
    }
    return {
      kind: 'blocked',
      reason: `no untapped land is known to produce ${pip.colours.join(' or ')}`,
    }
  }

  // Tap the land assigned to the first still-actionable pip in cost order (left to right).
  for (const pip of unpaid) {
    if (pip.optional) continue
    const target = assignment.get(pip)
    if (target) {
      return { kind: 'tap', cardId: target.id, reason: target.reason }
    }
  }

  return {
    kind: 'nothing-to-do',
    reason: 'every remaining pip is payable without tapping a land (life or X = 0)',
  }
}

/**
 * Check whether a card/permanent is a TOKEN.
 *
 * Uses the engine's own flag (`Permanent.token`, from `PermanentView.isToken()`)
 * whenever it is present — that is the single source of truth.
 *
 * The name-based test is only a fallback for objects that carry no flag (plain
 * `Card`s, stack items, replay payloads). It is NOT sufficient on its own: XMage
 * declares 116 token names WITHOUT the " Token" suffix — "Ajani's Pridemate",
 * "Akroma, Angel of Wrath", "Black Lotus", "Baneslayer Angel", "Treasure",
 * "Food", "Clue" among them. Relying on the suffix misclassified every one of
 * those as a real card, which cost them the token art fallback chain and left
 * them rendering through the generic (wrong) Scryfall named lookup.
 */
export function isTokenCard(card: CardLike): boolean {
  if (isString(card) || !card) return false
  // Engine flag wins when present — `false` is authoritative, not "unknown".
  const flagged = (card as { token?: boolean }).token
  if (typeof flagged === 'boolean') return flagged
  const name = card.name || ''
  return name.includes(' Token') || name.includes(' token')
}

/**
 * Check if a card should display power/toughness.
 * XMage sets power/toughness to "0" for all cards, not just creatures.
 * Only show P/T for creatures and vehicles (matching desktop client's showPT()).
 */
export function hasPT(card: CardLike): boolean {
  if (isString(card) || !card) return false
  const tl = card.typeLine
  if (tl) {
    return /\bCreature\b/.test(tl) || /\bVehicle\b/.test(tl)
  }
  // Fallback: if no typeLine, check for non-zero power/toughness
  return (card.power != null && card.power !== '0') || (card.toughness != null && card.toughness !== '0')
}

/** Format power/toughness as "p/t". */
export function formatPT(card: CardLike): string {
  if (isString(card) || !card) return '?/?'
  const p = card.power != null ? card.power : '?'
  const t = card.toughness != null ? card.toughness : '?'
  return `${p}/${t}`
}

/**
 * Check if a card is likely a land.
 * Uses typeLine when available; falls back to heuristic for old snapshots.
 */
export function isLikelyLand(card: CardLike): boolean {
  if (isString(card) || !card) return false
  const tl = card.typeLine
  if (tl) {
    return /\bLand\b/.test(tl)
  }
  // Fallback for snapshots without typeLine:
  // Creatures have P/T, planeswalkers have loyalty, battles have defense
  if (hasPT(card) || card.loyalty || card.defense) return false
  if (isTokenCard(card)) return false
  return true
}

/** Check if a card is a creature. */
export function isCreature(card: CardLike): boolean {
  if (isString(card) || !card) return false
  if (hasPT(card)) return true
  const tl = card.typeLine
  if (tl) return /\bCreature\b/.test(tl)
  return false
}