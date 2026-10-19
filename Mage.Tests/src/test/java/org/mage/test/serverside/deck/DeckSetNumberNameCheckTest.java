package org.mage.test.serverside.deck;

import mage.cards.decks.CardNameUtil;
import mage.cards.decks.Deck;
import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.cards.repository.CardScanner;
import mage.game.GameException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mage.test.serverside.base.MageTestPlayerBase;

/**
 * A [SET:NUM] deck line must never silently import a DIFFERENT card than the line names

 * <p>
 * Valid-code-wrong-card pairs used below (verified against the card DB):
 * [M10:150] is Panic Attack, [M10:146] is Lightning Bolt,
 * [M10:211] is Gorgon Flail, [M10:217] is Pithing Needle.
 */
public class DeckSetNumberNameCheckTest extends MageTestPlayerBase {

    @Before
    public void setUp() {
        CardScanner.scan();
    }

    private DeckCardLists prepareDeck(DeckCardInfo... cards) {
        DeckCardLists deckSource = new DeckCardLists();
        deckSource.setName("test deck");
        for (DeckCardInfo card : cards) {
            deckSource.getCards().add(card);
        }
        return deckSource;
    }

    private CardInfo findCard(String setCode, String cardNumber) {
        CardInfo cardInfo = CardRepository.instance.findCard(setCode, cardNumber);
        Assert.assertNotNull("test fixture card is missing from the DB: [" + setCode + ":" + cardNumber + "]",
                cardInfo);
        return cardInfo;
    }

    // ── 1. correct [SET:NUM] lines keep importing the right card ──

    @Test
    public void test_CorrectSetNumberStillImportsTheNamedCard() throws GameException {
        Deck deck = Deck.load(prepareDeck(
                new DeckCardInfo("Lightning Bolt", "146", "M10"),
                new DeckCardInfo("Grizzly Bears", "129", "S99")
        ), false, false);

        Assert.assertEquals(2, deck.getCards().size());
        Assert.assertTrue("Lightning Bolt must be imported",
                deck.getCards().stream().anyMatch(card -> "Lightning Bolt".equals(card.getName())
                        && "146".equals(card.getCardNumber())));
        Assert.assertTrue("Grizzly Bears must be imported",
                deck.getCards().stream().anyMatch(card -> "Grizzly Bears".equals(card.getName())));
    }

    @Test
    public void test_MultiNameCardsKeepImportingByFullNameOrHalfName() throws GameException {
        // split card written as "A // B" and by either half — must all still work
        for (String lineName : new String[]{"Wear // Tear", "Wear", "Tear"}) {
            Deck deck = Deck.load(prepareDeck(
                    new DeckCardInfo(lineName, "135", "DGM")
            ), false, false);
            Assert.assertEquals("failed for line name: " + lineName, 1, deck.getCards().size());
            Assert.assertEquals("failed for line name: " + lineName,
                    "Wear // Tear", deck.getCards().iterator().next().getName());
        }
    }

    @Test
    public void test_SecondSideNamesKeepImporting() throws GameException {
        // transform card: name it by front side or by back side
        for (String lineName : new String[]{"Delver of Secrets", "Insectile Aberration"}) {
            Deck deck = Deck.load(prepareDeck(
                    new DeckCardInfo(lineName, "51", "ISD")
            ), false, false);
            Assert.assertEquals("failed for line name: " + lineName, 1, deck.getCards().size());
            Assert.assertEquals("failed for line name: " + lineName,
                    "Delver of Secrets", deck.getCards().iterator().next().getName());
        }
    }

    @Test
    public void test_NameOnlyLinesAndLinesWithoutNamesKeepWorking() throws GameException {
        DeckCardLists source = prepareDeck(
                new DeckCardInfo("Lightning Bolt", "", ""), // no code, resolved by name
                new DeckCardInfo("Lightning Bolt", "146", "M10")
        );
        // layout-only entries carry an empty name and must not be treated as a mismatch
        source.getCards().add(new DeckCardInfo("", "146", "M10", 1));

        Deck deck = Deck.load(source, false, false);
        Assert.assertEquals(3, deck.getCards().size());
    }

