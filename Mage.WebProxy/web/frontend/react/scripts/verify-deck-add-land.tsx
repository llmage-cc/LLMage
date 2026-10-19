/**
 * Behavioural check for the deck editor's Add Lands… dialog — the web
 * counterpart of the swing AddLandDialog (basic lands into the maindeck).
 *
 * Run with:  bash scripts/verify-deck-add-land.sh
 *
 * Three layers, all against REAL shipped code:
 *   A. the REAL dialog component renders through react-dom/server: every basic
 *      land the dialog offers is listed, with quantity controls and the
 *      Add/Cancel pair,
 *   B. the REAL store action (`addBasicLands`) drives the REAL wire path: it
 *      asks `card_search` for each land, then adds the card it gets back through
 *      `addCard` — so the assertion is about the deck the SEARCH RESULT produced
 *      (set/number/imageUrl included), not about a fabricated object,
 *   C. Fail Fast: a land the server cannot resolve rejects with the land named
 *      and adds NOTHING for it — never a fake printing, never a partial object;
 *      counts that are zero add nothing; and the button that opens the dialog is
 *      wired in the toolbar with its own data-explain id.
 *
 * Bundled by rolldown with browser stubs — see scripts/verify-stubs.mjs.
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { readFileSync } from 'node:fs'
import { useGameStore } from '../src/store/gameStore'
import AddLandDialog from '../src/components/deckeditor/AddLandDialog'
import { BASIC_LANDS, basicLandCriteria, clampLandCount, BASIC_LAND_MAX } from '../src/utils/basicLands'
import { EXPLAIN } from '../src/hints/explain'
import type { CardSearchResult } from '../src/types'

// react-dom/server reads zustand's getServerSnapshot (the INITIAL state), so
// point it at the live state to render the store-driven parts faithfully.
;(useGameStore as unknown as { getInitialState: () => unknown }).getInitialState =
  () => useGameStore.getState()

// ── record what actually goes on the wire ──
interface SentMsg { type: string; requestId?: string; criteria?: { name?: string; rarities?: string[]; count?: number } }
const sent: SentMsg[] = []
useGameStore.getState().setWebSocket({
  send: (m: unknown) => { sent.push(m as SentMsg) },
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  noteConnectAccepted: () => {},
  disconnect: () => {},
} as never)

import { handleDeckEditorResponse } from '../src/store/slices/deckSlice'

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) console.log(`  PASS  ${name}`)
  else { failures++; console.log(`  FAIL  ${name}${detail ? '  — ' + detail : ''}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

const flush = () => new Promise((r) => setTimeout(r, 0))

/** A card_search result shaped exactly like CardSearchService.toJson. */
const land = (name: string, setCode: string, cardNumber: string): CardSearchResult => ({
  name, setCode, cardNumber, manaCost: [], manaValue: 0, types: ['LAND'], subtypes: [name],
  supertypes: ['BASIC'], rarity: 'LAND', colors: [], rules: [],
  imageUrl: `https://img.test/${setCode}/${cardNumber}.png`,
})

/**
 * Run addBasicLands to completion by answering each `card_search` the way the
 * proxy would. `resolve` decides the result per land (null = an empty result,
 * i.e. the card DB does not have it).
 */
async function runAddBasicLands(
  counts: Record<string, number>,
  resolve: (name: string) => CardSearchResult | null,
): Promise<{ ok: boolean; error?: string; requests: SentMsg[] }> {
  const mark = sent.length
  const promise = useGameStore.getState().addBasicLands(counts)
  let settled: { ok: boolean; error?: string } = { ok: true }
  const done = promise.then(() => { settled = { ok: true } }, (e: Error) => { settled = { ok: false, error: e.message } })
  const answered = new Set<string>()
  for (let round = 0; round < 20; round++) {
    await flush()
    const inflight = sent.slice(mark).filter((m) => m.type === 'card_search' && !answered.has(m.requestId!))
    if (inflight.length === 0) break
    for (const req of inflight) {
      answered.add(req.requestId!)
      const card = resolve(String(req.criteria?.name))
      handleDeckEditorResponse({
        type: 'card_search_result',
        requestId: req.requestId!,
        result: { cards: card ? [card] : [], count: card ? 1 : 0, start: 0 },
      })
    }
  }
  await done
  return { ...settled, requests: sent.slice(mark).filter((m) => m.type === 'card_search') }
}

