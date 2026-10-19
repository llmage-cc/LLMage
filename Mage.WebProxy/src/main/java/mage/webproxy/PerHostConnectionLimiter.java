package mage.webproxy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cap the
 * number of concurrent connections this proxy holds to one XMage server host, so
 * a hosted deployment's single source IP looks like a modest gateway at
 * xmage.today rather than a bot farm.
 *
 * <p>Configuration: {@code -Dwebproxy.maxConnectionsPerHost=N} — N a positive
 * integer. Unset or {@code 0} means unlimited (the current behavior, preserved).
 * Anything else (non-numeric, negative) fails fast with
 * {@link IllegalStateException} at boot, in the same spirit as
 * {@code webproxy.hideServerTarget} in {@code WebProxyServer} — a cap that
 * silently degrades to "unlimited" or to some invented number is worse than no
 * cap, because the operator believes the deployment is bounded when it is not.
 *
 * <p>Counting: one slot per session, keyed by the RESOLVED target
 * {@code host:port} of the XMage server that session dialed. A slot is reserved
 * BEFORE the dial (so N concurrent attempts cannot all slip past the check and
 * overshoot the cap) and released by the session's {@code cleanup()} — which
 * every teardown path runs (WS close, WS error, and every deliberate
 * connect-rejection close). A failed dial releases just like a normal session
 * end; the slot's lifetime is the session's, not the TCP connection's.
 *
 * <p>Thread safety: both acquire and release go through
 * {@link ConcurrentHashMap#compute} on the target key, so the
 * check-then-increment and the decrement-then-remove are atomic per host. An
 * entry is removed once its count reaches zero, so a long-lived proxy does not
 * accumulate state for servers that are no longer being dialed. The map never
 * holds more entries than there are distinct targets the operator allows
 * ({@code webproxy.allowedXmageHosts}), so it stays tiny.
 */
final class PerHostConnectionLimiter {

    /** System property that carries the cap. */
    static final String MAX_PER_HOST_PROPERTY = "webproxy.maxConnectionsPerHost";

    /** Value meaning "no cap" — the default. */
    static final int UNLIMITED = 0;

    private static final PerHostConnectionLimiter SHARED = new PerHostConnectionLimiter();

    /** The proxy-wide limiter: every browser session shares one pool per host. */
    static PerHostConnectionLimiter shared() {
        return SHARED;
    }

    /** Live slot count per target key. Entries exist only while the count is > 0. */
    private final ConcurrentHashMap<String, Integer> live = new ConcurrentHashMap<>();

    /**
     * Read and strictly validate {@link #MAX_PER_HOST_PROPERTY}.
     *
     * @return the cap, or {@link #UNLIMITED} when unset/0
     * @throws IllegalStateException when the value is not a non-negative integer —
     *         fail fast at boot rather than pretending a typo'd cap is in force
     */
    static int connectionCap() {
        String raw = System.getProperty(MAX_PER_HOST_PROPERTY);
        if (raw == null || raw.trim().isEmpty()) {
            return UNLIMITED;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid " + MAX_PER_HOST_PROPERTY
                    + " (expected a positive integer, 0/unset = unlimited): " + raw);
        }
        if (value < 0) {
            throw new IllegalStateException("Invalid " + MAX_PER_HOST_PROPERTY
                    + " (must not be negative; 0/unset = unlimited): " + raw);
        }
        return value;
    }

    /**
     * Build the counting key for a resolved target. Fails fast on a null/empty
     * host or an out-of-range port — a silent empty key would pool unrelated
     * targets together.
     */
    static String hostKey(String host, int port) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("host is required for the per-host connection cap");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range for the per-host connection cap: " + port);
        }
        return host + ":" + port;
    }

    /**
     * Reserve one slot for {@code host:port}.
     *
     * @param cap the active cap; {@code <= 0} means unlimited (nothing is counted)
     * @return true when the slot was reserved; false when the host is already at
     *         the cap — the caller must refuse the connect, explicitly
     */
    boolean acquire(String host, int port, int cap) {
        if (cap <= 0) {
            return true;
        }
        String key = hostKey(host, port);
        final boolean[] reserved = { false };
        live.compute(key, (k, count) -> {
            int current = count == null ? 0 : count;
            if (current >= cap) {
                return current; // at cap — unchanged, refuse
            }
            reserved[0] = true;
            return current + 1;
        });
        return reserved[0];
    }

    /**
     * Release a slot previously reserved under {@code key} (as returned by
     * {@link #hostKey}). Idempotent for a key that is not held: the session layer
     * nulls its field before calling, so a double teardown cannot double-release.
     * Returns false (and changes nothing) when the key was not held — surfaced to
     * the caller/log so a bookkeeping bug is visible rather than silently
     * corrupting the count.
     */
    boolean release(String key) {
        if (key == null) {
            return false;
        }
        final boolean[] released = { false };
        live.compute(key, (k, count) -> {
            if (count == null || count <= 0) {
                return null; // nothing held — keep it removed
            }
            released[0] = true;
            int next = count - 1;
            return next == 0 ? null : next;
        });
        return released[0];
    }

    /** Current live count for a target (0 when untracked). Test/diagnostic seam. */
    int countFor(String host, int port) {
        Integer count = live.get(hostKey(host, port));
        return count == null ? 0 : count;
    }

    /** Total distinct targets currently holding slots (test/diagnostic seam). */
    int trackedHosts() {
        return live.size();
    }
}
