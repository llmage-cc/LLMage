
package org.mage.test.cards.abilities.keywords;

import mage.abilities.Ability;
import mage.abilities.SpellAbility;
import mage.abilities.costs.AlternativeSourceCosts;
import mage.abilities.keyword.HasteAbility;
import mage.cards.Card;
import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBase;

/**
 * 702.109. {@link mage.abilities.keyword.DashAbility Dash}
 * 702.109a Dash represents three abilities: two static abilities that
 * function while the card with dash is on the stack, one of which may
 * create a delayed triggered ability, and a static ability that functions
 * while the object with dash is on the battlefield.
 * <p>
 * “Dash [cost]” means “You may cast this card by paying [cost] rather that
 * its mana cost,” “If this spell's dash cost was paid, return the permanent this
 * spell becomes to its owner's hand at the beginning of the next end step,”
 * and “As long as this permanent's dash cost was paid, it has haste.”
 * <p>
 * Paying a card's dash cost follows the rules for paying alternative costs
 * in rules 601.2b and 601.2e–g.
 *
 * @author BetaSteward
 */
public class DashTest extends CardTestPlayerBase {
    /**
     * Screamreach Brawler
     * Creature — Orc Berserker 2/3
     * {2}{R}
     * <p>
     * Dash {1}{R} (You may cast this spell for its dash cost. If you do, it gains haste, and it's returned
     * from the battlefield to its owner's hand at the beginning of the next end step.)
     */
    @Test
    public void testDash() {
        setStrictChooseMode(true);

        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 2);
        addCard(Zone.HAND, playerA, "Screamreach Brawler");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Screamreach Brawler");
        setChoice(playerA, "Cast with Dash alternative cost: {1}{R} (source: Screamreach Brawler");
        attack(1, playerA, "Screamreach Brawler");

        setStopAt(2, PhaseStep.UNTAP);
        execute();

