package org.mage.test.match;

import mage.game.FakeMatch;
import org.junit.Assert;
import org.junit.Test;

/**
 * Regression test: MatchImpl.cleanUpOnMatchEnd() NPE'd when the match never
 * started a game (games list empty → getGame() == null). This happens when a
 * table is torn down after a failed player join (e.g. LLMPlayer.applyOverrides
 * throwing during roomJoinTable).
 *
 * Contract: cleanUpOnMatchEnd must not throw when no game was ever added, and
 * must still clear the (empty) games list when saving is off.
 */
public class MatchCleanUpNoGameTest {

    @Test
    public void cleanUpDoesNotThrowWhenMatchHasNoGame() {
        FakeMatch match = new FakeMatch();
        Assert.assertNull("fresh match must have no game", match.getGame());
        // Must not throw NullPointerException (regression: getGame().isSimulation() NPE)
        match.cleanUpOnMatchEnd(false, false);
        Assert.assertTrue("games list must be cleared when save is off", match.getGames().isEmpty());
    }

    @Test
    public void cleanUpDoesNotThrowWhenSavingOnAndNoGame() {
        FakeMatch match = new FakeMatch();
        match.cleanUpOnMatchEnd(true, false);
        Assert.assertTrue(match.getGames().isEmpty());
    }
}