package mage.interfaces;

import java.io.Serializable;
import java.util.UUID;

/**
 * Server → browser LLM prompt request for client-key seats (key-client-only
 * architecture, ).
 *
 * The server builds the exact OpenAI-compatible request body it would have
 * POSTed to the provider (buildRequestBody* in LLMClient — byte-identical,
 * so provider prompt caching and thinking-dialect encoding are preserved),
 * and hands it to the browser through the proxy. The browser attaches the
 * user's API key (never sent to the server) and POSTs it to the provider —
 * directly, or through the same-origin CORS relay for providers without
 * CORS support. The browser returns the raw response via
 * {@link mage.interfaces.LLMBridgeDecision}.
 *
 * SECURITY: this payload must NEVER contain an API key. It travels over
 * JBoss remoting (server→proxy) and the public WebSocket (proxy→browser).
 * It does carry the endpoint URL — the browser needs to know which provider
 * to call, and the endpoint is operator-configurable public data, not a
 * secret. The server-side SSRF allowlist (isAllowedModelHost) is enforced
 * at table-creation time (parseLLMConfig), before any seat can run.
 */
public class LLMPromptPayload implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Unique id for this exchange — echoed back in the decision. */
    public final UUID requestId;
    /** The seat (player) making the call — for per-seat key lookup in the browser. */
    public final UUID seatId;
    /** Game id — lets the browser scope the exchange to a live game. */
    public final UUID gameId;
    /** Player name for display/debug purposes (e.g. "glm-5.3-flash-Kestrel"). */
    public final String playerName;
    /**
     * Exact OpenAI-compatible request body (JSON string) the server would
     * have sent. Includes model, messages, reasoning effort/thinking params,
     * stream flag. The browser POSTs it verbatim.
     */
    public final String requestBody;
    /** Provider chat-completions URL the browser must POST to. NOT a secret. */
    public final String apiEndpoint;
    /** true when stream:true is set in requestBody — browser parses SSE. */
    public final boolean stream;
    /** Call timeout from LLMConfig — bounds the browser fetch. */
    public final int timeoutSeconds;
    /** Browser key-tag naming which locally-stored key to use (not a secret). */
    public final String clientKeyTag;
    /**
     * Opaque routing token pinning this call to a server-side route captured
     * while the game was still registered (async post-game reflection for the
     * match-deciding game — ).
     * Null on every in-game call; the transport then resolves the route live
     * (gameId → tableId → owner → session). ADDITIVE wire-compat: this class
     * is Java-serialized through JBoss remoting (ClientCallback LLM_PROMPT)
     * with a pinned serialVersionUID of 1L on both peers; per Java
     * serialization field-name matching, a field the peer's class lacks is
     * ignored (their reads see null), so fork↔fork and fork↔stock-proxy pairs
     * both stay compatible. NOT a secret.
     */
    public final String routeToken;

    public LLMPromptPayload(UUID requestId, UUID seatId, UUID gameId, String playerName,
                            String requestBody, String apiEndpoint, boolean stream, int timeoutSeconds,
                            String clientKeyTag) {
        this(requestId, seatId, gameId, playerName, requestBody, apiEndpoint, stream,
                timeoutSeconds, clientKeyTag, null);
    }

    public LLMPromptPayload(UUID requestId, UUID seatId, UUID gameId, String playerName,
                            String requestBody, String apiEndpoint, boolean stream, int timeoutSeconds,
                            String clientKeyTag, String routeToken) {
        this.requestId = requestId;
        this.seatId = seatId;
        this.gameId = gameId;
        this.playerName = playerName;
        this.requestBody = requestBody;
        this.apiEndpoint = apiEndpoint;
        this.stream = stream;
        this.timeoutSeconds = timeoutSeconds;
        this.clientKeyTag = clientKeyTag;
        this.routeToken = routeToken;
    }

    /**
     * Server-side completion result for the bridge: the raw response body the
     * provider returned (full JSON for non-streaming, concatenated SSE data
     * content for streaming), plus optional display metadata.
     */
    public static class Decision implements Serializable {
        private static final long serialVersionUID = 1L;
        public final UUID requestId;
        /** Raw response JSON (or assembled answer for streamed calls). */
        public final String rawResponse;
        /** Thinking/reasoning text for debug display (may be null). */
        public final String thinking;
        /** HTTP status of the provider call (0 when unknown/not applicable). */
        public final int httpStatus;

        public Decision(UUID requestId, String rawResponse, String thinking, int httpStatus) {
            this.requestId = requestId;
            this.rawResponse = rawResponse;
            this.thinking = thinking;
            this.httpStatus = httpStatus;
        }
    }

    /**
     * Browser-reported failure for the bridge: message is user-readable,
     * retryable mirrors the server's isRetryable() classification when known.
     */
    public static class Failure implements Serializable {
        private static final long serialVersionUID = 1L;
        public final UUID requestId;
        public final String message;
        public final boolean retryable;
        public final int httpStatus;

        public Failure(UUID requestId, String message, boolean retryable, int httpStatus) {
            this.requestId = requestId;
            this.message = message;
            this.retryable = retryable;
            this.httpStatus = httpStatus;
        }
    }
}