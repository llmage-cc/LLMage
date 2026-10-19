package mage.webproxy;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for seat naming in {@link SeatAliases}, the per-username memory
 * of own-seat names the proxy joined under an account.
 *
 * Why this class exists at all: every proxy seat-identity guard
 * ({@code checkForRejoinableGame}, {@code isGameSeatOfUser} for rejoin_game,
 * {@code isTableSeatOfUser} for submit_deck, {@code startTableWatchForSeatedTable})
 * used to be a bare {@code username.equals(seat.getPlayerName())}. The creator can
 * creator type a seat name for their own seat, which the join loop passes to the
 * server — so after a browser reload the user's OWN seat no longer carries the
 * username and rejoin/deck-submit would be refused. The contract pinned here:
 *
 *  - the derived default (username) always qualifies, with or without a record,
 *  - a name the proxy RECORDED for this account qualifies,
 *  - a name belonging to a different account never does (no cross-user leak),
 *  - recording the username itself is a no-op (no unbounded growth from the
 *    common case), duplicate records don't grow the deque, the per-user cap
 *    keeps the most recent names, and the account cap evicts LRU.
 */
public class SeatAliasesTest {

    @Before
    public void setUp() {
        SeatAliases.clear();
    }

    @After
    public void tearDown() {
        SeatAliases.clear();
    }

    @Test
    public void usernameAlwaysQualifiesEvenWithoutARecord() {
        Assert.assertTrue("the derived default (username) is always the user's seat",
                SeatAliases.isCreatorSeat("wizard", "wizard"));
    }

    @Test
    public void recordedNameQualifiesForThatAccountOnly() {
        SeatAliases.recordCreatorSeat("wizard", "Alice");
        Assert.assertTrue("the account that joined as Alice recognizes the seat",
                SeatAliases.isCreatorSeat("wizard", "Alice"));
        Assert.assertFalse("another account must NOT have its seat-identity extended to Alice",
                SeatAliases.isCreatorSeat("rogue", "Alice"));
    }

    @Test
    public void recordingTheUsernameIsANoOp() {
        SeatAliases.recordCreatorSeat("wizard", "wizard");
        Assert.assertTrue(SeatAliases.isCreatorSeat("wizard", "wizard"));
        Assert.assertEquals("no entry is created for the derived default (nothing to track)",
                0, SeatAliases.trackedUsers());
    }

    @Test
    public void nullAndEmptyInputsAreNotRecordedAndNeverMatch() {
        SeatAliases.recordCreatorSeat("wizard", null);
        SeatAliases.recordCreatorSeat("wizard", "");
        SeatAliases.recordCreatorSeat(null, "Alice");
        Assert.assertEquals(0, SeatAliases.trackedUsers());
        Assert.assertFalse(SeatAliases.isCreatorSeat("wizard", null));
        Assert.assertFalse(SeatAliases.isCreatorSeat(null, "Alice"));
        Assert.assertFalse(SeatAliases.isCreatorSeat("wizard", ""));
    }

    @Test
    public void duplicateRecordsDoNotGrowTheTrackedSet() {
        SeatAliases.recordCreatorSeat("wizard", "Alice");
        SeatAliases.recordCreatorSeat("wizard", "Alice");
        Assert.assertTrue(SeatAliases.isCreatorSeat("wizard", "Alice"));
        // Cap is 8 names/user; the duplicate must not consume a second slot.
        for (int i = 0; i < SeatAliases.MAX_NAMES_PER_USER + 2; i++) {
            SeatAliases.recordCreatorSeat("wizard", "Bot-" + i);
        }
        // The most recent names survive; "Alice" was pushed out by the cap.
        Assert.assertTrue(SeatAliases.isCreatorSeat("wizard", "Bot-" + (SeatAliases.MAX_NAMES_PER_USER + 1)));
        Assert.assertFalse("the cap keeps the MOST RECENT names",
                SeatAliases.isCreatorSeat("wizard", "Alice"));
    }

    @Test
    public void newestNamesSurviveThePerUserCap() {
        for (int i = 0; i < SeatAliases.MAX_NAMES_PER_USER; i++) {
            SeatAliases.recordCreatorSeat("wizard", "Name" + i);
        }
        for (int i = 0; i < SeatAliases.MAX_NAMES_PER_USER; i++) {
            Assert.assertTrue("Name" + i + " is within the cap",
                    SeatAliases.isCreatorSeat("wizard", "Name" + i));
        }
        SeatAliases.recordCreatorSeat("wizard", "Newest");
        Assert.assertTrue(SeatAliases.isCreatorSeat("wizard", "Newest"));
        Assert.assertFalse("the OLDEST name is evicted by the cap",
                SeatAliases.isCreatorSeat("wizard", "Name0"));
    }

    @Test
    public void accountRegistryStaysBounded() {
        for (int i = 0; i < SeatAliases.MAX_USERS + 5; i++) {
            SeatAliases.recordCreatorSeat("user" + i, "Seat" + i);
        }
        Assert.assertTrue("the registry is bounded to MAX_USERS",
                SeatAliases.trackedUsers() <= SeatAliases.MAX_USERS);
        Assert.assertTrue("the most recent account is tracked",
                SeatAliases.isCreatorSeat("user" + (SeatAliases.MAX_USERS + 4),
                        "Seat" + (SeatAliases.MAX_USERS + 4)));
    }

    @Test
    public void theDerivedDefaultStillQualifiesAfterTheCapEvictsTheAccount() {
        // Eviction must never make the previous behavior WORSE: the username check
        // stands on its own, independent of any registry state.
        for (int i = 0; i < SeatAliases.MAX_USERS + 5; i++) {
            SeatAliases.recordCreatorSeat("user" + i, "Seat" + i);
        }
        Assert.assertTrue(SeatAliases.isCreatorSeat("user0", "user0"));
        Assert.assertFalse("an evicted account's typed name is no longer recognized",
                SeatAliases.isCreatorSeat("user0", "Seat0"));
    }
}
