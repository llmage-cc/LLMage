package org.mage.test.multiplayer;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestMultiPlayerBase;

/**
 * Multiplayer collateral regression for the searchLibrary stricter no-op
 * semantics:
 * Sylvan Primordial's "for each permanent destroyed this way, search your
 * library for a Forest card" wires {@code TargetCardInLibrary(0, X, filter)}
 * after the fix, so with fewer Forests in the library than destroyed
 * permanents the available Forests are still found.
 * <p>
 * Base: 4-player FreeForAll, range of influence one — player order is
 * A -&gt; D -&gt; C -&gt; B, so A's opponents in range are D and B.
 *
 *
 */
public class SearchLibraryPartialFindMultiplayerTest extends CardTestMultiPlayerBase {

    /**
     * Sylvan Primordial destroys one noncreature permanent of EACH opponent
     * (D and B), so X = 2, but the library holds only one Forest — it must be
     * found (before the fix: min 2 &gt; 1 match → the search found nothing).
     */
    @Test
    public void testSylvanPrimordialPartialFind() {
        skipInitShuffling(); // keep the drawn top card deterministic (must be a Mountain, not the Forest)
        removeAllCardsFromHand(playerA);
        removeAllCardsFromLibrary(playerA);

        addCard(Zone.BATTLEFIELD, playerA, "Forest", 7); // {5}{G}{G}
        addCard(Zone.HAND, playerA, "Sylvan Primordial");
        // the Forest sits between two filler cards: FreeForAll does not skip the
        // starting player's first draw, and this keeps the Forest off the top
        // whichever end of the list ends up as the library's top card.
        addCard(Zone.LIBRARY, playerA, "Mountain", 1);
        addCard(Zone.LIBRARY, playerA, "Forest", 1); // fewer Forests than destroyed permanents
        addCard(Zone.LIBRARY, playerA, "Mountain", 1);

        // opponents in range of A: D and B (player order A -> D -> C -> B)
        addCard(Zone.BATTLEFIELD, playerD, "Sol Ring");
        addCard(Zone.BATTLEFIELD, playerB, "Sol Ring");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Sylvan Primordial");
        addTarget(playerA, "Sol Ring"); // destroy D's Sol Ring (order irrelevant, one per opponent)
        addTarget(playerA, "Sol Ring"); // destroy B's Sol Ring
        addTarget(playerA, "Forest"); // up to two, only one available

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Sylvan Primordial", 1);
        // both Sol Rings destroyed this way -> X = 2 for the search
        assertGraveyardCount(playerD, "Sol Ring", 1);
        assertGraveyardCount(playerB, "Sol Ring", 1);
        assertPermanentCount(playerD, "Sol Ring", 0);
        assertPermanentCount(playerB, "Sol Ring", 0);
        // the single available Forest was found and put onto the battlefield tapped
        assertPermanentCount(playerA, "Forest", 8);
        assertLibraryCount(playerA, "Forest", 0);
    }
}
