package org.mage.test.LLM;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 * Fixture-only unit tests for the OpenAI Responses dialect codec
 * ({@code api_dialect=openai-responses}). No network: request bodies are built
 * through the codec and responses are literal JSON strings.
 */
public class LLMResponsesCodecTest {

    private static LLMConfig base() {
        return new LLMConfig()
                .apiEndpoint("https://api.openai.com/v1/chat/completions")
                .model("gpt-5.5")
                .thinkingEnabled(true)
                .reasoningEffort("high")
                .maxTokens(16000)
                .apiDialect("openai-responses");
    }

    private static DialectCodec codec() {
        return DialectCodecs.forConfig(base());
    }

    private static JsonObject conversationBody(LLMConfig config, String stop) {
        List<DialectCodec.Turn> turns = new ArrayList<>(Arrays.asList(
                new DialectCodec.Turn("user", "hello"),
                new DialectCodec.Turn("assistant", "Decision: 1")));
        String body = DialectCodecs.forConfig(config).buildConversationRequest(
                new DialectCodec.ConversationHistory(config, "SYS", "SUMMARY", turns,
                        "current prompt", false, null, stop, false));
        return JsonParser.parseString(body).getAsJsonObject();
    }

    @Test
    public void endpointDerivedFromChatCompletionsUrl() {
        Assert.assertEquals("https://api.openai.com/v1/responses", codec().resolveEndpoint(base()));
        LLMConfig baseUrl = base().apiEndpoint("https://api.openai.com/v1");
        Assert.assertEquals("https://api.openai.com/v1/responses", codec().resolveEndpoint(baseUrl));
        try {
            codec().resolveEndpoint(base().apiEndpoint("https://api.openai.com/openai"));
            Assert.fail("expected a Fail Fast on an underivable endpoint");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("/v1/responses"));
        }
    }

    @Test
    public void requestShape() {
        JsonObject root = conversationBody(base(), null);
        Assert.assertEquals("gpt-5.5", root.get("model").getAsString());
        Assert.assertEquals("SYS\n\nPrior game summary:\nSUMMARY", root.get("instructions").getAsString());
        Assert.assertEquals(16000, root.get("max_output_tokens").getAsInt());
        Assert.assertFalse(root.get("stream").getAsBoolean());
        Assert.assertEquals("high", root.getAsJsonObject("reasoning").get("effort").getAsString());
        Assert.assertEquals("auto", root.getAsJsonObject("reasoning").get("summary").getAsString());

        JsonArray input = root.getAsJsonArray("input");
        Assert.assertEquals(3, input.size());
        Assert.assertEquals("user", input.get(0).getAsJsonObject().get("role").getAsString());
        Assert.assertEquals("hello", input.get(0).getAsJsonObject().get("content").getAsString());
        Assert.assertEquals("assistant", input.get(1).getAsJsonObject().get("role").getAsString());
        Assert.assertEquals("current prompt", input.get(2).getAsJsonObject().get("content").getAsString());
        Assert.assertFalse("Responses has no stop parameter", root.has("stop"));
        Assert.assertTrue(codec().requiresClientSideStop());
    }

    @Test
    public void responseParseCollectsReasoningSummaryAndMessageText() {
        String body = "{\"id\":\"resp_1\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"I should play a land.\"}]},"
                + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"3\"}]}],"
                + "\"usage\":{\"input_tokens\":100,\"output_tokens\":50,\"total_tokens\":150,"
                + "\"output_tokens_details\":{\"reasoning_tokens\":20}}}";
        LLMResponse resp = codec().parseResponse(base(), body);
        Assert.assertEquals("I should play a land.", resp.thinking);
        Assert.assertTrue("reasoning summaries are provider summaries, not raw CoT", resp.thinkingSummarized);
        Assert.assertEquals("3", resp.answer);
        Assert.assertEquals("stop", resp.finishReason);
        Assert.assertEquals(Integer.valueOf(100), resp.usage.promptTokens);
        Assert.assertEquals(Integer.valueOf(50), resp.usage.completionTokens);
        Assert.assertEquals(Integer.valueOf(150), resp.usage.totalTokens);
        Assert.assertEquals(Integer.valueOf(20), resp.usage.reasoningTokens);
        Assert.assertNull("Responses has no content-block round-trip payload", resp.contentBlocks);
    }

    @Test
    public void incompleteMaxOutputTokensMapsToLength() {
        String body = "{\"id\":\"resp_2\",\"status\":\"incomplete\","
                + "\"incomplete_details\":{\"reason\":\"max_output_tokens\"},"
                + "\"output\":[{\"type\":\"reasoning\",\"summary\":["
                + "{\"type\":\"summary_text\",\"text\":\"still thinking\"}]}],"
                + "\"usage\":{\"input_tokens\":100,\"output_tokens\":16000,\"total_tokens\":16100,"
                + "\"output_tokens_details\":{\"reasoning_tokens\":15900}}}";
        LLMResponse resp = codec().parseResponse(base(), body);
        Assert.assertTrue("no answer was produced", resp.answer != null && resp.answer.isEmpty());
        Assert.assertEquals("length", resp.finishReason);
        Assert.assertEquals("still thinking", resp.thinking);
        Assert.assertEquals(Integer.valueOf(15900), resp.usage.reasoningTokens);
    }

    @Test
    public void unrecognisedIncompleteReasonFailsFast() {
        String body = "{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"content_filter\"},"
                + "\"output\":[]}";
        try {
            codec().parseResponse(base(), body);
            Assert.fail("expected a Fail Fast on an unrecognised incomplete reason");
        } catch (RuntimeException e) {
            Assert.assertTrue(e.getMessage().contains("content_filter"));
        }
    }

    @Test
    public void streamEventsParse() {
        final StringBuilder reasoning = new StringBuilder();
        final StringBuilder content = new StringBuilder();
        DialectCodec.StreamListener listener = new DialectCodec.StreamListener() {
            @Override
            public void onReasoning(String token) { reasoning.append(token); }

            @Override
            public void onContent(String token) { content.append(token); }
        };
        DialectCodec.StreamParser parser = codec().newStreamParser(base(), listener);
        Assert.assertTrue(parser.accept(
                "{\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"think \"}"));
        Assert.assertTrue(parser.accept(
                "{\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"more\"}"));
        Assert.assertTrue(parser.accept(
                "{\"type\":\"response.output_text.delta\",\"delta\":\"3\"}"));
        Assert.assertFalse("response.completed terminates the stream", parser.accept(
                "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\","
                        + "\"usage\":{\"input_tokens\":7,\"output_tokens\":2,\"total_tokens\":9,"
                        + "\"output_tokens_details\":{\"reasoning_tokens\":1}}}}"));
        LLMResponse resp = parser.finish();
        Assert.assertEquals("think more", resp.thinking);
        Assert.assertEquals("think more", reasoning.toString());
        Assert.assertEquals("3", resp.answer);
        Assert.assertEquals("3", content.toString());
        Assert.assertEquals("stop", resp.finishReason);
        Assert.assertTrue(resp.thinkingSummarized);
        Assert.assertEquals(Integer.valueOf(1), resp.usage.reasoningTokens);
    }

    @Test
    public void streamIncompleteMaxOutputTokensMapsToLength() {
        DialectCodec.StreamParser parser =
                codec().newStreamParser(base(), new NoopListener());
        parser.accept("{\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"thinking\"}");
        Assert.assertFalse(parser.accept("{\"type\":\"response.incomplete\",\"response\":{"
                + "\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},"
                + "\"usage\":{\"input_tokens\":5,\"output_tokens\":99,\"total_tokens\":104}}}"));
        LLMResponse resp = parser.finish();
        Assert.assertTrue(resp.answer != null && resp.answer.isEmpty());
        Assert.assertEquals("length", resp.finishReason);
    }

    private static final class NoopListener implements DialectCodec.StreamListener {
        @Override
        public void onReasoning(String token) { }

        @Override
        public void onContent(String token) { }
    }
}
