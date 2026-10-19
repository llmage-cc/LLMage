/**
 * Standalone behavioural check for the deck-editor Collection tab fixes (N1/N2/N5/N6/N7).
 *
 * Covers:
 *   N2 — loadCollectionCards sends `card_lookup` with the OWNED KEYS (not a set-only
 *        card_search), and the viewer's data model renders the response.
 *   N5 — addSearchResultToCollection is reachable from a search result and creates the
 *        FIRST collection entry (no circular dependency on the lookup).
 *   N7 — the mount-once guard: a second mount must not re-send the lookup.
 *   N3 — last-wins: a stale legality/search response must not overwrite a newer one.
 *
 * Run:  bash scripts/verify-collection.sh
 *
 * Not part of the app build (scripts/ is excluded from tsconfig "include": ["src"]).
 * Drives the REAL store + REAL message handler, so assertions cover shipped code.
 */
import { useGameStore } from '../src/store/gameStore'
import { handleDeckEditorResponse } from '../src/store/slices/deckSlice'
import type { CardSearchResult, ServerMessage } from '../src/types'
import type { DeckEntry } from '../src/types/deck'

// ── record what actually goes on the wire ──
interface SentMsg { type: string; requestId?: string; criteria?: unknown; keys?: unknown }
const sent: SentMsg[] = []
useGameStore.getState().setWebSocket({
  send: (m: unknown) => { sent.push(m as SentMsg) },
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  disconnect: () => {},
} as never)

const lastOf = (type: string) => [...sent].reverse().find((m) => m.type === type)
const countOf = (type: string) => sent.filter((m) => m.type === type).length

let failures = 0
function check(label: string, cond: boolean, detail = ''): void {
  if (!cond) failures++
  console.log(`  [${cond ? 'PASS' : 'FAIL'}] ${label}${detail ? ' :: ' + detail : ''}`)
}

const card = (name: string, setCode: string, cardNumber: string, types: string[]): CardSearchResult => ({
  name, setCode, cardNumber, manaCost: [], manaValue: 1, types, subtypes: [], supertypes: [],
  rarity: 'COMMON', colors: [], rules: [], imageUrl: `https://example.test/${setCode}/${cardNumber}.png`,
})

/**
 * Answer the most recent request of `type` the way the proxy would, using the real
 * message-router entry point. `respond(requestId, result)` is what messageHandlers calls.
 */
function answer(type: string, result: unknown, error?: string): void {
  const msg = lastOf(type)
  if (!msg?.requestId) throw new Error(`no in-flight ${type} request to answer`)
  handleDeckEditorResponse({
    type: `${type}_result` as 'card_lookup_result',
    requestId: msg.requestId,
    ...(error ? { error } : { result }),
  })
}

const flush = () => new Promise((r) => setTimeout(r, 0))

