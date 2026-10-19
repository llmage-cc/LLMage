package mage.webproxy;

import org.eclipse.jetty.server.handler.ContextHandler;

/**
 * Compile-time seam between WebProxyServer and the LLMage-only routes
 * The LLMage build adds src/llmage/java to the
 * source path with an implementation that registers /llm-relay; the stock
 * profile (-Pstock) omits that source root and compiles THIS stub, which
 * registers nothing. WebProxyServer never references LLmRelayHandler/Route
 * directly, so both flavors compile from one source tree.
 */
final class LLmageRoutes {

    private LLmageRoutes() {
    }

    /** The /llm-relay ContextHandler, or null in the stock artifact. */
    static ContextHandler relayHandler() {
        // Stock stub: no relay.
        return null;
    }
}
