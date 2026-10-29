package org.mage.test.LLM;

import mage.cards.CardSetInfo;
import mage.cards.Cards;
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
import mage.players.ManaPool;
import mage.players.Player;
import mage.players.StubPlayer;
import mage.target.TargetPlayer;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pins the pre-game PROMPT-ASSEMBLY guards: {@code LLMPlayer.chooseUse},
 * {@code LLMPlayer.chooseMulligan} and the target funnels
 * ({@code chooseTarget} / {@code choose(Cards, TargetCard)}).
 *
 * <p>Each callback assembles a prompt and then calls the provider. The provider
 * call was already guarded, but the assembly that precedes it was not, so a
 * {@code RuntimeException} thrown while BUILDING the prompt escaped
 * {@code GameImpl.init} (no try/catch there) into {@code GameController
 * .endGameWithError} → {@code endWithTechnicalWinner}: a winner by
 * life/time/<b>random</b> with {@code GameEndReason.TECHNICAL_ERROR} and no
 * forfeit — the outcome the pre-game guard exists to prevent. All of them now
 * carry the same pre-call/post-call split: {@code chooseUse} and
 * {@code chooseMulligan} run the assembly inside the provider-call try, and the
 * target funnels mark the request boundary with {@code ProviderCallBoundary};
 * a throw before the request is started forfeits the seat pre-game
 * ({@code endReason llm_failure}) and never reaches the ladder-gated forfeit,
 * which rethrows everything that is not {@code thrownAfterFullLadder}.
 *
 * <p>Mid-game behaviour is unchanged: the
 * same assembly throw still propagates once the priority loop has started.
 *
 * <p>Focused fixture, same shape as {@code LLMPreGameYesNoFailureTest}: a real
 * duel put into the pre-game window ({@code beginTurn} per seat, which fills
 * the range data the concede unwind reads), with the prompt assembly of the
 * LLM seat made to throw and the provider ladder recording any call. No game
 * loop, no network.
 */
public class LLMPreGamePromptAssemblyFailureTest {

    /** The seat's own prompt-assembly failure (a plain RuntimeException, not a provider shape). */
    private static final String ASSEMBLY_FAILURE = "prompt assembly: null card in the seat's own format";

    private static final String QUESTION = "Use Leyline of the Void as your companion?";

    /** Which seat method the assembly failure is injected through. */
    private enum AssemblyHook {
        /** {@code chooseUse} reads the mana pool while building the prompt. */
        MANA_POOL,
        /** {@code chooseMulligan} reads the seat id while building the opponents block. */
        PLAYER_ID,
        /**
         * The target pickers read the seat's hand inside
         * {@code LLMGameSerializer.buildSituationSummary} while building the
         * target prompt — i.e. AFTER the funnel entry and BEFORE the request.
         */
        HAND
    }

    /**
     * LLM seat whose prompt assembly throws once, armed right before the call
     * under test. The provider ladder is stubbed to fail loudly if it is ever
     * reached — every test here asserts the request was never sent.
     */
    private static final class AssemblyFailingPlayer extends LLMPlayer {

        private final AssemblyHook hook;
        private final List<String> providerCalls = new ArrayList<>();
        private boolean armed;

