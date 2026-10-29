package org.mage.test.game.ends;

import mage.cards.CardSetInfo;
import mage.cards.basiclands.Forest;
import mage.cards.decks.Deck;
import mage.constants.MultiplayerAttackOption;
import mage.constants.RangeOfInfluence;
import mage.constants.Rarity;
import mage.game.Game;
import mage.game.GameEndReason;
import mage.game.GameInfo;
import mage.game.GameOptions;
import mage.game.Table;
import mage.game.TwoPlayerDuel;
import mage.game.match.Match;
import mage.game.match.MatchImpl;
import mage.game.match.MatchOptions;
import mage.game.mulligan.MulliganType;
import mage.game.result.ResultProtos;
import mage.players.Player;
import mage.players.PlayerType;
import mage.players.StubPlayer;
import mage.view.GameEndView;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

/**
 * Pins the end reason of a game closed by a CRITICAL server/game error
 * ({@code GameImpl.endWithTechnicalWinner}).
 *
 * <p>Before the fix the method awarded a winner by life, then priority time
 * left, then <b>random</b>, and recorded nothing: {@code END_GAME_INFO} had no
 * {@code endReason}, so the client rendered a plain "Victory!" for a game that
 * did not play out (measured live 2026-10-21).
 *
 * <p>Covered here, with a real duel and a real Table/Match (no game loop, no
 * network):
 * <ol>
 *   <li>{@code endWithTechnicalWinner} records
 *       {@link GameEndReason#TECHNICAL_ERROR} on the state, and the reason
 *       survives the {@code end()} call the method makes;</li>
 *   <li>the awarded winner keeps the old {@code winnerId}/{@code technicalResult}
 *       semantics (exactly one seat wins, the rest lose, never a draw);</li>
 *   <li>a SEAT's {@link GameEndView} resolves to "technical_error" for both the
 *       awarded winner and the other seat, and the watcher view (no subject
 *       seat) still reports null.</li>
 * </ol>
 *
 * <p>Out of scope: choosing the winner (life/time/random) is unchanged and
 * random at equal life/time, so the assertions never name a seat.
 */
public class TechnicalErrorEndReasonTest {

    private static Deck forestDeck(UUID playerId) {
        Deck deck = new Deck();
        for (int i = 0; i < 40; i++) {
            deck.getCards().add(new Forest(playerId,
                    new CardSetInfo("Forest", "TEST", "1", Rarity.LAND)));
        }
        return deck;
    }

