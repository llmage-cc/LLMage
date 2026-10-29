package mage.player.llm;

import java.util.List;

/**
 * Factory + shared request-shaping helpers for the dialect codecs.
 *
 * <p>INTERNAL to the plugin: {@link LLMClient} is the only production caller.</p>
 */
public final class DialectCodecs {

    /**
     * Floor for a reasoning budget. Anthropic's native API requires
     * budget_tokens &gt;= 1024; the same floor keeps a descended cap sane
     * wherever a budget is emitted.
     */
    public static final int MIN_REASONING_BUDGET_TOKENS = 1024;

    /** Answer-reserve multiplier used when max_tokens is unset but a reasoning cap rides on the request. */
    public static final int ANSWER_RESERVE_MULTIPLIER = 2;

    private DialectCodecs() {
    }

    /** Select the codec for this config. Unknown values fail fast. */
    public static DialectCodec forConfig(LLMConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("LLMConfig is null — cannot select an api dialect");
        }
        switch (ApiDialect.fromWire(config.getApiDialect())) {
            case CHAT_COMPLETIONS:
                return new ChatCompletionsCodec();
            case OPENAI_RESPONSES:
                return new OpenAIResponsesCodec();
            case ANTHROPIC_MESSAGES:
                return new AnthropicMessagesCodec();
            default:
                throw new IllegalStateException("Unhandled apiDialect: " + config.getApiDialect());
        }
    }

    /**
     * Whether a chat-completions request may emit the non-standard
     * {@code thinking} object for this endpoint (Ollama-family, or an explicit
     * budget dialect). Null endpoint is a misconfiguration — Fail Fast.
     */
    static boolean emitsThinkingObjectAt(LLMConfig config, String endpoint) {
        if (endpoint == null) {
            throw new IllegalStateException("LLM apiEndpoint is null — cannot classify the"
                    + " thinking-object dialect (set xmage.llm.endpoint / the GUI LLM config)");
        }
        return "budget".equals(config.getThinkingDialect())
                || LLMConfig.isOllamaEndpoint(endpoint);
    }

    /** First (highest) rung of the budget ladder. */
    static int currentBudgetTokens(LLMConfig config) {
        List<Integer> ladder = config.getThinkingBudgetTokens();
        if (ladder == null || ladder.isEmpty()) {
            throw new IllegalStateException("thinkingDialect=budget but thinkingBudgetTokens ladder is empty");
        }
        return ladder.get(0);
    }

    /**
     * Effort-dialect cap scaled by how far the per-attempt rung sits below the
     * configured effort. Same semantics the pre-dialect LLMClient implemented:
     * no ordering → full cap; lowest rung → the 1024 floor.
     */
    static int effortDescentScaledBudget(LLMConfig config, String effort) {
        int cap = config.getThinkingMaxTokens();
        List<String> ladder = config.getReasoningEffortLevels();
        if (ladder == null) return cap;
        int rung = ladder.indexOf(effort);                          // 0 = highest effort
        int configuredRung = ladder.indexOf(config.getReasoningEffort());
        if (rung < 0 || configuredRung < 0 || rung <= configuredRung) return cap;
        int span = ladder.size() - 1 - configuredRung;               // rungs below configured
        if (span <= 0) return cap;                                   // configured effort is the lowest rung
        int descended = rung - configuredRung;
        int scaled = (int) ((long) cap * (span - descended) / span);
        return Math.max(scaled, MIN_REASONING_BUDGET_TOKENS);
    }

    /** First line of a text block, used for the client-side stop emulation. */
    public static String firstLine(String text) {
        if (text == null) return null;
        int nl = text.indexOf('\n');
        return (nl < 0) ? text : text.substring(0, nl);
    }

    /**
     * Derive a dialect endpoint from the configured one. Only explicit,
     * recognisable shapes are accepted — anything else throws rather than
     * guessing at the provider's URL layout.
     */
    static String deriveEndpoint(LLMConfig config, String apiSuffix) {
        String endpoint = config.getApiEndpoint();
        if (endpoint == null) {
            throw new IllegalStateException("LLM apiEndpoint is null — set xmage.llm.endpoint / the GUI LLM config");
        }
        if (endpoint.endsWith(apiSuffix)) {
            return endpoint;
        }
        final String chat = "/v1/chat/completions";
        if (endpoint.endsWith(chat)) {
            return endpoint.substring(0, endpoint.length() - chat.length()) + apiSuffix;
        }
        final String chatNoV1 = "/chat/completions";
        if (endpoint.endsWith(chatNoV1)) {
            return endpoint.substring(0, endpoint.length() - chatNoV1.length()) + apiSuffix;
        }
        if (endpoint.endsWith("/v1")) {
            return endpoint.substring(0, endpoint.length() - "/v1".length()) + apiSuffix;
        }
        throw new IllegalStateException("Cannot derive the '" + apiSuffix + "' endpoint from apiEndpoint '"
                + endpoint + "' — set the endpoint to the full dialect URL (…" + apiSuffix + ")");
    }
}
