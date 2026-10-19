import type { StateCreator } from 'zustand'
import type { GameStore } from '../types'
import type {
  CardSearchCriteria,
  CardSearchResponse,
  CardSearchResult,
  CardLookupResponse,
  DeckEntry,
  LegalityResult,
  SavedDeck,
} from '@/types'
import type { ClientMessage } from '@/types'
import { BASIC_LANDS, basicLandCriteria } from '@/utils/basicLands'
import {
  RANDOM_DECK_MAX_BASICS,
  type RandomDeckSelection,
  type RandomSpellCard,
  deriveRandomDeckSeed,
  formatRandomDeckText,
  selectRandomDeck,
} from '@/utils/randomDeck'

// ── localStorage keys ──
const LS_DECKS = 'xmage.web.decks'
const LS_COLLECTION = 'xmage.web.collection'
const LS_DRAFT = 'xmage.web.deckeditor.currentDraft'

/**
 * How many ownership keys go in one `card_lookup`.
 * Must not exceed CardSearchService.MAX_LOOKUP_KEYS (200) — the proxy rejects larger
 * requests outright, so this is the page size for a big collection, not a limit.
 */
const LOOKUP_PAGE_SIZE = 200

// ── In-flight request tracking (requestId → resolver) ──
interface PendingEntry {
  resolve: (msg: { result?: unknown; error?: string }) => void
  reject: (err: Error) => void
  timeout: ReturnType<typeof setTimeout>
}
const pendingRequests = new Map<string, PendingEntry>()

function makeRequestId(): string {
  return crypto.randomUUID()
}

/** Called from messageHandlers when a deck-editor response arrives. */
export function handleDeckEditorResponse(msg: {
  type: 'card_search_result' | 'legality_result' | 'card_versions_result' | 'card_lookup_result'
  requestId: string
  result?: unknown
  error?: string
}): void {
  const entry = pendingRequests.get(msg.requestId)
  if (!entry) {
    console.error(`[deckSlice] Response for unknown requestId: ${msg.requestId} (${msg.type})`)
    return
  }
  clearTimeout(entry.timeout)
  pendingRequests.delete(msg.requestId)
  entry.resolve(msg)
}

/** Loose shape — ws may not be a GameWebSocket here to avoid a circular type import. */
interface WsLike {
  send: (msg: ClientMessage) => void
}

function sendAndAwait<T>(
  ws: WsLike | null,
  message: ClientMessage & { requestId: string },
  timeoutMs = 15_000,
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    if (!ws) {
      reject(new Error('WebSocket not connected'))
      return
    }
    const timeout = setTimeout(() => {
      pendingRequests.delete(message.requestId)
      reject(new Error(`Request ${message.type} timed out after ${timeoutMs}ms`))
    }, timeoutMs)
    pendingRequests.set(message.requestId, {
      resolve: (msg) => {
        if (msg.error) reject(new Error(msg.error))
        else resolve(msg.result as T)
      },
      reject,
      timeout,
    })
    ws.send(message)
  })
}

// ── Storage helpers ──
function loadSavedDecks(): SavedDeck[] {
  const raw = localStorage.getItem(LS_DECKS)
  if (!raw) return []
  try {
    return JSON.parse(raw) as SavedDeck[]
  } catch (e) {
    throw new Error(`Corrupt saved decks in localStorage[${LS_DECKS}]: ${(e as Error).message}`)
  }
}

function saveSavedDecks(decks: SavedDeck[]): void {
  localStorage.setItem(LS_DECKS, JSON.stringify(decks))
}

function loadCollection(): Record<string, number> {
  const raw = localStorage.getItem(LS_COLLECTION)
  if (!raw) return {}
  try {
    return JSON.parse(raw) as Record<string, number>
  } catch (e) {
    throw new Error(`Corrupt collection in localStorage[${LS_COLLECTION}]: ${(e as Error).message}`)
  }
}

function saveCollection(c: Record<string, number>): void {
  localStorage.setItem(LS_COLLECTION, JSON.stringify(c))
}

export type DeckTab = 'build' | 'collection'

export interface DeckState {
  currentDeck: {
    name: string
    cards: DeckEntry[]
    sideboard: DeckEntry[]
  }
  isDirty: boolean
  currentDeckSavedId: string | null

  savedDecks: SavedDeck[]

  editorOpen: boolean
  activeEditorTab: DeckTab

  /** Right-click menu target for a deck/sideboard card, or null when closed. */
  deckCardContextMenu: { entry: DeckEntry; sideboard: boolean; x: number; y: number } | null
  /** Art picker modal target — which card are we picking art for? */
  artPickerFor: { entry: DeckEntry; sideboard: boolean } | null

  searchCriteria: CardSearchCriteria
  searchResults: CardSearchResult[]
  searchTotal: number
  searchLoading: boolean
  searchError: string | null

  legality: LegalityResult | null
  legalityLoading: boolean
  legalityError: string | null
  legalityDeckHash: string | null

  collection: Record<string, number>

  /**
   * Resolved metadata for owned printings, keyed by `${set}:${num}`.
   * Populated by `loadCollectionCards` (card_lookup); the CollectionViewer renders
   * from this. Deliberately SEPARATE from `searchResults`: the search panel's results
   * are a query result, while this is the user's own card set — sharing one array meant
   * a search in the Build tab silently emptied the Collection tab.
   */
  collectionCards: Record<string, CardSearchResult>
  collectionLoading: boolean
  collectionError: string | null
  /** Keys the proxy could not resolve (owned printing absent from the card DB). */
  collectionMissing: string[]

