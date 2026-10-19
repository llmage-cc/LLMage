package org.mage.test.AI.basic;

import mage.abilities.common.PassAbility;
import mage.constants.PhaseStep;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.player.TestComputerPlayerMonteCarlo;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseWithMonteCarloAIHelps;

/**
 * Regression test for the defect "AI (Monte Carlo / MCTS) seat stuck in infinite
 * 'null ability' loop at turn 1 upkeep — game never starts"

 * <p>
 * Before the fix, {@code ComputerPlayerMCTS.priority()} read the computed action from the MCTS
 * root unconditionally: with no action ({@code root.getAction() == null}, or {@code root == null})
 * it logged FATAL "null ability" and called {@code activateAbility(null, game)} — a no-op that
 * returns false and does NOT pass the seat — yet priority() still returned true ("I did
 * something"). The engine's priority loop only yields for a player who is passed, so it re-fired
 * the same seat forever (the opponent never received priority again and
 * the table looked hung). The fix: a missing computed action warns and passes the seat, returning
 * false — the same side effect {@code pass(game)} / the PassAbility branch always had.
 * <p>
 * Test design: the real MCTS search cannot run inside Mage.Tests (see the seam docs in
 * {@link TestComputerPlayerMonteCarlo}: createMCTSGame restores MCTSPlayer over the framework's
 * TestPlayer wrappers and throws), so the seat stays passive and the seam injects the root state
 * {@code getNextAction} would have produced. {@code ComputerPlayerMCTS.priority()} itself — the
 * code under test — runs for real, on the seat's own upkeep priority, exactly as in a live game.
 * <p>
 * Note on turn 1: {@code TwoPlayerDuel.init} makes the starting player skip the draw step
 * (103.7a), so liveness is asserted at PRECOMBAT_MAIN, not DRAW.
 */
public class MonteCarloNullActionTest extends CardTestPlayerBaseWithMonteCarloAIHelps {

    private TestComputerPlayerMonteCarlo getMonteCarloSeat(TestPlayer seat) {
        return (TestComputerPlayerMonteCarlo) seat.getComputerPlayer();
    }

    /**
     * The failing condition: root exists but carries no action ("choose action:null success
     * ratio: -1.0" in the log proves root != null). priority() must pass, not spin.
     */
    @Test
    public void test_MonteCarloSeat_NullActionOnUpkeep_MustPassAndAdvance() {
        runCode("MC seat priority with a null action", 1, PhaseStep.UPKEEP, playerB, (info, player, game) -> {
            TestComputerPlayerMonteCarlo mcSeat = (TestComputerPlayerMonteCarlo) player;
            Assert.assertEquals("must run on the seat's own upkeep priority",
                    PhaseStep.UPKEEP, game.getTurnStepType());
            mcSeat.setNullActionMode(TestComputerPlayerMonteCarlo.NullActionMode.NULL_ACTION);

            // the exact failing call: priority() with an action-less root
            boolean didSomething = mcSeat.priority(game);

            Assert.assertFalse("a missing computed action must not report an action"
                            + " (returning true without passing was the spin)",
                    didSomething);
            Assert.assertTrue("a missing computed action must pass the seat so the game advances",
                    mcSeat.isPassed());
            Assert.assertEquals("priority() must never activate a null ability",
                    0, mcSeat.getNullAbilityActivationCount());

            // engine re-fire simulation: even if priority() is called again before the seat
            // resets, it must keep passing and never spin (each call returns false)
            boolean didSomethingAgain = mcSeat.priority(game);
            Assert.assertFalse("a repeated priority() with a null action must still not report an action",
                    didSomethingAgain);
            Assert.assertEquals("priority() must never activate a null ability",
                    0, mcSeat.getNullAbilityActivationCount());
        });

        // liveness proof: the engine moved past the upkeep — the seat yielded priority instead
        // of being re-fired forever. Under the old code the game never left this step.
        runCode("upkeep must complete and the turn must advance", 1, PhaseStep.PRECOMBAT_MAIN, playerA,
                (info, player, game) -> {
                    Assert.assertEquals("turn must have advanced to the precombat main phase",
                            PhaseStep.PRECOMBAT_MAIN, game.getTurnStepType());
                    Assert.assertEquals("the whole turn must have passed with no null ability activation",
                            0, getMonteCarloSeat(playerB).getNullAbilityActivationCount());
                });

        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        setStrictChooseMode(true);
        execute();

        Assert.assertEquals("priority() must never activate a null ability",
                0, getMonteCarloSeat(playerB).getNullAbilityActivationCount());
    }

