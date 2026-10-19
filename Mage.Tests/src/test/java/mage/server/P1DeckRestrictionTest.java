package mage.server;

import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.P1DeckPool;
import mage.game.Seat;
import mage.players.PlayerType;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for the server-side enforcement gate
 * ({@link P1DeckRestriction}). Lives in package {@code mage.server} to reach the
 * package-private gate directly, exactly as {@code TableController} calls it.
 *
 * <p>This is the evidence that the authoritative decision — the same method the
 * join/sideboard/botgame paths invoke — accepts the 55 training lists and rejects
 * everything else for a P1 seat, and leaves every other seat type untouched.
 */
public class P1DeckRestrictionTest {

    private static DeckCardLists trainingDeck() {
        return P1DeckPool.instance().defaultDeck();
    }

    private static DeckCardLists offPoolDeck() {
        DeckCardLists deck = new DeckCardLists();
        deck.setName("Hand-Built Junk");
        deck.getCards().add(new DeckCardInfo("Mountain", "", "", 40));
        deck.getCards().add(new DeckCardInfo("Grizzly Bears", "", "", 20));
        return deck;
    }

    @Test
    public void testTrainingDeckAcceptedForP1Seat() {
        Seat seat = new Seat(PlayerType.COMPUTER_P1);
        String reason = P1DeckRestriction.rejectReason(seat, trainingDeck());
        Assert.assertNull("a training deck must be accepted for a P1 seat", reason);
    }

    @Test
    public void testOffPoolDeckRejectedForP1Seat() {
        Seat seat = new Seat(PlayerType.COMPUTER_P1);
        String reason = P1DeckRestriction.rejectReason(seat, offPoolDeck());
        Assert.assertNotNull("an off-pool deck must be rejected for a P1 seat", reason);
        Assert.assertTrue("rejection must name the training pool",
                reason.contains("55") && reason.toLowerCase().contains("training"));
    }

    @Test
    public void testOtherSeatTypesUnaffected() {
        // The restriction is per-SEAT: human / LLM / MAD seats keep full deck freedom.
        for (PlayerType type : new PlayerType[]{
                PlayerType.HUMAN, PlayerType.COMPUTER_LLM, PlayerType.COMPUTER_MAD}) {
            Seat seat = new Seat(type);
            String reason = P1DeckRestriction.rejectReason(seat, offPoolDeck());
            Assert.assertNull(type + " seat must keep full deck freedom", reason);
        }
    }

    @Test
    public void testEscapeHatchDisabledByDefault() {
        Assert.assertFalse("the experimental escape hatch must be OFF by default",
                P1DeckPool.isNonTrainingAllowed());
    }

    @Test
    public void testEscapeHatchLiftsRestriction() {
        Seat seat = new Seat(PlayerType.COMPUTER_P1);
        System.setProperty(P1DeckPool.ALLOW_NON_TRAINING_PROPERTY, "true");
        try {
            Assert.assertTrue(P1DeckPool.isNonTrainingAllowed());
            Assert.assertNull("with the escape hatch ON an off-pool deck is allowed",
                    P1DeckRestriction.rejectReason(seat, offPoolDeck()));
        } finally {
            System.clearProperty(P1DeckPool.ALLOW_NON_TRAINING_PROPERTY);
        }
        // And the restriction is back once the property is cleared.
        Assert.assertNotNull(P1DeckRestriction.rejectReason(seat, offPoolDeck()));
    }

    @Test
    public void testLogLineNamesTheRejection() {
        Seat seat = new Seat(PlayerType.COMPUTER_P1);
        DeckCardLists deck = offPoolDeck();
        String reason = P1DeckRestriction.rejectReason(seat, deck);
        Assert.assertNotNull(reason);
        String line = P1DeckRestriction.logLine("joinTable", "AI-0", java.util.UUID.randomUUID(), deck);
        Assert.assertTrue("log line must name the restriction", line.startsWith("P1 deck restriction:"));
        Assert.assertTrue("log line must name the seat", line.contains("COMPUTER_P1"));
        Assert.assertTrue("log line must state the pool size", line.contains("55 training decks"));
    }
}
