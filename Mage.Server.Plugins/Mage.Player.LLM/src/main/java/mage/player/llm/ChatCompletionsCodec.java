package mage.player.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.apache.log4j.Logger;

/**
 * OpenAI-compatible chat-completions dialect ({@code {base}/v1/chat/completions}).
 *
 * <p>This is the historical shape and the default. Its request bodies and
 * response parsing are byte-for-byte the pre-dialect implementation: the body
 * builders below are a verbatim move of the code that used to live in
 * {@link LLMClient}, so an unset/chat-completions config cannot drift.</p>
 */
public final class ChatCompletionsCodec implements DialectCodec {

    private static final Logger logger = Logger.getLogger(ChatCompletionsCodec.class);

    @Override
    public ApiDialect dialect() {
        return ApiDialect.CHAT_COMPLETIONS;
    }

    @Override
    public String resolveEndpoint(LLMConfig config) {
        if (config.getApiEndpoint() == null) {
            throw new IllegalStateException("LLM apiEndpoint is null — set xmage.llm.endpoint / the GUI LLM config");
        }
        return config.getApiEndpoint();
    }

    @Override
    public boolean requiresClientSideStop() {
        return false;
    }

    // ------------------------------------------------------------------
    //  Requests
    // ------------------------------------------------------------------

    @Override
    public String buildConversationRequest(ConversationHistory h) {
        LLMConfig config = h.config;
        JsonObject root = new JsonObject();
        root.addProperty("model", config.getModel());
        if (config.isTemperatureEnabled()) {
            root.addProperty("temperature", config.getTemperature());
        }

        // Endpoint the body will be POSTed to — resolved ONCE so the
        // thinking-object gate and the max_tokens reserve below agree on where
        // this request goes.
        String endpoint = config.getApiEndpoint();

        Integer reasoningBudget = null;   // the cap emitted as thinking.budget_tokens, or null
        JsonObject thinking = null;
        if (config.isThinkingEnabled() && !h.noThinking) {
            thinking = new JsonObject();
            thinking.addProperty("type", "enabled");
            if ("budget".equals(config.getThinkingDialect())) {
                // Budget dialect: the per-attempt override IS the ladder rung, so
                // descent lowers the emitted cap directly.
                reasoningBudget = (h.effortOverride != null)
                        ? Integer.parseInt(h.effortOverride)
                        : DialectCodecs.currentBudgetTokens(config);
            } else {
                String effort = (h.effortOverride != null) ? h.effortOverride : config.getReasoningEffort();
                root.addProperty("reasoning_effort", effort);
                if (config.getThinkingMaxTokens() > 0 && DialectCodecs.emitsThinkingObjectAt(config, endpoint)) {
                    reasoningBudget = DialectCodecs.effortDescentScaledBudget(config, effort);
                }
            }
            if (reasoningBudget != null) {
                thinking.addProperty("budget_tokens", reasoningBudget);
            }
            if (DialectCodecs.emitsThinkingObjectAt(config, endpoint)) {
                root.add("thinking", thinking);
            }
        }

        // Total output cap. When max_tokens is configured it is authoritative;
        // otherwise, if this request also carries a reasoning cap, reserve output
        // room for the ANSWER with max_tokens = reasoning cap * factor.
        if (config.getMaxTokens() > 0) {
            root.addProperty("max_tokens", config.getMaxTokens());
        } else if (reasoningBudget != null) {
            root.addProperty("max_tokens", reasoningBudget * DialectCodecs.ANSWER_RESERVE_MULTIPLIER);
        }

        root.addProperty("stream", h.stream);

        JsonArray messages = new JsonArray();

        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", h.systemPrompt);
        messages.add(sysMsg);

        // Inject summarized history as system context so the LLM sees compressed game history.
        if (h.summarizedHistory != null && !h.summarizedHistory.isEmpty()) {
            JsonObject sumMsg = new JsonObject();
            sumMsg.addProperty("role", "system");
            sumMsg.addProperty("content", "Prior game summary:\n" + h.summarizedHistory);
            messages.add(sumMsg);
        }

        for (Turn cm : h.turns) {
            JsonObject msg = new JsonObject();
            msg.addProperty("role", cm.role);
            msg.addProperty("content", cm.content);
            messages.add(msg);
        }

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", h.currentPrompt);
        messages.add(userMsg);

        root.add("messages", messages);
        if (h.stopSequence != null) {
            JsonArray stop = new JsonArray();
            stop.add(h.stopSequence);
            root.add("stop", stop);
        }
        if (h.stream) {
            // Ask the provider to append a final usage-only chunk to the stream so
            // we get real token counts for streaming calls too (OpenAI-compatible:
            // stream_options.include_usage).
            JsonObject streamOptions = new JsonObject();
            streamOptions.addProperty("include_usage", true);
            root.add("stream_options", streamOptions);
        }
        return new com.google.gson.Gson().toJson(root);
    }