    private static Game newGame() {
        Game game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE,
                MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);
        GameOptions options = new GameOptions();
        options.testMode = true;
        options.skipInitShuffling = true;
        game.setGameOptions(options);
        return game;
    }

    private static void addPlayer(Game game, Player player) {
        Deck deck = forestDeck(player.getId());
        game.loadCards(deck.getCards(), player.getId());
        game.addPlayer(player, deck);
    }

    /** A real match + table so GameEndView can build its MatchView. */
    private static Table newTable(Match match) {
        return new Table(UUID.randomUUID(), "Two Player Duel", "technical-error",
                "test", null,
                Arrays.asList(PlayerType.HUMAN, PlayerType.HUMAN),
                null, match, Collections.<String>emptySet(), false);
    }

    /**
     * Minimal concrete MatchImpl: only the abstract methods this fixture never
     * touches (the same shape LLMSideboardTerminalFailureTest uses).
     */
    private static final class TestMatch extends MatchImpl {

        TestMatch(MatchOptions options) {
            super(options);
        }

        @Override
        public GameInfo createGameInfo(Game game) {
            return null;
        }

        @Override
        public java.util.List<GameInfo> getGamesInfo() {
            return Collections.emptyList();
        }

        @Override
        public void startGame() {
        }

        @Override
        public ResultProtos.MatchProto toProto() {
            return null;
        }
    }

    @Test
    public void technicalWinnerStampsTechnicalErrorAndKeepsWinnerSemantics() {
        Game game = newGame();
        StubPlayer seatA = new StubPlayer("Seat A", RangeOfInfluence.ONE);
        StubPlayer seatB = new StubPlayer("Seat B", RangeOfInfluence.ONE);
        addPlayer(game, seatA);
        addPlayer(game, seatB);

        Assert.assertNull("a fresh game records no reason",
                game.getState().getEndReason());

        game.endWithTechnicalWinner("game thread error: boom");

        // The stamp is on the state AND survives the end() call made inside
        // endWithTechnicalWinner (end() itself does not touch endReason).
        Assert.assertEquals("a critical-error closure must be recorded as a technical error",
                GameEndReason.TECHNICAL_ERROR, game.getState().getEndReason());
        Assert.assertTrue("the game must be over", game.hasEnded());
        Assert.assertFalse("a technical winner is never a draw", game.isADraw());

        // winnerId/technicalResult semantics are unchanged: exactly one winner,
        // every other seat lost, and the engine's own winner string names it.
        int winners = 0;
        int losers = 0;
        String winnerName = null;
        for (Player player : game.getPlayers().values()) {
            Assert.assertFalse("technicalResult is exclusive per seat (wins xor loses)",
                    player.hasWon() && player.hasLost());
            if (player.hasWon()) {
                winners++;
                winnerName = player.getName();
            }
            if (player.hasLost()) {
                losers++;
            }
        }
        Assert.assertEquals("exactly one seat is awarded the technical win", 1, winners);
        Assert.assertEquals("every other seat is marked lost", 1, losers);
        Assert.assertEquals("getWinner reports the awarded seat",
                "Player " + winnerName + " is the winner", game.getWinner());
    }

    @Test
    public void gameEndViewReportsTechnicalErrorForEachSeat() {
        Game game = newGame();
        StubPlayer seatA = new StubPlayer("Seat A", RangeOfInfluence.ONE);
        StubPlayer seatB = new StubPlayer("Seat B", RangeOfInfluence.ONE);
        addPlayer(game, seatA);
        addPlayer(game, seatB);

        MatchOptions options = new MatchOptions("technical-error", "Two Player Duel", false);
        options.setWinsNeeded(1);
        Match match = new TestMatch(options);
        Table table = newTable(match);
        match.addPlayer(seatA, forestDeck(seatA.getId()));
        match.addPlayer(seatB, forestDeck(seatB.getId()));

        game.endWithTechnicalWinner("game thread error: boom");

        // The END_GAME_INFO payload is built per seat (GameSessionPlayer
        // .endGameInfo -> new GameEndView(state, game, playerId, table)); the
        // wire value must be the new vocabulary for BOTH seats, so neither sees
        // an ordinary victory/defeat dialog copy.
        GameEndView viewA = new GameEndView(game.getState(), game, seatA.getId(), table);
        GameEndView viewB = new GameEndView(game.getState(), game, seatB.getId(), table);
        Assert.assertEquals(GameEndReason.TECHNICAL_ERROR, viewA.getEndReason());
        Assert.assertEquals(GameEndReason.TECHNICAL_ERROR, viewB.getEndReason());
        Assert.assertTrue("the engine's technical result still reaches the views",
                viewA.hasWon() ^ viewB.hasWon());

        // A watcher has no subject seat: the field stays null, as before.
        GameEndView watcher = new GameEndView(game.getState(), game, null, table);
        Assert.assertNull("a watcher must not get a seat's end reason", watcher.getEndReason());
    }

    /** The vocabulary must match the clients' literal. */
    @Test
    public void wireVocabularyIsExact() {
        Assert.assertEquals("technical_error", GameEndReason.TECHNICAL_ERROR);
        Assert.assertEquals(GameEndReason.TECHNICAL_ERROR,
                GameEndReason.resolve(false, false, false, GameEndReason.TECHNICAL_ERROR));
    }

    /**
     * A played-out end (nothing recorded) must keep reporting "unknown" — the
     * additive value changes nothing for ordinary games.
     */
    @Test
    public void playedOutEndStillReportsUnknown() {
        Assert.assertEquals(GameEndReason.UNKNOWN,
                GameEndReason.resolve(false, false, false, null));
        Assert.assertNotEquals(GameEndReason.TECHNICAL_ERROR,
                GameEndReason.resolve(false, false, false, null));
    }
}
