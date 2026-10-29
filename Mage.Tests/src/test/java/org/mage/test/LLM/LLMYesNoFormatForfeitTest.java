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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Pins the strict yes/no contract: a strict yes/no prompt gets up to TWO explicit
 * "reply with exactly yes or no" corrections, and a seat that still answers prose
 * FORFEITS the game
 * instead of the engine awarding the opponent a technical winner by life/time/
 * random.
 *
 * <p>Why this test exists: the 2026-10-25 tournament final asked a one-word Dredge
 * question, the model answered prose, the single generic correction was exhausted,
 * the parser threw, and {@code endWithTechnicalWinner} decided the title 2-0 instead
 * of 1-1.
 *
 * <p>The four cases are the acceptance shape:
 * <ul>
 *   <li>an exact answer the first time is taken as-is — no correction call;</li>
 *   <li>prose, then an exact answer, proceeds after exactly ONE explicit nudge;</li>
 *   <li>prose after both nudges forfeits the seat: endReason
 *       {@code llm_format_forfeit}, the seat loses, the opponent wins the game
 *       through the engine's ordinary concede unwind;</li>
 *   <li>mid-game (the window the incident happened in) the forfeit does NOT take
 *       the {@code endWithTechnicalWinner} path — no throw, no
 *       {@code technical_error} reason — and the queued concede still loses the
 *       seat once the priority loop processes it.</li>
 * </ul>
 *
 * <p>Focused fixture: a real duel with the LLM call ladder stubbed (answers come
 * from a script). The pre-game window is reproduced exactly as
 * {@code GameImpl.init} opens it ({@code beginTurn} per seat, which fills the
 * in-range data the concede unwind needs).
 */
public class LLMYesNoFormatForfeitTest {

    private static final String QUESTION = "Dredge Life from the Loam?";

    /** The live off-format answer shape: reasoning instead of one word. */
    private static final String PROSE = "Let me think about this. I have 4 cards in hand, the graveyard "
            + "has lands presumably, and dredging Loam mills 3 and returns Loam to hand — I already have "
            + "enough lands, so I would rather draw a fresh card.";

    /**
     * LLM seat driven by a fixed answer script. Every model call goes through the
     * real retry entry point, so corrections are counted exactly as production
     * counts them.
     */
    private static final class ScriptedYesNoPlayer extends LLMPlayer {

        private final Deque<String> answers = new ArrayDeque<>();
        private final List<String> prompts = new ArrayList<>();
        private final List<String> promptTypes = new ArrayList<>();

        ScriptedYesNoPlayer(String name, String... script) {
            super(name, RangeOfInfluence.ONE, 0);
            for (String answer : script) {
                answers.add(answer);
            }
            getLLMConfig()
                    .apiEndpoint("http://127.0.0.1:9/v1/chat/completions")
                    .model("stub-model")
                    .temperature(0.0)
                    .timeoutSeconds(1)
                    .deckAssessmentEnabled(false)
                    .verboseLogging(false);
        }

        /** No init-time network probes: the script stubs the ladder below. */
        @Override
        protected boolean isTestHarness() {
            return true;
        }

        @Override
        protected LLMResponse chatAndRecordWithRetryWithEffort(String prompt, String compactSummary,
                                                               String promptType, Integer turn,
                                                               String phaseStep, String effortOverride) {
            prompts.add(prompt);
            promptTypes.add(promptType);
            String answer = answers.isEmpty() ? PROSE : answers.poll();
            return new LLMResponse("stub thinking", answer);
        }

        int calls() {
            return prompts.size();
        }

        String promptAt(int index) {
            return prompts.get(index);
        }

        String promptTypeAt(int index) {
            return promptTypes.get(index);
        }

