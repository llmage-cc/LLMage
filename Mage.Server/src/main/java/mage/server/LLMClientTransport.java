package mage.server;

import mage.interfaces.LLMBridgeRegistry;
import mage.interfaces.LLMPromptPayload;
import mage.interfaces.callback.ClientCallback;
import mage.interfaces.callback.ClientCallbackMethod;
import mage.server.managers.ManagerFactory;
import org.apache.log4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side transport for the client-key LLM bridge
 * Routes prompt payloads from the LLM plugin
 * to the browser session that owns the seat's table.
 *
 * Routing chain: gameId → GameManagerImpl.gameController → tableId →
 * TableManagerImpl.getController(tableId).userId (table owner — the browser
 * that created the table, also the browser holding the seat's user key)
 * → UserManager → User.getSessionId → SessionManager → Session.fireCallback
 * (LLM_PROMPT) → JBoss remoting → WebProxy → browser.
 *
 * Registered once at server boot from MainManagerFactory.
 *
 * PINNED ROUTES:
 * the async post-game reflection for a game whose table was torn down while the
 * reflection was still queued (always true for the match-DECIDING game, and for
 * every non-deciding game too — TableController.endGameAndStartNextGame's finally
 * runs gameManager.removeGame(gameId) while the reflection sits on the post-game
 * executor) cannot use the live gameId → tableId lookup above. The plugin pins the
 * route while the game is still registered (LLMPlayer.generatePostGameSummary →
 * LLMClient.pinBridgeRouteForReflection → LLMBridgeRegistry.pinRoute); the seat's
 * post-game calls then carry LLMPromptPayload.routeToken and are served from the
 * pin without the live lookup.
 *
 * <p>Pins are keyed by (gameId, seatId). The two seats of one game each pin the
 * SAME gameId while it is still registered; a gameId-only key let the second
 * seat's put overwrite the first seat's token, so at most one reflection per game
 * could ever be served

 *
 * <p>A pin is NOT single-use: one seat's post-game phase makes more than one
 * bridge call (the reflection itself, then the "match review for sideboarding"
 * call — generatePostGameSummaryFromSnapshot writes the postgame file only
 * AFTER both). Consuming the pin on the first call would drop the second, throw,
 * and lose the postgame file — the same outcome as having no pin at all. The pin
 * therefore serves every token-carrying call of its seat until the seat's
 * post-game reflection task finishes and explicitly releases it
 * ({@link #releaseRoute}); a token-carrying call whose (gameId, seatId) has no
 * live pin is still dropped loudly, never re-routed.
 */
public class LLMClientTransport implements LLMBridgeRegistry.Transport {

    private static final Logger logger = Logger.getLogger(LLMClientTransport.class);

    private final ManagerFactory managerFactory;
    /**
     * (gameId, seatId) → pinned route + issued token. The key carries BOTH ids:
     * the two seats of one game pin the same gameId while the game is still
     * registered, and they must never overwrite each other

     * Route and token share ONE map entry so a concurrent deliver can never
     * observe a route with a missing token (or a token with no route).
     */
    private final Map<SeatRouteKey, PinnedRoute> pinnedRoutes = new ConcurrentHashMap<>();

    public LLMClientTransport(ManagerFactory managerFactory) {
        this.managerFactory = managerFactory;
    }

    /** Register with the bridge registry. Called once at boot. */
    public static void register(ManagerFactory managerFactory) {
        LLMBridgeRegistry.setTransport(new LLMClientTransport(managerFactory));
    }

    /** Immutable pin key: one pin per (game, seat) — never per game alone. */
    private static final class SeatRouteKey {
        final UUID gameId;
        final UUID seatId;

        SeatRouteKey(UUID gameId, UUID seatId) {
            this.gameId = gameId;
            this.seatId = seatId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof SeatRouteKey)) {
                return false;
            }
            SeatRouteKey other = (SeatRouteKey) o;
            return gameId.equals(other.gameId) && seatId.equals(other.seatId);
        }

        @Override
        public int hashCode() {
            return 31 * gameId.hashCode() + seatId.hashCode();
        }

        @Override
        public String toString() {
            return "game " + gameId + " seat " + seatId;
        }
    }

    /** Immutable pin: the routing target + its issued token, captured at pin time. */
    private static final class PinnedRoute {
        final UUID ownerUserId;
        final String sessionId;
        final String token;

        PinnedRoute(UUID ownerUserId, String sessionId, String token) {
            this.ownerUserId = ownerUserId;
            this.sessionId = sessionId;
            this.token = token;
        }
    }

    @Override
    public boolean deliver(LLMPromptPayload payload) {
        UUID gameId = payload.gameId;
        if (gameId == null) {
            return false;
        }
        if (payload.routeToken != null) {
            return deliverPinned(payload, gameId);
        }
        // Live lookup: gameId → controller → tableId → owner → session.
        // A tokenless call does NOT touch the pin store. The seat's sideboard /
        // revised-strategy calls run live for the SAME (gameId, seatId) while the
        // seat's own reflection is still outstanding, so releasing a pin here
        // would delete the very route that reflection needs. The old "a pin for a
        // match that continued past this game is dead weight" release was wrong
        // for exactly this case — it made every non-deciding game (which always
        // sideboards) lose its reflections

        UUID ownerUserId = resolveOwnerUserId(gameId);
        if (ownerUserId == null) {
            return false;
        }
        return fireForUser(ownerUserId, gameId, payload);
    }

    /**
     * Resolve the live route for the given game AND seat and pin it, returning
     * the opaque token the plugin later puts on
     * {@link LLMPromptPayload#routeToken}. Returns null when no route resolves
     * right now (game already torn down, no owner, no session) — the caller
     * then keeps the live-lookup behavior and fails exactly as before the
     * pinning change. Re-pinning the same (gameId, seatId) replaces the previous
     * token (the old one then fails closed).
     */
    @Override
    public String pinRoute(UUID gameId, UUID seatId) {
        if (gameId == null || seatId == null) {
            return null;
        }
        UUID ownerUserId = resolveOwnerUserId(gameId);
        if (ownerUserId == null) {
            return null;
        }
        String sessionId = sessionIdForUser(ownerUserId);
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        pinnedRoutes.put(new SeatRouteKey(gameId, seatId),
                new PinnedRoute(ownerUserId, sessionId, token));
        return token;
    }

    /**
     * Release the (gameId, seatId) pin once that seat's post-game phase is over.
     * Explicit because a pin must outlive teardown but must NOT outlive the
     * reflection task that is its only user — this is the one place that bounds
     * the map.
     */
    @Override
    public void releaseRoute(UUID gameId, UUID seatId) {
        if (gameId == null || seatId == null) {
            return;
        }
        pinnedRoutes.remove(new SeatRouteKey(gameId, seatId));
    }

    /** gameId → controller → tableId → owning user (null when any link is gone). */
    private UUID resolveOwnerUserId(UUID gameId) {
        UUID tableId = tableIdForGame(gameId);
        if (tableId == null) {
            return null;
        }
        return ownerUserIdForTable(tableId);
    }

    /**
     * Serve a payload carrying a route token from the (gameId, seatId) pin that
     * issued it. The pin is NOT consumed by a successful delivery (see the class
     * comment: the seat's post-game phase makes several calls, and the file is
     * written only after the last of them). A token that matches nothing — wrong
     * seat, unknown, or already released — is dropped loudly (log) and reported
     * undelivered, never silently re-routed through the live lookup of a
     * DIFFERENT game nor through the other seat's pin.
     */
    private boolean deliverPinned(LLMPromptPayload payload, UUID gameId) {
        if (payload.seatId == null) {
            // Fail loud: a token with no seat cannot identify which pin it
            // claims, and guessing by gameId alone is the very bug being fixed.
            logger.warn("LLM bridge: dropping payload with route token but no seatId for game "
                    + gameId + " (requestId=" + payload.requestId + ")");
            return false;
        }
        SeatRouteKey key = new SeatRouteKey(gameId, payload.seatId);
        PinnedRoute route = pinnedRoutes.get(key);
        if (route == null || !route.token.equals(payload.routeToken)) {
            logger.warn("LLM bridge: dropping payload with unknown route token for " + key
                    + " (requestId=" + payload.requestId + ")");
            return false;
        }
        // Session-hijack guard FIRST: the pin was taken for a specific owner
        // user; only fire when the live session still belongs to that user
        // (a recycled sessionId for someone else must never receive another
        // seat's prompt — fail closed instead).
        if (!route.ownerUserId.equals(userIdForSession(route.sessionId))) {
            logger.warn("LLM bridge: pinned session " + route.sessionId + " no longer belongs to user "
                    + route.ownerUserId + " (" + key + ") — dropping pinned delivery");
            unpin(key);
            return false;
        }
        if (!fireToSession(route.sessionId, gameId, payload)) {
            // Pinned browser session died in the meantime — same end state as
            // today's live-lookup failure ("No browser session" upstream).
            unpin(key);
            return false;
        }
        // Delivered. The pin stays until the seat's post-game phase releases it.
        return true;
    }

    // ── Live-resolution seams (protected for focused unit tests) ────────────

    /** gameId → tableId (GameManagerImpl.getTableIdForGame); null once torn down. */
    protected UUID tableIdForGame(UUID gameId) {
        return managerFactory.gameManager().getTableIdForGame(gameId);
    }

    /** tableId → owning user (TableManagerImpl controller); null when gone. */
    protected UUID ownerUserIdForTable(UUID tableId) {
        return managerFactory.tableManager().getController(tableId)
                .map(TableController::getOwnerUserId)
                .orElse(null);
    }

    /** userId → its current sessionId, or null when the user is gone. */
    protected String sessionIdForUser(UUID userId) {
        Optional<User> user = managerFactory.userManager().getUser(userId);
        return user.map(User::getSessionId).orElse(null);
    }

    /** sessionId → the userId currently bound to it, or null when unknown. */
    protected UUID userIdForSession(String sessionId) {
        Optional<User> user = managerFactory.sessionManager().getUser(sessionId);
        return user.map(User::getId).orElse(null);
    }

    /** Live path: resolve owner user → session → fireCallback. */
    private boolean fireForUser(UUID ownerUserId, UUID gameId, LLMPromptPayload payload) {
        return fireToSession(sessionIdForUser(ownerUserId), gameId, payload);
    }

    /** sessionId → fire the LLM_PROMPT callback. False when the session is gone. */
    protected boolean fireToSession(String sessionId, UUID gameId, LLMPromptPayload payload) {
        if (sessionId == null) {
            return false;
        }
        Optional<Session> session = managerFactory.sessionManager().getSession(sessionId);
        if (!session.isPresent()) {
            return false;
        }
        session.get().fireCallback(new ClientCallback(ClientCallbackMethod.LLM_PROMPT, gameId, payload));
        return true;
    }

    /**
     * Drop one (game, seat) pin. Called when that pinned delivery failed (dead or
     * hijacked session) and from {@link #releaseRoute} when the seat's post-game
     * phase ended. NOT called on a live (tokenless) delivery — see {@link #deliver}.
     */
    private void unpin(SeatRouteKey key) {
        pinnedRoutes.remove(key);
    }

    /**
     * Lifecycle hygiene for the pin map: a pin exists so the reflection can fire
     * AFTER teardown, so it cannot be dropped at removeGame time itself — but a
     * reflection that never ran (queue saturation, JVM shutdown) would otherwise
     * linger. Every pin that is taken is released by the seat's post-game
     * reflection task (or by a failed pinned delivery), so the map only ever holds
     * pins whose reflection is still outstanding; the maximum size is twice the
     * number of concurrently torn-down-but-unreflected games (two seats each,
     * bounded by the post-game executor queue capacity).
     *
     * @return number of pins currently held.
     */
    public int pinnedRouteCountForTest() {
        return pinnedRoutes.size();
    }

    /** Test/ops hook: drop every pin. */
    public void clearPinnedRoutesForTest() {
        pinnedRoutes.clear();
    }
}
