import type { LlmPromptMessage, LlmDecisionRequest, LlmErrorRequest } from '@/types'
import { LLM_PROVIDERS } from '@/utils/constants'

/**
 * Browser-side client-key LLM bridge.
 *
 * The server sends an llm_prompt with the exact OpenAI-compatible request
 * body it would have POSTed. This module attaches the user's API key —
 * which is NEVER sent to the server or proxy — and performs the call:
 *
 *   1. Direct browser→provider fetch (OpenAI, Groq, OpenRouter, Anthropic…
 *      providers with CORS support).
 *   2. Same-origin relay fallback (/llm-relay?url=…) for providers without
 *      CORS (charm.land, ollama.com) — the relay adds CORS headers and
 *      echoes bytes; it does not read, log, or store Authorization.
 *
 * The key lives in module memory only: never localStorage, sessionStorage,
 * or cookies. A page refresh drops it; the seat concedes at timeout unless
 * the user re-enters it. That trade-off is deliberate (keys on disk in a
 * browser are keys in every XSS's reach).
 */

// ── Key store (tag-keyed, memory only) ─────────────────────────────────
//
// Keys are stored under browser-generated opaque tags (clientKeyTag), not
// seat UUIDs: the browser learns seat UUIDs only at prompt time, so at setup
// time it tags each client-key seat and sends useClientKey+clientKeyTag in
// the seat config. The tag rides to the prompt payload and selects the key.

const seatKeys = new Map<string, string>()

export function setSeatKey(tag: string, key: string): void {
  const trimmed = key.trim()
  if (trimmed) {
    seatKeys.set(tag, trimmed)
  } else {
    seatKeys.delete(tag)
  }
}

export function hasSeatKey(tag: string): boolean {
  return seatKeys.has(tag)
}

export function clearSeatKeys(): void {
  seatKeys.clear()
}

/** Fresh tag for a newly configured client-key seat. */
export function newClientKeyTag(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return 'k-' + Math.random().toString(36).slice(2) + Date.now().toString(36)
}

/** @internal — the stored key must never be readable from other modules. */
function getSeatKey(tag: string): string {
  const key = seatKeys.get(tag)
  if (!key) {
    throw new Error(
      `No API key stored for tag ${tag} — re-enter the key in the game setup (keys are memory-only and do not survive a page refresh)`
    )
  }
  return key
}

// ── Provider call ──────────────────────────────────────────────────────

/** Same-origin CORS relay path on the proxy (charm/ollama fallback). */
const RELAY_PATH = '/llm-relay'

/**
 * Providers known to lack CORS headers — always go through the relay.
 *
 * Derived from the seat-form provider table (LLM_PROVIDERS.needsRelay) so the
 * dropdown and the routing can never disagree; the verify-provider-dropdown
 * harness pins them together. The extra hosts are not dropdown entries but
 * keep their relay routing for seats configured outside the table:
 * hyper.charm.land (operator-default charm endpoint) and api.deepseek.com.
 *
 * In a STOCK bundle (__LLMAGE__ === false) this
 * module must not carry the relay URL or the provider-call logic. Every
 * exported function early-returns an error; RELAY_PATH and the provider
 * branch stay behind the __LLMAGE__ constant so the minifier dead-code
 * eliminates them (build-stock.sh greps the bundle to enforce it).
 */
const STOCK_DISABLED = !__LLMAGE__

const RELAY_HOSTS = new Set<string>([
  ...LLM_PROVIDERS.filter((p) => p.needsRelay).map((p) => new URL(p.endpoint).hostname),
  'hyper.charm.land',
  'api.deepseek.com',
])

/** Whether provider calls for this endpoint must route via /llm-relay. */
export function isRelayHost(apiEndpoint: string): boolean {  try {
    const u = new URL(apiEndpoint)
    return RELAY_HOSTS.has(u.hostname)
  } catch {
    return false
  }
}

/**
 * Setup-time warning shown UNDER the Provider dropdown when the seat's
 * endpoint is relay-routed. Derived from the SAME rule the calls use
 * (isRelayHost) so the note can never disagree with where the key actually
 * goes: relay hosts cannot be called from the browser directly (no CORS), so
 * the request — Authorization header included — passes through this
 * deployment's relay before reaching the provider. Empty string =
 * browser-direct, nothing to say.
 */
export function relayKeyTransitWarning(apiEndpoint: string): string {
  if (!isRelayHost(apiEndpoint)) return ''
  return (
    'This provider has no browser CORS support, so its requests are relayed through our server — ' +
    'your API key passes through the relay (it is never stored or logged, but it does transit our ' +
    "infrastructure). If you'd rather keep your key strictly between your browser and the provider, " +
    'use a CORS-capable provider (OpenAI, OpenRouter or Groq).'
  )
}

function endpointUrl(apiEndpoint: string, viaRelay: boolean): string {
  if (!viaRelay || STOCK_DISABLED) return apiEndpoint
  return RELAY_PATH + '?url=' + encodeURIComponent(apiEndpoint)
}

/**
 * OpenCode Go/Zen requires x-opencode-session (stable per conversation) or
 * rejects with 400 MissingSessionID. One stable id per key-tag (per seat).
 */
