/**
 * Standalone behavioural check for "Select a creature type" suggested types

 *
 * Run with:  bash scripts/verify-choice-suggested.sh
 *
 * The engine already ranks creature-type chooser options
 * (mage.choices.ChoiceCreatureType.onChooseStart writes `Choice.getSortData`:
 * me = -100, opponent = -10, unused = 0) and the desktop client sorts its dialog
 * by it (PickChoiceDialog.java:116-127 — choice.getSortData()). The web dropped
 * the field. This harness drives the REAL modules end to end with NO browser:
 *  (a) the REAL message handler + store: a `choose_choice` wire payload carrying
 *      `order` per option lands on the prompt verbatim — and an option the engine
 *      did NOT rank keeps NO order field (nothing is invented),
 *  (b) the REAL ChooseChoicePrompt rendered through react-dom/server: ranked
 *      options come first under a single "Suggested" heading, in priority order,
 *      with the rest in wire order after them — and no heading appears at all
 *      when nothing is ranked,
 *  (c) the REAL splitChoiceOptions helper the component renders from: filtering
 *      narrows BOTH groups in place (stable within a group),
 *  (d) the alt-cost chooser family (`order` 0..n — ordering, not relevance) and a
 *      fully unranked payload render exactly as before (no Suggested section).
 *
 * Same pattern as verify-provider-dropdown.sh: rolldown + react-dom/server +
 * the shared browser stubs. Only the stubs are fake — the components, the store
 * and the wire handler are the shipped ones.
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import { ChooseChoicePrompt, splitChoiceOptions, suggestionOrder } from '../src/components/prompts/ChooseChoicePrompt'
import type { ChooseChoicePrompt as ChooseChoicePromptType, ServerMessage } from '../src/types'

// react-dom/server reads zustand's getServerSnapshot (the INITIAL state), so
// point it at the live state to render the store-driven parts faithfully.
;(useGameStore as unknown as { getInitialState: () => unknown }).getInitialState =
  () => useGameStore.getState()

// The bundle is written to /tmp (outside the project), so `import.meta.url`
// points at the temp dir — the runner passes the real scripts dir in
// XMAGE_VERIFY_DIR (the pattern verify-prompt-text.ts / verify-battlefield-split-scale.sh
// established). `declare`d: this directory is outside the app tsconfig's
// `include`, so @types/node's globals are not in scope.
declare const process: { env: Record<string, string | undefined>; exit(code: number): void }
const HERE = process.env.XMAGE_VERIFY_DIR ?? '.'
const COMPONENT_SRC = join(HERE, '..', 'src', 'components', 'prompts', 'ChooseChoicePrompt.tsx')

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

/**
 * The exact wire shape WebProxyClient.handleGameChooseChoice sends for a
 * creature-type chooser: `{index,key,label}` per option, plus the forwarded
 * `order` (Choice.getSortData) for the ranked types. Labels carry the engine's
 * `(me)`/`(opponent)` tag. Wire order is the engine's map order — deliberate
 * jumble here so the render order can only come from the priorities.
 */
const creatureTypeWire = () => [
  { index: 0, key: 'Angel', label: 'Angel' },
  { index: 1, key: 'Elf', label: 'Elf (me)', order: -100 },
  { index: 2, key: 'Goblin', label: 'Goblin (opponent)', order: -10 },
  { index: 3, key: 'Zombie', label: 'Zombie' },
  { index: 4, key: 'Wizard', label: 'Wizard (opponent)', order: -10 },
  { index: 5, key: 'Merfolk', label: 'Merfolk (me, opponent)', order: -100 },
  { index: 6, key: 'Dragon', label: 'Dragon' },
  { index: 7, key: 'Hydra', label: 'Hydra' },
  { index: 8, key: 'Kraken', label: 'Kraken' },
  { index: 9, key: 'Pirate', label: 'Pirate' },
]

