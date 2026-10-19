import type { LLMConfigSection } from '@/types'

/**
 * Supported providers for the seat's endpoint dropdown (LLM seat config).
 *
 * The stored value stays the raw chat-completions URL ('' = server default), so
 * nothing downstream (create_table, model fetch, relay routing) changes with
 * the dropdown. `needsRelay` mirrors the browser-key bridge's routing:
 * providers WITHOUT CORS headers reach the provider through the same-origin
 * /llm-relay (llmBridgeClient derives its RELAY_HOSTS from this table — the
 * verify-provider-dropdown harness pins the two together). `disabledReason`
 * marks an entry that is listed but not selectable.
 *
 * Deployment coupling: every endpoint host must be on the proxy's
 * -Dwebproxy.allowedModelHosts allowlist (fetch_models, seat endpoints, relay).
 */
export interface LlmProviderOption {
  id: string
  label: string
  endpoint: string
  /** No CORS on the provider — browser-key calls route via /llm-relay. */
  needsRelay: boolean
  /** Present = not selectable; the reason is shown in the option label. */
  disabledReason?: string
}

export const LLM_PROVIDERS: LlmProviderOption[] = [
  { id: 'openai', label: 'OpenAI', endpoint: 'https://api.openai.com/v1/chat/completions', needsRelay: false },
  { id: 'openrouter', label: 'OpenRouter', endpoint: 'https://openrouter.ai/api/v1/chat/completions', needsRelay: false },
  { id: 'groq', label: 'Groq', endpoint: 'https://api.groq.com/openai/v1/chat/completions', needsRelay: false },
  { id: 'opencode-go', label: 'OpenCode Go', endpoint: 'https://opencode.ai/zen/go/v1/chat/completions', needsRelay: true },
  { id: 'ollama-cloud', label: 'Ollama Cloud', endpoint: 'https://ollama.com/v1/chat/completions', needsRelay: true },
  { id: 'ollama-local', label: 'Local Ollama', endpoint: 'http://localhost:11434/v1/chat/completions', needsRelay: false },
  {
    id: 'anthropic',
    label: 'Anthropic',
    endpoint: 'https://api.anthropic.com/v1/messages',
    needsRelay: false,
    disabledReason: 'server-side request format not supported yet',
  },
]

/** Select value for the free-text fallback option (never a stored endpoint). */
export const PROVIDER_OTHER = '__other__'

/**
 * Map a stored endpoint to the dropdown's select value: '' (server default), a
 * provider id, or PROVIDER_OTHER for any custom URL. Pure — the component's
 * rendered state derives from the stored value, so a reload/edit shows the
 * right option without extra bookkeeping.
 */
export function endpointSelectValue(endpoint: unknown): string {
  const ep = typeof endpoint === 'string' ? endpoint.trim() : ''
  if (!ep) return ''
  const match = LLM_PROVIDERS.find((p) => p.endpoint === ep)
  return match ? match.id : PROVIDER_OTHER
}

/**
 * LLM config sections — ported from app.js init() llmConfigSections.
 * These define the fields shown in the GameSetupDialog for each LLM player slot.
 */
export const LLM_CONFIG_SECTIONS: LLMConfigSection[] = [
  { name: 'Core', fields: [
    { key: 'model', label: 'Model', type: 'model_select', value: '', placeholder: '(server default)' },
    { key: 'apiEndpoint', label: 'Provider', type: 'endpoint_select', value: '', placeholder: 'e.g. https://your-provider.example/v1/chat/completions' },
    { key: 'apiKey', label: 'API Key', type: 'password', value: '', placeholder: '(server default — or enter a key: it stays in YOUR browser, never stored on the server)' },
    { key: 'reasoningEffort', label: 'Reasoning Effort', type: 'select', options: ['default','low','medium','high','max'], value: 'default' },
    { key: 'thinkingEnabled', label: 'Thinking Enabled', type: 'checkbox', value: true },
    { key: 'temperatureEnabled', label: 'Override Temperature', type: 'checkbox', value: false },
    { key: 'temperature', label: 'Temperature', type: 'number', value: 0.7, min: 0, max: 2, step: 0.1 },
    { key: 'maxTokens', label: 'Max Output Tokens', type: 'number', value: 0, min: 0, max: 131072, step: 256 },
    { key: 'timeoutSeconds', label: 'Timeout (sec)', type: 'number', value: 600, min: 10, max: 3600, step: 10 },
  ]},
  { name: 'Behavior', fields: [
    { key: 'streamingEnabled', label: 'Streaming', type: 'checkbox', value: true },
    { key: 'verboseLogging', label: 'Verbose Logging', type: 'checkbox', value: false },
    { key: 'deckAssessmentEnabled', label: 'Deck Assessment', type: 'checkbox', value: true },
    { key: 'concedeEnabled', label: 'Allow Concede', type: 'checkbox', value: true },
    { key: 'askReason', label: 'Ask for REASON line (hidden-reasoning models)', type: 'checkbox', value: false },
    { key: 'transcriptDir', label: 'Transcript Dir', type: 'text', value: '', placeholder: '(disabled)' },
  ]},
  { name: 'Personality', fields: [
    { key: 'personalityEnabled', label: 'Personality Enabled', type: 'checkbox', value: true },
    { key: 'personalityName', label: 'Personality', type: 'personality_select', value: '', placeholder: '(auto-detect)' },
    { key: 'botName', label: 'Bot Name', type: 'personality_select', value: '', placeholder: '(random)' },
  ]},
  { name: 'Conversation', fields: [
    // all three limits default to infinity, expressed as 0
    // (0 = unlimited; negative is rejected server-side Fail-Fast).
    { key: 'conversationMaxExchanges', label: 'Max Exchanges', type: 'number', value: 0, min: 0, max: 99999, step: 1 },
    { key: 'conversationMaxHistoryChars', label: 'Max History Chars', type: 'number', value: 0, min: 0, max: 99999999, step: 1000 },
    { key: 'conversationThinkCharsPerMsg', label: 'Think Chars/Msg', type: 'number', value: 0, min: 0, max: 99999999, step: 1000 },
  ]},
  { name: 'Summarization', fields: [
    { key: 'summarizeTriggerChars', label: 'Trigger Chars', type: 'number', value: 0, min: 0, max: 1000000, step: 1000 },
    { key: 'summarizeContextRatio', label: 'Context Ratio', type: 'number', value: 0.65, min: 0, max: 1, step: 0.05 },
    { key: 'modelContextWindow', label: 'Context Window (0=auto)', type: 'number', value: 0, min: 0, max: 10000000, step: 4096 },
    { key: 'summarizeTargetChars', label: 'Target Chars', type: 'number', value: 0, min: 0, max: 1000000, step: 500 },
    { key: 'summarizeTargetRatio', label: 'Target Ratio', type: 'number', value: 0.25, min: 0, max: 1, step: 0.05 },
    // default 5 (was 2).
    { key: 'summarizeKeepExchanges', label: 'Keep Exchanges', type: 'number', value: 5, min: 0, max: 20, step: 1 },
  ]},
]

