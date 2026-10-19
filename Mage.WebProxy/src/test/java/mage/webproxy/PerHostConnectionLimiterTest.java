package mage.webproxy;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for the per-host concurrent-connection cap

 *
 * <p>Contract pinned here:
 *  - strict system-property validation: unset/0 = unlimited, garbage and negative
 *    values throw {@link IllegalStateException} (fail fast at boot, like
 *    {@code webproxy.hideServerTarget});
 *  - acquire/refuse semantics: the N+1-th concurrent reservation for one target is
 *    refused, while other targets are unaffected;
 *  - release semantics: a released slot is reusable, a double release reports
 *    "not held" without corrupting the count, and a zero-count entry is dropped
 *    so a long-lived proxy does not accumulate per-target state;
 *  - concurrent safety: many threads racing one target cannot overshoot the cap.
 *
 * No server, no XMage: the limiter is a pure in-JVM counter (the session-level
 * wiring is exercised live by the integration probe).
 */
public class PerHostConnectionLimiterTest {

    private static final String PROP = PerHostConnectionLimiter.MAX_PER_HOST_PROPERTY;

    @After
    public void clearProp() {
        System.clearProperty(PROP);
    }

    // ── property validation ──

    @Test
    public void unsetPropertyMeansUnlimited() {
        System.clearProperty(PROP);
        Assert.assertEquals("unset must mean unlimited (current behavior preserved)",
                PerHostConnectionLimiter.UNLIMITED, PerHostConnectionLimiter.connectionCap());
    }

    @Test
    public void blankPropertyMeansUnlimited() {
        System.setProperty(PROP, "   ");
        Assert.assertEquals(PerHostConnectionLimiter.UNLIMITED,
                PerHostConnectionLimiter.connectionCap());
    }

    @Test
    public void zeroMeansUnlimited() {
        System.setProperty(PROP, "0");
        Assert.assertEquals("0 is the documented unlimited value — not a cap of zero",
                PerHostConnectionLimiter.UNLIMITED, PerHostConnectionLimiter.connectionCap());
    }

    @Test
    public void positiveValueIsTheCap() {
        System.setProperty(PROP, "2");
        Assert.assertEquals(2, PerHostConnectionLimiter.connectionCap());
        System.setProperty(PROP, " 7 "); // whitespace tolerated
        Assert.assertEquals(7, PerHostConnectionLimiter.connectionCap());
    }

    @Test
    public void garbageValueFailsFast() {
        System.setProperty(PROP, "two");
        try {
            PerHostConnectionLimiter.connectionCap();
            Assert.fail("a non-numeric cap must throw, not degrade to unlimited");
        } catch (IllegalStateException expected) {
            Assert.assertTrue("the message must name the property: " + expected.getMessage(),
                    expected.getMessage().contains(PROP));
        }
    }

