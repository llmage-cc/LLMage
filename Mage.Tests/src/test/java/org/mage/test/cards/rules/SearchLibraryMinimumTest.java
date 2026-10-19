package org.mage.test.cards.rules;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBase;

/**
 * searchLibrary under-minimum semantics (the "stricter no-op rules"
 * behavior" direction).
 *
 * <p>
 * CR 701.23b — a search of a hidden zone for cards with a STATED QUALITY never
 * requires the searcher to find them. When such a search cannot even match the
 * caller's minimum, there is no legal choice, so the search must find nothing
 * (the engine skips the choose call; the minimum is NOT relaxed to the match
 * count).
 * <p>
 * CR 701.23d — a search for a QUANTITY of cards only ("search your library for
 * three cards") must find that many, or as many as possible if the zone has
 * fewer. That relax path is intentionally kept, see
 * {@link #testQuantitySearchStillFindsAsManyAsPossible()}.
 *
 *
 */
public class SearchLibraryMinimumTest extends CardTestPlayerBase {

    /**
     * Infernal Tutor with a restricted same-name filter that matches nothing in
     * the library (the historical T3 scenario): the search must be
     * a no-op instead of relaxing the minimum (before the fix the searcher could
     * be offered cards outside the filter).
     */
    @Test
    public void testRestrictedSearchWithZeroMatchesIsNoOp() {
        removeAllCardsFromHand(playerA);
        removeAllCardsFromLibrary(playerA);

        // Infernal Tutor {1}{B}: reveal a card from hand, search for a card with that name
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 2);
        addCard(Zone.HAND, playerA, "Infernal Tutor", 1);
        addCard(Zone.HAND, playerA, "Tendrils of Agony", 1);
        // library has no Tendrils of Agony: the name-restricted filter matches 0 cards
        addCard(Zone.LIBRARY, playerA, "Dark Ritual", 1);
        addCard(Zone.LIBRARY, playerA, "Swamp", 2);

        // hand has 1 card left at resolution (Tendrils of Agony), so it is revealed automatically
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Infernal Tutor");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        // no card was searched out of the library, and no unrestricted fallback
        assertLibraryCount(playerA, "Dark Ritual", 1);
        assertLibraryCount(playerA, "Swamp", 2);
        // the revealed card stays in hand (it was never a search target)
        assertHandCount(playerA, "Tendrils of Agony", 1);
        assertHandCount(playerA, 1);
        assertGraveyardCount(playerA, "Infernal Tutor", 1);
    }

    /**
     * Burning-Rune Demon ("search your library for exactly two cards not named
     * Burning-Rune Demon that have different names") with only ONE legal card in
     * the library: 1 &lt; min 2, so the restricted search is a no-op. Before the
     * fix the minimum relaxed to 1, the single card was revealed, an opponent
     * chose it and it went to the controller's hand (or graveyard).
     */
    @Test
    public void testRestrictedSearchWithPartialMatchesIsNoOp() {
        removeAllCardsFromHand(playerA);
        removeAllCardsFromLibrary(playerA);

        // Burning-Rune Demon {4}{B}{B}, ETB is optional ("you may search")
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 6);
        addCard(Zone.HAND, playerA, "Burning-Rune Demon", 1);
        // the only card in the library matches the restricted filter
        addCard(Zone.LIBRARY, playerA, "Balduvian Bears", 1);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Burning-Rune Demon");
        setChoice(playerA, true); // optional trigger: yes, search

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        // the search found nothing: the card stays in the library, nothing revealed
        assertLibraryCount(playerA, "Balduvian Bears", 1);
        assertPermanentCount(playerA, "Burning-Rune Demon", 1);
        assertHandCount(playerA, 0);
        assertGraveyardCount(playerA, 0);
    }

    /**
     * CR 701.23d control case: a quantity-only search (no predicates in the
     * filter) must still find as many cards as possible, so the relax path is kept
     * for it. Behold the Beyond asks for exactly three cards and only two are
     * left in the library: both must end up in hand.
     */
    @Test
    public void testQuantitySearchStillFindsAsManyAsPossible() {
        removeAllCardsFromHand(playerA);
        removeAllCardsFromLibrary(playerA);

        // Behold the Beyond {5}{B}{B}: discard your hand, search for three cards, put them into hand
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 7);
        addCard(Zone.HAND, playerA, "Behold the Beyond", 1);
        addCard(Zone.LIBRARY, playerA, "Swamp", 2);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Behold the Beyond");
        addTarget(playerA, "Swamp^Swamp"); // only two cards available for the three-card search
        // The search allows up to three picks, so with both Swamps taken the engine still asks
        // once more (its completion recheck counts candidates across the whole card pool, not
        // just the search window). Finish the dialog like a human player does — with the
        // done/skip command, legal because max 3 > current 2.
        addTarget(playerA, TestPlayer.TARGET_SKIP);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertHandCount(playerA, "Swamp", 2);
        assertLibraryCount(playerA, "Swamp", 0);
    }

    /**
     * Control case: a restricted search that CAN satisfy its minimum keeps working
     * normally (only the under-minimum branch changed).
     */
    @Test
    public void testRestrictedSearchAtMinimumStillWorks() {
        removeAllCardsFromHand(playerA);
        removeAllCardsFromLibrary(playerA);

        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 2);
        // two Infernal Tutors in hand; one is cast, the other is the revealed card
        addCard(Zone.HAND, playerA, "Infernal Tutor", 2);
        addCard(Zone.LIBRARY, playerA, "Infernal Tutor", 1);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Infernal Tutor");
        addTarget(playerA, "Infernal Tutor"); // search target (1 legal match = minimum)

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertHandCount(playerA, "Infernal Tutor", 2);
        assertLibraryCount(playerA, "Infernal Tutor", 0);
    }
}
