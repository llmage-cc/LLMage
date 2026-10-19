package mage.interfaces;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Classloader-bridged registry for client-key LLM exchanges

 *
 * The LLM plugin (mage-player-llm, own PluginClassLoader) and the XMage
 * server both see Mage.Common classes through parent delegation — one
 * shared class object — so a static registry here is the one place both
 * can call directly without reflection.
 *
 * Flow:
 *  1. Plugin (game thread) calls {@link #request} with the prompt payload.
 *     It blocks on a CompletableFuture, bounded by timeoutSeconds.
 *  2. The server transport (Mage.Server LLMClientTransport, registered at
 *     boot) routes the payload to the seat's browser via Session.fireCallback
 *     (ClientCallbackMethod.LLM_PROMPT) → JBoss remoting → proxy → WS.
 *  3. Browser POSTs to the provider with the user's key, then sends the
 *     decision back through the proxy → SessionImpl.llmDecision() →
 *     {@link #complete} / {@link #fail}.
 *
 * Failure modes (all complete the future exceptionally, callers treat as
 * call failure and apply existing retry/concede logic):
 *  - browser disconnect / tab close → timeout
 *  - user-key fetch failure → {@link #fail}
 *  - server transport missing → IllegalStateException at request time
 */
public final class LLMBridgeRegistry {

    /** Implemented server-side (Mage.Server); routes a payload to its browser. */
    public interface Transport {
        /**
         * Send the prompt to the browser owning the given seat.
         * @return true if a browser session was found and the payload queued.
         * @throws IllegalStateException when no transport is registered.
         */
        boolean deliver(LLMPromptPayload payload);

        /**
         * Resolve and pin the current route for the given game and seat,
         * returning an opaque token usable later via
         * {@link LLMPromptPayload#routeToken} after the live gameId → tableId
         * link is gone. Keyed by (gameId, seatId) because ONE game has TWO
         * seats with their own post-game reflection; a gameId-only pin let the
         * second seat overwrite the first

         * Default: no pinning support (tests, stub transports) — {@link #pinRoute}
         * then yields null and every call keeps the live lookup.
         */
        default String pinRoute(UUID gameId, UUID seatId) {
            return null;
        }

        /**
         * Release the pin held for the given (game, seat) once that seat's
         * post-game phase is over. Keyed like {@link #pinRoute}.
         * Default: no pinning support (tests, stub transports) — nothing to
         * release.
         */
        default void releaseRoute(UUID gameId, UUID seatId) {
        }
    }

    private static volatile Transport transport;
    private static final ConcurrentHashMap<UUID, CompletableFuture<LLMPromptPayload.Decision>> PENDING =
            new ConcurrentHashMap<>();

    private LLMBridgeRegistry() { }

    /** Called once at server boot (Mage.Server Main / MainManagerFactory). */
    public static void setTransport(Transport t) {
        transport = t;
    }

    /** Test hook — clear the transport between test cases. */
    public static void clearTransport() {
        transport = null;
    }

    /** Number of exchanges awaiting a browser decision. */
    public static int pendingCount() {
        return PENDING.size();
    }

    /**
     * Issue (or re-resolve) a route token pinning the given game+seat's current
     * bridge route server-side. Call this while the game is still registered;
     * the token lets a LATER request (async post-game reflection, fired after
     * the deciding game's table teardown removed the gameId → tableId link)
     * still reach the same browser via {@link LLMPromptPayload#routeToken}.
     *
     * <p>Keyed by (gameId, seatId): both seats of a game pin the same gameId,
     * and a gameId-only pin let one seat's reflection steal the other's route

     *
     * @return the token to put on later payloads, or null when no route could
     *         be resolved right now (torn down already / no browser session) —
     *         callers must treat null as "no pin possible" and keep the live
     *         lookup behavior.
     * @throws IllegalStateException when no transport is registered.
     */
    public static String pinRoute(UUID gameId, UUID seatId) {
        Transport t = transport;
        if (t == null) {
            throw new IllegalStateException("LLM bridge transport not registered — "
                    + "is this a client-key seat on a server without web proxy support?");
        }
        return t.pinRoute(gameId, seatId);
    }

    /**
     * Release the (gameId, seatId) pin once that seat's post-game phase has
     * finished. Called by the post-game reflection task (LLMClient) in a finally
     * block, so the pin store can never grow past the concurrently outstanding
     * reflections. Never throws when no transport is registered — a missing
     * transport has no pins to release.
     */
    public static void releaseRoute(UUID gameId, UUID seatId) {
        Transport t = transport;
        if (t != null) {
            t.releaseRoute(gameId, seatId);
        }
    }

    /** Called by the server when a browser decision arrives (proxy → SessionImpl). */
    public static void complete(LLMPromptPayload.Decision decision) {
        CompletableFuture<LLMPromptPayload.Decision> f = PENDING.get(decision.requestId);
        if (f != null) {
            f.complete(decision);
        }
    }

    /** Called by the server when a browser reports failure. */
    public static void fail(LLMPromptPayload.Failure failure) {
        CompletableFuture<LLMPromptPayload.Decision> f = PENDING.get(failure.requestId);
        if (f != null) {
            f.completeExceptionally(new BrowserCallException(failure));
        }
    }

    /** Browser-reported failure carrying the provider's status/message. */
    public static final class BrowserCallException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final int httpStatus;
        public final boolean retryable;
        public BrowserCallException(LLMPromptPayload.Failure failure) {
            super(failure.message != null ? failure.message : "LLM call failed in browser");
            this.httpStatus = failure.httpStatus;
            this.retryable = failure.retryable;
        }
    }

    /**
     * Send a prompt to the seat's browser and block until the decision
     * arrives, the timeout elapses, or the exchange fails.
     *
     * @return the raw provider response (decision payload)
     * @throws IllegalStateException transport not registered or no browser
     *                               session for the seat
     * @throws TimeoutException      browser did not answer in time
     * @throws RuntimeException      browser-reported failure (message carries
     *                               the provider error text)
     */
    public static LLMPromptPayload.Decision request(LLMPromptPayload payload) throws Exception {
        CompletableFuture<LLMPromptPayload.Decision> future = new CompletableFuture<>();
        UUID key = payload.requestId;
        CompletableFuture<LLMPromptPayload.Decision> prev = PENDING.putIfAbsent(key, future);
        if (prev != null) {
            throw new IllegalStateException("Duplicate LLM bridge requestId: " + key);
        }
        try {
            Transport t = transport;
            if (t == null) {
                throw new IllegalStateException("LLM bridge transport not registered — "
                        + "is this a client-key seat on a server without web proxy support?");
            }
            boolean delivered = t.deliver(payload);
            if (!delivered) {
                throw new IllegalStateException("No browser session for LLM seat "
                        + payload.playerName + " (seatId=" + payload.seatId + ") — "
                        + "the browser that configured this seat must stay connected, or "
                        + "the seat must be configured with a server-side key.");
            }
            try {
                return future.get(payload.timeoutSeconds, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                // Browser-reported failure — unwrap so the caller sees the
                // provider's own message (retry classification uses it).
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new RuntimeException(cause.getMessage(), cause);
            }
        } finally {
            PENDING.remove(key);
        }
    }
}