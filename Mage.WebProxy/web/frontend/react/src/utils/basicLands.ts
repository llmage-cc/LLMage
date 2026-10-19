/**
 * Basic lands offered by the deck editor's Add Lands… dialog.
 *
 * Mirrors the swing client's AddLandDialog (Mage.Client/src/main/java/mage/client/
 * dialog/AddLandDialog.java): the five basics plus Wastes, each added by NAME.
 *
 * WHY NAMES AND NOT (set, number): the Java dialog resolves the printing at add
 * time through `CardCriteria.rarities(Rarity.LAND).name(<land>)` against the
 * server's card DB, so the client never invents a printing. The web client has
 * the same primitive — `card_search` with `{name, rarities:['LAND'], count:1}`
 * (see CardSearchService.search) — so the dialog asks the DB and adds whatever
 * the server actually has. Hardcoding a set/number here would be a fabricated
 * card object the moment a set rotates out of the DB; a real search result
 * cannot be wrong about what exists.
 *
 * Verified against the live card DB (92,684 rows, dev copy of cards.h2):
 *   Plains 811, Island 818, Swamp 806, Mountain 838, Forest 821, Wastes 16
 *   printings; all six carry supertypes BASIC.
 *
 * `rarity` is the value the search needs; `wastes` flags the one land whose
 * printings are NOT all Rarity.LAND (Wastes has COMMON/RARE/SPECIAL rows too —
 * 15 of its 16 printings), so its lookup must not filter on rarity or it would
 * resolve to a single printing. This mirrors AddLandDialog's own fallback, which
 * re-queries without the rarity filter when the first search comes back empty.
 */
export interface BasicLandOption {
  /** Exact card name as stored in the card DB — the search key. */
  name: string
  /** Single-letter mana code shown as the row icon (Wastes = colourless). */
  symbol: string
  /** True for Wastes: its printings are not all Rarity.LAND, so skip the filter. */
  anyRarity?: boolean
}

export const BASIC_LANDS: BasicLandOption[] = [
  { name: 'Plains', symbol: 'W' },
  { name: 'Island', symbol: 'U' },
  { name: 'Swamp', symbol: 'B' },
  { name: 'Mountain', symbol: 'R' },
  { name: 'Forest', symbol: 'G' },
  // Wastes is colourless ({C}) and is classed LAND on some printings but
  // COMMON/RARE/SPECIAL on others — see BasicLandOption.anyRarity.
  { name: 'Wastes', symbol: 'C', anyRarity: true },
]

/** The search criteria for one basic land — the shape CardSearchService accepts. */
export function basicLandCriteria(land: BasicLandOption): {
  name: string
  rarities?: string[]
  count: number
} {
  return land.anyRarity
    ? { name: land.name, count: 1 }
    : { name: land.name, rarities: ['LAND'], count: 1 }
}

/** Highest count the dialog's steppers/input accept for one land. */
export const BASIC_LAND_MAX = 99

/**
 * The dialog's count math, kept pure so it is testable without a browser.
 * `next` is whatever the control produced (a stepper delta result, a typed
 * string parsed by the caller, or NaN) — it is floored to an integer and
 * clamped into [0, BASIC_LAND_MAX] so a blank or garbage input becomes 0
 * rather than NaN propagating into the deck.
 */
export function clampLandCount(next: number): number {
  if (!Number.isFinite(next)) return 0
  return Math.max(0, Math.min(BASIC_LAND_MAX, Math.trunc(next)))
}