const opencodeSessions = new Map<string, string>()
function opencodeSessionFor(tag: string): string {
  let s = opencodeSessions.get(tag)
  if (!s) {
    s = 'llmage-' + (typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : Math.random().toString(36).slice(2) + Date.now().toString(36))
    opencodeSessions.set(tag, s)
  }
  return s
}

function isOpencodeHost(url: string): boolean {
  try {
    return new URL(url).hostname === 'opencode.ai'
  } catch {
    return false
  }
}

/** POST the server-built body to the provider with the seat's stored key. */
async function callProvider(
  apiEndpoint: string,
  clientKeyTag: string,
  requestBody: string,
  timeoutSeconds: number
): Promise<{ rawResponse: string; httpStatus: number; viaRelay: boolean }> {
  if (STOCK_DISABLED) {
    // Unreachable by design: llm_prompt is dropped before the bridge when the
    // build or the server lacks the capability. Throwing keeps the failure loud.
    throw new Error('LLM bridge disabled: this is a stock build without LLM support')
  }
  const key = getSeatKey(clientKeyTag)
  const viaRelay = isRelayHost(apiEndpoint)
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutSeconds * 1000)
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'application/json',
    Authorization: 'Bearer ' + key,
  }
  if (isOpencodeHost(apiEndpoint)) {
    headers['x-opencode-session'] = opencodeSessionFor(clientKeyTag)
  }
  try {
    const resp = await fetch(endpointUrl(apiEndpoint, viaRelay), {
      method: 'POST',
      headers,
      body: requestBody,
      signal: controller.signal,
    })
    const rawResponse = await resp.text()
    return { rawResponse, httpStatus: resp.status, viaRelay }
  } finally {
    clearTimeout(timer)
  }
}

/**
 * Handle an incoming llm_prompt: perform the provider call, then send the
 * decision/error back through the WebSocket. Fire-and-forget — the
 * server-side future owns the timeout; this never throws to the caller
 * (messageHandlers must not gain an async error path).
 */
export function handleLlmPrompt(
  msg: LlmPromptMessage,
  send: (m: LlmDecisionRequest | LlmErrorRequest) => void
): void {
  callProvider(msg.apiEndpoint, msg.clientKeyTag, msg.requestBody, msg.timeoutSeconds)
    .then((result) => {
      if (result.httpStatus >= 200 && result.httpStatus < 300) {
        const decision: LlmDecisionRequest = {
          type: 'llm_decision',
          requestId: msg.requestId,
          rawResponse: result.rawResponse,
          httpStatus: result.httpStatus,
        }
        send(decision)
      } else {
        const err: LlmErrorRequest = {
          type: 'llm_error',
          requestId: msg.requestId,
          message:
            `HTTP ${result.httpStatus} from provider` +
            (result.viaRelay ? ' (via relay)' : ' (browser-direct)') +
            `: ${truncate(result.rawResponse, 400)}`,
          retryable: result.httpStatus === 429 || result.httpStatus >= 500,
          httpStatus: result.httpStatus,
        }
        send(err)
      }
    })
    .catch((e: unknown) => {
      const err: LlmErrorRequest = {
        type: 'llm_error',
        requestId: msg.requestId,
        message: `Browser LLM call failed: ${e instanceof Error ? e.message : String(e)}`,
        retryable: true, // network/CORS/abort failures are retryable server-side
      }
      send(err)
    })
}

function truncate(s: string, n: number): string {
  return s.length > n ? s.slice(0, n) + '…' : s
}

// ── Browser-direct fetch_models (client-key seat configuration) ────────

export interface ProviderModelInfo {
  id: string
  contextLength: number | undefined
}

/**
 * Fetch the provider's model list DIRECTLY from the browser (no proxy
 * relay, no key in any WS message). Used for client-key seats so the key
 * never enters the proxy even for model discovery.
 *
 * Returns model ids + context windows (context_length / context_window —
 * providers name it differently).
 */
export async function fetchProviderModelsBrowserDirect(
  apiEndpoint: string,
  key: string
): Promise<ProviderModelInfo[]> {
  if (STOCK_DISABLED) {
    // Stock builds carry no provider bridge (nor the relay URL): unreachable
    // by design — the LLM seat option itself is compiled out.
    throw new Error('LLM bridge disabled: this is a stock build without LLM support')
  }
  const base = apiEndpoint
    .replace(/\/v1\/chat\/completions$/, '')
    .replace(/\/v1$/, '')
  const modelsUrl = base + '/v1/models'
  // Relay hosts (no CORS) go through the same-origin relay — the key still
  // never enters the proxy's logs or storage; it transits as a forwarded header.
  const url = isRelayHost(apiEndpoint) ? endpointUrl(modelsUrl, true) : modelsUrl
  const headers: Record<string, string> = {}
  if (key) headers['Authorization'] = 'Bearer ' + key
  if (isOpencodeHost(apiEndpoint)) headers['x-opencode-session'] = opencodeSessionFor('models-fetch')
  const resp = await fetch(url, { headers })
  if (!resp.ok) {
    throw new Error(`HTTP ${resp.status} fetching ${modelsUrl}`)
  }
  const json = (await resp.json()) as {
    data?: Array<{
      id: string
      context_length?: number
      context_window?: number
    }>
  }
  if (!json.data || !Array.isArray(json.data)) {
    throw new Error('Malformed /v1/models response (no data array)')
  }
  return json.data.map((m) => ({
    id: m.id,
    contextLength: m.context_length ?? m.context_window,
  }))
}