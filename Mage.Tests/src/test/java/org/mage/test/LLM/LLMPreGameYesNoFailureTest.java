package org.mage.test.LLM;

import mage.cards.CardSetInfo;
import mage.cards.basiclands.Forest;
import mage.cards.decks.Deck;
import mage.constants.MultiplayerAttackOption;
import mage.constants.Outcome;
import mage.constants.RangeOfInfluence;
import mage.constants.Rarity;
import mage.game.Game;
import mage.game.GameEndReason;
import mage.game.GameOptions;
import mage.game.TwoPlayerDuel;
import mage.game.mulligan.MulliganType;
import mage.player.llm.LLMPlayer;
import mage.player.llm.LLMResponse;
import mage.players.Player;
import mage.players.StubPlayer;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pins {@code LLMPlayer.chooseUse}'s pre-game guard (the residual of the
 * pre-game target fix).
 *
 * <p>Yes/no asks made BEFORE the priority loop — {@code GameImpl.init}'s
 * companion question — run outside the game's error/auto-restore handling. A
 * terminal provider failure there used to escape {@code GameImpl.start} to
 * {@code GameController.endGameWithError} → {@code endWithTechnicalWinner}, a
 * winner by life/time/random. The guard makes the seat forfeit instead:
 * endReason {@code llm_failure}, the opponent wins, chooseUse returns a
 * benign "declined".
 *
 * <p>Two behaviours must NOT change (also pinned here):
 * <ul>
 *   <li>mid-game yes/no failures still propagate — the play-on contract;</li>
 *   <li>the guard wraps ONLY the provider call, so the "LLM yes/no:
 *       unrecognized response" RuntimeException thrown later by the parser is
 *       structurally outside it and cannot be swallowed (it is a parser crash,
 *       not a provider failure — see the code comment at the guard).</li>
 * </ul>
 *
 * <p>Focused fixture: a real duel whose seats are put into the same pre-game
 * window {@code GameImpl.init} uses ({@code beginTurn} per seat, which fills
 * the range data the concede unwind reads), with the LLM call ladder stubbed.
 * No game loop, no network, and the guard is exercised through the public
 * chooseUse entry.
 */
public class LLMPreGameYesNoFailureTest {

    /** The exhausted-ladder signature `thrownAfterFullLadder` classifies as terminal. */
    private static final String TERMINAL_FAILURE =
            "LLM call failed after 5 attempts. Last error: "
            + "java.net.ConnectException: Connection refused";

    private static final String QUESTION = "Use Leyline of the Void as your companion?";

    /** LLM seat whose provider call always raises the terminal ladder failure. */
    private static final class FailingYesNoPlayer extends LLMPlayer {

        private final String failure;
        private final List<String> promptTypes = new ArrayList<>();

        FailingYesNoPlayer(String name, String failure) {
            super(name, RangeOfInfluence.ONE, 0);
            this.failure = failure;
            getLLMConfig()
                    .apiEndpoint("http://127.0.0.1:9/v1/chat/completions")
                    .model("stub-model")
                    .temperature(0.0)
                    .timeoutSeconds(1)
                    .deckAssessmentEnabled(false)
                    .verboseLogging(false);
        }

        /** No init-time network probes: the fixture stubs the ladder below. */
        @Override
        protected boolean isTestHarness() {
            return true;
        }

        @Override
        protected LLMResponse chatAndRecordWithRetry(String prompt, String compactSummary,
                                                     String promptType, Integer turn,
                                                     String phaseStep) {
            promptTypes.add(promptType);
            throw new RuntimeException(failure);
        }
    }

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

    /**
     * Reproduce {@code GameImpl.init}'s pre-game window: it calls
     * {@code player.beginTurn(this)} for every seat before the pre-game
     * questions. That fills {@code inRange}, which the concede unwind needs —
     * {@code won()} asks {@code game.getOpponents()} and the range check throws
     * on an unfilled range. Without this the fixture is NOT the real pre-game
     * state.
     */
    private static void openPreGameWindow(Game game) {
        for (Player player : game.getPlayers().values()) {
            player.beginTurn(game);
        }
    }

    @Test
    public void preGameTerminalFailureConcedesAndDeclines() {
        Game game = newGame();
        FailingYesNoPlayer llm = new FailingYesNoPlayer("LLM seat", TERMINAL_FAILURE);
        addPlayer(game, llm);
        StubPlayer opponent = new StubPlayer("Opponent", RangeOfInfluence.ONE);
        addPlayer(game, opponent);

        Assert.assertTrue("fixture precondition: the yes/no ask is pre-game",
                game.getState().getPriorityPlayerId() == null);
        openPreGameWindow(game);

        boolean answer = llm.chooseUse(Outcome.Benefit, QUESTION, null, game);

        Assert.assertFalse("a terminal pre-game failure returns the benign decline", answer);
        Assert.assertEquals("the prompt went through the yes/no path",
                "yesno", llm.promptTypes.get(0));
        Assert.assertEquals("the seat's provider failure is the recorded reason",
                GameEndReason.LLM_FAILURE, game.getState().getEndReason());
        Assert.assertTrue("the game ended", game.hasEnded());
        Assert.assertTrue("the failing seat loses", llm.hasLost());
        Assert.assertFalse("the opponent must not be marked lost", opponent.hasLost());
        // The remaining seat wins through the engine's ordinary concede unwind
        // (checkIfGameIsOver -> player.won). GameImpl.play() is what turns that
        // into winnerId/!isADraw at the end of a real start(); this fixture
        // calls chooseUse directly, so it asserts the seat outcome, not the
        // match-level bookkeeping the game loop owns.
        Assert.assertTrue("the opponent wins — no random technical winner", opponent.hasWon());
    }

    @Test
    public void midGameTerminalFailureStillPropagates() {
        Game game = newGame();
        FailingYesNoPlayer llm = new FailingYesNoPlayer("LLM seat", TERMINAL_FAILURE);
        addPlayer(game, llm);
        addPlayer(game, new StubPlayer("Opponent", RangeOfInfluence.ONE));

        // The priority loop has started: this is a MID-GAME yes/no ask, which
        // endWithTechnicalWinner handles.
        game.getState().setPriorityPlayerId(llm.getId());

        try {
            llm.chooseUse(Outcome.Benefit, QUESTION, null, game);
            Assert.fail("a mid-game terminal failure must propagate (play on), not forfeit");
        } catch (RuntimeException e) {
            Assert.assertEquals("the propagated failure is the stubbed one",
                    TERMINAL_FAILURE, e.getMessage());
        }

        Assert.assertNull("no reason may be recorded by the pre-game guard",
                game.getState().getEndReason());
        Assert.assertFalse("the game must not end in the pre-game guard", game.hasEnded());
        Assert.assertFalse("the seat must not forfeit mid-game", llm.hasLost());
    }
}
