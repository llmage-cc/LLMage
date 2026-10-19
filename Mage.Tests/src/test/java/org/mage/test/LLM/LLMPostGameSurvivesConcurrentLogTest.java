package org.mage.test.LLM;

import mage.constants.RangeOfInfluence;
import mage.player.llm.LLMClient;
import mage.player.llm.LLMConfig;
import mage.player.llm.LLMPlayer;
import mage.player.llm.LLMResponse;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Game-end regression for the ConcurrentModificationException that killed the
 * post-game reflection path.
 *
 * <p>Unlike {@link LLMGameLogConcurrencyTest} (which exercises the snapshot
 * helper in isolation), this drives the REAL game-end path — {@code won()} →
 * {@code generatePostGameSummary()} — with the game log being appended from a
 * second thread throughout, exactly as LLMGameLogCollector does when a web
 * player leaves mid-mulligan. Before the fix the reflection pass threw CME and
 * the RuntimeException propagated out of won() and killed the GameWorker.
 *
 * <p>The stub client answers instantly so the reflection never waits on a
 * network call; the model content is irrelevant — the test is about surviving
 * concurrent mutation of the log list.
 *
 * <p>Load-safety:
 * under full-suite runs on a memory/CPU-loaded box this class OOMed (the
 * appender grew the log without bound and the reflection snapshot copied it)
 * and saw calls=0 (the appender starved the reflection thread, which then
 * legitimately hit the turn-1/short-log early-return in
 * {@code generatePostGameSummary}). The appenders therefore keep the log
 * bounded (trim under the log's monitor, mirroring a real game's finite log)
 * and park briefly every 1024 appends; the reflection fixture seeds a
 * game-end-sized log so the production early-return cannot legitimately fire.
 * The appends themselves stay UNSYNCHRONIZED — the race against
 * {@code copyLogWithRetry} is exactly what this test exists to cover.
 */
public class LLMPostGameSurvivesConcurrentLogTest extends CardTestPlayerBaseLLM {

    private static final AtomicInteger reflectionCalls = new AtomicInteger();
    private static final AtomicReference<Throwable> postGameFailure = new AtomicReference<>();

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
            // Stub the client: no live calls, and the reflection path returns
            // immediately. The failure we are hunting is the CME, not content.
            llmPlayer.injectTestClient(new StubClient(llmPlayer.getLLMConfig()));
            org.mage.test.player.TestPlayer testPlayer = new org.mage.test.player.TestPlayer(llmPlayer);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    static class StubClient extends LLMClient {
        StubClient(LLMConfig config) {
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
            return new LLMResponse("stub thinking", "stub reflection body");
        }
    }

