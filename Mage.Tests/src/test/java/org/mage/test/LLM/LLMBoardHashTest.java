package org.mage.test.LLM;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import mage.player.llm.LLMGameSerializer;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies computeBoardHash changes when states that matter to the LLM change —
 * the board-cursor optimization (skip re-rendering unchanged boards) silently
 * served stale state when the hash missed a field. The hash now covers ALL
 * counter types (loyalty!), attachments, and controller — not just +1/+1.
 *
 * Runs with the standard test fixture (no LLM calls); both players passive.
 */
public class LLMBoardHashTest extends CardTestPlayerBaseLLM {

    @Test
    public void loyaltyCounterChangeInvalidatesHash() {
        addCard(Zone.BATTLEFIELD, playerA, "Jace, the Mind Sculptor", 1);
        // Fixture action form: add counter to Jace during PlayerA's upkeep
        addCounters(1, PhaseStep.UPKEEP, playerA, "Jace, the Mind Sculptor", CounterType.LOYALTY, 2);

        setStrictChooseMode(false);
        setStopAt(1, PhaseStep.UPKEEP);
        execute();

        long after = LLMGameSerializer.computeBoardHash(currentGame, playerA.getId());

        // Compare against the hash with the counters removed — i.e. had the SBA
        // or ability not fired, the hash would be this. The assertion is that the
        // loyalty-count difference alone flips the hash.
        mage.game.permanent.Permanent jace = getPermanent("Jace, the Mind Sculptor", playerA);
        Assert.assertNotNull("Jace should be on the battlefield", jace);
        jace.getCounters(currentGame).removeCounter(CounterType.LOYALTY, 2);
        long withoutExtraLoyalty = LLMGameSerializer.computeBoardHash(currentGame  , playerA.getId());

        Assert.assertNotEquals("loyalty counter difference must change the board hash "
                + "(a planeswalker at loyalty 3 vs 5 has different activatable abilities)", after, withoutExtraLoyalty);
    }

    @Test
    public void attachmentChangeInvalidatesHash() {
        addCard(Zone.BATTLEFIELD, playerA, "Silvercoat Lion", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Bonesplitter", 1);

        setStrictChooseMode(false);
        setStopAt(1, PhaseStep.UPKEEP);
        execute();

        long before = LLMGameSerializer.computeBoardHash(currentGame, playerA.getId());
        // Attach Bonesplitter to the Lion — both sides of the reciprocal link
        mage.game.permanent.Permanent equipment = getPermanent("Bonesplitter", playerA);
        mage.game.permanent.Permanent lion = getPermanent("Silvercoat Lion", playerA);
        Assert.assertNotNull(equipment);
        Assert.assertNotNull(lion);
        lion.addAttachment(equipment.getId(), null, currentGame);
        equipment.attachTo(lion.getId(), null, currentGame);
        long after = LLMGameSerializer.computeBoardHash(currentGame, playerA.getId());

        Assert.assertNotEquals("attaching equipment must change the board hash", before, after);
    }

    @Test
    public void unchangedBoardKeepsHashStable() {
        addCard(Zone.BATTLEFIELD, playerA, "Silvercoat Lion", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 3);

        setStrictChooseMode(false);
        setStopAt(1, PhaseStep.UPKEEP);
        execute();

        long first = LLMGameSerializer.computeBoardHash(currentGame, playerA.getId());
        long second = LLMGameSerializer.computeBoardHash(currentGame, playerA.getId());
        Assert.assertEquals("pure re-hash of untouched state must be stable", first, second);
    }
}
