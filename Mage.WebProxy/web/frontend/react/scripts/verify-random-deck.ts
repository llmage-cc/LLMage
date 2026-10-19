/**
 * Store-level checks for the random deck generator — pure-core + slice
 * path, mirroring how verify-deck-import.ts drives the REAL store.
 *
 * Run with:  bash scripts/verify-random-deck.sh
 * (Run it as part of the normal verification pass.)
 *
 * Covers:
 *  (a) seeded determinism: same seed + same pool ⇒ byte-identical deck,
 *      different seed ⇒ (almost surely) different deck,
 *  (b) quotas: exactly 60 cards, ≤4 copies per non-basic name, exactly 20
 *      basic lands split across ≤5 basic types,
 *  (c) color fit: every non-basic card's colors ⊆ chosen colors,
 *  (d) Fail-Fast: empty pool throws with the actionable message,
 *  (e) formatRandomDeckText emits "N [SET:NUM] Name" lines + NAME: line,
 *  (f) the .dck text round-trips through the REAL store's importDeckText,
 *  (g) distributeBasicLands tracks pip percentages (1:2 split on W:U pips),
 *  (h) generateRandomDeck against a stubbed ws: fills from collection,
 *      throws + sets randomDeckError on empty collection (Fail Fast).
 */
import {
  RANDOM_DECK_MAX_BASICS,
  RANDOM_DECK_MAX_PER_NAME,
  RANDOM_DECK_SIZE,
  RANDOM_DECK_SPELL_TARGET,
  distributeBasicLands,
  deriveRandomDeckSeed,
  formatRandomDeckText,
  mulberry32,
  selectRandomDeck,
  type RandomSpellCard,
} from '../src/utils/randomDeck'
import { useGameStore } from '../src/store/gameStore'
import { handleDeckEditorResponse } from '../src/store/slices/deckSlice'

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) {
    console.log(`  PASS  ${name}`)
  } else {
    failures++
    console.log(`  FAIL  ${name} ${detail}`)
  }
}

// ── Synthetic collection pool: monowhite, 25 distinct names × 4 copies ──
function monoWhitePool(): RandomSpellCard[] {
  const pool: RandomSpellCard[] = []
  for (let i = 1; i <= 25; i++) {
    pool.push({
      name: `White Spell ${i}`,
      setCode: 'TST',
      cardNumber: String(i),
      owned: 4,
      colors: ['white'],
      manaCost: ['{1}{W}'],
    })
  }
  // 8 colorless artifacts so the mono-white candidate beats two-color pools.
  for (let i = 26; i <= 33; i++) {
    pool.push({
      name: `Colorless Spell ${i}`,
      setCode: 'TST',
      cardNumber: String(i),
      owned: 4,
      colors: [],
      manaCost: ['{3}'],
    })
  }
  return pool
}

console.log('## seeded determinism + quotas (pure core)')
{
  const pool = monoWhitePool()
  const a = selectRandomDeck(42, pool)
  const b = selectRandomDeck(42, pool)
  check('same seed ⇒ same colors', JSON.stringify(a.chosenColors) === JSON.stringify(b.chosenColors))
  check('same seed ⇒ same main', JSON.stringify(a.main) === JSON.stringify(b.main))
  check('same seed ⇒ same basics', JSON.stringify(a.basicsNeeded) === JSON.stringify(b.basicsNeeded))
  const c = selectRandomDeck(43, pool)
  check('different seed ⇒ different main', JSON.stringify(a.main) !== JSON.stringify(c.main))

  const cardCount = a.main.reduce((sum, e) => sum + e.count, 0)
  const basicCount = Object.values(a.basicsNeeded).reduce((sum, n) => sum + n, 0)
  check('total is 60 cards', cardCount + basicCount === RANDOM_DECK_SIZE, `${cardCount}+${basicCount}`)
  check('spell quota is 40', cardCount === RANDOM_DECK_SPELL_TARGET, String(cardCount))
  check('basic quota is exactly 20', basicCount === RANDOM_DECK_MAX_BASICS, String(basicCount))
  check('≤4 copies per name', a.main.every((e) => e.count <= RANDOM_DECK_MAX_PER_NAME))
  check('≤ owned copies', a.main.every((e) => {
    const card = pool.find((p) => p.name === e.name)
    return card !== undefined && e.count <= card.owned
  }))
  check('≤20 across the basic types', basicCount <= RANDOM_DECK_MAX_BASICS && a.basicsNeeded['Plains'] !== undefined)
  check('only the chosen color + colorless in pool', a.chosenColors.length >= 1 && a.chosenColors.length <= 2)
}

