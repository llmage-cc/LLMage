package mage.webproxy;

/**
 * Deterministic thinking-shape classification for LLM endpoints.
 *
 * Mirrors the engine's request-builder gate —
 * {@code mage.player.llm.LLMClient.emitsThinkingObject()} (commit 67af8b38f4):
 * the {@code thinking:{...}} request object is emitted ONLY for
 * Ollama-classified endpoints (same heuristic the context-window detection
 * uses: "ollama" in the URL or the default port 11434) or when the explicit
 * {@code budget} dialect is configured. Everywhere else the effort dialect
 * sends bare {@code reasoning_effort}.
 *
 * The proxy uses this to surface a SETUP-TIME warning in the web UI before a
 * game dies at mulligan: the seat form warns next to "Thinking Enabled" for
 * endpoints classified STRICT (non-Ollama), where thinking params reach a
 * gateway that may reject them (measured: glm-5.3-flash on opencode zen/go
 * rejects the thinking object with HTTP 400 — game-fatal at mulligan; all six
 * zen/go models accept bare reasoning_effort). This is deterministic
 * classification only — it makes no network call and burns no tokens.
 */
public final class ThinkingShapeClassifier {

    private ThinkingShapeClassifier() {
        // static utility — never instantiated
    }

    /** The request shape an endpoint receives when thinking is enabled. */
    public enum Shape {
        /** thinking:{type:"enabled"...} object emitted (Ollama-classified or budget dialect). */
        THINKING_OBJECT,
        /** bare reasoning_effort only (non-Ollama endpoint, effort dialect). */
        BARE_EFFORT,
        /** thinking disabled — no thinking params at all. */
        OFF
    }

    /**
     * Ollama detection heuristic — the SAME one the engine uses
     * ({@code LLMConfig.isOllamaEndpoint}): "ollama" anywhere in the URL or
     * the default port 11434. Kept literal: an Ollama-classified endpoint GETS
     * the thinking object, so the proxy must classify identically to the
     * engine or the warning lies.
     */
    public static boolean isOllamaEndpoint(String endpoint) {
        return endpoint != null && (endpoint.contains("ollama") || endpoint.contains("11434"));
    }

    /**
     * Classify the request shape for a seat. Same truth table as the engine's
     * {@code emitsThinkingObject()}:
     * <ul>
     *   <li>thinking disabled → OFF (the engine emits neither the object nor
     *       reasoning_effort — see {@code buildRequestBodyConv});</li>
     *   <li>budget dialect → THINKING_OBJECT (that dialect is object-based by
     *       definition, on any endpoint);</li>
     *   <li>otherwise (effort dialect, the default) → THINKING_OBJECT for an
     *       Ollama-classified endpoint, BARE_EFFORT for everything else.</li>
     * </ul>
     *
     * @param endpoint        the seat's chat-completions URL ("" = server default)
     * @param thinkingDialect the seat's configured dialect ("effort" | "budget";
     *                        null/empty = the engine default, "effort")
     * @param thinkingEnabled whether thinking is enabled for the seat
     */
    public static Shape classify(String endpoint, String thinkingDialect, boolean thinkingEnabled) {
        if (!thinkingEnabled) {
            return Shape.OFF;
        }
        boolean budget = "budget".equals(thinkingDialect);
        if (budget || isOllamaEndpoint(endpoint)) {
            return Shape.THINKING_OBJECT;
        }
        return Shape.BARE_EFFORT;
    }

    /**
     * True when the seat's thinking params reach a STRICT (non-Ollama)
     * endpoint with thinking enabled — the setup-time warning case. A
     * THINKING_OBJECT on an Ollama-classified endpoint is the expected shape;
     * a THINKING_OBJECT from the budget dialect on a strict endpoint is also
     * strict-classified (the dialect is explicitly configured, so the user
     * chose it — the warning still tells them what will be sent).
     */
    public static boolean isStrictShape(String endpoint, String thinkingDialect, boolean thinkingEnabled) {
        return classify(endpoint, thinkingDialect, thinkingEnabled) == Shape.BARE_EFFORT;
    }

    /**
     * The setup-time warning text for a seat classified strict. Null when the
     * shape is not strict (nothing to warn about — never a silent empty
     * string that a caller could misread as "no seat").
     */
    public static String strictWarning(String seatName, String model) {
        String modelPart = (model == null || model.isEmpty()) ? "" : " (" + model + ")";
        return seatName + modelPart + ": this endpoint is not Ollama-classified, so the seat sends"
                + " bare reasoning_effort (no thinking object). Strict gateways may reject thinking"
                + " params with HTTP 400 — which kills the game at mulligan. If the provider rejects"
                + " them, uncheck Thinking Enabled for this seat before starting the game.";
    }
}