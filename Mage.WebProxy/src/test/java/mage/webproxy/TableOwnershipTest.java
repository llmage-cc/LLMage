package mage.webproxy;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.UUID;

/**
 * Unit tests for {@link TableOwnership} — the per-username cache of PROVEN table
 * ownership.

 *
 * <p>The cache is what makes the proxy-side ownership flag affordable: the table list
 * is serialized on every get_tables reply AND on every table-watch push (every 2 s
 * while seated), and a probe per table per push would put an RPC storm on the server.
 * The contract pinned here is the one the class comment states:
 *
 * <ul>
 *   <li>a POSITIVE verdict never expires — ownership is immutable for a live table (it
 *       is the creating session's user id on the TableController);</li>
 *   <li>a NEGATIVE verdict holds for {@link TableOwnership#NEGATIVE_TTL_MS} only, then
 *       the next read re-probes — a probe that failed for a transient remoting reason
 *       answers {@code false} too (SessionImpl swallows RPC failures), so a cache entry
 *       pinned on that must not outlive the TTL;</li>
 *   <li>both maps are LRU-bounded (per-user tables and total users) so a long-lived
 *       proxy cannot accumulate state for accounts/tables that stopped existing;</li>
 *   <li>null/empty keys are no-ops, not NPEs — the call sites pass whatever the session
 *       holds.</li>
 * </ul>
 *
 * <p>Time is an explicit parameter on purpose ({@code cached(username, tableId, nowMs)}):
 * the TTL rule is a pure function of state and clock, so these tests inject {@code nowMs}
 * and never sleep. That is the same reason the class exists as a standalone unit — a
 * {@code System.currentTimeMillis()} inside would make the boundary untestable.
 */
public class TableOwnershipTest {

    private static final long NOW = 1_700_000_000_000L;

    @Before
    public void clearBefore() {
        TableOwnership.clear();
    }

    @After
    public void clearAfter() {
        TableOwnership.clear();
    }

    // ── miss / hit basics ──

    @Test
    public void unknownPairIsAMiss() {
        Assert.assertNull("a never-seen (user, table) must be a miss",
                TableOwnership.cached("alice", UUID.randomUUID(), NOW));
    }

    @Test
    public void rememberedPositiveIsReturned() {
        UUID table = UUID.randomUUID();
        TableOwnership.remember("alice", table, true, NOW);
        Assert.assertEquals(Boolean.TRUE, TableOwnership.cached("alice", table, NOW));
    }

    @Test
    public void rememberedNegativeIsReturnedWithinTtl() {
        UUID table = UUID.randomUUID();
        TableOwnership.remember("bob", table, false, NOW);
        Assert.assertEquals(Boolean.FALSE, TableOwnership.cached("bob", table, NOW));
        Assert.assertEquals(Boolean.FALSE,
                TableOwnership.cached("bob", table, NOW + TableOwnership.NEGATIVE_TTL_MS));
    }

    // ── positive: immortal ──

    @Test
    public void positiveVerdictNeverExpires() {
        UUID table = UUID.randomUUID();
        TableOwnership.remember("alice", table, true, NOW);
        long farFuture = NOW + 30L * 24 * 60 * 60 * 1000; // 30 days
        Assert.assertEquals("a positive verdict must not expire — ownership is immutable",
                Boolean.TRUE, TableOwnership.cached("alice", table, farFuture));
    }

    // ── negative: TTL expiry, then the caller re-probes ──

    @Test
    public void negativeVerdictExpiresAfterTtl() {
        UUID table = UUID.randomUUID();
        TableOwnership.remember("bob", table, false, NOW);
        Assert.assertNull("one millisecond past the TTL the entry must be a miss again",
                TableOwnership.cached("bob", table, NOW + TableOwnership.NEGATIVE_TTL_MS + 1));
    }

