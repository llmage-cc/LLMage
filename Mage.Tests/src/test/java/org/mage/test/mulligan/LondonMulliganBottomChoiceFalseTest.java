package org.mage.test.mulligan;

import mage.abilities.Ability;
import mage.cards.CardSetInfo;
import mage.cards.Cards;
import mage.cards.basiclands.Forest;
import mage.cards.decks.Deck;
import mage.constants.MultiplayerAttackOption;
import mage.constants.Outcome;
import mage.constants.RangeOfInfluence;
import mage.constants.Rarity;
import mage.game.Game;
import mage.game.GameOptions;
import mage.game.TwoPlayerDuel;
import mage.game.mulligan.LondonMulligan;
import mage.players.Player;
import mage.players.StubPlayer;
import mage.target.Target;
import org.junit.Assert;
import org.junit.Test;

import java.util.UUID;

/**
 * Pins the London mulligan bottom-card loop against a seat that does NOT choose
 * a card (audit D3).
 *
 * <p>{@code LondonMulligan} used to ignore {@code player.chooseTarget(...)}'s
 * boolean and push {@code target.getTargets()} (empty on a false return) onto
 * the bottom, then re-ask the same seat while the hand stayed oversized — an
 * unbounded re-prompt loop for any live seat that keeps answering the same way
 * (an LLM seat whose answer does not parse returns false after its own retries;
 * the loop only stopped if the concede flipped {@code canRespond()}).
 *
 * <p>The loop now stops on a false/empty return, never picks a card for the
 * seat (AGENTS.md: no graceful fallbacks that hide a failure) and records the
 * failure on the game log. This fixture drives the REAL phase ({@code
 * GameImpl.init} → {@code Mulligan.executeMulliganPhase} →
 * {@code LondonMulligan.mulligan}) with a stub seat and no server fixture; the
 * stub fails loudly if the loop asks a second time, so the pre-fix behaviour is
 * a fast failure instead of a hang.
 */
public class LondonMulliganBottomChoiceFalseTest {

    private static final int STARTING_HAND = 7;
    private static final int DECK_SIZE = 40;

    /**
     * Stub seat that takes exactly one mulligan and then declines to name a card
     * for the bottom. It counts the ask and the bottom move so the test can tell
     * "stopped" apart from "asked again" and from "the engine picked for me".
     */
    private static final class BottomRefusingPlayer extends StubPlayer {

        private int mulligansTaken;
        private int bottomAsks;
        private int bottomMoves;

        BottomRefusingPlayer(String name) {
            super(name, RangeOfInfluence.ONE);
        }

        @Override
        public boolean chooseMulligan(Game game) {
            if (mulligansTaken == 0) {
                mulligansTaken++;
                return true;
            }
            return false;   // keep the hand once it has mulliganed
        }

        @Override
        public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game) {
            bottomAsks++;
            if (bottomAsks > 1) {
                throw new IllegalStateException("the London mulligan bottom loop asked again"
                        + " after a false return (" + bottomAsks + " asks) — it is re-prompting"
                        + " the same seat with the same oversized hand instead of stopping");
            }
            return false;   // did not choose: nothing is added to the target
        }

        @Override
        public boolean putCardsOnBottomOfLibrary(Cards cards, Game game, Ability source, boolean anyOrder) {
            bottomMoves++;
            return super.putCardsOnBottomOfLibrary(cards, game, source, anyOrder);
        }
    }

    private static void addPlayer(Game game, Player player) {
        Deck deck = new Deck();
        for (int i = 0; i < DECK_SIZE; i++) {
            deck.getCards().add(new Forest(player.getId(),
                    new CardSetInfo("Forest", "TEST", "1", Rarity.LAND)));
        }
        game.loadCards(deck.getCards(), player.getId());
        game.addPlayer(player, deck);
    }

    @Test
    public void falseBottomChoiceStopsTheLoopWithoutPickingACard() {
        Game game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE,
                new LondonMulligan(0), 60, 20, STARTING_HAND) {
            @Override
            protected void play(UUID nextPlayerId) {
                // no game loop: this test is over once the mulligan phase is done
            }
        };
        GameOptions options = new GameOptions();
        options.skipInitShuffling = true;
        game.setGameOptions(options);

        BottomRefusingPlayer refusing = new BottomRefusingPlayer("Refusing seat");
        addPlayer(game, refusing);
        addPlayer(game, new StubPlayer("Opponent", RangeOfInfluence.ONE));

        // Real pre-game path: init declares the mulligan, re-draws, and asks for
        // the bottom card. A second ask means the loop did not stop.
        game.start(refusing.getId());

        Assert.assertEquals("the seat that did not choose must be asked exactly once",
                1, refusing.bottomAsks);
        Assert.assertEquals("the engine must never choose a bottom card for the seat",
                0, refusing.bottomMoves);
        Assert.assertEquals("the hand stays oversized — the failure is visible, not papered over",
                STARTING_HAND, refusing.getHand().size());
        Assert.assertEquals("the bottom step must not move a card to the library",
                DECK_SIZE - STARTING_HAND, refusing.getLibrary().size());
    }
}
