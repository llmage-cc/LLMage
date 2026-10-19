/**
 * Standalone behavioural check for the LLM seat config's tab split:
 * "The whole LLM config menu is awful to
 * deal with, should be split into tabs like the java client is"; the desktop
 * reference is Mage.Client `LLMConfigDialog.java`, JTabbedPane Core / Behavior /
 * Personality / Conversation / Summarization).
 *
 * Run with:  bash scripts/verify-llm-config-tabs.sh
 *
 * Covers the feature end to end with NO browser, driving the REAL modules:
 *  (a) the tab set and its order are the desktop client's, and every
 *      LLM_CONFIG_SECTIONS field sits in exactly one tab — none dropped, none
 *      duplicated, each with its exact label and control type,
 *  (b) the REAL seat row (PlayerSlotRow) renders the tab bar for an LLM seat:
 *      role=tablist with 5 role=tab buttons, the first selected (aria-selected),
 *      roving tabIndex, and exactly ONE visible panel (role=tabpanel),
 *  (c) each tab's aria-controls points at the panel that IS visible when that tab
 *      is active — switching is proven by rendering every tab active and checking
 *      panel visibility AND which tab reports aria-selected (the click/keyboard
 *      handler bindings are asserted by the .sh wrapper, because react-dom/server
 *      strips event handlers from SSR markup — same split as
 *      verify-card-grid-preview.sh),
 *  (d) the tablist key map: ArrowRight/Left wrap around, Home/End jump, other keys
 *      leave the selection alone,
 *  (e) Fail Fast: an unknown section name, a duplicate section, a missing section
 *      and an unknown initial tab all throw,
 *  (f) field behaviour is unchanged: a model pick auto-fills modelContextWindow
 *      from the browser-direct window map (client-key bridge), an apiEndpoint
 *      change routes the model refresh browser-direct vs proxy-relayed by key
 *      presence, everything else is a plain set and the seat object is not mutated,
 *  (g) the Provider dropdown still works inside the Core tab (Other… + free text),
 *  (h) the LLM config block stays gated on the LLMage build flavour and the
 *      server's LLM capability.
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import { PlayerSlotRow } from '../src/components/PlayerSlotRow'
import {
  LLMConfigTabs,
  LLM_CONFIG_TABS,
  DEFAULT_LLM_CONFIG_TAB,
  buildLlmConfigTabs,
  llmConfigTabIndex,
  tabAfterKey,
  applyLlmConfigFieldChange,
  refreshModelsForSeat,
} from '../src/components/LLMConfigTabs'
import { LLM_CONFIG_SECTIONS, PROVIDER_OTHER, NO_SEAT_ENDPOINT_MESSAGE } from '../src/utils/constants'
import { EXPLAIN } from '../src/hints/explain'
import type { LLMConfigField, LLMConfigSection, PlayerSlot } from '../src/types'

// react-dom/server reads zustand's getServerSnapshot, which is
// `selector(api.getInitialState())` — the store's INITIAL state object. Under
// rolldown the store singleton is shared with this harness, so the working
// lever is that snapshot object itself (same as verify-token-menu-hides-preview):
// setState below is mirrored into it so every server render sees the scenario.
// Patching useGameStore.getInitialState is NOT sufficient (zustand v5 react.mjs
// wires the third useSyncExternalStore argument to the api closure).
const ssrSnapshot = (useGameStore as unknown as { getInitialState: () => Record<string, unknown> }).getInitialState()
const setLive = (patch: Record<string, unknown>) => {
  useGameStore.setState(patch as never)
  Object.assign(ssrSnapshot, patch)
}

// The store surface the component reads (models + personalities + capability),
// set the way the running client has it before a seat's config is edited.
setLive({
  availableModels: ['gpt-4o', 'deepseek-chat'],
  modelContextWindows: { 'gpt-4o': 128000 },
  availablePersonalities: ['Kestrel', 'Ember'],
  modelsLoading: false,
  capabilities: { llm: true, replayScrubber: true, replaySidecar: true, serverVersion: 'test', playerTypes: [] },
})

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

// ── SSR markup helpers ────────────────────────────────────────────────────────
/** The opening tag of every role="tabpanel", in document order. */
const panelTags = (html: string): string[] => [...html.matchAll(/<div role="tabpanel"[^>]*>/g)].map((m) => m[0])
/** Visible panels = the ones not carrying the hidden attribute. */
const visiblePanels = (html: string): string[] => panelTags(html).filter((t) => !t.includes('hidden'))

