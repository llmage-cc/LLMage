/**
 * Random deck generator — the web parity point for the desktop client's
 * "Generate a random deck" button (Mage.Client TournamentPlayerPanel.btnGenerate
 * → DeckGenerator / DeckGeneratorPool).
 *
 * Pinned design (implement, do not re-derive):
 *  - The deck is built ONLY from the user's fetched collection (the deck
 *    editor's existing collection data path — no new proxy messages). The
 *    desktop generator searches the whole local card DB; that difference is
 *    the point of the web control.
 *  - 60-card maindeck, max 4 copies of any non-basic card name (also capped
 *    by how many the user actually owns).
 *  - Basic lands: at most 20 total across the five basic types (the ≤20 pin
 *    overrides the desktop's DEFAULT_LAND_PERCENTAGE=41 → 24 lands, because
 *    the web pool deliberately has no non-basic-land search: lands are basics
 *    only, chosen to match the selected spells' colored mana pips).
 *  - Deterministic seeded PRNG: mulberry32, seeded from Date.now() XOR
 *    crypto.getRandomValues. Same seed + same collection snapshot ⇒ the same
 *    deck. No user-facing seed input — regenerate for a new deck.
 *  - Colors are derived from the pool (the desktop dialog asks the user for
 *    colors; the web control has no color picker — the candidate color set
 *    with the largest usable spell capacity wins, deterministically).
 *
 * Everything here is pure: the deckSlice resolves owned printings' metadata
 * through the existing card_lookup path and hands plain records to
 * selectRandomDeck, so the selection logic stays free of store/network deps.
 */

export const RANDOM_DECK_SIZE = 60
/** Non-basic (spell) card quota: 60 cards minus the 20-basic land cap. */
export const RANDOM_DECK_SPELL_TARGET = 40
export const RANDOM_DECK_MAX_PER_NAME = 4
export const RANDOM_DECK_MAX_BASICS = 20

type DeckColor = 'white' | 'blue' | 'black' | 'red' | 'green'

const DECK_COLORS: DeckColor[] = ['white', 'blue', 'black', 'red', 'green']

/** Single-letter mana code per color, as it appears inside mana-cost braces. */
const COLOR_LETTER: Record<DeckColor, string> = {
  white: 'W',
  blue: 'U',
  black: 'B',
  red: 'R',
  green: 'G',
}

/** The basic land name for a color — desktop parity: DeckGeneratorPool.getBasicLandName. */
const BASIC_BY_COLOR: Record<DeckColor, string> = {
  white: 'Plains',
  blue: 'Island',
  black: 'Swamp',
  red: 'Mountain',
  green: 'Forest',
}

/**
 * Candidate color sets, in a fixed deterministic order (the five mono colors,
 * then the ten pairs). The candidate with the largest usable spell capacity
 * wins; ties keep the earlier candidate, so the same collection snapshot
 * always chooses the same colors. Three-plus-color pools are deliberately not
 * offered: at ≤4 copies per name they almost always fall below the 40-spell
 * minimum, and the desktop's default dialog starts at two colors too.
 */
const COLOR_SET_CANDIDATES: DeckColor[][] = [
  ...DECK_COLORS.map((c) => [c]),
  ['white', 'blue'],
  ['white', 'black'],
  ['white', 'red'],
  ['white', 'green'],
  ['blue', 'black'],
  ['blue', 'red'],
  ['blue', 'green'],
  ['black', 'red'],
  ['black', 'green'],
  ['red', 'green'],
]

/** One owned non-basic card, aggregated by NAME across its owned printings. */
export interface RandomSpellCard {
  name: string
  /** The printing the deck entry will name (lexicographically first owned key). */
  setCode: string
  cardNumber: string
  /** Total owned copies of this name (may exceed 4; the generator caps at 4). */
  owned: number
  /** Server color strings: white/blue/black/red/green/colorless (or empty). */
  colors: string[]
  /** Mana-cost strings as the server serializes them: "{2}{W}{W}". */
  manaCost: string[]
}

export interface RandomDeckMainEntry {
  name: string
  setCode: string
  cardNumber: string
  count: number
}