/** Deliver one action_prompt the way the socket handler does. */
function deliverWire(options: unknown[]): ChooseChoicePromptType {
  handleMessage({
    type: 'action_prompt',
    action_type: 'choose_choice',
    message: 'Choose a creature type',
    options,
  } as unknown as ServerMessage, useGameStore.getState())
  const p = useGameStore.getState().actionPrompt
  if (p == null || p.type !== 'choose_choice') throw new Error('no choose_choice prompt in store')
  return p
}

const reset = () => useGameStore.setState({ actionPrompt: null, currentActionPrompt: null })

/** Button labels in rendered order (the SSR markup the popup shows). */
const renderedLabels = (html: string): string[] =>
  [...html.matchAll(/<button[^>]*class="btn-prompt"[^>]*>([^<]*)<\/button>/g)].map((m) => m[1]!)

// ─────────────────────────────────────────────────────────────────────────────
section('Wire -> store: the engine relevance is carried through, never invented')

{
  reset()
  const prompt = deliverWire(creatureTypeWire())
  const byName = (n: string) => prompt.choices.find((c) => c.name.startsWith(n))
  check('Elf keeps the engine priority -100', byName('Elf')?.order === -100, JSON.stringify(byName('Elf')))
  check('Merfolk (me, opponent) keeps -100', byName('Merfolk')?.order === -100, JSON.stringify(byName('Merfolk')))
  check('Goblin (opponent) keeps -10', byName('Goblin')?.order === -10, JSON.stringify(byName('Goblin')))
  check('a type the engine did not rank has NO order field',
    byName('Angel')?.order === undefined && !('order' in (byName('Angel') as object)),
    JSON.stringify(byName('Angel')))
  check('only the 4 ranked options carry an order (no invented priority)',
    prompt.choices.filter((c) => c.order !== undefined).length === 4,
    String(prompt.choices.filter((c) => c.order !== undefined).length))
  check('the label (me) tag still reaches the option text',
    byName('Elf')?.name === 'Elf (me)', byName('Elf')?.name)
}

// ─────────────────────────────────────────────────────────────────────────────
section('Rendered prompt — Suggested section first, stable priority order, rest as today')

{
  const prompt = deliverWire(creatureTypeWire())
  const html = renderToStaticMarkup(<ChooseChoicePrompt prompt={prompt} />)
  const labels = renderedLabels(html)
  check('exactly one Suggested heading', html.split('Suggested').length - 1 === 1, html)
  check('Elf (priority -100) renders FIRST, under the heading',
    labels[0] === 'Elf (me)' && html.indexOf('Suggested') < html.indexOf('Elf (me)'),
    labels.join(' | '))
  check('me-ranked before opponents, both before the rest, ties in wire order',
    labels.join('|') === 'Elf (me)|Merfolk (me, opponent)|Goblin (opponent)|Wizard (opponent)|Angel|Zombie|Dragon|Hydra|Kraken|Pirate',
    labels.join('|'))
  check('every wire option still renders (nothing dropped)',
    labels.length === prompt.choices.length && prompt.choices.length === 10, String(labels.length))
  check('the filter box renders for >8 options', html.includes('prompt-choice-filter'), html.slice(0, 200))

  // The rendered groups must come from the one shared helper — a second local
  // filter would drift the moment either side changes.
  const src = readFileSync(COMPONENT_SRC, 'utf8')
  check('the component groups/filters through splitChoiceOptions (single source)',
    /splitChoiceOptions\(prompt\.choices, filter\)/.test(src), src.slice(0, 400))
  check('both groups render from that one result (suggested.map + rest.map)',
    /suggested\.map\(option\)/.test(src) && /rest\.map\(option\)/.test(src))
}

// ─────────────────────────────────────────────────────────────────────────────
section('splitChoiceOptions — filter applies within BOTH groups')

