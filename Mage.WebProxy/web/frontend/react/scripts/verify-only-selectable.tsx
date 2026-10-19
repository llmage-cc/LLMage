/**
 * Standalone behavioural check for the "Only selectable" prompt filter.
 *
 * Run with:  bash scripts/verify-only-selectable.sh
 *
 * Covers the fetch-land / library-search grid (CardSelectPopup's TargetSelectBody):
 *  (a) the switch defaults OFF and the grid renders every candidate,
 *  (b) a filtered grid shows exactly the selectable cards (and keeps the count honest),
 *  (c) the ANSWER PATH IS UNTOUCHED — a click on a real tile still dispatches
 *      `select_target` with that card's id, exactly as before the filter existed,
 *  (d) trigger-order prompts are unaffected (every candidate is already selectable),
 *  (e) the all-hidden case is not a silent empty box.
 *
 * Everything drives the REAL modules: the real store, the real message handler and
 * the real popup component (react-dom/server, so no browser). The only stub is the
 * browser API surface in scripts/verify-stubs.mjs.
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import { TargetSelectBody } from '../src/components/CardSelectPopup'
import { hidesCandidate, ONLY_SELECTABLE_KEY, readOnlySelectablePref, writeOnlySelectablePref } from '../src/utils/promptFilters'
import { clickCandidate } from '../src/utils/targetCandidates'
import type { ServerMessage, TargetPrompt as TargetPromptType } from '../src/types'

// ── the synthetic search: 20 cards looked at, exactly 5 legal answers ──
const LANDS = ['Forest', 'Island', 'Mountain', 'Plains', 'Swamp']
const SPELLS = [
  'Grizzly Bears', 'Lightning Bolt', 'Rampant Growth', 'Craw Wurm', 'Centaur Courser',
  'Goblin Piker', 'Savannah Lions', 'Wall of Wood', 'Bonesplitter', 'Ornithopter',
  'Scathe Zombies', 'Trained Armodon', 'Gray Ogre', 'Raging Goblin', 'Shock',
]
const VALID_IDS = LANDS.map((_, i) => `land-${i}`)

const searchCards = () => [
  ...LANDS.map((name, i) => ({
    name, typeLine: 'Basic Land', manaCost: '', rules: [],
    id: `land-${i}`, imageUrl: '', isValidTarget: true,
  })),
  ...SPELLS.map((name, i) => ({
    name, typeLine: i % 3 === 0 ? 'Instant' : 'Creature — Test', manaCost: '{1}{G}',
    rules: [], id: `spell-${i}`, imageUrl: '', isValidTarget: false,
  })),
]

/** The wire shape WebProxyClient.handleGameTarget emits for a fetch-land search. */
const searchMessage = (): ServerMessage => ({
  type: 'action_prompt', action_type: 'target',
  message: 'Search your library for a land card',
  context: 'library_search', required: false, target_count: 0, max_targets: 20,
  targets: [],
  lookAtCards: searchCards(),
  lookAtCardsValidIds: VALID_IDS,
} as unknown as ServerMessage)

// ── wire capture: what actually goes on the socket ──
const sent: Array<Record<string, unknown>> = []
useGameStore.getState().setWebSocket({
  send: (m: unknown) => { sent.push(m as Record<string, unknown>) },
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  disconnect: () => {},
} as never)

// react-dom/server reads zustand's initial snapshot; point it at the live state.
;(useGameStore as unknown as { getInitialState: () => unknown }).getInitialState =
  () => useGameStore.getState()

const reset = () => {
  sent.length = 0
  useGameStore.setState({ actionPrompt: null, currentActionPrompt: null, eventLog: [], gameState: null })
}
const deliver = (msg: ServerMessage) => handleMessage(msg, useGameStore.getState())
const prompt = (): TargetPromptType => {
  const p = useGameStore.getState().actionPrompt
  if (p == null || p.type !== 'target') throw new Error('no target prompt in store')
  return p
}
/** The tiles the grid actually renders, in order. */
const tileNames = (html: string): string[] =>
  [...html.matchAll(/class="popup-card-name">([^<]*)</g)].map((m) => m[1]!)

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

// ─────────────────────────────────────────────────────────────────────────────
section('default — the switch is OFF and every candidate renders')

localStorage.removeItem(ONLY_SELECTABLE_KEY)
reset()
deliver(searchMessage())
{
  const p = prompt()
  check('the preference defaults to OFF', readOnlySelectablePref() === false)
  check('the prompt carries all 20 looked-at cards', p.lookAtCards!.length === 20, String(p.lookAtCards!.length))
  check('only 5 of them are answers', p.lookAtCards!.filter((c) => !hidesCandidate(p, c)).length === 5)
  const html = renderToStaticMarkup(<TargetSelectBody prompt={p} />)
  check('all 20 tiles render while OFF', tileNames(html).length === 20, String(tileNames(html).length))
  check('the toggle renders unchecked', html.includes('only-selectable-toggle') && !html.includes('checked=""'), html.slice(0, 200))
  check('the toggle is labelled and tooltipped', html.includes('Only selectable') && html.includes('title='))
  check('the count reads "all"', html.includes('Showing all 20 cards'), html.slice(0, 400))
}

