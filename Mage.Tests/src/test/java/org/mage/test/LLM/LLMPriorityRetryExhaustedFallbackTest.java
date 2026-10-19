package org.mage.test.LLM;

import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.game.Game;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;
import org.mage.test.player.TestPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * The priority() action-retry loop must DISCRIMINATE and
 * must NOT kill the game when a single decision is unrecoverable.
 *
 * Pre-fix behavior: {@code LLMPlayer.priority} retried {@code promptAndExecute}
 * THREE times, each time feeding the model the SAME rebuilt action list plus a
 * generic "YOUR LAST ACTION FAILED" notice. A deterministic model re-picked the
 * identical engine-rejected action every attempt; the loop then threw
 * {@code RuntimeException("LLM priority: all 3 action retries exhausted …")}.
 * That escaped into {@code GameImpl.playPriority}'s inner catch, and after the
 * 16th such error the engine raised "Too many errors, game will be end" — one
 * unrecoverable decision ended a whole game/match (observed in a server
 * log: 3x FATAL "Game error: … T9.M1; active: glm-5.3-flash-casey" on a
 * mana-plan / 'YOUR LAST ACTION FAILED' repeat).
 *
 * Post-fix behavior pinned here (OFFLINE — the LLM call is stubbed out; no game
 * server and no network):
 *  a. the FINAL retry calls {@code promptAndExecute(game, finalAttempt=true)} —
 *     once per retry burst — so the caller can prepend the accumulated
 *     engine-rejection detail and make the last attempt carry something NEW;
 *  b. when all 3 retries are rejected, priority() reports the stuck state LOUDLY
 *     (FATAL log + self-log) and PASSES priority (the legal fallback) instead of
 *     throwing, so the game/match survives.
 *
 * The LLM seat's {@code promptAndExecute} is overridden to report "rejected"
 * without any network I/O, so the whole flow is deterministic.
 *
 * Not run by default (compile-only constraint). Run with: mvn test -pl Mage.Tests -Dtest=LLMPriorityRetryExhaustedFallbackTest
 */
public class LLMPriorityRetryExhaustedFallbackTest extends CardTestPlayerBaseLLM {

    /** Signals between the test body and the player subclass below. */
    static final List<Boolean> finalAttemptFlags = new ArrayList<>();
    static boolean rejectEverything = true;

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence range) {
        if (name.equals("PlayerA")) {
            TestLLMPlayer llmPlayer = new TestLLMPlayer(name, range, 0) {
                /**
                 * Simulate the stuck model: every call reports "action rejected"
                 * (returns false) without any network I/O, and records whether the
                 * caller flagged it as the discriminating final attempt.
                 */
                @Override
                protected boolean promptAndExecute(Game game, boolean finalAttempt) {
                    finalAttemptFlags.add(finalAttempt);
                    if (rejectEverything) {
                        return false; // simulate an engine-rejected action
                    }
                    // Simulate a SUCCESSFUL action by actually taking the legal
                    // one (pass priority). A stub that merely returns true takes
                    // no action, so the engine re-asks priority forever and the
                    // harness aborts at its 400-call guard — that was the
                    // testRecoveryOnRetryDoesNotReachFallback red.
                    pass(game);
                    return true;
                }
            };
            llmPlayer.getLLMConfig()
                    .apiEndpoint("http://localhost:11434/v1/chat/completions")
                    .model("deepseek-v4-flash:cloud")
                    .temperature(0.0)
                    .maxTokens(65536)
                    .timeoutSeconds(120)
                    .deckAssessmentEnabled(false)
                    .verboseLogging(false);
            llmPlayer.reconnect();
            TestPlayer testPlayer = new TestPlayer(llmPlayer);
            testPlayer.setAIPlayer(true); // LLM seat takes priority itself
            return testPlayer;
        }
        return super.createPlayer(name, range);
    }

    @After
    public void resetSignals() {
        finalAttemptFlags.clear();
        rejectEverything = true;
    }

    /**
     * Every attempt is rejected: prior code threw and killed the game; the fix
     * must pass priority (priority returns true, the game runs to the stop step)
     * and exactly every third attempt must have been flagged finalAttempt.
     *
     * The fixture mirrors LLMBasicTest.testCastCreatureWhenManaAvailable (a
     * castable creature + mana) so buildActionList produces a real action; the
     * overridden promptAndExecute then deterministically rejects it.
     */
    @Test
    public void testExhaustedRetriesPassInsteadOfThrow() {
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 2);
        addCard(Zone.HAND, playerA, "Silvercoat Lion", 1);
        addCard(Zone.BATTLEFIELD, playerB, "Hill Giant", 1);

        setStrictChooseMode(false);
        rejectEverything = true;

        setStopAt(1, PhaseStep.BEGIN_COMBAT);

        // Pre-fix, the third rejection escaped as RuntimeException and this call
        // would throw out of the test harness.
        execute();

        Assert.assertTrue("retry loop never ran — the fixture produced no "
                        + "LLM-decidable action (buildActionList empty?)",
                finalAttemptFlags.size() >= 3);
        Assert.assertEquals("each retry burst must be exactly 3 attempts "
                        + "(flags=" + finalAttemptFlags + ")",
                0, finalAttemptFlags.size() % 3);
        long finalFlags = finalAttemptFlags.stream().filter(Boolean::booleanValue).count();
        Assert.assertEquals("exactly one final attempt per 3-attempt burst "
                        + "(flags=" + finalAttemptFlags + ")",
                finalAttemptFlags.size() / 3, finalFlags);
        // Indices 2, 5, 8, … are the 3rd attempt of each burst and must be final.
        for (int i = 2; i < finalAttemptFlags.size(); i += 3) {
            Assert.assertTrue("attempt " + (i + 1) + " must be flagged finalAttempt",
                    finalAttemptFlags.get(i));
        }
        Assert.assertFalse("attempts 1-2 of a burst must not be flagged finalAttempt",
                finalAttemptFlags.get(0) || finalAttemptFlags.get(1));
        // The game survived: reaching the stop step without an uncaught
        // RuntimeException is itself the assertion (the pre-fix throw would have
        // escaped into GameImpl.playPriority's error path → "Too many errors").
        Assert.assertFalse("game must not be over from a stuck decision",
                currentGame.checkIfGameIsOver());
    }

    /**
     * A recovering model stops the loop on its first successful action: the
     * fallback never fires.
     */
    @Test
    public void testRecoveryOnRetryDoesNotReachFallback() {
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 2);
        addCard(Zone.HAND, playerA, "Silvercoat Lion", 1);
        addCard(Zone.BATTLEFIELD, playerB, "Hill Giant", 1);

        setStrictChooseMode(false);
        rejectEverything = false; // the first call already "succeeds"

        setStopAt(1, PhaseStep.BEGIN_COMBAT);

        execute();

        Assert.assertTrue("a successful action must stop the retry burst after 1 call "
                        + "(flags=" + finalAttemptFlags + ")",
                !finalAttemptFlags.isEmpty());
        Assert.assertFalse("a recovered decision must never be flagged finalAttempt",
                finalAttemptFlags.get(0));
    }
}
