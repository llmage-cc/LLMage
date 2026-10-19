package org.mage.test.LLM;

import mage.player.llm.LLMPlayer;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Regression tests for the game-end ConcurrentModificationException

 *
 * <p>The crash: a web player left during the mulligan, the game ended, and
 * {@code LLMPlayer.won() → generatePostGameSummary()} copied the GameState game
 * log while {@code LLMGameLogCollector} was still appending to it from a
 * game-event thread. The copy threw CME, the RuntimeException killed the
 * game-end path, and the FATAL took the worker down:
 *
 * <pre>
 *   at mage.player.llm.LLMPlayer.generatePostGameSummary(LLMPlayer.java:3869)
 *   Caused by: java.util.ConcurrentModificationException (ArrayList$Itr)
 * </pre>
 *
 * <p>{@code LLMPlayer.copyLogWithRetry} is the shared snapshot helper now used by
 * the reflection pass and by the priority prompt builder. These tests hammer it
 * with a genuinely concurrent appender and assert it (a) never lets CME escape
 * and (b) returns a copy that is a faithful prefix of what was appended.
 *
 * <p>No network access required.
 */
public class LLMGameLogConcurrencyTest {

    /** Appends to a live ArrayList while the main thread snapshots it, repeatedly. */
    @Test
    public void snapshotSurvivesConcurrentAppend() throws Exception {
        final List<String> log = new ArrayList<>();
        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicReference<Throwable> appenderFailure = new AtomicReference<>();
        final CountDownLatch started = new CountDownLatch(1);

        // The appender also TRIMS, mirroring the real selfLog path: an unbounded
        // list would grow to hundreds of MB over the run and OOM the copy loop.
        Thread appender = new Thread(() -> {
            started.countDown();
            try {
                int i = 0;
                while (!stop.get()) {
                    synchronized (log) {
                        log.add("event " + (i++));
                        if (log.size() > 400) {
                            log.subList(0, log.size() - 400).clear();
                        }
                    }
                }
            } catch (Throwable t) {
                appenderFailure.set(t);
            }
        }, "test-game-log-appender");
        appender.start();
        Assert.assertTrue("appender should start", started.await(5, TimeUnit.SECONDS));

        try {
            // Fixed iteration count, not wall-clock: the appender grows the list
            // unboundedly, so a time-based loop ends up dominated by copying huge
            // lists (and would flake under CI contention).
            int snapshots = 0;
            for (int i = 0; i < 300; i++) {
                // The bug: this threw CME (or read a torn array) without the helper.
                List<String> snapshot = LLMPlayer.copyLogWithRetry(log, "test");
                Assert.assertNotNull(snapshot);
                // A snapshot must be a prefix of the append sequence: entries are
                // monotonically increasing integers, so any torn/garbled copy
                // (nulls, duplicated/out-of-order entries) shows up here.
                int prev = -1;
                for (String entry : snapshot) {
                    Assert.assertNotNull("snapshot must not contain nulls", entry);
                    int n = Integer.parseInt(entry.substring("event ".length()));
                    Assert.assertTrue("snapshot must stay ordered (got " + n + " after " + prev + ")",
                            n > prev);
                    prev = n;
                }
                snapshots++;
            }
            Assert.assertEquals("every iteration must have produced a snapshot", 300, snapshots);
        } finally {
            stop.set(true);
            appender.join(5000);
        }
        Assert.assertNull("appender thread must not have thrown: " + appenderFailure.get(),
                appenderFailure.get());
    }

    /** The helper still works on a quiescent log (the common case). */
    @Test
    public void snapshotOfStableLogIsACopy() {
        List<String> log = new ArrayList<>();
        log.add("a");
        log.add("b");
        List<String> snapshot = LLMPlayer.copyLogWithRetry(log, "test");
        Assert.assertEquals(2, snapshot.size());
        log.add("c");
        Assert.assertEquals("snapshot must not alias the live list", 2, snapshot.size());
        Assert.assertEquals(3, log.size());
    }

    /** Empty log → empty snapshot (the turn-1 early-return path reads the size). */
    @Test
    public void snapshotOfEmptyLog() {
        Assert.assertTrue(LLMPlayer.copyLogWithRetry(new ArrayList<String>(), "test").isEmpty());
    }

    /**
     * Deterministic proof that the retry branch is live: a list whose first two
     * copies race and succeed on the third must return the data (not throw), and
     * the retries must actually have been exercised. This is the mechanism that
     * keeps the game-end path alive when an append lands mid-copy, tested without
     * depending on winning a race.
     */
    @Test
    public void transientRacesAreRetriedUntilTheCopySucceeds() {
        final java.util.concurrent.atomic.AtomicInteger copyAttempts =
                new java.util.concurrent.atomic.AtomicInteger();
        List<String> flaky = new ArrayList<String>() {
            @Override
            public Object[] toArray() {
                if (copyAttempts.incrementAndGet() <= 2) {
                    throw new ConcurrentModificationException("simulated transient race");
                }
                return super.toArray();
            }
        };
        flaky.add("a");
        flaky.add("b");

        List<String> snapshot = LLMPlayer.copyLogWithRetry(flaky, "flaky");
        Assert.assertEquals("the retry must eventually return the real contents",
                java.util.Arrays.asList("a", "b"), snapshot);
        Assert.assertEquals("two races then a success = 3 copy attempts",
                3, copyAttempts.get());
    }

    /** A list that can never be copied fails LOUDLY rather than returning junk. */
    @Test
    public void permanentlyRacingLogFailsFast() {
        // A list whose toArray() always throws stands in for an append path that
        // can never be read consistently — the helper must surface that, not
        // hand back a partial log.
        List<String> hostile = new ArrayList<String>() {
            @Override
            public Object[] toArray() {
                throw new ConcurrentModificationException("simulated permanent race");
            }
        };
        hostile.add("x");
        try {
            LLMPlayer.copyLogWithRetry(hostile, "hostile");
            Assert.fail("expected IllegalStateException after exhausting snapshot attempts");
        } catch (IllegalStateException e) {
            Assert.assertTrue("message should name the label, got: " + e.getMessage(),
                    e.getMessage().contains("hostile"));
            Assert.assertTrue("the CME cause must be preserved",
                    e.getCause() instanceof ConcurrentModificationException);
        }
    }
}
