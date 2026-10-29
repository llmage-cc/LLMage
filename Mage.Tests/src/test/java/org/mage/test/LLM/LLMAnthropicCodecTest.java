package org.mage.test.LLM;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.player.llm.AnthropicMessagesCodec;
import mage.player.llm.DialectCodec;
import mage.player.llm.DialectCodecs;
import mage.player.llm.LLMConfig;
import mage.player.llm.LLMResponse;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fixture-only unit tests for the Anthropic Messages dialect codec
 * ({@code api_dialect=anthropic-messages}): request shape, response parse,
 * max_tokens/thinking-budget invariants and SSE events.
 *
 * <p>No network: bodies are built through the codec, responses are literal JSON
 * strings.</p>
 */
public class LLMAnthropicCodecTest {

    private static LLMConfig base() {
        return new LLMConfig()
                .apiEndpoint("https://api.anthropic.com/v1/chat/completions")
                .model("claude-sonnet-4-5")
                .thinkingEnabled(true)
                .reasoningEffort("high")
                .apiDialect("anthropic-messages");
    }

    private static DialectCodec codec() {
        return DialectCodecs.forConfig(base());
    }

    private static JsonObject parse(String body) {
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private static JsonObject conversation(LLMConfig config, List<DialectCodec.Turn> turns, String stop) {
        return parse(codec().buildConversationRequest(new DialectCodec.ConversationHistory(
                config, "SYS", null, turns, "current prompt", false, null, stop, false)));
    }

    // ------------------------------------------------------------------
    //  Request shape
    // ------------------------------------------------------------------

    @Test
    public void endpointDerivedFromChatCompletionsUrl() {
        Assert.assertEquals("https://api.anthropic.com/v1/messages", codec().resolveEndpoint(base()));
        Assert.assertEquals("https://api.anthropic.com/v1/messages",
                codec().resolveEndpoint(base().apiEndpoint("https://api.anthropic.com/v1")));
        Assert.assertEquals("https://api.anthropic.com/v1/messages",
                codec().resolveEndpoint(base().apiEndpoint("https://api.anthropic.com/v1/messages")));
        Assert.assertFalse(codec().requiresClientSideStop());
    }

    @Test
    public void requestShapeUsesTopLevelSystemAndAdaptiveSummaryThinking() {
        JsonObject root = conversation(base(), new ArrayList<>(), "\n");
        Assert.assertEquals("claude-sonnet-4-5", root.get("model").getAsString());
        Assert.assertEquals("SYS", root.get("system").getAsString());
        Assert.assertEquals("max_tokens is mandatory", 16000, root.get("max_tokens").getAsInt());
        Assert.assertEquals("adaptive", root.getAsJsonObject("thinking").get("type").getAsString());
        Assert.assertEquals("summarized", root.getAsJsonObject("thinking").get("display").getAsString());
        Assert.assertFalse("no reasoning_effort in the Anthropic dialect", root.has("reasoning_effort"));
        Assert.assertFalse("no chat-completions messages/system split", root.has("instructions"));
        JsonArray messages = root.getAsJsonArray("messages");
        Assert.assertEquals(1, messages.size());
        Assert.assertEquals("user", messages.get(0).getAsJsonObject().get("role").getAsString());
        Assert.assertEquals("current prompt", messages.get(0).getAsJsonObject().get("content").getAsString());
        JsonArray stop = root.getAsJsonArray("stop_sequences");
        Assert.assertEquals("\n", stop.get(0).getAsString());
        Assert.assertFalse(root.get("stream").getAsBoolean());
    }

    @Test
    public void noThinkingUsesAdaptiveOmittedNotDisabled() {
        String body = codec().buildSimpleRequest(base(), "SYS", "summarize", false, true);
        JsonObject root = parse(body);
        JsonObject thinking = root.getAsJsonObject("thinking");
        Assert.assertEquals("adaptive", thinking.get("type").getAsString());
        Assert.assertEquals("omitted", thinking.get("display").getAsString());
        Assert.assertNotEquals("newest models 400 on disabled", "disabled", thinking.get("type").getAsString());
        Assert.assertTrue(root.has("max_tokens"));
    }

    // ------------------------------------------------------------------
    //  max_tokens / budget ladder
    // ------------------------------------------------------------------

    @Test
    public void budgetLadderClampsBottomRungTo1024() {
        LLMConfig config = base().thinkingDialect("budget").thinkingBudgetTokens("16000,8000,4000,1000");
        int[] rungs = {16000, 8000, 4000, 1000};
        int[] expected = {16000, 8000, 4000, 1024};
        for (int i = 0; i < rungs.length; i++) {
            String body = codec().buildConversationRequest(new DialectCodec.ConversationHistory(
                    config, "SYS", null, new ArrayList<>(), "prompt", false,
                    String.valueOf(rungs[i]), null, false));
            JsonObject root = parse(body);
            JsonObject thinking = root.getAsJsonObject("thinking");
            Assert.assertEquals("enabled", thinking.get("type").getAsString());
            Assert.assertEquals("rung " + rungs[i] + " must be clamped to " + expected[i],
                    expected[i], thinking.get("budget_tokens").getAsInt());
            Assert.assertTrue("max_tokens must exceed the thinking budget at rung " + rungs[i],
                    root.get("max_tokens").getAsInt() > thinking.get("budget_tokens").getAsInt());
        }
    }

    @Test
    public void configuredMaxTokensMustExceedThinkingBudget() {
        LLMConfig config = base().thinkingDialect("budget").maxTokens(2000);
        try {
            codec().buildConversationRequest(new DialectCodec.ConversationHistory(
                    config, "SYS", null, new ArrayList<>(), "prompt", false, "4000", null, false));
            Assert.fail("expected a Fail Fast when max_tokens cannot exceed the thinking budget");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("max_tokens"));
            Assert.assertTrue(e.getMessage().contains("2000"));
            Assert.assertTrue(e.getMessage().contains("4000"));
        }
    }

