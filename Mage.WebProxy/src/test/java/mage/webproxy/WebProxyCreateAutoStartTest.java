package mage.webproxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.DeckValidator;
import mage.constants.RangeOfInfluence;
import mage.game.FakeMatch;
import mage.game.Table;
import mage.game.match.Match;
import mage.game.match.MatchOptions;
import mage.players.Player;
import mage.players.PlayerType;
import mage.players.StubPlayer;
import mage.interfaces.MageClient;
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
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Regression tests for the create-time auto-start (the toast half).
 *
 * The reported incident: a table created with seat 0 = Computer-LLM and seat 1 =
 * Human left open (for a friend) fired the create-time auto-start against a 1/2
 * table; startMatch returned false (expected — the server only starts a
 * READY_TO_START table with all seats occupied) and the browser got
 * `Failed to start match: ` with an empty reason, for a condition that is simply
 * "waiting for a human seat".
 *
 * Contract pinned here:
 *  - expected condition (a seat was deliberately left open): NO error frame, and
 *    the skip is logged with the reason (no silent swallow);
 *  - genuine failure (all seats joined, startMatch still false): the error frame
 *    still goes out and is logged at ERROR;
 *  - the all-AI success path keeps its outcome (STARTED → the caller's
 *    auto-watch decision is preserved).
 *
 * The behavioural tests drive the REAL handleCreateTable on a real WebProxySession
 * whose xmageSession is a SessionImpl subclass standing in for the XMage server
 * (same technique as WebProxyRollbackTurnsTest / WebProxyTokenArtPreferenceTest;
 * TableView is built through the real Table + FakeMatch path as in
 * WebProxyMatchDecidedTest). The auto-join task runs on the session's own
 * background pool, so the assertions poll rather than assume synchrony.
 */
public class WebProxyCreateAutoStartTest {

    private static final long AWAIT_MS = 10_000;
    private static final long POLL_MS = 100;

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

    // ── the stand-in XMage session ──

    /**
     * A SessionImpl whose RPCs succeed locally: createTable hands back a real
     * TableView, joinTable accepts, startMatch answers as configured. Nothing here
     * touches the network (SessionImpl's own methods are all no-ops while
     * unconnected; the overrides are what the handler under test calls).
     */
    static class FakeXmageSession extends SessionImpl {
        final UUID roomId = UUID.randomUUID();
        final List<String> joined = new CopyOnWriteArrayList<>();
        volatile boolean startMatchResult;
        volatile TableView table;
        /** The real Table behind the TableView — the retry tests fill a seat on it. */
        volatile mage.game.Table realTable;

        FakeXmageSession() {
            super(new MageClient() {
                @Override public mage.utils.MageVersion getVersion() { return null; }
                @Override public void connected(String message) { }
                @Override public void disconnected(boolean askToReconnect, boolean keepMySessionActive) { }
                @Override public void showMessage(String message) { }
                @Override public void showError(String message) { }
                @Override public void onNewConnection() { }
                @Override public void onCallback(mage.interfaces.callback.ClientCallback callback) { }
            });
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
            realTable = t;
            table = new TableView(t);
            return table;
        }

        @Override
        public boolean joinTable(UUID roomId, UUID tableId, String playerName, PlayerType playerType,
                                 int skill, DeckCardLists deckList, String password) {
            joined.add(playerName + ":" + playerType);
            return true;
        }

        @Override
        public boolean startMatch(UUID roomId, UUID tableId) {
            return startMatchResult;
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
        java.lang.reflect.Field pc = WebProxySession.class.getDeclaredField("proxyClient");
        pc.setAccessible(true);
        pc.set(session, new WebProxyClient("tester"));
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
        json.addProperty("name", "create-time auto-start test");
        json.addProperty("gameType", "Two Player Duel");
        json.addProperty("deckType", "Constructed - Freeform");
        json.addProperty("winsNeeded", 1);
        json.add("playerConfigs", playerConfigs);
        json.addProperty("deck", "");
        Method m = WebProxySession.class.getDeclaredMethod("handleCreateTable", JsonObject.class);
        m.setAccessible(true);
        m.invoke(session, json);
    }