/** Game types — from app.js gameTypes array. */
export const GAME_TYPES = [
  'Two Player Duel',
  'Free For All',
  'Commander Two Player Duel',
  'Commander Free For All',
  'Tiny Leaders Two Player Duel',
  'Canadian Highlander Two Player Duel',
  'Penny Dreadful Commander Free For All',
  'Freeform Commander Two Player Duel',
  'Freeform Commander Free For All',
  'Freeform Unlimited Commander',
  'Oathbreaker Two Player Duel',
  'Oathbreaker Free For All',
  'Brawl Two Player Duel',
  'Brawl Free For All',
  'Momir Basic Two Player Duel',
  'Momir Basic Free For All',
]

/** Deck types — from app.js deckTypes array. */
export const DECK_TYPES = [
  'Constructed - Standard',
  'Constructed - Extended',
  'Constructed - Frontier',
  'Constructed - Pioneer',
  'Constructed - Modern',
  'Constructed - Modern - No Banned List',
  'Constructed - Eternal',
  'Constructed - Legacy',
  'Constructed - Vintage',
  'Constructed - Pauper',
  'Constructed - Historic',
  'Constructed - Historical Type 2',
  'Constructed - Super Type 2',
  'Constructed - Australian Highlander',
  'Constructed - Canadian Highlander',
  'Constructed - European Highlander',
  'Constructed - Old School 93/94',
  'Constructed - Old School 93/94 - Italian Rules',
  'Constructed - Old School 93/94 - Channel Fireball Rules',
  'Constructed - Old School 93/94 - EudoGames Rules',
  'Constructed - Old School 93/94 - EC Rules',
  'Constructed - Premodern',
  'Constructed - Freeform',
  'Constructed - Freeform Unlimited',
  'Variant Magic - Commander',
  'Variant Magic - Duel Commander',
  'Variant Magic - MTGO 1v1 Commander',
  'Variant Magic - Centurion Commander',
  'Variant Magic - Tiny Leaders',
  'Variant Magic - Momir Basic',
  'Variant Magic - Penny Dreadful Commander',
  'Variant Magic - Freeform Commander',
  'Variant Magic - Freeform Unlimited Commander',
  'Variant Magic - Brawl',
  'Variant Magic - Oathbreaker',
  'Limited',
]

/** Default endpoint for fetching LLM models — the local Ollama chat-completions URL. */
export const DEFAULT_LLM_ENDPOINT = 'http://localhost:11434/v1/chat/completions'

/**
 * Shown when the seat's own Provider/endpoint is left empty (the "(server default)"
 * placeholder) and the user asks to list models for it. The seat then uses the
 * SERVER's configured endpoint, which this browser does not know — so instead of
 * dialing a fabricated localhost default (which the proxy's host allowlist rejects
 * while naming the WRONG host), the UI says plainly that there is nothing to fetch
 * here. Kept as one
 * definition so the seat-row message and the store guard cannot drift.
 */
export const NO_SEAT_ENDPOINT_MESSAGE =
  'This seat has no model endpoint set — it uses the server\'s configured default. ' +
  'Set a Provider/endpoint to list its models here.'