export interface RandomDeckBasicEntry {
  name: string
  setCode: string
  cardNumber: string
  count: number
}

export interface RandomDeckSelection {
  /** Deck name line for the .dck text — carries the seed so a deck is reproducible. */
  name: string
  chosenColors: DeckColor[]
  main: RandomDeckMainEntry[]
  /** Basic land name → count; values sum to exactly RANDOM_DECK_MAX_BASICS. */
  basicsNeeded: Record<string, number>
}

/**
 * Seed derivation: Date.now() mixed with crypto.getRandomValues (pinned).
 * >>> 0 keeps the value an unsigned 32-bit integer for mulberry32.
 */
export function deriveRandomDeckSeed(): number {
  const buf = new Uint32Array(1)
  crypto.getRandomValues(buf)
  const rnd = buf[0]
  if (rnd === undefined) {
    throw new Error('crypto.getRandomValues returned no data — cannot seed the random deck generator')
  }
  return ((Date.now() & 0xffffffff) ^ rnd) >>> 0
}

/**
 * mulberry32 — a tiny seeded PRNG with well-mixed output. The deck must be
 * reproducible per seed (pinned), so Math.random() is out.
 */
export function mulberry32(seed: number): () => number {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/** A card fits the chosen colors if it is colorless or all of its colors are chosen. */
function fitsColors(card: RandomSpellCard, chosen: readonly string[]): boolean {
  return card.colors.every((c) => c === 'colorless' || chosen.includes(c))
}

/** Fisher–Yates over index list, driven by the seeded rng (deterministic). */
function shuffledOrder(n: number, rng: () => number): number[] {
  const idx: number[] = []
  for (let i = 0; i < n; i++) idx.push(i)
  for (let i = n - 1; i > 0; i--) {
    const j = Math.floor(rng() * (i + 1))
    const tmp = idx[i]!
    idx[i] = idx[j]!
    idx[j] = tmp
  }
  return idx
}

/**
 * Basic-land split — desktop parity with DeckGenerator.addBasicLands: repeatedly
 * add a basic for the color whose share of produced lands most lags its share
 * of colored mana pips in the selected spells. First-max wins ties, matching the
 * desktop's strict `>` comparison over its fixed color order. Zero colored pips
 * (generic/colorless spells only) splits the quota evenly across the chosen
 * colors so the deck still has something to cast with.
 */
export function distributeBasicLands(
  pips: Record<DeckColor, number>,
  chosen: readonly DeckColor[],
  landTotal: number,
): Record<string, number> {
  const result: Record<string, number> = {}
  for (const c of chosen) result[c] = 0
  if (chosen.length === 0 || landTotal <= 0) return result

  const pipTotal = chosen.reduce((sum, c) => sum + pips[c], 0)
  if (pipTotal === 0) {
    const base = Math.floor(landTotal / chosen.length)
    let rest = landTotal - base * chosen.length
    for (const c of chosen) {
      result[c] = base + (rest > 0 ? 1 : 0)
      if (rest > 0) rest--
    }
    return result
  }

  const produced: Record<DeckColor, number> = { white: 0, blue: 0, black: 0, red: 0, green: 0 }
  let colorTotal = 0
  let remaining = landTotal
  while (remaining > 0) {
    let bestColor: DeckColor | null = null
    let bestScore = -Infinity
    for (const c of chosen) {
      const neededPct = (pips[c] / pipTotal) * 100
      if (neededPct <= 0) continue
      const current = produced[c]
      const currentPct = current > 0 ? (current / colorTotal) * 100 : 0
      const score = neededPct - currentPct
      if (score > bestScore) {
        bestScore = score
        bestColor = c
      }
    }
    if (bestColor === null) {
      // Unreachable when pipTotal > 0 — fail loudly rather than spin forever.
      throw new Error('Random deck: no color needs basic lands but land slots remain')
    }
    result[bestColor] = result[bestColor]! + 1
    produced[bestColor]++
    colorTotal++
    remaining--
  }
  return result
}

/**
 * Pure selection core: choose the deck's colors from the pool, fill the spell
 * quota from the shuffled fitting cards, count the selected spells' colored
 * mana pips, and split the basic-land quota over the chosen colors.
 *
 * Throws (Fail Fast) when no color pool can supply the 40 spell copies — the
 * caller surfaces that message verbatim; no partial or silently undersized
 * deck is ever returned.
 */
export function selectRandomDeck(seed: number, spells: RandomSpellCard[]): RandomDeckSelection {
  let chosen: DeckColor[] | null = null
  let bestCapacity = 0
  for (const candidate of COLOR_SET_CANDIDATES) {
    let capacity = 0
    for (const card of spells) {
      if (fitsColors(card, candidate)) capacity += Math.min(RANDOM_DECK_MAX_PER_NAME, card.owned)
    }
    if (capacity > bestCapacity) {
      bestCapacity = capacity
      chosen = candidate
    }
  }
  if (chosen === null || bestCapacity < RANDOM_DECK_SPELL_TARGET) {
    throw new Error(
      `Not enough cards in your collection to build a ${RANDOM_DECK_SIZE}-card random deck: ` +
      `the largest usable color pool holds ${bestCapacity} of the required ${RANDOM_DECK_SPELL_TARGET} spell copies ` +
      `(max ${RANDOM_DECK_MAX_PER_NAME} copies per card name). Own more cards via the deck editor's Collection tab.`,
    )
  }
  const picked: DeckColor[] = chosen

  const rng = mulberry32(seed)
  const fitting = spells.filter((card) => fitsColors(card, picked))
  const pips: Record<DeckColor, number> = { white: 0, blue: 0, black: 0, red: 0, green: 0 }

  const main: RandomDeckMainEntry[] = []
  let need = RANDOM_DECK_SPELL_TARGET
  for (const idx of shuffledOrder(fitting.length, rng)) {
    if (need <= 0) break
    const card = fitting[idx]!
    const take = Math.min(RANDOM_DECK_MAX_PER_NAME, card.owned, need)
    if (take <= 0) continue
    main.push({ name: card.name, setCode: card.setCode, cardNumber: card.cardNumber, count: take })
    need -= take
    // Colored mana pips of the SELECTED spells drive the basic-land split
    // (desktop parity: DeckGeneratorPool.calculateSpellColorPercentages). Each
    // brace token counts once per chosen color it contains, so hybrid
    // "{W/U}" and Phyrexian "{W/P}" pips feed both/their color as on desktop.
    for (const cost of card.manaCost) {
      for (const token of cost.split(/[{}]/)) {
        if (!token) continue
        for (const color of picked) {
          if (token.includes(COLOR_LETTER[color])) pips[color]++
        }
      }
    }
  }
  if (need > 0) {
    // The capacity check above guarantees enough copies; reaching here is an
    // internal invariant violation, not a user-facing state.
    throw new Error(
      `Random deck generation fell short of ${RANDOM_DECK_SPELL_TARGET} spells (internal invariant, capacity ${bestCapacity})`,
    )
  }

  const basicsNeeded: Record<string, number> = {}
  for (const [color, count] of Object.entries(distributeBasicLands(pips, picked, RANDOM_DECK_MAX_BASICS))) {
    const basicName = BASIC_BY_COLOR[color as DeckColor]
    if (!basicName || count <= 0) continue
    basicsNeeded[basicName] = count
  }

  return {
    name: `Random Deck (seed ${seed})`,
    chosenColors: [...picked],
    main,
    basicsNeeded,
  }
}

/**
 * Serialize the selection to .dck text — the exact line format
 * exportDeckText / SavedDeckPicker emit ("N [SET:NUM] Card Name"), so the
 * existing join_table/create_table deck path parses it unchanged.
 */
export function formatRandomDeckText(selection: RandomDeckSelection, basics: RandomDeckBasicEntry[]): string {
  const lines: string[] = [`NAME:${selection.name}`]
  for (const e of selection.main) {
    lines.push(`${e.count} [${e.setCode}:${e.cardNumber}] ${e.name}`)
  }
  for (const b of basics) {
    lines.push(`${b.count} [${b.setCode}:${b.cardNumber}] ${b.name}`)
  }
  return lines.join('\n')
}