    private static JsonObject seat(String type, String deck, String name) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("deck", deck);
        o.addProperty("isYou", false);
        o.addProperty("name", name);
        return o;
    }

    private boolean loggedContaining(String fragment) {
        return appender.events.stream().anyMatch(e -> e.getRenderedMessage() != null
                && e.getRenderedMessage().contains(fragment));
    }

    private static boolean sentStartError(WebProxyAuthCloseTest.StubSession ws) {
        return ws.sent.stream().anyMatch(s -> s.contains("Failed to start match"));
    }

    /** Poll until `cond` holds or AWAIT_MS elapses (the auto-join task is async). */
    private static void await(String what, java.util.function.BooleanSupplier cond) throws Exception {
        long deadline = System.currentTimeMillis() + AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(POLL_MS);
        }
        Assert.fail("timed out waiting for: " + what);
    }

    private static void awaitQuiet(WebProxyAuthCloseTest.StubSession ws, String fragment) throws Exception {
        // Wait for the decision to be reached (a table_created frame is sent before the
        // background task even starts; the log line is the decision marker in the
        // expected path, an error frame in the genuine-failure path).
        long deadline = System.currentTimeMillis() + AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (sentStartError(ws)) {
                return;
            }
            Thread.sleep(POLL_MS);
        }
    }

    // ── the expected condition: a seat was left open on purpose ──

    @Test
    public void tableWithHumanSeatLeftOpenSendsNoStartError() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        WebProxySession session = newSession(xmage);
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);

        JsonArray seats = new JsonArray();
        seats.add(seat("COMPUTER_MAD", "", "AI-0"));
        seats.add(seat("HUMAN", "", "OpenSeat")); // no deck → auto-join leaves it open
        createTable(session, seats);

        // The decision marker: the skip log must appear ...
        await("the auto-start skip log line", () -> loggedContaining("Skipping create-time auto-start"));
        // ... and no start-error frame may ever reach the browser.
        Assert.assertFalse("a table with a deliberately open seat must NOT toast 'Failed to start match'",
                sentStartError(ws));
        Assert.assertTrue("the skip must state WHY (seat count + who starts the match)",
                loggedContaining("seat(s) left open on purpose"));
        // startMatch must not even have been attempted (nothing to fail).
        Assert.assertFalse("no auto-start attempt on a known not-full table",
                loggedContaining("startMatch returned"));
        session.onWebSocketClose(1000, "test over");
    }

    @Test
    public void secondHumanSeatLeftOpenAlsoSkips() throws Exception {
        // [HUMAN(creator, deck), HUMAN(open), COMPUTER_MAD] — the second human seat
        // has a deck but is still left open (only the creator's own seat is joined).
        FakeXmageSession xmage = new FakeXmageSession();
        WebProxySession session = newSession(xmage);
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);

        JsonArray seats = new JsonArray();
        seats.add(seat("HUMAN", "24 Mountain\n12 Grizzly Bears", "tester"));
        seats.add(seat("HUMAN", "24 Mountain\n12 Grizzly Bears", "Friend"));
        seats.add(seat("COMPUTER_MAD", "", "AI-0"));
        createTable(session, seats);

        await("the auto-start skip log line", () -> loggedContaining("Skipping create-time auto-start"));
        Assert.assertFalse("no 'Failed to start match' toast for the second-human-seat layout",
                sentStartError(ws));
    }

    // ── the genuine failure: all seats joined, startMatch still false ──

    @Test
    public void fullTableWithFailingStartStillSendsTheError() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        xmage.startMatchResult = false; // the server-side reject path
        WebProxySession session = newSession(xmage);
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);

        JsonArray seats = new JsonArray();
        seats.add(seat("COMPUTER_MAD", "", "AI-0"));
        seats.add(seat("HUMAN", "24 Mountain\n12 Grizzly Bears", "tester"));
        createTable(session, seats);

        await("the genuine-failure error frame", () -> sentStartError(ws));
        Assert.assertTrue("both seats must have been joined before the failure is called genuine",
                xmage.joined.size() == 2);
        Assert.assertTrue("a genuine start failure must still be logged at ERROR",
                appender.events.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getRenderedMessage() != null
                        && e.getRenderedMessage().contains("Create-time auto-start failed")));
        Assert.assertFalse("a genuine failure must NOT be reported as a skip",
                loggedContaining("Skipping create-time auto-start"));
    }

    // ── the success path keeps its outcome (auto-watch decision preserved) ──

    @Test
    public void allAiTableStartsAndKeepsTheAutoWatchPath() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        xmage.startMatchResult = true;
        WebProxySession session = newSession(xmage);
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);

        JsonArray seats = new JsonArray();
        seats.add(seat("COMPUTER_MAD", "", "AI-0"));
        seats.add(seat("COMPUTER_MAD", "", "AI-1"));
        createTable(session, seats);

        await("the all-AI auto-watch decision", () -> loggedContaining("All seats are AI"));
        Assert.assertFalse("the all-AI success path must not error", sentStartError(ws));
    }

    // ── the decision itself (unit-level, no server fake) ──

    @Test
    public void decisionSkipsWithoutCallingStartMatchWhenASeatIsOpen() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        WebProxySession session = newSession(xmage);

        WebProxySession.CreateAutoStartOutcome outcome =
                session.createTimeAutoStart(1, 2, xmage.roomId, UUID.randomUUID());

        Assert.assertEquals("a known not-full table is a SKIP, not a failure",
                WebProxySession.CreateAutoStartOutcome.SKIPPED_SEATS_OPEN, outcome);
        Assert.assertTrue("the skip must be logged with the reason",
                loggedContaining("Skipping create-time auto-start"));
    }

    @Test
    public void decisionReportsFailureWhenStartMatchFailsOnAFullTable() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        xmage.startMatchResult = false;
        WebProxySession session = newSession(xmage);
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);

        WebProxySession.CreateAutoStartOutcome outcome =
                session.createTimeAutoStart(0, 2, xmage.roomId, UUID.randomUUID());

        Assert.assertEquals("a full table that still fails is a genuine FAILED",
                WebProxySession.CreateAutoStartOutcome.FAILED, outcome);
        Assert.assertTrue("the genuine failure must reach the browser", sentStartError(ws));
    }

    @Test
    public void decisionReportsStartedOnSuccess() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        xmage.startMatchResult = true;
        WebProxySession session = newSession(xmage);
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);

        WebProxySession.CreateAutoStartOutcome outcome =
                session.createTimeAutoStart(0, 2, xmage.roomId, UUID.randomUUID());

        Assert.assertEquals(WebProxySession.CreateAutoStartOutcome.STARTED, outcome);
        Assert.assertFalse("a successful start must not error", sentStartError(ws));
    }

    // ── the reported message shape must not regress to the empty reason ──

    @Test
    public void genuineStartErrorCarriesTheServerReasonWhenPresent() throws Exception {
        // An empty reason is what the incident showed; when the server DOES report
        // one it must be included verbatim.
        FakeXmageSession xmage = new FakeXmageSession() {
            @Override
            public String getLastError() {
                return "Table is not ready";
            }

            @Override
            public boolean startMatch(UUID roomId, UUID tableId) {
                return false;
            }
        };
        WebProxySession session = newSession(xmage);
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);

        session.createTimeAutoStart(0, 2, xmage.roomId, UUID.randomUUID());

        Assert.assertTrue("the server's reason must ride along with the toast",
                ws.sent.stream().anyMatch(s -> s.contains("Failed to start match: Table is not ready")));
    }

    /** Guard: the skip branch must not grow an error send again. */
    @Test
    public void skipBranchSendsNoErrorInSource() throws Exception {
        String src = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/mage/webproxy/WebProxySession.java")));
        int i = src.indexOf("CreateAutoStartOutcome createTimeAutoStart");
        Assert.assertTrue("createTimeAutoStart not found", i >= 0);
        int skip = src.indexOf("Skipping create-time auto-start", i);
        int started = src.indexOf("Auto-starting match for table ", i);
        Assert.assertTrue("skip branch not found", skip > 0 && started > skip);
        Assert.assertFalse("the skip branch must not send a browser error",
                src.substring(i, started).contains("sendError("));
    }
}
