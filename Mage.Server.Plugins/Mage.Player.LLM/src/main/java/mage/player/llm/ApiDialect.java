package mage.player.llm;

/**
 * Wire dialect of an LLM endpoint's HTTP API, selected per model through the
 * {@code api_dialect} config field ({@code xmage.llm[2].api_dialect}, the seat
 * config dialog, {@code LLM{n}_API_DIALECT} in start-server.sh).
 *
 * <p>INTERNAL: consumed by the {@link DialectCodec} layer. Nothing above
 * {@link LLMClient} ever sees a dialect. An unknown value is a configuration
 * error and fails fast — there is no silent default on a typo.</p>
 */
public enum ApiDialect {

    /** OpenAI-compatible chat completions ({@code {base}/v1/chat/completions}) — the historical/default shape. */
    CHAT_COMPLETIONS("chat-completions"),

    /** OpenAI Responses API ({@code {base}/v1/responses}): reasoning summaries + {@code output[]} items. */
    OPENAI_RESPONSES("openai-responses"),

    /** Anthropic Messages API ({@code {base}/v1/messages}): top-level system, required max_tokens, content blocks. */
    ANTHROPIC_MESSAGES("anthropic-messages");

    private final String wireName;

    ApiDialect(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** All valid wire values, comma-separated, for error messages and docs. */
    public static String validValues() {
        StringBuilder sb = new StringBuilder();
        for (ApiDialect d : values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(d.wireName);
        }
        return sb.toString();
    }

    /**
     * Parse a configured dialect value. Fail Fast: null or anything not in the
     * enum throws with the complete valid-value list — never a silent default.
     */
    public static ApiDialect fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("apiDialect is null — set one of: " + validValues());
        }
        for (ApiDialect d : values()) {
            if (d.wireName.equals(value)) {
                return d;
            }
        }
        throw new IllegalArgumentException("Unknown apiDialect '" + value + "'. Valid values: " + validValues());
    }
}
