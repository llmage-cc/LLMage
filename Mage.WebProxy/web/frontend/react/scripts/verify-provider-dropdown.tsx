/**
 * Standalone behavioural check for the LLM seat config's Provider dropdown

 *
 * Run with:  bash scripts/verify-provider-dropdown.sh
 *
 * Covers the feature end to end with NO browser, driving the REAL modules:
 *  (a) every listed provider's endpoint is routed exactly as the provider table
 *      says it must be (browser-direct vs /llm-relay) — the table and
 *      llmBridgeClient's RELAY_HOSTS can never drift apart,
 *  (b) a stored endpoint maps to the right select value (server default /
 *      provider id / Other…) and survives whitespace,
 *  (c) the pure select transition: provider -> its endpoint, server default ->
 *      empty, Other… -> the last custom URL (never a provider URL left behind),
 *      unknown/disabled option -> Fail-Fast throw,
 *  (d) the REAL field component renders: provider preselected, Other… reveals
 *      the free-text box seeded with the stored URL, Anthropic listed but
 *      disabled with its reason, the model-refresh button still present,
 *  (e) the REAL seat row (PlayerSlotRow) shows the Provider dropdown for an LLM
 *      seat whose llmConfig still defaults to the server default,
 *  (f) the relay-key-transit warning: the pure helper keys off the same
 *      isRelayHost rule as the routing, renders (role=alert,
 *      data-testid=relay-key-transit-warning) UNDER the Provider dropdown for
 *      every relay-host selection, and renders nothing for CORS-capable
 *      providers / the server default; it coexists with the thinking-shape
 *      warning on a strict relay endpoint.
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import { LLMConfigFieldInput } from '../src/components/LLMConfigFieldInput'
import { PlayerSlotRow } from '../src/components/PlayerSlotRow'
import {
  LLM_PROVIDERS,
  PROVIDER_OTHER,
  endpointSelectValue,
  LLM_CONFIG_SECTIONS,
} from '../src/utils/constants'
import { endpointSelectTransition } from '../src/components/LLMConfigFieldInput'
import { LLMConfigTabs, relayWarningFor, setupWarningFor } from '../src/components/LLMConfigTabs'
import { isRelayHost, relayKeyTransitWarning } from '../src/utils/llmBridgeClient'
import type { LLMConfigField, PlayerSlot } from '../src/types'

// react-dom/server reads zustand's getServerSnapshot (the INITIAL state), so
// point it at the live state to render the store-driven parts faithfully.
;(useGameStore as unknown as { getInitialState: () => unknown }).getInitialState =
  () => useGameStore.getState()

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

const fieldOf = (key: string): LLMConfigField => {
  const field = LLM_CONFIG_SECTIONS.flatMap((s) => s.fields).find((f) => f.key === key)
  if (!field) throw new Error(`no field '${key}' in LLM_CONFIG_SECTIONS`)
  return field
}

/** SSR the real field component for a value (the row's own call shape). */
const renderField = (value: unknown): string =>
  renderToStaticMarkup(
    <LLMConfigFieldInput
      field={fieldOf('apiEndpoint')}
      value={value}
      availableModels={['gpt-4o', 'deepseek-chat']}
      modelsLoading={false}
      availablePersonalities={['Kestrel']}
      onChange={() => {}}
      onRefreshModels={() => {}}
    />
  )

/** A player slot built exactly the way the store's makeSlot builds one. */
function makeSlot(defaultType: string, isYou: boolean): PlayerSlot {
  const slot: PlayerSlot = { type: defaultType, isYou, deck: '', showLLMConfig: defaultType === 'COMPUTER_LLM', llmConfig: {} }
  for (const sectionDef of LLM_CONFIG_SECTIONS) {
    for (const f of sectionDef.fields) slot.llmConfig[f.key] = f.value
  }
  return slot
}

// ─────────────────────────────────────────────────────────────────────────────
section('The field IS the provider dropdown, defaulting to the server default')

{
  const field = fieldOf('apiEndpoint')
  check('apiEndpoint field switched to endpoint_select', field.type === 'endpoint_select', field.type)
  check('label reads Provider', field.label === 'Provider', field.label)
  check('default value stays empty (server default applies downstream)', field.value === '', JSON.stringify(field.value))
  check('the raw endpoint field type is gone from the Core section',
    !LLM_CONFIG_SECTIONS.flatMap((s) => s.fields).some((f) => f.key === 'apiEndpoint' && f.type === 'text'))
}