        int unscriptedAnswersLeft() {
            return answers.size();
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

    /** Reproduce {@code GameImpl.init}'s pre-game window (fills {@code inRange}). */
    private static void openPreGameWindow(Game game) {
        for (Player player : game.getPlayers().values()) {
            player.beginTurn(game);
        }
    }

    @Test
    public void exactAnswerFirstTimeIsTakenWithoutACorrection() {
        Game game = newGame();
        ScriptedYesNoPlayer llm = new ScriptedYesNoPlayer("LLM seat", "yes");
        addPlayer(game, llm);
        addPlayer(game, new StubPlayer("Opponent", RangeOfInfluence.ONE));
        openPreGameWindow(game);

        boolean answer = llm.chooseUse(Outcome.Benefit, QUESTION, null, game);

        Assert.assertTrue("an exact 'yes' is accepted", answer);
        Assert.assertEquals("an in-format answer costs exactly one model call", 1, llm.calls());
        Assert.assertNull("no end reason may be recorded", game.getState().getEndReason());
        Assert.assertFalse("the game must not end on a good answer", game.hasEnded());
        Assert.assertFalse("the seat must not forfeit", llm.hasLost());
    }

    @Test
    public void proseThenExactAnswerProceedsAfterOneExplicitNudge() {
        Game game = newGame();
        ScriptedYesNoPlayer llm = new ScriptedYesNoPlayer("LLM seat", PROSE, "no");
        addPlayer(game, llm);
        addPlayer(game, new StubPlayer("Opponent", RangeOfInfluence.ONE));
        openPreGameWindow(game);

        boolean answer = llm.chooseUse(Outcome.Benefit, QUESTION, null, game);

        Assert.assertFalse("the exact 'no' on the nudge is honored", answer);
        Assert.assertEquals("prose then an exact answer is 2 model calls (ask + one nudge)",
                2, llm.calls());
        Assert.assertEquals("the second call is the format correction",
                "yesno-corrected", llm.promptTypeAt(1));
        String nudge = llm.promptAt(1);
        Assert.assertTrue("the nudge must quote the rejected answer, got: " + nudge,
                nudge.contains(PROSE.substring(0, 40)));
        Assert.assertTrue("the nudge must demand exactly yes or no, got: " + nudge,
                nudge.contains("exactly the single word \"yes\"")
                        && nudge.contains("exactly the single word \"no\""));
        Assert.assertNull("a recovering answer records no end reason",
                game.getState().getEndReason());
        Assert.assertFalse("no forfeit when the nudge works", llm.hasLost());
    }

    @Test
    public void proseAfterBothNudgesForfeitsTheSeatAndTheOpponentWins() {
        Game game = newGame();
        ScriptedYesNoPlayer llm = new ScriptedYesNoPlayer("LLM seat", PROSE, PROSE, PROSE, "yes");
        StubPlayer opponent = new StubPlayer("Opponent", RangeOfInfluence.ONE);
        addPlayer(game, llm);
        addPlayer(game, opponent);
        openPreGameWindow(game);

        boolean answer = llm.chooseUse(Outcome.Benefit, QUESTION, null, game);

        Assert.assertFalse("a forfeit returns the engine's benign decline", answer);
        Assert.assertEquals("one ask + exactly TWO explicit nudges, no more", 3, llm.calls());
        Assert.assertEquals("the model was never asked a third time", 1, llm.unscriptedAnswersLeft());
        Assert.assertEquals("the forfeit records its own reason",
                GameEndReason.LLM_FORMAT_FORFEIT, game.getState().getEndReason());
        Assert.assertNotEquals("the technical-winner path must not be taken",
                GameEndReason.TECHNICAL_ERROR, game.getState().getEndReason());
        Assert.assertTrue("the game ends through the ordinary concede unwind", game.hasEnded());
        Assert.assertTrue("the offending seat records a REAL loss", llm.hasLost());
        Assert.assertTrue("the opponent wins outright, not by life/random", opponent.hasWon());
    }

    @Test
    public void midGameFormatDisobedienceForfeitsWithoutTechnicalWinner() {
        Game game = newGame();
        ScriptedYesNoPlayer llm = new ScriptedYesNoPlayer("LLM seat", PROSE, PROSE, PROSE);
        StubPlayer opponent = new StubPlayer("Opponent", RangeOfInfluence.ONE);
        addPlayer(game, llm);
        addPlayer(game, opponent);
        openPreGameWindow(game);
        // The priority loop has started: this is the MID-GAME window the incident
        // happened in (turn 7 draw step, an in-game chooseUse).
        game.getState().setPriorityPlayerId(llm.getId());

        boolean answer = llm.chooseUse(Outcome.Benefit, QUESTION, null, game);

        Assert.assertFalse("the forfeit declines the question instead of throwing", answer);
        Assert.assertEquals("the seat forfeits for format disobedience",
                GameEndReason.LLM_FORMAT_FORFEIT, game.getState().getEndReason());
        Assert.assertNotEquals("no technical winner is awarded for this cause",
                GameEndReason.TECHNICAL_ERROR, game.getState().getEndReason());

        // The concede is queued for the priority loop (GameImpl.setConcedingPlayer);
        // processing it is the engine's ordinary way to end the seat's game.
        ((mage.game.GameImpl) game).checkConcede();
        ((mage.game.GameImpl) game).checkIfGameIsOver();

        Assert.assertTrue("the seat loses the game", llm.hasLost());
        Assert.assertTrue("the opponent wins the game", opponent.hasWon());
        Assert.assertTrue("the game is over", game.hasEnded());
    }
}
