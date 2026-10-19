package org.mage.test.cards.rules;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBase;

/**
 * Collateral regression for the searchLibrary stricter no-op semantics
 * cards whose oracle
 * text allows a partial find ("up to N", "for each ...", "less than or equal
 * to") must wire {@code TargetCardInLibrary(0, N, filter)} so that under the
 * stricter engine a search that cannot satisfy N still finds the available
 * subset (and the player may even stop before N).
 * <p>
 * Before the fix these cards used {@code (N, filter)} (min = max = N) and, with
 * the engine semantics, found NOTHING whenever the library held fewer than N
 * matching cards — a visible regression on common lines (Scapeshift etc.).
 * <p>
 * Engine untouched by this fix; see SearchLibraryMinimumTest for the engine-side
 * semantics themselves.
 *
 *
 */
public class SearchLibraryPartialFindTest extends CardTestPlayerBase {

    /**
     * Scapeshift: sacrifice three lands, library holds only two land cards.
     * "Search your library for up to that many land cards" — the search must
     * find both available lands (before the fix: min 3 &gt; 2 matches → nothing).
     */
    @Test
    public void testScapeshiftPartialFind() {
        removeAllCardsFromHand(playerA);
        removeAllCardsFromLibrary(playerA);
        removeAllCardsFromLibrary(playerB); // keep the completion recheck's candidate pool to A's library only

        addCard(Zone.BATTLEFIELD, playerA, "Forest", 5);
        addCard(Zone.HAND, playerA, "Scapeshift");
        addCard(Zone.LIBRARY, playerA, "Mountain", 2);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Scapeshift");
        setChoice(playerA, "Forest^Forest^Forest"); // sacrifice three lands
        setChoice(playerA, TestPlayer.CHOICE_SKIP); // stop sacrificing
        addTarget(playerA, "Mountain^Mountain"); // up to three, take both available

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertGraveyardCount(playerA, "Forest", 3);
        assertPermanentCount(playerA, "Forest", 2);
        assertPermanentCount(playerA, "Mountain", 2);
        assertTappedCount("Mountain", true, 2); // they enter tapped
        assertLibraryCount(playerA, "Mountain", 0);
    }

    /**
     * Scapeshift "up to" discretion: with five lands available the player may
     * stop after two (min 0 now permits fewer than the maximum).
     */
    @Test
    public void testScapeshiftCanStopBeforeMaximum() {
        removeAllCardsFromHand(playerA);
        removeAllCardsFromLibrary(playerA);

        addCard(Zone.BATTLEFIELD, playerA, "Forest", 5);
        addCard(Zone.HAND, playerA, "Scapeshift");
        addCard(Zone.LIBRARY, playerA, "Mountain", 5);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Scapeshift");
        setChoice(playerA, "Forest^Forest^Forest"); // sacrifice three lands
        setChoice(playerA, TestPlayer.CHOICE_SKIP); // stop sacrificing
        addTarget(playerA, "Mountain^Mountain"); // choose only two of the available five
        addTarget(playerA, TestPlayer.TARGET_SKIP); // and stop the search

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertGraveyardCount(playerA, "Forest", 3);
        assertPermanentCount(playerA, "Forest", 2);
        assertPermanentCount(playerA, "Mountain", 2);
        assertLibraryCount(playerA, "Mountain", 3);
    }

    /**
     * Rites of Spring: discard two cards, library holds one basic land.
     * "Search your library for up to that many basic land cards" — the search
     * must find the one available basic land (before the fix: nothing).
     */
    @Test
    public void testRitesOfSpringPartialFind() {
        removeAllCardsFromHand(playerA);
        removeAllCardsFromLibrary(playerA);
        removeAllCardsFromLibrary(playerB); // keep the completion recheck's candidate pool to A's library only

        addCard(Zone.BATTLEFIELD, playerA, "Forest", 2);
        addCard(Zone.HAND, playerA, "Rites of Spring");
        addCard(Zone.HAND, playerA, "Silvercoat Lion");
        addCard(Zone.HAND, playerA, "Pillarfield Ox");
        addCard(Zone.LIBRARY, playerA, "Forest", 1);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Rites of Spring");
        setChoice(playerA, "Silvercoat Lion^Pillarfield Ox"); // discard both (any number)
        addTarget(playerA, "Forest"); // up to two, take the only basic land

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertGraveyardCount(playerA, "Rites of Spring", 1);
        assertGraveyardCount(playerA, "Silvercoat Lion", 1);
        assertGraveyardCount(playerA, "Pillarfield Ox", 1);
        assertHandCount(playerA, "Forest", 1); // revealed and put into hand
        assertLibraryCount(playerA, "Forest", 0);
    }

