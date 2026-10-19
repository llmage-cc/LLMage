package org.mage.test.LLM;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.player.llm.LLMClient;
import mage.player.llm.LLMConfig;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;

/**
 * Pins the thinking-object gating: the non-standard {@code thinking:{...}}
 * object is an Ollama-family extension and is emitted
 * ONLY for Ollama-classified endpoints or the explicit "budget" dialect.
 *
 * Regression basis: an LLM seat on
 * opencode.ai/zen/go with model glm-5.3-flash got HTTP 400
 * {@code invalid request body: json: unknown field "thinking"} on the
 * pre-game assessment call; the non-retryable error escaped GameImpl.init
 * and the [FATAL] GameWorker crash left the table stuck at T1. The same body
 * was accepted by glm-5.3 / kimi-k2.6 / qwen3.8-max / space-bunny-free —
 * per-model strictness behind one gateway, so the engine must not emit the
 * object to non-Ollama endpoints at all.
 *
 * The existing dialect tests (LLMThinkingDialectTest, LLMTerseStopAndBudgetCapTest)
 * use localhost:11434 and therefore keep pinning the OLLAMA side of this
 * split unchanged (object present, budget_tokens cap present).
 */
public class LLMThinkingObjectGatingTest {

    private static final String OLLAMA = "http://localhost:11434/v1/chat/completions";
    private static final String CHARM = "https://hyper.charm.land/v1/chat/completions";
    private static final String ZEN = "https://opencode.ai/zen/go/v1/chat/completions";

    private static final Method BODY_CONV;
    private static final Method BODY_NO_THINKING;
    private static final Method BODY_PLAIN;

    static {
        try {
            BODY_CONV = LLMClient.class.getDeclaredMethod(
                    "buildRequestBodyConv", boolean.class, String.class, String.class, String.class);
            BODY_CONV.setAccessible(true);
            BODY_NO_THINKING = LLMClient.class.getDeclaredMethod(
                    "buildRequestBodyNoThinking", String.class, String.class, boolean.class);
            BODY_NO_THINKING.setAccessible(true);
            BODY_PLAIN = LLMClient.class.getDeclaredMethod(
                    "buildRequestBody", String.class, String.class, boolean.class);
            BODY_PLAIN.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static LLMConfig base(String endpoint) {
        return new LLMConfig()
                .apiEndpoint(endpoint)
                .model("probe-model")
                .thinkingEnabled(true);
    }

    private static JsonObject conv(LLMConfig config) throws Exception {
        LLMClient client = new LLMClient(config);
        client.resetConversationForTest("sys");
        return JsonParser.parseString(
                (String) BODY_CONV.invoke(client, false, "prompt", null, null)).getAsJsonObject();
    }

    private static JsonObject noThinking(LLMConfig config) throws Exception {
        LLMClient client = new LLMClient(config);
        return JsonParser.parseString(
                (String) BODY_NO_THINKING.invoke(client, "sys", "prompt", false)).getAsJsonObject();
    }

    private static JsonObject plain(LLMConfig config) throws Exception {
        LLMClient client = new LLMClient(config);
        return JsonParser.parseString(
                (String) BODY_PLAIN.invoke(client, "sys", "prompt", false)).getAsJsonObject();
    }

    // ── effort dialect, OpenAI-compatible (non-Ollama) endpoint ──

    @Test
    public void effortDialectOmitsThinkingObjectOnOpenAiCompatibleEndpoint() throws Exception {
        JsonObject root = conv(base(ZEN).reasoningEffort("max").thinkingMaxTokens(32768));
        Assert.assertFalse("the thinking object must NOT be sent to a strict OpenAI-compatible endpoint",
                root.has("thinking"));
        Assert.assertEquals("standard reasoning_effort must still be sent", "max",
                root.get("reasoning_effort").getAsString());
    }

    @Test
    public void effortDialectCharmOmitsThinkingObject() throws Exception {
        // charm is the dev-rig default provider and classified non-Ollama:
        // it accepts bare reasoning_effort (probed live) and never
        // honored budget_tokens anyway, so omitting the object loses nothing.
        JsonObject root = conv(base(CHARM).reasoningEffort("high").thinkingMaxTokens(32768));
        Assert.assertFalse("charm must not receive the thinking object", root.has("thinking"));
        Assert.assertEquals("high", root.get("reasoning_effort").getAsString());
    }

    @Test
    public void thinkingMaxTokensCapIsOllamaOnly() throws Exception {
        // The #7 hard cap rides inside the thinking object — on a non-Ollama
        // endpoint an object-level budget_tokens is not expressible, and the
        // old unconditional emission was exactly what 400'd glm-5.3-flash.
        JsonObject nonOllama = conv(base(ZEN).thinkingMaxTokens(32768));
        Assert.assertFalse("no thinking object on non-Ollama endpoint", nonOllama.has("thinking"));

        JsonObject ollama = conv(base(OLLAMA).thinkingMaxTokens(32768));
        Assert.assertTrue("thinking object must be present on Ollama endpoint", ollama.has("thinking"));
        Assert.assertEquals(32768,
                ollama.getAsJsonObject("thinking").get("budget_tokens").getAsInt());
    }

    @Test
    public void ollamaEndpointKeepsFullEffortShape() throws Exception {
        JsonObject root = conv(base(OLLAMA).reasoningEffort("max").thinkingMaxTokens(32768));
        Assert.assertTrue("Ollama-classified endpoints keep the thinking object", root.has("thinking"));
        Assert.assertEquals("max", root.get("reasoning_effort").getAsString());
        Assert.assertEquals(32768,
                root.getAsJsonObject("thinking").get("budget_tokens").getAsInt());
    }

    // ── budget dialect is object-based by definition ──

    @Test
    public void budgetDialectEmitsObjectEvenOnOpenAiCompatibleEndpoint() throws Exception {
        // No silent translation: the configured dialect must be emitted as-is
        // on any endpoint; an endpoint that rejects it fails fast on the API
        // error (project policy), it does not get a different dialect.
        JsonObject root = conv(base(ZEN).thinkingDialect("budget").thinkingBudgetTokens("20000,10000"));
        Assert.assertTrue("budget dialect must send the thinking object", root.has("thinking"));
        Assert.assertEquals(20000,
                root.getAsJsonObject("thinking").get("budget_tokens").getAsInt());
        Assert.assertFalse("budget dialect must not send reasoning_effort", root.has("reasoning_effort"));
    }

    // ── summarization one-shot ──

    @Test
    public void summarizerOmitsDisabledObjectOnOpenAiCompatibleEndpoint() throws Exception {
        JsonObject root = noThinking(base(ZEN).thinkingMaxTokens(0));
        Assert.assertFalse("summarizer must not send thinking{disabled} to a strict endpoint",
                root.has("thinking"));
    }

    @Test
    public void summarizerKeepsDisabledObjectOnOllamaEndpoint() throws Exception {
        JsonObject root = noThinking(base(OLLAMA).thinkingMaxTokens(0));
        Assert.assertTrue("Ollama endpoints keep thinking{disabled}", root.has("thinking"));
        Assert.assertEquals("disabled",
                root.getAsJsonObject("thinking").get("type").getAsString());
    }

    // ── startup probe path (buildRequestBody) ──

    @Test
    public void startupProbePathOmitsObjectOnOpenAiCompatibleEndpoint() throws Exception {
        JsonObject root = plain(base(ZEN).reasoningEffort("high").thinkingMaxTokens(32768));
        Assert.assertFalse("probe must not send the thinking object to a strict endpoint",
                root.has("thinking"));
        Assert.assertEquals("high", root.get("reasoning_effort").getAsString());
    }
}
