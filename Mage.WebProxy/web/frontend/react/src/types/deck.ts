/**
 * Deck editor types — shared between the deckSlice, components, and the
 * card_search / legality_check WS payloads.
 *
 * Mirrors the server-side JSON emitted by CardSearchService / LegalityService.
 * Field names match the Java serialization exactly.
 */

export interface DeckEntry {
  name: string
  setCode: string
  cardNumber: string
  count: number
  /** Scryfall image URL — populated when the card was fetched from the
      server (search, deck load, legality round-trip). Optional so .dck
      text imports (text-only) still work without a server round-trip. */
  imageUrl?: string | null | undefined
}

export interface SavedDeck {
  id: string                 // crypto.randomUUID()
  name: string
  createdAt: number
  updatedAt: number
  format?: string            // last-known label for picker hint
  cards: DeckEntry[]
  sideboard: DeckEntry[]
}

export interface CardSearchCriteria {
  // exactOptionalPropertyTypes is on: explicitly allow undefined for
  // cleaner Partial<> ergonomics in the search panel.
  name?: string | undefined
  rules?: string | undefined
  types?: string[] | undefined           // CardType names (CREATURE, INSTANT...)
  notTypes?: string[] | undefined
  subtypes?: string[] | undefined
  supertypes?: string[] | undefined
  rarities?: string[] | undefined        // COMMON, UNCOMMON, RARE, MYTHIC...
  setCodes?: string[] | undefined
  manaValue?: number | undefined
  colors?: string[] | undefined          // 'white'|'blue'|'black'|'red'|'green' (or single letters)
  colorless?: boolean | undefined
  start?: number | undefined
  count?: number | undefined
  sortBy?: string | undefined
}

/** CardInfo as serialized by CardSearchService.toJson. */
export interface CardSearchResult {
  name: string
  setCode: string
  cardNumber: string
  manaCost: string[]
  manaValue: number
  types: string[]
  subtypes: string[]
  supertypes: string[]
  rarity: string | null
  colors: string[]
  rules: string[]
  power?: string
  toughness?: string
  loyalty?: string
  imageUrl: string | null
}

export interface CardSearchResponse {
  cards: CardSearchResult[]
  count: number
  start: number
}

export interface LegalityFormatResult {
  format: string
  shortName: string
  status: 'legal' | 'partly' | 'illegal' | 'unknown'
  errors: Array<{ group: string; message: string; cardName?: string }>
  error?: string             // set when status === 'unknown'
}

export interface LegalityBracketGroup {
  name: string
  count: number
  cards: string[]
}

export interface LegalityBracket {
  level: number              // 1..5
  legal: boolean
  badCards: string[]
  groups: LegalityBracketGroup[]
  error?: string
}

export interface LegalityResult {
  formats: LegalityFormatResult[]
  edhPower: { level?: number; powerCards?: string[]; powerInfo?: string[]; error?: string }
  brackets: LegalityBracket[]
}

// ── Client messages (browser → proxy) ──
export interface CardSearchRequest {
  type: 'card_search'
  requestId: string
  criteria: CardSearchCriteria
}

export interface CardVersionsRequest {
  type: 'card_versions'
  requestId: string
  cardName: string
}

/** One owned printing, as keyed by `localStorage['xmage.web.collection']`. */
export interface CardLookupKey {
  setCode: string
  cardNumber: string
}

/**
 * Exact ownership-key lookup — the CollectionViewer's query shape.
 *
 * A collection is a list of owned printings, so the viewer asks for those keys directly.
 * A set-scoped `card_search` cannot express that: it returns an arbitrary slice of every
 * card in the set, capped at 200, so owned cards past the cap never render (and the
 * request itself is a full-set scan for data the client already knows).
 */
export interface CardLookupRequest {
  type: 'card_lookup'
  requestId: string
  keys: CardLookupKey[]
}

export interface CardLookupResponse {
  cards: CardSearchResult[]
  count: number
  /** Keys the proxy could not resolve — a card DB rebuilt against a different pool. */
  missing: string[]
}

export interface CardLookupResultMessage {
  type: 'card_lookup_result'
  requestId: string
  result?: CardLookupResponse
  error?: string
}

export interface LegalityCheckRequest {
  type: 'legality_check'
  requestId: string
  deck: {
    name: string
    cards: DeckEntry[]
    sideboard: DeckEntry[]
  }
}

// ── Server messages (proxy → browser) ──
export interface CardSearchResultMessage {
  type: 'card_search_result'
  requestId: string
  result?: CardSearchResponse
  error?: string
}

export interface CardVersionsResponse {
  versions: CardSearchResult[]
  count: number
  cardName: string
}

export interface CardVersionsResultMessage {
  type: 'card_versions_result'
  requestId: string
  result?: CardVersionsResponse
  error?: string
}

export interface LegalityResultMessage {
  type: 'legality_result'
  requestId: string
  result?: LegalityResult
  error?: string
}