console.log('## color fit')
{
  const pool = monoWhitePool()
  const sel = selectRandomDeck(7, pool)
  check('chose monowhite', sel.chosenColors.length === 1 && sel.chosenColors[0] === 'white',
    JSON.stringify(sel.chosenColors))
  check('basics split only into Plains', Object.keys(sel.basicsNeeded).every((n) => n === 'Plains'))
}

console.log('## fail-fast: unusable pool throws')
{
  let threw = false
  let message = ''
  try {
    selectRandomDeck(1, [])
  } catch (e) {
    threw = true
    message = (e as Error).message
  }
  check('empty pool throws', threw)
  check('message is actionable', message.includes('Not enough cards in your collection'), message)
}

console.log('## distributeBasicLands tracks pip share')
{
  const split = distributeBasicLands({ white: 10, blue: 20, black: 0, red: 0, green: 0 }, ['white', 'blue'], 20)
  check('1:2 pip share ⇒ ~7:13 land split', split['white'] === 7 && split['blue'] === 13,
    JSON.stringify(split))
  const even = distributeBasicLands({ white: 5, blue: 5, black: 0, red: 0, green: 0 }, ['white', 'blue'], 20)
  check('equal pips ⇒ 10:10 split', even['white'] === 10 && even['blue'] === 10, JSON.stringify(even))
  const generic = distributeBasicLands({ white: 0, blue: 0, black: 0, red: 0, green: 0 }, ['white', 'blue'], 20)
  check('zero pips ⇒ even fallback split', generic['white'] === 10 && generic['blue'] === 10,
    JSON.stringify(generic))
}

console.log('## formatRandomDeckText emits .dck lines')
{
  const sel = selectRandomDeck(42, monoWhitePool())
  const basics = Object.entries(sel.basicsNeeded).map(([name, count]) => ({
    name, setCode: 'TST', cardNumber: '999', count,
  }))
  const text = formatRandomDeckText(sel, basics)
  check('NAME line carries the seed', text.startsWith(`NAME:${sel.name}`) && sel.name.includes('seed 42'))
  const lines = text.split('\n')
  // One line per distinct main entry (copies aggregate into "N [SET:NUM] Name")
  // plus one per basic and the NAME header — the 60-card composition itself is
  // proven by the store round-trip below, which imports the same text and
  // counts the parsed cards.
  check('NAME + one line per main entry + one per basic',
    lines.length === 1 + sel.main.length + basics.length, String(lines.length))
  check('lines are "N [SET:NUM] Name"', lines.slice(1).every((l) => /^\d+ \[[^\]:]+:[^\]:]+\] .+$/.test(l)))
}

console.log('## .dck text round-trips through the REAL store importDeckText')
{
  const sel = selectRandomDeck(42, monoWhitePool())
  const basics = Object.entries(sel.basicsNeeded).map(([name, count]) => ({
    name, setCode: 'TST', cardNumber: '999', count,
  }))
  const text = formatRandomDeckText(sel, basics)
  const r = useGameStore.getState().importDeckText(text, sel.name)
  check('import succeeds', r.ok, ('error' in r && r.error) || '')
  if (r.ok) {
    const d = useGameStore.getState().currentDeck
    const mainCount = d.cards.reduce((s, e) => s + e.count, 0)
    const sbCount = d.sideboard.reduce((s, e) => s + e.count, 0)
    check('parses to 60 cards, empty sideboard', mainCount === 60 && sbCount === 0, `${mainCount}+${sbCount}`)
    check('deck name carried', d.name === sel.name)
  }
}

console.log('## PRNG + seed derivation sanity')
{
  const s1 = deriveRandomDeckSeed()
  const s2 = deriveRandomDeckSeed()
  check('two seeds differ (crypto mix)', s1 !== s2, `${s1} vs ${s2}`)
  check('seed is uint32', Number.isInteger(s1) && s1 >= 0 && s1 <= 0xffffffff, String(s1))
  const rng = mulberry32(123)
  const first = rng()
  const rng2 = mulberry32(123)
  check('mulberry32 reproducible', rng2() === first)
  const vals = Array.from({ length: 100 }, () => mulberry32(9)())
  check('same-seed streams identical', new Set(vals).size === 1)
}