section('filter ON — exactly the selectable cards remain')

{
  writeOnlySelectablePref(true)
  const p = prompt()
  const html = renderToStaticMarkup(<TargetSelectBody prompt={p} />)
  const names = tileNames(html)
  check('only the 5 legal answers render', names.length === 5, JSON.stringify(names))
  check('they are the right cards', VALID_IDS.every((id, i) => names[i] === LANDS[i]), JSON.stringify(names))
  check('no non-land card survives the filter', !SPELLS.some((s) => names.includes(s)), JSON.stringify(names))
  check('the count reflects the filter', html.includes('Showing 5 of 20 cards'), html.slice(0, 400))
  check('every rendered tile is selectable (no dead tiles)', !html.includes('popup-card-disabled'))
  check('the toggle renders checked', html.includes('only-selectable-toggle') && html.includes('checked=""'))
  writeOnlySelectablePref(false)
}

section('persistence — the preference survives a new prompt')

{
  writeOnlySelectablePref(true)
  check('the preference is stored', localStorage.getItem(ONLY_SELECTABLE_KEY) === '1')
  check('and reads back as ON', readOnlySelectablePref() === true)
  // A fresh prompt is a fresh mount of the component; the persisted pref is what
  // it starts from.
  reset()
  deliver(searchMessage())
  const html = renderToStaticMarkup(<TargetSelectBody prompt={prompt()} />)
  check('the next search opens already filtered', tileNames(html).length === 5, String(tileNames(html).length))
  writeOnlySelectablePref(false)
  check('turning it off is stored too', readOnlySelectablePref() === false)
}

section('ANSWER PATH UNCHANGED — a click still dispatches select_target with the card id')

{
  reset()
  deliver(searchMessage())
  const p = prompt()
  // The click path the grid's onClick uses — the shared clickCandidate(), driven
  // through the store actions, exactly as before the filter existed.
  const actions = {
    selectTarget: (id: string) => useGameStore.getState().selectTarget(id),
    hideActionPrompt: () => useGameStore.getState().hideActionPrompt(),
  }
  const card = p.lookAtCards!.find((c) => c.id === 'land-3')!
  check('a filter-visible card is selectable', !hidesCandidate(p, card))
  check('the click reports success', clickCandidate(p, card, actions))
  check('exactly one message went out', sent.length === 1, JSON.stringify(sent))
  check('it is select_target carrying the card id',
    sent[0]!.type === 'select_target' && sent[0]!.targetId === 'land-3', JSON.stringify(sent[0]))
  check('no other field was added to the answer',
    Object.keys(sent[0]!).sort().join(',') === 'targetId,type', Object.keys(sent[0]!).join(','))
  // The filter is display-only: the id sent is the card's own id, never an index
  // into the filtered list.
  check('the id is the candidate id, not the tile index', sent[0]!.targetId !== '3', String(sent[0]!.targetId))
}

section('trigger-order prompts are unaffected (every candidate is selectable)')

{
  reset()
  const abilityCards = [
    { name: 'Ajani, Nacatl Pariah', typeLine: '', manaCost: '', rules: [], id: 'ab-1', imageUrl: '', isValidTarget: true, sourceName: 'Ajani, Nacatl Pariah', abilityText: 'When Ajani enters, create a Cat.' },
    { name: 'Guide of Souls', typeLine: '', manaCost: '', rules: [], id: 'ab-2', imageUrl: '', isValidTarget: true, sourceName: 'Guide of Souls', abilityText: 'Whenever another creature enters, gain 1 life.' },
  ]
  deliver({
    type: 'action_prompt', action_type: 'target',
    message: 'Pick triggered ability (goes to the stack first)',
    context: 'trigger_order', trigger_order: true, required: true,
    target_count: 1, min: 1, max: 1, targets: [],
    lookAtCards: abilityCards,
  } as unknown as ServerMessage)
  const p = prompt()
  check('the prompt is trigger order', p.triggerOrder === true)
  check('nothing is hidden even with the filter ON',
    p.lookAtCards!.every((c) => !hidesCandidate(p, c)))
  writeOnlySelectablePref(true)
  const html = renderToStaticMarkup(<TargetSelectBody prompt={p} />)
  check('both abilities still render', tileNames(html).length === 2, JSON.stringify(tileNames(html)))
  writeOnlySelectablePref(false)
}

section('all-hidden — the empty grid explains itself')

{
  reset()
  deliver({
    type: 'action_prompt', action_type: 'target', message: 'Surveil 2',
    context: 'default', required: true, target_count: 0, max_targets: 0, targets: [],
    lookAtCards: searchCards().map((c) => ({ ...c, isValidTarget: false })),
    lookAtCardsValidIds: [],
  } as unknown as ServerMessage)
  writeOnlySelectablePref(true)
  const html = renderToStaticMarkup(<TargetSelectBody prompt={prompt()} />)
  check('no tile renders', tileNames(html).length === 0, String(tileNames(html).length))
  check('the count says so', html.includes('Showing 0 of 20 cards'), html.slice(0, 400))
  check('an explanatory notice replaces the silent empty box',
    html.includes('only-selectable-empty') && html.includes('No card here can be selected'), html)
  writeOnlySelectablePref(false)
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
