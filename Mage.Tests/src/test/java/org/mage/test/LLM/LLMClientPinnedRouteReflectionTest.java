package org.mage.test.LLM;

import mage.interfaces.LLMBridgeRegistry;
import mage.interfaces.LLMPromptPayload;
import mage.server.LLMClientTransport;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Async post-game reflection bridge routing (the routing target is captured

 * at submit time).
 *
 * Root cause pinned at source: TableController.endGameAndStartNextGame runs
 * match.hasEnded() → closeTable() for the match-DECIDING game and its finally
 * runs gameManager.removeGame(gameId); the async reflection performs
 * its bridge call 25-37 s LATER on the post-game executor thread, when the
 * live gameId → tableId → owner lookup in LLMClientTransport.deliver() is
 * already gone → "No browser session", no postgame file.
 *
 * Fix under test: the game thread pins the resolved route via
 * LLMBridgeRegistry.pinRoute(gameId, seatId) at snapshot time; the reflection's
 * payload then carries LLMPromptPayload.routeToken and the transport serves
 * it from the pin (with a session-ownership guard), never touching the dead
 * live lookup.
 *
 * Rig: the transport's protected resolution seams are overridden so no live
 * ManagerFactory / TableController / Session is needed. gameTornDown=true
 * simulates removeGame() + table close: the live lookup (via the overridden
 * seam) reports the game gone, exactly as the real GameManagerImpl does.
 *
 * Legs:
 *  1. torn-down game + pinned route → deliver SUCCEEDS via the pin, the
 *     callback fires to the pinned session; the pin outlives the delivery and
 *     its explicit release frees it (the seat's post-game phase makes more than
 *     one bridge call, so it must not be consumed by the first one);
 *  2. torn-down game + NO pin → deliver returns false → the registry's
 *     existing "No browser session" IllegalStateException (unchanged);
 *  3. stale/wrong/released token on a torn-down game → dropped (fail closed);
 *  4. live game + in-game (sideboard) call → live lookup still used, the pin is
 *     KEPT (a tokenless call runs for the SAME (game, seat) while that seat's
 *     reflection is still queued);
 *  5. pin on an already-torn-down game, or without a seat → null;
 *  6. pinned session recycled by another user → fail closed;
 *  7. wire-compat: routeToken survives Java serialization round-trip and
 *     serialVersionUID stays pinned at 1L.
 *
 * The two-seat collision regression (a gameId-only pin let the seats overwrite
 * each other) is covered by {@link LLMClientPostgamePinTwoSeatTest}.
 */
public class LLMClientPinnedRouteReflectionTest {

    @After
    public void clearTransport() {
        LLMBridgeRegistry.clearTransport();
    }

    /**
     * Transport with the live-resolution seams stubbed: no ManagerFactory,
     * no TableController, no Session — the pin mechanics under test stay real.
     */
    static class Rig extends LLMClientTransport {
        final UUID gameId = UUID.randomUUID();
        final UUID seatId = UUID.randomUUID();
        final UUID tableId = UUID.randomUUID();
        final UUID ownerUserId = UUID.randomUUID();
        final String sessionId = "session-" + UUID.randomUUID();
        final AtomicReference<LLMPromptPayload> fired = new AtomicReference<>();
        final AtomicReference<String> firedSession = new AtomicReference<>();
        volatile boolean gameTornDown = false;

        Rig() {
            super(null);
        }

        // gameId → tableId (GameManagerImpl.getTableIdForGame) — null once torn down.
        @Override
        protected UUID tableIdForGame(UUID gameId) {
            return gameTornDown ? null : tableId;
        }

        // tableId → owner (TableManagerImpl.getController(tableId).getOwnerUserId()).
        @Override
        protected UUID ownerUserIdForTable(UUID tableId) {
            return this.tableId.equals(tableId) ? ownerUserId : null;
        }

        @Override
        protected String sessionIdForUser(UUID userId) {
            return ownerUserId.equals(userId) ? sessionId : null;
        }

        @Override
        protected UUID userIdForSession(String sessionId) {
            return this.sessionId.equals(sessionId) ? ownerUserId : null;
        }

        @Override
        protected boolean fireToSession(String sessionId, UUID gameId, LLMPromptPayload payload) {
            if (!this.sessionId.equals(sessionId)) {
                return false;
            }
            fired.set(payload);
            firedSession.set(sessionId);
            return true;
        }

        LLMPromptPayload payload(String routeToken) {
            return payload(seatId, routeToken);
        }

        LLMPromptPayload payload(UUID seatId, String routeToken) {
            return new LLMPromptPayload(UUID.randomUUID(), seatId, gameId,
                    "deepseek-v4.1-flash-phoenix", "{}", "https://x.example/v1/chat/completions",
                    false, 10, "k-1", routeToken);
        }
    }

    @Test
    public void decidingGameReflectionDeliversViaPinnedRouteAfterTeardown() {
        Rig rig = new Rig();
        LLMBridgeRegistry.setTransport(rig);

        // Game thread, game still registered: snapshot-time pin.
        String token = LLMBridgeRegistry.pinRoute(rig.gameId, rig.seatId);
        Assert.assertNotNull("pin must resolve while the game is alive", token);
        Assert.assertEquals(1, rig.pinnedRouteCountForTest());

        // TableController.endGameAndStartNextGame: closeTable + removeGame —
        // the live lookup is dead by the time the async reflection fires.
        rig.gameTornDown = true;

        boolean delivered = rig.deliver(rig.payload(token));
        Assert.assertTrue("pinned route must serve the reflection after teardown", delivered);
        Assert.assertNotNull("callback must fire to the pinned session", rig.fired.get());
        Assert.assertEquals("callback must go to the session captured at pin time",
                rig.sessionId, rig.firedSession.get());
        Assert.assertEquals("pin stays live for the rest of the seat's post-game calls",
                1, rig.pinnedRouteCountForTest());

        // Post-game task end: explicit release bounds the map.
        LLMBridgeRegistry.releaseRoute(rig.gameId, rig.seatId);
        Assert.assertEquals("release frees the pin", 0, rig.pinnedRouteCountForTest());
    }

