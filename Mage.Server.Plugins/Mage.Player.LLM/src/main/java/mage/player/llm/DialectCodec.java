package mage.player.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Internal request/response mapper for one wire dialect.
 *
 * <p>{@link LLMClient}'s public surface ({@code chat}, {@code chatConversation},
 * {@code chatConversationWithStop}, {@code chatNoSideEffects},
 * {@code parseResponse}) is dialect-agnostic: every HTTP-shaped decision is made
 * here, selected once per client from {@link LLMConfig#getApiDialect()}.</p>
 *
 * <p>The chat-completions implementation reproduces the pre-dialect request
 * bodies byte for byte, so an unset/chat-completions config behaves exactly as
 * it did before this layer existed.</p>
 */
public interface DialectCodec {

    ApiDialect dialect();

    /**
     * Endpoint this codec POSTs to. chat-completions returns the configured
     * endpoint unchanged; the other dialects derive their path from it
     * ({@code /v1/chat/completions} → {@code /v1/responses} /
     * {@code /v1/messages}) and fail fast on an unrecognised URL shape.
     */
    String resolveEndpoint(LLMConfig config);

    /**
     * JSON body for a history-free call (startup probe, deck assessment
     * one-shots). {@code noThinking} means "do not ask for reasoning on this
     * call" (summarization one-shot).
     */
    String buildSimpleRequest(LLMConfig config, String systemPrompt, String userPrompt,
                              boolean stream, boolean noThinking);

    /** JSON body for a conversation turn with prior history. */
    String buildConversationRequest(ConversationHistory history);

    /** Parse a full (non-streaming) provider response body. */
    LLMResponse parseResponse(LLMConfig config, String responseBody);

    /**
     * True when this dialect has no server-side stop parameter, so an emitted
     * stop sequence must be applied by trimming the answer client-side.
     */
    boolean requiresClientSideStop();

    /** New accumulator for one SSE stream. */
    StreamParser newStreamParser(LLMConfig config, StreamListener listener);

    /** Neutral inputs of one conversation call, assembled by {@link LLMClient}. */
    final class ConversationHistory {
        public final LLMConfig config;
        public final String systemPrompt;
        public final String summarizedHistory;   // may be null
        public final List<Turn> turns;           // prior history, oldest first
        public final String currentPrompt;
        public final boolean stream;
        public final String effortOverride;      // null = configured effort
        public final String stopSequence;        // null = no stop
        public final boolean noThinking;

        public ConversationHistory(LLMConfig config, String systemPrompt, String summarizedHistory,
                                   List<Turn> turns, String currentPrompt, boolean stream,
                                   String effortOverride, String stopSequence, boolean noThinking) {
            this.config = config;
            this.systemPrompt = systemPrompt;
            this.summarizedHistory = summarizedHistory;
            this.turns = (turns == null) ? Collections.emptyList() : new ArrayList<>(turns);
            this.currentPrompt = currentPrompt;
            this.stream = stream;
            this.effortOverride = effortOverride;
            this.stopSequence = stopSequence;
            this.noThinking = noThinking;
        }
    }

    /** One prior conversation message. */
    final class Turn {
        public final String role;
        public final String content;
        /**
         * Raw provider content-block array for an assistant turn that produced
         * thinking blocks (anthropic-messages). Must be replayed verbatim.
         */
        public final String contentBlocks;
        /** True when this turn produced blocks that the provider requires back. */
        public final boolean contentBlocksRequired;

        public Turn(String role, String content) {
            this(role, content, null, false);
        }

        public Turn(String role, String content, String contentBlocks, boolean contentBlocksRequired) {
            this.role = role;
            this.content = content;
            this.contentBlocks = contentBlocks;
            this.contentBlocksRequired = contentBlocksRequired;
        }
    }

    /** Live-token callback for the debug view / console echo. */
    interface StreamListener {
        void onReasoning(String token);

        void onContent(String token);
    }

    /** SSE accumulator: feed each {@code data:} payload, then ask for the response. */
    interface StreamParser {
        /** @return false when the stream is complete (the [DONE] sentinel). */
        boolean accept(String data);

        LLMResponse finish();
    }
}
