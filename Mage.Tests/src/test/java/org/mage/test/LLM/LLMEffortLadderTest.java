package org.mage.test.LLM;

import mage.constants.RangeOfInfluence;
import mage.player.llm.LLMClient;
import mage.player.llm.LLMConfig;
import mage.player.llm.LLMResponse;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for the empty-answer reasoning-effort descent ladder in
 * LLMPlayer.chatAndRecordWithRetry (the empty-answer reasoning-loop
 * death spiral).
 *
 * The ladder: attempt 1 = configured effort, attempt 2 = configured effort +
 * nudge, attempts 3+ = descend config.reasoningEffortLevels one rung per
 * attempt (never above configured), exhaustion = throw. No network calls are
 * made by the test itself — a stubbed LLMClient scripts the responses.
 *
 * Construction of TestLLMPlayer runs fetchModelContextWindow() against local
 * Ollama (same requirement as every LLM test); the skip gate covers machines
 * without it.
 */
public class LLMEffortLadderTest {

    @BeforeClass
    public static void checkLLMAvailable() {
        // Stub-only: no live model call happens. Construction no longer probes the
        // endpoint either — LLMPlayer.init() gates context-window detection on
        // isTestHarness() — so the old Ollama-reachability gate is gone.
        Assume.assumeFalse("LLM tests skipped (set -Dxmage.llm.skip=false to run)",
                Boolean.getBoolean("xmage.llm.skip"));
    }

    /** Stub that scripts LLMResponses and records the effort override per call. */
    static class StubClient extends LLMClient {
        final List<String> effortsSeen = new ArrayList<>();
        /** Call indices (1-based) that arrived through chatConversationNoThinking. */
        final List<Integer> noThinkingCalls = new ArrayList<>();
        int emptyAnswersBeforeSuccess = -1;  // -1 = empty forever
        RuntimeException infraFailure = null;

        StubClient(LLMConfig config) {
            super(config);
        }

        @Override
        public LLMResponse chatConversation(String fullPrompt, String compactSummary,
                                             String promptType, Integer turn, String phaseStep,
                                             String effortOverride) {
            effortsSeen.add(effortOverride);
            int callNumber = effortsSeen.size();
            if (infraFailure != null) throw infraFailure;
            if (emptyAnswersBeforeSuccess < 0 || callNumber <= emptyAnswersBeforeSuccess) {
                // Reasoning loop: thinking present, no answer. finish_reason=length
                // is the REAL shape of this loop (token budget exhausted while still
                // reasoning) — with a null/absent finish_reason and non-blank
                // reasoning the bleed recovery would (correctly) promote the text,
                // which is not what this ladder test models.
                return new LLMResponse("We should pass. Action 0. But maybe skip to end step? "
                        + "We have no instants. So pass. Action 0. But maybe...", "",
                        null, null, "length");
            }
            return new LLMResponse("decided", "0");
        }

        @Override
        public LLMResponse chatConversationNoThinking(String fullPrompt, String compactSummary,
                                                      String promptType, Integer turn, String phaseStep,
                                                      String effortOverride) {
            effortsSeen.add(effortOverride);
            noThinkingCalls.add(effortsSeen.size());
            int callNumber = effortsSeen.size();
            if (infraFailure != null) throw infraFailure;
            if (emptyAnswersBeforeSuccess < 0 || callNumber <= emptyAnswersBeforeSuccess) {
                // Even with thinking off this scripted stub returns empty.
                return new LLMResponse("still looping, no answer", "", null, null, "length");
            }
            return new LLMResponse("decided", "0");
        }
    }

    private TestLLMPlayer newPlayer() {
        return new TestLLMPlayer("LadderTestBot", RangeOfInfluence.ONE, 0);
    }

    private void inject(TestLLMPlayer player, StubClient stub) {
        player.injectTestClient(stub);
    }

    /** Normal recovery on attempt 1 — no override, single call. */
    @Test
    public void testFirstAttemptSuccessUsesConfiguredEffort() {
        TestLLMPlayer player = newPlayer();
        StubClient stub = new StubClient(player.getLLMConfig());
        stub.emptyAnswersBeforeSuccess = 0;
        inject(player, stub);

        LLMResponse resp = player.chatAndRecordWithRetryTest("prompt", "summary", "priority", 1, "T1.M1");
        Assert.assertEquals("0", resp.answer);
        Assert.assertEquals(Arrays.asList((String) null), stub.effortsSeen);
    }

    /** Nudge-first ladder: attempts 1-2 at configured effort, attempt 3 descends one rung. */
    @Test
    public void testNudgeThenEffortDescent() {
        TestLLMPlayer player = newPlayer();
        StubClient stub = new StubClient(player.getLLMConfig());
        stub.emptyAnswersBeforeSuccess = 2;  // empty on attempts 1-2, answer on 3
        inject(player, stub);

        LLMResponse resp = player.chatAndRecordWithRetryTest("prompt", "summary", "priority", 1, "T1.M1");
        Assert.assertEquals("0", resp.answer);
        Assert.assertEquals("configured effort (max) → nudge (max) → descent (high)",
                Arrays.asList(null, null, "high"), stub.effortsSeen);
    }

    /** Loop survives the full descent: max, max, high, medium, low, then fatal error. */
    @Test
    public void testExhaustedDescentThrows() {
        TestLLMPlayer player = newPlayer();
        StubClient stub = new StubClient(player.getLLMConfig());
        stub.emptyAnswersBeforeSuccess = -1;  // never answers
        inject(player, stub);

        try {
            player.chatAndRecordWithRetryTest("prompt", "summary", "priority", 1, "T1.M1");
            Assert.fail("expected RuntimeException after exhausting the effort ladder");
        } catch (RuntimeException e) {
            Assert.assertTrue("expected descent-survived message, got: " + e.getMessage(),
                    e.getMessage().contains("survived the full reasoning-effort descent"));
        }
        Assert.assertEquals(Arrays.asList(null, null, "high", "medium", "low"), stub.effortsSeen);
    }

