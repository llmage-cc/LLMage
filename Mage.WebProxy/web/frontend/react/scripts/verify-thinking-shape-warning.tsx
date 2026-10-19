/**
 * Standalone behavioural check for the setup-time thinking-shape warning

 *
 * Run with:  bash scripts/verify-thinking-shape-warning.sh
 *
 * Covers, through the REAL modules (no browser, react-dom/server + store stubs):
 *  (a) the pure classification (utils/thinkingShape.ts) has the ENGINE's truth
 *      table — Ollama-classified (or budget dialect) = thinking{} object,
 *      non-Ollama + effort = bare reasoning_effort, disabled = off — including
 *      the incident endpoint (opencode zen/go) and the localhost Ollama default,
 *  (b) the proxy's verdict recorded from models_list flows into the seat form:
 *      LLMConfigTabs renders the warning NEXT TO the Thinking Enabled checkbox
 *      (data-testid=thinking-shape-warning, role=alert) for a strict endpoint
 *      with thinking enabled, and renders NO warning for an Ollama endpoint,
 *      for thinking disabled, or for non-thinking fields,
 *  (c) the proxy's llm_setup_warning surfaces in the NAMED seat's row
 *      (data-testid=llm-setup-warning) and in no other seat's row,
 *  (d) messageHandlers records the models_list classification into the store
 *      and routes llm_setup_warning; absent classification fields do NOT wipe
 *      or alter the map (older proxies stay compatible),
 *  (e) the dialog lifecycle clears the warning (showGameSetup / hideGameSetup).
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import { PlayerSlotRow } from '../src/components/PlayerSlotRow'
import { LLMConfigTabs, thinkingShapeWarningFor, seatThinkingShape } from '../src/components/LLMConfigTabs'
import { LLM_CONFIG_SECTIONS } from '../src/utils/constants'
import { thinkingShapeFor, isOllamaEndpoint, strictThinkingWarning } from '../src/utils/thinkingShape'
import { handleMessage } from '../src/network/messageHandlers'
import type { ServerMessage, PlayerSlot } from '../src/types'

// react-dom/server reads zustand's getServerSnapshot (the INITIAL state object).
// Mirror every setState into it so server renders see the scenario — same
// working lever as verify-llm-config-tabs.tsx.
const ssrSnapshot = (useGameStore as unknown as { getInitialState: () => Record<string, unknown> }).getInitialState()
const setLive = (patch: Record<string, unknown>) => {
  useGameStore.setState(patch as never)
  Object.assign(ssrSnapshot, patch)
}

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

const ZEN_GO = 'https://opencode.ai/zen/go/v1/chat/completions'
const OPENAI = 'https://api.openai.com/v1/chat/completions'
const OLLAMA_CLOUD = 'https://ollama.com/v1/chat/completions'
const OLLAMA_LOCAL = 'http://localhost:11434/v1/chat/completions'

// ─────────────────────────────────────────────────────────────────────────────
section("Classification — the engine's truth table (utils/thinkingShape.ts)")

{
  check('opencode zen/go is NOT Ollama-classified (the incident endpoint)', !isOllamaEndpoint(ZEN_GO))
  check('api.openai.com is NOT Ollama-classified', !isOllamaEndpoint(OPENAI))
  check('ollama.com IS Ollama-classified', isOllamaEndpoint(OLLAMA_CLOUD))
  check('localhost:11434 IS Ollama-classified (default port)', isOllamaEndpoint(OLLAMA_LOCAL))

  check('zen/go + thinking enabled = bare effort (strict)', thinkingShapeFor(ZEN_GO, undefined, true) === 'effort')
  check('zen/go + thinking disabled = off', thinkingShapeFor(ZEN_GO, undefined, false) === 'off')
  check('ollama cloud + thinking enabled = thinking object', thinkingShapeFor(OLLAMA_CLOUD, undefined, true) === 'object')
  check('localhost ollama + thinking enabled = thinking object', thinkingShapeFor(OLLAMA_LOCAL, undefined, true) === 'object')
  check('budget dialect = object on ANY endpoint (object-based by definition)', thinkingShapeFor(ZEN_GO, 'budget', true) === 'object')
  check('disabled wins over the budget dialect', thinkingShapeFor(ZEN_GO, 'budget', false) === 'off')
  check('unknown dialect strings fall through to the endpoint rule (engine behaviour)', thinkingShapeFor(ZEN_GO, 'weird', true) === 'effort')

  const w = strictThinkingWarning(ZEN_GO, undefined, true)
  check('the strict warning names the shape, the failure and the fix',
    w.includes('reasoning_effort') && w.includes('400') && w.includes('Thinking Enabled') && w.includes('mulligan'), w)
  check('no warning for an Ollama-classified endpoint', strictThinkingWarning(OLLAMA_LOCAL, undefined, true) === '')
  check('no warning when thinking is disabled', strictThinkingWarning(ZEN_GO, undefined, false) === '')
}

// ─────────────────────────────────────────────────────────────────────────────
section('Store + message dispatch — models_list classification and llm_setup_warning')

{
  setLive({ thinkingEndpointClass: {}, llmSetupWarning: null, availableModels: [], modelsLoading: true })
  handleMessage(
    { type: 'models_list', models: ['glm-5.3-flash'], endpoint: ZEN_GO, ollamaClassified: false } as unknown as ServerMessage,
    useGameStore.getState()
  )
  const state = useGameStore.getState()
  check('models_list records the endpoint verdict in the store', state.thinkingEndpointClass[ZEN_GO] === false, JSON.stringify(state.thinkingEndpointClass))

  handleMessage(
    { type: 'models_list', models: ['m'], endpoint: OLLAMA_LOCAL, ollamaClassified: true } as unknown as ServerMessage,
    useGameStore.getState()
  )
  check('an Ollama-classified endpoint is recorded as such', useGameStore.getState().thinkingEndpointClass[OLLAMA_LOCAL] === true)

  // older proxies: no classification fields — the map must be untouched
  handleMessage({ type: 'models_list', models: ['x'] } as unknown as ServerMessage, useGameStore.getState())
  check('a classification-less models_list does not wipe the map', Object.keys(useGameStore.getState().thinkingEndpointClass).length === 2)

  handleMessage(
    { type: 'llm_setup_warning', seat: 'AI-1', message: 'AI-1: strict endpoint warning text' } as unknown as ServerMessage,
    useGameStore.getState()
  )
  const warn = useGameStore.getState().llmSetupWarning
  check('llm_setup_warning is stored with seat + message', warn?.seat === 'AI-1' && warn?.message.includes('strict endpoint'), JSON.stringify(warn))
}

// ─────────────────────────────────────────────────────────────────────────────
section('Seat form — the warning renders next to Thinking Enabled, from the proxy verdict')

function makeSlot(type: string, config: Record<string, unknown>): PlayerSlot {
  const slot: PlayerSlot = { type, isYou: false, deck: '', showLLMConfig: type === 'COMPUTER_LLM', llmConfig: {} }
  for (const sectionDef of LLM_CONFIG_SECTIONS) {
    for (const f of sectionDef.fields) slot.llmConfig[f.key] = f.value
  }
  slot.llmConfig = { ...slot.llmConfig, ...config }
  return slot
}

const renderRow = (slot: PlayerSlot): string =>
  renderToStaticMarkup(
    <PlayerSlotRow slot={slot} index={1} canRemove={false} onUpdate={() => {}} onRemove={() => {}} onDeckFile={() => {}} />
  )

const renderTabs = (config: Record<string, unknown>): string =>
  renderToStaticMarkup(<LLMConfigTabs config={config} onConfigChange={() => {}} />)

{
  // Proxy verdict present: zen/go strict → warn next to the checkbox.
  setLive({ thinkingEndpointClass: { [ZEN_GO]: false }, llmSetupWarning: null })
  const seat = makeSlot('COMPUTER_LLM', { apiEndpoint: ZEN_GO, thinkingEnabled: true })
  const html = renderTabs(seat.llmConfig)
  check('strict endpoint + thinking enabled renders the checkbox warning', html.includes('data-testid="thinking-shape-warning"'), html.slice(0, 200))
  check('the warning is role=alert', /role="alert"[^>]*data-testid="thinking-shape-warning"|data-testid="thinking-shape-warning"[^>]*role="alert"/.test(html))
  // The marker must sit between the CORE panel's opening tag and the next
  // panel's — i.e. inside the panel that carries the Thinking Enabled checkbox.
  // 'llm-panel-core' appears twice (tab aria-controls + panel id); the panel's
  // own opening tag is the LAST occurrence, followed by the fields.
  const corePanelAt = html.lastIndexOf('llm-panel-core')
  const markerAt = html.indexOf('thinking-shape-warning')
  const behaviorPanelAt = html.lastIndexOf('llm-panel-behavior')
  check('the warning sits in the Core panel (where Thinking Enabled lives)',
    corePanelAt >= 0 && markerAt > corePanelAt && (behaviorPanelAt < 0 || markerAt < behaviorPanelAt),
    `core=${corePanelAt} marker=${markerAt} behavior=${behaviorPanelAt}`)
  check('the warning carries the fix (uncheck Thinking Enabled)', html.includes('uncheck Thinking Enabled'))
  check('the warning does NOT repeat in other panels', html.split('thinking-shape-warning').length - 1 === 1)

  // Same endpoint, thinking UNCHECKED → no warning.
  const off = makeSlot('COMPUTER_LLM', { apiEndpoint: ZEN_GO, thinkingEnabled: false })
  check('thinking unchecked on a strict endpoint shows no checkbox warning', !renderTabs(off.llmConfig).includes('thinking-shape-warning'))

  // Ollama endpoint → no warning.
  setLive({ thinkingEndpointClass: { [OLLAMA_LOCAL]: true }, llmSetupWarning: null })
  const ollama = makeSlot('COMPUTER_LLM', { apiEndpoint: OLLAMA_LOCAL, thinkingEnabled: true })
  check('an Ollama-classified endpoint shows no checkbox warning', !renderTabs(ollama.llmConfig).includes('thinking-shape-warning'))

  // Proxy verdict recorded for a DIFFERENT endpoint must not silence the
  // seat's own deterministic classification.
  setLive({ thinkingEndpointClass: { [OLLAMA_LOCAL]: true }, llmSetupWarning: null })
  const unfetched = makeSlot('COMPUTER_LLM', { apiEndpoint: OPENAI, thinkingEnabled: true })
  check('an endpoint without its own verdict still warns (own classification)', renderTabs(unfetched.llmConfig).includes('thinking-shape-warning'))

  // Pure helper contract: non-thinking fields never warn.
  check('only the thinkingEnabled field can warn', thinkingShapeWarningFor({ thinkingEnabled: true, apiEndpoint: ZEN_GO }, 'model', false) === '')
  check('the helper honours the proxy verdict (ollama → no warning)', thinkingShapeWarningFor({ thinkingEnabled: true, apiEndpoint: ZEN_GO }, 'thinkingEnabled', true) === '')
  check('the helper warns on a strict proxy verdict', thinkingShapeWarningFor({ thinkingEnabled: true, apiEndpoint: ZEN_GO }, 'thinkingEnabled', false) !== '')
  check('seatThinkingShape mirrors the engine for the incident seat', seatThinkingShape({ apiEndpoint: ZEN_GO, thinkingEnabled: true }) === 'effort')
}

// ─────────────────────────────────────────────────────────────────────────────
section('Proxy llm_setup_warning — surfaces in the NAMED seat only')

{
  setLive({ thinkingEndpointClass: {}, llmSetupWarning: { seat: 'AI-1', message: 'AI-1 (glm-5.3-flash): strict endpoint — uncheck Thinking Enabled' } })
  const seat0 = renderRow(makeSlot('COMPUTER_LLM', { apiEndpoint: ZEN_GO }))
  const seat1 = renderRow(makeSlot('COMPUTER_LLM', { apiEndpoint: ZEN_GO }))
  // seat index 0 vs 1: the row under test is index 1 (AI-1)
  check('the named seat (AI-1) renders the proxy warning', seat1.includes('data-testid="llm-setup-warning"') && seat1.includes('glm-5.3-flash'), '')
  check('the warning is role=alert inside the LLM block', /class="llm-setup-warning"/.test(seat1))
  setLive({ llmSetupWarning: { seat: 'AI-2', message: 'AI-2: other seat' } })
  check('a warning for ANOTHER seat renders nothing in this row', !renderRow(makeSlot('COMPUTER_LLM', { apiEndpoint: ZEN_GO })).includes('llm-setup-warning'))
  setLive({ llmSetupWarning: null })
  check('no warning state → no warning rendered', !renderRow(makeSlot('COMPUTER_LLM', { apiEndpoint: ZEN_GO })).includes('llm-setup-warning'))

  // A HUMAN seat never renders the LLM block (nor the warning).
  const human = renderRow(makeSlot('HUMAN', {}))
  check('a HUMAN seat shows no LLM block and no warning', !human.includes('llm-config-section') && !human.includes('llm-setup-warning'))
}

// ─────────────────────────────────────────────────────────────────────────────
section('Dialog lifecycle — the warning clears when the setup dialog opens/closes')

{
  setLive({ llmSetupWarning: { seat: 'AI-1', message: 'x' } })
  useGameStore.getState().showGameSetup()
  check('showGameSetup clears a stale warning', useGameStore.getState().llmSetupWarning === null)
  useGameStore.getState().setLlmSetupWarning({ seat: 'AI-1', message: 'x' })
  useGameStore.getState().hideGameSetup()
  check('hideGameSetup clears the warning', useGameStore.getState().llmSetupWarning === null)
  useGameStore.getState().setLlmSetupWarning({ seat: 'AI-1', message: 'x' })
  useGameStore.getState().showTournamentSetupDialog()
  check('showTournamentSetupDialog clears a stale warning', useGameStore.getState().llmSetupWarning === null)
  useGameStore.getState().setLlmSetupWarning({ seat: 'AI-1', message: 'x' })
  useGameStore.getState().hideTournamentSetup()
  check('hideTournamentSetup clears the warning', useGameStore.getState().llmSetupWarning === null)
}

// ─────────────────────────────────────────────────────────────────────────────
section('D1 — the pending warning re-surfaces as a toast when the dialog closes')

{
  // The defect: the proxy emits llm_setup_warning BEFORE table_created,
  // and table_created closes the setup dialog — the warning was stored but the
  // seat form (its only render surface) was already unmounted, and reopening
  // New Game cleared it. Fix: the dialog-close points re-surface the pending
  // warning through the store's error toast (the socket-down action toast surface).

  // Match path: setTableCreated.
  setLive({ llmSetupWarning: null, errorToastVisible: false, errorMessage: '' })
  useGameStore.getState().setLlmSetupWarning({ seat: 'AI-1', message: 'AI-1 (glm-5.3-flash): strict endpoint — uncheck Thinking Enabled' })
  useGameStore.getState().setTableCreated('table-1', 'Web Game')
  check('setTableCreated raises the error toast for the pending warning', useGameStore.getState().errorToastVisible === true)
  check('the toast carries the warning text', useGameStore.getState().errorMessage.includes('strict endpoint'), useGameStore.getState().errorMessage)
  check('the dialog closed as before', useGameStore.getState().showSetup === false)
  check('table ownership recorded as before', useGameStore.getState().myTableId === 'table-1' && useGameStore.getState().isTableOwner === true)

  // No warning → no toast (the happy path stays silent).
  setLive({ llmSetupWarning: null, errorToastVisible: false, errorMessage: '' })
  useGameStore.getState().setTableCreated('table-2', 'Web Game')
  check('a create WITHOUT a pending warning raises no toast', useGameStore.getState().errorToastVisible === false)

  // Tournament path: setTournamentCreated (create_tournament emits the same warning).
  setLive({ llmSetupWarning: null, errorToastVisible: false, errorMessage: '', showTournamentSetup: true })
  useGameStore.getState().setLlmSetupWarning({ seat: 'AI-2', message: 'AI-2: tournament seat strict warning' })
  useGameStore.getState().setTournamentCreated('tour-1', 'Web Tournament')
  check('setTournamentCreated raises the toast for its pending warning', useGameStore.getState().errorToastVisible === true)
  check('the tournament toast carries the warning text', useGameStore.getState().errorMessage.includes('AI-2'), useGameStore.getState().errorMessage)
  check('the tournament dialog closed as before', useGameStore.getState().showTournamentSetup === false)
  setLive({ showTournamentSetup: false, errorToastVisible: false, errorMessage: '' })
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
;(globalThis as unknown as { process: { exit: (n: number) => void } }).process.exit(failures === 0 ? 0 : 1)