    /**
     * Far Wanderings, threshold branch: "instead search your library for up to
     * three basic land cards" — with two basic lands available both must be
     * found (before the fix: min 3 &gt; 2 matches → nothing).
     */
    @Test
    public void testFarWanderingsThresholdPartialFind() {
        removeAllCardsFromHand(playerA);
        removeAllCardsFromLibrary(playerA);
        removeAllCardsFromLibrary(playerB); // keep the completion recheck's candidate pool to A's library only

        addCard(Zone.BATTLEFIELD, playerA, "Forest", 3);
        addCard(Zone.GRAVEYARD, playerA, "Silvercoat Lion", 7); // threshold online
        addCard(Zone.HAND, playerA, "Far Wanderings");
        addCard(Zone.LIBRARY, playerA, "Forest", 2);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Far Wanderings");
        addTarget(playerA, "Forest^Forest"); // up to three, take both available

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertGraveyardCount(playerA, "Far Wanderings", 1);
        assertGraveyardCount(playerA, "Silvercoat Lion", 7);
        assertPermanentCount(playerA, "Forest", 5); // 3 original + 2 found
        assertLibraryCount(playerA, "Forest", 0);
    }

    /**
     * Planar Engineering: "Search your library for four basic land cards" — a
     * stated-quality search of a hidden zone is never forced to find cards
     * (CR 701.23b), so with two basic lands available both must be found
     * (before the fix: nothing).
     */
    @Test
    public void testPlanarEngineeringPartialFind() {
        removeAllCardsFromHand(playerA);
        removeAllCardsFromLibrary(playerA);
        removeAllCardsFromLibrary(playerB); // keep the completion recheck's candidate pool to A's library only

        addCard(Zone.BATTLEFIELD, playerA, "Forest", 6);
        addCard(Zone.HAND, playerA, "Planar Engineering");
        addCard(Zone.LIBRARY, playerA, "Mountain", 2);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Planar Engineering");
        setChoice(playerA, "Forest^Forest"); // sacrifice two lands
        addTarget(playerA, "Mountain^Mountain"); // up to four, take both available

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertGraveyardCount(playerA, "Planar Engineering", 1);
        assertGraveyardCount(playerA, "Forest", 2);
        assertPermanentCount(playerA, "Forest", 4);
        assertPermanentCount(playerA, "Mountain", 2);
        assertTappedCount("Mountain", true, 2);
        assertLibraryCount(playerA, "Mountain", 0);
    }

    /**
     * Scholarship Sponsor: the player with fewer lands "searches their library
     * for a number of basic land cards less than or equal to the difference" —
     * library holds one basic land; it must be found (before the fix: nothing).
     */
    @Test
    public void testScholarshipSponsorPartialFind() {
        removeAllCardsFromHand(playerA);
        removeAllCardsFromLibrary(playerA);
        removeAllCardsFromHand(playerB);
        removeAllCardsFromLibrary(playerB);

        addCard(Zone.BATTLEFIELD, playerA, "Plains", 4);
        addCard(Zone.HAND, playerA, "Scholarship Sponsor");
        addCard(Zone.BATTLEFIELD, playerB, "Mountain", 2); // fewer lands than A
        addCard(Zone.LIBRARY, playerB, "Forest", 1); // only one basic land available

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Scholarship Sponsor");
        // playerB is the one who searches (their own library), so B's dialog picks the card
        addTarget(playerB, "Forest"); // up to the difference, take the only basic land

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Scholarship Sponsor", 1);
        assertPermanentCount(playerB, "Mountain", 2);
        assertPermanentCount(playerB, "Forest", 1);
        assertTappedCount("Forest", true, 1);
        assertLibraryCount(playerB, "Forest", 0);
    }

    /**
     * Travel Through Caradhras: council's dilemma, both players vote Redhorn
     * Pass ("for each Redhorn Pass vote, search your library for a basic land
     * card"). With two votes and a single basic land in the library the one
     * available land must be found (before the fix: nothing).
     */
    @Test
    public void testTravelThroughCaradhrasPartialFind() {
        removeAllCardsFromHand(playerA);
        removeAllCardsFromLibrary(playerA);
        removeAllCardsFromLibrary(playerB); // keep the completion recheck's candidate pool to A's library only

        addCard(Zone.BATTLEFIELD, playerA, "Forest", 6);
        addCard(Zone.HAND, playerA, "Travel Through Caradhras");
        addCard(Zone.LIBRARY, playerA, "Forest", 1);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Travel Through Caradhras");
        setChoice(playerA, "Yes"); // vote Redhorn Pass
        setChoice(playerB, "Yes"); // vote Redhorn Pass as well -> two votes
        addTarget(playerA, "Forest"); // up to two, take the only basic land

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertExileCount(playerA, "Travel Through Caradhras", 1); // exiled on resolution
        assertPermanentCount(playerA, "Forest", 7); // 6 original + 1 found
        assertLibraryCount(playerA, "Forest", 0);
    }
}