    @Test
    public void anthropicBudgetClampHelper() {
        Assert.assertEquals(1024, AnthropicMessagesCodec.clampBudget(1000));
        Assert.assertEquals(1024, AnthropicMessagesCodec.clampBudget(1024));
        Assert.assertEquals(4000, AnthropicMessagesCodec.clampBudget(4000));
    }

    // ------------------------------------------------------------------
    //  Response parse
    // ------------------------------------------------------------------

    @Test
    public void responseParseCollectsThinkingSummaryAndText() {
        String body = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"thinking\",\"thinking\":\"Play the land first.\","
                + "\"signature\":\"sig-abc\"},{\"type\":\"text\",\"text\":\"3\"}],"
                + "\"stop_reason\":\"end_turn\","
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
        LLMResponse resp = codec().parseResponse(base(), body);
        Assert.assertEquals("Play the land first.", resp.thinking);
        Assert.assertTrue(resp.thinkingSummarized);
        Assert.assertEquals("3", resp.answer);
        Assert.assertEquals("stop", resp.finishReason);
        Assert.assertEquals(Integer.valueOf(10), resp.usage.promptTokens);
        Assert.assertEquals(Integer.valueOf(5), resp.usage.completionTokens);
        Assert.assertEquals(Integer.valueOf(15), resp.usage.totalTokens);
        Assert.assertNotNull(resp.contentBlocks);
        Assert.assertTrue("blocks must keep the signature", resp.contentBlocks.contains("sig-abc"));
        Assert.assertTrue(resp.contentBlocks.contains("\"thinking\""));
    }

    @Test
    public void maxTokensStopReasonMapsToLength() {
        String body = "{\"type\":\"message\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"...\"}],"
                + "\"stop_reason\":\"max_tokens\",\"usage\":{\"input_tokens\":1,\"output_tokens\":16000}}";
        LLMResponse resp = codec().parseResponse(base(), body);
        Assert.assertTrue("no text block -> empty answer", resp.answer != null && resp.answer.isEmpty());
        Assert.assertEquals("length", resp.finishReason);
    }

    @Test
    public void errorResponseFailsFast() {
        String body = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"max_tokens: must be greater than thinking.budget_tokens\"}}";
        try {
            codec().parseResponse(base(), body);
            Assert.fail("expected a Fail Fast on an Anthropic error body");
        } catch (RuntimeException e) {
            Assert.assertTrue(e.getMessage().contains("invalid_request_error")
                    || e.getMessage().contains("budget_tokens"));
        }
    }

    // ------------------------------------------------------------------
    //  Streaming
    // ------------------------------------------------------------------

