package mage.webproxy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import mage.remote.SessionImpl;

/**
 * Per-username memory of the tables the account has been PROVEN to own, so the
 * proxy can put a server-authoritative ownership flag on the table list without
 * paying one {@code tableIsOwner} RPC per table per push.
 *
 * WHY THIS EXISTS
 * ---------------
 * A table
 * the creator holds NO seat at shows the owner no start control anywhere, and
 * the browser learns ownership only from its own {@code table_created} frame —
 * so a page reload (F5) loses it and the owner's UI does not even know the
 * table is theirs. The wire carried no ownership signal at all before this
 * (only {@code controllerName}, which is display text and must not be parsed),
 * while the server has the authoritative answer in
 * {@code TableController.isOwner(userId)} behind the {@code tableIsOwner} RPC
 * ({@link SessionImpl#isTableOwner}).
 *
 * WHO IS ASKED, AND HOW OFTEN
 * ---------------------------
 * Ownership is IMMUTABLE for a live table (it is the creating session's user id
 * on the TableController), so:
 *  - a POSITIVE answer is cached for the lifetime of the proxy process — no
 *    re-probe, ever;
 *  - a NEGATIVE answer is provisional ({@link #NEGATIVE_TTL_MS}): the probe can
 *    fail for reasons other than "not the owner" (a remoting hiccup answers
 *    false), and a cache entry pinned on that would hide the owner's Start
 *    control until the proxy restarts.
 *
 * Only WAITING/READY_TO_START, non-tournament tables are ever probed (see
 * {@code WebProxySession#resolveOwnedTableIds}) — a table that already started
 * is past the window where the flag is consulted.
 *
 * WHY NOT PER-SESSION STATE
 * -------------------------
 * A browser reload builds a NEW {@link WebProxySession} (one per WS connection)
 * while the XMage session (and with it the user id that owns the table) is kept
 * alive — the same reason {@link SeatAliases} and {@link GameHistory} are keyed
 * by username. The cache must outlive the WS session, otherwise a reload pays
 * the full probe scan again.
 *
 * BOUNDS / LIMITS (stated, not hidden)
 * ------------------------------------
 *  - {@link #MAX_TABLES_PER_USER} tables tracked per account, most recently
 *    (re)confirmed first; the oldest entry is evicted beyond that (the table
 *    then gets re-probed once — never wrong, only once more expensive).
 *  - {@link #MAX_USERS} accounts tracked; the least-recently-used account is
 *    evicted beyond that.
 *  - The username is the key, exactly like the username-based seat checks: a
 *    user reconnecting under the same name reclaims the same XMage user
 *    instance (anonymous same-host takeover), so the same key still selects the
 *    right answers. Entries are a FACT about an RPC the proxy itself ran, never
 *    a browser-supplied claim.
 *
 * Thread safety: the connect thread (probe scan) and the table-watch executor
 * (flag resolution) both read/write; every access is guarded by {@link #LOCK}.
 */
final class TableOwnership {

    /**
     * How long a NEGATIVE probe answer stays valid. Long enough that repeated
     * reconnects in a busy lobby do not re-probe every waiting table, short
     * enough that a probe that failed for a transient remoting reason cannot
     * hide the owner's control for the lifetime of the proxy.
     */
    static final long NEGATIVE_TTL_MS = 120_000L;

    /** Tables tracked per account (see the class comment). */
    static final int MAX_TABLES_PER_USER = 64;

    /** Accounts tracked at once; LRU-evicted (see the class comment). */
    static final int MAX_USERS = 64;

    private static final Object LOCK = new Object();

    /** One probe result: the answer and when it was read. */
    private static final class Verdict {
        final boolean owner;
        final long checkedAtMs;

        Verdict(boolean owner, long checkedAtMs) {
            this.owner = owner;
            this.checkedAtMs = checkedAtMs;
        }
    }

    /** username → (tableId → verdict), least recently touched first. */
    private static final Map<String, LinkedHashMap<UUID, Verdict>> BY_USER =
            new LinkedHashMap<String, LinkedHashMap<UUID, Verdict>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, LinkedHashMap<UUID, Verdict>> eldest) {
                    return size() > MAX_USERS;
                }
            };

    private TableOwnership() {
    }

    /**
     * The cached verdict for (username, table), or null when there is none that
     * is still valid — the caller then has to probe the server.
     *
     * <p>A positive verdict never expires; a negative one only holds for
     * {@link #NEGATIVE_TTL_MS} (see the class comment).
     *
     * @param nowMs the caller's clock — an explicit parameter so the TTL rule is
     *              a pure function of state and can be pinned by the unit tests
     *              without sleeping
     */
    static Boolean cached(String username, UUID tableId, long nowMs) {
        if (username == null || username.isEmpty() || tableId == null) {
            return null;
        }
        synchronized (LOCK) {
            LinkedHashMap<UUID, Verdict> tables = BY_USER.get(username);
            if (tables == null) {
                return null;
            }
            Verdict v = tables.get(tableId);
            if (v == null) {
                return null;
            }
            if (v.owner) {
                return Boolean.TRUE;
            }
            if (nowMs - v.checkedAtMs > NEGATIVE_TTL_MS) {
                tables.remove(tableId);
                return null;
            }
            return Boolean.FALSE;
        }
    }

    /**
     * Record a probe answer for (username, table). Called with the RESULT of a
     * real {@code tableIsOwner} RPC, or with {@code owner=true} right after this
     * session created the table itself (the create RPC is that proof).
     */
    static void remember(String username, UUID tableId, boolean owner, long nowMs) {
        if (username == null || username.isEmpty() || tableId == null) {
            return;
        }
        synchronized (LOCK) {
            LinkedHashMap<UUID, Verdict> tables = BY_USER.get(username);
            if (tables == null) {
                tables = new LinkedHashMap<UUID, Verdict>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<UUID, Verdict> eldest) {
                        return size() > MAX_TABLES_PER_USER;
                    }
                };
                BY_USER.put(username, tables);
            }
            tables.put(tableId, new Verdict(owner, nowMs));
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

    /** Tracked table count for one account — for the standalone unit tests. */
    static int trackedTables(String username) {
        synchronized (LOCK) {
            LinkedHashMap<UUID, Verdict> tables = BY_USER.get(username);
            return tables == null ? 0 : tables.size();
        }
    }

    /**
     * True when at least one POSITIVE verdict is held for this account — the
     * cleanup()-time keep decision reads this (see {@code WebProxySession#cleanup}):
     * an account that owns a table it has not started is worth keeping the XMage
     * session alive for across a browser reload, exactly like a live game.
     *
     * <p>Negatives are ignored on purpose: "not the owner" of someone else's table
     * must never hold a session open.
     */
    static boolean hasPositiveVerdicts(String username) {
        if (username == null || username.isEmpty()) {
            return false;
        }
        synchronized (LOCK) {
            LinkedHashMap<UUID, Verdict> tables = BY_USER.get(username);
            if (tables == null) {
                return false;
            }
            for (Verdict v : tables.values()) {
                if (v.owner) {
                    return true;
                }
            }
            return false;
        }
    }
}