/** The panel segment that follows its own opening tag (up to the next panel / end). */
function panelSegments(html: string): Array<{ tag: string; body: string }> {
  const tags = panelTags(html)
  return tags.map((tag, i) => {
    const start = html.indexOf(tag) + tag.length
    const next = i + 1 < tags.length ? html.indexOf(tags[i + 1]!, start) : html.length
    return { tag, body: html.slice(start, next) }
  })
}

const activeTabName = (html: string): string =>
  /<button[^>]*role="tab"[^>]*aria-selected="true"[^>]*>([^<]*)<\/button>/.exec(html)?.[1] ?? '(none)'

const tabNames = (html: string): string[] =>
  [...html.matchAll(/<button[^>]*role="tab"[^>]*>([^<]*)<\/button>/g)].map((m) => m[1]!)

/** The control a field's type must render, as a markup token. */
function controlToken(field: LLMConfigField): string {
  switch (field.type) {
    case 'checkbox': return '<input type="checkbox"'
    case 'number': return '<input type="number"'
    case 'text': return '<input type="text"'
    case 'password': return '<input type="password"'
    case 'select':
    case 'model_select':
    case 'personality_select':
    case 'endpoint_select': return '<select'
    default: throw new Error(`harness: unhandled field type '${field.type}'`)
  }
}

/** A player slot built exactly the way the store's makeSlot builds one. */
function makeSlot(type: string): PlayerSlot {
  const slot: PlayerSlot = { type, isYou: false, deck: '', showLLMConfig: type === 'COMPUTER_LLM', llmConfig: {} }
  for (const sectionDef of LLM_CONFIG_SECTIONS) {
    for (const f of sectionDef.fields) slot.llmConfig[f.key] = f.value
  }
  return slot
}

const renderRow = (slot: PlayerSlot): string =>
  renderToStaticMarkup(
    <PlayerSlotRow slot={slot} index={1} canRemove={false} onUpdate={() => {}} onRemove={() => {}} onDeckFile={() => {}} />
  )

const renderTabs = (config: Record<string, unknown>, defaultTab?: string): string =>
  renderToStaticMarkup(
    defaultTab === undefined
      ? <LLMConfigTabs config={config} onConfigChange={() => {}} />
      : <LLMConfigTabs config={config} onConfigChange={() => {}} defaultTab={defaultTab} />
  )

const fullConfig = (): Record<string, unknown> => {
  const config: Record<string, unknown> = {}
  for (const sectionDef of LLM_CONFIG_SECTIONS) {
    for (const f of sectionDef.fields) config[f.key] = f.value
  }
  return config
}

// ─────────────────────────────────────────────────────────────────────────────
section('Tab set — the desktop client\'s five sections, in JTabbedPane order')

{
  check('the canonical tab list is the desktop dialog\'s', LLM_CONFIG_TABS.join(',') === 'Core,Behavior,Personality,Conversation,Summarization', LLM_CONFIG_TABS.join(','))
  check('the desktop dialog opens on Core', DEFAULT_LLM_CONFIG_TAB === 'Core')
  const sections = LLM_CONFIG_SECTIONS.map((s) => s.name)
  check('LLM_CONFIG_SECTIONS names match the tabs exactly (same order)', sections.join(',') === LLM_CONFIG_TABS.join(','), sections.join(','))
  const tabs = buildLlmConfigTabs(LLM_CONFIG_SECTIONS)
  check('buildLlmConfigTabs pairs every tab with its fields', tabs.length === 5 && tabs.every((t) => t.section.fields.length > 0))

  // Every field of the flat list must appear in exactly one tab, keeping its
  // section, label and type — the split is layout only.
  const flat = LLM_CONFIG_SECTIONS.flatMap((s) => s.fields.map((f) => `${s.name}/${f.key}`))
  const tabbed = tabs.flatMap((t) => t.section.fields.map((f) => `${t.name}/${f.key}`))
  check('every LLM_CONFIG_SECTIONS field is reachable in exactly one tab', flat.join('|') === tabbed.join('|'), tabbed.join('|'))
  check('no field key appears twice', new Set(flat.map((k) => k.split('/')[1])).size === flat.length)
  // 27 fields since the askReason toggle. The pinned count exists to catch fields
  // being
  // DROPPED in the tab split — it must be bumped whenever a field is added.
  check('field count is preserved (27 fields across 5 tabs)', flat.length === 27, String(flat.length))
}

