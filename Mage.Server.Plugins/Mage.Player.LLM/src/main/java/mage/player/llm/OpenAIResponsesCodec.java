package mage.player.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.log4j.Logger;

/**
 * OpenAI Responses API dialect ({@code {base}/v1/responses}).
 *
 * <p>Differences from chat-completions that matter to the engine:</p>
 * <ul>
 *   <li>system prompt is top-level {@code instructions}; history is {@code input[]} items;</li>
 *   <li>reasoning is requested as {@code reasoning:{effort, summary:"auto"}} and comes
 *       back as <b>summaries</b> only (never raw CoT) — surfaced in
 *       {@link LLMResponse#thinking} with {@link LLMResponse#thinkingSummarized}=true;</li>
 *   <li>{@code status:"incomplete"} with {@code incomplete_details.reason==
 *       "max_output_tokens"} is the budget-died-during-reasoning signal and maps to
 *       {@code finishReason="length"}, which feeds the existing empty-answer ladder;</li>
 *   <li>there is no {@code stop} parameter — terse-answer stops are trimmed
 *       client-side (see {@link #requiresClientSideStop()}).</li>
 * </ul>
 */
public final class OpenAIResponsesCodec implements DialectCodec {

    private static final Logger logger = Logger.getLogger(OpenAIResponsesCodec.class);

    @Override
    public ApiDialect dialect() {
        return ApiDialect.OPENAI_RESPONSES;
    }

    @Override
    public String resolveEndpoint(LLMConfig config) {
        return DialectCodecs.deriveEndpoint(config, "/v1/responses");
    }

    @Override
    public boolean requiresClientSideStop() {
        return true;   // Responses has no stop parameter
    }

    // ------------------------------------------------------------------
    //  Requests
    // ------------------------------------------------------------------

    @Override
    public String buildSimpleRequest(LLMConfig config, String systemPrompt, String userPrompt,
                                     boolean stream, boolean noThinking) {
        return build(config, systemPrompt, null, new JsonArray(), userPrompt, stream, null, noThinking);
    }

    @Override
    public String buildConversationRequest(ConversationHistory h) {
        JsonArray input = new JsonArray();
        for (Turn turn : h.turns) {
            JsonObject item = new JsonObject();
            item.addProperty("role", turn.role);
            item.addProperty("content", turn.content);
            input.add(item);
        }
        return build(h.config, h.systemPrompt, h.summarizedHistory, input, h.currentPrompt,
                h.stream, h.effortOverride, h.noThinking);
    }

    private String build(LLMConfig config, String systemPrompt, String summarizedHistory,
                         JsonArray priorInput, String currentPrompt, boolean stream,
                         String effortOverride, boolean noThinking) {
        JsonObject root = new JsonObject();
        root.addProperty("model", config.getModel());

        String instructions = systemPrompt;
        if (summarizedHistory != null && !summarizedHistory.isEmpty()) {
            instructions = instructions + "\n\nPrior game summary:\n" + summarizedHistory;
        }
        root.addProperty("instructions", instructions);

        if (config.isTemperatureEnabled()) {
            root.addProperty("temperature", config.getTemperature());
        }
        if (config.getMaxTokens() > 0) {
            root.addProperty("max_output_tokens", config.getMaxTokens());
        }
        if (config.isThinkingEnabled() && !noThinking) {
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("effort", (effortOverride != null) ? effortOverride : config.getReasoningEffort());
            reasoning.addProperty("summary", "auto");
            root.add("reasoning", reasoning);
        }
        root.addProperty("stream", stream);

        JsonArray input = new JsonArray();
        for (JsonElement el : priorInput) {
            input.add(el);
        }
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", currentPrompt);
        input.add(userMsg);
        root.add("input", input);

        if (stream) {
            logger.debug("Responses dialect streams usage on response.completed; stream_options not sent");
        }
        return new com.google.gson.Gson().toJson(root);
    }

    // ------------------------------------------------------------------
    //  Response
    // ------------------------------------------------------------------

