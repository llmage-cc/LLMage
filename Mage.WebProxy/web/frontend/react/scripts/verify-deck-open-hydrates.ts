/**
 * Store-level check: "saved decks load without art".
 *
 * Run with:  bash scripts/verify-deck-open-hydrates.sh
 *
 * Drives the REAL store (`openDeck` on the REAL deckSlice) the way
 * ImportExportControls does — the Open… list in the right rail calls
 * `openDeck(d.id)` on an ALREADY MOUNTED editor, which is exactly the path the
 * editor's own mount effect cannot cover.
 *
 * What is asserted:
 *   (a) openDeck loads the saved deck's entries verbatim (name/set/num/count),
 *   (b) openDeck fires hydrateDeckImages — a `card_versions` request goes on the
 *       wire for every entry missing imageUrl, deduped by name, including
 *       sideboard entries,
 *   (c) the resolved URLs are written back into currentDeck (the deck grid reads
 *       entry.imageUrl, so this is what makes the art appear),
 *   (d) the hydrated URLs are persisted to the draft (a reload keeps the art),
 *   (e) a saved deck that ALREADY carries art sends nothing (no wasted round-trips),
 *   (f) the fix does not depend on DeckEditorView's one-shot mount effect — this
 *       harness never mounts a component, so (b)+(c) prove the hydration comes
 *       from openDeck itself.
 *
 * Not part of the app build (scripts/ is outside tsconfig "include": ["src"]).
 * Bundled by rolldown with browser stubs — see scripts/verify-stubs.mjs.
 */
import { useGameStore } from '../src/store/gameStore'
import { handleDeckEditorResponse } from '../src/store/slices/deckSlice'
import type { CardSearchResult } from '../src/types'
import type { DeckEntry, SavedDeck } from '../src/types/deck'

const LS_DECKS = 'xmage.web.decks'
const LS_DRAFT = 'xmage.web.deckeditor.currentDraft'

// ── record what actually goes on the wire ──
interface SentMsg { type: string; requestId?: string; cardName?: string; criteria?: unknown }
const sent: SentMsg[] = []
useGameStore.getState().setWebSocket({
  send: (m: unknown) => { sent.push(m as SentMsg) },
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  noteConnectAccepted: () => {},
  disconnect: () => {},
} as never)