// ─────────────────────────────────────────────────────────────────────────────
section('Provider table and relay routing cannot drift apart')

{
  const relayExtraHosts = ['hyper.charm.land', 'api.deepseek.com']
  for (const p of LLM_PROVIDERS) {
    const host = new URL(p.endpoint).hostname
    check(`${p.id}: isRelayHost matches needsRelay=${p.needsRelay}`, isRelayHost(p.endpoint) === p.needsRelay,
      `${host} routed ${isRelayHost(p.endpoint)}`)
  }
  for (const ep of relayExtraHosts) {
    check(`${ep}: still relay-routed (extra host, not a dropdown entry)`, isRelayHost(`https://${ep}/v1/chat/completions`))
  }
  check('openrouter.ai stays browser-direct (CORS: *)', !isRelayHost('https://openrouter.ai/api/v1/chat/completions'))
  check('a malformed endpoint is not a relay host', !isRelayHost('not a url'))
}

// ─────────────────────────────────────────────────────────────────────────────
section('Stored endpoint -> select value')

{
  check('empty string -> server default', endpointSelectValue('') === '')
  check('whitespace -> server default', endpointSelectValue('   ') === '')
  check('undefined -> server default', endpointSelectValue(undefined) === '')
  check('number -> server default (defensive, never rendered as a value)', endpointSelectValue(7) === '')
  for (const p of LLM_PROVIDERS) {
    check(`${p.id}: its endpoint maps back to its id`, endpointSelectValue(p.endpoint) === p.id)
  }
  check('a custom URL maps to Other…', endpointSelectValue('https://my-gw.internal/v1/chat/completions') === PROVIDER_OTHER)
  check('the same URL with surrounding whitespace still maps to Other… (and keeps classifying)',
    endpointSelectValue('  https://my-gw.internal/v1/chat/completions  ') === PROVIDER_OTHER)
  check('a near-miss URL is Other…, not a provider',
    endpointSelectValue('https://api.openai.com/v1/chat/completions/') === PROVIDER_OTHER)
}

// ─────────────────────────────────────────────────────────────────────────────
section('Select transition — picking an option decides the stored endpoint')

{
  const openai = LLM_PROVIDERS.find((p) => p.id === 'openai')!

  let t = endpointSelectTransition('openai', '', '')
  check('provider pick -> provider endpoint, textbox hidden',
    t.endpoint === openai.endpoint && t.otherMode === false, JSON.stringify(t))

  t = endpointSelectTransition('', openai.endpoint, '')
  check('(server default) -> empty endpoint, textbox hidden',
    t.endpoint === '' && t.otherMode === false, JSON.stringify(t))

  t = endpointSelectTransition(PROVIDER_OTHER, 'https://my-gw.internal/v1/chat/completions', '')
  check('Other… with a custom URL already stored keeps it',
    t.endpoint === 'https://my-gw.internal/v1/chat/completions' && t.otherMode === true, JSON.stringify(t))

  t = endpointSelectTransition(PROVIDER_OTHER, openai.endpoint, 'https://draft.example/v1/chat/completions')
  check('Other… right after a provider restores the typed draft (never the provider URL)',
    t.endpoint === 'https://draft.example/v1/chat/completions' && t.otherMode === true, JSON.stringify(t))

  t = endpointSelectTransition(PROVIDER_OTHER, '', '')
  check('Other… on a fresh seat starts empty for typing',
    t.endpoint === '' && t.otherMode === true, JSON.stringify(t))

  let threw: unknown = null
  try { endpointSelectTransition('anthropic', '', '') } catch (e) { threw = e }
  check('selecting the disabled Anthropic entry throws (Fail Fast)', threw instanceof Error, String(threw))

  threw = null
  try { endpointSelectTransition('no-such-provider', '', '') } catch (e) { threw = e }
  check('an unknown option id throws (Fail Fast)', threw instanceof Error, String(threw))
}

// ─────────────────────────────────────────────────────────────────────────────
section('Rendered field — the real component')