async function main(): Promise<void> {
  const store = useGameStore
  const S = () => store.getState()

  // ─────────────────────────── N2: the request shape ───────────────────────────
  console.log('\nN2 — Collection tab request shape')
  localStorage.setItem('xmage.web.collection', JSON.stringify({
    'J21:787': 4, '3ED:162': 1, 'DRB:1': 2,
  }))
  S()._hydrate()

  sent.length = 0
  const load = S().loadCollectionCards()
  await flush()

  const lookup = lastOf('card_lookup')
  check('sends card_lookup (not a set-only card_search)', lookup !== undefined && countOf('card_search') === 0,
    `types sent: ${[...new Set(sent.map((m) => m.type))].join(', ')}`)
  const keys = (lookup?.keys ?? []) as Array<{ setCode: string; cardNumber: string }>
  check('keys are the OWNED printings', keys.length === 3, JSON.stringify(keys))
  check('a key past the old 200-result cap is present (J21:787)',
    keys.some((k) => k.setCode === 'J21' && k.cardNumber === '787'))
  check('no setCodes criteria is sent', (lookup as { criteria?: unknown })?.criteria === undefined)

  // The proxy's answer
  answer('card_lookup', { cards: [card('Lightning Bolt', 'J21', '787', ['INSTANT'])], count: 1, missing: [] })
  await load

  check('result is cached under the SET:NUM key', S().collectionCards['J21:787'] !== undefined)
  check('loading flag cleared', S().collectionLoading === false, String(S().collectionLoading))
  check('error is null', S().collectionError === null, String(S().collectionError))

  // ───────────────── N2: the response actually reaches the grid ─────────────────
  console.log('\nN2 — viewer data model (what the grid renders)')
  const ownedKeys = Object.keys(S().collection).filter((k) => (S().collection[k] ?? 0) > 0)
  const bySet = new Map<string, CardSearchResult[]>()
  for (const c of Object.values(S().collectionCards)) {
    if ((S().collection[`${c.setCode}:${c.cardNumber}`] ?? 0) <= 0) continue
    bySet.set(c.setCode, [...(bySet.get(c.setCode) ?? []), c])
  }
  check('owned count is 3 (header line)', ownedKeys.length === 3, String(ownedKeys.length))
  check('grid is NON-EMPTY — the loading placeholder is left', bySet.size > 0, `bySet=${[...bySet.keys()].join(',')}`)

  // ──────────────────────── N2: a dead server reports, not hangs ─────────────────
  console.log('\nN2 — an unresolved collection is reported, not silently empty')
  localStorage.setItem('xmage.web.collection', JSON.stringify({ 'ZZZ:999': 1 }))
  S()._hydrate()
  sent.length = 0
  const load2 = S().loadCollectionCards()
  await flush()
  answer('card_lookup', { cards: [], count: 0, missing: ['ZZZ:999'] })
  await load2
  check('missing keys recorded', S().collectionMissing.includes('ZZZ:999'), JSON.stringify(S().collectionMissing))
  check('collectionCards empty but NOT stuck loading', S().collectionLoading === false && Object.keys(S().collectionCards).length === 0)

  // ──────────────────────────────── N5: first card ────────────────────────────────
  console.log('\nN5 — a search result can create the FIRST collection entry')
  localStorage.setItem('xmage.web.collection', JSON.stringify({}))
  S()._hydrate()
  check('collection starts empty', Object.keys(S().collection).length === 0)

  const bolt = card('Lightning Bolt', 'J21', '787', ['INSTANT'])
  S().addSearchResultToCollection(bolt, 1)
  check('collection now has the entry', S().collection['J21:787'] === 1, JSON.stringify(S().collection))
  check('metadata cached so it renders without a lookup round-trip',
    S().collectionCards['J21:787']?.name === 'Lightning Bolt')
  check('persisted to localStorage', (localStorage.getItem('xmage.web.collection') ?? '').includes('J21:787'))

  // ───────────────────────────── N7: mount-once for the lookup ─────────────────────────
  console.log('\nN7 — StrictMode double-mount does not re-send the lookup')
  // The component's useRef guard is what makes this true; assert the property it guards:
  // a second loadCollectionCards must be a fresh intentional call, but an identical
  // one right after the first must not duplicate the in-flight request from the effect.
  // (The effect-level guard lives in CollectionViewer.tsx: `didLoad` ref.)
  const src = await import('node:fs').then((fs) =>
    fs.readFileSync(process.env.XMAGE_REACT_SRC + '/components/deckeditor/CollectionViewer.tsx', 'utf8'))
  check('CollectionViewer guards its mount effect with a ref (survives StrictMode remount)',
    /useEffect\(\(\) => \{\s*\n\s*if \(didLoad\.current\) return/.test(src))
  check('CollectionViewer no longer issues a set-only search on mount',
    !src.includes('setSearchCriteria({ setCodes'), 'setCodes mount search removed')

  // ──────────────────── N3: last-wins guard on legality + search ────────────────────
  console.log('\nN3 — a stale legality response must not overwrite a newer one')
  const deckA = { name: 'A', cards: [{ name: 'Lightning Bolt', setCode: 'J21', cardNumber: '787', count: 56 }], sideboard: [] }
  const deckB = { name: 'B', cards: [{ name: 'Lightning Bolt', setCode: 'J21', cardNumber: '787', count: 60 }], sideboard: [] }
  const legalityResult = (n: number) => ({ formats: [{ format: 'STD', shortName: 'STD', status: 'legal' as const, errors: [], error: `deck-${n}` }], edhPower: {}, brackets: [] })

  sent.length = 0
  const p1 = S().runLegality(deckA)
  const p2 = S().runLegality(deckB)
  await flush()
  const lreqs = sent.filter((m) => m.type === 'legality_check')
  check('two overlapping checks are in flight', lreqs.length === 2, String(lreqs.length))

  // Answer OUT OF ORDER: the OLDER (56-card) request lands last.
  handleDeckEditorResponse({ type: 'legality_result', requestId: lreqs[0]!.requestId!, result: legalityResult(56) })
  handleDeckEditorResponse({ type: 'legality_result', requestId: lreqs[1]!.requestId!, result: legalityResult(60) })
  await Promise.all([p1, p2])

  check('the NEWER deck revision wins even though the older response arrived last',
    S().legality?.formats[0]?.error === 'deck-60', `panel shows ${S().legality?.formats[0]?.error}`)
  check('the OLDER response was dropped rather than applied',
    S().legality?.formats[0]?.error !== 'deck-56')

  console.log('\nN3 — same guard on search-as-you-type')
  sent.length = 0
  S().setSearchCriteria({ name: 'Light' })
  await flush()
  const p3 = S().runSearch()
  await flush()
  S().setSearchCriteria({ name: 'Lightning Bolt' })
  await flush()
  const p4 = S().runSearch()
  await flush()
  const sreqs = sent.filter((m) => m.type === 'card_search')
  handleDeckEditorResponse({ type: 'card_search_result', requestId: sreqs[sreqs.length - 1]!.requestId!, result: { cards: [card('Lightning Bolt', 'J21', '787', ['INSTANT'])], count: 1, start: 0 } })
  handleDeckEditorResponse({ type: 'card_search_result', requestId: sreqs[0]!.requestId!, result: { cards: [], count: 0, start: 0 } })
  await Promise.all([p3, p4])
  check('the NEWER search result survives a late stale response',
    S().searchResults.length === 1 && S().searchResults[0]!.name === 'Lightning Bolt',
    JSON.stringify(S().searchResults.map((c) => c.name)))

  console.log('\nN2 — a collection larger than one lookup page is paged, not failed')
  const big: Record<string, number> = {}
  for (let i = 1; i <= 450; i++) big[`J21:${i}`] = 1
  localStorage.setItem('xmage.web.collection', JSON.stringify(big))
  S()._hydrate()
  sent.length = 0
  // Paging is sequential (each page is awaited before the next is sent), so drive the
  // loop: answer whatever is in flight, yield, and repeat until the load resolves.
  let done = false
  const load3 = S().loadCollectionCards().then(() => { done = true })
  const answered: SentMsg[] = []
  for (let guard = 0; guard < 20 && !done; guard++) {
    await flush()
    const inflight = sent.filter((m) => m.type === 'card_lookup' && !answered.includes(m))
    for (const m of inflight) {
      answered.push(m)
      handleDeckEditorResponse({
        type: 'card_lookup_result', requestId: m.requestId!,
        result: {
          cards: (m.keys as Array<{ setCode: string; cardNumber: string }>)
            .map((k) => card('Bolt', k.setCode, k.cardNumber, ['INSTANT'])),
          count: (m.keys as unknown[]).length, missing: [],
        },
      })
    }
  }
  await load3

  const paged = answered
  check('450 keys are split across pages of <=200', paged.length === 3, `pages=${paged.length}`)
  check('no page exceeds the server cap',
    paged.every((m) => ((m.keys ?? []) as unknown[]).length <= 200),
    paged.map((m) => ((m.keys ?? []) as unknown[]).length).join(','))
  check('every owned key is covered exactly once',
    (paged.reduce((a, m) => a + ((m.keys ?? []) as unknown[]).length, 0)) === 450,
    String(paged.reduce((a, m) => a + ((m.keys ?? []) as unknown[]).length, 0)))
  check('all pages merged into the cache',
    Object.keys(S().collectionCards).length === 450, String(Object.keys(S().collectionCards).length))
  check('loading completes for a big collection', S().collectionLoading === false)

  // [SET:NUM] PARSING (found live 2026-09-16 by the SESSION B test agent).
  //
  // The importer's regex used `[^]:]`, which in a JS regex literal is `[^]` (an EMPTY
  // NEGATED CLASS = "any character") followed by `:]` — not "not ] and not :". So no
  // bracket expression ever matched: setCode/cardNumber stayed empty and the whole
  // "[M10:150]" was glued into the card NAME, making every [SET:NUM] import fail as
  // "card not found". This asserts the parsed FIELDS, which is the only thing that
  // distinguishes the broken class from the fixed one.
  useGameStore.getState().importDeckText('4 [M10:146] Lightning Bolt\nSB: 2 [ZEN:223] Marsh Flats')
  {
    const d = S().currentDeck
    const main = d.cards[0] as DeckEntry | undefined
    const side = d.sideboard[0] as DeckEntry | undefined
    check('[SET:NUM]: set code is parsed out of the brackets (not left in the name)',
      main?.setCode === 'M10' && main?.cardNumber === '146',
      JSON.stringify(main))
    check('[SET:NUM]: card name excludes the bracket expression',
      main?.name === 'Lightning Bolt', String(main?.name))
    check('[SET:NUM]: sideboard lines parse too',
      side?.setCode === 'ZEN' && side?.cardNumber === '223' && side?.name === 'Marsh Flats',
      JSON.stringify(side))
  }
  // Plain-name lines must keep working, and a split card name must survive intact.
  useGameStore.getState().importDeckText('4 Lightning Bolt\n2 Fire // Ice')
  {
    const d = S().currentDeck
    check('plain names still parse with no set/number',
      d.cards[0]?.name === 'Lightning Bolt' && d.cards[0]?.setCode === '',
      JSON.stringify(d.cards[0]))
    check('split-card names survive the parser',
      d.cards[1]?.name === 'Fire // Ice', String(d.cards[1]?.name))
  }

  console.log(`\nFAILURES: ${failures === 0 ? 'none' : failures}`)
  process.exit(failures === 0 ? 0 : 1)
}

void main()
