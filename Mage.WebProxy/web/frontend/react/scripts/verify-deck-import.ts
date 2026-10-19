/**
 * Store-level checks for the deck-editor import parser (deckSlice.importDeckText):
 * the annotated-export format fix ("1 x Akroma's Will (lcc) 125 [Instant] x1 [:]")
 * mirroring the Java DeckLoader fix of 2026-09-19 — the seat textareas and the deck
 * editor must accept the same three formats.
 *
 * Run with:  bash scripts/verify-deck-import.sh
 *
 * Drives the REAL store's importDeckText (the exact path the deck editor's
 * ImportExportControls uses for paste + file imports), so assertions cover the
 * shipped code path.
 */
import { useGameStore } from '../src/store/gameStore'

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) {
    console.log(`  PASS  ${name}`)
  } else {
    failures++
    console.log(`  FAIL  ${name} ${detail}`)
  }
}

function importOrError(text: string, name?: string): { ok: boolean; error?: string } {
  const result = useGameStore.getState().importDeckText(text, name)
  return result.ok ? { ok: true } : { ok: false, error: result.error }
}

console.log('## annotated-export format imports cleanly')
{
  const r = importOrError(
    "1 x Akroma's Will (lcc) 125 [Instant] x1 [:]\n" +
    "8 x Forest (lci) 401 [Land] x8 [:]\n" +
    "1 Pantlaza, Sun-Favored (lcc) 4 [Commander{top}] x1 [:]\n" +
    "SB: 4 x Mountain (lci) 400 [Land] x4\n"
  )
  check('import succeeds', r.ok, r.error ?? '')
  const d = useGameStore.getState().currentDeck
  check('3 main + 1 sideboard', d.cards.length === 3 && d.sideboard.length === 1,
    `main=${d.cards.length} sb=${d.sideboard.length}`)
  const akroma = d.cards[0]!
  check("Akroma's Will name is clean", akroma.name === "Akroma's Will", akroma.name)
  check('set/number extracted', akroma.setCode === 'lcc' && akroma.cardNumber === '125',
    `${akroma.setCode}:${akroma.cardNumber}`)
  check('count from leading number', akroma.count === 1, String(akroma.count))
  const forest = d.cards[1]!
  check('multi-count works', forest.name === 'Forest' && forest.count === 8 && forest.setCode === 'lci',
    `${forest.name} x${forest.count} (${forest.setCode})`)
  const pantlaza = d.cards[2]!
  check('comma name with Commander{top} type', pantlaza.name === 'Pantlaza, Sun-Favored' && pantlaza.setCode === 'lcc',
    pantlaza.name)
  const sb = d.sideboard[0]!
  check('sideboard entry lands in sideboard', sb.name === 'Mountain' && sb.count === 4 && sb.setCode === 'lci',
    `${sb.name} x${sb.count}`)
}

console.log('## annotated without echo / without [:] still imports')
{
  const r = importOrError("1 x Arcane Signet (lcc) 299 [Artifact]\n")
  check('no-echo line ok', r.ok, r.error ?? '')
  const d = useGameStore.getState().currentDeck
  check('Arcane Signet parsed', d.cards[0]?.name === 'Arcane Signet' && d.cards[0]?.setCode === 'lcc',
    JSON.stringify(d.cards[0]))
}

console.log('## near-miss annotated lines are REJECTED with a hint (no silent garbage)')
{
  const r2 = importOrError("1 x Forest (lci) 401 [Land\n")
  check('truncated [Type] rejected', !r2.ok, 'expected rejection')
  check('truncated hint too', (r2.error ?? '').includes('annotated export'), r2.error ?? '')
  const r3 = importOrError("8 x Forest (lci) 401 [Land] x4\n")
  check('count-echo contradiction rejected', !r3.ok, 'expected rejection')
  check('contradiction names the conflict', (r3.error ?? '').includes('contradicts'), r3.error ?? '')
}