    @Override
    public String buildSimpleRequest(LLMConfig config, String systemPrompt, String userPrompt,
                                     boolean stream, boolean noThinking) {
        if (noThinking) {
            return buildSimpleRequestNoThinking(config, systemPrompt, userPrompt, stream);
        }
        JsonObject root = new JsonObject();
        root.addProperty("model", config.getModel());
        if (config.isTemperatureEnabled()) {
            root.addProperty("temperature", config.getTemperature());
        }
        if (config.getMaxTokens() > 0) {
            root.addProperty("max_tokens", config.getMaxTokens());
        }
        root.addProperty("stream", stream);

        if (config.isThinkingEnabled()) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "enabled");
            if ("budget".equals(config.getThinkingDialect())) {
                thinking.addProperty("budget_tokens", DialectCodecs.currentBudgetTokens(config));
            } else {
                root.addProperty("reasoning_effort", config.getReasoningEffort());
                if (config.getThinkingMaxTokens() > 0 && emitsThinkingObject(config)) {
                    thinking.addProperty("budget_tokens", config.getThinkingMaxTokens());
                }
            }
            if (emitsThinkingObject(config)) {
                root.add("thinking", thinking);
            }
        }

        JsonArray messages = new JsonArray();

        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        messages.add(sysMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messages.add(userMsg);

        root.add("messages", messages);
        return new com.google.gson.Gson().toJson(root);
    }

    /** Verbatim pre-dialect buildRequestBodyNoThinking: thinking explicitly disabled where the endpoint accepts the object. */
    private String buildSimpleRequestNoThinking(LLMConfig config, String systemPrompt, String userPrompt, boolean stream) {
        JsonObject root = new JsonObject();
        root.addProperty("model", config.getModel());
        if (config.isTemperatureEnabled()) {
            root.addProperty("temperature", config.getTemperature());
        }
        if (config.getMaxTokens() > 0) {
            root.addProperty("max_tokens", config.getMaxTokens());
        }
        root.addProperty("stream", stream);

        if (emitsThinkingObject(config)) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "disabled");
            root.add("thinking", thinking);
        }

        JsonArray messages = new JsonArray();

        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        messages.add(sysMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messages.add(userMsg);

        root.add("messages", messages);
        return new com.google.gson.Gson().toJson(root);
    }

    private boolean emitsThinkingObject(LLMConfig config) {
        return DialectCodecs.emitsThinkingObjectAt(config, config.getApiEndpoint());
    }

    // ------------------------------------------------------------------
    //  Response
    // ------------------------------------------------------------------

    @Override
    public LLMResponse parseResponse(LLMConfig config, String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            LLMResponse.Usage usage = LLMClient.parseUsage(root);
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices != null && choices.size() > 0) {
                JsonObject firstChoice = choices.get(0).getAsJsonObject();
                JsonObject message = firstChoice.getAsJsonObject("message");
                if (message != null) {
                    String content = getStringOrNull(message, "content");
                    // reasoning_content is the OpenAI/DeepSeek-compatible field;
                    // `reasoning` and the choices[0].reasoning_content placement are
                    // provider variants (ollama-cloud).
                    String reasoning = getStringOrNull(message, "reasoning_content");
                    if (reasoning == null || reasoning.isEmpty()) {
                        reasoning = getStringOrNull(message, "reasoning");
                    }
                    if (reasoning == null || reasoning.isEmpty()) {
                        reasoning = getStringOrNull(firstChoice, "reasoning_content");
                    }
                    String finishReason = getStringOrNull(firstChoice, "finish_reason");
                    LLMResponse resp = new LLMResponse(reasoning, content, usage, null, finishReason);
                    if (usage != null && config.isVerboseLogging()) {
                        logger.info("LLM " + usage);
                    }
                    return resp;
                }
            }
            throw new RuntimeException("Unexpected LLM response format: " + responseBody);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to parse LLM response: " + t.getMessage(), t);
        }
    }

    private static String getStringOrNull(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    // ------------------------------------------------------------------
    //  Streaming
    // ------------------------------------------------------------------

    @Override
    public StreamParser newStreamParser(LLMConfig config, StreamListener listener) {
        return new Parser(listener);
    }

    /** Verbatim pre-dialect SSE chunk handling, moved out of LLMClient's loop. */
    private static final class Parser implements StreamParser {
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final StreamListener listener;
        private LLMResponse.Usage usage;
        private String finishReason;

        Parser(StreamListener listener) {
            this.listener = listener;
        }

        @Override
        public boolean accept(String data) {
            if ("[DONE]".equals(data)) {
                return false;
            }
            try {
                JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                // Final usage-only chunk (stream_options.include_usage): has no
                // choices. Capture it — real token counts per decision.
                if (usage == null && (chunk.has("usage") && !chunk.get("usage").isJsonNull())) {
                    usage = LLMClient.parseUsage(chunk);
                    return true;
                }
                JsonArray choices = chunk.getAsJsonArray("choices");
                if (choices == null || choices.isEmpty()) return true;

                JsonObject choice = choices.get(0).getAsJsonObject();
                String fr = getStringOrNull(choice, "finish_reason");
                if (fr != null) finishReason = fr;
                JsonObject delta = choice.getAsJsonObject("delta");
                if (delta == null) return true;

                String rToken = getStringOrNull(delta, "reasoning_content");
                if (rToken == null) rToken = getStringOrNull(delta, "reasoning");
                String cToken = getStringOrNull(delta, "content");
                if (rToken != null && !rToken.isEmpty()) {
                    reasoning.append(rToken);
                    listener.onReasoning(rToken);
                }
                if (cToken != null && !cToken.isEmpty()) {
                    content.append(cToken);
                    listener.onContent(cToken);
                }
            } catch (JsonSyntaxException e) {
                logger.debug("Skipped malformed SSE chunk: " + e.getMessage());
            }
            return true;
        }

        @Override
        public LLMResponse finish() {
            return new LLMResponse(reasoning.toString(), content.toString(), usage, null, finishReason);
        }
    }
}
