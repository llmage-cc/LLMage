package org.mage.test.LLM;

import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.player.llm.LLMClient;
import mage.player.llm.LLMConfig;
import mage.player.llm.LLMPlayer;
import mage.player.llm.LLMResponse;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Async post-game reflection contract — reflection runs off the game thread.
 *
 * <p>The game-over path records the result before any reflection work is
 * snapshotted: match and sideboard proceed immediately, and a reflection
 * failure yields no postgame file, a loud log, and no match change.
 *
 * <p>Failure mode being pinned:
 * the winner's post-game reflection hung >600s ON the game worker
 * thread inside {@code won()}; the exception propagated through
 * {@code checkIfGameIsOver}, the match was recorded as "Game is a draw" and
 * game 2 never started (zombie table).
 *
 * <p>This class drives the REAL game-over entry points ({@code won()} /
 * {@code lostForced()}, including the internal checkIfGameIsOver/leave cascade
 * a direct call triggers) against a stub client whose reflection call throws
 * or stalls, and asserts the four contract clauses:
 * <ol>
 *   <li>a reflection failure NEVER propagates out of won()/lostForced()
 *       (pre-fix: the RuntimeException propagated out of the game-end path);</li>
 *   <li>won()/lostForced() return PROMPTLY while the reflection call is stuck
 *       (pre-fix: the call blocked the game thread for the provider timeout);</li>
 *   <li>the result is committed before/independent of reflection work — win/
 *       loss flags are set while the reflection is still in flight, so a hung
 *       provider can no longer flip the record to a draw;</li>
 *   <li>failure = loud log + no postgame file; healthy = the postgame file is
 *       still written with the reflection content.</li>
 * </ol>
 *
 * <p>RED/GREEN discrimination: against the pre-fix code, leg 1 fails with
 * the propagated RuntimeException and leg 2 fails the promptness assertion
 * (won() blocks in the stub gate until its 10s backstop). The stub answers
 * instantly for every non-reflection call, so only the reflection path is
 * exercised.
 */
public class LLMPostGameReflectionAsyncTest extends CardTestPlayerBaseLLM {

    /** Reflection-call behavior for the stub provider. */
    private enum Mode { HEALTHY, THROW, STALL }

    private static volatile Mode mode = Mode.HEALTHY;
    private static final AtomicInteger reflectionCalls = new AtomicInteger();
    private static volatile CountDownLatch reflectionEntered;
    private static volatile CountDownLatch releaseGate;
    private static volatile String reflectionThreadName;
    /** Per-chat-call answer bodies, consumed in call order; null = single default body. */
    private static volatile java.util.Deque<String> chatScript;

    /** The stall gate's own backstop: if the test thread dies, the worker still frees. */
    private static final long STALL_BACKSTOP_SECONDS = 10;

    // Chat-call tags, in the order the post-game worker fires them (single-game
    // match: one call; multi-game: reflection first, match review second). The
    // stub answers each call with the tagged body so the test can pin WHICH
    // response landed where — body strings double as unique equivalence markers.
    private static final String REFLECTION_BODY = "equivalence reflection body";
    private static final String REVIEW_BODY = "equivalence match review body";