    @Test
    public void test_UnknownCardStillFailsWithTheCardNotFoundError() {
        try {
            Deck.load(prepareDeck(new DeckCardInfo("Excavate", "288", "M10")), false, false);
            Assert.fail("expected GameException for a card that does not exist in the set");
        } catch (GameException e) {
            Assert.assertTrue("unexpected message: " + e.getMessage(),
                    e.getMessage().contains("Card not found") || e.getMessage().contains("Excavate"));
        }
    }

    // ── 2. a [SET:NUM] code pointing at another card must not import silently ──

    @Test
    public void test_WrongCardNumberIsRejectedNotSilentlyImported() {
        // the exact repro: [M10:150] is Panic Attack, not Lightning Bolt
        try {
            Deck deck = Deck.load(prepareDeck(
                    new DeckCardInfo("Lightning Bolt", "150", "M10")
            ), false, false);
            Assert.fail("wrong-card import must fail loudly, but the deck loaded with: "
                    + deck.getCards().iterator().next().getName());
        } catch (GameException e) {
            Assert.assertTrue("error must name both the line and the resolved card, was: " + e.getMessage(),
                    e.getMessage().contains("Lightning Bolt")
                            && e.getMessage().contains("Panic Attack")
                            && e.getMessage().contains("M10")
                            && e.getMessage().contains("150"));
        }
    }

    @Test
    public void test_WrongCardNumberIsRejectedEvenWithIgnoreErrors() {
        // ignoreErrors tolerates UNKNOWN cards only — never the WRONG card
        try {
            Deck.load(prepareDeck(
                    new DeckCardInfo("Mountain", "211", "M10") // M10:211 is Gorgon Flail
            ), true, false);
            Assert.fail("wrong-card import must fail even with ignoreErrors=true");
        } catch (GameException e) {
            Assert.assertTrue("error must name the resolved card, was: " + e.getMessage(),
                    e.getMessage().contains("Gorgon Flail"));
        }
    }

    @Test
    public void test_WrongCardNumberInSideboardIsRejected() {
        DeckCardLists source = new DeckCardLists();
        source.setName("sideboard test");
        source.getCards().add(new DeckCardInfo("Lightning Bolt", "146", "M10"));
        source.getSideboard().add(new DeckCardInfo("Lightning Bolt", "150", "M10"));

        try {
            Deck.load(source, false, false);
            Assert.fail("wrong-card import in the sideboard must fail loudly");
        } catch (GameException e) {
            Assert.assertTrue("error must name the resolved card, was: " + e.getMessage(),
                    e.getMessage().contains("Panic Attack"));
        }
    }

    // ── 3. the shared name check itself ──

    @Test
    public void test_NameCheckHelperAcceptsVariantsAndRejectsForeignCards() {
        CardInfo lightningBolt = findCard("M10", "146");
        Assert.assertTrue(CardNameUtil.isSameCardName("Lightning Bolt", lightningBolt));
        Assert.assertTrue("case-insensitive match must pass",
                CardNameUtil.isSameCardName("lightning bolt", lightningBolt));
        Assert.assertFalse("a different card must not match",
                CardNameUtil.isSameCardName("Panic Attack", lightningBolt));
        Assert.assertFalse("empty name has nothing to match", CardNameUtil.isSameCardName("", lightningBolt));

        CardInfo wearTear = findCard("DGM", "135");
        for (String name : new String[]{"Wear // Tear", "Wear", "Tear", "wear // tear"}) {
            Assert.assertTrue("must accept variant: " + name, CardNameUtil.isSameCardName(name, wearTear));
        }
        Assert.assertFalse("must reject a foreign card name",
                CardNameUtil.isSameCardName("Toil // Trouble", wearTear));

        CardInfo shatterskull = findCard("ZNR", "161");
        Assert.assertTrue(CardNameUtil.isSameCardName("Shatterskull Smashing", shatterskull));
        Assert.assertTrue(CardNameUtil.isSameCardName("Shatterskull, the Hammer Pass", shatterskull));
    }

    @Test
    public void test_WrongCardErrorNamesBothCardsAndTheCode() {
        CardInfo panicAttack = findCard("M10", "150");
        String error = CardNameUtil.buildWrongCardError("Lightning Bolt", panicAttack);
        Assert.assertTrue(error, error.contains("Lightning Bolt"));
        Assert.assertTrue(error, error.contains("Panic Attack"));
        Assert.assertTrue(error, error.contains("[M10:150]"));
    }
}