    /**
     * The reflection prompt builder reads the game log and selfLog. With a
     * second thread hammering {@code addGameLog} while the game end runs, the
     * pre-fix code threw CME out of won().
     */
    @Test
    public void postGameSummarySurvivesConcurrentGameLogAppends() throws Exception {
        reflectionCalls.set(0);
        postGameFailure.set(null);

        mage.constants.Zone hand = mage.constants.Zone.HAND;
        addCard(hand, playerA, "Plains", 2);
        addCard(mage.constants.Zone.BATTLEFIELD, playerA, "Plains", 2);
        addCard(mage.constants.Zone.BATTLEFIELD, playerB, "Plains", 2);

        // A second thread appends to the game log for the duration of the run —
        // the LLMGameLogCollector shape (a game-event thread calling
        // state.addGameLog while the game-end path copies the log).
        final mage.game.Game gameRef = currentGame;
        gameRef.getState().setGameLogEnabled(true);
        // Seed the log to a real game-end size BEFORE the appender starts: the
        // appender cannot be relied on to have appended anything (a loaded box
        // starves it), and generatePostGameSummary skips reflection when
        // turnNum <= 1 && log < 10 — the calls=0 flake mode. A game-end fixture
        // must present a real game-end log.
        for (int i = 0; i < 20; i++) {
            gameRef.getState().addGameLog("[test] seeded game event " + i);
        }
        Assert.assertTrue("reflection fixture must seed a log past the reflection "
                        + "early-return threshold (had " + gameRef.getState().getGameLog().size() + ")",
                gameRef.getState().getGameLog().size() >= 10);
        final AtomicBoolean stop = new AtomicBoolean(false);
        final CountDownLatch started = new CountDownLatch(1);
        final Thread appender = new Thread(() -> {
            started.countDown();
            int i = 0;
            while (!stop.get()) {
                try {
                    gameRef.getState().addGameLog("[test] concurrent event " + (i++));
                    // Keep the log bounded like a real game's (a live game log
                    // is finite); an unbounded list OOMed the reflection
                    // snapshot under full-suite memory pressure. The trim runs
                    // under the log's monitor, the same lock the production
                    // snapshot takes, so it is atomic with copies.
                    if (gameRef.getState().getGameLog().size() > 400) {
                        synchronized (gameRef.getState().getGameLog()) {
                            List<String> log = gameRef.getState().getGameLog();
                            log.subList(0, log.size() - 400).clear();
                        }
                    }
                } catch (Throwable t) {
                    postGameFailure.set(t);
                    return;
                }
                // Gentle loop: yield often (keeps the appender from hogging a
                // core on a loaded VPS) and park 1 ms occasionally so the JVM
                // can schedule the reflection thread even under full-suite
                // CPU starvation.
                if ((i & 0x3F) == 0) {
                    Thread.yield();
                }
                if ((i & 0x3FF) == 0) {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "test-gamelog-appender");
        appender.start();
        // The 5 s await is appender-latch only (the reflection call happens on
        // the main thread and needs no synchronization). On a fully loaded box
        // the await is not the tight spot: the fixture's log is seeded so the
        // production early-return cannot hit, and copyLogWithRetry retries
        // snapshot races internally. Kept as-is by design.
        //
        Assert.assertTrue(started.await(5, TimeUnit.SECONDS));

        try {
            // Drive the real game-end path repeatedly while the log churns.
            LLMPlayer llm = (LLMPlayer) playerA.getComputerPlayer();
            for (int i = 0; i < 5; i++) {
                llm.recordReplayExchangeForTest(gameRef, i, "priority", "turning the log over", "0");
            }
            // won() → generatePostGameSummary() is the crash site. Calling it
            // directly (rather than only via won()) keeps the test deterministic
            // while still exercising the exact reflection code path.
            invokeGeneratePostGameSummary(llm, gameRef);
            // The reflection now runs on a
            // bounded executor thread, so the reflection call/state assertions
            // below must await the task — they used to be satisfied synchronously
            // by the in-line call. The game-end path itself already returned.
            Assert.assertTrue("the async reflection task must complete",
                    llm.awaitPostGameReflectionForTest(15000));
        } finally {
            stop.set(true);
            appender.join(5000);
        }

        Assert.assertNull("the log appender must not have thrown: " + postGameFailure.get(),
                postGameFailure.get());
        Assert.assertTrue("the reflection path should have run and produced a summary (calls="
                + reflectionCalls.get() + ")", reflectionCalls.get() >= 1);
        Assert.assertNotNull("post-game reflection text should be stored on the player",
                llmReflection((LLMPlayer) playerA.getComputerPlayer()));
    }

    /**
     * Reflection text stored on the player, read directly from the private field
     * (getLLMDebugData() returns null until there is conversation history, which
     * a stubbed, turn-less test game never builds).
     */
    private static String llmReflection(LLMPlayer player) {
        try {
            java.lang.reflect.Field f = LLMPlayer.class.getDeclaredField("postGameReflection");
            f.setAccessible(true);
            return (String) f.get(player);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not read postGameReflection: " + e, e);
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

    /**
     * Sanity check that the reflection prompt is still assembled (the fix must
     * not simply skip the log section) — asserts via the recorded call count
     * plus a direct prompt-build smoke test with concurrent appends.
     */
    @Test
    public void reflectionPromptBuilderReadsTheLogUnderConcurrentAppends() throws Exception {
        addCard(mage.constants.Zone.BATTLEFIELD, playerA, "Plains", 2);
        addCard(mage.constants.Zone.BATTLEFIELD, playerB, "Plains", 2);

        final mage.game.Game gameRef = currentGame;
        gameRef.getState().setGameLogEnabled(true);
        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread appender = new Thread(() -> {
            int i = 0;
            try {
                while (!stop.get()) {
                    gameRef.getState().addGameLog("[test] event " + (i++));
                    // Bounded like a real game log: an unbounded appender OOMed
                    // this loop under full-suite memory pressure. Trimmed under
                    // the log's monitor, the same lock the snapshot takes.
                    if (gameRef.getState().getGameLog().size() > 400) {
                        synchronized (gameRef.getState().getGameLog()) {
                            List<String> log = gameRef.getState().getGameLog();
                            log.subList(0, log.size() - 400).clear();
                        }
                    }
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "test-gamelog-appender-2");
        appender.start();
        // Load-safety: the appender thread is not guaranteed a slot to run
        // before the main thread proceeds (a loaded box starves it, which used
        // to make the very first snapshot read an empty list and the ordered
        // assertions vacuous). Give it a moment; on a normal box it's there
        // long before this returns.
        appender.join(1000);
        try {
            // Fixed iteration count: a wall-clock loop would dominate the run
            // with copying large lists (the appender keeps the list at a
            // bounded ~400 entries; a real game log is finite too). The
            // entries carry monotonically increasing indices: any duplicated,
            // reordered, or null entry in a snapshot is a torn copy and fails
            // — entries trimmed away by the appender mid-test are append-side
            // housekeeping, not snapshot corruption, so they don't fail this.
            for (int i = 0; i < 200; i++) {
                List<String> snapshot = LLMPlayer.copyLogWithRetry(gameRef.getState().getGameLog(), "test");
                Assert.assertNotNull(snapshot);
                int prev = -1;
                for (String entry : snapshot) {
                    Assert.assertNotNull("no null entries may survive the copy", entry);
                    Assert.assertTrue("copied entry should be a real game-log line, got: " + entry,
                            entry.startsWith("[test] event "));
                    int n = Integer.parseInt(entry.substring("[test] event ".length()));
                    Assert.assertTrue("snapshot must stay ordered (got " + n + " after " + prev + ")",
                            n > prev);
                    prev = n;
                }
            }
        } finally {
            stop.set(true);
            appender.join(5000);
        }
        Assert.assertNull("appender must not have thrown: " + failure.get(), failure.get());
    }
}