    @Override
    protected org.mage.test.player.TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if (getFullSimulatedPlayers().contains(name)) {
            TestLLMPlayer llmPlayer = new TestLLMPlayer(name, RangeOfInfluence.ONE, 0);
            llmPlayer.getLLMConfig()
                    .apiEndpoint("http://localhost:11434/v1/chat/completions")
                    .model("stub-model")
                    .maxTokens(65536)
                    .timeoutSeconds(120)
                    .deckAssessmentEnabled(false)
                    .verboseLogging(false);
            llmPlayer.reconnect();
            // Stub the client: no live calls. Only the reflection path matters;
            // the transcriptDir is set per-test on the shared config object.
            llmPlayer.injectTestClient(new ReflectionStubClient(llmPlayer.getLLMConfig()));
            org.mage.test.player.TestPlayer testPlayer = new org.mage.test.player.TestPlayer(llmPlayer);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    static class ReflectionStubClient extends LLMClient {
        ReflectionStubClient(LLMConfig config) {
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
            reflectionCalls.incrementAndGet();
            reflectionThreadName = Thread.currentThread().getName();
            Mode m = mode;
            if (m == Mode.THROW) {
                throw new IllegalStateException("stub reflection failure (async-isolation test)");
            }
            if (m == Mode.STALL) {
                CountDownLatch entered = reflectionEntered;
                if (entered != null) {
                    entered.countDown();
                }
                CountDownLatch gate = releaseGate;
                if (gate != null) {
                    try {
                        gate.await(STALL_BACKSTOP_SECONDS, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            java.util.Deque<String> script = chatScript;
            String body = (script != null && !script.isEmpty())
                    ? script.poll() : "stub reflection body";
            return new LLMResponse("stub reflection thinking", body);
        }
    }

    // ------------------------------------------------------------------
    // Leg 1: reflection THROWS — nothing may propagate out of lostForced()
    // ------------------------------------------------------------------
    @Test
    public void reflectionThrowDoesNotPropagateOutOfLostForced() throws Exception {
        mode = Mode.THROW;
        reflectionCalls.set(0);
        Path tmp = Files.createTempDirectory("llm-reflection-throw");
        Logger root = Logger.getRootLogger();
        CapturingAppender appender = new CapturingAppender();
        appender.setThreshold(Level.WARN);
        root.addAppender(appender);
        try {
            Game gameRef = currentGame;
            seedGameLog(gameRef, 20);
            seedStartedGameState(gameRef);
            LLMPlayer llm = (LLMPlayer) playerA.getComputerPlayer();
            llm.getLLMConfig().transcriptDir(tmp.toString());

            try {
                llm.lostForced(gameRef);
            } catch (Throwable t) {
                Assert.fail("lostForced() must not propagate a reflection failure: " + t);
            }

            // Result committed on the game thread despite the reflection failing.
            Assert.assertTrue("the loss must be recorded", llm.hasLost());
            Assert.assertTrue("the opponent must be recorded as winner — NOT a draw",
                    playerB.hasWon());

            Assert.assertTrue("the reflection task must have run to completion",
                    llm.awaitPostGameReflectionForTest(15000));
            Assert.assertTrue("the failure must be counted (loud degradation, not silence)",
                    llm.postGameReflectionFailuresForTest() >= 1);
            Assert.assertNull("no partial reflection may be stored on failure",
                    llmReflection(llm));

            // LOUD LOG contract: logger.error with the full cause and the
            // "no postgame file" consequence.
            Assert.assertTrue("the failure must be logged loudly: 'Failed to generate post-game reflection'",
                    appender.contains("Failed to generate post-game reflection"));
            Assert.assertTrue("the log must name the failure (approved degradation)",
                    appender.contains("no postgame file"));

            // Failure = no postgame file.
            Assert.assertNull("no postgame file may be written when the reflection fails",
                    newestPostgame(tmp));
        } finally {
            root.removeAppender(appender);
            deleteRecursively(tmp);
        }
    }

    // ------------------------------------------------------------------
    // Leg 2: reflection STALLS — won() must return promptly, and the
    // recorded result must be committed while the reflection is in flight.
    // ------------------------------------------------------------------
    @Test
    public void reflectionStallDoesNotBlockWonAndResultIsCommittedWhileStalled() throws Exception {
        releaseGate = new CountDownLatch(1);
        reflectionEntered = new CountDownLatch(1);
        mode = Mode.STALL;
        reflectionCalls.set(0);
        Path tmp = Files.createTempDirectory("llm-reflection-stall");
        try {
            Game gameRef = currentGame;
            seedGameLog(gameRef, 20);
            seedStartedGameState(gameRef);
            LLMPlayer llm = (LLMPlayer) playerA.getComputerPlayer();
            llm.getLLMConfig().transcriptDir(tmp.toString());

            long start = System.nanoTime();
            llm.won(gameRef);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            Assert.assertTrue("won() must return promptly while the reflection call stalls"
                            + " (returned in " + elapsedMs + " ms; pre-fix it blocked for the"
                            + " whole provider call)",
                    elapsedMs < 2500);

            Assert.assertTrue("the stalled reflection must actually be in flight",
                    reflectionEntered.await(5, TimeUnit.SECONDS));
            Assert.assertFalse("the reflection must still be running (single-flight, async)",
                    llm.awaitPostGameReflectionForTest(300));

            // RESULT-COMMIT ORDERING: the recorded result is committed before /
            // independent of the reflection — asserted while the reflection is STUCK.
            Assert.assertTrue("winner flag must be committed while the reflection is stuck",
                    playerA.hasWon());
            Assert.assertTrue("loser flag must be committed while the reflection is stuck",
                    playerB.hasLost());
            Assert.assertTrue("the game must be ended while the reflection is stuck",
                    gameRef.hasEnded());
            Assert.assertNull("a stalled reflection must not have written a file yet",
                    newestPostgame(tmp));

            // Release the stall: healthy completion still writes the postgame file.
            releaseGate.countDown();
            Assert.assertTrue("after the stall is released the reflection must complete",
                    llm.awaitPostGameReflectionForTest(15000));
            Assert.assertEquals("a stall that completes is not a failure", 0,
                    llm.postGameReflectionFailuresForTest());

            Path pg = newestPostgame(tmp);
            Assert.assertNotNull("the postgame file must be written once the slow call returns", pg);
            String content = readAll(pg);
            Assert.assertTrue("the file must carry the committed (non-draw) result: " + content,
                    content.contains("WON") && !content.contains("Result: Draw"));
            Assert.assertTrue("the file must carry the reflection content",
                    content.contains("stub reflection body"));
        } finally {
            releaseGate.countDown();  // never leave the worker gated
            deleteRecursively(tmp);
        }
    }

    // ------------------------------------------------------------------
    // Leg 3: healthy path — postgame file still written with reflection content
    // ------------------------------------------------------------------
    @Test
    public void healthyReflectionStillWritesPostgameFile() throws Exception {
        mode = Mode.HEALTHY;
        reflectionCalls.set(0);
        Path tmp = Files.createTempDirectory("llm-reflection-healthy");
        try {
            Game gameRef = currentGame;
            seedGameLog(gameRef, 20);
            seedStartedGameState(gameRef);
            LLMPlayer llm = (LLMPlayer) playerA.getComputerPlayer();
            llm.getLLMConfig().transcriptDir(tmp.toString());

            llm.lostForced(gameRef);  // must not throw

            Assert.assertTrue("the reflection must complete",
                    llm.awaitPostGameReflectionForTest(15000));
            Assert.assertEquals("no failures on the healthy path", 0,
                    llm.postGameReflectionFailuresForTest());
            Assert.assertTrue("the reflection call must have been made", reflectionCalls.get() >= 1);
            Assert.assertEquals("the reflection text must be stored on the player",
                    "stub reflection body", llmReflection(llm));

            Path pg = newestPostgame(tmp);
            Assert.assertNotNull("the postgame file must be written (behavior kept when healthy)", pg);
            String content = readAll(pg);
            Assert.assertTrue("the file must carry the reflection header",
                    content.contains("=== POST-GAME REFLECTION ==="));
            Assert.assertTrue("the file must carry the reflection content",
                    content.contains("stub reflection body"));
            Assert.assertTrue("the file must carry the committed (non-draw) result: " + content,
                    content.contains("WON") && !content.contains("Result: Draw"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    // ------------------------------------------------------------------
    // Leg 4: the reflection LLM call provably runs OFF the invoking thread
    // ------------------------------------------------------------------
    @Test
    public void reflectionRunsOffTheInvokingThread() throws Exception {
        mode = Mode.HEALTHY;
        reflectionCalls.set(0);
        reflectionThreadName = null;
        Path tmp = Files.createTempDirectory("llm-reflection-thread");
        try {
            Game gameRef = currentGame;
            seedGameLog(gameRef, 20);
            seedStartedGameState(gameRef);
            LLMPlayer llm = (LLMPlayer) playerA.getComputerPlayer();
            llm.getLLMConfig().transcriptDir(tmp.toString());

            String invokingThread = Thread.currentThread().getName();
            llm.lostForced(gameRef);  // returns while the reflection is on the executor
            Assert.assertTrue("the reflection must complete",
                    llm.awaitPostGameReflectionForTest(15000));

            Assert.assertTrue("the reflection call must have happened", reflectionCalls.get() >= 1);
            Assert.assertNotNull("thread name must have been captured", reflectionThreadName);
            Assert.assertNotEquals("the reflection LLM call must run OFF the game thread"
                            + ": ran on '" + reflectionThreadName + "'",
                    invokingThread, reflectionThreadName);
            Assert.assertTrue("the reflection must run on the dedicated executor thread, was '"
                            + reflectionThreadName + "'",
                    reflectionThreadName.contains("postgame-reflection"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    // ------------------------------------------------------------------
    // Equivalence legs:
    // the string writePostGameSummary / writeSideboardSummary append to the
    // .txt files is the SAME string that reaches mage.view.LLMConversationView
    // via getLLMDebugData (live) and via LLMPlayerHelper.buildReplayViews
    // (replay, gamePlan/matchReview only, postGameReflection deliberately null).
    // ------------------------------------------------------------------

    /**
     * (a) Single-game match: the reflection text the stub returns reaches BOTH
     * the postgame .txt file AND view.postGameReflection unchanged. The stub
     * history is seeded first so getLLMDebugData() returns a live view.
     */
    @Test
    public void reflectionTextReachesPostgameFileAndViewUnchanged() throws Exception {
        mode = Mode.HEALTHY;
        chatScript = new ArrayDeque<>(Arrays.asList(REFLECTION_BODY));
        reflectionCalls.set(0);
        Path tmp = Files.createTempDirectory("postgame-equivalence-reflection");
        try {
            Game gameRef = currentGame;
            seedGameLog(gameRef, 20);
            seedStartedGameState(gameRef);
            LLMPlayer llm = (LLMPlayer) playerA.getComputerPlayer();
            llm.getLLMConfig().transcriptDir(tmp.toString());
            seedClientHistory(llm);

            llm.lostForced(gameRef);
            Assert.assertTrue("the async reflection must complete before the view is read",
                    llm.awaitPostGameReflectionForTest(15000));

            Path pg = newestPostgame(tmp);
            Assert.assertNotNull("the postgame file must be written", pg);
            String fileContent = readAll(pg);
            Assert.assertTrue("the file must carry the reflection content",
                    fileContent.contains(REFLECTION_BODY));

            mage.view.LLMConversationView view = llm.getLLMDebugData();
            Assert.assertNotNull("the live view must be available (history seeded)", view);
            Assert.assertEquals("the SAME reflection string must reach view.postGameReflection",
                    REFLECTION_BODY, view.postGameReflection);
            Assert.assertEquals("... and it must equal the stored field byte-for-byte",
                    llm.getPostGameReflection(), view.postGameReflection);
        } finally {
            chatScript = null;
            deleteRecursively(tmp);
        }
    }

    /**
     * (b) Multi-game (Bo3) match: the match-review text the stub returns lands
     * in BOTH the .txt section ("--- REVIEW ---") AND view.matchReview, and the
     * reflection carries both texts. matchPlayer with winsNeeded > 1 flips the
     * worker's multi-game branch.
     */
    @Test
    public void matchReviewTextReachesPostgameFileAndViewInMultiGameMatch() throws Exception {
        mode = Mode.HEALTHY;
        chatScript = new ArrayDeque<>(Arrays.asList(REFLECTION_BODY, REVIEW_BODY));
        reflectionCalls.set(0);
        Path tmp = Files.createTempDirectory("postgame-equivalence-review");
        try {
            Game gameRef = currentGame;
            seedGameLog(gameRef, 20);
            seedStartedGameState(gameRef);
            LLMPlayer llm = (LLMPlayer) playerA.getComputerPlayer();
            llm.getLLMConfig().transcriptDir(tmp.toString());
            seedClientHistory(llm);
            attachMultiGameMatchPlayer(llm);

            llm.lostForced(gameRef);
            Assert.assertTrue("the async reflection + review must complete",
                    llm.awaitPostGameReflectionForTest(15000));
            Assert.assertTrue("both post-game chats must have run (reflection + review)",
                    reflectionCalls.get() >= 2);

            // The review reaches the NEXT game's live view: a copy constructor
            // (tournament sub-table / MatchImpl.initGame flow) carries matchReview.
            LLMPlayer nextGamePlayer = llm.copy();

            Path pg = newestPostgame(tmp);
            Assert.assertNotNull("the postgame file must be written", pg);
            String fileContent = readAll(pg);
            Assert.assertTrue("the file must carry the reflection content",
                    fileContent.contains(REFLECTION_BODY));
            Assert.assertTrue("the file must carry the match-review section",
                    fileContent.contains("--- REVIEW ---"));
            Assert.assertTrue("the file must carry the review content",
                    fileContent.contains(REVIEW_BODY));

            mage.view.LLMConversationView view = llm.getLLMDebugData();
            Assert.assertNotNull("the live view must be available (history seeded)", view);
            Assert.assertEquals("the SAME review string must reach view.matchReview",
                    REVIEW_BODY, view.matchReview);
            Assert.assertEquals("... and it must equal the stored field byte-for-byte",
                    llm.getMatchReview(), view.matchReview);
            Assert.assertEquals("the same view also carries the reflection",
                    REFLECTION_BODY, view.postGameReflection);

            // The replay route in a real Bo3: the NEXT game's player copy is the
            // object buildReplayViews reads. Its gamePlan is the revised
            // strategy — the sideboard summary's exact text becomes it verbatim
            // (ensureGamePlan's game-2+ branch), so the equivalence also holds
            // for the sideboard-file replay pin pinned in (c).
            Assert.assertEquals("matchReview must ride the copy constructor into game 2",
                    REVIEW_BODY, nextGamePlayer.getMatchReview());
        } finally {
            chatScript = null;
            deleteRecursively(tmp);
        }
    }

    /**
     * (c) Save-ordering rule, pinned: LLMPlayerHelper.buildReplayViews attaches
     * gamePlan/matchReview to the replay view but NEVER postGameReflection —
     * every save path writes the .game before the async reflection worker sets
     * the field, so it is always null on disk. The .txt postgame files are the
     * reflection record. The player here holds non-null values for all three
     * fields AND a recorded replay exchange (the non-null-guard buildReplayViews
     * requires); the attached replay view must carry exactly the two between-games
     * fields, never the reflection.
     */
    @Test
    public void replayViewAttachesGamePlanAndMatchReviewButNeverReflection() throws Exception {
        addCard(mage.constants.Zone.BATTLEFIELD, playerA, "Plains", 2);
        LLMPlayer fixture = (LLMPlayer) playerA.getComputerPlayer();
        Game gameRef = currentGame;

        java.lang.reflect.Field reviewField = LLMPlayer.class.getDeclaredField("matchReview");
        reviewField.setAccessible(true);
        reviewField.set(fixture, REVIEW_BODY);
        java.lang.reflect.Field reflectionField = LLMPlayer.class.getDeclaredField("postGameReflection");
        reflectionField.setAccessible(true);
        reflectionField.set(fixture, REFLECTION_BODY);
        java.lang.reflect.Field planField = LLMPlayer.class.getDeclaredField("gamePlan");
        planField.setAccessible(true);
        planField.set(fixture, "sideboard revised strategy body");

        // Tournament sub-table path: the object buildReplayViews reads in a real
        // Bo3 is the game-2 COPY of the seat (MatchImpl.initGame / copy ctor).
        // The copy deliberately starts with an EMPTY replay map (per-game reset)
        // — game 2's own exchanges fill it — while the between-games fields ride
        // the copy. isLLMPlayer accepts exactly this class (the harness subclass
        // name would not).
        LLMPlayer llm = fixture.copy();
        Assert.assertEquals("matchReview must ride the copy into the replay read",
                REVIEW_BODY, llm.getMatchReview());
        llm.recordReplayExchangeForTest(gameRef, 2, "priority", "game-2 exchange", "0");

        java.util.List<mage.view.LLMConversationView> views =
                mage.server.LLMPlayerHelper.buildReplayViews(llm, 2);
        Assert.assertNotNull("the replay view must be built from the recorded exchange", views);
        mage.view.LLMConversationView replayView = views.get(0);

        Assert.assertEquals("the between-games review must be attached to the replay view",
                REVIEW_BODY, replayView.matchReview);
        Assert.assertEquals("the between-games gameplan must be attached to the replay view",
                "sideboard revised strategy body", replayView.gamePlan);
        Assert.assertNull("postGameReflection must NEVER be attached on the replay path —"
                        + " the async worker sets it only AFTER the .game save was written;"
                        + " the postgame .txt files are its record",
                replayView.postGameReflection);
    }

    // ------------------------------------------------------------------
    // Equivalence helpers
    // ------------------------------------------------------------------

    /**
     * Seed the stub client's conversation history so getLLMDebugData() returns
     * a live (non-null) view — addFailureMessage adds a user message without
     * any LLM call.
     */
    private static void seedClientHistory(LLMPlayer llm) {
        ((TestLLMPlayer) llm).clientForTest().addFailureMessage("seeded conversation exchange");
    }

    /**
     * Flip the async worker's multi-game branch (matches the Bo3 shape):
     * matchPlayer with winsNeeded = 2 makes the worker also ask for a match
     * review. MatchImpl is abstract; the anonymous stub supplies the minimal
     * surface its construction reads (same shape as
     * LLMBetweenGamesReplayDataTest / LLMSideboardTerminalFailureTest).
     */
    private static void attachMultiGameMatchPlayer(LLMPlayer llm) {
        mage.game.match.MatchOptions opts =
                new mage.game.match.MatchOptions("postgame-equivalence", "Two Player Duel", false);
        opts.setWinsNeeded(2);
        mage.game.match.Match match = new mage.game.match.MatchImpl(opts) {
            @Override public mage.game.GameInfo createGameInfo(mage.game.Game game) { return null; }
            @Override public java.util.List<mage.game.GameInfo> getGamesInfo() {
                return java.util.Collections.emptyList();
            }
            @Override public java.util.Date getEndTime() { return null; }
            @Override public java.util.Date getStartTime() { return new java.util.Date(); }
            @Override public void startGame() { }
            @Override public mage.game.result.ResultProtos.MatchProto toProto() { return null; }
        };
        llm.setMatchPlayer(new mage.game.match.MatchPlayer(llm, new mage.cards.decks.Deck(), match));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Seed a real game-end-sized log. generatePostGameSummary() early-returns
     * when {@code turnNum <= 1 && log < 10} (the "don't waste an API call on an
     * instant concession" guard), so a fixture that expects the reflection to
     * run must present a log past that threshold — same seeding as
     * LLMPostGameSurvivesConcurrentLogTest.
     */
    private static void seedGameLog(Game game, int entries) {
        game.getState().setGameLogEnabled(true);
        for (int i = 0; i < entries; i++) {
            game.getState().addGameLog("[test] seeded game event " + i);
        }
    }

    /**
     * Mark the fixture as a STARTED game. inRange is filled by
     * PlayerImpl.updateRange() from beginTurn during real play; driving
     * won()/lostForced() directly on an unstarted fixture leaves it empty and
     * getOpponents() throws "game is not started, but you call
     * hasPlayerInRange" — a fixture artifact, not a production behavior. Seeded
     * for BOTH players (the win/loss cascade calls getOpponents from each side).
     */
    private static void seedStartedGameState(Game game) {
        for (mage.players.Player p : game.getPlayers().values()) {
            p.updateRange(game);
        }
    }

    /** Reflection text read from the volatile field (no public getter; same as the sibling test). */
    private static String llmReflection(LLMPlayer player) {
        try {
            java.lang.reflect.Field f = LLMPlayer.class.getDeclaredField("postGameReflection");
            f.setAccessible(true);
            return (String) f.get(player);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not read postGameReflection: " + e, e);
        }
    }

    /** Newest postgame file in the transcript dir (null when none). */
    private static Path newestPostgame(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().contains("_postgame-"))
                    .sorted()
                    .reduce((a, b) -> b)
                    .orElse(null);
        }
    }

    private static String readAll(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup only
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup only
        }
    }

    /** Captures log4j events emitted while the action runs (same shape as RestoreBookmarkRecoveryTest). */
    private static final class CapturingAppender extends AppenderSkeleton {
        final List<String> messages = Collections.synchronizedList(new ArrayList<String>());

        @Override
        protected void append(LoggingEvent event) {
            messages.add(String.valueOf(event.getMessage()));
        }

        @Override
        public void close() {
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }

        boolean contains(String needle) {
            synchronized (messages) {
                return messages.stream().anyMatch(m -> m != null && m.contains(needle));
            }
        }
    }
}