    @Test
    public void tornDownGameWithoutPinStillFailsAsBefore() {
        Rig rig = new Rig();
        LLMBridgeRegistry.setTransport(rig);
        rig.gameTornDown = true;

        Assert.assertFalse("no pin + torn down game must stay undeliverable",
                rig.deliver(rig.payload(null)));

        // …and the registry turns that into the existing fail-fast error.
        try {
            LLMBridgeRegistry.request(rig.payload(null));
            Assert.fail("torn-down game without a pin must fail fast");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("No browser session"));
        } catch (Exception e) {
            Assert.fail("expected IllegalStateException, got " + e);
        }
    }

    @Test
    public void staleOrForgedTokenIsDroppedFailClosed() {
        Rig rig = new Rig();
        LLMBridgeRegistry.setTransport(rig);
        String token = LLMBridgeRegistry.pinRoute(rig.gameId, rig.seatId);
        Assert.assertNotNull(token);
        rig.gameTornDown = true;

        Assert.assertFalse("forged token must not deliver",
                rig.deliver(rig.payload("forged-token")));
        Assert.assertNull("forged token must not fire a callback", rig.fired.get());
        Assert.assertEquals("forged attempt must not touch the real pin",
                1, rig.pinnedRouteCountForTest());

        Assert.assertTrue(rig.deliver(rig.payload(token)));
        // Released pin: the token is now unknown → dropped, never re-routed.
        LLMBridgeRegistry.releaseRoute(rig.gameId, rig.seatId);
        Assert.assertFalse("released pin must not serve",
                rig.deliver(rig.payload(token)));
    }

    @Test
    public void inGameCallUsesLiveLookupAndKeepsPin() {
        Rig rig = new Rig();
        LLMBridgeRegistry.setTransport(rig);
        String token = LLMBridgeRegistry.pinRoute(rig.gameId, rig.seatId);
        Assert.assertNotNull(token);

        // Non-deciding game: match continues, live lookup works. The seat's
        // SIDEBOARD call for this same game must not delete the reflection pin
        // that is still queued behind it.
        boolean delivered = rig.deliver(rig.payload(null));
        Assert.assertTrue("live lookup must serve in-game calls", delivered);
        Assert.assertNotNull(rig.fired.get());
        Assert.assertEquals("tokenless in-game call must leave the pin alone",
                1, rig.pinnedRouteCountForTest());
    }

    @Test
    public void pinReturnsNullWhenGameAlreadyGone() {
        Rig rig = new Rig();
        LLMBridgeRegistry.setTransport(rig);
        rig.gameTornDown = true;
        Assert.assertNull("pinning a torn-down game must fail cleanly",
                LLMBridgeRegistry.pinRoute(rig.gameId, rig.seatId));
        Assert.assertEquals(0, rig.pinnedRouteCountForTest());
    }

    @Test
    public void pinReturnsNullWithoutASeatId() {
        Rig rig = new Rig();
        LLMBridgeRegistry.setTransport(rig);
        Assert.assertNull("a pin without a seat cannot be keyed",
                LLMBridgeRegistry.pinRoute(rig.gameId, null));
        Assert.assertEquals(0, rig.pinnedRouteCountForTest());
    }

    @Test
    public void pinnedSessionTakenOverByAnotherUserFailsClosed() {
        Rig rig = new Rig() {
            @Override
            protected UUID userIdForSession(String sessionId) {
                // Session id recycled by a DIFFERENT user after the pin.
                return UUID.randomUUID();
            }
        };
        LLMBridgeRegistry.setTransport(rig);
        String token = LLMBridgeRegistry.pinRoute(rig.gameId, rig.seatId);
        Assert.assertNotNull(token);
        rig.gameTornDown = true;

        Assert.assertFalse("recycled session must not receive another seat's prompt",
                rig.deliver(rig.payload(token)));
        Assert.assertNull(rig.fired.get());
        Assert.assertEquals("failed pinned delivery unpins", 0, rig.pinnedRouteCountForTest());
    }

    @Test
    public void routeTokenSurvivesWireSerialization() throws Exception {
        UUID requestId = UUID.randomUUID();
        LLMPromptPayload p = new LLMPromptPayload(requestId, UUID.randomUUID(), UUID.randomUUID(),
                "bot", "{}", "https://x.example/v1/chat/completions", false, 10, "k-1", "tok-123");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(p);
        }
        LLMPromptPayload copy;
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            copy = (LLMPromptPayload) ois.readObject();
        }
        Assert.assertEquals("tok-123", copy.routeToken);
        Assert.assertEquals(requestId, copy.requestId);
        // Pinned suid on both remoting peers (proxy + server): must stay 1L so
        // per-field name matching — not class evolution — carries the new field.
        Assert.assertEquals(1L,
                java.io.ObjectStreamClass.lookup(LLMPromptPayload.class).getSerialVersionUID());
        // Legacy payloads (9-arg ctor) carry no pin.
        LLMPromptPayload legacy = new LLMPromptPayload(requestId, UUID.randomUUID(), UUID.randomUUID(),
                "bot", "{}", "https://x.example/v1/chat/completions", false, 10, "k-1");
        Assert.assertNull(legacy.routeToken);
    }
}
