package mage.webproxy;

import com.google.gson.JsonObject;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Regression tests for token_art_preference OUTSIDE a seated live game:
 * picking token art during a REPLAY changed the token on screen
 * but the proxy answered "token_art_preference requires an active game", and
 * the browser surfaced it as an error toast.
 *
 * The message is a VIEWER preference first: the browser applies it to
 * localStorage the moment the user picks. Sharing it with the table is the
 * bonus half, and it needs a SEATED live game: a gameId to scope the table and
 * a playerId to attribute the choice. A replay (replayGameId set, currentGameId
 * null by design) and a spectated game (gameId set, playerId null) both lack
 * that — and both must be accepted viewer-locally, not refused.
 *
 * Drives a real WebProxySession with a real (unconnected) WebProxyClient and a
 * recording WebSocket stub — no server, no XMage — the same technique
 * WebProxyAuthCloseTest/WebProxyRollbackTurnsTest use. Live-state setup
 * (currentGameId / myPlayerId / replayGameId) goes through reflection; those
 * fields are only set by live callbacks and there is no seam otherwise.
 */
public class WebProxyTokenArtPreferenceTest {

    private WebProxyAuthCloseTest.RecordingAppender appender;

    @Before
    public void attachLogs() {
        appender = new WebProxyAuthCloseTest.RecordingAppender();
        appender.setThreshold(Level.TRACE);
        Logger.getRootLogger().addAppender(appender);
    }

    @After
    public void detachLogs() {
        Logger.getRootLogger().removeAppender(appender);
    }

    // ── fixtures ──

    private static final String ALLOWED_URL =
            "https://cards.scryfall.io/small/front/0/a/0a7c8ad6-b6c6-41ef-939b-ce3ec261a50b.jpg";

    private static WebProxySession sessionWithClient(String username) throws Exception {
        WebProxySession session = new WebProxySession();
        Field f = WebProxySession.class.getDeclaredField("proxyClient");
        f.setAccessible(true);
        f.set(session, new WebProxyClient(username));
        return session;
    }

    /** Point the session's client at a live-game/replay context via reflection. */
    private static void setClientField(WebProxySession session, String field, Object value) throws Exception {
        Field pf = WebProxySession.class.getDeclaredField("proxyClient");
        pf.setAccessible(true);
        WebProxyClient client = (WebProxyClient) pf.get(session);
        Field f = WebProxyClient.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(client, value);
    }