{
  const htmlDefault = renderField('')
  check('server default preselected in the select', /<option value="" selected="">\(server default\)<\/option>/.test(htmlDefault), htmlDefault)
  for (const p of LLM_PROVIDERS) {
    check(`${p.id} listed in the dropdown`, htmlDefault.includes(p.label), p.label)
  }
  check('no free-text endpoint box while a provider/default is selected', !htmlDefault.includes('type="text"'), htmlDefault)
  check('Anthropic option is disabled with its reason', htmlDefault.includes('disabled') && htmlDefault.includes('not supported yet'), htmlDefault)
  check('model-refresh button still rendered next to the model field family', htmlDefault.includes('llm-config-field'))

  const openai = LLM_PROVIDERS.find((p) => p.id === 'openai')!
  const htmlProvider = renderField(openai.endpoint)
  check('a stored provider endpoint preselects that provider',
    new RegExp(`<option value="openai" selected="">`).test(htmlProvider), htmlProvider)
  check('still no free-text box for a provider selection', !htmlProvider.includes('type="text"'), htmlProvider)

  const custom = 'https://my-gw.internal/v1/chat/completions'
  const htmlOther = renderField(custom)
  check('a custom endpoint selects Other…',
    new RegExp(`<option value="${PROVIDER_OTHER}" selected="">`).test(htmlOther), htmlOther)
  check('and reveals the free-text box seeded with the stored URL',
    htmlOther.includes(`value="${custom}"`) && htmlOther.includes('type="text"'), htmlOther)
}

// ─────────────────────────────────────────────────────────────────────────────
section('Rendered seat row — the real PlayerSlotRow')

{
  const slot = makeSlot('COMPUTER_LLM', false)
  check('a fresh LLM seat defaults apiEndpoint to the server default', slot.llmConfig.apiEndpoint === '')
  const html = renderToStaticMarkup(
    <PlayerSlotRow
      slot={slot}
      index={1}
      canRemove={false}
      onUpdate={() => {}}
      onRemove={() => {}}
      onDeckFile={() => {}}
    />
  )
  check('the seat row renders the Provider dropdown', html.includes('Provider'), html)
  check('and lists all providers', LLM_PROVIDERS.every((p) => html.includes(p.label)))
  // Scope the "no free-text box" check to the Provider field itself: other
  // fields in the seat (Transcript Dir) legitimately render text inputs.
  const providerFieldRegion = /<label>Provider<\/label>([\s\S]*?)<\/div><\/div>/.exec(html)?.[1] ?? ''
  check('no bare endpoint textbox inside the Provider field for a fresh seat',
    providerFieldRegion.length > 0 && !providerFieldRegion.includes('type="text"'), providerFieldRegion)

  const customSlot = makeSlot('COMPUTER_LLM', false)
  customSlot.llmConfig.apiEndpoint = 'https://legacy-seat.example/v1/chat/completions'
  const htmlCustom = renderToStaticMarkup(
    <PlayerSlotRow
      slot={customSlot}
      index={1}
      canRemove={false}
      onUpdate={() => {}}
      onRemove={() => {}}
      onDeckFile={() => {}}
    />
  )
  check('a pre-existing custom endpoint reopens as Other… with the URL intact',
    htmlCustom.includes('https://legacy-seat.example/v1/chat/completions') && htmlCustom.includes(PROVIDER_OTHER), htmlCustom)
}

// ─────────────────────────────────────────────────────────────────────────────
section('Relay-key-transit warning — under the Provider dropdown, only for relay hosts')