    /** Descent starts below the CONFIGURED effort, not the ladder top. */
    @Test
    public void testDescentStartsBelowConfiguredEffort() {
        TestLLMPlayer player = newPlayer();
        player.getLLMConfig().reasoningEffort("high");
        StubClient stub = new StubClient(player.getLLMConfig());
        stub.emptyAnswersBeforeSuccess = -1;
        inject(player, stub);

        try {
            player.chatAndRecordWithRetryTest("prompt", "summary", "priority", 1, "T1.M1");
            Assert.fail("expected RuntimeException");
        } catch (RuntimeException e) {
            Assert.assertTrue(e.getMessage().contains("survived the full reasoning-effort descent"));
        }
        // configured=high → descent: medium, low, low (clamped)
        Assert.assertEquals(Arrays.asList(null, null, "medium", "low", "low"), stub.effortsSeen);
    }

    /** Infrastructure errors (not empty answers) must NOT trigger the descent. */
    @Test
    public void testInfrastructureErrorsDoNotDescend() {
        TestLLMPlayer player = newPlayer();
        StubClient stub = new StubClient(player.getLLMConfig());
        stub.infraFailure = new RuntimeException("connection refused");
        inject(player, stub);

        try {
            player.chatAndRecordWithRetryTest("prompt", "summary", "priority", 1, "T1.M1");
            Assert.fail("expected RuntimeException");
        } catch (RuntimeException e) {
            Assert.assertTrue("expected generic retry-exhausted message, got: " + e.getMessage(),
                    e.getMessage().contains("LLM call failed after"));
        }
        Assert.assertEquals(Arrays.asList(null, null, null, null, null), stub.effortsSeen);
    }

    /**
     * Final empty-answer rung: attempt 5 (LLM_MAX_RETRIES) must go through the
     * thinking-disabled path, then exhaustion throws. Attempts 1-4 stay on the
     * thinking-enabled descent call.
     */
    @Test
    public void testFinalAttemptUsesNoThinkingPathBeforeExhaustion() {
        TestLLMPlayer player = newPlayer();
        StubClient stub = new StubClient(player.getLLMConfig());
        stub.emptyAnswersBeforeSuccess = -1;  // never answers
        inject(player, stub);

        try {
            player.chatAndRecordWithRetryTest("prompt", "summary", "priority", 1, "T1.M1");
            Assert.fail("expected RuntimeException after the thinking-disabled final attempt");
        } catch (RuntimeException e) {
            Assert.assertTrue("expected descent-survived message, got: " + e.getMessage(),
                    e.getMessage().contains("survived the full reasoning-effort descent"));
            Assert.assertTrue("message must name the thinking-disabled final attempt, got: " + e.getMessage(),
                    e.getMessage().contains("thinking-disabled final attempt"));
        }
        // Attempts 1-4 use chatConversation (thinking on) with the normal
        // descent effort sequence; exactly attempt 5 switches to no-thinking.
        Assert.assertEquals("only the FINAL attempt is thinking-disabled",
                Arrays.asList(5), stub.noThinkingCalls);
        Assert.assertEquals("all 5 attempts were made",
                5, stub.effortsSeen.size());
        Assert.assertEquals(Arrays.asList(null, null, "high", "medium", "low"), stub.effortsSeen);
    }

    /**
     * A no-thinking final attempt that SUCCEEDS returns the answer (no throw);
     * everything up to and including attempt 5 is empty until attempt 5 itself.
     */
    @Test
    public void testNoThinkingFinalAttemptCanRecover() {
        TestLLMPlayer player = newPlayer();
        StubClient stub = new StubClient(player.getLLMConfig());
        stub.emptyAnswersBeforeSuccess = 4;  // empty on 1-4, answer on 5
        inject(player, stub);

        LLMResponse resp = player.chatAndRecordWithRetryTest("prompt", "summary", "priority", 1, "T1.M1");
        Assert.assertEquals("0", resp.answer);
        Assert.assertEquals("the recovering call must be the no-thinking final attempt",
                Arrays.asList(5), stub.noThinkingCalls);
        Assert.assertEquals(Arrays.asList(null, null, "high", "medium", "low"), stub.effortsSeen);
    }

    /**
     * A pure infrastructure failure never routes through the thinking-disabled
     * path: no attempt was an empty answer, so the final-attempt gate stays off
     * and the exhaustion message is the generic one, not the descent message.
     */
    @Test
    public void testInfrastructureFailureNeverUsesNoThinkingPath() {
        TestLLMPlayer player = newPlayer();
        StubClient stub = new StubClient(player.getLLMConfig());
        stub.infraFailure = new RuntimeException("connection reset");
        inject(player, stub);

        try {
            player.chatAndRecordWithRetryTest("prompt", "summary", "priority", 1, "T1.M1");
            Assert.fail("expected RuntimeException");
        } catch (RuntimeException e) {
            Assert.assertTrue("expected generic retry-exhausted message, got: " + e.getMessage(),
                    e.getMessage().contains("LLM call failed after"));
            Assert.assertFalse("must not report the empty-answer descent, got: " + e.getMessage(),
                    e.getMessage().contains("reasoning-effort descent"));
        }
        Assert.assertTrue("an infra failure must not be routed through the no-thinking path",
                stub.noThinkingCalls.isEmpty());
    }
}