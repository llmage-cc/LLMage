package org.mage.test.cards.mana.conditional;

import mage.ConditionalMana;
import mage.Mana;
import mage.abilities.condition.common.AdamantCondition;
import mage.abilities.keyword.FlyingAbility;
import mage.abilities.mana.ManaOptions;
import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;
import static org.mage.test.utils.ManaOptionsTestUtils.assertManaOptions;

/**
 * @author LevelX2
 */
public class ConditionalManaTest extends CardTestPlayerBase {

    @Test
    public void testNormalUse() {
        setStrictChooseMode(true);

        // {T}: Add one mana of any color. Spend this mana only to cast a multicolored spell.
        addCard(Zone.BATTLEFIELD, playerA, "Pillar of the Paruns", 2);

        // Target player gains 7 life.
        addCard(Zone.HAND, playerA, "Heroes' Reunion", 1); // Instant {G}{W}

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Heroes' Reunion", playerA);
        setChoice(playerA, "Green"); // Choose color
        setChoice(playerA, "White"); // Choose color

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertGraveyardCount(playerA, "Heroes' Reunion", 1);
        assertHandCount(playerA, "Heroes' Reunion", 0); // player A could not cast it
        assertLife(playerA, 27);
    }

    @Test
    public void testNotAllowedUse() {
        // {T}: Add one mana of any color. Spend this mana only to cast a multicolored spell.
        addCard(Zone.BATTLEFIELD, playerA, "Pillar of the Paruns", 2);
        addCard(Zone.HAND, playerA, "Silvercoat Lion", 1);

        checkPlayableAbility("before", 1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cast Silvercoat", false);

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertHandCount(playerA, "Silvercoat Lion", 1); // player A could not cast Silvercoat Lion because the conditional mana is not available
    }

    /**
     * // As Secluded Courtyard enters the battlefield, choose a creature type.
     * // {T}: Add {C}.
     * // {T}: Add one mana of any color. Spend this mana only to cast a creature spell of the chosen
     * // type or activate an ability of a creature or creature card of the chosen type.
     * <p>
     * A non-creature card that merely carries the chosen creature subtype (Kindred enchantment
     * Bitterblossom, subtype Faerie) must not be payable with that mana. The creature-type choice is
     * made for "Faerie" and the conditional mana is floated into the mana pool, so the playability
     * check has to evaluate Courtyard's own condition (same gate as Cavern of Souls /
     * CreatureCastManaCondition).
     */
    @Test
    public void testSecludedCourtyardNotCastableForKindredEnchantment() {
        // Secluded Courtyard - Land, enters untapped, {T}: Add {C} / {T}: Add one mana of any color with a restriction
        addCard(Zone.HAND, playerA, "Secluded Courtyard", 1);
        // {T}: Add {C} - covers the generic part {1} of Bitterblossom's cost
        addCard(Zone.BATTLEFIELD, playerA, "Wastes", 1);
        // Bitterblossom - Kindred Enchantment {1}{B}, subtype Faerie (NOT a creature spell)
        addCard(Zone.HAND, playerA, "Bitterblossom", 1);

        playLand(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Secluded Courtyard");
        setChoice(playerA, "Faerie"); // chosen creature type
        // float the Courtyard's conditional mana so the playable check must check its condition
        activateManaAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Add one mana of ");
        setChoice(playerA, "Black");

        checkPlayableAbility("kindred enchantment", 1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cast Bitterblossom", false);

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertHandCount(playerA, "Bitterblossom", 1); // must not be castable with the Courtyard mana
    }

    /**
     * Positive control for the same condition: a creature of the chosen type stays castable off the
     * Courtyard's conditional mana (compare Cavern of Souls behavior).
     */
    @Test
    public void testSecludedCourtyardCastableForCreatureOfChosenType() {
        addCard(Zone.HAND, playerA, "Secluded Courtyard", 1);
        // Faerie Miscreant - Creature {U}, subtype Faerie (creature of the chosen type)
        addCard(Zone.HAND, playerA, "Faerie Miscreant", 1);

        playLand(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Secluded Courtyard");
        setChoice(playerA, "Faerie"); // chosen creature type
        // float the Courtyard's conditional mana (this is the only mana source for the {U})
        activateManaAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Add one mana of ");
        setChoice(playerA, "Blue");

        checkPlayableAbility("creature spell", 1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cast Faerie Miscreant", true);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Faerie Miscreant");

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Faerie Miscreant", 1);
    }

    @Test
    public void testWorkingWithReflectingPool() {
        addCard(Zone.BATTLEFIELD, playerA, "Cavern of Souls", 1); // can give {C] or {any} mana ({any} with restrictions)
        addCard(Zone.BATTLEFIELD, playerA, "Reflecting Pool", 1); // must give {C} or {any} mana from the Cavern, but without restrictions
        addCard(Zone.HAND, playerA, "Silvercoat Lion", 1); // white bear
        addCard(Zone.BATTLEFIELD, playerA, "Upwelling", 1);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Silvercoat Lion");

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Silvercoat Lion", 1);
    }

    @Test
    public void testWorkingWithReflectingPool2() {
        addCard(Zone.BATTLEFIELD, playerA, "Reflecting Pool", 1); // can create white mana without restriction from the Hive
        // {T}: Add {C}.
        // {T}: Add one mana of any color. Spend this mana only to cast a Sliver spell.
        // {5}, {T}: Create a 1/1 colorless Sliver creature token. Activate this ability only if you control a Sliver.
        addCard(Zone.BATTLEFIELD, playerA, "Sliver Hive", 1);
        addCard(Zone.HAND, playerA, "Silvercoat Lion", 1);

        activateManaAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Add {C}");
        activateManaAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Add one mana of any type");

        setChoice(playerA, "White");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Silvercoat Lion");

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Silvercoat Lion", 1);
    }

    /**
     * I was unable to use "Rosheen Meanderer" ability to pay for "Candelabra of
     * Tawnos" ability even thought it has "X" on its cost
     */
    @Test
    public void testRosheenMeandererUsingAbility() {
        // Flying
        addCard(Zone.HAND, playerB, "Snapping Drake", 2); // {3}{U}
        // {T}: Add {C}{C}{C}{C}. Spend this mana only on costs that contain {X}.
        addCard(Zone.BATTLEFIELD, playerB, "Rosheen Meanderer", 1);
        // {X}, {T}: Untap X target lands.
        addCard(Zone.BATTLEFIELD, playerB, "Candelabra of Tawnos", 1);

        addCard(Zone.BATTLEFIELD, playerB, "Island", 4);

        setStrictChooseMode(true);

        castSpell(2, PhaseStep.PRECOMBAT_MAIN, playerB, "Snapping Drake", true);
        activateManaAbility(2, PhaseStep.POSTCOMBAT_MAIN, playerB, "{T}: Add {C}{C}{C}{C}");

        activateAbility(2, PhaseStep.POSTCOMBAT_MAIN, playerB, "{X}, {T}: Untap");
        setChoice(playerB, "X=4");
        addTarget(playerB, "Island");
        addTarget(playerB, "Island");
        addTarget(playerB, "Island");
        addTarget(playerB, "Island");
        waitStackResolved(2, PhaseStep.POSTCOMBAT_MAIN);

        castSpell(2, PhaseStep.POSTCOMBAT_MAIN, playerB, "Snapping Drake");

        setStopAt(2, PhaseStep.END_TURN);
        execute();

        assertTappedCount("Island", true, 4);
        assertTappedCount("Rosheen Meanderer", true, 1);
        assertTappedCount("Candelabra of Tawnos", true, 1);

        assertPermanentCount(playerB, "Snapping Drake", 2);
    }

    /**
     * I've found a bit of a problem with colorless costs. I've been unable to
     * pay colorless costs with lands conditionally tapping for 2 colorless i.e
     * shrine of forsaken gods and eldrazi temple ,including if I float the
     * mana. Seperately but on a related note, if you float at least one
     * colorless mana you can pay all colorless costs with floated generic mana.
     */
    @Test
    public void testPayColorlessWithConditionalMana() {
        // {T}: Add {C}.
        // {T}: Add {C}{C}. Spend this mana only to cast colorless spells. Activate this ability only if you control seven or more lands.
        addCard(Zone.BATTLEFIELD, playerA, "Shrine of the Forsaken Gods", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 8);
        // When you cast Kozilek, the Great Distortion, if you have fewer than seven cards in hand, draw cards equal to the difference.
        // Menace
        // Discard a card with converted mana cost X: Counter target spell with converted mana cost X.
        addCard(Zone.HAND, playerA, "Kozilek, the Great Distortion", 1); // {8}{C}{C}

        activateManaAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Add {C}{C}");
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Kozilek, the Great Distortion");

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Kozilek, the Great Distortion", 1);
    }

    @Test
    public void CultivatorDroneColorlessSpell() {
        // Devoid
        // {T}: Add {C}. Spend this mana only to cast a colorless spell, activate an ability of a colorless permanent, or pay a cost that contains {C}.
        addCard(Zone.BATTLEFIELD, playerA, "Cultivator Drone", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Silvercoat Lion", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 1);
        // Target creature gets +3/-3 until end of turn.
        addCard(Zone.HAND, playerA, "Spatial Contortion", 1); // {1}{C}

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Spatial Contortion", "Silvercoat Lion");

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertGraveyardCount(playerA, "Spatial Contortion", 1);
        assertGraveyardCount(playerA, "Silvercoat Lion", 1);
    }

    @Test
    public void CultivatorDroneColorlessAbility() {
        // Devoid
        // {T}: Add {C}. Spend this mana only to cast a colorless spell, activate an ability of a colorless permanent, or pay a cost that contains {C}.
        addCard(Zone.BATTLEFIELD, playerA, "Cultivator Drone", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Wastes", 1);
        // Untap Endbringer during each other player's untap step.
        // {T}: Endbringer deals 1 damage to any target.
        // {C}, {T}: Target creature can't attack or block this turn.
        // {C}{C}, {T}: Draw a card.
        addCard(Zone.BATTLEFIELD, playerA, "Endbringer", 1); // {1}{C}

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{C}{C},");

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertHandCount(playerA, 1);
    }

    @Test
    public void CultivatorDroneColorlessCost() {
        // Devoid
        // {T}: Add {C}. Spend this mana only to cast a colorless spell, activate an ability of a colorless permanent, or pay a cost that contains {C}.
        addCard(Zone.BATTLEFIELD, playerA, "Cultivator Drone", 1);
        // Devoid (This card has no color.)
        // Flying
        // When Gravity Negator attacks, you may pay {C}. If you do, another target creature gains flying until end of turn. ({C} represents colorless mana)
        addCard(Zone.BATTLEFIELD, playerA, "Gravity Negator", 1); // 2/3

        attack(1, playerA, "Gravity Negator");

        setStopAt(1, PhaseStep.POSTCOMBAT_MAIN);
        execute();

        assertTapped("Gravity Negator", true);
        assertAbility(playerA, "Cultivator Drone", FlyingAbility.getInstance(), true);

        assertLife(playerB, 18);
    }

    @Test
    public void EmpoweredAutogeneratorAddsCountWithMana() {
        // Empowered Autogenerator's activated ability is a mana ability. It doesn’t use the stack and can’t be responded to. (2019-08-23)

        // Empowered Autogenerator enters the battlefield tapped.
        // {T}: Put a charge counter on Empowered Autogenerator. Add X mana of any one color, where X is the number of charge counters on Empowered Autogenerator.
        addCard(Zone.BATTLEFIELD, playerA, "Empowered Autogenerator", 1);
        //
        addCard(Zone.HAND, playerA, "Lightning Bolt", 1);

        activateManaAbility(3, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Put a charge counter");
        setChoice(playerA, "Red");
        castSpell(3, PhaseStep.PRECOMBAT_MAIN, playerA, "Lightning Bolt", playerB);

        setStrictChooseMode(true);
        setStopAt(3, PhaseStep.POSTCOMBAT_MAIN);
        execute();

        assertCounterCount(playerA, "Empowered Autogenerator", CounterType.CHARGE, 1);
        assertLife(playerB, 20 - 3);
    }

    @Test
    public void EmpoweredAutogeneratorAddsDoubleCountersWithDoubleSeason() {
        // Empowered Autogenerator's activated ability is a mana ability. It doesn’t use the stack and can’t be responded to. (2019-08-23)

        // Empowered Autogenerator enters the battlefield tapped.
        // {T}: Put a charge counter on Empowered Autogenerator. Add X mana of any one color, where X is the number of charge counters on Empowered Autogenerator.
        addCard(Zone.BATTLEFIELD, playerA, "Empowered Autogenerator", 1);
        //
        addCard(Zone.HAND, playerA, "Lightning Bolt", 1);
        //
        // If an effect would put one or more tokens into play under your control, it puts twice that many of those tokens into play instead.
        // If an effect would place one or more counters on a permanent you control, it places twice that many of those counters on that permanent instead.
        addCard(Zone.BATTLEFIELD, playerA, "Doubling Season", 1);

        activateManaAbility(3, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Put a charge counter");
        setChoice(playerA, "Red");
        castSpell(3, PhaseStep.PRECOMBAT_MAIN, playerA, "Lightning Bolt", playerB);

        setStrictChooseMode(true);
        setStopAt(3, PhaseStep.POSTCOMBAT_MAIN);
        execute();

        assertCounterCount(playerA, "Empowered Autogenerator", CounterType.CHARGE, 2);
        assertLife(playerB, 20 - 3);
    }

    @Test
    public void EmpoweredAutogeneratorAddsDoubleCountersWithDoubleSeason_AutoPay() {
        // Empowered Autogenerator's activated ability is a mana ability. It doesn’t use the stack and can’t be responded to. (2019-08-23)

        // Empowered Autogenerator enters the battlefield tapped.
        // {T}: Put a charge counter on Empowered Autogenerator. Add X mana of any one color, where X is the number of charge counters on Empowered Autogenerator.
        addCard(Zone.BATTLEFIELD, playerA, "Empowered Autogenerator", 1);
        //
        addCard(Zone.HAND, playerA, "Lightning Bolt", 1);
        //
        // If an effect would put one or more tokens into play under your control, it puts twice that many of those tokens into play instead.
        // If an effect would place one or more counters on a permanent you control, it places twice that many of those counters on that permanent instead.
        addCard(Zone.BATTLEFIELD, playerA, "Doubling Season", 1);

        //activateManaAbility(3, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Put a charge counter"); // auto pay
        setChoice(playerA, "Red");
        castSpell(3, PhaseStep.PRECOMBAT_MAIN, playerA, "Lightning Bolt", playerB);

        setStrictChooseMode(true);
        setStopAt(3, PhaseStep.POSTCOMBAT_MAIN);
        execute();

        assertCounterCount(playerA, "Empowered Autogenerator", CounterType.CHARGE, 2);
        assertLife(playerB, 20 - 3);
    }

    @Test
    public void DictateOfKarametra_ManualPay() {
        // Whenever you tap a land for mana, add one mana of any type that land produced.
        addCard(Zone.BATTLEFIELD, playerA, "Dictate of Karametra");
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 2);
        //
        addCard(Zone.HAND, playerA, "Precision Bolt", 1); // {2}{R}

        // manual mana pay to activate extra mana
        activateManaAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Add {R}");
        activateManaAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Add {R}");
        checkManaPool("mana", 1, PhaseStep.PRECOMBAT_MAIN, playerA, "R", 4);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Precision Bolt", playerB);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.POSTCOMBAT_MAIN);
        execute();

        assertLife(playerB, 20 - 3);
    }

    // TODO: add support TriggeredManaAbility for available mana calculations
    //  AI can't see extra mana added by AddManaOfAnyTypeProducedEffect and same
    //  (maybe it was removed by https://github.com/magefree/mage/pull/5943 to fix multiple TAPPED_FOR_MANA calls or never works before)
    //  As idea: getPlayable -> getManaAvailable -> available.addMana -- search all TriggeredManaAbility
    //  and process all available net mana by special call like TriggeredManaAbility->getNetManaForEvent(ManaEvent xxx)

    @Test
    public void TriggeredManaAbilityMustGivesExtraManaOptions() {
        // TriggeredManaAbility must give extra mana options (2 red instead 1)
        // Whenever you tap a land for mana, add one mana of any type that land produced.
        addCard(Zone.BATTLEFIELD, playerA, "Dictate of Karametra");
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.POSTCOMBAT_MAIN);
        execute();

        ManaOptions manaOptions = playerA.getAvailableManaTest(currentGame);
        Assert.assertEquals("mana variations don't fit", 1, manaOptions.size());
        assertManaOptions("{R}{R}", manaOptions);
    }

    @Test
    public void DictateOfKarametra_AutoPay() {
        // Whenever you tap a land for mana, add one mana of any type that land produced.
        addCard(Zone.BATTLEFIELD, playerA, "Dictate of Karametra");
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 2);
        //
        addCard(Zone.HAND, playerA, "Precision Bolt", 1); // {2}{R}

        // computer must see available mana (4 red mana instead 2)
        //activateManaAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Add {R}");
        //activateManaAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Add {R}");
        showAvailableAbilities("abils", 1, PhaseStep.PRECOMBAT_MAIN, playerA);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Precision Bolt", playerB);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.POSTCOMBAT_MAIN);
        execute();

        assertLife(playerB, 20 - 3);
    }

    @Test
    public void testTwoConditionalMana() {
        setStrictChooseMode(true);

        // At the beginning of your upkeep, look at the top card of your library. You may put that card into your graveyard.
        // Exile a card from your graveyard: Add {C}. Spend this mana only to cast a colored spell without {X} in its mana cost.
        addCard(Zone.BATTLEFIELD, playerA, "Titans' Nest"); // Enchantment {1}{B}{G}{U}

        // {T}: Add {C}{C}{C}{C}. Spend this mana only on costs that contain {X}.
        addCard(Zone.BATTLEFIELD, playerA, "Rosheen Meanderer", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);

        addCard(Zone.GRAVEYARD, playerA, "Grizzly Bears", 2);

        // Init library to mill a third bear
        skipInitShuffling();
        addCard(Zone.LIBRARY, playerA, "Grizzly Bears");

        addTarget(playerA, "Grizzly Bears"); // upkeep surveil: put the Bears in the graveyard.

        setStopAt(1, PhaseStep.POSTCOMBAT_MAIN);
        execute();

        assertPermanentCount(playerA, "Titans' Nest", 1);

        ManaOptions manaOptions = playerA.getAvailableManaTest(currentGame);
        Assert.assertEquals("mana variations don't fit", 4, manaOptions.size());
        assertManaOptions("{R}", manaOptions);
        assertManaOptions("{C}{C}{C}{R}[{TitansNestManaCondition}]", manaOptions);
        assertManaOptions("{C}{C}{C}{C}{R}[{XCostManaCondition}]", manaOptions);
        assertManaOptions("{C}{C}{C}{C}{C}{C}{C}{R}[{XCostManaCondition}{TitansNestManaCondition}]", manaOptions);
    }

    @Test
    public void testConditionalManaSoftCounter() {
        addCard(Zone.HAND, playerA, "Fallaji Excavation", 1);
        addCard(Zone.HAND, playerA, "Gigantosaurus", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 5);
        addCard(Zone.HAND, playerB, "Mana Leak");
        addCard(Zone.BATTLEFIELD, playerB, "Island", 2);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Fallaji Excavation");
        castSpell(3, PhaseStep.PRECOMBAT_MAIN, playerA, "Gigantosaurus");
        castSpell(3, PhaseStep.PRECOMBAT_MAIN, playerB, "Mana Leak", "Gigantosaurus");
        setChoice(playerA, true);

        setStrictChooseMode(true);
        setStopAt(3, PhaseStep.POSTCOMBAT_MAIN);
        execute();
        assertPermanentCount(playerA, "Gigantosaurus", 1);
        assertTappedCount("Powerstone Token", true, 3);
    }
    @Test
    public void testConditionalManaCantSoftCounter() {
        addCard(Zone.BATTLEFIELD, playerA, "Jaya Ballard");
        addCard(Zone.HAND, playerA, "Gigantosaurus", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 5);
        addCard(Zone.HAND, playerB, "Mana Leak");
        addCard(Zone.BATTLEFIELD, playerB, "Island", 2);

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "+1: Add");
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN, true);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Gigantosaurus");
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerB, "Mana Leak", "Gigantosaurus");
        setChoice(playerA, true);

        setStrictChooseMode(true);
        setStopAt(3, PhaseStep.POSTCOMBAT_MAIN);
        execute();
        assertPermanentCount(playerA, "Gigantosaurus", 0);
    }

    @Test
    public void testConditionalManaDeduplication() {
        ManaOptions manaOptions = new ManaOptions();

        ConditionalMana originalMana = new ConditionalMana(Mana.GreenMana(1));

        ConditionalMana mana2 = originalMana.copy();
        mana2.addCondition(AdamantCondition.WHITE);

        ConditionalMana mana3 = originalMana.copy();
        mana3.addCondition(AdamantCondition.BLUE);
        ConditionalMana mana3Copy = originalMana.copy();
        mana3Copy.addCondition(AdamantCondition.BLUE);
        mana3Copy.add(Mana.GreenMana(1));

        ConditionalMana mana4 = originalMana.copy();
        mana4.addCondition(AdamantCondition.BLACK);
        ConditionalMana mana4Copy = originalMana.copy();
        mana4Copy.addCondition(AdamantCondition.BLACK);

        manaOptions.add(originalMana);
        manaOptions.add(mana2);
        manaOptions.add(mana3);
        manaOptions.add(mana3Copy); // Added, and should remain since different amount of Green mana
        manaOptions.add(mana4);
        manaOptions.add(mana4Copy); // Adding it to make sure it gets removed

        Assert.assertEquals("Incorrect number of mana", 5, manaOptions.size());
    }

    // https://github.com/magefree/mage/issues/9796
    @Test
    public void testJeganthaYes() {
        addCard(Zone.BATTLEFIELD, playerA, "Jegantha, the Wellspring");
        addCard(Zone.HAND, playerA, "Lost Auramancers");
        addCard(Zone.BATTLEFIELD, playerA, "Plains");
        addCard(Zone.BATTLEFIELD, playerA, "Wastes", 2);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Lost Auramancers");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Lost Auramancers", 1);
    }

    @Test
    public void testJeganthaYes2() {
        addCard(Zone.BATTLEFIELD, playerA, "Jegantha, the Wellspring");
        addCard(Zone.HAND, playerA, "Honden of Infinite Rage");
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 2);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Honden of Infinite Rage");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Honden of Infinite Rage", 1);
    }

    @Test
    public void testJeganthaNo() {
        addCard(Zone.BATTLEFIELD, playerA, "Jegantha, the Wellspring");
        addCard(Zone.HAND, playerA, "Lost Auramancers");
        addCard(Zone.BATTLEFIELD, playerA, "Plains");

        checkPlayableAbility("nope", 1, PhaseStep.PRECOMBAT_MAIN, playerA, "Lost Auramancers", false);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Lost Auramancers", 0);
    }

    // ── Secluded Courtyard ──
    //
    // These pin the ENGINE side of the web client's auto-tap fix: the play_mana prompt now
    // carries a per-source `manaPayableForThisCost` flag, and that flag is derived from
    // exactly the gate exercised here — ConditionalMana.apply for the object being paid
    // (the same call PlayerImpl.canPayMinimumManaCost makes, PlayerImpl.java:3899). The
    // engine and the flag must agree, so the payment rules are asserted directly.

    /**
     * Secluded Courtyard's conditional any-colour mana pays for a creature spell of the
     * chosen type — here an Elf, paid entirely from the Courtyard.
     */
    @Test
    public void testSecludedCourtyardPayableForThisCostChosenTypeCreature() {
        // As Secluded Courtyard enters, choose a creature type.
        // {T}: Add {C}.
        // {T}: Add one mana of any color. Spend this mana only to cast a creature spell of the
        // chosen type or activate an ability of a creature or creature card of the chosen type.
        addCard(Zone.HAND, playerA, "Secluded Courtyard");
        // Creature — Elf Scout {G}
        addCard(Zone.HAND, playerA, "Gladecover Scout");

        playLand(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Secluded Courtyard");
        setChoice(playerA, "Elf"); // the creature type the Courtyard names
        setChoice(playerA, "Green"); // the colour its any-colour mana is spent as

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Gladecover Scout");

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Gladecover Scout", 1);
        // The Courtyard is the only possible source of the {G}, so it must have been tapped.
        assertTapped("Secluded Courtyard", true);
    }

    /**
     * …and it pays for nothing else: "a creature spell of the chosen type" can never be
     * satisfied by a non-creature spell, whichever type was chosen. With the Courtyard the
     * only untapped source, the spell must be unplayable — the restriction is exactly what
     * the auto-tapper relies on the engine to enforce, and it is what the per-source
     * `payableForThisCost` flag reports back.
     * <p>
     * DISABLED 2026-09-28: the OFFER side cannot honor this yet for an
     * UNTAPPED source with more than one mana ability — ManaOptions' union branch rebuilds the
     * options with a plain Mana.add and drops the conditional, so the restricted any-colour land
     * still advertises a castable non-creature spell. Reproduces identically
     * with Cavern of Souls, i.e. it is a PRE-EXISTING ENGINE defect, not
     * this card and not this change.
     * <p>The per-source
     * `payableForThisCost` flag is unaffected: it is evaluated per ability via
     * ConditionalMana.apply and reports false correctly (the autotap harness covers it).
     * Re-enable together with the engine fix.
     */
    @Test // re-enabled with the ManaOptions fix (RED before the fix, GREEN after)
    public void testSecludedCourtyardPayableForThisCostRejectsNonCreature() {
        // As Secluded Courtyard enters, choose a creature type.
        // {T}: Add {C}.
        // {T}: Add one mana of any color. Spend this mana only to cast a creature spell of the
        // chosen type or activate an ability of a creature or creature card of the chosen type.
        addCard(Zone.HAND, playerA, "Secluded Courtyard");
        // Instant {R} — not a creature spell, so the conditional mana may not pay for it
        addCard(Zone.HAND, playerA, "Lightning Bolt");

        playLand(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Secluded Courtyard");
        setChoice(playerA, "Human"); // the chosen type is irrelevant: the spell is not a creature

        checkPlayableAbility("conditional mana is restricted to the chosen creature type",
                1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cast Lightning Bolt", false);

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertHandCount(playerA, "Lightning Bolt", 1);
        assertTapped("Secluded Courtyard", false);
    }

    /**
     * Cavern of Souls twin of the Courtyard case above — same engine shape, second card that
     * a probe reported: an UNTAPPED Cavern of Souls ({T}: Add {C} plus {T}: Add
     * one mana of any colour restricted to creature spells of the chosen type) must not offer a
     * non-creature spell. Before the ManaOptions union fix the union branch dropped the
     * conditional ({C} and {Any} survived as plain mana, conditions gone), so Duress was
     * offered — the "not offered" check below is the discriminating negative control.

     */
    @Test
    public void testCavernOfSoulsUntappedRejectsNonCreatureSpell() {
        // As Cavern of Souls enters the battlefield, choose a creature type.
        // {T}: Add {C}.
        // {T}: Add one mana of any color. Spend this mana only to cast a creature spell of the
        // chosen type, and that spell can't be countered.
        addCard(Zone.HAND, playerA, "Cavern of Souls");
        // Sorcery {B} — not a creature spell, so the conditional mana may not pay for it
        addCard(Zone.HAND, playerA, "Duress");

        playLand(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cavern of Souls");
        setChoice(playerA, "Human"); // the chosen type is irrelevant: the spell is not a creature

        checkPlayableAbility("untapped Cavern of Souls must not offer a non-creature spell",
                1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cast Duress", false);

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertHandCount(playerA, "Duress", 1);
        assertTapped("Cavern of Souls", false);
    }

    /**
     * Positive control for the Cavern twin: a creature spell of the chosen type stays offered
     * and castable off the untapped Cavern's conditional mana (the union must keep the
     * condition, not remove the mana).
     */
    @Test
    public void testCavernOfSoulsUntappedAllowsChosenTypeCreature() {
        addCard(Zone.HAND, playerA, "Cavern of Souls");
        // Elite Vanguard — Creature {W}, subtype Human (of the chosen type)
        addCard(Zone.HAND, playerA, "Elite Vanguard");

        playLand(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cavern of Souls");
        setChoice(playerA, "Human");

        checkPlayableAbility("chosen type creature stays offered", 1, PhaseStep.PRECOMBAT_MAIN,
                playerA, "Cast Elite Vanguard", true);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Elite Vanguard");
        setChoice(playerA, "White"); // the colour its any-colour mana is spent as

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Elite Vanguard", 1);
        assertTapped("Cavern of Souls", true); // the Cavern was the only possible source
    }
}