    @Override
    public LLMResponse parseResponse(LLMConfig config, String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            if (root.has("error") && !root.get("error").isJsonNull()) {
                throw new RuntimeException("Responses API error: " + root.get("error"));
            }
            LLMResponse.Usage usage = parseUsage(root, responseBody);

            StringBuilder thinking = new StringBuilder();
            StringBuilder answer = new StringBuilder();
            boolean hasReasoningItem = false;
            JsonArray output = root.has("output") ? root.getAsJsonArray("output") : null;
            if (output != null) {
                for (JsonElement el : output) {
                    if (!el.isJsonObject()) continue;
                    JsonObject item = el.getAsJsonObject();
                    String type = item.has("type") ? item.get("type").getAsString() : null;
                    if ("reasoning".equals(type)) {
                        hasReasoningItem = true;
                        appendSummary(item, thinking);
                    } else if ("message".equals(type)) {
                        appendText(item, answer);
                    }
                }
            }

            String status = root.has("status") ? root.get("status").getAsString() : null;
            String finishReason = mapFinishReason(root, status, responseBody);

            LLMResponse resp = new LLMResponse(
                    thinking.length() > 0 ? thinking.toString() : null,
                    answer.toString(),
                    usage, null, finishReason, thinking.length() > 0, null);
            if (usage != null && config.isVerboseLogging()) {
                logger.info("LLM (responses) " + usage);
            }
            if (hasReasoningItem && thinking.length() == 0 && root.has("status")
                    && "incomplete".equals(status)) {
                logger.warn("Responses API returned a reasoning item with no summary text"
                        + " (provider hid the summary) — thinking stays empty");
            }
            return resp;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to parse LLM response: " + t.getMessage(), t);
        }
    }

    private static void appendSummary(JsonObject reasoningItem, StringBuilder out) {
        if (!reasoningItem.has("summary") || !reasoningItem.get("summary").isJsonArray()) return;
        for (JsonElement el : reasoningItem.getAsJsonArray("summary")) {
            if (!el.isJsonObject()) continue;
            JsonObject part = el.getAsJsonObject();
            String text = part.has("text") ? part.get("text").getAsString() : null;
            if (text == null || text.isEmpty()) continue;
            if (out.length() > 0) out.append("\n\n");
            out.append(text);
        }
    }

    private static void appendText(JsonObject messageItem, StringBuilder out) {
        if (!messageItem.has("content") || !messageItem.get("content").isJsonArray()) return;
        for (JsonElement el : messageItem.getAsJsonArray("content")) {
            if (!el.isJsonObject()) continue;
            JsonObject part = el.getAsJsonObject();
            String type = part.has("type") ? part.get("type").getAsString() : null;
            if (!"output_text".equals(type)) continue;
            String text = part.has("text") ? part.get("text").getAsString() : null;
            if (text != null) out.append(text);
        }
    }

    /**
     * Status → the engine's finish_reason vocabulary. {@code incomplete} +
     * max_output_tokens becomes {@code "length"} (truncated at the output cap —
     * the budget-died-during-reasoning shape the empty-answer ladder handles).
     */
    private static String mapFinishReason(JsonObject root, String status, String body) {
        if (status == null) return null;
        if ("completed".equals(status)) return "stop";
        if ("incomplete".equals(status)) {
            String reason = null;
            if (root.has("incomplete_details") && root.get("incomplete_details").isJsonObject()) {
                JsonObject details = root.getAsJsonObject("incomplete_details");
                if (details.has("reason") && !details.get("reason").isJsonNull()) {
                    reason = details.get("reason").getAsString();
                }
            }
            if ("max_output_tokens".equals(reason)) return "length";
            throw new RuntimeException("Responses API returned status=incomplete with"
                    + " unrecognised reason '" + reason + "': " + body);
        }
        if ("failed".equals(status)) {
            throw new RuntimeException("Responses API call failed: " + body);
        }
        return status;
    }

    /** Responses usage: input_tokens / output_tokens / total_tokens / output_tokens_details.reasoning_tokens. */
    private static LLMResponse.Usage parseUsage(JsonObject root, String body) {
        if (!root.has("usage") || root.get("usage").isJsonNull()) return null;
        JsonObject u = root.getAsJsonObject("usage");
        Integer prompt = getIntOrNull(u, "input_tokens");
        Integer completion = getIntOrNull(u, "output_tokens");
        Integer total = getIntOrNull(u, "total_tokens");
        Integer reasoning = null;
        if (u.has("output_tokens_details") && u.get("output_tokens_details").isJsonObject()) {
            reasoning = getIntOrNull(u.getAsJsonObject("output_tokens_details"), "reasoning_tokens");
        }
        if (prompt == null && completion == null && total == null) {
            throw new RuntimeException("Responses API 'usage' object has no recognised token fields: "
                    + u + " (body: " + body + ")");
        }
        return new LLMResponse.Usage(prompt, completion, total, reasoning);
    }

    private static Integer getIntOrNull(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsInt();
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
            JsonObject event = JsonParser.parseString(data).getAsJsonObject();
            String type = event.has("type") ? event.get("type").getAsString() : null;
            if (type == null) return true;
            switch (type) {
                case "response.reasoning_summary_text.delta": {
                    String delta = event.has("delta") ? event.get("delta").getAsString() : null;
                    if (delta != null && !delta.isEmpty()) {
                        reasoning.append(delta);
                        listener.onReasoning(delta);
                    }
                    return true;
                }
                case "response.output_text.delta": {
                    String delta = event.has("delta") ? event.get("delta").getAsString() : null;
                    if (delta != null && !delta.isEmpty()) {
                        content.append(delta);
                        listener.onContent(delta);
                    }
                    return true;
                }
                case "response.completed": {
                    JsonObject response = event.getAsJsonObject("response");
                    if (response != null) {
                        usage = parseUsage(response, data);
                    }
                    finishReason = "stop";
                    return false;
                }
                case "response.incomplete": {
                    JsonObject response = event.getAsJsonObject("response");
                    String reason = null;
                    if (response != null && response.has("incomplete_details")
                            && response.get("incomplete_details").isJsonObject()) {
                        JsonObject details = response.getAsJsonObject("incomplete_details");
                        if (details.has("reason") && !details.get("reason").isJsonNull()) {
                            reason = details.get("reason").getAsString();
                        }
                    }
                    if ("max_output_tokens".equals(reason)) {
                        finishReason = "length";
                    } else {
                        throw new RuntimeException("Responses API stream ended incomplete with"
                                + " unrecognised reason '" + reason + "': " + data);
                    }
                    if (response != null) {
                        usage = parseUsage(response, data);
                    }
                    return false;
                }
                case "response.failed":
                case "error":
                    throw new RuntimeException("Responses API stream failed: " + data);
                default:
                    return true;
            }
        }

        @Override
        public LLMResponse finish() {
            return new LLMResponse(
                    reasoning.length() > 0 ? reasoning.toString() : null,
                    content.toString(),
                    usage, null, finishReason, reasoning.length() > 0, null);
        }
    }
}