{
  // Pure helper: keyed off the SAME rule as the routing (isRelayHost).
  for (const p of LLM_PROVIDERS) {
    const w = relayKeyTransitWarning(p.endpoint)
    check(`${p.id}: warning ${p.needsRelay ? 'present' : 'absent'} (matches needsRelay=${p.needsRelay})`,
      p.needsRelay ? w !== '' : w === '', w)
  }
  check('the extra relay hosts warn too (hyper.charm.land)', relayKeyTransitWarning('https://hyper.charm.land/v1/chat/completions') !== '')
  check('the extra relay hosts warn too (api.deepseek.com)', relayKeyTransitWarning('https://api.deepseek.com/v1/chat/completions') !== '')
  check('the server default warns nothing (no endpoint selected yet)', relayKeyTransitWarning('') === '')
  check('a malformed endpoint warns nothing', relayKeyTransitWarning('not a url') === '')
  check('the warning says the key transits the relay and is not stored/logged',
    relayKeyTransitWarning('https://opencode.ai/zen/go/v1/chat/completions').includes('relay') &&
    relayKeyTransitWarning('https://opencode.ai/zen/go/v1/chat/completions').includes('never stored') &&
    relayKeyTransitWarning('https://opencode.ai/zen/go/v1/chat/completions').includes('OpenAI'))
  check('relayWarningFor reads the seat config endpoint', relayWarningFor({ apiEndpoint: ' https://opencode.ai/zen/go/v1/chat/completions ' }) !== '')
  check('relayWarningFor stays empty without an endpoint', relayWarningFor({}) === '')

  // Combined helper: Provider field -> transit warning, Thinking Enabled -> shape probe.
  const relaySeat = { apiEndpoint: 'https://opencode.ai/zen/go/v1/chat/completions', thinkingEnabled: true }
  const provWarn = setupWarningFor(relaySeat, 'apiEndpoint', undefined)
  check('setupWarningFor(apiEndpoint) returns the relay testid', provWarn?.testId === 'relay-key-transit-warning', JSON.stringify(provWarn))
  check('the relay warning names this deployment as the relay hop', provWarn?.message.includes('through our server') === true, JSON.stringify(provWarn))
  const thinkWarn = setupWarningFor(relaySeat, 'thinkingEnabled', undefined)
  check('setupWarningFor(thinkingEnabled) still returns the shape probe', thinkWarn?.testId === 'thinking-shape-warning', JSON.stringify(thinkWarn))
  check('setupWarningFor on a non-warning field returns undefined', setupWarningFor(relaySeat, 'model', undefined) === undefined)

  // Rendered: the REAL tabs with a relay-host endpoint show the warning under
  // the Provider field — inside the Core panel where the dropdown lives.
  const relayConfig: Record<string, unknown> = makeSlot('COMPUTER_LLM', false).llmConfig
  relayConfig.apiEndpoint = 'https://opencode.ai/zen/go/v1/chat/completions'
  const relayHtml = renderToStaticMarkup(<LLMConfigTabs config={relayConfig} onConfigChange={() => {}} />)
  check('a relay endpoint selection renders the transit warning', relayHtml.includes('data-testid="relay-key-transit-warning"'), relayHtml.slice(0, 300))
  check('the transit warning is role=alert',
    /role="alert"[^>]*data-testid="relay-key-transit-warning"|data-testid="relay-key-transit-warning"[^>]*role="alert"/.test(relayHtml))
  const relayMarkerAt = relayHtml.indexOf('relay-key-transit-warning')
  const providerLabelAt = relayHtml.indexOf('<label>Provider</label>')
  check('the warning sits right under the Provider field', providerLabelAt >= 0 && relayMarkerAt > providerLabelAt,
    `provider=${providerLabelAt} marker=${relayMarkerAt}`)
  check('the transit warning does NOT repeat in other panels', relayHtml.split('relay-key-transit-warning').length - 1 === 1)
  check('the transit warning text is present', relayHtml.includes('passes through the relay'))

  // The seat row renders it too (tabs live inside the row).
  const relaySlot = makeSlot('COMPUTER_LLM', false)
  relaySlot.llmConfig.apiEndpoint = 'https://opencode.ai/zen/go/v1/chat/completions'
  const relayRow = renderToStaticMarkup(
    <PlayerSlotRow slot={relaySlot} index={1} canRemove={false} onUpdate={() => {}} onRemove={() => {}} onDeckFile={() => {}} />
  )
  check('the seat row shows the transit warning for a relay seat', relayRow.includes('relay-key-transit-warning'))

  // CORS providers and the server default render NO transit warning.
  const openaiConfig: Record<string, unknown> = makeSlot('COMPUTER_LLM', false).llmConfig
  openaiConfig.apiEndpoint = 'https://api.openai.com/v1/chat/completions'
  const openaiHtml = renderToStaticMarkup(<LLMConfigTabs config={openaiConfig} onConfigChange={() => {}} />)
  check('a browser-direct provider renders no transit warning', !openaiHtml.includes('relay-key-transit-warning'))
  const defaultHtml = renderToStaticMarkup(<LLMConfigTabs config={makeSlot('COMPUTER_LLM', false).llmConfig} onConfigChange={() => {}} />)
  check('the server default renders no transit warning', !defaultHtml.includes('relay-key-transit-warning'))
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
;(globalThis as unknown as { process: { exit: (n: number) => void } }).process.exit(failures === 0 ? 0 : 1)