  newDeck: () => void
  openDeck: (id: string) => void
  saveDeck: () => void
  saveDeckAs: (name: string) => void
  deleteDeck: (id: string) => void
  renameDeck: (name: string) => void
  /**
   * Add basic lands to the current deck's maindeck (the Add Lands… dialog).
   * `counts` is keyed by basic-land name (Plains/Island/Swamp/Mountain/Forest/
   * Wastes); lands are resolved through a real `card_search` and each count is
   * added via `addCard`, so legality + persistence behave exactly as a clicked
   * search result. Rejects (and sets `basicLandsError`) when a land cannot be
   * resolved — never a fabricated card.
   */
  addBasicLands: (counts: Record<string, number>) => Promise<void>
  /** Last error from addBasicLands, or null. Cleared on a fresh attempt. */
  basicLandsError: string | null
  addCard: (card: CardSearchResult, sideboard?: boolean, count?: number) => void
  removeCard: (name: string, setCode: string, cardNumber: string, sideboard?: boolean) => void
  setCardCount: (name: string, setCode: string, cardNumber: string, count: number, sideboard?: boolean) => void
  /** Swap one entry's (set,cardNumber) for a different printing; same count, same zone. */
  changeArt: (from: DeckEntry, to: { setCode: string; cardNumber: string; imageUrl?: string | null }, sideboard: boolean) => void
  /** Add another copy, choosing a specific printing. Equivalent to addCard with explicit set/num. */
  duplicateEntry: (entry: DeckEntry, sideboard: boolean) => void
  moveToSideboard: (name: string, setCode: string, cardNumber: string) => void
  moveToMain: (name: string, setCode: string, cardNumber: string) => void
  clearDeck: () => void
  clearSideboard: () => void
  importDeckText: (text: string, name?: string) => { ok: true } | { ok: false; error: string }
  exportDeckText: () => string

  openEditor: () => void
  closeEditor: () => void
  setEditorTab: (tab: DeckTab) => void
  setDeckCardContextMenu: (menu: { entry: DeckEntry; sideboard: boolean; x: number; y: number } | null) => void
  setArtPickerFor: (target: { entry: DeckEntry; sideboard: boolean } | null) => void

  setSearchCriteria: (patch: Partial<CardSearchCriteria>) => void
  runSearch: () => Promise<void>
  /** Search-as-you-type entry point: debounced runSearch, cancels in-flight. */
  scheduleSearch: () => void

  runLegality: (deckJson?: { name: string; cards: DeckEntry[]; sideboard: DeckEntry[] }) => Promise<void>

  addToCollection: (setCode: string, cardNumber: string, count?: number) => void
  removeFromCollection: (setCode: string, cardNumber: string, count?: number) => void
  clearCollection: () => void
  /** Resolve owned printings' metadata (card_lookup) for the CollectionViewer. */
  loadCollectionCards: () => Promise<void>
  /** Add a searched card to the collection (the missing entry point — see N5). */
  addSearchResultToCollection: (card: CardSearchResult, count?: number) => void

  /**
   * Generate a random 60-card deck from the user's collection and hand
   * it to the setup deck textarea as .dck text. Resolves owned printings'
   * metadata through the existing card_lookup path (no new proxy messages),
   * then runs the seeded generator in utils/randomDeck. Throws (Fail Fast)
   * when the collection is empty, metadata failed to load, or the pool is too
   * small for 60 cards — the caller disables the affordance and surfaces the
   * message; no partial/silent deck is ever produced.
   */
  generateRandomDeck: () => Promise<string>
  /** Last error from generateRandomDeck, or null. Cleared on a fresh attempt. */
  randomDeckError: string | null
  /** True while generateRandomDeck's card_lookup pages are in flight. */
  randomDeckLoading: boolean

  /** Fetch all printings of a card by name. Returns the versions list for the UI. */
  lookupCardVersions: (cardName: string) => Promise<CardSearchResult[]>

  /** Walk the current deck and populate missing imageUrl fields via card_versions. */
  hydrateDeckImages: () => Promise<void>

  _hydrate: () => void
}

function entryKey(e: { setCode: string; cardNumber: string }): string {
  return `${e.setCode}:${e.cardNumber}`
}

function deckToHash(d: { cards: DeckEntry[]; sideboard: DeckEntry[] }): string {
  return JSON.stringify({
    c: d.cards.map((e: DeckEntry) => [e.name, e.setCode, e.cardNumber, e.count]),
    s: d.sideboard.map((e: DeckEntry) => [e.name, e.setCode, e.cardNumber, e.count]),
  })
}

function emptyDeck(): { name: string; cards: DeckEntry[]; sideboard: DeckEntry[] } {
  return { name: 'Untitled Deck', cards: [], sideboard: [] }
}

