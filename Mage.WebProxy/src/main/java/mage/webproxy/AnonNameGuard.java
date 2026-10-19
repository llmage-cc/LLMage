package mage.webproxy;

import org.apache.log4j.Logger;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anon same-name collision guard

 *
 * The XMage server runs anon mode with {@code ANON_IDENTIFY_BY_HOST=true}
 * (Mage.Server Session.java): a connect with an existing anon name from the
 * same host silently kills the older session
 * ({@code DisconnectReason.AnotherUserInstanceSilent}). Every browser dialed
 * through this proxy appears at the server under the proxy's own host, so ANY
 * two webui users who pick the same anon name kick each other; each killed
 * client learns about it one ping-tick later ("Ping failed"), auto-reconnects
 * under the same name, and kills the other back — the kick loop this guard
 * exists to prevent.
 *
 * The proxy is the right place for the guard: it is the only component that
 * sees BOTH the anon username and the per-browser browserId (the
 * {@code web:<username>:<uuid>} the browser persists in sessionStorage, sent
 * on connect since mitigation 2 of )
 * for every session.
 *
 * Policy: REFUSE the new connect with a "name in use"
 * connect_error + 4401 close — the same user-fixable rejection path as a bad
 * auth token. The older session is never touched, so there is nothing to
 * surface to it.
 *
 * Reconnect exception: a same-name connect carrying the SAME browserId is the
 * legitimate takeover path (page refresh with saved creds; restoreSessionId is
 * not forwarded through the proxy, so the server would otherwise treat it as a
 * fresh same-host connect and silently kill the dying session anyway — the
 * guard lets that through unchanged).
 *
 * Scoping: a name collides only per target server ({@code host:port}) — a
 * multi-server proxy must not block "Bob" on server A because "Bob" is
 * connected to server B. Case-insensitive on the username, mirroring
 * UserManagerImpl's {@code users.computeIfAbsent(userName.toLowerCase(Locale.ROOT))}.
 *
 * Ownership mirrors PerHostConnectionLimiter: the entry belongs to the
 * WebProxySession that registered it, release is identity-checked, and cleanup()
 * releases on EVERY teardown path (WS close, WS error, deliberate rejection
 * close). A dial that fails AFTER registration (auth failure, server offline)
 * is still released — cleanup() runs for a half-built session too.
 */
public final class AnonNameGuard {

    private static final Logger logger = Logger.getLogger(AnonNameGuard.class);

    private static final AnonNameGuard SHARED = new AnonNameGuard();

    public static AnonNameGuard shared() {
        return SHARED;
    }

    /** One registration: who holds name {@code username} at {@code host:port}. */
    public static final class ActiveConnect {
        final WebProxySession owner;
        final String host;
        final int port;
        /** Lowercased at registration — the map key. */
        final String usernameLower;
        /** As sent on the connect frame; "" when absent (old clients, probes). */
        final String browserId;

        ActiveConnect(WebProxySession owner, String host, int port, String usernameLower, String browserId) {
            this.owner = owner;
            this.host = host;
            this.port = port;
            this.usernameLower = usernameLower;
            this.browserId = browserId;
        }
    }

    /** Outcome of a {@link #checkAndRegister} call. */
    public static final class Decision {
        public final boolean allowed;
        /** Non-null when allowed: the entry cleanup() must release. */
        public final ActiveConnect active;

        private Decision(boolean allowed, ActiveConnect active) {
            this.allowed = allowed;
            this.active = active;
        }
    }

    /**
     * Key = host + '\0' + port + '\0' + usernameLower. NUL separators so no
     * hostname/username string can forge a boundary (hostnames and the
     * username allowlist [a-zA-Z0-9_] never contain NUL).
     */
    private static String key(String host, int port, String usernameLower) {
        return host + '\0' + port + '\0' + usernameLower;
    }

    private final ConcurrentHashMap<String, ActiveConnect> active = new ConcurrentHashMap<>();

    /**
     * Register {@code username} at {@code host:port} for {@code session}, or
     * refuse when another live session already holds that name under a
     * DIFFERENT browserId. Register-and-check are one atomic map operation —
     * two racing connects cannot both pass. A refused connect leaves the map
     * unchanged.
     */
    public Decision checkAndRegister(WebProxySession session, String host, int port,
                                     String username, String browserId) {
        String usernameLower = username.toLowerCase(Locale.ROOT);
        ActiveConnect candidate = new ActiveConnect(session, host, port, usernameLower, browserId);
        String k = key(host, port, usernameLower);
        // Loop instead of a single putIfAbsent: the same-browserId retake needs
        // a compare-and-replace, which putIfAbsent cannot express.
        while (true) {
            ActiveConnect existing = active.putIfAbsent(k, candidate);
            if (existing == null) {
                return new Decision(true, candidate);
            }
            if (existing.owner != null && existing.owner == session) {
                // Re-registration by the same session (a second connect attempt
                // after a failed dial released nothing because cleanup() already
                // ran — defensive; the duplicate-connect guard normally fires
                // first). Treat as allowed; the original entry stands.
                // NB: null owners (unit tests driving the guard without a
                // session) must NOT match — null == null would let every
                // session-less probe take any name over.
                return new Decision(true, existing);
            }
            if (!existing.browserId.isEmpty() && existing.browserId.equals(browserId)) {
                // Legitimate reconnect takeover from the same browser: the old
                // entry's owner is dying (its ping will fail or its socket is
                // already closing — cleanup() will run and its identity-checked
                // release will no-op against the entry we are about to install).
                if (active.replace(k, existing, candidate)) {
                    logger.info("Anon name takeover by same browserId for " + usernameLower
                            + " at " + host + ":" + port);
                    return new Decision(true, candidate);
                }
                continue; // raced — re-read and retry
            }
            return new Decision(false, null);
        }
    }

    /**
     * Release {@code c}'s registration. Identity-checked: if the name was
     * re-registered by another session after {@code c}'s owner died, the newer
     * entry is NOT removed. Idempotent — safe to call from every teardown path.
     */
    public void release(ActiveConnect c) {
        active.remove(key(c.host, c.port, c.usernameLower), c);
    }

    /** Test hook: how many names are currently registered. */
    int registeredCount() {
        return active.size();
    }

    /** Test hook: drop every registration (between tests). */
    void reset() {
        active.clear();
    }
}
