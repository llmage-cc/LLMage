package org.mage.test.LLM;

import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.game.Game;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;
import org.mage.test.player.TestPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * RED-FIRST probe for a proposed semantic change to the glm-5.3-flash @
 * opencode zen/go "BOTH channels empty → terminal concede" defect.

 *
 * <p>This test is report-only: it characterizes a proposed product change
 * (fall back to a legal pass when a decision is unrecoverable, instead of
 * conceding the match) — an LLM seat would play on instead of forfeiting.
 * DO NOT make this test pass by weakening it; it exists to make the
 * decision visible as a RED.
 *
 * <p>CHARACTERIZED BASE BEHAVIOR (LLMPlayer.priority, LLMPlayer.java:2354–2358):
 * the non-final attempt raises the exhausted-ladder RuntimeException; priority()'s
 * catch matches {@code thrownAfterFullLadder} and calls
 * {@code concedeOnTerminalFailure(game, e, "priority")} → the game/match ends. The
 * proposed change replaces that call with the legal fallback pass (as the
 * priority retry loop already does).
 *
 * <p>The fixture stubs the LLM decision surface (no network) so the terminal path
 * is deterministic: the non-final priority attempts raise the EXACT both-empty
 * exhausted-ladder exception; a final attempt would take the one unconditionally
 * legal action (pass). At base, the first raise escapes {@code priority()} as the
 * concede/kill path; after the fix, {@code priority()} takes the legal pass and
 * the game survives to the stop step.
 *
 * <p>Offline — no game server and no network. OPT-IN (it is RED at base by
 * design); run: {@code mvn test -pl Mage.Tests
 * -Dtest=LLMZenBothChannelsEmptyConcedeTest -Dxmage.llm.bothempty.probe=true}
 */
public class LLMZenBothChannelsEmptyConcedeTest extends CardTestPlayerBaseLLM {

    /**
     * OPT-IN gate. This probe is RED at base BY DESIGN (it demands the
     * semantic change described above), so it must not run in
     * the default suite — a permanently-red test would break CI hygiene (AGENTS.md:
     * "Never re-run tests to work around a failure"). Run it explicitly with
     * {@code -Dxmage.llm.bothempty.probe=true} to see the RED and to turn it GREEN
     * after the fix.
     */
    @BeforeClass
    public static void requireOptIn() {
        Assume.assumeTrue("both-empty concede probe is opt-in (the change it demands is"
                        + " pending); run with -Dxmage.llm.bothempty.probe=true",
                Boolean.getBoolean("xmage.llm.bothempty.probe"));
    }

    /** Signals between the test body and the player subclass below. */
    static final List<Boolean> finalAttemptFlags = new ArrayList<>();

    /** The exact exhausted-ladder message the captured both-empty shape produces. */
    static final String TERMINAL_BOTH_EMPTY =
            "LLM empty-answer loop survived the full reasoning-effort descent down to 'low'"
            + " and the thinking-disabled final attempt, 5 attempts. Something is seriously"
            + " wrong — the model loops even with thinking turned off. Last error: LLM returned"
            + " empty answer (attempt 5) (no reasoning reported either)";

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence range) {
        if (name.equals("PlayerA")) {
            TestLLMPlayer llmPlayer = new TestLLMPlayer(name, range, 0) {
                @Override
                protected boolean promptAndExecute(Game game, boolean finalAttempt) {
                    finalAttemptFlags.add(finalAttempt);
                    if (finalAttempt) {
                        // The one unconditionally-legal action.
                        pass(game);
                        return true;
                    }
                    // The captured both-empty terminal failure, raised where the real
                    // ladder raises it (before any engine action).
                    throw new RuntimeException(TERMINAL_BOTH_EMPTY);
                }
            };
            llmPlayer.getLLMConfig()
                    .apiEndpoint("http://localhost:11434/v1/chat/completions")
                    .model("glm-5.3-flash")
                    .temperature(0.0)
                    .maxTokens(65536)
                    .timeoutSeconds(120)
                    .deckAssessmentEnabled(false)
                    .verboseLogging(false);
            llmPlayer.reconnect();
            TestPlayer testPlayer = new TestPlayer(llmPlayer);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, range);
    }

    @After
    public void resetSignals() {
        finalAttemptFlags.clear();
    }

    /**
     * A single unrecoverable both-empty decision must NOT end the game. At base the
     * terminal exception escapes {@code priority()} (the game is killed/conceded);
     * the fix direction is a legal pass so the seat plays on.
     */
    @Test
    public void bothEmptyEscalationDoesNotConcedeTheSeat() {
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 2);
        addCard(Zone.HAND, playerA, "Silvercoat Lion", 1);
        addCard(Zone.BATTLEFIELD, playerB, "Hill Giant", 1);

        setStrictChooseMode(false);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);

        try {
            execute();
        } catch (Throwable t) {
            Assert.fail("BASE behavior: the both-empty terminal path escaped priority() and"
                    + " killed the game (fix direction: fall back to a legal pass instead of"
                    + " conceding). Escaped: " + t);
        }
        Assert.assertFalse("the LLM seat must NOT concede the match on an unrecoverable"
                        + " both-empty decision (fix direction: play on with an explicit pass)",
                currentGame.checkIfGameIsOver());
    }
}