    private static JsonObject prefJson(String tokenName, String url) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "token_art_preference");
        o.addProperty("tokenName", tokenName);
        if (url == null) {
            o.add("url", com.google.gson.JsonNull.INSTANCE);
        } else {
            o.addProperty("url", url);
        }
        return o;
    }

    private static void invokePreference(WebProxySession session, JsonObject obj) throws Exception {
        Method m = WebProxySession.class.getDeclaredMethod("handleTokenArtPreference", JsonObject.class);
        m.setAccessible(true);
        m.invoke(session, obj);
    }

    private WebProxyAuthCloseTest.StubSession attachWs(WebProxySession session) {
        WebProxyAuthCloseTest.StubSession ws = new WebProxyAuthCloseTest.StubSession();
        session.onWebSocketConnect(ws);
        return ws;
    }

    private boolean logged(String fragment) {
        return appender.events.stream().anyMatch(e -> e.getRenderedMessage() != null
                && e.getRenderedMessage().contains(fragment));
    }

    // ── the reported defect: a replay is not "an active game" ──

    @Test
    public void preferenceDuringReplayIsViewerLocalNotAnError() throws Exception {
        WebProxySession session = sessionWithClient("tester");
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);
        // A replay loaded: replayGameId set, currentGameId null (by design — a
        // replay id must never leak into live-game routing).
        setClientField(session, "replayGameId", UUID.randomUUID());

        invokePreference(session, prefJson("Soldier", ALLOWED_URL));

        Assert.assertTrue("no error may be sent for a viewer-local preference",
                ws.sent.stream().noneMatch(s -> s.contains("\"error\"")));
        Assert.assertTrue("the viewer-local accept must be logged (traceable, not silent)",
                logged("recorded viewer-locally"));
        Assert.assertTrue("the log must say WHY nothing was relayed (replay loaded)",
                logged("replay loaded"));
    }

    @Test
    public void clearingPreferenceDuringReplayIsAccepted() throws Exception {
        WebProxySession session = sessionWithClient("tester");
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);
        setClientField(session, "replayGameId", UUID.randomUUID());

        invokePreference(session, prefJson("Soldier", null));

        Assert.assertTrue("clearing a preference (Reset to default) during a replay must not error",
                ws.sent.stream().noneMatch(s -> s.contains("\"error\"")));
        Assert.assertTrue("clearing must also be logged as viewer-local",
                logged("recorded viewer-locally"));
    }

    @Test
    public void preferenceWhileSpectatingIsViewerLocalNotAnError() throws Exception {
        // Watching a live game: currentGameId set, playerId null (the watched
        // GameView carries no "my player") — still no table identity to relay to.
        WebProxySession session = sessionWithClient("tester");
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);
        setClientField(session, "currentGameId", UUID.randomUUID());

        invokePreference(session, prefJson("Soldier", ALLOWED_URL));

        Assert.assertTrue("watching a game must not turn a preference into an error",
                ws.sent.stream().noneMatch(s -> s.contains("\"error\"")));
        Assert.assertTrue("the spectator case must be logged as viewer-local",
                logged("no seated live game"));
    }

    // ── validation still fails fast, and is STRICTER than before ──

    @Test
    public void invalidTokenNameStillErrors() throws Exception {
        WebProxySession session = sessionWithClient("tester");
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);

        invokePreference(session, prefJson("", ALLOWED_URL));

        Assert.assertTrue("an empty tokenName must be refused with an error",
                ws.sent.stream().anyMatch(s -> s.contains("requires a valid tokenName")));
    }

    @Test
    public void disallowedHostStillErrorsEvenWithoutASeatedGame() throws Exception {
        // The allowlist check now runs BEFORE the seated-game branch: a tampered
        // URL must never be silently accepted just because there is no game to
        // relay to (the old order skipped validation on the error path only
        // because it refused earlier; the new viewer-local accept must not
        // become a validation bypass).
        WebProxySession session = sessionWithClient("tester");
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);
        setClientField(session, "replayGameId", UUID.randomUUID());

        invokePreference(session, prefJson("Soldier", "https://evil.example.com/a.jpg"));

        Assert.assertTrue("a non-allowlisted URL must be refused",
                ws.sent.stream().anyMatch(s -> s.contains("not an allowed card-image host")));
    }

    // ── the seated relay must still work ──

    @Test
    public void seatedGameStillRelaysTheChoice() throws Exception {
        WebProxySession session = sessionWithClient("tester");
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);
        setClientField(session, "currentGameId", UUID.randomUUID());
        setClientField(session, "myPlayerId", UUID.randomUUID());

        invokePreference(session, prefJson("Soldier", ALLOWED_URL));

        Assert.assertTrue("a seated preference must still broadcast token_art_preferences",
                ws.sent.stream().anyMatch(s -> s.contains("token_art_preferences")));
        Assert.assertTrue("no error on the seated path",
                ws.sent.stream().noneMatch(s -> s.contains("\"error\"")));
        Assert.assertTrue("the seated relay must still log the broadcast",
                logged("session(s) notified"));
    }

    // ── grep guard: the old refusal must not come back ──

    @Test
    public void handlerNoLongerRefusesOutsideASeatedGameInSource() throws Exception {
        String src = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/mage/webproxy/WebProxySession.java")));
        Assert.assertFalse("the old refusal (sendError on a non-seated preference) must not exist — replay regression",
                src.contains("sendError(\"token_art_preference requires an active game\")"));
    }
}
