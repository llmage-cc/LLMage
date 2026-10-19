package org.mage.test.cards.single.mh2;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

/**
 * @author Susucr
 */
public class UrzasSagaTest extends CardTestPlayerBase {

    /**
     * {@link mage.cards.u.UrzasSaga Urza's Saga}
     * Enchantment Land — Urza's Saga
     * I — This Saga gains “{T}: Add {C}.”
     * II — This Saga gains “{2}, {T}: Create a 0/0 colorless Construct artifact creature token with ‘This token gets +1/+1 for each artifact you control.’”
     * III — Search your library for an artifact card with mana cost {0} or {1}, put it onto the battlefield, then shuffle.
     */
    private static final String saga = "Urza's Saga";

    @Test
    public void test_SimplePlay() {
        skipInitShuffling();
        addCard(Zone.LIBRARY, playerA, "Black Lotus");
        addCard(Zone.LIBRARY, playerA, "Plateau", 2);

        addCard(Zone.HAND, playerA, saga, 1);
        addCard(Zone.HAND, playerA, "Sol Ring", 1);

        playLand(1, PhaseStep.PRECOMBAT_MAIN, playerA, saga);
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN, playerA); // let trigger I resolve
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Sol Ring");

        waitStackResolved(3, PhaseStep.PRECOMBAT_MAIN, playerA); // let trigger II
        activateManaAbility(3, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Add {C}{C}");
        activateAbility(3, PhaseStep.PRECOMBAT_MAIN, playerA, "{2}");

        checkPermanentCount("after first token creation", 3, PhaseStep.POSTCOMBAT_MAIN, playerA, "Construct Token", 1);

        activateManaAbility(5, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Add {C}{C}");
        activateAbility(5, PhaseStep.PRECOMBAT_MAIN, playerA, "{2}");
        addTarget(playerA, "Black Lotus");

        setStrictChooseMode(true);
        setStopAt(5, PhaseStep.POSTCOMBAT_MAIN);
        execute();

        assertGraveyardCount(playerA, saga, 1);
        assertPermanentCount(playerA, "Black Lotus", 1);
        assertPermanentCount(playerA, "Construct Token", 2);
        assertPowerToughness(playerA, "Construct Token", 4, 4);
    }

    /**
     * Regression ({@code AbilityImpl.activate} pay-order): the chapter II ability
     * "{2}, {T}: Create a 0/0 colorless Construct ..." requires tapping Urza's Saga itself, so
     * the Saga must not be tapped for its own "{T}: Add {C}" to pay that {2}. The mana pool is
     * left empty here on purpose, so the {2} must come from the OTHER source (Sol Ring) —
     * before the fix the payment loop could tap the still-untapped Saga for mana, after which
     * the tap cost failed and the whole activation was rejected and rolled back.
     */
    @Test
    public void test_ChapterII_NoSelfManaTap() {
        addCard(Zone.BATTLEFIELD, playerA, "Sol Ring");

        addCard(Zone.HAND, playerA, saga, 1);
        playLand(1, PhaseStep.PRECOMBAT_MAIN, playerA, saga);
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN, playerA); // let trigger I resolve ({T}: Add {C})
        waitStackResolved(3, PhaseStep.PRECOMBAT_MAIN, playerA); // let trigger II resolve ({2}, {T}: ...)

        // no mana in pool: the cost must be paid with Sol Ring, not with the Saga itself
        activateAbility(3, PhaseStep.PRECOMBAT_MAIN, playerA, "{2}");

        setStrictChooseMode(true);
        setStopAt(3, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Construct Token", 1);
        assertTapped(saga, true); // tapped by its own ability's {T} cost
        assertTapped("Sol Ring", true); // tapped for the {2}
    }

    /**
     * Regression, alternative-source half: with enough OTHER colorless sources the chapter II
     * ability "{2}, {T}" must go through while paying its own tap — the Saga may only be tapped
     * by its own {T} cost, never for its own "{T}: Add {C}" mana (a permanent can't be both the
     * tapped cost and the mana source of the same activation). The two Wastes provide the {2}.
     */
    @Test
    public void test_ChapterII_PaysWithOtherSourcesOnly() {
        addCard(Zone.BATTLEFIELD, playerA, "Wastes", 2);

        addCard(Zone.HAND, playerA, saga, 1);
        playLand(1, PhaseStep.PRECOMBAT_MAIN, playerA, saga);
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN, playerA); // let trigger I resolve
        waitStackResolved(3, PhaseStep.PRECOMBAT_MAIN, playerA); // let trigger II resolve

        activateAbility(3, PhaseStep.PRECOMBAT_MAIN, playerA, "{2}");

        setStrictChooseMode(true);
        setStopAt(3, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Construct Token", 1);
        assertTapped(saga, true); // tapped by its own ability's {T} cost
        assertTappedCount("Wastes", true, 2); // tapped for the {2}
    }
}
