package org.mage.test.LLM;

import mage.interfaces.LLMBridgeRegistry;
import mage.interfaces.LLMPromptPayload;
import mage.player.llm.LLMClient;
import mage.player.llm.LLMConfig;
import mage.server.LLMClientTransport;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Post-game reflection bridge pin: TWO SEATS of one game, and the pin's lifetime

 *
 * <p>Measured live (2026-10-02, 3× Bo3 LLM-v-LLM, 7 games): only 3 of 14
 * seat-reflections produced a postgame file, with 11
 * {@code LLM bridge: dropping payload with unknown route token for game <id>}
 * warnings and 11 matching {@code No browser session for LLM seat} errors.
 *
 * <p>Two defects, both reproduced here against the real transport and the real
 * LLMClient:
 * <ol>
 *   <li>the pin store was keyed by gameId ALONE. BOTH seats of a game pin the
 *       same gameId while it is still registered, so the second seat's put
 *       OVERWROTE the first seat's token and at most one reflection per game
 *       could ever be served (leg 1);</li>
 *   <li>the pinned token was carried by EVERY payload the client sent, so the
 *       SIDEBOARD call that runs right after game N ends (same client, same
 *       gameId) consumed the pin first (leg 2).</li>
 * </ol>
 *
 * <p>Leg 3 pins the deliberate multi-call lifetime: one seat's post-game phase
 * makes more than one bridge call (the reflection, then the "match review for
 * sideboarding" call, which runs BEFORE the postgame file is written), so a pin
 * consumed on the first delivery would drop the second call, throw, and lose the
 * file anyway. The pin is therefore released explicitly when the reflection task
 * ends.
 *
 * <p>Leg 4 is the negative control for the loud-drop posture: a released/unknown
 * token must still be dropped with a WARN naming the game and seat and reported
 * undelivered — no silent fallback to the live lookup or to the other seat's pin.
 *
 * <p>RED/GREEN: on the unmodified base commit, leg 1 fails — the second seat's
 * pin has overwritten the first seat's token, so that seat's reflection is
 * dropped and {@code deliver} returns false. There is no token scope at all on
 * base, so leg 2's premise (a reflection thread that carries the token while a
 * sideboard thread does not) cannot even be expressed.
 */
public class LLMClientPostgamePinTwoSeatTest {

    private static final UUID GAME_ID = UUID.randomUUID();
    private static final UUID SEAT_A = UUID.randomUUID();
    private static final UUID SEAT_B = UUID.randomUUID();
    private static final UUID TABLE_ID = UUID.randomUUID();
    private static final UUID OWNER_USER = UUID.randomUUID();
    private static final String SESSION = "session-" + UUID.randomUUID();

    private static final AtomicReference<String> FIRED_SEAT = new AtomicReference<>();
    private static final AtomicReference<String> FIRED_TOKEN = new AtomicReference<>();
    private static final AtomicReference<String> FIRED_SESSION = new AtomicReference<>();

    /** True while the game is registered; the finally in endGameAndStartNextGame kills the live link. */
    private static volatile boolean gameTornDown = false;

    @After
    public void tearDown() {
        LLMBridgeRegistry.clearTransport();
        FIRED_SEAT.set(null);
        FIRED_TOKEN.set(null);
        FIRED_SESSION.set(null);
        gameTornDown = false;
    }

    /**
     * Transport with the live-resolution seams stubbed (no ManagerFactory),
     * recording which seat each fired payload belonged to. Both seats share one
     * browser session — exactly as in a real LLM-v-LLM table created by one client.
     */
    private static class TwoSeatRig extends LLMClientTransport {
        TwoSeatRig() {
            super(null);
        }

        @Override
        protected UUID tableIdForGame(UUID gameId) {
            return gameTornDown || !GAME_ID.equals(gameId) ? null : TABLE_ID;
        }

        @Override
        protected UUID ownerUserIdForTable(UUID tableId) {
            return TABLE_ID.equals(tableId) ? OWNER_USER : null;
        }

        @Override
        protected String sessionIdForUser(UUID userId) {
            return OWNER_USER.equals(userId) ? SESSION : null;
        }

        @Override
        protected UUID userIdForSession(String sessionId) {
            return SESSION.equals(sessionId) ? OWNER_USER : null;
        }

        @Override
        protected boolean fireToSession(String sessionId, UUID gameId, LLMPromptPayload payload) {
            if (!SESSION.equals(sessionId)) {
                return false;
            }
            FIRED_SEAT.set(payload.seatId == null ? null : payload.seatId.toString());
            FIRED_TOKEN.set(payload.routeToken);
            FIRED_SESSION.set(sessionId);
            return true;
        }