    @Test
    public void streamEventsParseIncludingSignature() {
        final StringBuilder reasoning = new StringBuilder();
        final StringBuilder content = new StringBuilder();
        DialectCodec.StreamListener listener = new DialectCodec.StreamListener() {
            @Override
            public void onReasoning(String token) { reasoning.append(token); }

            @Override
            public void onContent(String token) { content.append(token); }
        };
        DialectCodec.StreamParser parser = codec().newStreamParser(base(), listener);
        Assert.assertTrue(parser.accept("{\"type\":\"message_start\",\"message\":{\"usage\":"
                + "{\"input_tokens\":11,\"output_tokens\":0}}}"));
        Assert.assertTrue(parser.accept("{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}"));
        Assert.assertTrue(parser.accept("{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"think \"}}"));
        Assert.assertTrue(parser.accept("{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig-xyz\"}}"));
        Assert.assertTrue(parser.accept("{\"type\":\"content_block_stop\",\"index\":0}"));
        Assert.assertTrue(parser.accept("{\"type\":\"content_block_start\",\"index\":1,"
                + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
        Assert.assertTrue(parser.accept("{\"type\":\"content_block_delta\",\"index\":1,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"3\"}}"));
        Assert.assertTrue(parser.accept("{\"type\":\"content_block_stop\",\"index\":1}"));
        Assert.assertTrue(parser.accept("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                + "\"usage\":{\"output_tokens\":9}}"));
        Assert.assertFalse("message_stop terminates the stream", parser.accept("{\"type\":\"message_stop\"}"));

        LLMResponse resp = parser.finish();
        Assert.assertEquals("think ", resp.thinking);
        Assert.assertEquals("think ", reasoning.toString());
        Assert.assertEquals("3", resp.answer);
        Assert.assertEquals("stop", resp.finishReason);
        Assert.assertTrue(resp.thinkingSummarized);
        Assert.assertEquals(Integer.valueOf(11), resp.usage.promptTokens);
        Assert.assertEquals(Integer.valueOf(9), resp.usage.completionTokens);
        Assert.assertNotNull(resp.contentBlocks);
        Assert.assertTrue("signature_delta must be captured into the replayed blocks",
                resp.contentBlocks.contains("sig-xyz"));
    }

    // ------------------------------------------------------------------
    //  Multi-turn content-block round trip
    // ------------------------------------------------------------------

    @Test
    public void priorThinkingTurnBlocksAreReplayedVerbatim() {
        String blocks = "[{\"type\":\"thinking\",\"thinking\":\"prior reasoning\",\"signature\":\"sig-1\"},"
                + "{\"type\":\"text\",\"text\":\"Decision: 1\"}]";
        List<DialectCodec.Turn> turns = new ArrayList<>(Arrays.asList(
                new DialectCodec.Turn("user", "hello"),
                new DialectCodec.Turn("assistant", "Decision: 1", blocks, true)));
        JsonObject root = conversation(base(), turns, null);
        JsonArray messages = root.getAsJsonArray("messages");
        Assert.assertEquals(3, messages.size());
        JsonObject assistant = messages.get(1).getAsJsonObject();
        Assert.assertEquals("assistant", assistant.get("role").getAsString());
        JsonArray content = assistant.getAsJsonArray("content");
        Assert.assertEquals("blocks are replayed as an array, not flattened text", 2, content.size());
        Assert.assertEquals("thinking", content.get(0).getAsJsonObject().get("type").getAsString());
        Assert.assertEquals("sig-1", content.get(0).getAsJsonObject().get("signature").getAsString());
        Assert.assertEquals("Decision: 1", content.get(1).getAsJsonObject().get("text").getAsString());
    }

    @Test
    public void priorTurnWithoutThinkingBlocksUsesPlainText() {
        List<DialectCodec.Turn> turns = new ArrayList<>(Arrays.asList(
                new DialectCodec.Turn("user", "hello"),
                new DialectCodec.Turn("assistant", "Decision: 1")));
        JsonObject root = conversation(base(), turns, null);
        JsonObject assistant = root.getAsJsonArray("messages").get(1).getAsJsonObject();
        Assert.assertEquals("Decision: 1", assistant.get("content").getAsString());
    }

    @Test
    public void missingBlocksForThinkingTurnFailsFast() {
        List<DialectCodec.Turn> turns = new ArrayList<>(Arrays.asList(
                new DialectCodec.Turn("user", "hello"),
                new DialectCodec.Turn("assistant", "Decision: 1", null, true)));
        try {
            conversation(base(), turns, null);
            Assert.fail("expected a Fail Fast when a thinking turn's blocks are missing");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("assistant turn 2"));
            Assert.assertTrue(e.getMessage().contains("content blocks are missing"));
        }
    }
}