export const createDeckSlice: StateCreator<GameStore, [], [], DeckState> = (set, get) => {
  let initialDecks: SavedDeck[] = []
  let initialCollection: Record<string, number> = {}
  let initialDraft = emptyDeck()
  let storageInitError: string | null = null
  try {
    initialDecks = loadSavedDecks()
    initialCollection = loadCollection()
    const draftRaw = localStorage.getItem(LS_DRAFT)
    if (draftRaw) {
      const parsed = JSON.parse(draftRaw) as { name: string; cards: DeckEntry[]; sideboard: DeckEntry[] }
      initialDraft = parsed
    }
  } catch (e) {
    storageInitError = (e as Error).message
    console.error('[deckSlice] Storage hydration failed:', storageInitError)
  }

  let legalityTimer: ReturnType<typeof setTimeout> | null = null
  let searchTimer: ReturnType<typeof setTimeout> | null = null
  const scheduleLegality = (): void => {
    if (legalityTimer) clearTimeout(legalityTimer)
    legalityTimer = setTimeout(() => {
      void get().runLegality()
    }, 300)
  }

  // ── Last-wins request guards (N3) ──
  // A legality_check on a 60-card deck takes seconds (measured warm ~0.4 s, cold ~7.6 s
  // on a fresh proxy). With the 300 ms debounce, an edit during a check leaves TWO requests
  // in flight; whichever RESPONDS last wins, which is the OLDER deck's result — the panel
  // then shows legality for a deck the user already changed (2026-09-12: "has only 56
  // cards" displayed for a 60-card deck for ~44 s). Same shape for search-as-you-type.
  // Each action takes a ticket and only applies its result when it is still the newest.
  let legalitySeq = 0
  let searchSeq = 0
  let collectionSeq = 0

  const persistDraft = (): void => {
    localStorage.setItem(LS_DRAFT, JSON.stringify(get().currentDeck))
  }

  const persistDecks = (): void => {
    saveSavedDecks(get().savedDecks)
  }

  const persistCollection = (): void => {
    saveCollection(get().collection)
  }

  return {
    currentDeck: initialDraft,
    isDirty: false,
    currentDeckSavedId: null,
    savedDecks: initialDecks,
    editorOpen: false,
    activeEditorTab: 'build',
    deckCardContextMenu: null,
    artPickerFor: null,
    searchCriteria: {},
    searchResults: [],
    searchTotal: 0,
    searchLoading: false,
    searchError: storageInitError,
    collectionCards: {},
    collectionLoading: false,
    collectionError: null,
    collectionMissing: [],
    legality: null,
    legalityLoading: false,
    legalityError: null,
    legalityDeckHash: null,
    basicLandsError: null,
    collection: initialCollection,
    randomDeckError: null,
    randomDeckLoading: false,

    newDeck: () => {
      set({ currentDeck: emptyDeck(), isDirty: false, currentDeckSavedId: null, legality: null, legalityDeckHash: null })
      persistDraft()
    },

    openDeck: (id) => {
      const found = get().savedDecks.find((d: SavedDeck) => d.id === id)
      if (!found) throw new Error(`Saved deck not found: ${id}`)
      set({
        currentDeck: { name: found.name, cards: [...found.cards], sideboard: [...found.sideboard] },
        isDirty: false,
        currentDeckSavedId: found.id,
        legality: null,
        legalityDeckHash: null,
      })
      persistDraft()
      scheduleLegality()

      // Eagerly resolve imageUrls for entries that carry none, exactly like
      // importDeckText does. Saved decks are a text-shaped record (the deck grid reads
      // entry.imageUrl), so a deck saved from a text import — or saved before art
      // hydration ran — reopens with no art. The editor's own backfill cannot cover
      // this: DeckEditorView's mount effect is one-shot per editor mount (didInitial
      // ref, DeckEditorView.tsx:94-104), so loading a saved deck into an ALREADY
      // mounted editor (the normal path: Open… in the right rail) never re-runs it.
      // Fire-and-forget: errors are logged per card and must not block the load.
      void get().hydrateDeckImages()
    },

    saveDeck: () => {
      const { currentDeck, currentDeckSavedId, savedDecks } = get()
      if (currentDeckSavedId) {
        const idx = savedDecks.findIndex((d: SavedDeck) => d.id === currentDeckSavedId)
        if (idx < 0) throw new Error(`Saved deck disappeared: ${currentDeckSavedId}`)
        const updated: SavedDeck = {
          ...savedDecks[idx]!,
          name: currentDeck.name,
          cards: [...currentDeck.cards],
          sideboard: [...currentDeck.sideboard],
          updatedAt: Date.now(),
        }
        const next = [...savedDecks]
        next[idx] = updated
        set({ savedDecks: next, isDirty: false })
        persistDecks()
      } else {
        get().saveDeckAs(currentDeck.name)
      }
    },

    saveDeckAs: (name) => {
      const { currentDeck, savedDecks } = get()
      const deck: SavedDeck = {
        id: crypto.randomUUID(),
        name,
        createdAt: Date.now(),
        updatedAt: Date.now(),
        cards: [...currentDeck.cards],
        sideboard: [...currentDeck.sideboard],
      }
      set({
        savedDecks: [...savedDecks, deck],
        currentDeckSavedId: deck.id,
        isDirty: false,
        currentDeck: { ...currentDeck, name },
      })
      persistDecks()
      persistDraft()
    },

    deleteDeck: (id) => {
      const { savedDecks, currentDeckSavedId } = get()
      const updates: Partial<DeckState> = { savedDecks: savedDecks.filter((d: SavedDeck) => d.id !== id) }
      if (currentDeckSavedId === id) updates.currentDeckSavedId = null
      set(updates)
      persistDecks()
    },

    renameDeck: (name) => {
      set({ currentDeck: { ...get().currentDeck, name }, isDirty: true })
      persistDraft()
    },

    addBasicLands: async (counts) => {
      const { ws } = get()
      set({ basicLandsError: null })
      const wanted = BASIC_LANDS
        .map((land) => ({ land, count: counts[land.name] ?? 0 }))
        .filter((x) => x.count > 0)
      try {
        for (const { land, count } of wanted) {
          // Resolve the printing from the server's card DB — never a fabricated
          // card. `basicLandCriteria` asks for the land by name (with the LAND
          // rarity filter except for Wastes, whose printings are not all LAND);
          // the same CardCriteria flow the swing AddLandDialog uses.
          const result = await sendAndAwait<CardSearchResponse>(ws, {
            type: 'card_search',
            requestId: makeRequestId(),
            criteria: basicLandCriteria(land),
          })
          const card = result.cards[0]
          if (!card) {
            throw new Error(
              `${land.name} is not in this server's card database — no basic land was added`,
            )
          }
          // Same entry point every other add uses, so legality and persistence
          // stay consistent (addCard schedules the check and persists the draft).
          get().addCard(card, false, count)
        }
      } catch (e) {
        // Fail Fast: the dialog shows this and stays open; nothing is silently
        // added for an unresolvable land. Lands added before the failure stand
        // (they are already in the deck and visible).
        set({ basicLandsError: (e as Error).message })
        throw e
      }
    },

    addCard: (card, sideboard = false, count = 1) => {
      const { currentDeck } = get()
      const listName = sideboard ? 'sideboard' : 'cards'
      const list = [...currentDeck[listName]]
      const idx = list.findIndex((e: DeckEntry) =>
        e.name === card.name && e.setCode === card.setCode && e.cardNumber === card.cardNumber,
      )
      if (idx >= 0) {
        list[idx] = { ...list[idx]!, count: list[idx]!.count + count }
      } else {
        list.push({
          name: card.name,
          setCode: card.setCode,
          cardNumber: card.cardNumber,
          count,
          imageUrl: card.imageUrl,
        })
      }
      set({
        currentDeck: { ...currentDeck, [listName]: list },
        isDirty: true,
      } as Partial<DeckState>)
      persistDraft()
      scheduleLegality()
    },

    removeCard: (name, setCode, cardNumber, sideboard = false) => {
      const { currentDeck } = get()
      const listName = sideboard ? 'sideboard' : 'cards'
      const list = currentDeck[listName].filter((e: DeckEntry) =>
        !(e.name === name && e.setCode === setCode && e.cardNumber === cardNumber),
      )
      set({
        currentDeck: { ...currentDeck, [listName]: list },
        isDirty: true,
      } as Partial<DeckState>)
      persistDraft()
      scheduleLegality()
    },

    changeArt: (from, to, sideboard) => {
      const { currentDeck } = get()
      const listName = sideboard ? 'sideboard' : 'cards'
      const list = [...currentDeck[listName]]
      const idx = list.findIndex((e: DeckEntry) =>
        e.name === from.name && e.setCode === from.setCode && e.cardNumber === from.cardNumber,
      )
      if (idx < 0) throw new Error(`Card not in ${sideboard ? 'sideboard' : 'deck'}: ${from.name}`)

      // Remove the source entry
      const removedCount = list[idx]!.count
      list.splice(idx, 1)

      // If the target printing already exists in this zone, merge counts
      const existingIdx = list.findIndex((e: DeckEntry) =>
        e.name === from.name && e.setCode === to.setCode && e.cardNumber === to.cardNumber,
      )
      if (existingIdx >= 0) {
        list[existingIdx] = { ...list[existingIdx]!, count: list[existingIdx]!.count + removedCount }
      } else {
        list.push({
          name: from.name,
          setCode: to.setCode,
          cardNumber: to.cardNumber,
          count: removedCount,
        })
      }
      set({
        currentDeck: { ...currentDeck, [listName]: list },
        isDirty: true,
      } as Partial<DeckState>)
      persistDraft()
      scheduleLegality()
    },

    duplicateEntry: (entry, sideboard) => {
      const { currentDeck } = get()
      const listName = sideboard ? 'sideboard' : 'cards'
      const list = [...currentDeck[listName]]
      const idx = list.findIndex((e: DeckEntry) =>
        e.name === entry.name && e.setCode === entry.setCode && e.cardNumber === entry.cardNumber,
      )
      if (idx < 0) throw new Error(`Card not in ${sideboard ? 'sideboard' : 'deck'}: ${entry.name}`)
      list[idx] = { ...list[idx]!, count: list[idx]!.count + 1 }
      set({
        currentDeck: { ...currentDeck, [listName]: list },
        isDirty: true,
      } as Partial<DeckState>)
      persistDraft()
      scheduleLegality()
    },

    setCardCount: (name, setCode, cardNumber, count, sideboard = false) => {
      if (count <= 0) {
        get().removeCard(name, setCode, cardNumber, sideboard)
        return
      }
      const { currentDeck } = get()
      const listName = sideboard ? 'sideboard' : 'cards'
      const list = [...currentDeck[listName]]
      const idx = list.findIndex((e: DeckEntry) =>
        e.name === name && e.setCode === setCode && e.cardNumber === cardNumber,
      )
      if (idx < 0) throw new Error(`Card not in deck: ${name}`)
      list[idx] = { ...list[idx]!, count }
      set({
        currentDeck: { ...currentDeck, [listName]: list },
        isDirty: true,
      } as Partial<DeckState>)
      persistDraft()
      scheduleLegality()
    },

    moveToSideboard: (name, setCode, cardNumber) => {
      const { currentDeck } = get()
      const cardEntry = currentDeck.cards.find((e: DeckEntry) =>
        e.name === name && e.setCode === setCode && e.cardNumber === cardNumber,
      )
      if (!cardEntry) throw new Error(`Card not in main deck: ${name}`)

      const newCards = currentDeck.cards.filter((e: DeckEntry) =>
        !(e.name === name && e.setCode === setCode && e.cardNumber === cardNumber),
      )
      const newSideboard = [...currentDeck.sideboard]
      const sbIdx = newSideboard.findIndex((e: DeckEntry) =>
        e.name === name && e.setCode === setCode && e.cardNumber === cardNumber,
      )
      if (sbIdx >= 0) {
        newSideboard[sbIdx] = { ...newSideboard[sbIdx]!, count: newSideboard[sbIdx]!.count + cardEntry.count }
      } else {
        newSideboard.push({ ...cardEntry })
      }
      set({
        currentDeck: { ...currentDeck, cards: newCards, sideboard: newSideboard },
        isDirty: true,
      })
      persistDraft()
      scheduleLegality()
    },

    moveToMain: (name, setCode, cardNumber) => {
      const { currentDeck } = get()
      const cardEntry = currentDeck.sideboard.find((e: DeckEntry) =>
        e.name === name && e.setCode === setCode && e.cardNumber === cardNumber,
      )
      if (!cardEntry) throw new Error(`Card not in sideboard: ${name}`)

      const newSideboard = currentDeck.sideboard.filter((e: DeckEntry) =>
        !(e.name === name && e.setCode === setCode && e.cardNumber === cardNumber),
      )
      const newCards = [...currentDeck.cards]
      const mainIdx = newCards.findIndex((e: DeckEntry) =>
        e.name === name && e.setCode === setCode && e.cardNumber === cardNumber,
      )
      if (mainIdx >= 0) {
        newCards[mainIdx] = { ...newCards[mainIdx]!, count: newCards[mainIdx]!.count + cardEntry.count }
      } else {
        newCards.push({ ...cardEntry })
      }
      set({
        currentDeck: { ...currentDeck, cards: newCards, sideboard: newSideboard },
        isDirty: true,
      })
      persistDraft()
      scheduleLegality()
    },

    clearDeck: () => {
      set({ currentDeck: { ...get().currentDeck, cards: [] }, isDirty: true })
      persistDraft()
      scheduleLegality()
    },

    clearSideboard: () => {
      set({ currentDeck: { ...get().currentDeck, sideboard: [] }, isDirty: true })
      persistDraft()
      scheduleLegality()
    },

    importDeckText: (text, name) => {
      const lines = text.split('\n')
      const cards: DeckEntry[] = []
      const sideboard: DeckEntry[] = []
      let deckName = name ?? null

      for (let i = 0; i < lines.length; i++) {
        const line = lines[i]!.trim()
        if (!line || line.startsWith('#') || line.startsWith('//')) continue
        if (line.startsWith('NAME:')) {
          deckName = deckName ?? line.substring(5).trim()
          continue
        }
        if (line.startsWith('AUTHOR:') || line.startsWith('LAYOUT ')) continue

        // Character class must exclude BOTH ']' and ':'. Written as [^\\]:] because in
        // a JS regex literal a bare `[^]` parses as an EMPTY NEGATED CLASS — i.e. "any
        // character" — so the original `[^]:]` never matched a bracket expression at
        // all: `4 [M10:150] Lightning Bolt` fell through with setCode/cardNumber null
        // and the whole `[M10:150]` glued into the card NAME, so every [SET:NUM] import
        // failed as "card not found" (found live 2026-09-16 by the SESSION B test agent,
        // reproduced directly: /[^]:]/.test(']') === false).
        //
        // Parse order matters: annotated-export lines ("1 x Akroma's Will (lcc) 125
        // [Instant] x1 [:]", Moxfield/Archidekt style) ALSO match the .dck regex below,
        // so the stricter format must be tried FIRST — falling through glues the
        // annotations into the card name and the server rejects it as "Card not found"

        // mirrors DeckLoader.java, same day).
        const annotated = /^(SB:)?\s*(\d+)(?:\s*[xX]\s+|\s+)(.+?)\s*\(([A-Za-z0-9]+)\)\s*(\d+)\s*(?:\[([^\]]+)\])?\s*(?:[xX](\d+))?\s*(?:\[:\])?\s*$/.exec(line)
        let isSideboard: boolean
        let setCode: string
        let cardNumber: string
        let cardName: string
        let count: number
        if (annotated) {
          isSideboard = annotated[1] !== undefined
          count = parseInt(annotated[2]!, 10)
          cardName = annotated[3]!.trim()
          setCode = annotated[4]!
          cardNumber = annotated[5]!
          // Leading count and trailing xN echo must agree (mirrors DeckLoader).
          if (annotated[7] !== undefined && parseInt(annotated[7], 10) !== count) {
            return { ok: false as const, error: `Line ${i + 1}: count ${count} contradicts trailing x${annotated[7]} in "${line}"` }
          }
        } else {
          const m = /^(SB:)?\s*(\d+)\s*(?:\[([^\]:]+):([^\]:]+)\])?\s*(.+?)\s*$/.exec(line)
          if (!m) return { ok: false as const, error: `Line ${i + 1} malformed: ${line}` }

          isSideboard = m[1] !== undefined
          setCode = m[3]?.trim() ?? ''
          cardNumber = m[4]?.trim() ?? ''
          cardName = m[5]!.trim()
          count = parseInt(m[2]!, 10)

          // "1x Lightning Bolt" / "4 x Grizzly Bears": the count-x separator is not
          // part of the card name. No real card name starts with a lowercase x
          // followed by whitespace ("Xantcha" is Xa-), so a leading "x " here is
          // always the separator the loose regex swallowed. (This is what produced
          // the original "x Akroma's Will (lcc) 125 [Instant]" garbage names.)
          if (cardName.length > 2 && (cardName.startsWith('x ') || cardName.startsWith('X '))) {
            cardName = cardName.slice(2).trim()
          }

          // Near-miss guard (mirrors DeckLoader): a line SHAPED like an annotated
          // export without the full structure (missing [Type], truncated bracket,
          // trailing junk) would import a garbage name like "x Akroma's Will (lcc)
          // 125" and fail later with an opaque server error. Real card names never
          // contain "(short-code) digits", so this cannot false-positive.
          if (!setCode && !cardNumber
              && /^(?:[xX]\s+)?(.+?)\s*\(([A-Za-z0-9]+)\)\s*(\d+)(\s.*)?$/.test(cardName)) {
            return { ok: false as const, error: `Line ${i + 1} looks like an annotated export ("${line}"). Use "N [SET:NUM] Card Name", plain "N Card Name", or the full form "N x Card Name (SET) NUM [Type]".` }
          }
        }

        const entry: DeckEntry = {
          name: cardName,
          setCode,
          cardNumber,
          count,
        }

        if (isSideboard) sideboard.push(entry)
        else cards.push(entry)
      }

      set({
        currentDeck: {
          name: deckName ?? get().currentDeck.name,
          cards,
          sideboard,
        },
        isDirty: true,
        currentDeckSavedId: null,
      })
      persistDraft()
      scheduleLegality()

      // Eagerly resolve imageUrls for all newly imported entries so the deck
      // grid shows art immediately. This is fire-and-forget; errors are logged
      // but don't block the import.
      void get().hydrateDeckImages()

      return { ok: true as const }
    },

    exportDeckText: () => {
      const { currentDeck } = get()
      const lines: string[] = [`NAME:${currentDeck.name}`]
      for (const e of currentDeck.cards) {
        const setPart = e.setCode && e.cardNumber ? ` [${e.setCode}:${e.cardNumber}]` : ''
        lines.push(`${e.count}${setPart} ${e.name}`)
      }
      for (const e of currentDeck.sideboard) {
        const setPart = e.setCode && e.cardNumber ? ` [${e.setCode}:${e.cardNumber}]` : ''
        lines.push(`SB: ${e.count}${setPart} ${e.name}`)
      }
      return lines.join('\n')
    },

    openEditor: () => set({ editorOpen: true }),
    closeEditor: () => set({ editorOpen: false }),
    setEditorTab: (tab) => set({ activeEditorTab: tab }),
    setDeckCardContextMenu: (menu) => set({ deckCardContextMenu: menu }),
    setArtPickerFor: (target) => set({ artPickerFor: target }),

    setSearchCriteria: (patch) => {
      set({ searchCriteria: { ...get().searchCriteria, ...patch } })
      get().scheduleSearch()
    },

    scheduleSearch: () => {
      // searchTimer is a slice-scope variable (see createDeckSlice above)
      if (searchTimer) clearTimeout(searchTimer)
      searchTimer = setTimeout(() => {
        void get().runSearch()
      }, 250)
    },

    runSearch: async () => {
      const { searchCriteria, ws } = get()
      const ticket = ++searchSeq
      set({ searchLoading: true, searchError: null })
      try {
        const result = await sendAndAwait<CardSearchResponse>(ws, {
          type: 'card_search',
          requestId: makeRequestId(),
          criteria: searchCriteria,
        })
        // Last-wins: a newer search was started while this one was in flight, so its
        // results are already stale — drop them instead of overwriting the newer ones.
        if (ticket !== searchSeq) return
        set({
          searchResults: result.cards,
          searchTotal: result.count,
          searchLoading: false,
        })
      } catch (e) {
        if (ticket !== searchSeq) return
        set({ searchError: (e as Error).message, searchLoading: false })
      }
    },

    runLegality: async (deckJson) => {
      const { ws, currentDeck, legalityDeckHash } = get()
      const target = deckJson ?? {
        name: currentDeck.name,
        cards: currentDeck.cards,
        sideboard: currentDeck.sideboard,
      }
      const newHash = deckToHash(target)
      if (newHash === legalityDeckHash && get().legality != null) {
        return
      }

      const ticket = ++legalitySeq
      set({ legalityLoading: true, legalityError: null })
      try {
        const result = await sendAndAwait<LegalityResult>(ws, {
          type: 'legality_check',
          requestId: makeRequestId(),
          deck: target,
        }, 30_000)
        // Last-wins: this deck revision is no longer current. Applying it would show
        // badges for a deck the user already changed (N3's stale "has only 56 cards"
        // on a 60-card deck). The newer request's result is still in flight and will land.
        if (ticket !== legalitySeq) return
        set({
          legality: result,
          legalityLoading: false,
          legalityDeckHash: newHash,
        })
      } catch (e) {
        if (ticket !== legalitySeq) return
        // The deck changed since this check was sent: the error describes a revision the
        // user no longer has, so keep the panel honest (no error, no stale badges) and let
        // the in-flight newer check report for the current deck.
        set({ legalityError: (e as Error).message, legalityLoading: false })
      }
    },

    addToCollection: (setCode, cardNumber, count = 1) => {
      const { collection } = get()
      const key = entryKey({ setCode, cardNumber })
      const cur = collection[key] ?? 0
      set({ collection: { ...collection, [key]: cur + count } })
      persistCollection()
    },

    removeFromCollection: (setCode, cardNumber, count = 1) => {
      const { collection } = get()
      const key = entryKey({ setCode, cardNumber })
      const cur = collection[key] ?? 0
      const next = Math.max(0, cur - count)
      const updated = { ...collection }
      if (next === 0) delete updated[key]
      else updated[key] = next
      set({ collection: updated })
      persistCollection()
    },

    clearCollection: () => {
      set({ collection: {}, collectionCards: {}, collectionMissing: [] })
      persistCollection()
    },

    loadCollectionCards: async () => {
      const { collection, ws } = get()
      // The viewer previously mounted a set-only card_search here: `{setCodes, count:200}`.
      // That is the wrong query for two independent reasons, and both are why the tab died:
      //   1. It asks for "any 200 cards in these sets", not "the cards I own" — an owned
      //      card past the 200 cap (or in a set with >200 printings) never came back, so
      //      `bySet` stayed empty for most collections even with a live response.
      //   2. The setCodes OR-chain scans every printing in every set the collection
      //      touches, to compute metadata the client already has as keys.
      // Ask by key instead: the collection IS a (setCode, cardNumber) list.
      const keys = Object.keys(collection)
        .filter((k) => (collection[k] ?? 0) > 0)
        .map((k) => {
          const sep = k.indexOf(':')
          // Fail fast: a collection key without a set/number separator is corrupt
          // localStorage, not something to silently drop.
          if (sep <= 0 || sep === k.length - 1) {
            throw new Error(`Corrupt collection key (expected SET:NUM): ${k}`)
          }
          return { setCode: k.slice(0, sep), cardNumber: k.slice(sep + 1) }
        })
      if (keys.length === 0) {
        set({ collectionCards: {}, collectionLoading: false, collectionError: null, collectionMissing: [] })
        return
      }

      const ticket = ++collectionSeq
      set({ collectionLoading: true, collectionError: null })
      try {
        // Page the keys: the proxy rejects a card_lookup above its per-request cap
        // (CardSearchService.MAX_LOOKUP_KEYS = 200). A 400-card collection is normal, so
        // sending one oversized request would fail the whole tab — the same
        // "works only for small inputs" shape this fix is removing. Chunk and merge.
        const byKey: Record<string, CardSearchResult> = {}
        const missing: string[] = []
        for (let i = 0; i < keys.length; i += LOOKUP_PAGE_SIZE) {
          const page = keys.slice(i, i + LOOKUP_PAGE_SIZE)
          const result = await sendAndAwait<CardLookupResponse>(ws, {
            type: 'card_lookup',
            requestId: makeRequestId(),
            keys: page,
          })
          // Last-wins: a newer load (or an edit that invalidated this one) supersedes
          // every page still queued — stop rather than write stale metadata.
          if (ticket !== collectionSeq) return
          for (const card of result.cards) {
            byKey[`${card.setCode}:${card.cardNumber}`] = card
          }
          missing.push(...result.missing)
        }
        // Merge, never replace: a card added from search while this was in flight
        // (addSearchResultToCollection caches its own metadata) must survive.
        set((state) => {
          const merged = { ...byKey }
          for (const [k, v] of Object.entries(state.collectionCards)) {
            if ((state.collection[k] ?? 0) > 0 && merged[k] === undefined) merged[k] = v
          }
          return {
            collectionCards: merged,
            collectionMissing: missing,
            collectionLoading: false,
          }
        })
      } catch (e) {
        if (ticket !== collectionSeq) return
        set({ collectionError: (e as Error).message, collectionLoading: false })
      }
    },

    addSearchResultToCollection: (card, count = 1) => {
      get().addToCollection(card.setCode, card.cardNumber, count)
      // Cache the metadata we already have so the new entry renders immediately —
      // and works before the next card_lookup (there is no UI path to add the FIRST
      // collection card if that requires a lookup that only runs when non-empty — N5).
      set((state) => ({
        collectionCards: { ...state.collectionCards, [`${card.setCode}:${card.cardNumber}`]: card },
        collectionMissing: state.collectionMissing.filter((k) => k !== `${card.setCode}:${card.cardNumber}`),
      }))
    },

    generateRandomDeck: async () => {
      const { collection, ws } = get()
      set({ randomDeckError: null, randomDeckLoading: true })
      try {
        // Fail Fast: an empty collection is a user-visible precondition failure,
        // never an empty deck. The control disables itself on this error.
        const keys = Object.keys(collection)
          .filter((k) => (collection[k] ?? 0) > 0)
          .map((k) => {
            const sep = k.indexOf(':')
            if (sep <= 0 || sep === k.length - 1) {
              throw new Error(`Corrupt collection key (expected SET:NUM): ${k}`)
            }
            return { raw: k, setCode: k.slice(0, sep), cardNumber: k.slice(sep + 1) }
          })
        if (keys.length === 0) {
          throw new Error(
            'Your collection is empty — add cards in the deck editor\'s Collection tab before generating a random deck',
          )
        }

        // Resolve owned printings' metadata through the SAME card_lookup path
        // loadCollectionCards uses (no new proxy messages). Collection entries
        // already carry their owned count; the metadata supplies colors/manaCost.
        const byKey: Record<string, CardSearchResult> = {}
        const missing: string[] = []
        for (let i = 0; i < keys.length; i += LOOKUP_PAGE_SIZE) {
          const page = keys.slice(i, i + LOOKUP_PAGE_SIZE).map((k) => ({ setCode: k.setCode, cardNumber: k.cardNumber }))
          const result = await sendAndAwait<CardLookupResponse>(ws, {
            type: 'card_lookup',
            requestId: makeRequestId(),
            keys: page,
          })
          for (const card of result.cards) {
            byKey[`${card.setCode}:${card.cardNumber}`] = card
          }
          missing.push(...result.missing)
        }
        // Fail Fast: an owned printing the card DB cannot resolve would silently
        // shrink the pool (its copies just vanish). Surface it instead.
        if (missing.length > 0) {
          throw new Error(
            `Your collection references printings this server's card database does not know: ${missing.join(', ')}`,
          )
        }

        // Aggregate owned printings BY NAME: one deck entry per card name (the
        // 4-copy cap is per name, not per printing), named at the printing the
        // collection actually owns. Lands are excluded from the spell pool —
        // every land in a random deck is a computed basic (pinned: "lands
        // chosen to match the pool color needs"); a random non-basic land in
        // the spell quota would produce mana the color plan never asked for.
        const byName = new Map<string, RandomSpellCard>()
        for (const key of keys) {
          const card = byKey[`${key.setCode}:${key.cardNumber}`]
          if (!card) continue // impossible after the missing check; type-narrowing only
          if (card.supertypes.includes('BASIC')) continue // basics land in the quota separately
          if (card.types.includes('LAND')) continue
          const owned = collection[key.raw] ?? 0
          const existing = byName.get(card.name)
          if (existing) {
            existing.owned += owned
            continue
          }
          byName.set(card.name, {
            name: card.name,
            setCode: key.setCode,
            cardNumber: key.cardNumber,
            owned,
            colors: card.colors,
            manaCost: card.manaCost,
          })
        }

        const spells = [...byName.values()].filter((c) => c.owned > 0)
        const seed = deriveRandomDeckSeed()
        const selection: RandomDeckSelection = selectRandomDeck(seed, spells)

        // Resolve each needed basic printing from the collection itself — never
        // a fabricated card object (mirrors addBasicLands' stance).
        const basicKeys = Object.keys(selection.basicsNeeded).map((basicName) => {
          if (!BASIC_LANDS.some((b) => b.name === basicName)) {
            throw new Error(`Random deck generated an unknown basic land name: ${basicName}`)
          }
          const ownedPrintings = keys
            .filter((k) => byKey[`${k.setCode}:${k.cardNumber}`]?.name === basicName)
            .map((k) => k.raw)
            .sort()
          if (ownedPrintings.length === 0) {
            throw new Error(
              `${basicName} is not in your collection — random decks need at least one printing of each basic land they use`,
            )
          }
          // One printing per basic (they are interchangeable for legality);
          // deterministic: the lexicographically first owned key.
          const first = ownedPrintings[0]!
          const sep = first.indexOf(':')
          return {
            name: basicName,
            setCode: first.slice(0, sep),
            cardNumber: first.slice(sep + 1),
            count: selection.basicsNeeded[basicName]!,
          }
        })
        if (basicKeys.length === 0) {
          throw new Error('Random deck selected no basic lands (internal invariant: land quota is 20)')
        }
        const expectedBasicCount = Object.values(selection.basicsNeeded)
          .reduce((a, c) => a + c, 0)
        if (basicKeys.length > RANDOM_DECK_MAX_BASICS || expectedBasicCount !== RANDOM_DECK_MAX_BASICS) {
          throw new Error(
            `Random deck basic-land quota violated: ${expectedBasicCount} lands across ${basicKeys.length} types (expected ${RANDOM_DECK_MAX_BASICS})`,
          )
        }

        const text = formatRandomDeckText(selection, basicKeys)
        set({ randomDeckLoading: false })
        return text
      } catch (e) {
        // Fail Fast: the affordance shows this and stays disabled for retry;
        // nothing is silently generated or partially filled.
        set({ randomDeckError: (e as Error).message, randomDeckLoading: false })
        throw e
      }
    },

    lookupCardVersions: async (cardName) => {
      const { ws } = get()
      const result = await sendAndAwait<import('@/types').CardVersionsResponse>(ws, {
        type: 'card_versions',
        requestId: makeRequestId(),
        cardName,
      })
      return result.versions
    },

    hydrateDeckImages: async () => {
      const { currentDeck } = get()

      // Find entries missing imageUrl, group by name to dedupe lookups
      const missing = new Map<string, { setCode: string; cardNumber: string }[]>()
      for (const zone of ['cards', 'sideboard'] as const) {
        for (const e of currentDeck[zone]) {
          if (e.imageUrl) continue
          const arr = missing.get(e.name) ?? []
          arr.push({ setCode: e.setCode, cardNumber: e.cardNumber })
          missing.set(e.name, arr)
        }
      }
      if (missing.size === 0) return

      const { ws } = get()

      // Fan out one card_versions per unique name, then write back the URL.
      for (const [name, entries] of missing) {
        try {
          const versions = await sendAndAwait<import('@/types').CardVersionsResponse>(ws, {
            type: 'card_versions',
            requestId: makeRequestId(),
            cardName: name,
          })

          // For each entry with this name, pick the version matching its set:num
          // (or the first printing as fallback) and patch the deck.
          set((state) => {
            const updated = { ...state.currentDeck }
            for (const zone of ['cards', 'sideboard'] as const) {
              updated[zone] = updated[zone].map((e: DeckEntry) => {
                if (e.name !== name || e.imageUrl) return e
                const match = versions.versions.find(
                  (v) => v.setCode === e.setCode && v.cardNumber === e.cardNumber,
                ) ?? versions.versions[0]
                if (!match?.imageUrl) return e
                return { ...e, imageUrl: match.imageUrl }
              })
            }
            return { currentDeck: updated }
          })

          // entries were only needed to group the lookup; mark used to satisfy tsc
          void entries
        } catch (err) {
          console.error(`[deckSlice] hydrateDeckImages failed for ${name}:`, err)
        }
      }
      persistDraft()
    },

    _hydrate: () => {
      set({ savedDecks: loadSavedDecks(), collection: loadCollection() })
    },
  }
}
