package org.mage.test.LLM;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.player.llm.LLMClient;
import mage.player.llm.LLMConfig;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;

/**
 * Pins the thinking-disabled FINAL attempt of the empty-answer retry ladder
 * (fix for repeated empty-answer stalls).
 *
 * The retry ladder descends reasoning effort on every retry, but descent only
 * shrinks the reasoning allowance — it never removes it. A model that loops at
 * every effort still starves the answer, so the last attempt must turn thinking
 * OFF entirely: the built body carries NO {@code thinking} object and NO
 * {@code reasoning_effort}, while {@code max_tokens} (authoritative — 65536 in
 * production via start-server.sh) is preserved so the ENTIRE output budget is
 * available to the answer stream.
 *
 * Runs offline: the bodies are built by reflection, no HTTP call is made. The
 * 5-arg overload is the no-thinking switch; the 4-arg overload must keep
 * delegating with noThinking=false (the existing body-shape tests resolve the
 * 4-arg signature).
 */
public class LLMNoThinkingFinalAttemptBodyTest {

    private static final String OLLAMA = "http://localhost:11434/v1/chat/completions";
    private static final String ZEN = "https://opencode.ai/zen/go/v1/chat/completions";

    private static final Method BODY_CONV_4;
    private static final Method BODY_CONV_5;

    static {
        try {
            BODY_CONV_4 = LLMClient.class.getDeclaredMethod(
                    "buildRequestBodyConv", boolean.class, String.class, String.class, String.class);
            BODY_CONV_4.setAccessible(true);
            BODY_CONV_5 = LLMClient.class.getDeclaredMethod(
                    "buildRequestBodyConv", boolean.class, String.class, String.class, String.class, boolean.class);
            BODY_CONV_5.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static JsonObject body(LLMConfig config, boolean noThinking) throws Exception {
        LLMClient client = new LLMClient(config);
        client.resetConversationForTest("sys");
        return JsonParser.parseString((String) BODY_CONV_5.invoke(client, false, "prompt", null, null, noThinking))
                .getAsJsonObject();
    }

    private static LLMConfig base(String endpoint) {
        return new LLMConfig()
                .apiEndpoint(endpoint)
                .model("probe-model")
                .thinkingEnabled(true)
                .reasoningEffort("max")
                .reasoningEffortLevels("max,high,medium,low");
    }

    @Test
    public void noThinkingBodyDropsThinkingAndReasoningEffortButKeepsMaxTokens() throws Exception {
        // Production shape (start-server.sh): max_tokens=65536 (authoritative),
        // reasoning_effort=medium, thinking={enabled,budget_tokens:32768}.
        JsonObject root = body(base(OLLAMA).thinkingMaxTokens(32768).maxTokens(65536), true);
        Assert.assertFalse("no-thinking body must NOT carry a thinking object", root.has("thinking"));
        Assert.assertFalse("no-thinking body must NOT carry reasoning_effort", root.has("reasoning_effort"));
        Assert.assertTrue("max_tokens must survive — the answer needs the full budget", root.has("max_tokens"));
        Assert.assertEquals("the authoritative max_tokens is preserved unchanged",
                65536, root.get("max_tokens").getAsInt());
        Assert.assertEquals("probe-model", root.get("model").getAsString());
        Assert.assertEquals("stream is still emitted", false, root.get("stream").getAsBoolean());
        Assert.assertTrue("messages still present", root.has("messages"));
    }

    @Test
    public void noThinkingBodyDropsThinkingEvenForStrictOpenAiEndpoint() throws Exception {
        // On a strict OpenAI-compatible endpoint the thinking object was already
        // gated off; the no-thinking switch must additionally drop reasoning_effort.
        JsonObject root = body(base(ZEN).thinkingMaxTokens(32768), true);
        Assert.assertFalse(root.has("thinking"));
        Assert.assertFalse("no-thinking must drop reasoning_effort on strict endpoints too",
                root.has("reasoning_effort"));
    }

    @Test
    public void fourArgOverloadKeepsDelegatingWithThinkingEnabled() throws Exception {
        // Regression pin: the 4-arg overload (resolved by the reflection-based
        // body-shape suites) must keep emitting the thinking shape.
        LLMConfig config = base(OLLAMA).thinkingMaxTokens(32768).maxTokens(65536);
        LLMClient client = new LLMClient(config);
        client.resetConversationForTest("sys");
        JsonObject root = JsonParser.parseString(
                        (String) BODY_CONV_4.invoke(client, false, "prompt", null, null))
                .getAsJsonObject();
        Assert.assertTrue("4-arg overload must emit the thinking object", root.has("thinking"));
        Assert.assertEquals("max", root.get("reasoning_effort").getAsString());
        Assert.assertEquals(65536, root.get("max_tokens").getAsInt());
    }

    @Test
    public void noThinkingBodyIsIdenticalToFourArgExceptForThinkingFields() throws Exception {
        // Only thinking/reasoning_effort may differ; everything else (model,
        // max_tokens, temperature, stream, messages) is untouched.
        LLMConfig config = base(OLLAMA).thinkingMaxTokens(32768).maxTokens(65536);
        LLMClient client = new LLMClient(config);
        client.resetConversationForTest("sys");
        JsonObject withThinking = JsonParser.parseString(
                (String) BODY_CONV_5.invoke(client, false, "prompt", null, null, false)).getAsJsonObject();
        JsonObject withoutThinking = JsonParser.parseString(
                (String) BODY_CONV_5.invoke(client, false, "prompt", null, null, true)).getAsJsonObject();
        Assert.assertTrue(withThinking.has("thinking"));
        Assert.assertFalse(withoutThinking.has("thinking"));
        Assert.assertEquals(withThinking.get("max_tokens").getAsInt(),
                withoutThinking.get("max_tokens").getAsInt());
        Assert.assertEquals(withThinking.get("messages").toString(),
                withoutThinking.get("messages").toString());
    }
}
