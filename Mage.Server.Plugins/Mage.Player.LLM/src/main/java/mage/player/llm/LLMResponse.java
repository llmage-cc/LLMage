package mage.player.llm;

/**
 * Holds both the reasoning/thinking content and the final answer
 * from an LLM chat completion response.
 */
public class LLMResponse {
    public final String thinking;      // chain-of-thought reasoning (may be null)
    public final String answer;        // the final content/answer (may be null)
    // The model's one-sentence stated reason (ask-reason feature): extracted
    // best-effort from the line after the answer when the seat's askReason flag
    // is on. Null when the flag is off or the model didn't provide one. This is
    // a post-hoc rationalization in the answer content, NOT the hidden
    // chain-of-thought — for hidden-reasoning providers it's the only intent
    // signal available.
    public final String statedReason;  // may be null

    /**
     * The provider's {@code choices[0].finish_reason} for this response
     * ("stop", "length", "content_filter", …), or null when the provider
     * omitted it. Captured so an empty answer can be diagnosed: a
     * {@code finish_reason=length} names truncation, a {@code stop} with
     * empty {@code content} names a stopped-but-empty response (the
     * glm-5.3-flash@zen reasoning-bleed shape). Previously never read, so
     * truncation was invisible.
     */
    public final String finishReason;  // may be null

    /**
     * Token usage reported by the API (null when the provider omits the
     * `usage` object — e.g. some streaming modes). Names follow the
     * OpenAI chat-completions usage schema.
     */
    public static class Usage {
        public final Integer promptTokens;
        public final Integer completionTokens;
        public final Integer totalTokens;
        // Reasoning-token count where the provider exposes it
        // (OpenAI: usage.completion_tokens_details.reasoning_tokens;
        //  DeepSeek: usage.completion_tokens_details.reasoning_tokens likewise).
        public final Integer reasoningTokens;

        public Usage(Integer promptTokens, Integer completionTokens,
                     Integer totalTokens, Integer reasoningTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
            this.reasoningTokens = reasoningTokens;
        }

        @Override
        public String toString() {
            return "usage[prompt=" + promptTokens + ", completion=" + completionTokens
                    + (reasoningTokens != null ? " (reasoning=" + reasoningTokens + ")" : "")
                    + ", total=" + totalTokens + "]";
        }
    }

    public final Usage usage;  // null when the provider didn't report usage

    public LLMResponse(String thinking, String answer) {
        this(thinking, answer, null, null);
    }

    public LLMResponse(String thinking, String answer, Usage usage) {
        this(thinking, answer, usage, null);
    }

    public LLMResponse(String thinking, String answer, Usage usage, String statedReason) {
        this(thinking, answer, usage, statedReason, null);
    }

    public LLMResponse(String thinking, String answer, Usage usage, String statedReason,
                       String finishReason) {
        this.thinking = thinking;
        this.answer = answer;
        this.usage = usage;
        this.statedReason = statedReason;
        this.finishReason = finishReason;
    }
}
