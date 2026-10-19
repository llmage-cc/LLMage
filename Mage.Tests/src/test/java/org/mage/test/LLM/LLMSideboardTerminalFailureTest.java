package org.mage.test.LLM;

import mage.cards.Card;
import mage.cards.decks.Deck;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.constants.RangeOfInfluence;
import mage.game.match.MatchOptions;
import mage.player.llm.LLMClient;
import mage.player.llm.LLMConfig;
import mage.player.llm.LLMPlayer;
import mage.player.llm.LLMResponse;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;

import java.util.UUID;

/**
 * Pins the sideboard-terminal-failure contract: when an LLM call in
 * the sideboard phase fails, the match must END and the table must be released —
 * it must NOT be left non-FINISHED in SIDEBOARDING.
 *
 * <p>Pre-fix failure mode (six Bo3 tables once wedged for hours):
 * {@code LLMPlayer.sideboard} gated {@code match.quitMatch(playerId)} on
 * {@code thrownAfterFullLadder(t)}. The sideboard path uses
 * {@code LLMClient.chat} — a SINGLE call with no retry ladder — so the
 * exhausted-ladder signature ("LLM call failed after N attempts") never
 * appears, the predicate is false, and the {@code RuntimeException} is
 * rethrown. The throw then escapes {@code MatchImpl.sideboard} →
 * {@code TableController.sideboard} (its catch is {@code GameException}, not
 * {@code RuntimeException}) → {@code GameWorker} (endProcessed already true,
 * so its Throwable catch logs "GameWorker system error" but skips
 * {@code endGameWithError}). {@code closeTable()}/{@code matchEnd()} never
 * run, so the table stays in SIDEBOARDING and is republished by
 * {@code GamesRoomImpl} forever.
 *
 * <p>The fix classifies ANY sideboard-phase failure as terminal for the match
 * (there is no next-ladder-window to defer to on the match-end path) and quits
 * unconditionally. Both sideboard-phase LLM calls are covered: the main
 * sideboard chat and the post-sideboard revised-strategy chat.
 *
 * <p>Each test drives the real {@code MatchImpl.sideboard()} loop on a real
 * two-player match whose seat has a sideboard, with a stub client whose
 * {@code chat()} throws a retryable provider failure (the exact shape the
 * browser runner reports for HTTP 400/403/5xx). The throw must NOT escape, and
 * {@code match.hasEnded()} must be true afterwards (so the table would be
 * closed, not left non-FINISHED).
 */
public class LLMSideboardTerminalFailureTest extends CardTestPlayerBaseLLM {

    /** A retryable provider failure: the shape a reported 400/403/5xx takes. */
    private static final String PROVIDER_FAILURE =
            "LLM API call failed: runner provider call failed: HTTP 400 unknown field 'thinking'";

    private TestMatch match;