const since = (n: number, type: string) => sent.slice(n).filter((m) => m.type === type)

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) console.log(`  PASS  ${name}`)
  else { failures++; console.log(`  FAIL  ${name}${detail ? '  — ' + detail : ''}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

/** The store's own flush: message handlers resolve promises on the microtask queue. */
const flush = () => new Promise((r) => setTimeout(r, 0))

const version = (name: string, setCode: string, cardNumber: string, imageUrl: string | null): CardSearchResult => ({
  name, setCode, cardNumber, manaCost: [], manaValue: 0, types: ['LAND'], subtypes: [], supertypes: [],
  rarity: 'LAND', colors: [], rules: [], imageUrl,
})

/** The proxy's answer to an in-flight request (success or error — the real router entry point). */
function answer(type: string, requestId: string, result: unknown, error?: string): void {
  handleDeckEditorResponse({
    type: `${type}_result` as 'card_versions_result',
    requestId,
    ...(error ? { error } : { result }),
  })
}

function seedDeck(deck: SavedDeck): void {
  localStorage.setItem(LS_DECKS, JSON.stringify([deck]))
  localStorage.removeItem(LS_DRAFT)
  useGameStore.getState()._hydrate()
}

const savedDeck = (
  id: string, name: string, cards: DeckEntry[], sideboard: DeckEntry[] = [],
): SavedDeck => ({ id, name, createdAt: 1, updatedAt: 2, cards, sideboard })

async function main(): Promise<void> {
  const S = () => useGameStore.getState()

  // ─────────────────────────────────────────────────────────────────────────
  section('openDeck on a text-import-shaped saved deck hydrates art')

  seedDeck(savedDeck('deck-plain', 'Mono Green', [
    { name: 'Forest', setCode: 'M19', cardNumber: '277', count: 24 },
    { name: 'Grizzly Bears', setCode: 'M19', cardNumber: '176', count: 4 },
  ], [
    { name: 'Forest', setCode: 'M19', cardNumber: '277', count: 2 },
  ]))

  check('the seeded deck carries NO imageUrl (the reported symptom)',
    S().savedDecks[0]!.cards.every((e) => !e.imageUrl),
    JSON.stringify(S().savedDecks[0]!.cards.map((e) => e.imageUrl)))

  // Baseline: count requests before the call so the assertion is about THIS openDeck.
  const mark = sent.length
  S().openDeck('deck-plain')
  await flush()

  const hydrations = since(mark, 'card_versions')
  check('openDeck sends card_versions (hydrateDeckImages ran)',
    hydrations.length > 0,
    `requests after openDeck: ${sent.slice(mark).map((m) => m.type).join(',') || '(none)'}`)

  // hydrateDeckImages looks cards up ONE AT A TIME (each `sendAndAwait` is
  // awaited inside the loop), so a single flush sees only the first request.
  // Pump: answer whatever is in flight, yield, repeat until the walk stalls —
  // exactly how a real proxy would interleave with the browser.
  const answered = new Set<string>()
  for (let round = 0; round < 10; round++) {
    await flush()
    const inflight = sent.filter((m) => m.type === 'card_versions' && !answered.has(m.requestId!))
    if (inflight.length === 0) break
    for (const req of inflight) {
      answered.add(req.requestId!)
      answer('card_versions', req.requestId!, {
        versions: [
          version(String(req.cardName), 'M19', req.cardName === 'Forest' ? '277' : '176', `https://img.test/${req.cardName}.png`),
        ],
        count: 1,
        cardName: req.cardName,
      })
    }
  }
  await flush()

  const allHydrations = since(mark, 'card_versions')
  check('one request per unique NAME, not per entry (Forest deduped across zones)',
    allHydrations.length === 2 && allHydrations.filter((m) => m.cardName === 'Forest').length === 1,
    allHydrations.map((m) => String(m.cardName)).join(','))
  check('the sideboard-only name is included',
    allHydrations.some((m) => m.cardName === 'Grizzly Bears') && allHydrations.some((m) => m.cardName === 'Forest'))

  const mainEntries = S().currentDeck.cards
  const sbEntries = S().currentDeck.sideboard
  check('maindeck entries got imageUrl written back (this is the fix)',
    mainEntries.every((e) => !!e.imageUrl), JSON.stringify(mainEntries.map((e) => e.imageUrl)))
  check('sideboard entries got imageUrl too',
    sbEntries.every((e) => !!e.imageUrl), JSON.stringify(sbEntries.map((e) => e.imageUrl)))
  check('the matched set/number art is used, not just any printing',
    mainEntries.find((e) => e.name === 'Forest')?.imageUrl === 'https://img.test/Forest.png',
    String(mainEntries.find((e) => e.name === 'Forest')?.imageUrl))
  check('the deck contents are otherwise untouched (name/set/num/count preserved)',
    mainEntries[0]!.name === 'Forest' && mainEntries[0]!.setCode === 'M19'
    && mainEntries[0]!.cardNumber === '277' && mainEntries[0]!.count === 24,
    JSON.stringify(mainEntries[0]))
  check('the saved deck was NOT mutated by the hydration (only the working draft)',
    S().savedDecks[0]!.cards.every((e) => !e.imageUrl),
    JSON.stringify(S().savedDecks[0]!.cards.map((e) => e.imageUrl)))

  const draftRaw = localStorage.getItem(LS_DRAFT) ?? ''
  check('hydrated URLs are persisted to the draft (art survives a reload)',
    draftRaw.includes('https://img.test/Forest.png'), draftRaw.slice(0, 200))

  // ─────────────────────────────────────────────────────────────────────────
  section('a saved deck that already carries art costs no round-trips')

  seedDeck(savedDeck('deck-with-art', 'Mono Red', [
    { name: 'Mountain', setCode: 'M19', cardNumber: '279', count: 24, imageUrl: 'https://img.test/Mountain.png' },
  ]))
  const mark2 = sent.length
  S().openDeck('deck-with-art')
  await flush()
  check('nothing is requested when every entry already has art',
    since(mark2, 'card_versions').length === 0,
    since(mark2, 'card_versions').map((m) => String(m.cardName)).join(','))
  check('the existing art is kept as-is',
    S().currentDeck.cards[0]?.imageUrl === 'https://img.test/Mountain.png',
    String(S().currentDeck.cards[0]?.imageUrl))

  // ─────────────────────────────────────────────────────────────────────────
  section('the hydration comes from openDeck, not from an editor mount')

  // Nothing in this harness renders a component: if (b)+(c) above passed, the
  // backfill CANNOT have come from DeckEditorView's didInitial-guarded effect.
  // Pin the counter-proof too: the editor's effect is one-shot per MOUNT, so it
  // cannot fire again for an open into an already-mounted editor.
  const fs = await import('node:fs')
  const view = fs.readFileSync(`${process.env.XMAGE_REACT_SRC}/components/deckeditor/DeckEditorView.tsx`, 'utf8')
  check('DeckEditorView backfill is guarded by a one-shot ref (the reason openDeck must do it)',
    /const didInitial = useRef\(false\)/.test(view) && /if \(didInitial\.current\) return/.test(view),
    'guard not found')
  check('the editor backfill is conditioned on a non-empty deck at mount time',
    view.includes('currentDeck.cards.length > 0 || currentDeck.sideboard.length > 0'))

  // ── a failed lookup must not break the load ──
  section('a failing version lookup degrades to "no art", never a broken open')

  seedDeck(savedDeck('deck-fail', 'Failure Case', [
    { name: 'Island', setCode: 'M19', cardNumber: '278', count: 10 },
  ]))
  const realError = console.error
  let logged = ''
  console.error = (...args: unknown[]) => { logged += args.map(String).join(' ') + '\n' }
  const mark3 = sent.length
  S().openDeck('deck-fail')
  await flush()
  const failing = since(mark3, 'card_versions')
  check('the lookup was attempted', failing.length === 1)
  // Guarded: without the fix this section has no request at all, and an
  // uncaught TypeError here would hide the earlier FAILs behind a stack trace.
  if (failing[0]) {
    answer('card_versions', failing[0].requestId!, undefined, 'db error')
    await flush()
  }
  console.error = realError
  check('the deck is still open and usable (openDeck did not reject)',
    S().currentDeckSavedId === 'deck-fail' && S().currentDeck.cards.length === 1,
    JSON.stringify({ id: S().currentDeckSavedId, n: S().currentDeck.cards.length }))
  check('the failure is logged, not swallowed silently',
    logged.includes('hydrateDeckImages'), logged.slice(0, 200))
  check('the entry simply has no art (fallback tile, no crash)',
    !S().currentDeck.cards[0]?.imageUrl, String(S().currentDeck.cards[0]?.imageUrl))

  console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
  ;(globalThis as unknown as { process: { exit: (n: number) => void } }).process.exit(failures === 0 ? 0 : 1)
}

void main()