    @Test
    public void negativeValueFailsFast() {
        System.setProperty(PROP, "-1");
        try {
            PerHostConnectionLimiter.connectionCap();
            Assert.fail("a negative cap must throw");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains(PROP));
        }
    }

    @Test
    public void floatingPointValueFailsFast() {
        System.setProperty(PROP, "1.5");
        try {
            PerHostConnectionLimiter.connectionCap();
            Assert.fail("a fractional cap must throw (no silent truncation)");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains(PROP));
        }
    }

    // ── acquire / refuse ──

    @Test
    public void capRefusesTheNPlusOneThAcquire() {
        PerHostConnectionLimiter limiter = new PerHostConnectionLimiter();
        Assert.assertTrue(limiter.acquire("127.0.0.1", 17171, 2));
        Assert.assertTrue(limiter.acquire("127.0.0.1", 17171, 2));
        Assert.assertFalse("the 3rd concurrent connection must be refused",
                limiter.acquire("127.0.0.1", 17171, 2));
        Assert.assertEquals("a refused acquire must not count", 2, limiter.countFor("127.0.0.1", 17171));
    }

    @Test
    public void unlimitedCapNeverRefusesAndCountsNothing() {
        PerHostConnectionLimiter limiter = new PerHostConnectionLimiter();
        for (int i = 0; i < 50; i++) {
            Assert.assertTrue(limiter.acquire("127.0.0.1", 17171, PerHostConnectionLimiter.UNLIMITED));
        }
        Assert.assertEquals("unlimited mode must not track state at all",
                0, limiter.trackedHosts());
    }

    @Test
    public void capIsPerHostNotGlobal() {
        PerHostConnectionLimiter limiter = new PerHostConnectionLimiter();
        Assert.assertTrue(limiter.acquire("host-a", 17171, 1));
        Assert.assertFalse("host-a is at its cap", limiter.acquire("host-a", 17171, 1));
        Assert.assertTrue("a different host must not be affected by host-a's cap",
                limiter.acquire("host-b", 17171, 1));
        Assert.assertTrue("a different PORT is a different target too",
                limiter.acquire("host-a", 17172, 1));
        Assert.assertEquals(3, limiter.trackedHosts());
    }

    // ── release semantics ──

    @Test
    public void releaseFreesTheSlotForReuse() {
        PerHostConnectionLimiter limiter = new PerHostConnectionLimiter();
        String key = PerHostConnectionLimiter.hostKey("127.0.0.1", 17171);
        Assert.assertTrue(limiter.acquire("127.0.0.1", 17171, 1));
        Assert.assertFalse(limiter.acquire("127.0.0.1", 17171, 1));
        Assert.assertTrue("the held slot must release", limiter.release(key));
        Assert.assertTrue("and the freed slot must be reusable", limiter.acquire("127.0.0.1", 17171, 1));
    }

    @Test
    public void releaseOfAnUnheldKeyReportsFalseAndKeepsTheEntryGone() {
        PerHostConnectionLimiter limiter = new PerHostConnectionLimiter();
        String key = PerHostConnectionLimiter.hostKey("127.0.0.1", 17171);
        Assert.assertFalse("releasing a slot nobody holds is a bookkeeping error, not a no-op",
                limiter.release(key));
        Assert.assertEquals(0, limiter.trackedHosts());

        // ...and it must not resurrect a phantom count for a live host.
        PerHostConnectionLimiter other = new PerHostConnectionLimiter();
        other.acquire("127.0.0.1", 17171, 2);
        other.release(PerHostConnectionLimiter.hostKey("127.0.0.1", 17171));
        other.release(PerHostConnectionLimiter.hostKey("127.0.0.1", 17171)); // double release
        Assert.assertEquals("a double release must not push the count below its floor",
                0, other.countFor("127.0.0.1", 17171));
        Assert.assertEquals(0, other.trackedHosts());
    }

    @Test
    public void zeroCountEntriesAreDropped() {
        PerHostConnectionLimiter limiter = new PerHostConnectionLimiter();
        limiter.acquire("127.0.0.1", 17171, 3);
        limiter.acquire("127.0.0.1", 17171, 3);
        Assert.assertEquals(1, limiter.trackedHosts());
        limiter.release(PerHostConnectionLimiter.hostKey("127.0.0.1", 17171));
        Assert.assertEquals(1, limiter.trackedHosts());
        limiter.release(PerHostConnectionLimiter.hostKey("127.0.0.1", 17171));
        Assert.assertEquals("the last release must drop the entry (no leak across cycles)",
                0, limiter.trackedHosts());
    }

    @Test
    public void openCloseCyclesDoNotLeakSlots() {
        PerHostConnectionLimiter limiter = new PerHostConnectionLimiter();
        String key = PerHostConnectionLimiter.hostKey("127.0.0.1", 17171);
        for (int i = 0; i < 20; i++) {
            Assert.assertTrue("cycle " + i + ": a slot must be available",
                    limiter.acquire("127.0.0.1", 17171, 1));
            Assert.assertTrue(limiter.release(key));
        }
        Assert.assertEquals("20 open/close cycles must leave zero state", 0, limiter.trackedHosts());
    }

    // ── key validation ──

    @Test
    public void hostKeyRejectsUnusableTargets() {
        try {
            PerHostConnectionLimiter.hostKey(null, 17171);
            Assert.fail("a null host must not pool under an empty key");
        } catch (IllegalArgumentException expected) {
            Assert.assertNotNull(expected.getMessage());
        }
        try {
            PerHostConnectionLimiter.hostKey("127.0.0.1", 0);
            Assert.fail("port 0 is not a usable target");
        } catch (IllegalArgumentException expected) {
            Assert.assertNotNull(expected.getMessage());
        }
        try {
            PerHostConnectionLimiter.hostKey("127.0.0.1", 70000);
            Assert.fail("an out-of-range port must be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertNotNull(expected.getMessage());
        }
    }

    // ── concurrent safety ──

    @Test
    public void concurrentAcquiresCannotOvershootTheCap() throws Exception {
        final int cap = 4;
        final int threads = 32;
        final PerHostConnectionLimiter limiter = new PerHostConnectionLimiter();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return limiter.acquire("127.0.0.1", 17171, cap);
                }));
            }
            start.countDown();
            AtomicInteger granted = new AtomicInteger();
            for (Future<Boolean> f : futures) {
                if (f.get(30, TimeUnit.SECONDS)) {
                    granted.incrementAndGet();
                }
            }
            Assert.assertEquals("exactly the cap may be granted under a 32-way race",
                    cap, granted.get());
            Assert.assertEquals(cap, limiter.countFor("127.0.0.1", 17171));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void concurrentAcquireReleaseKeepsTheCountConsistent() throws Exception {
        final int cap = 4;
        final int threads = 16;
        final int perThread = 50;
        final PerHostConnectionLimiter limiter = new PerHostConnectionLimiter();
        final String key = PerHostConnectionLimiter.hostKey("127.0.0.1", 17171);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        boolean acquired = limiter.acquire("127.0.0.1", 17171, cap);
                        if (acquired) {
                            limiter.release(key);
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            Assert.assertEquals("all acquire/release pairs are balanced — no leak, no drift",
                    0, limiter.countFor("127.0.0.1", 17171));
            Assert.assertEquals(0, limiter.trackedHosts());
        } finally {
            pool.shutdownNow();
        }
    }
}
