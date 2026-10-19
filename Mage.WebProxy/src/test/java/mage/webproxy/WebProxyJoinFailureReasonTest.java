package mage.webproxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.DeckValidator;
import mage.constants.RangeOfInfluence;
import mage.game.FakeMatch;
import mage.game.Table;
import mage.game.match.Match;
import mage.game.match.MatchOptions;
import mage.interfaces.MageClient;
import mage.interfaces.callback.ClientCallback;
import mage.interfaces.callback.ClientCallbackMethod;
import mage.players.Player;
import mage.players.PlayerType;
import mage.players.StubPlayer;
import mage.remote.SessionImpl;
import mage.view.TableView;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Regression tests for the silent seat-join failure.
 *
 * <p>The reported incident: a New Game with an LLM seat clicked Start Game and
 * silently returned to the lobby; the ONLY record was the browser console line
 * {@code Proxy error: Seat 0 failed to join: } with an EMPTY reason after the
 * colon. Root cause: {@code TableController.joinTable} reports a refusal as a
 * plain boolean and carries the real rejection text on an async
 * {@code SHOW_USERMESSAGE} callback ("Join Table", &lt;text&gt;).
 * {@code xmageSession.getLastError()} is set ONLY on connect failures, so the
 * proxy's join path read it and produced an empty reason, dropping the server's
 * actual message.
 *
 * <p>Contract pinned here (the MATCH path — the reported symptom; the TOURNAMENT
 * path shares the same reason helper, guarded in source by
 * {@code sourceGuardNoEmptyReasonFrames}):
 * <ul>
 *   <li>a refused seat join forwards the server's own SHOW_USERMESSAGE text
 *       VERBATIM in the {@code error} frame;</li>
 *   <li>that text wins over a stale {@code getLastError()};</li>
 *   <li>when no server text arrives the frame is still NON-EMPTY (fail-visible),
 *       and the no-reason case is logged at ERROR;</li>
 *   <li>EVERY {@code "failed to join: "} frame in the source is built from the
 *       shared reason helper — no call site regresses to a bare empty reason.</li>
 * </ul>
 *
 * <p>These tests drive the REAL {@code handleCreateTable} on a real
 * WebProxySession whose {@code xmageSession} is a SessionImpl subclass standing in
 * for the XMage server (same technique as {@link WebProxyCreateAutoStartTest}). The
 * fake delivers the server's user message through the real
 * {@link WebProxyClient#onCallback} path, so the reason travels the same record /
 * wait machinery the production code reads.
 */
public class WebProxyJoinFailureReasonTest {

    private static final long AWAIT_MS = 10_000;
    private static final long POLL_MS = 100;

    private WebProxyAuthCloseTest.RecordingAppender appender;

    @Before
    public void attachLogs() {
        // Hygiene: this test drives handleCreateTable, which records a POSITIVE
        // ownership verdict for the username in the STATIC TableOwnership cache —
        // and cleanup() consults that cache by username to decide keep-alive. A
        // stale "tester" verdict leaks into any later suite that closes a "tester"
        // session (WebProxyRejoinKeepAliveTest, observed failing without this).
        // Same teardown the other create-table suites use.
        TableOwnership.clear();
        SeatAliases.clear();
        appender = new WebProxyAuthCloseTest.RecordingAppender();
        appender.setThreshold(Level.TRACE);
        Logger.getRootLogger().addAppender(appender);
    }

    @After
    public void detachLogs() {
        TableOwnership.clear();
        SeatAliases.clear();
        Logger.getRootLogger().removeAppender(appender);
    }

    // ── the stand-in XMage session ──

    /**
     * A SessionImpl whose createTable hands back a real TableView and whose
     * joinTable refuses, optionally delivering the server's SHOW_USERMESSAGE reason
     * through the live WebProxyClient (as TableController.joinTable does) before it
     * returns false. Nothing here touches the network.
     */
    static class FakeXmageSession extends SessionImpl {
        final UUID roomId = UUID.randomUUID();
        volatile TableView table;
        /** When non-null, joinTable delivers this as the server's "Join Table" reason. */
        volatile String joinRejectionReason;
        /** The client the proxy installed — used to fire the real callback path. */
        volatile WebProxyClient client;
        /** A stale connect error that must NOT win over the server's reason. */
        volatile String lastError = "";
        final List<String> joinCalls = new CopyOnWriteArrayList<>();

        FakeXmageSession() {
            super(new MageClient() {
                @Override public mage.utils.MageVersion getVersion() { return null; }
                @Override public void connected(String message) { }
                @Override public void disconnected(boolean askToReconnect, boolean keepMySessionActive) { }
                @Override public void showMessage(String message) { }
                @Override public void showError(String message) { }
                @Override public void onNewConnection() { }
                @Override public void onCallback(ClientCallback callback) { }
            });
        }

        @Override
        public String getLastError() {
            return lastError;
        }

        @Override
        public UUID getMainRoomId() {
            return roomId;
        }

        @Override
        public TableView createTable(UUID roomId, MatchOptions options) {
            MatchOptions opts = new MatchOptions("test", "Two Player Duel", false);
            options.getPlayerTypes().forEach(opts.getPlayerTypes()::add);
            Match match = new FakeMatch();
            setOptions(match, opts);
            for (int i = 0; i < options.getPlayerTypes().size(); i++) {
                Player p = new StubPlayer("seat" + i, RangeOfInfluence.ALL);
                match.addPlayer(p, null);
            }
            Table t = new Table(this.roomId, "Two Player Duel", options.getName(), "tester",
                    (DeckValidator) null, new ArrayList<>(options.getPlayerTypes()),
                    null, match, Collections.emptySet(), false);
            table = new TableView(t);
            return table;
        }

        @Override
        public boolean joinTable(UUID roomId, UUID tableId, String playerName, PlayerType playerType,
                                 int skill, DeckCardLists deckList, String password) {
            joinCalls.add(playerName + ":" + playerType);
            // Fire the server's own user-message callback BEFORE returning false —
            // exactly the boundary TableController.joinTable is on.
            if (joinRejectionReason != null && client != null) {
                client.onCallback(new ClientCallback(ClientCallbackMethod.SHOW_USERMESSAGE, null,
                        Arrays.asList("Join Table", joinRejectionReason)));
            }
            return false;
        }

        @Override
        public java.util.Collection<TableView> getTables(UUID roomId) {
            return table == null ? Collections.emptyList() : Collections.singletonList(table);
        }

        @Override
        public boolean watchTable(UUID roomId, UUID tableId) {
            return true;
        }

        private static void setOptions(Match match, MatchOptions options) {
            try {
                java.lang.reflect.Field f = match.getClass().getSuperclass().getDeclaredField("options");
                f.setAccessible(true);
                f.set(match, options);
            } catch (Exception e) {
                throw new IllegalStateException("could not set MatchOptions on FakeMatch", e);
            }
        }
    }

    private static WebProxySession newSession(FakeXmageSession xmage) throws Exception {
        WebProxySession session = new WebProxySession();
        java.lang.reflect.Field xs = WebProxySession.class.getDeclaredField("xmageSession");
        xs.setAccessible(true);
        xs.set(session, xmage);
        WebProxyClient client = new WebProxyClient("tester");
        java.lang.reflect.Field pc = WebProxySession.class.getDeclaredField("proxyClient");
        pc.setAccessible(true);
        pc.set(session, client);
        xmage.client = client; // let the fake fire the real callback path
        return session;
    }

    private WebProxyAuthCloseTest.StubSession attachWs(WebProxySession session) {
        WebProxyAuthCloseTest.StubSession ws = new WebProxyAuthCloseTest.StubSession();
        session.onWebSocketConnect(ws);
        return ws;
    }

    private static void createTable(WebProxySession session, JsonArray playerConfigs) throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("type", "create_table");
        json.addProperty("name", "join-failure test");
        json.addProperty("gameType", "Two Player Duel");
        json.addProperty("deckType", "Constructed - Freeform");
        json.addProperty("winsNeeded", 1);
        json.add("playerConfigs", playerConfigs);
        json.addProperty("deck", "");
        Method m = WebProxySession.class.getDeclaredMethod("handleCreateTable", JsonObject.class);
        m.setAccessible(true);
        m.invoke(session, json);
    }

    /** [COMPUTER_MAD (deck auto-generated), HUMAN(no deck)] — seat 0 is the one joined. */
    private static JsonArray seats() {
        JsonArray seats = new JsonArray();
        JsonObject ai = new JsonObject();
        ai.addProperty("type", "COMPUTER_MAD");
        ai.addProperty("deck", "");
        ai.addProperty("isYou", false);
        ai.addProperty("name", "AI-0");
        seats.add(ai);
        JsonObject human = new JsonObject();
        human.addProperty("type", "HUMAN");
        human.addProperty("deck", "");
        human.addProperty("isYou", true);
        human.addProperty("name", "tester");
        seats.add(human);
        return seats;
    }

    /** Poll until a seat-join error frame appears (the auto-join task is async). */
    private static String awaitJoinErrorFrame(WebProxyAuthCloseTest.StubSession ws) throws Exception {
        long deadline = System.currentTimeMillis() + AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            for (String s : ws.sent) {
                if (s.contains("failed to join")) {
                    return com.google.gson.JsonParser.parseString(s).getAsJsonObject()
                            .get("message").getAsString();
                }
            }
            Thread.sleep(POLL_MS);
        }
        Assert.fail("timed out waiting for a seat-join error frame; sent=" + ws.sent);
        return null; // unreachable
    }

    // ── the defect: the server's reason reaches the browser verbatim ──

    @Test
    public void refusedSeatJoinForwardsTheServerReasonVerbatim() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        xmage.joinRejectionReason = "You can join a table only one time.";
        WebProxySession session = newSession(xmage);
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);

        createTable(session, seats());

        String message = awaitJoinErrorFrame(ws);
        Assert.assertEquals("the server's own rejection text must ride the frame VERBATIM",
                "Seat 0 failed to join: You can join a table only one time.", message);
        Assert.assertFalse("the reported empty-reason frame must be gone",
                message.endsWith("failed to join: ") || message.endsWith("failed to join:"));
    }

    @Test
    public void theServerReasonWinsOverAStaleLastError() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        xmage.joinRejectionReason = "No available seats.";
        xmage.lastError = "stale connect error from an earlier dial"; // must NOT win
        WebProxySession session = newSession(xmage);
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);

        createTable(session, seats());

        String message = awaitJoinErrorFrame(ws);
        Assert.assertEquals("the server's SHOW_USERMESSAGE text is authoritative",
                "Seat 0 failed to join: No available seats.", message);
    }

    // ── no server text: the reason is still non-empty AND logged at ERROR ──

    @Test
    public void noServerReasonYieldsAFailVisibleNonEmptyMessage() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession(); // joinRejectionReason stays null
        WebProxySession session = newSession(xmage);
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);

        createTable(session, seats());

        String message = awaitJoinErrorFrame(ws);
        Assert.assertTrue("a refused join must never render a bare trailing colon",
                message.endsWith("Seat 0 failed to join: ") == false && message.contains("failed to join: "));
        String reason = message.substring(message.indexOf("failed to join: ") + "failed to join: ".length());
        Assert.assertFalse("the reason must not be empty", reason.trim().isEmpty());
        Assert.assertTrue("the no-reason case must be logged at ERROR (Fail Fast, never silent)",
                appender.events.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getRenderedMessage() != null
                        && e.getRenderedMessage().contains("join refused but the server sent no reason text")));
    }

    // ── source guard: every "failed to join: " frame is built from the reason helper ──

    @Test
    public void sourceGuardNoEmptyReasonFrames() throws Exception {
        String src = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/mage/webproxy/WebProxySession.java")));

        // Both create paths must feed the frame from rawOrEmpty(reason).
        Assert.assertTrue("the match path must build its reason via the helper",
                src.contains("sendError(\"Seat \" + i + \" failed to join: \" + rawOrEmpty(reason));"));
        Assert.assertTrue("the tournament path must build its reason via the helper",
                src.contains("sendError(\"Tournament seat \" + i + \" failed to join: \" + rawOrEmpty(reason));"));

        // The reported bare empty-reason frame (getLastError() alone) must be gone.
        Assert.assertFalse("no site may emit the bare 'failed to join: ' + getLastError() frame",
                src.matches("(?s).*failed to join: \" \\+ rawOrEmpty\\(e != null \\? e : \"\"\\).*"));
    }
}
