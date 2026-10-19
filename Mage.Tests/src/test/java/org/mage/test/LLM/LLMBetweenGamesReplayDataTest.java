package org.mage.test.LLM;

import mage.constants.RangeOfInfluence;
import mage.player.llm.LLMClient;
import mage.player.llm.LLMConfig;
import mage.player.llm.LLMExchangeRecord;
import mage.player.llm.LLMPlayer;
import mage.player.llm.LLMResponse;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pins the between-games replay-data contract:
 *
 * <p>1. The async post-game path (match review, written on the post-game
 * executor after game 1 of a Bo3) must NEVER write or clear replay buckets:
 * game 1's .game save is written by endGameWithResult BEFORE the async worker
 * runs, so an async record could never reach its own replay — and the removed
 * clear-on-game-mismatch variant wiped whichever fresher game's buckets had
 * been recorded by the time the slow call returned. The regression shape:
 * record game-2 exchanges, THEN run the async reflection for game 1 — game 2's
 * buckets must be intact and must contain no match-review record.
 *
 * <p>2. The between-games text reaches the NEXT game's replay as serialized
 * FIELDS (matchReview), which buildReplayViews attaches — the same data the
 * live tab shows. Assert the field survives the copy constructor (the
 * tournament sub-table path) and that the getters exist for the reflective read.
 *
 * <p>Stub client: no live calls; the match-review path returns instantly.
 */
public class LLMBetweenGamesReplayDataTest extends CardTestPlayerBaseLLM {

    private static final AtomicInteger reviewCalls = new AtomicInteger();

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
            llmPlayer.injectTestClient(new ReviewStubClient(llmPlayer.getLLMConfig()));
            org.mage.test.player.TestPlayer testPlayer = new org.mage.test.player.TestPlayer(llmPlayer);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    static class ReviewStubClient extends LLMClient {
        ReviewStubClient(LLMConfig config) {
            super(config);
        }

        @Override
        public LLMResponse chatConversation(String fullPrompt, String compactSummary,
                                            String promptType, Integer turn, String phaseStep,
                                            String effortOverride) {
            return new LLMResponse("stub", "0");
        }

        @Override
        public LLMResponse chat(String systemPrompt, String userPrompt) {
            reviewCalls.incrementAndGet();
            // A multi-game match's post-game path asks for a match review; any
            // caller gets a deterministic body.
            return new LLMResponse("stub review thinking", "stub match review body");
        }
    }

    private static void invokeGeneratePostGameSummary(LLMPlayer player, mage.game.Game game) throws Exception {
        java.lang.reflect.Method m = LLMPlayer.class
                .getDeclaredMethod("generatePostGameSummary", mage.game.Game.class);
        m.setAccessible(true);
        try {
            m.invoke(player, game);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw new AssertionError("generatePostGameSummary threw on the game-end path: " + cause, cause);
        }
    }

