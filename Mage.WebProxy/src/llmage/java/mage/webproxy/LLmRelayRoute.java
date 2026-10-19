package mage.webproxy;

import org.eclipse.jetty.server.handler.ContextHandler;

/**
 * LLMage-only registration seam for the /llm-relay route.
 * Lives in src/llmage/java so the STOCK profile (-Pstock) never
 * compiles it — WebProxyServer references only this class via
 * LLMageRoutes.relayHandler(), and in a stock build that seam answers null
 * (see the main-source LLMageRoutes stub) without ever naming this class.
 */
public final class LLmRelayRoute {

    private LLmRelayRoute() {
    }

    public static ContextHandler register() {
        return LLmRelayHandler.register();
    }
}