    /**
     * The second null source: the state lookup can leave root == null (no matching tree state,
     * nothing rebuilt). priority() must not NPE and must pass the seat.
     */
    @Test
    public void test_MonteCarloSeat_NullRootOnUpkeep_MustPassNotCrash() {
        runCode("MC seat priority with a null root", 1, PhaseStep.UPKEEP, playerB, (info, player, game) -> {
            TestComputerPlayerMonteCarlo mcSeat = (TestComputerPlayerMonteCarlo) player;
            mcSeat.setNullActionMode(TestComputerPlayerMonteCarlo.NullActionMode.NULL_ROOT);

            boolean didSomething = mcSeat.priority(game);

            Assert.assertEquals("the null-root path must have been exercised",
                    1, mcSeat.getInterceptedPriorityCalls());
            Assert.assertFalse("a null root must not report an action (returning true was the spin)",
                    didSomething);
            Assert.assertTrue("a null root must pass the seat so the game advances",
                    mcSeat.isPassed());
            Assert.assertEquals("priority() must never activate a null ability",
                    0, mcSeat.getNullAbilityActivationCount());
        });

        runCode("upkeep must complete and the turn must advance", 1, PhaseStep.PRECOMBAT_MAIN, playerA,
                (info, player, game) -> {
                    Assert.assertEquals("turn must have advanced to the precombat main phase",
                            PhaseStep.PRECOMBAT_MAIN, game.getTurnStepType());
                });

        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        setStrictChooseMode(true);
        execute();

        Assert.assertEquals("priority() must never activate a null ability",
                0, getMonteCarloSeat(playerB).getNullAbilityActivationCount());
    }

    /**
     * Negative control: a genuinely computed PassAbility must keep its pre-fix behavior —
     * activateAbility(passAbility) passes the seat and priority() returns false.
     */
    @Test
    public void test_MonteCarloSeat_PassAbilityAction_KeepsPassBehavior() {
        runCode("MC seat priority with a PassAbility", 1, PhaseStep.UPKEEP, playerB, (info, player, game) -> {
            TestComputerPlayerMonteCarlo mcSeat = (TestComputerPlayerMonteCarlo) player;
            mcSeat.setForcedPriorityActionForTest(new PassAbility());

            boolean didSomething = mcSeat.priority(game);

            Assert.assertEquals("the forced-action path must have been exercised",
                    1, mcSeat.getInterceptedPriorityCalls());
            Assert.assertFalse("a computed PassAbility must keep priority() == false",
                    didSomething);
            Assert.assertTrue("a computed PassAbility must pass the seat",
                    mcSeat.isPassed());
            Assert.assertEquals("priority() must never activate a null ability",
                    0, mcSeat.getNullAbilityActivationCount());
        });

        runCode("upkeep must complete and the turn must advance", 1, PhaseStep.PRECOMBAT_MAIN, playerA,
                (info, player, game) -> {
                    Assert.assertEquals("turn must have advanced to the precombat main phase",
                            PhaseStep.PRECOMBAT_MAIN, game.getTurnStepType());
                });

        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        setStrictChooseMode(true);
        execute();

        Assert.assertEquals("priority() must never activate a null ability",
                0, getMonteCarloSeat(playerB).getNullAbilityActivationCount());
    }
}