{
  const choices = deliverWire(creatureTypeWire()).choices

  const all = splitChoiceOptions(choices, '')
  check('unfiltered: 4 suggested, 6 rest',
    all.suggested.length === 4 && all.rest.length === 6,
    `${all.suggested.length}/${all.rest.length}`)
  check('unfiltered suggested order is priority asc',
    all.suggested.map((c) => c.order).join(',') === '-100,-100,-10,-10',
    all.suggested.map((c) => `${c.name}=${c.order}`).join(' | '))

  // "elf" hits one suggested option (Elf) and no rest option.
  const elf = splitChoiceOptions(choices, 'elf')
  check('filter narrows the SUGGESTED group (Elf kept)',
    elf.suggested.map((c) => c.name).join('|') === 'Elf (me)', elf.suggested.map((c) => c.name).join('|'))
  check('and the REST group is concurrently narrowed (no unmatched leak)',
    elf.rest.length === 0, elf.rest.map((c) => c.name).join('|'))

  // "a" matches one suggested label (Wizard) and five rest labels — the exact
  // shape the old single-list filter produced, now split across the two groups.
  const a = splitChoiceOptions(choices, 'a')
  check('a filter matching both groups keeps both populated and ordered',
    a.suggested.map((c) => c.name).join('|') === 'Wizard (opponent)'
      && a.rest.map((c) => c.name).join('|') === 'Angel|Dragon|Hydra|Kraken|Pirate',
    `S:${a.suggested.map((c) => c.name).join('|')} R:${a.rest.map((c) => c.name).join('|')}`)

  // "r" hits two suggested options and four rest ones — suggested keeps its
  // priority order (Merfolk -100 before Wizard -10), rest keeps wire order.
  const r = splitChoiceOptions(choices, 'r')
  check('suggested keeps priority order under a multi-group filter',
    r.suggested.map((c) => c.name).join('|') === 'Merfolk (me, opponent)|Wizard (opponent)'
      && r.rest.map((c) => c.name).join('|') === 'Dragon|Hydra|Kraken|Pirate',
    `S:${r.suggested.map((c) => c.name).join('|')} R:${r.rest.map((c) => c.name).join('|')}`)

  // Case-insensitivity (the pre-existing filter contract).
  const upper = splitChoiceOptions(choices, 'GOBLIN')
  check('filter stays case-insensitive',
    upper.suggested.map((c) => c.name).join('|') === 'Goblin (opponent)', upper.suggested.map((c) => c.name).join('|'))

  const none = splitChoiceOptions(choices, 'zzz')
  check('no matches anywhere yields two empty groups (empty-state renders)',
    none.suggested.length === 0 && none.rest.length === 0)
}

// ─────────────────────────────────────────────────────────────────────────────
section('No heading when nothing is suggested / when the field is plain ordering')

{
  reset()
  const unranked = deliverWire([
    { index: 0, key: 'White', label: 'White' },
    { index: 1, key: 'Blue', label: 'Blue' },
  ])
  const html = renderToStaticMarkup(<ChooseChoicePrompt prompt={unranked} />)
  check('an unranked payload renders no Suggested heading', !html.includes('Suggested'), html)
  check('and both options render in wire order',
    renderedLabels(html).join('|') === 'White|Blue', renderedLabels(html).join('|'))

  // Alt-cost chooser: AbilityImpl.activate numbers options 0..n in sortData.
  // Those are NOT relevance — a "Suggested" section there would be a lie.
  reset()
  const altCost = deliverWire([
    { index: 0, key: '1', label: 'Cast with alternative cost: pay 3 life (source: Invigorate)', order: 0 },
    { index: 1, key: '2', label: 'Cast with no alternative cost: {3}{G}', order: 1 },
  ])
  const altHtml = renderToStaticMarkup(<ChooseChoicePrompt prompt={altCost} />)
  check('alt-cost ordering (0..n) does not become a Suggested section', !altHtml.includes('Suggested'), altHtml)
  check('the alt-cost option text is unchanged',
    renderedLabels(altHtml)[0] === 'Cast with alternative cost: pay 3 life (source: Invigorate)',
    renderedLabels(altHtml).join('|'))
  check('the predicate only accepts negative (relevance) values',
    suggestionOrder({ index: 0, name: 'x', order: 0 }) === null
      && suggestionOrder({ index: 1, name: 'y' }) === null
      && suggestionOrder({ index: 2, name: 'z', order: -100 }) === -100)
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