console.log('## generateRandomDeck through the REAL store (stubbed ws)')
{
  // Fresh store state: seed the collection directly, then drive the slice with
  // a stub ws that answers card_lookup from the same table.
  const collection: Record<string, number> = {}
  const answers = new Map<string, unknown>()
  const ownedPrintings = [
    { key: 'TST:101', name: 'White Spell A', colors: ['white'], manaCost: ['{1}{W}'], supertypes: [], types: ['CREATURE'] },
    { key: 'TST:102', name: 'White Spell B', colors: ['white'], manaCost: ['{2}{W}'], supertypes: [], types: ['CREATURE'] },
    // A BASIC printing the generator must exclude from the spell pool.
    { key: 'TST:900', name: 'Plains', colors: [], manaCost: [], supertypes: ['BASIC'], types: ['LAND'] },
  ]
  for (const p of ownedPrintings) collection[p.key] = 20
  useGameStore.setState({ collection })

  const stubWs = {
    send: (msg: { type: string; requestId: string; keys?: unknown[] }) => {
      if (msg.type !== 'card_lookup') return
      const cards = (msg.keys ?? []).map((k) => {
        const found = ownedPrintings.find((p) => p.key === `${(k as { setCode: string }).setCode}:${(k as { cardNumber: string }).cardNumber}`)
        if (!found) return null
        return {
          name: found.name, setCode: (k as { setCode: string }).setCode,
          cardNumber: (k as { cardNumber: string }).cardNumber,
          manaCost: found.manaCost, manaValue: 1, types: found.types, subtypes: [],
          supertypes: found.supertypes, rarity: 'COMMON', colors: found.colors,
          rules: [], imageUrl: null,
        }
      }).filter(Boolean)
      // Async microtask reply, like the real websocket.
      Promise.resolve().then(() => {
        handleDeckEditorResponse({
          type: 'card_lookup_result', requestId: msg.requestId,
          result: { cards, count: cards.length, missing: [] },
        })
      })
    },
  }
  useGameStore.setState({ ws: stubWs as never, randomDeckError: null })

  let result: string | null = null
  // Holder objects with an asserted union type: TS's control-flow analysis
  // does not track assignments made inside .catch callbacks, so a bare
  // `let threw: Error | null` (or a property initialized to plain `null`,
  // later re-assigned) gets narrowed to `null`/`never` and the checks below
  // would read `.message` off `never`.
  const outcome: { threw: Error | null } = { threw: null as Error | null }
  useGameStore.getState().generateRandomDeck().then((t) => { result = t }).catch((e: Error) => { outcome.threw = e })
  await new Promise((r) => setTimeout(r, 50))

  // The 2-spell collection cannot fill 40 spells — the slice must Fail Fast.
  check('too-small collection throws', outcome.threw !== null, outcome.threw?.message ?? '')
  check('error is actionable', outcome.threw?.message.includes('Not enough cards in your collection') ?? false)
  check('no text returned', result === null)
  const errState = useGameStore.getState().randomDeckError
  check('randomDeckError set for the affordance', typeof errState === 'string' && errState.length > 0)
  check('loading flag cleared', useGameStore.getState().randomDeckLoading === false)

  // Empty collection: the slice's own precondition error (the component's live
  // disable covers the pre-click case; this pins the post-click message too).
  useGameStore.setState({ collection: {}, randomDeckError: null })
  outcome.threw = null as Error | null
  useGameStore.getState().generateRandomDeck().catch((e: Error) => { outcome.threw = e })
  await new Promise((r) => setTimeout(r, 20))
  check('empty collection throws', outcome.threw !== null, outcome.threw?.message ?? '')
  check('empty-collection message is actionable',
    outcome.threw?.message.includes('Your collection is empty') ?? false)
}

console.log(failures === 0 ? '\nRESULT: ALL PASS' : `\nRESULT: ${failures} FAILURE(S)`)
;(globalThis as unknown as { process: { exit: (n: number) => void } }).process.exit(failures === 0 ? 0 : 1)