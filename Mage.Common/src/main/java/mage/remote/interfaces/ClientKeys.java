package mage.remote.interfaces;

import mage.interfaces.LLMPromptPayload;

/**
 * Client-key LLM bridge surface — browser
 * decision/error delivery from the web proxy to the XMage server.
 * Part of {@link mage.remote.Session} (see its extends list).
 */
public interface ClientKeys {

    /**
     * Relay a provider response for a pending client-key LLM exchange.
     * The payload classes are shared with the server through Mage.Common.
     *
     * @return true when the call reached a connected server
     */
    boolean llmDecision(LLMPromptPayload.Decision decision);

    /** Relay a failed provider call for a pending exchange. */
    boolean llmError(LLMPromptPayload.Failure failure);
}