console.log('## Moxfield-style "1x Name (set) num" (no space, optional [Type]) imports')
{
  const r = importOrError(
    "1x Akroma's Will (lcc) 125 [Instant]\n" +
    "24x Forest (lci) 401 [Land] x24\n" +
    "1 x Pantlaza, Sun-Favored (lcc) 4\n"
  )
  check('import succeeds', r.ok, r.error ?? '')
  const d = useGameStore.getState().currentDeck
  check("Akroma's Will clean", d.cards[0]?.name === "Akroma's Will" && d.cards[0]?.setCode === 'lcc',
    JSON.stringify(d.cards[0]))
  check('24x Forest count', d.cards[1]?.name === 'Forest' && d.cards[1]?.count === 24,
    JSON.stringify(d.cards[1]))
  check('no-[Type] line parses', d.cards[2]?.name === 'Pantlaza, Sun-Favored' && d.cards[2]?.cardNumber === '4',
    JSON.stringify(d.cards[2]))
}

console.log('## plain "4x Lightning Bolt" x-separator is stripped (the original garbage source)')
{
  const r = importOrError("4x Lightning Bolt\n4 x Grizzly Bears\n")
  check('import succeeds', r.ok, r.error ?? '')
  const d = useGameStore.getState().currentDeck
  check('no leading x in name (no-space)', d.cards[0]?.name === 'Lightning Bolt', d.cards[0]?.name ?? '')
  check('no leading x in name (spaced)', d.cards[1]?.name === 'Grizzly Bears', d.cards[1]?.name ?? '')
}

console.log('## names containing parentheses backtrack to the LAST (code) number')
{
  const r = importOrError("1 x B.F.M. (Big Furry Monster) (uds) 126 [Creature] x1 [:]\n")
  check('import succeeds', r.ok, r.error ?? '')
  const d = useGameStore.getState().currentDeck
  check('full name preserved', d.cards[0]?.name === 'B.F.M. (Big Furry Monster)', d.cards[0]?.name ?? '')
  check('code from the LAST pair', d.cards[0]?.setCode === 'uds' && d.cards[0]?.cardNumber === '126',
    `${d.cards[0]?.setCode}:${d.cards[0]?.cardNumber}`)
}

console.log('## regression: .dck and simple formats unchanged')
{
  const r = importOrError("4 [M10:150] Lightning Bolt\n24 Mountain\nSB: 4 [M10:150] Lightning Bolt\n")
  check('import succeeds', r.ok, r.error ?? '')
  const d = useGameStore.getState().currentDeck
  check('.dck set/number still parsed (the 2026-09-16 [^]:] fix intact)',
    d.cards[0]?.setCode === 'M10' && d.cards[0]?.cardNumber === '150' && d.cards[0]?.name === 'Lightning Bolt',
    JSON.stringify(d.cards[0]))
  check('plain line untouched', d.cards[1]?.name === 'Mountain' && d.cards[1]?.setCode === '',
    JSON.stringify(d.cards[1]))
  check('SB: prefix still works', d.sideboard[0]?.name === 'Lightning Bolt' && d.sideboard.length === 1,
    JSON.stringify(d.sideboard))
}

console.log('## import -> export round-trip is stable')
{
  const text =
    "1 x Akroma's Will (lcc) 125 [Instant] x1 [:]\n" +
    "8 x Forest (lci) 401 [Land] x8 [:]\n"
  const r = importOrError(text, 'RoundTrip')
  check('import ok', r.ok, r.error ?? '')
  const exported = useGameStore.getState().exportDeckText()
  check('export uses .dck format', exported.includes('1 [lcc:125] Akroma\'s Will'), exported)
  const r2 = importOrError(exported, 'RoundTrip2')
  check('re-import of export ok', r2.ok, r2.error ?? '')
  const d = useGameStore.getState().currentDeck
  check('same cards after round-trip',
    d.cards.length === 2 && d.cards[0]?.name === "Akroma's Will" && d.cards[1]?.count === 8,
    JSON.stringify(d.cards))
}

console.log('')
if (failures > 0) {
  console.log(`RESULT: ${failures} FAILURE(S)`)
} else {
  console.log('RESULT: ALL PASS')
}
;(globalThis as unknown as { process: { exit: (n: number) => void } }).process.exit(failures === 0 ? 0 : 1)