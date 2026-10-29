package mage.player.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.log4j.Logger;

/**
 * Anthropic Messages API dialect ({@code {base}/v1/messages}).
 *
 * <p>Shape differences that matter:</p>
 * <ul>
 *   <li>system prompt is a top-level {@code system} string (summary appended to it);</li>
 *   <li>{@code max_tokens} is mandatory and must exceed the thinking budget — derived
 *       from config when unset, and a hard error when the configured value cannot
 *       exceed it (no silent bump);</li>
 *   <li>thinking is {@code {type:"adaptive", display:"summarized"}} (or an explicit
 *       {@code enabled} budget for the budget dialect); the returned blocks are
 *       summaries, never raw CoT;</li>
 *   <li><b>multi-turn round-trip:</b> an assistant turn that produced thinking
 *       blocks must be sent back verbatim as a content-block array. Missing blocks
 *       for such a turn throw — a silent drop is an HTTP 400 from the provider;</li>
 *   <li>every reasoning budget rung is clamped to &gt;= 1024 (the API floor);</li>
 *   <li>{@code stop_sequences} is a direct equivalent of chat-completions' {@code stop}.</li>
 * </ul>
 */
public final class AnthropicMessagesCodec implements DialectCodec {

    private static final Logger logger = Logger.getLogger(AnthropicMessagesCodec.class);

    /** Floor required by the Messages API for thinking.budget_tokens. */
    public static final int MIN_THINKING_BUDGET = 1024;

    /** Default output ceiling when max_tokens is not configured. */
    private static final int DEFAULT_MAX_TOKENS = 16000;

    /** Headroom added above the thinking budget when deriving max_tokens. */
    private static final int ANSWER_HEADROOM_TOKENS = 8192;

    @Override
    public ApiDialect dialect() {
        return ApiDialect.ANTHROPIC_MESSAGES;
    }

    @Override
    public String resolveEndpoint(LLMConfig config) {
        return DialectCodecs.deriveEndpoint(config, "/v1/messages");
    }

    @Override
    public boolean requiresClientSideStop() {
        return false;   // stop_sequences is a first-class parameter
    }

    // ------------------------------------------------------------------
    //  Requests
    // ------------------------------------------------------------------

