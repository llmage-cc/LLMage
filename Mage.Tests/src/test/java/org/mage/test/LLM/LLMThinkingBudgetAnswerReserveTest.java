package org.mage.test.LLM;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.player.llm.LLMClient;
import mage.player.llm.LLMConfig;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;

/**
 * Pins the thinking-budget fixes.
 *
 * The served body used to carry
 * NO {@code max_tokens}, so a reasoning blowout returned empty content (620
 * occurrences in one run, ~+45% calls). When a reasoning cap is emitted and
 * max_tokens is not configured, the builder must reserve output room for the
 * ANSWER with {@code max_tokens = cap × 2}.
 *
 * The effort-descent ladder used to leave
 * thinkingMaxTokens on every rung, so the descent never lowered the reasoning
 * allowance. The emitted cap must shrink with the per-attempt effort override.
 *
 * Runs offline: the bodies are built by reflection, no HTTP call is made.
 */
public class LLMThinkingBudgetAnswerReserveTest {

    private static final String OLLAMA = "http://localhost:11434/v1/chat/completions";
    private static final String ZEN = "https://opencode.ai/zen/go/v1/chat/completions";

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

    private static JsonObject body(LLMConfig config, String effortOverride, String stop) throws Exception {
        LLMClient client = new LLMClient(config);
        client.resetConversationForTest("sys");
        return JsonParser.parseString((String) BODY_CONV.invoke(client, false, "prompt", effortOverride, stop))
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

    // ── The answer reserve ──────────────────────────────────────────────

    @Test
    public void effortDialectReservesMaxTokensForTheAnswer() throws Exception {
        // Production shape (start-server.sh): max_tokens unset, cap 32768.
        JsonObject root = body(base(OLLAMA).thinkingMaxTokens(32768), null, null);
        Assert.assertTrue("max_tokens must be present — the answer needs reserved room", root.has("max_tokens"));
        Assert.assertEquals(65536, root.get("max_tokens").getAsInt());
        Assert.assertEquals("max", root.get("reasoning_effort").getAsString());
        Assert.assertEquals(32768,
                root.getAsJsonObject("thinking").get("budget_tokens").getAsInt());
    }

    @Test
    public void configuredMaxTokensStaysAuthoritative() throws Exception {
        JsonObject root = body(base(OLLAMA).thinkingMaxTokens(32768).maxTokens(12000), null, null);
        Assert.assertEquals("an explicitly configured max_tokens must win", 12000,
                root.get("max_tokens").getAsInt());
    }

    @Test
    public void noReasoningCapMeansNoEmittedMaxTokens() throws Exception {
        // thinkingMaxTokens=0 → no reasoning cap → nothing to reserve against;
        // max_tokens stays omitted (host decides), the pre-batch-2 behavior.
        JsonObject root = body(base(OLLAMA).thinkingMaxTokens(0), null, null);
        Assert.assertFalse("no reasoning cap → no synthesized max_tokens", root.has("max_tokens"));
        Assert.assertFalse("no cap → no budget_tokens", root.getAsJsonObject("thinking").has("budget_tokens"));
    }

    @Test
    public void strictOpenAiEndpointGetsNeitherThinkingNorReserve() throws Exception {
        // The reserve rides WITH the reasoning cap; a strict OpenAI-compatible
        // endpoint receives no thinking object, so it must not receive a
        // synthesized max_tokens either — reasoning_effort stands alone.
        JsonObject root = body(base(ZEN).thinkingMaxTokens(32768), null, null);
        Assert.assertFalse(root.has("thinking"));
        Assert.assertFalse("no reasoning cap emitted here → no answer reserve", root.has("max_tokens"));
        Assert.assertEquals("max", root.get("reasoning_effort").getAsString());
    }

    // ── The cap descends with the effort rung ───────────────────────────

    @Test
    public void descendedEffortShrinksTheReasoningCap() throws Exception {
        // Ladder max,high,medium,low (highest first), configured effort "max"
        // (rung 0, the attempt-1 rung). Descent below the configured rung:
        //   high   (descended 1) → 32768 × (3−1)/3 = 21845
        //   medium (descended 2) → 32768 × (3−2)/3 = 10922
        //   low    (descended 3) → 0               → floored to 1024
        LLMConfig config = base(OLLAMA).thinkingMaxTokens(32768);
        Assert.assertEquals("attempt 1 (configured max) keeps the full cap", 32768,
                body(config, null, null).getAsJsonObject("thinking").get("budget_tokens").getAsInt());
        Assert.assertEquals("rung 1 (high)", 21845,
                body(config, "high", null).getAsJsonObject("thinking").get("budget_tokens").getAsInt());
        Assert.assertEquals("rung 2 (medium)", 10922,
                body(config, "medium", null).getAsJsonObject("thinking").get("budget_tokens").getAsInt());
        Assert.assertEquals("lowest rung floors at the reasoning floor", 1024,
                body(config, "low", null).getAsJsonObject("thinking").get("budget_tokens").getAsInt());
        // The reserve tracks the DESCENDED cap, so it can never fall below the answer.
        Assert.assertEquals(2048, body(config, "low", null).get("max_tokens").getAsInt());
    }

    @Test
    public void descentKeepsCapAtOrAboveTheFloor() throws Exception {
        // A small configured cap must not scale below the 1024 reasoning floor.
        JsonObject root = body(base(OLLAMA).thinkingMaxTokens(2048), "medium", null);
        Assert.assertEquals(1024, root.getAsJsonObject("thinking").get("budget_tokens").getAsInt());
    }

    @Test
    public void effortNotInLadderKeepsFullCap() throws Exception {
        // Custom per-model effort with no descent ordering — no guessing, full cap.
        JsonObject root = body(base(OLLAMA).thinkingMaxTokens(32768).reasoningEffort("high"), "xhigh", null);
        Assert.assertEquals(32768, root.getAsJsonObject("thinking").get("budget_tokens").getAsInt());
    }

    @Test
    public void budgetDialectOverrideStillSetsCapAndReserve() throws Exception {
        LLMConfig config = new LLMConfig()
                .apiEndpoint(OLLAMA).model("probe-model").thinkingEnabled(true)
                .thinkingDialect("budget").thinkingBudgetTokens("16000,8000,4000,1000");
        JsonObject root = body(config, "8000", null);
        Assert.assertEquals(8000, root.getAsJsonObject("thinking").get("budget_tokens").getAsInt());
        Assert.assertEquals("answer reserve tracks the descended budget", 16000,
                root.get("max_tokens").getAsInt());
        Assert.assertFalse("budget dialect must not send reasoning_effort", root.has("reasoning_effort"));
    }
}