    @Test
    public void expiredNegativeIsDroppedFromTheTableMap() {
        UUID table = UUID.randomUUID();
        TableOwnership.remember("bob", table, false, NOW);
        Assert.assertEquals(1, TableOwnership.trackedTables("bob"));
        Assert.assertNull(TableOwnership.cached("bob", table, NOW + TableOwnership.NEGATIVE_TTL_MS + 1));
        Assert.assertEquals("reading past the TTL must evict, not just miss",
                0, TableOwnership.trackedTables("bob"));
    }

    @Test
    public void reRememberingRefreshesTheNegativeClock() {
        UUID table = UUID.randomUUID();
        TableOwnership.remember("bob", table, false, NOW);
        long later = NOW + TableOwnership.NEGATIVE_TTL_MS + 10;
        TableOwnership.remember("bob", table, false, later); // a fresh probe answered again
        Assert.assertEquals("an entry re-confirmed later must live a full TTL from THEN",
                Boolean.FALSE, TableOwnership.cached("bob", table, later + TableOwnership.NEGATIVE_TTL_MS));
    }

    @Test
    public void positiveUpgradesANegative() {
        UUID table = UUID.randomUUID();
        TableOwnership.remember("alice", table, false, NOW);
        TableOwnership.remember("alice", table, true, NOW);
        Assert.assertEquals("a later positive must replace the negative (and then never expire)",
                Boolean.TRUE,
                TableOwnership.cached("alice", table, NOW + 10L * TableOwnership.NEGATIVE_TTL_MS));
    }

    // ── LRU: per-user table cap ──

    @Test
    public void perUserTableCapEvictsOldest() {
        int cap = TableOwnership.MAX_TABLES_PER_USER;
        UUID first = UUID.randomUUID();
        TableOwnership.remember("alice", first, true, NOW);
        for (int i = 1; i < cap; i++) {
            TableOwnership.remember("alice", UUID.randomUUID(), true, NOW + i);
        }
        Assert.assertEquals(cap, TableOwnership.trackedTables("alice"));
        TableOwnership.remember("alice", UUID.randomUUID(), true, NOW + cap);
        Assert.assertEquals("the per-user cap must hold after overflow",
                cap, TableOwnership.trackedTables("alice"));
        Assert.assertNull("the oldest table entry must have been evicted",
                TableOwnership.cached("alice", first, NOW + cap));
    }

    @Test
    public void reRememberingATableRefreshesItsLruPosition() {
        int cap = TableOwnership.MAX_TABLES_PER_USER;
        UUID old = UUID.randomUUID();
        TableOwnership.remember("alice", old, true, NOW);
        for (int i = 1; i < cap; i++) {
            TableOwnership.remember("alice", UUID.randomUUID(), true, NOW + i);
        }
        // Touch the OLDEST entry (a fresh probe confirmed it) — it must not be the victim now.
        TableOwnership.remember("alice", old, true, NOW + 100);
        TableOwnership.remember("alice", UUID.randomUUID(), true, NOW + 101);
        Assert.assertEquals("the re-touched entry must survive the overflow",
                Boolean.TRUE, TableOwnership.cached("alice", old, NOW + 101));
    }

    // ── LRU: user cap ──

    @Test
    public void userCapEvictsLeastRecentlyUsedAccount() {
        int cap = TableOwnership.MAX_USERS;
        UUID oldUsersTable = UUID.randomUUID();
        UUID firstUsersTable = UUID.randomUUID();
        TableOwnership.remember("user0", oldUsersTable, true, NOW);
        TableOwnership.remember("user1", firstUsersTable, true, NOW + 1);
        for (int i = 2; i < cap; i++) {
            TableOwnership.remember("user" + i, UUID.randomUUID(), true, NOW + i);
        }
        Assert.assertEquals(cap, TableOwnership.trackedUsers());
        // Touch user0's map (a read counts as a use — access-order map).
        Assert.assertEquals(Boolean.TRUE, TableOwnership.cached("user0", oldUsersTable, NOW + cap));
        // Overflow: user1 is now the least recently used account.
        TableOwnership.remember("newcomer", UUID.randomUUID(), true, NOW + cap + 1);
        Assert.assertEquals("the user cap must hold after overflow",
                cap, TableOwnership.trackedUsers());
        Assert.assertNull("the least recently used account must have been evicted",
                TableOwnership.cached("user1", firstUsersTable, NOW + cap + 1));
        Assert.assertEquals("the recently touched account must survive",
                Boolean.TRUE, TableOwnership.cached("user0", oldUsersTable, NOW + cap + 1));
    }