        AssemblyFailingPlayer(String name, AssemblyHook hook) {
            super(name, RangeOfInfluence.ONE, 0);
            this.hook = hook;
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

        void arm() {
            armed = true;
        }

        @Override
        public ManaPool getManaPool() {
            throwIfArmed(AssemblyHook.MANA_POOL);
            return super.getManaPool();
        }

        @Override
        public UUID getId() {
            throwIfArmed(AssemblyHook.PLAYER_ID);
            return super.getId();
        }

        @Override
        public Cards getHand() {
            throwIfArmed(AssemblyHook.HAND);
            return super.getHand();
        }

        /** Throws once and disarms, so the concede unwind runs on the real player. */
        private void throwIfArmed(AssemblyHook at) {
            if (armed && hook == at) {
                armed = false;
                throw new IllegalStateException(ASSEMBLY_FAILURE + " (" + at + ")");
            }
        }

        @Override
        protected LLMResponse chatAndRecordWithRetry(String prompt, String compactSummary,
                                                     String promptType, Integer turn,
                                                     String phaseStep) {
            providerCalls.add(promptType);
            return new LLMResponse(null, "keep");
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
     * questions, which fills {@code inRange} — the concede unwind's
     * {@code won()} asks {@code game.getOpponents()} and the range check throws
     * on an unfilled range.
     */
    private static void openPreGameWindow(Game game) {
        for (Player player : game.getPlayers().values()) {
            player.beginTurn(game);
        }
    }

    private static void assertSeatForfeited(Game game, AssemblyFailingPlayer llm,
                                            StubPlayer opponent) {
        Assert.assertEquals("a pre-game assembly failure must be recorded as the seat's"
                        + " LLM forfeit, not a technical winner",
                GameEndReason.LLM_FAILURE, game.getState().getEndReason());
        Assert.assertTrue("the game ended", game.hasEnded());
        Assert.assertTrue("the failing seat loses", llm.hasLost());
        Assert.assertFalse("the opponent must not be marked lost", opponent.hasLost());
        // The remaining seat wins through the engine's ordinary concede unwind
        // (checkIfGameIsOver -> player.won). GameImpl.play() is what turns that
        // into winnerId/!isADraw at the end of a real start(); this fixture
        // calls the callback directly, so it asserts the seat outcome, not the
        // match-level bookkeeping the game loop owns.
        Assert.assertTrue("the opponent wins — no random technical winner", opponent.hasWon());
    }

    @Test
    public void preGameChooseUseAssemblyFailureConcedes() {
        Game game = newGame();
        AssemblyFailingPlayer llm = new AssemblyFailingPlayer("LLM seat", AssemblyHook.MANA_POOL);
        addPlayer(game, llm);
        StubPlayer opponent = new StubPlayer("Opponent", RangeOfInfluence.ONE);
        addPlayer(game, opponent);

        Assert.assertNull("fixture precondition: no priority player yet",
                game.getState().getPriorityPlayerId());
        openPreGameWindow(game);
        llm.arm();

        boolean answer = llm.chooseUse(Outcome.Benefit, QUESTION, null, game);

        Assert.assertFalse("the assembly failure returns the benign decline", answer);
        Assert.assertTrue("the provider request must never be sent, saw: " + llm.providerCalls,
                llm.providerCalls.isEmpty());
        assertSeatForfeited(game, llm, opponent);
    }

    @Test
    public void preGameChooseMulliganAssemblyFailureConcedes() {
        Game game = newGame();
        AssemblyFailingPlayer llm = new AssemblyFailingPlayer("LLM seat", AssemblyHook.PLAYER_ID);
        addPlayer(game, llm);
        StubPlayer opponent = new StubPlayer("Opponent", RangeOfInfluence.ONE);
        addPlayer(game, opponent);

        Assert.assertNull("fixture precondition: no priority player yet",
                game.getState().getPriorityPlayerId());
        openPreGameWindow(game);
        llm.arm();

        boolean keep = llm.chooseMulligan(game);

        Assert.assertTrue("the assembly failure returns the benign keep", keep);
        Assert.assertTrue("the provider request must never be sent, saw: " + llm.providerCalls,
                llm.providerCalls.isEmpty());
        assertSeatForfeited(game, llm, opponent);
    }

    /**
     * Target-funnel arm: the pre-game STARTING-PLAYER choice
     * ({@code GameImpl.init:1441} → {@code Player.choose} →
     * {@code chooseTarget}) whose prompt assembly throws.
     *
     * <p>Before this guard, {@code failPreGameTargetChoice} rethrew every
     * failure that was not {@code thrownAfterFullLadder} — and an assembly
     * throw never is, because no request was sent — so the throw escaped
     * {@code init} into {@code endWithTechnicalWinner}. The target funnels now
     * carry the same pre-call/post-call split as {@code chooseUse}: the throw
     * lands on {@code failPreGamePromptAssembly}, which forfeits with
     * {@code llm_failure} and returns the benign "not chosen".
     */
    @Test
    public void preGameTargetChoiceAssemblyFailureConcedes() {
        Game game = newGame();
        AssemblyFailingPlayer llm = new AssemblyFailingPlayer("LLM seat", AssemblyHook.HAND);
        addPlayer(game, llm);
        StubPlayer opponent = new StubPlayer("Opponent", RangeOfInfluence.ONE);
        addPlayer(game, opponent);

        Assert.assertNull("fixture precondition: no priority player yet",
                game.getState().getPriorityPlayerId());
        openPreGameWindow(game);
        llm.arm();

        // The exact shape GameImpl.init uses for the starting player.
        TargetPlayer startingPlayer = new TargetPlayer();
        startingPlayer.withTargetName("starting player");
        boolean chosen = llm.choose(Outcome.Benefit, startingPlayer, null, game);

        Assert.assertFalse("the assembly failure returns the benign 'not chosen'", chosen);
        Assert.assertTrue("no target may be consumed by the failed choice",
                startingPlayer.getTargets().isEmpty());
        Assert.assertTrue("the provider request must never be sent, saw: " + llm.providerCalls,
                llm.providerCalls.isEmpty());
        assertSeatForfeited(game, llm, opponent);
    }

    /**
     * Mid-game scope pin for the target funnels: the same assembly throw must
     * still propagate once the priority loop has started.
     */
    @Test
    public void midGameTargetChoiceAssemblyFailureStillPropagates() {
        Game game = newGame();
        AssemblyFailingPlayer llm = new AssemblyFailingPlayer("LLM seat", AssemblyHook.HAND);
        addPlayer(game, llm);
        addPlayer(game, new StubPlayer("Opponent", RangeOfInfluence.ONE));

        // beginTurn fills the range data the target lookup reads; the priority id
        // is what makes the seat "mid-game" for the guard.
        openPreGameWindow(game);
        game.getState().setPriorityPlayerId(llm.getId());
        llm.arm();

        TargetPlayer target = new TargetPlayer();
        try {
            llm.chooseTarget(Outcome.Benefit, target, null, game);
            Assert.fail("a mid-game assembly failure must propagate (play on), not forfeit");
        } catch (IllegalStateException e) {
            Assert.assertEquals("the propagated failure is the injected assembly failure",
                    ASSEMBLY_FAILURE + " (" + AssemblyHook.HAND + ")", e.getMessage());
        }

        Assert.assertNull("no reason may be recorded by the pre-game guard",
                game.getState().getEndReason());
        Assert.assertFalse("the game must not end in the pre-game guard", game.hasEnded());
        Assert.assertFalse("the seat must not forfeit mid-game", llm.hasLost());
    }

    /**
     * Mid-game scope pin: once the priority loop has started, the same assembly
     * throw must still propagate — no forfeit,
     * no recorded reason, no silent decline.
     */
    @Test
    public void midGameChooseUseAssemblyFailureStillPropagates() {
        Game game = newGame();
        AssemblyFailingPlayer llm = new AssemblyFailingPlayer("LLM seat", AssemblyHook.MANA_POOL);
        addPlayer(game, llm);
        addPlayer(game, new StubPlayer("Opponent", RangeOfInfluence.ONE));

        game.getState().setPriorityPlayerId(llm.getId());
        llm.arm();

        try {
            llm.chooseUse(Outcome.Benefit, QUESTION, null, game);
            Assert.fail("a mid-game assembly failure must propagate (play on), not forfeit");
        } catch (IllegalStateException e) {
            Assert.assertEquals("the propagated failure is the injected assembly failure",
                    ASSEMBLY_FAILURE + " (" + AssemblyHook.MANA_POOL + ")", e.getMessage());
        }

        Assert.assertNull("no reason may be recorded by the pre-game guard",
                game.getState().getEndReason());
        Assert.assertFalse("the game must not end in the pre-game guard", game.hasEnded());
        Assert.assertFalse("the seat must not forfeit mid-game", llm.hasLost());
    }
}