    @Override
    public String buildSimpleRequest(LLMConfig config, String systemPrompt, String userPrompt,
                                     boolean stream, boolean noThinking) {
        JsonArray messages = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userPrompt);
        messages.add(user);
        return build(config, systemPrompt, null, messages, stream, null, noThinking);
    }

    @Override
    public String buildConversationRequest(ConversationHistory h) {
        JsonArray messages = new JsonArray();
        int index = 0;
        for (Turn turn : h.turns) {
            index++;
            JsonObject msg = new JsonObject();
            msg.addProperty("role", turn.role);
            if ("assistant".equals(turn.role)) {
                if (turn.contentBlocks != null) {
                    // Verbatim replay: thinking block + signature + text. The
                    // provider rejects a request that drops them.
                    msg.add("content", JsonParser.parseString(turn.contentBlocks));
                } else if (turn.contentBlocksRequired) {
                    throw new IllegalStateException("Anthropic dialect: assistant turn " + index
                            + " produced thinking blocks, but its content blocks are missing from"
                            + " conversation history. The request would be rejected by the provider;"
                            + " refusing to send a silently-dropped history."
                            + " (turn content starts: '" + abbreviate(turn.content) + "')");
                } else {
                    msg.addProperty("content", turn.content);
                }
            } else {
                msg.addProperty("content", turn.content);
            }
            messages.add(msg);
        }
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", h.currentPrompt);
        messages.add(user);
        return build(h.config, h.systemPrompt, h.summarizedHistory, messages,
                h.stream, h.stopSequence, h.noThinking, h.effortOverride);
    }

    private String build(LLMConfig config, String systemPrompt, String summarizedHistory,
                         JsonArray messages, boolean stream, String stopSequence, boolean noThinking) {
        return build(config, systemPrompt, summarizedHistory, messages, stream, stopSequence, noThinking, null);
    }

    private String build(LLMConfig config, String systemPrompt, String summarizedHistory,
                         JsonArray messages, boolean stream, String stopSequence, boolean noThinking,
                         String effortOverride) {
        JsonObject root = new JsonObject();
        root.addProperty("model", config.getModel());

        // Thinking first: its budget constrains max_tokens.
        Integer thinkingBudget = null;
        JsonObject thinking = null;
        if (config.isThinkingEnabled()) {
            thinking = new JsonObject();
            if (noThinking) {
                // The newest models 400 on {type:"disabled"}; adaptive+omitted keeps
                // the call legal — thinking still happens and is billed, but no
                // blocks come back (fine for a summarizer one-shot).
                thinking.addProperty("type", "adaptive");
                thinking.addProperty("display", "omitted");
            } else if ("budget".equals(config.getThinkingDialect())) {
                int rung = (effortOverride != null)
                        ? Integer.parseInt(effortOverride)
                        : DialectCodecs.currentBudgetTokens(config);
                thinkingBudget = clampBudget(rung);
                thinking.addProperty("type", "enabled");
                thinking.addProperty("budget_tokens", thinkingBudget);
            } else {
                thinking.addProperty("type", "adaptive");
                thinking.addProperty("display", "summarized");
            }
        }

        root.addProperty("max_tokens", resolveMaxTokens(config, thinkingBudget));

        String system = systemPrompt;
        if (summarizedHistory != null && !summarizedHistory.isEmpty()) {
            system = system + "\n\nPrior game summary:\n" + summarizedHistory;
        }
        root.addProperty("system", system);

        if (thinking != null) {
            root.add("thinking", thinking);
        }
        if (config.isTemperatureEnabled()) {
            root.addProperty("temperature", config.getTemperature());
        }
        root.addProperty("stream", stream);
        root.add("messages", messages);
        if (stopSequence != null) {
            JsonArray stop = new JsonArray();
            stop.add(stopSequence);
            root.add("stop_sequences", stop);
        }
        return new com.google.gson.Gson().toJson(root);
    }

    /** Every reasoning budget rung is clamped to the API floor. */
    public static int clampBudget(int rung) {
        return Math.max(rung, MIN_THINKING_BUDGET);
    }

    /**
     * Anthropic requires max_tokens. When unset, derive a value that leaves the
     * answer real room above the thinking budget; when set, it MUST exceed the
     * budget — a configured value that cannot is a hard error (no silent bump).
     */
    public static int resolveMaxTokens(LLMConfig config, Integer thinkingBudget) {
        int budget = (thinkingBudget == null) ? 0 : thinkingBudget;
        int configured = config.getMaxTokens();
        int maxTokens;
        if (configured > 0) {
            maxTokens = configured;
        } else if (budget > 0) {
            maxTokens = Math.max(DEFAULT_MAX_TOKENS, budget + ANSWER_HEADROOM_TOKENS);
        } else {
            maxTokens = DEFAULT_MAX_TOKENS;
        }
        if (budget > 0 && maxTokens <= budget) {
            throw new IllegalStateException("Anthropic dialect: max_tokens (" + maxTokens
                    + ") must exceed the thinking budget (" + budget + "). Raise xmage.llm[2].max_tokens"
                    + " or lower the thinking budget ladder.");
        }
        return maxTokens;
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() <= 60 ? s : s.substring(0, 60) + "...";
    }

    // ------------------------------------------------------------------
    //  Response
    // ------------------------------------------------------------------

    @Override
    public LLMResponse parseResponse(LLMConfig config, String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            if (root.has("type") && "error".equals(root.get("type").getAsString())) {
                throw new RuntimeException("Anthropic API error: " + responseBody);
            }
            StringBuilder thinking = new StringBuilder();
            StringBuilder answer = new StringBuilder();
            JsonArray blocks = root.has("content") ? root.getAsJsonArray("content") : null;
            boolean hasThinkingBlock = false;
            if (blocks != null) {
                for (JsonElement el : blocks) {
                    if (!el.isJsonObject()) continue;
                    JsonObject block = el.getAsJsonObject();
                    String type = block.has("type") ? block.get("type").getAsString() : null;
                    if ("thinking".equals(type)) {
                        hasThinkingBlock = true;
                        if (block.has("thinking") && !block.get("thinking").isJsonNull()) {
                            thinking.append(block.get("thinking").getAsString());
                        }
                    } else if ("redacted_thinking".equals(type)) {
                        hasThinkingBlock = true;
                    } else if ("text".equals(type)) {
                        if (block.has("text") && !block.get("text").isJsonNull()) {
                            answer.append(block.get("text").getAsString());
                        }
                    }
                }
            }
            LLMResponse.Usage usage = parseUsage(root, responseBody);
            String finishReason = mapStopReason(root, responseBody);
            String contentBlocks = hasThinkingBlock ? blocks.toString() : null;

            LLMResponse resp = new LLMResponse(
                    thinking.length() > 0 ? thinking.toString() : null,
                    answer.toString(),
                    usage, null, finishReason, thinking.length() > 0, contentBlocks);
            if (usage != null && config.isVerboseLogging()) {
                logger.info("LLM (anthropic) " + usage);
            }
            return resp;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to parse LLM response: " + t.getMessage(), t);
        }
    }

    /** max_tokens is the engine's "length" (truncated at the output cap). */
    private static String mapStopReason(JsonObject root, String body) {
        if (!root.has("stop_reason") || root.get("stop_reason").isJsonNull()) return null;
        String reason = root.get("stop_reason").getAsString();
        switch (reason) {
            case "end_turn":
            case "stop_sequence":
                return "stop";
            case "max_tokens":
                return "length";
            default:
                return reason;
        }
    }

    private static LLMResponse.Usage parseUsage(JsonObject root, String body) {
        if (!root.has("usage") || root.get("usage").isJsonNull()) return null;
        JsonObject u = root.getAsJsonObject("usage");
        Integer prompt = getIntOrNull(u, "input_tokens");
        Integer completion = getIntOrNull(u, "output_tokens");
        if (prompt == null && completion == null) {
            throw new RuntimeException("Anthropic 'usage' object has no input_tokens/output_tokens: "
                    + u + " (body: " + body + ")");
        }
        Integer total = (prompt == null || completion == null) ? null : prompt + completion;
        // The Messages API does not break reasoning out of output_tokens.
        return new LLMResponse.Usage(prompt, completion, total, null);
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
        private final StringBuilder thinking = new StringBuilder();
        private final StringBuilder answer = new StringBuilder();
        private final JsonArray blocks = new JsonArray();
        private final StreamListener listener;
        private JsonObject currentBlock;
        private boolean currentBlockHasThinking;
        private Integer inputTokens;
        private Integer outputTokens;
        private String stopReason;

        Parser(StreamListener listener) {
            this.listener = listener;
        }

        @Override
        public boolean accept(String data) {
            JsonObject event = JsonParser.parseString(data).getAsJsonObject();
            String type = event.has("type") ? event.get("type").getAsString() : null;
            if (type == null) return true;
            switch (type) {
                case "message_start": {
                    JsonObject message = event.getAsJsonObject("message");
                    if (message != null && message.has("usage") && message.get("usage").isJsonObject()) {
                        inputTokens = getIntOrNull(message.getAsJsonObject("usage"), "input_tokens");
                    }
                    return true;
                }
                case "content_block_start": {
                    JsonObject block = event.getAsJsonObject("content_block");
                    currentBlock = (block == null) ? null : block.deepCopy();
                    currentBlockHasThinking = false;
                    if (currentBlock != null && currentBlock.has("type")) {
                        String blockType = currentBlock.get("type").getAsString();
                        currentBlockHasThinking = "thinking".equals(blockType)
                                || "redacted_thinking".equals(blockType);
                    }
                    return true;
                }
                case "content_block_delta": {
                    JsonObject delta = event.getAsJsonObject("delta");
                    if (delta == null || currentBlock == null) return true;
                    String deltaType = delta.has("type") ? delta.get("type").getAsString() : null;
                    if ("thinking_delta".equals(deltaType)) {
                        String text = delta.has("thinking") ? delta.get("thinking").getAsString() : null;
                        if (text != null && !text.isEmpty()) {
                            thinking.append(text);
                            appendToBlock("thinking", text);
                            listener.onReasoning(text);
                        }
                    } else if ("text_delta".equals(deltaType)) {
                        String text = delta.has("text") ? delta.get("text").getAsString() : null;
                        if (text != null && !text.isEmpty()) {
                            answer.append(text);
                            appendToBlock("text", text);
                            listener.onContent(text);
                        }
                    } else if ("signature_delta".equals(deltaType)) {
                        String sig = delta.has("signature") ? delta.get("signature").getAsString() : null;
                        if (sig != null) appendToBlock("signature", sig);
                    }
                    return true;
                }
                case "content_block_stop": {
                    if (currentBlock != null) {
                        blocks.add(currentBlock);
                        currentBlock = null;
                    }
                    return true;
                }
                case "message_delta": {
                    if (event.has("delta") && event.get("delta").isJsonObject()) {
                        JsonObject delta = event.getAsJsonObject("delta");
                        if (delta.has("stop_reason") && !delta.get("stop_reason").isJsonNull()) {
                            stopReason = delta.get("stop_reason").getAsString();
                        }
                    }
                    if (event.has("usage") && event.get("usage").isJsonObject()) {
                        outputTokens = getIntOrNull(event.getAsJsonObject("usage"), "output_tokens");
                    }
                    return true;
                }
                case "message_stop":
                    return false;
                case "error":
                    throw new RuntimeException("Anthropic API stream failed: " + data);
                default:
                    return true;
            }
        }

        private void appendToBlock(String field, String text) {
            String existing = (currentBlock.has(field) && !currentBlock.get(field).isJsonNull())
                    ? currentBlock.get(field).getAsString() : "";
            currentBlock.addProperty(field, existing + text);
        }

        @Override
        public LLMResponse finish() {
            boolean hasThinkingBlock = false;
            for (JsonElement el : blocks) {
                if (!el.isJsonObject()) continue;
                JsonObject b = el.getAsJsonObject();
                String t = b.has("type") ? b.get("type").getAsString() : null;
                if ("thinking".equals(t) || "redacted_thinking".equals(t)) {
                    hasThinkingBlock = true;
                    break;
                }
            }
            Integer total = (inputTokens == null || outputTokens == null)
                    ? null : inputTokens + outputTokens;
            LLMResponse.Usage usage = (inputTokens == null && outputTokens == null)
                    ? null : new LLMResponse.Usage(inputTokens, outputTokens, total, null);
            String finishReason = null;
            if (stopReason != null) {
                switch (stopReason) {
                    case "end_turn":
                    case "stop_sequence":
                        finishReason = "stop";
                        break;
                    case "max_tokens":
                        finishReason = "length";
                        break;
                    default:
                        finishReason = stopReason;
                }
            }
            return new LLMResponse(
                    thinking.length() > 0 ? thinking.toString() : null,
                    answer.toString(),
                    usage, null, finishReason, thinking.length() > 0,
                    hasThinkingBlock ? blocks.toString() : null);
        }
    }
}
