package mage.webproxy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-username memory of the seat names an account created for ITSELF, so the
 * proxy's seat-identity checks still recognize the user's own seat after custom
 * seat naming.
 *
 * WHY THIS EXISTS
 * ---------------
 * Every "is this seat mine?" check in {@link WebProxySession} used to be a plain
 * string compare against {@code proxyClient.getUsername()}:
 * {@code checkForRejoinableGame}, {@code isGameSeatOfUser} (rejoin_game),
 * {@code isTableSeatOfUser} (submit_deck), {@code startTableWatchForSeatedTable}.
 * That is only correct while a human seat is ALWAYS named after the account
 * (the proxy's derived default). The creator can type a seat name for
 * their own seat, which becomes {@code Player.getName()} for the whole match —
 * so after a browser reload the rejoin path would not find the user's own seat
 * (name ≠ username) and would answer "Cannot rejoin — you are not seated at
 * that game's table" about the user's OWN game, and {@code submit_deck} would
 * be rejected the same way between games of a Bo3.
 *
 * WHAT IT RECORDS
 * ---------------
 * Only names THIS proxy observed itself pass to the server for a HUMAN seat the
 * session auto-joined as the creator ({@link #recordCreatorSeat}, called from
 * the auto-join loops after a successful join). It is therefore not a
 * user-supplied claim of an arbitrary name: the entry is a fact about a join
 * the proxy itself performed. Trust level matches the username check it
 * extends — the username key is still what selects the entries.
 *
 * WHY NOT PER-SESSION STATE
 * -------------------------
 * A browser reload builds a NEW {@link WebProxySession} (one per WS connection)
 * while the XMage session and the seat survive (connectStop(false, true) keeps
 * them for a live game) — exactly the reason {@link GameHistory} is keyed by
 * username too. The entry must outlive the WS session to serve the rejoin it
 * exists for.
 *
 * BOUNDS / LIMITS (stated, not hidden)
 * ------------------------------------
 *  - At most {@link #MAX_NAMES_PER_USER} names per account (most recent first),
 *    so a long-lived proxy session cannot accumulate names without bound.
 *  - At most {@link #MAX_USERS} accounts; the least-recently-used entry is
 *    evicted when a new account appears (that account then falls back to the
 *    username-only check — the previous behavior, never worse).
 *  - A stale name (the user created "Bob" once, later joins a table where
 *    ANOTHER player is named "Bob") can make the seat-mine checks answer "yes"
 *    for that seat within the same account. The check is only ever used to
 *    ALLOW this user's own rejoin/deck-submit/table-watch, and the server still
 *    validates the actual identity, so the worst case is a rejected rejoin —
 *    the same outcome as the username mismatch it replaces.
 *
 * Thread safety: callback threads and the table-watch executor both read/write;
 * every access is guarded by {@link #LOCK}.
 */
final class SeatAliases {

    /** Most recent own-seat names kept per account (see the class comment). */
    static final int MAX_NAMES_PER_USER = 8;

    /** Accounts tracked at once; LRU-evicted (see the class comment). */
    static final int MAX_USERS = 256;

    private static final Object LOCK = new Object();

    /** username → own-seat names, most recently recorded last. */
    private static final Map<String, Deque<String>> BY_USER =
            new LinkedHashMap<String, Deque<String>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Deque<String>> eldest) {
                    return size() > MAX_USERS;
                }
            };

    private SeatAliases() {
    }

    /**
     * Remember that {@code username} created a human seat named
     * {@code seatName}. No-op when the name is null/empty or is just the
     * username (the derived default already passes the compare).
     */
    static void recordCreatorSeat(String username, String seatName) {
        if (username == null || username.isEmpty() || seatName == null || seatName.isEmpty()) {
            return;
        }
        if (username.equals(seatName)) {
            return;
        }
        synchronized (LOCK) {
            Deque<String> names = BY_USER.get(username);
            if (names == null) {
                names = new ArrayDeque<>();
                BY_USER.put(username, names);
            }
            if (names.contains(seatName)) {
                return;
            }
            names.addLast(seatName);
            while (names.size() > MAX_NAMES_PER_USER) {
                names.removeFirst();
            }
        }
    }

    /**
     * True when {@code seatName} is a name {@code username} created for its own
     * creator seat (or is the username itself).
     */
    static boolean isCreatorSeat(String username, String seatName) {
        if (username == null || seatName == null) {
            return false;
        }
        if (username.equals(seatName)) {
            return true;
        }
        synchronized (LOCK) {
            Deque<String> names = BY_USER.get(username);
            return names != null && names.contains(seatName);
        }
    }

    /** Drop every entry — for the standalone unit tests. */
    static void clear() {
        synchronized (LOCK) {
            BY_USER.clear();
        }
    }

    /** Tracked account count — for the standalone unit tests. */
    static int trackedUsers() {
        synchronized (LOCK) {
            return BY_USER.size();
        }
    }
}