        /** A bridge payload for one seat of THE game, optionally carrying its pin token. */
        static LLMPromptPayload payload(String routeToken, UUID seatId) {
            return new LLMPromptPayload(UUID.randomUUID(), seatId, GAME_ID,
                    "seat-" + seatId, "{}", "https://x.example/v1/chat/completions",
                    false, 10, "k-1", routeToken);
        }
    }

    /** Captures the transport's WARN/ERROR lines so loud drops can be asserted. */
    private static final class CapturingAppender extends AppenderSkeleton {
        final List<String> messages = Collections.synchronizedList(new ArrayList<String>());

        @Override
        protected void append(LoggingEvent event) {
            messages.add(String.valueOf(event.getMessage()));
        }

        @Override
        public void close() {
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }

        boolean contains(String needle) {
            synchronized (messages) {
                return messages.stream().anyMatch(m -> m != null && m.contains(needle));
            }
        }
    }

    /**
     * Leg 1 — the regression that fails at base: two seats of the SAME game each
     * pin, then each seat's reflection is served from its OWN pin after teardown.
     */
    @Test
    public void bothSeatsOfOneGameDeliverTheirOwnPinnedReflectionAfterTeardown() {
        TwoSeatRig rig = new TwoSeatRig();
        LLMBridgeRegistry.setTransport(rig);

        // Game thread, game still registered: both seats pin at game end. At
        // base both pins go under the same gameId and the second put wins.
        String tokenA = LLMBridgeRegistry.pinRoute(GAME_ID, SEAT_A);
        String tokenB = LLMBridgeRegistry.pinRoute(GAME_ID, SEAT_B);
        Assert.assertNotNull("seat A must pin while the game is alive", tokenA);
        Assert.assertNotNull("seat B must pin while the game is alive", tokenB);
        Assert.assertNotEquals("the two seats must own distinct tokens", tokenA, tokenB);
        Assert.assertEquals("one pin per seat, not one per game", 2, rig.pinnedRouteCountForTest());

        // TableController.endGameAndStartNextGame finally: removeGame(gameId).
        gameTornDown = true;

        // Seat A's async reflection.
        Assert.assertTrue("seat A's reflection must be served from its own pin",
                rig.deliver(TwoSeatRig.payload(tokenA, SEAT_A)));
        Assert.assertEquals("seat A's reflection went to seat A", SEAT_A.toString(), FIRED_SEAT.get());
        Assert.assertEquals("seat A saw its own token", tokenA, FIRED_TOKEN.get());

        // Seat B's async reflection.
        Assert.assertTrue("seat B's reflection must be served from its own pin",
                rig.deliver(TwoSeatRig.payload(tokenB, SEAT_B)));
        Assert.assertEquals("seat B's reflection went to seat B", SEAT_B.toString(), FIRED_SEAT.get());
        Assert.assertEquals("seat B saw its own token", tokenB, FIRED_TOKEN.get());

        Assert.assertEquals("both pins still held until their tasks end",
                2, rig.pinnedRouteCountForTest());
        LLMBridgeRegistry.releaseRoute(GAME_ID, SEAT_A);
        LLMBridgeRegistry.releaseRoute(GAME_ID, SEAT_B);
        Assert.assertEquals("both pins released", 0, rig.pinnedRouteCountForTest());
    }

    /**
     * Leg 2 — the token is carried ONLY by the post-game reflection thread. The
     * sideboard call runs live for the same game, and it must not steal the pin.
     */
    @Test
    public void sideboardAndInGameCallsNeverCarryTheReflectionToken() {
        TwoSeatRig rig = new TwoSeatRig();
        LLMBridgeRegistry.setTransport(rig);

        LLMClient client = new LLMClient(new LLMConfig());
        client.setBridgeContext(GAME_ID.toString(), SEAT_A.toString(), "seat-a");
        client.pinBridgeRouteForReflection();
        Assert.assertEquals("the pin is held", 1, rig.pinnedRouteCountForTest());

        // The token is pinned, but the CURRENT (game/sideboard) thread must not
        // see it: this thread is not the reflection worker. At base this was a
        // plain field, so every sendViaBridge payload carried it.
        Assert.assertNull("an in-game/sideboard thread must not carry the pin token",
                client.reflectionRouteTokenForTest());

        // It is still there for the reflection worker.
        client.beginPinnedReflectionRoute();
        String token = client.reflectionRouteTokenForTest();
        Assert.assertNotNull("the reflection thread owns the pinned token", token);
        client.endPinnedReflectionRoute();
        Assert.assertNull("leaving the reflection scope drops the thread token",
                client.reflectionRouteTokenForTest());

        // Re-setting the SAME (game, seat) context — the sideboard path — must not
        // release the pin the queued reflection still needs.
        client.setBridgeContext(GAME_ID.toString(), SEAT_A.toString(), "seat-a");
        client.beginPinnedReflectionRoute();
        Assert.assertEquals("sideboard context for the same seat must not drop the pin",
                token, client.reflectionRouteTokenForTest());
        client.endPinnedReflectionRoute();
        Assert.assertEquals("the pin survived the sideboard context", 1, rig.pinnedRouteCountForTest());

        // A DIFFERENT game supersedes it.
        client.setBridgeContext(UUID.randomUUID().toString(), SEAT_A.toString(), "seat-a");
        Assert.assertEquals("a new game drops the stale pin", 0, rig.pinnedRouteCountForTest());

        client.releaseReflectionRoute();
    }

