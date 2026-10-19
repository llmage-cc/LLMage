package org.mage.test.LLM;

import mage.interfaces.LLMBridgeRegistry;
import mage.interfaces.LLMPromptPayload;
import mage.player.llm.LLMConfig;
import mage.player.llm.LLMPlayer;
import mage.constants.RangeOfInfluence;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Terminal-failure endgame pins:
 *
 * Typed classification through the retry classifier:
 *   - sendViaBridge no longer flattens BrowserCallException into a generic
 *     IOException, so isRetryable can honor the browser's structured
 *     retryable flag / httpStatus. A dead/revoked key fails on attempt 1
 *     instead of grinding the 5-attempt ladder (~30 s).
 * thrownAfterFullLadder classification (the per-callback guard gate):
 *   - ladder-exhausted signature messages are terminal
 *   - non-retryable HTTP statuses are terminal
 *   - transient failures are NOT terminal
 * Sideboard terminal failure ⇒ quitMatch (pinned by the message-signature
 *   gate; the full match-lifecycle behavior is an E2E, not a unit pin).
 */
public class LLMTerminalFailureEndgameTest {

    @After
    public void clearTransport() {
        LLMBridgeRegistry.clearTransport();
    }

    /** Empty config minus network: endpoint never dialed by these pins (no init()). */
    private static LLMConfig base() {
        return new LLMConfig()
                .apiEndpoint("http://localhost:11434/v1/chat/completions")
                .model("probe-model")
                .thinkingEnabled(false);
    }

    private static final class ProbePlayer extends mage.player.llm.LLMPlayer {
        ProbePlayer(LLMConfig config) {
            super("probe", RangeOfInfluence.ONE, 0);
            setLLMConfig(config);  // replaces construction-time config without init()'s probe/net
        }
        @Override
        protected boolean isTestHarness() { return true; }

        boolean retryable(Throwable t) { return isRetryable(t); }
        boolean terminalLadder(Throwable t) { return thrownAfterFullLadder(t); }
        boolean terminalNonRetryable(Throwable t) { return isTerminalNonRetryable(t); }
    }

    private static LLMBridgeRegistry.BrowserCallException bce(boolean retryable, int status, String msg) {
        LLMBridgeRegistry.fail(new LLMPromptPayload.Failure(UUID.randomUUID(), msg, retryable, status));
        // fail() routes through PENDING which has no such id — construct directly instead.
        return new LLMBridgeRegistry.BrowserCallException(
                new LLMPromptPayload.Failure(UUID.randomUUID(), msg, retryable, status));
    }

    // ------------------------------------------------------------------
    // isRetryable cause-chain classification
    // ------------------------------------------------------------------

    @Test
    public void browserCallExceptionRetryableFlagIsHonoredDirectly() {
        ProbePlayer p = new ProbePlayer(base());
        // Browser reported non-retryable (e.g. 401 dead key) — terminal NOW.
        Assert.assertFalse("retryable:false must classify non-retryable",
                p.retryable(bce(false, 401, "Unauthorized")));
        // Browser reported retryable (429/5xx) — ladder owns it.
        Assert.assertTrue(p.retryable(bce(true, 429, "Rate limited")));
        Assert.assertTrue(p.retryable(bce(true, 503, "Overloaded")));
    }

    @Test
    public void browserCallExceptionIsHonoredThroughCauseChain() {
        ProbePlayer p = new ProbePlayer(base());
        // The pre-fix failure shape: BrowserCallException wrapped by layers of
        // IOException/RuntimeException — the flag must still be reachable.
        Throwable wrapped = new IOException("outer transport failure",
                new RuntimeException("mid", bce(false, 400, "unknown field thinking")));
        Assert.assertFalse("wrapped retryable:false must classify terminal",
                p.retryable(wrapped));
    }

    @Test
    public void httpStatusClassificationUnchangedThroughChain() {
        ProbePlayer p = new ProbePlayer(base());
        // 429 stays retryable (existing rule, now chain-aware).
        mage.player.llm.HttpStatusException hse429 = new mage.player.llm.HttpStatusException(
                429, "", null);
        Assert.assertTrue(p.retryable(new IOException("wrap", hse429)));
        // 401 is terminal (existing rule, now reachable through wrapping too).
        mage.player.llm.HttpStatusException hse401 = new mage.player.llm.HttpStatusException(
                401, "missing authorization", null);
        Assert.assertFalse("401 through a wrapper must classify terminal",
                p.retryable(new RuntimeException("LLM API call failed: ...", hse401())));
        Assert.assertFalse(p.retryable(hse401));
    }

    private static Throwable hse401() {
        return new mage.player.llm.HttpStatusException(401, "missing authorization", null);
    }

    @Test
    public void genericFailuresStayRetryable() {
        ProbePlayer p = new ProbePlayer(base());
        // Empty-answer RuntimeException and plain IOException (network drop) retry.
        Assert.assertTrue(p.retryable(new RuntimeException("LLM returned empty answer (attempt 1)")));
        Assert.assertTrue(p.retryable(new IOException("connection reset")));
    }

    // ------------------------------------------------------------------
    // thrownAfterFullLadder — the per-callback guard gate
    // ------------------------------------------------------------------

    @Test
    public void ladderExhaustedSignatureIsTerminal() {
        ProbePlayer p = new ProbePlayer(base());
        // chatAndRecordWithRetry's exhausted-ladder wrapper messages.
        Assert.assertTrue("exhausted ladder must read terminal",
                p.terminalLadder(new RuntimeException(
                        "LLM call failed after 5 attempts. Last error: 401")));
        Assert.assertTrue("empty-answer descent exhaustion must read terminal",
                p.terminalLadder(new RuntimeException(
                        "LLM empty-answer loop survived the full reasoning-effort descent "
                                + "down to 'min'. 5 attempts.")));
        // And through the wrapper LLMClient.chat adds:
        Assert.assertTrue(p.terminalLadder(new RuntimeException(
                "LLM API call failed: LLM call failed after 5 attempts. Last error: 400",
                new RuntimeException("inner"))));
    }

    @Test
    public void nonRetryableStatusIsTerminalEvenWithoutLadderSignature() {
        ProbePlayer p = new ProbePlayer(base());
        Assert.assertTrue(p.terminalNonRetryable(bce(false, 401, "Unauthorized")));
        Assert.assertTrue("non-retryable anywhere in the chain is terminal",
                p.terminalLadder(new IOException("wrap", bce(false, 400, "bad request"))));
    }

    @Test
    public void transientFailuresAreNotTerminalForGuards() {
        ProbePlayer p = new ProbePlayer(base());
        // A rate-limit failure that surfaces mid-priority is the ladder's job,
        // not the concede guard's — the game continues.
        Assert.assertFalse(p.terminalLadder(bce(true, 429, "Rate limited")));
        Assert.assertFalse(p.terminalLadder(new IOException("connection reset")));
        Assert.assertFalse(p.terminalLadder(new RuntimeException("LLM returned empty answer (attempt 1)")));
    }
}