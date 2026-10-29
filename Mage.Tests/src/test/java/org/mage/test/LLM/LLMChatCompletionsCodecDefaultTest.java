package org.mage.test.LLM;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.player.llm.ApiDialect;
import mage.player.llm.DialectCodec;
import mage.player.llm.DialectCodecs;
import mage.player.llm.LLMConfig;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pins the Phase 2 default-behavior contract: with {@code api_dialect} unset or
 * {@code chat-completions}, the request bodies produced through the new
 * DialectCodec path are byte-identical to the pre-dialect implementation.
 *
 * <p>The expected strings below are the exact {@code new Gson().toJson(root)}
 * output of the old builders — key order included — so any drift in the codec
 * move shows up as a failing equality, not as a subtle field rename.</p>
 *
 * <p>No network: bodies are built directly through the codec.</p>
 */
public class LLMChatCompletionsCodecDefaultTest {

    /** Exact pre-change body: conversation turn, thinking=effort, explicit max_tokens, stop sequence. */
    private static final String EXPECTED_CONVERSATION_BODY =
            "{\"model\":\"probe-model\",\"reasoning_effort\":\"high\",\"thinking\":{\"type\":\"enabled\"},"
            + "\"max_tokens\":4096,\"stream\":false,"
            + "\"messages\":[{\"role\":\"system\",\"content\":\"SYS\"},"
            + "{\"role\":\"user\",\"content\":\"hello\"},"
            + "{\"role\":\"assistant\",\"content\":\"Decision: 1\"},"
            + "{\"role\":\"user\",\"content\":\"current prompt\"}],"
            + "\"stop\":[\"\\n\"]}";

    /** Exact pre-change body: startup probe / simple call. */
    private static final String EXPECTED_SIMPLE_BODY =
            "{\"model\":\"probe-model\",\"max_tokens\":4096,\"stream\":false,"
            + "\"reasoning_effort\":\"high\",\"thinking\":{\"type\":\"enabled\"},"
            + "\"messages\":[{\"role\":\"system\",\"content\":\"SYS\"},"
            + "{\"role\":\"user\",\"content\":\"pong\"}]}";

    /** Exact pre-change body: summarization one-shot (thinking disabled object, Ollama-classified endpoint). */
    private static final String EXPECTED_NO_THINKING_BODY =
            "{\"model\":\"probe-model\",\"max_tokens\":4096,\"stream\":false,"
            + "\"thinking\":{\"type\":\"disabled\"},"
            + "\"messages\":[{\"role\":\"system\",\"content\":\"SYS\"},"
            + "{\"role\":\"user\",\"content\":\"summarize\"}]}";

    private static LLMConfig base() {
        return new LLMConfig()
                .apiEndpoint("http://localhost:11434/v1/chat/completions")
                .model("probe-model")
                .thinkingEnabled(true)
                .reasoningEffort("high")
                .thinkingMaxTokens(0)   // no explicit reasoning cap: asserts dialect payload shape only
                .maxTokens(4096);
    }

    @Test
    public void defaultDialectIsChatCompletions() {
        Assert.assertEquals("chat-completions", new LLMConfig().getApiDialect());
        Assert.assertEquals(ApiDialect.CHAT_COMPLETIONS, DialectCodecs.forConfig(new LLMConfig()).dialect());
    }

    @Test
    public void conversationBodyIsByteIdenticalToPreDialectShape() {
        LLMConfig config = base();
        DialectCodec codec = DialectCodecs.forConfig(config);
        List<DialectCodec.Turn> turns = new ArrayList<>(Arrays.asList(
                new DialectCodec.Turn("user", "hello"),
                new DialectCodec.Turn("assistant", "Decision: 1")));
        String body = codec.buildConversationRequest(new DialectCodec.ConversationHistory(
                config, "SYS", null, turns, "current prompt", false, null, "\n", false));
        Assert.assertEquals(EXPECTED_CONVERSATION_BODY, body);
    }

    @Test
    public void simpleProbeBodyIsByteIdenticalToPreDialectShape() {
        LLMConfig config = base();
        DialectCodec codec = DialectCodecs.forConfig(config);
        Assert.assertEquals(EXPECTED_SIMPLE_BODY,
                codec.buildSimpleRequest(config, "SYS", "pong", false, false));
    }

    @Test
    public void noThinkingBodyIsByteIdenticalToPreDialectShape() {
        LLMConfig config = base();
        DialectCodec codec = DialectCodecs.forConfig(config);
        Assert.assertEquals(EXPECTED_NO_THINKING_BODY,
                codec.buildSimpleRequest(config, "SYS", "summarize", false, true));
    }

    @Test
    public void streamingConversationBodyAddsStreamOptionsAfterStop() {
        LLMConfig config = base();
        DialectCodec codec = DialectCodecs.forConfig(config);
        String body = codec.buildConversationRequest(new DialectCodec.ConversationHistory(
                config, "SYS", null, new ArrayList<>(), "current prompt", true, null, null, false));
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        Assert.assertTrue(root.get("stream").getAsBoolean());
        Assert.assertTrue(root.getAsJsonObject("stream_options").get("include_usage").getAsBoolean());
        Assert.assertFalse("no stop field when stopSequence is null", root.has("stop"));
    }

    @Test
    public void apiDialectSurvivesCopyConstructor() {
        LLMConfig original = base().apiDialect("anthropic-messages");
        LLMConfig copy = new LLMConfig(original);
        Assert.assertEquals("anthropic-messages", copy.getApiDialect());
    }

    @Test
    public void unknownDialectFailsFastWithValidValues() {
        try {
            base().apiDialect("openai_responses");
            Assert.fail("expected IllegalArgumentException for an unknown apiDialect");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue("message must list the valid values: " + e.getMessage(),
                    e.getMessage().contains("chat-completions")
                            && e.getMessage().contains("openai-responses")
                            && e.getMessage().contains("anthropic-messages"));
        }
    }
}