        assertLife(playerB, 18);
        assertPermanentCount(playerA, "Screamreach Brawler", 0);
        assertHandCount(playerA, "Screamreach Brawler", 1);

    }

    @Test
    public void testNoDash() {
        setStrictChooseMode(true);

        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 3);
        addCard(Zone.HAND, playerA, "Screamreach Brawler");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Screamreach Brawler");
        setChoice(playerA, TestPlayer.CHOICE_NORMAL_COST);
        checkPlayableAbility("attack", 1, PhaseStep.PRECOMBAT_MAIN, playerA, "attack: Scream", false);

        setStopAt(2, PhaseStep.UNTAP);
        execute();

        // TODO: Must be a better way to check if a creature can't attack

        assertLife(playerB, 20);
        assertPermanentCount(playerA, "Screamreach Brawler", 1);
        assertHandCount(playerA, "Screamreach Brawler", 0);

    }

    /**
     * If a dashed creature dies, it is not returned to hand.
     */
    @Test
    public void testDashedCreatureDiesInCombat() {
        setStrictChooseMode(true);

        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 2); // enough for Dash, can't cast normal way.
        addCard(Zone.HAND, playerA, "Screamreach Brawler"); // 2/3

        addCard(Zone.BATTLEFIELD, playerB, "Geist of the Moors", 1); // 3/1

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Screamreach Brawler");
        setChoice(playerA, "Cast with Dash alternative cost: {1}{R} (source: Screamreach Brawler");

        attack(1, playerA, "Screamreach Brawler");
        block(1, playerB, "Geist of the Moors", "Screamreach Brawler");

        setStopAt(2, PhaseStep.UNTAP);
        execute();

        assertLife(playerB, 20);
        assertPermanentCount(playerA, "Screamreach Brawler", 0);
        assertHandCount(playerA, "Screamreach Brawler", 0);
        assertGraveyardCount(playerA, "Screamreach Brawler", 1);
        assertGraveyardCount(playerB, "Geist of the Moors", 1);

    }

    /**
     * Test that the creature got Dash again if cast again
     */
    @Test
    public void testDashedCreatureDiesInCombatAndIsLaterRecast() {
        setStrictChooseMode(true);

        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 2); // enough for Dash, can't cast normal way.
        addCard(Zone.HAND, playerA, "Screamreach Brawler"); // 2/3

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Screamreach Brawler");
        setChoice(playerA, "Cast with Dash alternative cost: {1}{R} (source: Screamreach Brawler");
        attack(1, playerA, "Screamreach Brawler");

        castSpell(3, PhaseStep.PRECOMBAT_MAIN, playerA, "Screamreach Brawler");
        setChoice(playerA, "Cast with Dash alternative cost: {1}{R} (source: Screamreach Brawler");
        setStopAt(3, PhaseStep.BEGIN_COMBAT);
        execute();

        assertLife(playerB, 18);
        assertPermanentCount(playerA, "Screamreach Brawler", 1);
        assertHandCount(playerA, "Screamreach Brawler", 0);
        assertAbility(playerA, "Screamreach Brawler", HasteAbility.getInstance(), true);
    }

    @Test
    public void testWarbringerCostReduction() {
        setStrictChooseMode(true);

        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Warbringer");
        addCard(Zone.HAND, playerA, "Warbringer");

        setStrictChooseMode(true);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Warbringer");
        setChoice(playerA, "Cast with Dash alternative cost: {2}{R} (source: Warbringer");
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Warbringer", 2);
        assertHandCount(playerA, "Warbringer", 0);
    }

    @Test
    public void testRegularCostReduction() {
        setStrictChooseMode(true);

        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Ruby Medallion");
        addCard(Zone.HAND, playerA, "Screamreach Brawler");

        setStrictChooseMode(true);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Screamreach Brawler");
        setChoice(playerA, "Cast with Dash alternative cost: {1}{R} (source: Screamreach Brawler");
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Ruby Medallion", 1);
        assertPermanentCount(playerA, "Screamreach Brawler", 1);
        assertHandCount(playerA, "Screamreach Brawler", 0);
    }

    /**
     * Regression: mana cost payability must be answered for real. Dash is an alternative
     * cast cost ({1}{R} for Screamreach Brawler); with no red source the alternative cost is
     * not payable, so {@code canPay} must answer false and the alternative cost must not be
     * offered as activatable (the old stub returned true for every symbol, so the Dash option
     * showed up in the cast menu/action list with zero red sources).
     */
    @Test
    public void testDashAlternativeCostNotPayableWithoutRedSource() {
        addCard(Zone.BATTLEFIELD, playerA, "Wastes", 2);
        addCard(Zone.HAND, playerA, "Screamreach Brawler");

        runCode("dash not payable without red source", 1, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> {
            Card brawler = null;
            for (Card card : player.getHand().getCards(game)) {
                if (card.getName().equals("Screamreach Brawler")) {
                    brawler = card;
                }
            }
            Assert.assertNotNull(brawler);
            AlternativeSourceCosts dash = null;
            for (Ability ability : brawler.getAbilities()) {
                if (ability instanceof AlternativeSourceCosts) {
                    dash = (AlternativeSourceCosts) ability;
                }
            }
            Assert.assertNotNull(dash);
            SpellAbility spell = brawler.getSpellAbility();
            Assert.assertNotNull(spell);
            Assert.assertFalse("Dash alternative cost {1}{R} must not be payable with {C}{C} only",
                    dash.getCosts().canPay(spell, dash, player.getId(), game));
            Assert.assertFalse("Dash must not be offered as activatable without a red source",
                    dash.canActivateAlternativeCostsNow(spell, game));
        });
        checkPlayableAbility("no red source, no cast", 1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cast Screamreach Brawler", false);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertHandCount(playerA, "Screamreach Brawler", 1);
    }

    /**
     * Regression, positive half: with a red source available the Dash alternative cost is
     * payable and offered (the real payability answer must not over-restrict either). Two
     * Mountains pay exactly {1}{R}, so the card is castable ONLY via the Dash alternative cost
     * here (the regular {2}{R} needs three mana).
     */
    @Test
    public void testDashAlternativeCostPayableWithRedSource() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 2);
        addCard(Zone.HAND, playerA, "Screamreach Brawler");

        runCode("dash payable with red source", 1, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> {
            Card brawler = null;
            for (Card card : player.getHand().getCards(game)) {
                if (card.getName().equals("Screamreach Brawler")) {
                    brawler = card;
                }
            }
            Assert.assertNotNull(brawler);
            AlternativeSourceCosts dash = null;
            for (Ability ability : brawler.getAbilities()) {
                if (ability instanceof AlternativeSourceCosts) {
                    dash = (AlternativeSourceCosts) ability;
                }
            }
            Assert.assertNotNull(dash);
            SpellAbility spell = brawler.getSpellAbility();
            Assert.assertNotNull(spell);
            Assert.assertTrue("Dash alternative cost {1}{R} must be payable with {R}{R}",
                    dash.getCosts().canPay(spell, dash, player.getId(), game));
            Assert.assertTrue("Dash must be offered as activatable with a red source",
                    dash.canActivateAlternativeCostsNow(spell, game));
        });
        checkPlayableAbility("dash-only cast offered", 1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cast Screamreach Brawler", true);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();
    }
}