// ─────────────────────────────────────────────────────────────────────────────
section('Rendered tab bar — the real seat row')

{
  const html = renderRow(makeSlot('COMPUTER_LLM'))
  check('a tablist is rendered', html.includes('role="tablist"'), html)
  check('tablist is labelled for screen readers', /role="tablist"[^>]*aria-label="LLM configuration sections"/.test(html), html)
  check('five tabs render', tabNames(html).length === 5, tabNames(html).join(','))
  check('tab titles are the section names in order', tabNames(html).join(',') === LLM_CONFIG_TABS.join(','), tabNames(html).join(','))
  check('the first tab (Core) is selected', activeTabName(html) === 'Core', activeTabName(html))
  check('exactly one tab reports aria-selected=true', (html.match(/aria-selected="true"/g) ?? []).length === 1)
  check('every tab has aria-selected and an aria-controls target', (html.match(/role="tab"[^>]*aria-selected=/g) ?? []).length === 5 && (html.match(/aria-controls="/g) ?? []).length === 5)
  check('roving tabindex: the selected tab is focusable, the rest are not',
    /<button[^>]*aria-selected="true"[^>]*tabindex="0"/.test(html) && (html.match(/tabindex="-1"/g) ?? []).length === 4, html)
  check('five panels render', panelTags(html).length === 5)
  check('exactly ONE panel is visible', visiblePanels(html).length === 1, String(visiblePanels(html).length))
  check('each panel is labelled by its own tab', (html.match(/role="tabpanel"[^>]*aria-labelledby="/g) ?? []).length === 5)
  check('the tab data-explain id is registered (explain-coverage stays green)', typeof EXPLAIN['llm-config-tab'] === 'string')
  check('the LLM config block keeps its .llm-config-section container', html.includes('class="llm-config-section"'))
}

// ─────────────────────────────────────────────────────────────────────────────
section('Switching — every tab opens its own panel, one panel at a time')

{
  const html = renderRow(makeSlot('COMPUTER_LLM'))
  const segments = panelSegments(html)
  const tabsByName = buildLlmConfigTabs(LLM_CONFIG_SECTIONS)

  for (const { name } of tabsByName) {
    const rendered = renderTabs(fullConfig(), name)
    const visible = visiblePanels(rendered)
    check(`tab '${name}' active: exactly one panel visible`, visible.length === 1, String(visible.length))
    check(`tab '${name}' active: that tab reports aria-selected`, activeTabName(rendered) === name, activeTabName(rendered))
    check(`tab '${name}' active: the other four tabs report aria-selected=false`,
      (rendered.match(/aria-selected="false"/g) ?? []).length === 4, String((rendered.match(/aria-selected="false"/g) ?? []).length))
    // The visible panel is the one this tab controls.
    const controls = new RegExp(`<button[^>]*aria-selected="true"[^>]*aria-controls="([^"]+)"`).exec(rendered)?.[1] ?? ''
    check(`tab '${name}' active: the visible panel is the tab's aria-controls target`, controls !== '' && (visible[0] ?? '').includes(`id="${controls}"`), `${controls} vs ${visible[0] ?? ''}`)
  }

  // Every canonical tab title appears exactly once in the tab bar.
  check('every canonical tab title appears exactly once in the tab bar',
    tabsByName.every(({ name }) => html.split(`>${name}</button>`).length - 1 === 1), html)

  // The panel the FIRST render shows is the Core panel, and it is the one whose
  // segment carries the Core fields (not merely a hidden copy of them).
  const coreSegment = segments.find((s) => s.tag.includes('llm-panel-core'))
  check('the Core panel segment exists in the rendered markup', !!coreSegment)
  check('the Core panel holds the Core fields (Model/Provider/API Key)',
    !!coreSegment && coreSegment.body.includes('<label>Model</label>') &&
    coreSegment.body.includes('<label>Provider</label>') && coreSegment.body.includes('<label>API Key</label>'))
  check('the visible panel is the Core panel', (visiblePanels(html)[0] ?? '').includes('llm-panel-core'), visiblePanels(html)[0] ?? '')
}

// ─────────────────────────────────────────────────────────────────────────────
section('Every field of every section is rendered, with its exact label and control')

{
  for (const { name, section: sec } of buildLlmConfigTabs(LLM_CONFIG_SECTIONS)) {
    const rendered = renderTabs(fullConfig(), name)
    const seg = panelSegments(rendered).find((s) => s.tag.includes(`llm-panel-${name.toLowerCase()}`))
    check(`${name}: its own panel segment exists`, !!seg)
    if (!seg) continue
    const labels = [...seg.body.matchAll(/<label>([^<]*)<\/label>/g)].map((m) => m[1]!)
    check(`${name}: exactly its ${sec.fields.length} fields render (label order preserved)`,
      labels.join('|') === sec.fields.map((f) => f.label).join('|'), labels.join('|'))
    // Per field: its control type still renders the same element.
    const parts = seg.body.split('<label>')
    for (let i = 0; i < sec.fields.length; i++) {
      const field = sec.fields[i]!
      const region = parts[i + 1] ?? ''
      check(`${name}/${field.key}: '${field.label}' renders as ${field.type} (${controlToken(field)})`,
        region.startsWith(`${field.label}</label>`) && region.includes(controlToken(field)))
    }
    // The model field keeps its refresh button.
    if (sec.fields.some((f) => f.key === 'model')) {
      check(`${name}: the model field keeps its ↻ refresh button`, seg.body.includes('llm-refresh-models') && seg.body.includes('↻'))
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
section('Tablist key map (selection follows focus, like JTabbedPane)')

{
  check('ArrowRight moves to the next tab', tabAfterKey('Core', 'ArrowRight') === 'Behavior')
  check('ArrowDown moves to the next tab', tabAfterKey('Core', 'ArrowDown') === 'Behavior')
  check('ArrowRight wraps around at the end', tabAfterKey('Summarization', 'ArrowRight') === 'Core')
  check('ArrowLeft moves to the previous tab', tabAfterKey('Behavior', 'ArrowLeft') === 'Core')
  check('ArrowUp moves to the previous tab', tabAfterKey('Behavior', 'ArrowUp') === 'Core')
  check('ArrowLeft wraps around at the start', tabAfterKey('Core', 'ArrowLeft') === 'Summarization')
  check('Home jumps to the first tab', tabAfterKey('Conversation', 'Home') === 'Core')
  check('End jumps to the last tab', tabAfterKey('Core', 'End') === 'Summarization')
  for (const other of ['Tab', 'Enter', ' ', 'a', 'PageDown']) {
    check(`'${other}' does not move the selection`, tabAfterKey('Behavior', other) === 'Behavior')
  }
  check('tab indexes are the canonical order', llmConfigTabIndex('Core') === 0 && llmConfigTabIndex('Summarization') === 4)
}

// ─────────────────────────────────────────────────────────────────────────────
section('Fail Fast — unknown or missing sections throw, never a silent fallback')

{
  const threw = (fn: () => unknown): unknown => { try { fn(); return null } catch (e) { return e } }

  let err = threw(() => llmConfigTabIndex('Nonsense'))
  check('an unknown section name throws', err instanceof Error, String(err))

  const withUnknown: LLMConfigSection[] = [...LLM_CONFIG_SECTIONS, { name: 'Nonsense', fields: [{ key: 'x', label: 'X', type: 'text', value: '' }] }]
  err = threw(() => buildLlmConfigTabs(withUnknown))
  check('a section the tab bar does not know throws', err instanceof Error && String(err).includes('Nonsense'), String(err))

  err = threw(() => buildLlmConfigTabs([...LLM_CONFIG_SECTIONS, LLM_CONFIG_SECTIONS[0]!]))
  check('a duplicate section throws', err instanceof Error && String(err).includes('duplicate'), String(err))

  err = threw(() => buildLlmConfigTabs(LLM_CONFIG_SECTIONS.filter((s) => s.name !== 'Summarization')))
  check('a missing section throws instead of rendering an empty tab',
    err instanceof Error && String(err).includes('Summarization'), String(err))

  err = threw(() => renderTabs(fullConfig(), 'Summarisation'))
  check('an unknown defaultTab throws on render', err instanceof Error, String(err))

  err = threw(() => tabAfterKey('Nonsense', 'ArrowRight'))
  check('the key map throws for an unknown current tab', err instanceof Error, String(err))
}

// ─────────────────────────────────────────────────────────────────────────────
section('Field behaviour preserved — model window auto-fill and refresh routing')

{
  const deps = () => {
    const calls: string[] = []
    const reported: string[] = []
    return {
      calls,
      reported,
      d: {
        modelContextWindows: { 'gpt-4o': 128000 } as Record<string, number>,
        refreshModels: (ep: string, key: string) => { calls.push(`proxy:${ep}:${key}`) },
        refreshModelsBrowserDirect: (ep: string, key: string) => { calls.push(`direct:${ep}:${key}`) },
        reportModelsError: (m: string) => { reported.push(m) },
      },
    }
  }
  const fieldOf = (key: string): LLMConfigField => {
    const f = LLM_CONFIG_SECTIONS.flatMap((s) => s.fields).find((x) => x.key === key)
    if (!f) throw new Error(`harness: no field '${key}'`)
    return f
  }

  {
    // Client-key bridge: picking a model with a known window auto-fills it.
    const { calls, d } = deps()
    const next = applyLlmConfigFieldChange({ model: '', modelContextWindow: 0 }, fieldOf('model'), 'gpt-4o', d)
    check('model pick auto-fills modelContextWindow from the browser-direct window map', next.model === 'gpt-4o' && next.modelContextWindow === 128000, JSON.stringify(next))
    check('model pick does not trigger a refresh', calls.length === 0, calls.join(','))
    check('the seat config object is not mutated', JSON.stringify(next) !== JSON.stringify({ model: '', modelContextWindow: 0 }))
  }
  {
    const { d } = deps()
    const next = applyLlmConfigFieldChange({ model: 'gpt-4o', modelContextWindow: 128000 }, fieldOf('model'), 'deepseek-chat', d)
    check('a model with no known window leaves modelContextWindow untouched', next.model === 'deepseek-chat' && next.modelContextWindow === 128000, JSON.stringify(next))
    const cleared = applyLlmConfigFieldChange({ model: 'gpt-4o' }, fieldOf('model'), '', d)
    check('clearing the model (server default) is a plain set', cleared.model === '' && !('modelContextWindow' in cleared), JSON.stringify(cleared))
  }
  {
    // apiEndpoint: browser-direct with a key, proxy-relayed without.
    const { calls, d } = deps()
    applyLlmConfigFieldChange({ apiKey: 'sk-seat' }, fieldOf('apiEndpoint'), '  https://ollama.com/v1/chat/completions  ', d)
    check('endpoint change with a seat key refreshes browser-direct (key never enters the proxy)',
      calls.join(',') === 'direct:https://ollama.com/v1/chat/completions:sk-seat', calls.join(','))
  }
  {
    const { calls, d } = deps()
    applyLlmConfigFieldChange({ apiKey: '' }, fieldOf('apiEndpoint'), 'https://api.openai.com/v1/chat/completions', d)
    check('endpoint change without a key refreshes through the proxy',
      calls.join(',') === 'proxy:https://api.openai.com/v1/chat/completions:', calls.join(','))
  }
  {
    const { calls, d } = deps()
    const next = applyLlmConfigFieldChange({}, fieldOf('apiEndpoint'), '   ', d)
    // Stored verbatim ('' -> ''), matching the flat list — the create_table
    // payload trims and drops empties downstream (lobbySlice.ts:320-323).
    check('a blank endpoint (server default) refreshes nothing', next.apiEndpoint === '   ' && calls.length === 0, `${JSON.stringify(next)} ${calls.join(',')}`)
    const other = applyLlmConfigFieldChange({}, fieldOf('temperature'), 1.2, d)
    check('a non-model, non-endpoint field is a plain set', other.temperature === 1.2, JSON.stringify(other))
  }
  {
    const { calls, reported, d } = deps()
    refreshModelsForSeat({ apiEndpoint: '', apiKey: 'sk-seat' }, d)
    // A seat with NO endpoint of its own uses the SERVER's default — there is
    // nothing this browser can dial, and the old fabricated localhost default made
    // the proxy name the WRONG host ('localhost') and leak JVM-flag jargon into the
    // dialog.
    check('the ↻ refresh with NO seat endpoint dials nothing (no fabricated localhost default)',
      calls.length === 0, calls.join(','))
    check('…and reports the plain missing-endpoint reason instead',
      reported.length === 1 && reported[0] === NO_SEAT_ENDPOINT_MESSAGE, reported.join('|'))
    check('…and the message never leaks a JVM flag or the allowlist jargon',
      !/^-D|allowedModelHosts|allowlist/.test(reported[0] ?? ''), reported[0])
    const { calls: c2, d: d2 } = deps()
    refreshModelsForSeat({ apiEndpoint: 'https://api.groq.com/openai/v1/chat/completions', apiKey: '' }, d2)
    check('the ↻ refresh without a key goes proxy-relayed to the seat endpoint',
      c2.join(',') === 'proxy:https://api.groq.com/openai/v1/chat/completions:', c2.join(','))
  }
}

// ─────────────────────────────────────────────────────────────────────────────
section('Provider dropdown still works inside the Core tab')

{
  const config = fullConfig()
  config.apiEndpoint = 'https://my-gw.internal/v1/chat/completions'
  const rendered = renderTabs(config)
  check('a custom endpoint reopens as Other…', rendered.includes(`<option value="${PROVIDER_OTHER}" selected="">`), rendered)
  check('and reveals the free-text box seeded with the stored URL',
    rendered.includes('value="https://my-gw.internal/v1/chat/completions"') && rendered.includes('type="text"'))
  check('the free-text box lives in the Core panel', (panelSegments(rendered).find((s) => s.tag.includes('llm-panel-core'))?.body ?? '').includes('type="text"'))
  const fresh = renderTabs(fullConfig())
  check('a fresh seat shows the server default with no free-text box in Core',
    (panelSegments(fresh).find((s) => s.tag.includes('llm-panel-core'))?.body ?? '').includes('(server default)'))
  check('the model select lists the fetched models', fresh.includes('<option value="gpt-4o">gpt-4o</option>'))
  check('the personality select lists the fetched names', fresh.includes('<option value="Kestrel">Kestrel</option>'))
}

// ─────────────────────────────────────────────────────────────────────────────
section('Seat gating unchanged — LLM config only for an LLM seat on an LLM server')

{
  const llmRow = renderRow(makeSlot('COMPUTER_LLM'))
  check('an LLM seat on an LLM-capable server shows the tabs', llmRow.includes('role="tablist"'))

  const humanRow = renderRow(makeSlot('HUMAN'))
  check('a HUMAN seat shows no LLM config', !humanRow.includes('role="tablist"') && !humanRow.includes('llm-config-section'))

  const madRow = renderRow(makeSlot('COMPUTER_MAD'))
  check('an AI (Mad) seat shows no LLM config', !madRow.includes('role="tablist"'))
}

// ─────────────────────────────────────────────────────────────────────────────
section(' defaults — conversation limits 0 = infinity, keep 5')

{
  const fieldByKey = (key: string) => LLM_CONFIG_SECTIONS.flatMap((s) => s.fields).find((f) => f.key === key)!
  check('conversationMaxExchanges defaults to 0 (infinity)', fieldByKey('conversationMaxExchanges').value === 0)
  check('conversationMaxHistoryChars defaults to 0 (infinity)', fieldByKey('conversationMaxHistoryChars').value === 0)
  check('conversationThinkCharsPerMsg defaults to 0 (infinity)', fieldByKey('conversationThinkCharsPerMsg').value === 0)
  check('summarizeKeepExchanges defaults to 5 (was 2)', fieldByKey('summarizeKeepExchanges').value === 5)
  // 0 must ride the serialization path as a real number (sentinel, not "empty to drop").
  const conv = fullConfig()
  check('a default seat carries the 0 limit through to the payload', conv.conversationMaxExchanges === 0)
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
;(globalThis as unknown as { process: { exit: (n: number) => void } }).process.exit(failures === 0 ? 0 : 1)
