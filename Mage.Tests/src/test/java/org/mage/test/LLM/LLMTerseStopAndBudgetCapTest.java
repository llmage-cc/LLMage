package org.mage.test.LLM;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.player.llm.LLMClient;
import mage.player.llm.LLMConfig;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;

/**
 * Unit tests for two thinking-budget behaviours:
 *  thinkingMaxTokens — hard reasoning cap in effort mode
 *  stop:["\n"] on validator-checked (terse-answer) conversation calls
 *
 * Both verified against the live ollama-cloud endpoint before
 * wiring: budget_tokens is accepted alongside reasoning_effort, and stop
 * sequences truncate the answer at the sequence.
 */
public class LLMTerseStopAndBudgetCapTest {

    private static final Method BODY_CONV;

    static {
        try {
            BODY_CONV = LLMClient.class.getDeclaredMethod(
                    "buildRequestBodyConv", boolean.class, String.class, String.class, String.class);
            BODY_CONV.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static String convBody(LLMConfig config, String effortOverride, String stop) throws Exception {
        LLMClient client = new LLMClient(config);
        client.resetConversationForTest("sys");
        return (String) BODY_CONV.invoke(client, false, "prompt", effortOverride, stop);
    }

    private static LLMConfig base() {
        return new LLMConfig()
                .apiEndpoint("http://localhost:11434/v1/chat/completions")
                .model("probe")
                .thinkingEnabled(true);
    }

    // #7 — thinkingMaxTokens

    @Test
    public void effortDialectEmitsThinkingBudgetCapWhenSet() throws Exception {
        LLMConfig config = base().reasoningEffort("max").thinkingMaxTokens(32768);
        JsonObject root = JsonParser.parseString(convBody(config, null, null)).getAsJsonObject();
        Assert.assertEquals("reasoning_effort must still be emitted", "max",
                root.get("reasoning_effort").getAsString());
        Assert.assertTrue("thinking object must be present", root.has("thinking"));
        Assert.assertEquals("explicit reasoning cap must appear as budget_tokens inside thinking",
                32768, root.getAsJsonObject("thinking").get("budget_tokens").getAsInt());
    }

    @Test
    public void explicitZeroCapOmitsBudgetTokens() throws Exception {
        LLMConfig config = base().reasoningEffort("max").thinkingMaxTokens(0);
        JsonObject root = JsonParser.parseString(convBody(config, null, null)).getAsJsonObject();
        Assert.assertFalse("0 means no explicit cap — budget_tokens must be absent",
                root.getAsJsonObject("thinking").has("budget_tokens"));
    }

    @Test
    public void budgetDialectWinsOverThinkingMaxTokens() throws Exception {
        // budget dialect already sends thinking.budget_tokens from the ladder —
        // thinkingMaxTokens must not overwrite it.
        LLMConfig config = base().thinkingDialect("budget")
                .thinkingBudgetTokens("20000,10000,5000")
                .thinkingMaxTokens(99999);
        JsonObject root = JsonParser.parseString(convBody(config, null, null)).getAsJsonObject();
        Assert.assertEquals(20000, root.getAsJsonObject("thinking").get("budget_tokens").getAsInt());
    }

    @Test
    public void negativeThinkingMaxRejected() {
        try {
            base().thinkingMaxTokens(-1);
            Assert.fail("expected IllegalArgumentException for negative thinkingMaxTokens");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("-1"));
        }
    }

    // #8 — stop sequence emission

    @Test
    public void stopSequenceEmittedWhenProvided() throws Exception {
        JsonObject root = JsonParser.parseString(convBody(base(), null, "\n")).getAsJsonObject();
        Assert.assertTrue(root.has("stop"));
        Assert.assertEquals(1, root.getAsJsonArray("stop").size());
        Assert.assertEquals("\n", root.getAsJsonArray("stop").get(0).getAsString());
    }

    @Test
    public void stopSequenceOmittedWhenNull() throws Exception {
        JsonObject root = JsonParser.parseString(convBody(base(), null, null)).getAsJsonObject();
        Assert.assertFalse("null stop must not emit a stop field", root.has("stop"));
    }
}