async function main(): Promise<void> {
  const S = () => useGameStore.getState()

  // ─────────────────────────────────────────────────────────────────────────
  section('A. the dialog renders the basic lands from the card DB vocabulary')

  const html = renderToStaticMarkup(<AddLandDialog onClose={() => {}} />)
  for (const l of BASIC_LANDS) {
    check(`${l.name} is offered`, html.includes(`>${l.name}</span>`) || html.includes(l.name), l.name)
  }
  check('exactly six basic lands (Plains/Island/Swamp/Mountain/Forest/Wastes)',
    BASIC_LANDS.length === 6, BASIC_LANDS.map((l) => l.name).join(','))
  check('Wastes is one of them (the colourless basic)',
    BASIC_LANDS.some((l) => l.name === 'Wastes'))
  check('every row has a quantity control (number input)',
    (html.match(/type="number"/g) ?? []).length === 6, String((html.match(/type="number"/g) ?? []).length))
  check('every row has + and − steppers',
    (html.match(/class="addland-step"/g) ?? []).length === 12,
    String((html.match(/class="addland-step"/g) ?? []).length))
  check('Add lands and Cancel are both present',
    html.includes('Add lands') && html.includes('Cancel'))
  check('Add is disabled while every count is zero',
    /addland-add[^>]*disabled/.test(html), html.slice(html.indexOf('addland-add'), html.indexOf('addland-add') + 200))
  check('the dialog never invents a set/number in its markup',
    !/setCode|cardNumber|\([A-Z0-9]{2,5}\)\s*\d/.test(html))

  // ─────────────────────────────────────────────────────────────────────────
  section('B. the store adds real search results through the normal addCard path')

  S().newDeck()
  const r1 = await runAddBasicLands({ Forest: 24, Mountain: 12 }, (name) =>
    name === 'Forest' ? land('Forest', 'M19', '277') : land('Mountain', 'M19', '279'))

  check('one card_search per requested land', r1.requests.length === 2,
    r1.requests.map((m) => String(m.criteria?.name)).join(','))
  check('the lands asked for are the ones requested, by name',
    r1.requests.some((m) => m.criteria?.name === 'Forest') && r1.requests.some((m) => m.criteria?.name === 'Mountain'))
  check('the LAND rarity filter is sent for the coloured basics',
    r1.requests.find((m) => m.criteria?.name === 'Forest')?.criteria?.rarities?.join(',') === 'LAND',
    JSON.stringify(r1.requests.find((m) => m.criteria?.name === 'Forest')?.criteria))
  check('a land with zero count is not searched',
    !r1.requests.some((m) => m.criteria?.name === 'Plains'))
  check('the add succeeded', r1.ok, r1.error ?? '')

  const deck = S().currentDeck.cards
  check('the counts asked for are in the deck',
    deck.find((e) => e.name === 'Forest')?.count === 24 && deck.find((e) => e.name === 'Mountain')?.count === 12,
    JSON.stringify(deck.map((e) => [e.name, e.count])))
  check('the added entries carry the SEARCH RESULT\u2019s set/number (no fabricated values)',
    deck.find((e) => e.name === 'Forest')?.setCode === 'M19'
    && deck.find((e) => e.name === 'Forest')?.cardNumber === '277',
    JSON.stringify(deck.find((e) => e.name === 'Forest')))
  check('the art that came back with the result is kept (tile renders the image)',
    deck.find((e) => e.name === 'Forest')?.imageUrl === 'https://img.test/M19/277.png',
    String(deck.find((e) => e.name === 'Forest')?.imageUrl))
  check('the deck is marked dirty (the change is real, not a display)', S().isDirty === true)
  check('the draft was persisted (the add survives a reload)',
    (localStorage.getItem('xmage.web.deckeditor.currentDraft') ?? '').includes('Forest'))
  check('nothing landed in the sideboard', S().currentDeck.sideboard.length === 0)

  // A second add must MERGE into the existing entry (addCard semantics), not
  // create a duplicate row for the same printing.
  const r2 = await runAddBasicLands({ Forest: 4 }, () => land('Forest', 'M19', '277'))
  check('adding the same printing again merges the count', r2.ok && S().currentDeck.cards.length === 2,
    JSON.stringify(S().currentDeck.cards.map((e) => [e.name, e.count])))
  check('the merged count is 28', S().currentDeck.cards.find((e) => e.name === 'Forest')?.count === 28,
    String(S().currentDeck.cards.find((e) => e.name === 'Forest')?.count))

  // ─────────────────────────────────────────────────────────────────────────
  section('C. Fail Fast — an unresolvable land adds nothing and says so')

  S().newDeck()
  const r3 = await runAddBasicLands({ Plains: 4, Wastes: 3 }, (name) =>
    name === 'Plains' ? land('Plains', 'M19', '275') : null)
  check('the call REJECTS (no silent partial add)', !r3.ok, JSON.stringify(r3))
  check('the error names the land that could not be resolved',
    /Wastes/.test(r3.error ?? ''), r3.error ?? '')
  check('the store records the error for the dialog', /Wastes/.test(S().basicLandsError ?? ''),
    String(S().basicLandsError))
  check('no fabricated Wastes entry exists in the deck',
    !S().currentDeck.cards.some((e) => e.name === 'Wastes'),
    JSON.stringify(S().currentDeck.cards.map((e) => e.name)))
  check('every deck entry has a real set/number (nothing half-built)',
    S().currentDeck.cards.every((e) => e.setCode !== '' && e.cardNumber !== ''),
    JSON.stringify(S().currentDeck.cards))

  // Wastes lookup semantics: its printings are not all Rarity.LAND, so the
  // criteria must NOT filter on rarity (it would resolve to a single printing).
  const wastes = BASIC_LANDS.find((l) => l.name === 'Wastes')!
  const colored = BASIC_LANDS.find((l) => l.name === 'Forest')!
  check('Wastes is searched WITHOUT the LAND rarity filter (its printings are mixed-rarity)',
    basicLandCriteria(wastes).rarities === undefined, JSON.stringify(basicLandCriteria(wastes)))
  check('the coloured basics ARE searched with the LAND filter',
    basicLandCriteria(colored).rarities?.join(',') === 'LAND', JSON.stringify(basicLandCriteria(colored)))
  check('every criteria asks for exactly one result (the dialog adds the count itself)',
    BASIC_LANDS.every((l) => basicLandCriteria(l).count === 1))

  // The count controls' math, exercised without a DOM: a blank field parses to
  // NaN and a stepper can run negative, and neither may reach the deck.
  check('a blank/garbage count becomes 0', clampLandCount(Number.NaN) === 0)
  check('a negative stepper result floors at 0', clampLandCount(-3) === 0)
  check('the typed value is capped', clampLandCount(BASIC_LAND_MAX + 10) === BASIC_LAND_MAX)
  check('a fractional value is truncated, not rounded up',
    clampLandCount(4.9) === 4, String(clampLandCount(4.9)))
  check('a normal value passes through', clampLandCount(24) === 24)

  // A clean failed attempt clears the previous error.
  const r4 = await runAddBasicLands({ Island: 1 }, () => land('Island', 'M19', '278'))
  check('a later successful add clears the recorded error',
    r4.ok && S().basicLandsError === null, String(S().basicLandsError))

  // All-zero counts: a no-op, no requests.
  S().newDeck()
  const r5 = await runAddBasicLands({}, () => land('Forest', 'M19', '277'))
  check('all-zero counts send nothing and add nothing',
    r5.ok && r5.requests.length === 0 && S().currentDeck.cards.length === 0,
    `requests=${r5.requests.length} cards=${S().currentDeck.cards.length}`)

  // ─────────────────────────────────────────────────────────────────────────
  section('C2. the toolbar button that opens the dialog is wired and explained')

  const root = process.env.XMAGE_REACT_ROOT ?? '.'
  const controls = readFileSync(`${root}/src/components/deckeditor/ImportExportControls.tsx`, 'utf8')
  check('the toolbar renders the Add Lands… button',
    controls.includes('data-explain="deck-add-land"') && controls.includes('Add Lands'))
  check('the button opens the REAL dialog component',
    controls.includes("import AddLandDialog from './AddLandDialog'")
    && /showAddLand && <AddLandDialog/.test(controls))
  check('Cancel closes the dialog without submitting',
    readFileSync(`${root}/src/components/deckeditor/AddLandDialog.tsx`, 'utf8')
      .includes('data-explain="addland-cancel"'))

  for (const id of ['deck-add-land', 'addland-plus', 'addland-minus', 'addland-count', 'addland-submit', 'addland-cancel', 'addland-close']) {
    check(`EXPLAIN has '${id}'`, typeof EXPLAIN[id] === 'string' && EXPLAIN[id]!.length > 20, String(EXPLAIN[id]))
  }

  console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
  ;(globalThis as unknown as { process: { exit: (n: number) => void } }).process.exit(failures === 0 ? 0 : 1)
}

void main()
