package org.mage.test.game;

import mage.game.GameState;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Regression tests for the engine-side synchronized append in
 * {@link GameState#addGameLog(String)} (the capture race
 * of a concurrent append).
 *
 * <p>The crash it fixes: the game-end reflection copied the live game log while
 * {@code LLMGameLogCollector} appended to it from a game-event thread; the copy
 * threw {@code ConcurrentModificationException}. The plugin-side snapshot
 * helpers ({@code LLMPlayer.copyLogWithRetry}, {@code LLMGameSerializer}) lock
 * the LIST instance, so the engine append must take that SAME monitor to make
 * the race impossible instead of merely retryable.
 *
 * <p>These tests are deliberately deterministic where possible: the first one
 * proves the append blocks on the list monitor (no timing luck needed); the
 * second hammers append+copy concurrently and asserts no torn copy escapes.
 */
public class GameLogConcurrentAppendTest {

    private static GameState stateWithLogEnabled() {
        GameState state = new GameState();
        state.setGameLogEnabled(true);
        return state;
    }

    /**
     * Deterministic proof of the monitor: while the test thread holds the log
     * list's monitor, a concurrent addGameLog call must NOT complete. An
     * append synchronized on anything other than the list (e.g. on the
     * GameState instance) — or not synchronized at all — completes immediately
     * and fails this test.
     */
    @Test
    public void appendBlocksOnTheGameLogMonitor() throws Exception {
        final GameState state = stateWithLogEnabled();
        final AtomicBoolean enteredCall = new AtomicBoolean(false);
        final CountDownLatch appended = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        final Thread appender = new Thread(() -> {
            enteredCall.set(true);
            try {
                state.addGameLog("blocked-until-monitor-released");
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                appended.countDown();
            }
        }, "test-gamelog-append-monitor");

        synchronized (state.getGameLog()) {
            appender.start();
            // Wait until the appender is about to enter addGameLog, so the
            // negative assertion below is about the lock, not about scheduling.
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!enteredCall.get() && System.nanoTime() < deadline) {
                Thread.sleep(1L);
            }
            Assert.assertTrue("appender thread must reach the addGameLog call", enteredCall.get());
            // It must still be blocked: the append acquires the list monitor.
            Assert.assertFalse("addGameLog must not complete while the log monitor is held",
                    appended.await(200, TimeUnit.MILLISECONDS));
        }

        // Released: the append proceeds and the entry lands.
        Assert.assertTrue("addGameLog must complete once the monitor is released",
                appended.await(5, TimeUnit.SECONDS));
        Assert.assertNull("appender must not have thrown: " + failure.get(), failure.get());
        Assert.assertEquals(1, state.getGameLog().size());
        Assert.assertEquals("blocked-until-monitor-released", state.getGameLog().get(0));
    }

    /**
     * Concurrent hammering: two appender threads add indexed entries while the
     * test thread snapshots under the same monitor, exactly the way
     * {@code LLMPlayer.copyLogWithRetry} / {@code LLMGameSerializer} do.
     * A copy must never contain nulls, must never throw, and each appender's
     * entries within a snapshot must appear in order (they are appended under
     * the shared monitor, so no two appends can interleave inside one entry).
     */
    @Test
    public void concurrentAppendAndSnapshotNeverTears() throws Exception {
        final GameState state = stateWithLogEnabled();
        final int perThread = 2000;
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch started = new CountDownLatch(2);
        final List<String> log = state.getGameLog();

        Thread[] appenders = new Thread[2];
        for (int t = 0; t < appenders.length; t++) {
            final int threadId = t;
            appenders[t] = new Thread(() -> {
                started.countDown();
                try {
                    for (int i = 0; i < perThread; i++) {
                        state.addGameLog("t" + threadId + "-e" + i);
                    }
                } catch (Throwable ex) {
                    failure.set(ex);
                }
            }, "test-gamelog-appender-" + t);
            appenders[t].start();
        }
        Assert.assertTrue("appenders should start", started.await(5, TimeUnit.SECONDS));

        // Wait until the writers are meaningfully interleaved with the reads;
        // the deterministic monitor test above covers the lock semantics if
        // this box is too fast to overlap.
        final long overlapDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (log.size() < 10 && System.nanoTime() < overlapDeadline) {
            Thread.sleep(1L);
        }

        try {
            for (int i = 0; i < 300; i++) {
                List<String> snapshot;
                // The reader contract the engine append must honor: copy under
                // the list's own monitor.
                synchronized (log) {
                    snapshot = new ArrayList<>(log);
                }
                // Within one snapshot each appender's entries appear in the
                // order that thread appended them: two racing appends can only
                // be ordered one-after-the-other under the shared monitor, and
                // a torn read (null / garbled / reordered entry) fails here.
                int[] seenThisSnapshot = {-1, -1};
                for (String entry : snapshot) {
                    Assert.assertNotNull("no null entries may survive the copy", entry);
                    Assert.assertTrue("entry should be a real game-log line, got: " + entry,
                            entry.startsWith("t"));
                    int sep = entry.indexOf("-e");
                    int threadId = entry.charAt(1) - '0';
                    int n = Integer.parseInt(entry.substring(sep + 2));
                    Assert.assertTrue("entries of one appender must stay ordered in a snapshot"
                                    + " (thread " + threadId + ": got " + n
                                    + " after " + seenThisSnapshot[threadId] + ")",
                            n > seenThisSnapshot[threadId]);
                    seenThisSnapshot[threadId] = n;
                }
            }
        } finally {
            for (Thread appender : appenders) {
                appender.join(5000);
            }
        }
        Assert.assertNull("appenders must not have thrown: " + failure.get(), failure.get());

        // After the writers finish, nothing may have been lost: every append
        // that went through the synchronized path is present exactly once.
        Assert.assertEquals(2 * perThread, log.size());
    }
}