    /**
     * Leg 3 — a pin is NOT single-use within one seat's post-game phase: the
     * reflection and the match-review call both run before the postgame file is
     * written, so the second delivery must still succeed.
     */
    @Test
    public void oneSeatsPostgamePhaseServesBothItsCallsFromThePin() {
        TwoSeatRig rig = new TwoSeatRig();
        LLMBridgeRegistry.setTransport(rig);
        String token = LLMBridgeRegistry.pinRoute(GAME_ID, SEAT_A);
        Assert.assertNotNull(token);
        gameTornDown = true;

        // generatePostGameSummaryFromSnapshot: client.chat(REFLECTION) …
        Assert.assertTrue(rig.deliver(TwoSeatRig.payload(token, SEAT_A)));
        // … then client.chat(MATCH REVIEW), still before writePostGameSummaryTo.
        Assert.assertTrue("the match-review call must also reach the seat",
                rig.deliver(TwoSeatRig.payload(token, SEAT_A)));
        Assert.assertEquals("the review call went to the same seat", SEAT_A.toString(), FIRED_SEAT.get());

        // Finally in runPostGameReflection.
        LLMBridgeRegistry.releaseRoute(GAME_ID, SEAT_A);
        Assert.assertEquals(0, rig.pinnedRouteCountForTest());
    }

    /**
     * Leg 4 — loud-drop posture: an unknown/released/wrong-seat token is dropped
     * with a WARN naming the game and seat and reported undelivered. Never
     * silently re-routed through the live lookup or the other seat's pin.
     */
    @Test
    public void unknownOrForeignSeatTokenIsDroppedLoudlyAndReportedUndelivered() throws Exception {
        TwoSeatRig rig = new TwoSeatRig();
        LLMBridgeRegistry.setTransport(rig);
        Logger transportLogger = Logger.getLogger(LLMClientTransport.class);
        CapturingAppender appender = new CapturingAppender();
        transportLogger.addAppender(appender);

        try {
            String tokenA = LLMBridgeRegistry.pinRoute(GAME_ID, SEAT_A);
            Assert.assertNotNull(tokenA);
            gameTornDown = true;

            // Seat B never pinned; it must not be able to use seat A's token.
            Assert.assertFalse("another seat's token must not deliver",
                    rig.deliver(TwoSeatRig.payload(tokenA, SEAT_B)));
            Assert.assertTrue("the drop names the game and the seat",
                    appender.contains("unknown route token for game " + GAME_ID + " seat " + SEAT_B));
            Assert.assertEquals("the foreign attempt must not touch seat A's pin",
                    1, rig.pinnedRouteCountForTest());

            // Seat A's own pin has since been released by its post-game task.
            LLMBridgeRegistry.releaseRoute(GAME_ID, SEAT_A);
            Assert.assertFalse("a released token must not deliver",
                    rig.deliver(TwoSeatRig.payload(tokenA, SEAT_A)));
            Assert.assertTrue(appender.contains("unknown route token for game " + GAME_ID + " seat " + SEAT_A));

            // …and the registry still surfaces it as the existing fail-fast error.
            try {
                LLMBridgeRegistry.request(TwoSeatRig.payload(tokenA, SEAT_A));
                Assert.fail("an undelivered pinned call must fail fast");
            } catch (IllegalStateException expected) {
                Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("No browser session"));
            }

            // A token with no seat at all cannot be routed to a pin: fail loud.
            LLMPromptPayload seatless = new LLMPromptPayload(UUID.randomUUID(), null, GAME_ID,
                    "seatless", "{}", "https://x.example/v1/chat/completions", false, 10, "k-1", tokenA);
            Assert.assertFalse("a seatless token must not deliver",
                    rig.deliver(seatless));
            Assert.assertTrue(appender.contains("route token but no seatId for game " + GAME_ID));
        } finally {
            transportLogger.removeAppender(appender);
        }
    }
}
