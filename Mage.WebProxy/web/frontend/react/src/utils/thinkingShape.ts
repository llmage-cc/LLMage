/**
 * Thinking-shape classification.

 *
 * Mirrors the ENGINE's request-builder gate — `LLMClient.emitsThinkingObject()`
 * (commit 67af8b38f4): the `thinking:{...}` request object is emitted ONLY for
 * Ollama-classified endpoints ("ollama" in the URL or port 11434 — the same
 * heuristic the context-window detection uses) or when the explicit `budget`
 * dialect is configured. Everywhere else the effort dialect sends bare
 * `reasoning_effort`.
 *
 * The seat form uses this to warn NEXT TO the Thinking Enabled checkbox for
 * endpoints classified STRICT (non-Ollama): strict gateways may reject thinking
 * params with HTTP 400 — measured game-fatal on glm-5.3-flash via opencode
 * zen/go (unknown field "thinking" at mulligan). Deterministic only: no fetch,
 * no token burn. The proxy's ThinkingShapeClassifier (Mage.WebProxy Java) is
 * the same rule — the harness pins both sides to the same truth table.
 */

/** Mirrors LLMConfig.isOllamaEndpoint (Mage.Server.Plugins/Mage.Player.LLM) — kept literal. */
export function isOllamaEndpoint(endpoint: string): boolean {
  return endpoint.includes('ollama') || endpoint.includes('11434')
}

/** The request shape an endpoint receives when thinking is enabled. */
export type ThinkingShape = 'object' | 'effort' | 'off'

/**
 * Classify the shape for a seat. Same truth table as the engine:
 *  - thinking disabled → 'off' (neither the object nor reasoning_effort is sent);
 *  - budget dialect → 'object' (object-based by definition, on any endpoint);
 *  - otherwise → 'object' for an Ollama-classified endpoint, 'effort' (bare
 *    reasoning_effort) for everything else.
 *
 * The web seat form has no dialect field, so seats run the SERVER default
 * dialect; the caller passes `undefined` for it and the engine default
 * ("effort") applies — same default LLMConfig ships with.
 */
export function thinkingShapeFor(endpoint: string, thinkingDialect: string | undefined, thinkingEnabled: boolean): ThinkingShape {
  if (!thinkingEnabled) return 'off'
  if (thinkingDialect === 'budget' || isOllamaEndpoint(endpoint)) return 'object'
  return 'effort'
}

/**
 * The setup-time warning for a seat classified strict (bare reasoning_effort
 * to a non-Ollama endpoint with thinking enabled). Empty string = no warning.
 * The text mirrors the proxy's llm_setup_warning wording.
 */
export function strictThinkingWarning(endpoint: string, thinkingDialect: string | undefined, thinkingEnabled: boolean): string {
  if (thinkingShapeFor(endpoint, thinkingDialect, thinkingEnabled) !== 'effort') return ''
  return (
    'This endpoint is not Ollama-classified, so the seat sends bare reasoning_effort (no thinking object). ' +
    'Strict gateways may reject thinking params with HTTP 400 — which kills the game at mulligan. ' +
    'If the provider rejects them, uncheck Thinking Enabled for this seat before starting the game.'
  )
}

/** The server-default dialect property the engine reads (start-server.sh sets it). */
export const SERVER_DEFAULT_THINKING_DIALECT = 'effort' as const