    // ── null / empty guards ──

    @Test
    public void nullAndEmptyKeysAreNoOps() {
        UUID table = UUID.randomUUID();
        Assert.assertNull(TableOwnership.cached(null, table, NOW));
        Assert.assertNull(TableOwnership.cached("", table, NOW));
        Assert.assertNull(TableOwnership.cached("alice", null, NOW));

        TableOwnership.remember(null, table, true, NOW);
        TableOwnership.remember("", table, true, NOW);
        TableOwnership.remember("alice", null, true, NOW);
        Assert.assertEquals("no malformed remember may create an entry", 0, TableOwnership.trackedUsers());
    }

    // ── hasPositiveVerdicts: the cleanup() keep signal ──

    @Test
    public void hasPositiveVerdictsIsFalseWhenNothingIsKnown() {
        Assert.assertFalse("no entries at all", TableOwnership.hasPositiveVerdicts("alice"));
        Assert.assertFalse("null and empty are no-ops", TableOwnership.hasPositiveVerdicts(null));
        Assert.assertFalse("empty username", TableOwnership.hasPositiveVerdicts(""));
    }

    @Test
    public void hasPositiveVerdictsIgnoresNegatives() {
        TableOwnership.remember("alice", UUID.randomUUID(), false, NOW);
        Assert.assertFalse("a negative verdict must never hold a session open",
                TableOwnership.hasPositiveVerdicts("alice"));
    }

    @Test
    public void hasPositiveVerdictsIsTrueWithOnePositiveAmongMany() {
        TableOwnership.remember("alice", UUID.randomUUID(), false, NOW);
        TableOwnership.remember("alice", UUID.randomUUID(), false, NOW);
        TableOwnership.remember("alice", UUID.randomUUID(), true, NOW);
        Assert.assertTrue(TableOwnership.hasPositiveVerdicts("alice"));
        Assert.assertFalse("other accounts are unaffected", TableOwnership.hasPositiveVerdicts("bob"));
    }

    @Test
    public void hasPositiveVerdictsStaysTrueAfterTheNegativeTtl() {
        // The keep signal reads positives, which are immortal — no clock dependence.
        UUID table = UUID.randomUUID();
        TableOwnership.remember("alice", table, true, NOW);
        long farFuture = NOW + 30L * 24 * 60 * 60 * 1000; // 30 days
        Assert.assertEquals("the positive is still readable far later",
                Boolean.TRUE, TableOwnership.cached("alice", table, farFuture));
        Assert.assertTrue("so the keep signal is still on",
                TableOwnership.hasPositiveVerdicts("alice"));
    }

    // ── clear ──

    @Test
    public void clearDropsEverything() {
        TableOwnership.remember("alice", UUID.randomUUID(), true, NOW);
        TableOwnership.remember("bob", UUID.randomUUID(), false, NOW);
        Assert.assertEquals(2, TableOwnership.trackedUsers());
        TableOwnership.clear();
        Assert.assertEquals(0, TableOwnership.trackedUsers());
        Assert.assertEquals(0, TableOwnership.trackedTables("alice"));
    }

    // ── keying: usernames are independent ──

    @Test
    public void verdictsArePerUsername() {
        UUID table = UUID.randomUUID();
        TableOwnership.remember("alice", table, true, NOW);
        Assert.assertEquals(Boolean.TRUE, TableOwnership.cached("alice", table, NOW));
        Assert.assertNull("another account must not inherit the verdict",
                TableOwnership.cached("bob", table, NOW));
    }
}