    @Override
    protected org.mage.test.player.TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if (getFullSimulatedPlayers().contains(name)) {
            TestLLMPlayer llmPlayer = new TestLLMPlayer(name, RangeOfInfluence.ONE, 0);
            llmPlayer.getLLMConfig()
                    .apiEndpoint("http://localhost:11434/v1/chat/completions")
                    .model("deepseek-v4-flash:cloud")
                    .maxTokens(65536)
                    .timeoutSeconds(120)
                    .deckAssessmentEnabled(false)
                    .verboseLogging(false);
            llmPlayer.reconnect();
            // Default: the FIRST sideboard chat (the main sideboard call) fails.
            llmPlayer.injectTestClient(new FailingSideboardStubClient(llmPlayer.getLLMConfig(), 1, "NONE"));
            org.mage.test.player.TestPlayer testPlayer = new org.mage.test.player.TestPlayer(llmPlayer);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    /**
     * Build the game fixture and a Bo3 match whose LLM seat has a sideboard, so
     * {@code sideboard()} takes the LLM path (the "no sideboard → submit
     * unchanged" early return must not swallow the failure).
     */
    @Before
    public void setUpSideboardMatch() {
        // Real game fixture: two seats so the sideboard loop sees both players.
        addCard(mage.constants.Zone.BATTLEFIELD, playerA, "Plains", 2);

        MatchOptions opts = new MatchOptions("sideboard-wedge", "Two Player Duel", false);
        opts.setWinsNeeded(2);  // Bo3 so the sideboard phase is in play
        match = new TestMatch(opts);
        // sideboard() needs match.getGame() non-null and hasStarted() true.
        match.attachGame(currentGame);
        match.addGame();

        Deck seatDeck = new Deck();
        seatDeck.getSideboard().add(sideboardCard());

        match.addPlayer(playerA.getComputerPlayer(), seatDeck);
        match.addPlayer(playerB.getComputerPlayer(), new Deck());
        match.startMatch();

        Assert.assertFalse("match must not have ended before sideboarding", match.hasEnded());
    }

    private UUID llmId() {
        return playerA.getComputerPlayer().getId();
    }

    private FailingSideboardStubClient client() {
        return (FailingSideboardStubClient) ((TestLLMPlayer) playerA.getComputerPlayer()).clientForTest();
    }

    /**
     * Pre-fix, the rethrown RuntimeException escapes {@code match.sideboard()};
     * post-fix the seat quits and the loop returns normally. Drives the REAL
     * match-level sideboard loop (on "main" = the test's game-thread token).
     */
    @Test
    public void sideboardCallFailureEndsMatchInsteadOfWedgingInSideboarding() {
        FailingSideboardStubClient client = client();
        match.sideboard();

        Assert.assertTrue("a sideboard LLM failure must have been attempted (stub was reached)",
                client.chatCalls >= 1);
        Assert.assertTrue("match must END after a sideboard-call failure — otherwise the table "
                        + "stays non-FINISHED in SIDEBOARDING forever",
                match.hasEnded());
        Assert.assertNotNull("the LLM seat must still be registered on the match",
                match.getPlayer(llmId()));
        Assert.assertTrue("the LLM seat must be marked quit so closeTable()/matchEnd() run",
                match.getPlayer(llmId()).hasQuit());
    }

    /**
     * The sideboard phase's SECOND LLM call ({@code generateRevisedStrategy}) runs
     * on the same match-end path. A failure there must also end the match —
     * pre-fix its {@code RuntimeException} escaped {@code sideboard()} exactly
     * like the main chat's and wedged the table.
     */
    @Test
    public void revisedStrategyFailureEndsMatchInsteadOfWedgingInSideboarding() {
        FailingSideboardStubClient client = client();
        // First sideboard chat returns NONE (no card swaps → no deck-validation
        // noise), second chat (revised strategy) fails.
        client.failAtCall = 2;
        match.sideboard();

        Assert.assertTrue("both the main sideboard and revised-strategy chats must have run "
                        + "(calls=" + client.chatCalls + ")", client.chatCalls >= 2);
        Assert.assertTrue("match must END after a revised-strategy failure — otherwise the table "
                        + "stays non-FINISHED in SIDEBOARDING forever",
                match.hasEnded());
        Assert.assertTrue("the LLM seat must be marked quit so closeTable()/matchEnd() run",
                match.getPlayer(llmId()).hasQuit());
    }

    /**
     * Root-cause pin: the stub's failure shape is NOT the exhausted-ladder
     * signature the old gate keyed on. This documents why the pre-fix predicate
     * was false (→ rethrow → wedge) and guards against reintroducing the gate.
     */
    @Test
    public void providerFailureShapeIsNotLadderSignature() {
        ProbePlayer p = new ProbePlayer(new LLMConfig()
                .apiEndpoint("http://localhost:11434/v1/chat/completions")
                .model("probe-model")
                .thinkingEnabled(false));
        Throwable t = new RuntimeException(PROVIDER_FAILURE);
        Assert.assertFalse("provider failure shape must NOT read as the exhausted-ladder signature",
                p.terminalLadder(t));
        // Control: the ladder-exhausted message the old gate keyed on IS terminal.
        Assert.assertTrue(p.terminalLadder(new RuntimeException(
                "LLM API call failed: LLM call failed after 5 attempts. Last error: 400")));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * Every sideboard-path {@code chat()} call fails from call {@code failAtCall}
     * (1-based) onward; earlier calls return the deterministic success body. In-game
     * calls ({@code chatConversation}) are stubbed harmlessly.
     */
    static class FailingSideboardStubClient extends LLMClient {
        private int chatCalls;
        int failAtCall;
        private final String firstAnswer;

        FailingSideboardStubClient(LLMConfig config, int failAtCall, String firstAnswer) {
            super(config);
            this.failAtCall = failAtCall;
            this.firstAnswer = firstAnswer;
        }

        @Override
        public LLMResponse chatConversation(String fullPrompt, String compactSummary,
                                            String promptType, Integer turn, String phaseStep,
                                            String effortOverride) {
            return new LLMResponse("stub", "0");
        }

        @Override
        public LLMResponse chat(String systemPrompt, String userPrompt) {
            chatCalls++;
            if (chatCalls >= failAtCall) {
                // Retryable by every classification the old predicate used — this is
                // the shape that fell through the thrownAfterFullLadder gate to the
                // rethrow (LLMClient.chat wraps failures exactly like this).
                throw new RuntimeException(PROVIDER_FAILURE);
            }
            return new LLMResponse("stub thinking", firstAnswer);
        }
    }

    /** Minimal concrete MatchImpl: only the abstract methods the fixture never touches. */
    private static final class TestMatch extends mage.game.match.MatchImpl {
        TestMatch(MatchOptions options) {
            super(options);
        }

        /** Register the fixture's live game so sideboard() does not early-return on a null game. */
        void attachGame(mage.game.Game game) {
            this.games.add(game);
        }

        @Override
        public mage.game.GameInfo createGameInfo(mage.game.Game game) {
            return null;
        }

        @Override
        public java.util.List<mage.game.GameInfo> getGamesInfo() {
            return java.util.Collections.emptyList();
        }

        @Override
        public void startGame() {
        }

        @Override
        public mage.game.result.ResultProtos.MatchProto toProto() {
            return null;
        }
    }

    private static Card sideboardCard() {
        CardInfo info = CardRepository.instance.findCard("Grizzly Bears", true);
        Assert.assertNotNull("[TEST] sideboard card must exist in the card repository", info);
        return info.createCard();
    }

    /** Exposes the protected classifier without touching live LLM state. */
    private static final class ProbePlayer extends LLMPlayer {
        ProbePlayer(LLMConfig config) {
            super("probe", RangeOfInfluence.ONE, 0);
            setLLMConfig(config);
        }

        @Override
        protected boolean isTestHarness() {
            return true;
        }

        boolean terminalLadder(Throwable t) {
            return thrownAfterFullLadder(t);
        }
    }
}