    /** Game 2's buckets must survive game 1's async post-game work landing late. */
    @Test
    public void asyncPostGameWorkNeverTouchesNewerGameBuckets() throws Exception {
        addCard(mage.constants.Zone.BATTLEFIELD, playerA, "Plains", 2);
        addCard(mage.constants.Zone.BATTLEFIELD, playerB, "Plains", 2);

        final mage.game.Game game1 = currentGame;
        game1.getState().setGameLogEnabled(true);
        for (int i = 0; i < 20; i++) {
            game1.getState().addGameLog("[test] seeded game event " + i);
        }

        LLMPlayer llm = (LLMPlayer) playerA.getComputerPlayer();
        // Bo3 shape: the match-review path is gated on matchPlayer.getWinsNeeded() > 1.
        // MatchImpl is abstract (the unimplemented methods need a server/game
        // environment this fixture does not have) — an anonymous stub supplies
        // the minimal surface MatchPlayer construction reads (winsNeeded).
        mage.game.match.MatchOptions opts = new mage.game.match.MatchOptions("test", "Two Player Duel", false);
        opts.setWinsNeeded(2);
        mage.game.match.Match match = new mage.game.match.MatchImpl(opts) {
            @Override public mage.game.GameInfo createGameInfo(mage.game.Game game) { return null; }
            @Override public java.util.List<mage.game.GameInfo> getGamesInfo() { return java.util.Collections.emptyList(); }
            @Override public java.util.Date getEndTime() { return null; }
            @Override public java.util.Date getStartTime() { return new java.util.Date(); }
            @Override public void startGame() { }
            @Override public mage.game.result.ResultProtos.MatchProto toProto() { return null; }
        };
        llm.setMatchPlayer(new mage.game.match.MatchPlayer(llm, new mage.cards.decks.Deck(), match));
        // A game-1 exchange recorded normally (the in-game recorder).
        llm.recordReplayExchangeForTest(game1, 3, "priority", "game-1 exchange", "0");

        // Fire game 1's async post-game path. Its worker runs while we
        // simulate game 2 starting — the Bo3 shape: reflection + match review
        // are slow provider calls, sideboarding + game 2's first exchanges
        // land on the game thread meanwhile.
        invokeGeneratePostGameSummary(llm, game1);

        // Game 2 begins: a fresh Game object, fresh buckets.
        mage.game.Game game2 = new mage.game.TwoPlayerDuel(mage.constants.MultiplayerAttackOption.LEFT,
                RangeOfInfluence.ONE, mage.game.mulligan.MulliganType.GAME_DEFAULT.getMulligan(0),
                60, 20, 7);
        game2.addPlayer(playerA, new mage.cards.decks.Deck());
        llm.recordReplayExchangeForTest(game2, 2, "priority", "game-2 exchange A", "0");
        llm.recordReplayExchangeForTest(game2, 4, "attackers", "game-2 exchange B", "1");

        // The pre-fix corruption: the async match-review record (if any) landed
        // after game-2 exchanges were recorded, saw recordGameId == game-2, and
        // CLEARED the map (or recorded a match_review record into game 2's
        // bucket). Post-fix, game 1's async work may only touch serialized
        // FIELDS — the map must be exactly game 2's two exchanges.
        boolean reflectionDone = llm.awaitPostGameReflectionForTest(15000);
        Assert.assertTrue("async reflection must complete for the review-field assertions", reflectionDone);

        Assert.assertEquals("game-2 id must scope the map", game2.getId(), llm.replayRecordGameIdForTest());
        Assert.assertEquals("game 2 owns exactly its two recorded buckets", 2, llm.getReplayLLMData().size());
        List<?> bucket2 = llm.getReplayLLMData().get(2);
        List<?> bucket4 = llm.getReplayLLMData().get(4);
        Assert.assertNotNull(bucket2);
        Assert.assertNotNull(bucket4);
        Assert.assertEquals(1, bucket2.size());
        Assert.assertEquals(1, bucket4.size());
        for (List<?> bucket : java.util.Arrays.asList(bucket2, bucket4)) {
            LLMExchangeRecord rec = (LLMExchangeRecord) bucket.get(0);
            Assert.assertNull("no async match-review record may leak into a newer game's buckets",
                    rec.fullPrompt);
            Assert.assertFalse(rec.preGame);
        }

        // The review text itself must be on the player (serialized field) —
        // what buildReplayViews attaches to the next game's replay view.
        String review = llm.getMatchReview();
        Assert.assertNotNull("match review must be stored on the player for the next game's replay", review);
        Assert.assertTrue(review.contains("stub match review body"));
        Assert.assertTrue("the review call must actually have run (calls=" + reviewCalls.get() + ")",
                reviewCalls.get() >= 1);
    }

    /**
     * The serialized-field route survives the copy constructor (tournament
     * sub-tables) — the next game's replay gets the review through the copy.
     */
    @Test
    public void matchReviewSurvivesCopyConstructor() throws Exception {
        addCard(mage.constants.Zone.BATTLEFIELD, playerA, "Plains", 2);
        LLMPlayer original = (LLMPlayer) playerA.getComputerPlayer();

        java.lang.reflect.Field f = LLMPlayer.class.getDeclaredField("matchReview");
        f.setAccessible(true);
        f.set(original, "review from game 1");

        LLMPlayer copy = original.copy();
        Assert.assertEquals("matchReview must ride the copy constructor into sub-tables",
                "review from game 1", copy.getMatchReview());
    }

    /** The getters used by LLMPlayerHelper.buildReplayViews exist and read the fields. */
    @Test
    public void replaySectionGettersExist() {
        addCard(mage.constants.Zone.BATTLEFIELD, playerA, "Plains", 2);
        LLMPlayer llm = (LLMPlayer) playerA.getComputerPlayer();

        Assert.assertNull("gamePlan must be null before any assessment/gameplan call", llm.getGamePlan());
        Assert.assertNull(llm.getMatchReview());
        Assert.assertNull(llm.getPostGameReflection());
    }
}