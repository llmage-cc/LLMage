package org.mage.test.LLM;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.player.llm.HttpStatusException;
import mage.player.llm.LLMClient;
import mage.player.llm.LLMConfig;
import mage.player.llm.LLMPlayer;
import mage.player.llm.LLMResponse;
import org.junit.Assert;
import org.junit.Test;
import mage.constants.RangeOfInfluence;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Unit tests for the thinking-dialect config + budget ladder,
 * the format-correction effort descent, and the retry backoff.
 *
 *  - dialect "effort"  → request carries reasoning_effort, thinking has no budget_tokens
 *  - dialect "budget"  → request carries thinking.budget_tokens, no reasoning_effort
 *  - budget ladder     → empty-answer retries descend thinkingBudgetTokens
 *  - HttpStatusException: 429/5xx retryable; 400/401/404 terminal; Retry-After parsed
 *  - retryDelayMs      → exponential 2s/4s/8s/16s, capped 32s, honors Retry-After
 */
public class LLMThinkingDialectTest {

    private static String requestBody(LLMConfig config, String effortOverride) throws Exception {
        LLMClient client = new LLMClient(config);
        client.resetConversationForTest("sys");
        Method m = LLMClient.class.getDeclaredMethod(
                "buildRequestBodyConv", boolean.class, String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(client, false, "test prompt", effortOverride);
    }

    private static LLMConfig base() {
        return new LLMConfig()
                .apiEndpoint("http://localhost:11434/v1/chat/completions")
                .model("probe-model")
                .thinkingEnabled(true)
                .thinkingMaxTokens(0);   // disable the explicit cap in this probe — the test
                                         // asserts dialect payload shape, not the cap default
    }

    @Test
    public void effortDialectEmitsReasoningEffort() throws Exception {
        LLMConfig config = base().reasoningEffort("high");
        String body = requestBody(config, null);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        Assert.assertEquals("high", root.get("reasoning_effort").getAsString());
        Assert.assertFalse("effort dialect with thinkingMaxTokens=0 must not send budget_tokens",
                root.getAsJsonObject("thinking").has("budget_tokens"));
    }

    @Test
    public void budgetDialectEmitsBudgetTokens() throws Exception {
        LLMConfig config = base().thinkingDialect("budget").thinkingBudgetTokens("20000,10000,5000");
        String body = requestBody(config, null);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        Assert.assertFalse("budget dialect must not send reasoning_effort", root.has("reasoning_effort"));
        Assert.assertEquals(20000, root.getAsJsonObject("thinking").get("budget_tokens").getAsInt());
    }

    @Test
    public void budgetDialectDescendsOnOverride() throws Exception {
        LLMConfig config = base().thinkingDialect("budget").thinkingBudgetTokens("20000,10000,5000");
        String body = requestBody(config, "10000");   // ladder rung for attempt 3
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        Assert.assertEquals(10000, root.getAsJsonObject("thinking").get("budget_tokens").getAsInt());
    }

    @Test
    public void invalidDialectRejected() {
        try {
            base().thinkingDialect("turbo");
            Assert.fail("expected IllegalArgumentException for unknown dialect");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("turbo"));
        }
    }

    @Test
    public void invalidBudgetRejected() {
        try {
            base().thinkingBudgetTokens("16000,banana");
            Assert.fail("expected IllegalArgumentException for non-integer budget");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("banana"));
        }
    }

    // --- HttpStatusException / backoff -------------------------------------

    @Test
    public void httpStatusClassifiesRetryable() {
        Assert.assertTrue(new HttpStatusException(429, "", null).isRetryableWithBackoff());
        Assert.assertTrue(new HttpStatusException(503, "", null).isRetryableWithBackoff());
        Assert.assertFalse(new HttpStatusException(400, "", null).isRetryableWithBackoff());
        Assert.assertFalse(new HttpStatusException(401, "", null).isRetryableWithBackoff());
        Assert.assertFalse(new HttpStatusException(404, "", null).isRetryableWithBackoff());
    }

    @Test
    public void retryAfterParsedSeconds() {
        Assert.assertEquals(7, new HttpStatusException(429, "", "7").retryAfterSeconds);
        Assert.assertEquals(-1, new HttpStatusException(429, "", null).retryAfterSeconds);
        Assert.assertEquals(-1, new HttpStatusException(429, "", "Mon, 01 Jan 2024").retryAfterSeconds);
    }

    @Test
    public void retryDelayIsExponentialAndHonorsRetryAfter() throws Exception {
        Method m = LLMPlayer.class.getDeclaredMethod("retryDelayMs", int.class, Throwable.class);
        m.setAccessible(true);
        HttpStatusException rateLimited = new HttpStatusException(429, "", null);
        HttpStatusException withHint = new HttpStatusException(429, "", "30");
        Assert.assertEquals(2000L, m.invoke(null, 1, rateLimited));
        Assert.assertEquals(4000L, m.invoke(null, 2, rateLimited));
        Assert.assertEquals(8000L, m.invoke(null, 3, rateLimited));
        Assert.assertEquals(16000L, m.invoke(null, 4, rateLimited));
        Assert.assertEquals(30000L, m.invoke(null, 1, withHint));
        // Non-HTTP failures (empty answers, IOExceptions) also get exp backoff
        Assert.assertEquals(4000L, m.invoke(null, 2, new RuntimeException("empty answer")));
    }

    // --- Budget ladder in LLMPlayer.effortForAttempt ------------------------

    private static final class ProbePlayer extends LLMPlayer {
        ProbePlayer(LLMConfig config) {
            super("probe", RangeOfInfluence.ONE, 0);
            setLLMConfig(config);  // replace construction-time config without init()'s probe/net
        }
        @Override
        protected boolean isTestHarness() { return true; }
        String effortAt(int attempt) throws Exception {
            Method m = LLMPlayer.class.getDeclaredMethod("effortForAttempt", int.class);
            m.setAccessible(true);
            return (String) m.invoke(this, attempt);
        }
    }

    @Test
    public void budgetLadderDescendsPerAttempt() throws Exception {
        LLMConfig config = base().thinkingDialect("budget").thinkingBudgetTokens("20000,10000,5000");
        ProbePlayer p = new ProbePlayer(config);
        Assert.assertNull(p.effortAt(1));
        Assert.assertNull(p.effortAt(2));
        // attempt 3 → second rung (index min(3-2, size-1) = 1)
        Assert.assertEquals("10000", p.effortAt(3));
        // attempt 4→2, attempt 5→clamped at 2
        Assert.assertEquals("5000", p.effortAt(4));
        Assert.assertEquals("5000", p.effortAt(5));
    }

    @Test
    public void effortLadderUnchangedInEffortDialect() throws Exception {
        LLMConfig config = base().reasoningEffort("high")
                .reasoningEffortLevels(java.util.Arrays.asList("max", "high", "medium", "low"));
        ProbePlayer p = new ProbePlayer(config);
        Assert.assertNull(p.effortAt(2));
        Assert.assertEquals("medium", p.effortAt(3));  // one rung below "high"
        Assert.assertEquals("low", p.effortAt(4));
        Assert.assertEquals("low", p.effortAt(5));     // clamped
    }
}
