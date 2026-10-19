package mage.webproxy;

import org.eclipse.jetty.server.handler.ContextHandler;

/**
 * LLMage implementation of the route seam. Lives
 * in src/llmage/java — compiled ONLY in the LLMage build; the stock profile
 * compiles the main-source stub that returns null instead.
 */
final class LLmageRoutes {

    private LLmageRoutes() {
    }

    /** The /llm-relay ContextHandler (never null in the LLMage build). */
    static ContextHandler relayHandler() {
        return LLmRelayRoute.register();
    }
}
