package org.mage.test.LLM;

import mage.cards.CardSetInfo;
import mage.cards.basiclands.Forest;
import mage.cards.decks.Deck;
import mage.constants.MultiplayerAttackOption;
import mage.constants.RangeOfInfluence;
import mage.constants.Rarity;
import mage.game.Game;
import mage.game.GameEndReason;
import mage.game.GameOptions;
import mage.game.TwoPlayerDuel;
import mage.game.mulligan.MulliganType;
import mage.player.llm.LLMPlayer;
import mage.player.llm.LLMResponse;
import mage.players.StubPlayer;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pins the endgame of a terminal LLM provider failure during the game's
 * STARTING-PLAYER choice — the `choosingPlayer.choose(...)` call inside
 * `GameImpl.init`, reached from `GameImpl.start`.
 *
 * <p>That choice runs BEFORE the priority loop, so it is covered neither by
 * `LLMPlayer.passPriorityOnUnrecoverable` (priority only) nor by the engine's
 * error/auto-restore handling: the failure escaped `GameImpl.start` to
 * `GameWorker`, and `GameController.endGameWithError` closed the game with
 * `endWithTechnicalWinner` — a winner picked by life/time/<b>random</b> with NO
 * `endReason` (measured live 2026-10-21, wave-1021-actionable anomaly A4). The
 * winner's dialog was a plain "Victory!".
 *
 * <p>Target selection has no legal "pass", so a terminal provider failure
 * there must end the game as this seat's forfeit: `endReason = llm_failure`,
 * the failing seat loses, the opponent wins — no technical winner.
 *
 * <p>Focused fixture (no game loop, no network): a real {@code TwoPlayerDuel}
 * whose LLM seat is asked for the starting player, with the LLM call ladder
 * stubbed to raise the exact failure shape under test.
 */
public class LLMInitTargetFailureEndgameTest {

    /** The exhausted-ladder signature `thrownAfterFullLadder` classifies as terminal. */
    private static final String TERMINAL_FAILURE =
            "LLM call failed after 5 attempts. Last error: "
            + "java.net.ConnectException: Connection refused";

    /** A retryable failure — the ladder still owns it, so no forfeit may be recorded. */
    private static final String TRANSIENT_FAILURE = "LLM returned empty answer (attempt 1)";

    /** LLM seat whose provider call always fails with the given failure shape. */
    private static final class FailingPlayer extends LLMPlayer {

        private final String failure;
        private final List<String> promptTypes = new ArrayList<>();
        private final List<String> prompts = new ArrayList<>();

        FailingPlayer(String name, String failure) {
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
            prompts.add(prompt);
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

    private static void addPlayer(Game game, mage.players.Player player) {
        Deck deck = forestDeck(player.getId());
        game.loadCards(deck.getCards(), player.getId());
        game.addPlayer(player, deck);
    }

    @Test
    public void startingPlayerTargetFailureEndsAsLlmForfeit() {
        Game game = newGame();

        FailingPlayer llm = new FailingPlayer("LLM seat", TERMINAL_FAILURE);
        addPlayer(game, llm);
        StubPlayer opponent = new StubPlayer("Opponent", RangeOfInfluence.ONE);
        addPlayer(game, opponent);

        try {
            // The LLM seat is the choosing player → GameImpl.init asks IT for the
            // starting player (the exact live path).
            game.start(llm.getId());
        } catch (RuntimeException e) {
            Assert.assertTrue("the failure must come from the starting-player target"
                            + " prompt (LLMPlayer.chooseTarget), saw prompts: " + llm.promptTypes
                            + " / " + llm.prompts + " ; escaped: " + e,
                    llm.promptTypes.contains("target")
                            && llm.prompts.get(0).contains("starting player"));
            Assert.fail("BASE defect: the terminal LLM failure escaped GameImpl.start,"
                    + " so GameController.endGameWithError would close the game with"
                    + " endWithTechnicalWinner (random winner, no endReason): " + e);
        }

        Assert.assertEquals("the starting-player choice is the failing prompt",
                "target", llm.promptTypes.get(0));
        Assert.assertTrue("the failing prompt must be the starting-player choice, was: "
                        + llm.prompts.get(0),
                llm.prompts.get(0).contains("starting player"));

        Assert.assertEquals("a terminal LLM failure in the starting-player choice must be"
                        + " recorded as the seat's LLM forfeit",
                GameEndReason.LLM_FAILURE, game.getState().getEndReason());
        Assert.assertTrue("the game must have ended", game.hasEnded());
        Assert.assertTrue("the failing LLM seat loses", llm.hasLost());
        Assert.assertFalse("the opponent must not be marked lost", opponent.hasLost());
        Assert.assertFalse("a forfeit is not a draw", game.isADraw());
        Assert.assertEquals("the opponent wins — no random technical winner",
                "Player " + opponent.getName() + " is the winner", game.getWinner());
    }

    /**
     * Scope pin: only TERMINAL failures forfeit. A retryable one is still the
     * ladder's (or, for an engine exception, the game's) to handle — the guard
     * must rethrow unchanged, so no forfeit is recorded and no silent fallback
     * target is invented.
     */
    @Test
    public void transientPreGameFailureDoesNotForfeit() {
        Game game = newGame();

        FailingPlayer llm = new FailingPlayer("LLM seat", TRANSIENT_FAILURE);
        addPlayer(game, llm);
        addPlayer(game, new StubPlayer("Opponent", RangeOfInfluence.ONE));

        try {
            game.start(llm.getId());
            Assert.fail("a retryable pre-game failure must still propagate (the ladder"
                    + " owns it) instead of forfeiting the match");
        } catch (RuntimeException e) {
            Assert.assertEquals("the propagated failure is the stubbed one",
                    TRANSIENT_FAILURE, e.getMessage());
        }

        Assert.assertNull("a retryable failure must not record an end reason",
                game.getState().getEndReason());
        Assert.assertFalse("the LLM seat must not be marked lost", llm.hasLost());
        Assert.assertFalse("the game must not have ended", game.hasEnded());
    }
}
