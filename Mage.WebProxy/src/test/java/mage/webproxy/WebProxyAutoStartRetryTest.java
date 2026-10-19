package mage.webproxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.DeckValidator;
import mage.constants.RangeOfInfluence;
import mage.constants.TableState;
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
 * Regression tests for the proxy's re-attempt on seat fill; the create-time
 * auto-start tests live in
 * {@link WebProxyCreateAutoStartTest}).
 *
 * The reported incident: a table created with seat 0 = Computer-LLM and seat 1 = Human
 * left open reached 2/2 READY_TO_START when the friend joined — and the match could never
 * begin: the proxy never re-attempted the create-time auto-start (the create-time start skips it for the
 * expected open seat), the owner holds no seat (no waiting panel, no grid action), and the
 * joiner is not the owner — so no browser ever sends start_match.
 *
 * Contract pinned here:
 *  - create [LLM seat, open Human seat] → the create-time start is SKIPPED and the
 *    re-attempt is ARMED ("Auto-start pending" log line);
 *  - when the last seat fills and the table turns READY_TO_START, the watch fires the
 *    re-attempt EXACTLY ONCE → startMatch invoked (exactly once), no error frame on success;
 *  - while the table is still WAITING / has an open seat, no attempt is made (held, not
 *    consumed);
 *  - manual-early-start / already-started (non-waiting state) → NO second attempt (pending
 *    dropped, silent);
 *  - a genuine failure (full + READY_TO_START, startMatch false) → the error frame still
 *    goes out (toast semantics preserved) and is NOT retried again;
 *  - a create whose attempt genuinely fails (full table) must NOT arm a retry;
 *  - teardown drops the pending id.
 *
 * The behavioural tests drive the REAL handleCreateTable on a real WebProxySession whose
 * xmageSession is a SessionImpl subclass standing in for the XMage server (same technique
 * as WebProxyCreateAutoStartTest; the fake now captures the real mage.game.Table so a test
 * can play "the server": seat the human, which flips the real table to READY_TO_START).
 * The auto-join task and the table watch run on the session's own background pool, so the
 * assertions poll rather than assume synchrony.
 */
public class WebProxyAutoStartRetryTest {

    private static final long AWAIT_MS = 10_000;
    private static final long POLL_MS = 100;

    private WebProxyAuthCloseTest.RecordingAppender appender;

    /**
     * Copy-on-write twin of {@link WebProxyAuthCloseTest.RecordingAppender}: this suite's
     * watch threads log from the watch executor while the test thread iterates the events,
     * and an ArrayList appender races into ConcurrentModificationException. Iteration must
     * be snapshot-consistent; appends stay cheap at log volume.
     */
    static class COWRecordingAppender extends org.apache.log4j.AppenderSkeleton {
        final List<org.apache.log4j.spi.LoggingEvent> events = new CopyOnWriteArrayList<>();
        @Override protected void append(org.apache.log4j.spi.LoggingEvent event) { events.add(event); }
        @Override public void close() { }
        @Override public boolean requiresLayout() { return false; }
    }

    // The session/ws under test, for the @After teardown (also on assertion failures).
    private WebProxySession currentSession;
    private WebProxyAuthCloseTest.StubSession currentWs;
    private boolean wsAttached;

    @Before
    public void attachLogs() {
        appender = new WebProxyAuthCloseTest.RecordingAppender();
        appender.setThreshold(Level.TRACE);
        Logger.getRootLogger().addAppender(appender);
    }

    @After
    public void closeSession() {
        // Stop THIS test's watch/auto-join threads even when an assertion failed mid-test —
        // a leaking watch keeps ticking (2 s, daemon) into every later test's assertions.
        if (currentSession != null) {
            try {
                currentSession.onWebSocketClose(1000, "test over");
            } catch (Throwable t) {
                // Teardown must not mask the test's own failure.
            }
        }
        currentSession = null;
        currentWs = null;
        Logger.getRootLogger().removeAppender(appender);
    }

    /**
     * Same stand-in as {@link WebProxyCreateAutoStartTest.FakeXmageSession} (kept separate so
     * this class owns the seat-fill simulation): createTable hands back a real TableView over
     * a real Table; getTables reflects whatever the test has done to that Table — including
     * a human seat filled later via {@link #joinHuman()}, which flips the table to
     * READY_TO_START exactly as the real server does (Table.joinTable → isReady).
     */
    static class FakeXmageSession extends SessionImpl {
        final UUID roomId = UUID.randomUUID();
        final List<String> joined = new CopyOnWriteArrayList<>();
        volatile boolean startMatchResult = true;
        volatile TableView table;
        volatile mage.game.Table realTable;
        volatile boolean throwOnStartMatch;
        int startMatchCalls; // read-only from the tests' single watcher thread

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

        /**
         * The proxy's table watch only ticks while the XMage session reads as connected
         * (tableWatchTick's first guard). A bare SessionImpl is never connected (its
         * callbackClient is null), so the fake must answer honestly for the simulated
         * environment: from the proxy's point of view the server IS reachable.
         */
        @Override
        public boolean isConnected() {
            return true;
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
            mage.game.Table t = new mage.game.Table(this.roomId, "Two Player Duel",
                    options.getName(), "tester", (DeckValidator) null,
                    new ArrayList<>(options.getPlayerTypes()), null, match,
                    Collections.emptySet(), false);
            realTable = t;
            table = new TableView(t);
            return table;
        }

        @Override
        public boolean joinTable(UUID roomId, UUID tableId, String playerName, PlayerType playerType,
                                 int skill, DeckCardLists deckList, String password) {
            joined.add(playerName + ":" + playerType);
            // Seat the player on the real Table — exactly what the real server does on a
            // successful joinTable RPC (TableController.joinTable → Table.joinTable, which
            // flips the table to READY_TO_START when the last seat fills). Without this the
            // seats stay empty and the watch can never observe the table become full.
            try {
                mage.game.Table t = realTable;
                if (t != null) {
                    mage.game.Seat seat = t.getNextAvailableSeat(playerType);
                    if (seat == null) {
                        return false; // no matching open seat — an honest reject
                    }
                    t.joinTable(new StubPlayer(playerName, RangeOfInfluence.ALL), seat);
                }
            } catch (Exception e) {
                // Surface as the RPC-level reject the real server would produce.
                return false;
            }
            return true;
        }

        /**
         * The room read the watch performs every tick — served from the fake's live
         * TableView, which the tests rebuild after mutating the real Table (seat fill,
         * state flips). Without this override the unconnected base SessionImpl answers
         * with nothing and every tick reads as a miss.
         */
        @Override
        public java.util.Collection<TableView> getTables(UUID roomId) {
            return table == null ? Collections.emptyList() : Collections.singletonList(table);
        }

        @Override
        public boolean startMatch(UUID roomId, UUID tableId) {
            startMatchCalls++;
            if (throwOnStartMatch) {
                throw new IllegalStateException("server exploded");
            }
            return startMatchResult;
        }
    }

    // ── the "server side" of the story: fill the open seat on the real Table ──

    /**
     * Simulate the human joining the open seat server-side: Seat.setPlayer + the real
     * Table.joinTable state machine (isReady → READY_TO_START). The TableView the proxy
     * reads is rebuilt from the Table each getTables call (as a real room read would).
     */
    private static void joinHuman(FakeXmageSession xmage, String playerName) throws Exception {
        Table t = xmage.realTable;
        Assert.assertNotNull("the fake must have a real Table to seat the human at", t);
        mage.game.Seat[] seats = t.getSeats();
        for (mage.game.Seat seat : seats) {
            if (seat.getPlayer() == null && seat.getPlayerType() == PlayerType.HUMAN) {
                Player p = new StubPlayer(playerName, RangeOfInfluence.ALL);
                t.joinTable(p, seat); // flips the table to READY_TO_START when full
                // The proxy re-reads through getTables → rebuild the view from the live table.
                xmage.table = new TableView(t);
                return;
            }
        }
        Assert.fail("no open HUMAN seat to fill on the fake table");
    }

    // ── session/ws plumbing (same as WebProxyCreateAutoStartTest) ──

    private static WebProxySession newSession(FakeXmageSession xmage) throws Exception {
        WebProxySession session = new WebProxySession();
        java.lang.reflect.Field xs = WebProxySession.class.getDeclaredField("xmageSession");
        xs.setAccessible(true);
        xs.set(session, xmage);
        java.lang.reflect.Field pc = WebProxySession.class.getDeclaredField("proxyClient");
        pc.setAccessible(true);
        pc.set(session, new WebProxyClient("tester"));
        // An LLM-capable server: the incident's table was created with a Computer-LLM
        // seat, and requireServerLlm would otherwise fail-fast the whole create.
        java.lang.reflect.Field caps = WebProxySession.class.getDeclaredField("serverCapabilities");
        caps.setAccessible(true);
        caps.set(session, ServerCapabilities.forTest(null,
                new ArrayList<>(Collections.singletonList(PlayerType.COMPUTER_LLM)),
                true, true, true));
        return session;
    }

    private WebProxyAuthCloseTest.StubSession attachWs(WebProxySession session) {
        WebProxyAuthCloseTest.StubSession ws = new WebProxyAuthCloseTest.StubSession();
        session.onWebSocketConnect(ws);
        // Recorded for the @After teardown (also runs when an assertion fails mid-test).
        this.currentSession = session;
        this.currentWs = ws;
        return ws;
    }

    private static void createLlmPlusOpenSeatTable(WebProxySession session) throws Exception {
        JsonArray seats = new JsonArray();
        seats.add(llmSeat());
        seats.add(openHumanSeat());
        createTable(session, seats);
    }

    private static void createTable(WebProxySession session, JsonArray playerConfigs) throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("type", "create_table");
        json.addProperty("name", "auto-start retry test");
        json.addProperty("gameType", "Two Player Duel");
        json.addProperty("deckType", "Constructed - Freeform");
        json.addProperty("winsNeeded", 1);
        json.add("playerConfigs", playerConfigs);
        json.addProperty("deck", "");
        Method m = WebProxySession.class.getDeclaredMethod("handleCreateTable", JsonObject.class);
        m.setAccessible(true);
        m.invoke(session, json);
    }

    /** A Computer-LLM seat WITH its config — the layout from the incident. */
    private static JsonObject llmSeat() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "COMPUTER_LLM");
        o.addProperty("deck", "");
        o.addProperty("isYou", false);
        o.addProperty("name", "AI-0");
        JsonObject llm = new JsonObject();
        llm.addProperty("model", "test-model");
        o.add("llmConfig", llm);
        return o;
    }

    /** A Human seat with no deck → the auto-join task deliberately leaves it open. */
    private static JsonObject openHumanSeat() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "HUMAN");
        o.addProperty("deck", "");
        o.addProperty("isYou", false);
        o.addProperty("name", "OpenSeat");
        return o;
    }

    private static boolean sentStartError(WebProxyAuthCloseTest.StubSession ws) {
        return ws.sent.stream().anyMatch(s -> s.contains("Failed to start match"));
    }

    private boolean loggedContaining(String fragment) {
        return appender.events.stream().anyMatch(e -> e.getRenderedMessage() != null
                && e.getRenderedMessage().contains(fragment));
    }

    /** Poll until `cond` holds or AWAIT_MS elapses (the auto-join task and watch are async). */
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

    /** Give the watch a few ticks (≥ 3 × interval) to act or refrain. */
    private static void letWatchRun() throws Exception {
        Thread.sleep(7_000);
    }

    // ── the happy path: create with an open seat → skip → join → fire exactly once ──

    @Test
    public void openSeatCreateSkipsThenFiresExactlyOnceWhenLastSeatFills() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        xmage.startMatchResult = true;
        WebProxySession session = newSession(xmage);
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);

        createLlmPlusOpenSeatTable(session);

        // The skip happened at create time ...
        await("the auto-start skip log line", () -> loggedContaining("Skipping create-time auto-start"));
        // ... and the re-attempt was armed.
        await("the candidate-1 arming log line", () -> loggedContaining("Auto-start pending for table"));
        Assert.assertFalse("no start attempt while a seat is deliberately open",
                loggedContaining("re-attempting auto-start"));

        // The human fills the last seat → the table turns READY_TO_START.
        joinHuman(xmage, "Friend");

        await("the single re-attempt", () -> loggedContaining("re-attempting auto-start"));
        await("the startMatch result log", () -> loggedContaining("startMatch (pending auto-start) returned true"));

        // Give the watch more ticks, then assert EXACTLY ONCE.
        Thread.sleep(5_000);
        Assert.assertEquals("the re-attempt must fire EXACTLY ONCE per table",
                1, xmage.startMatchCalls);
        Assert.assertFalse("a successful re-attempt must not toast",
                sentStartError(ws));
        session.onWebSocketClose(1000, "test over");
    }

    // ── held, not consumed: the waiting state must never burn the pending start ──

    @Test
    public void pendingSurvivesWaitingTicksAndFiresOnlyWhenReady() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        xmage.startMatchResult = true;
        WebProxySession session = newSession(xmage);
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);

        createLlmPlusOpenSeatTable(session);
        await("the arming log line", () -> loggedContaining("Auto-start pending for table"));

        // Several watch ticks while the table is still WAITING — none may consume the
        // pending start, none may call startMatch.
        letWatchRun();
        Assert.assertEquals("WAITING ticks must not attempt the start",
                0, xmage.startMatchCalls);
        Assert.assertTrue("the pending start must still be armed after WAITING ticks",
                loggedContaining("Auto-start pending for table"));

        // NOW the seat fills → the very next tick fires it.
        joinHuman(xmage, "Friend");
        await("the re-attempt after the seat fill", () -> loggedContaining("re-attempting auto-start"));
        Assert.assertEquals("the re-attempt fires on the first READY_TO_START tick",
                1, xmage.startMatchCalls);
        session.onWebSocketClose(1000, "test over");
    }

    // ── the open-seat guard: never fire while a seat is still open ──

    @Test
    public void pendingNeverFiresWhileASeatIsOpen() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        xmage.startMatchResult = true;
        WebProxySession session = newSession(xmage);
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);

        createLlmPlusOpenSeatTable(session);
        await("the arming log line", () -> loggedContaining("Auto-start pending for table"));

        // Simulate a server anomaly: the table claims READY_TO_START while a seat is still
        // open. The proxy's own read sees the empty SeatView and must hold the start.
        xmage.realTable.setState(TableState.READY_TO_START); // NOT via joinTable — seat stays empty
        xmage.table = new TableView(xmage.realTable); // the watch re-reads the fresh view
        letWatchRun();

        Assert.assertEquals("the start must NOT be attempted over an open seat",
                0, xmage.startMatchCalls);
        Assert.assertTrue("the open-seat hold must be logged (fail-fast, not silent)",
                loggedContaining("a seat is still open"));
        Assert.assertFalse("no error frame for an expected hold", sentStartError(ws));

        // The seat then fills → the held start fires.
        joinHuman(xmage, "Friend");
        await("the re-attempt after the hold", () -> loggedContaining("re-attempting auto-start"));
        Assert.assertEquals("the held start fires exactly once once the seat fills",
                1, xmage.startMatchCalls);
        session.onWebSocketClose(1000, "test over");
    }

    // ── the owner started it manually before the watch could: no second attempt ──

    @Test
    public void manualStartBeforeTheWatchConsumesThePending() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        xmage.startMatchResult = true;
        WebProxySession session = newSession(xmage);
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);

        createLlmPlusOpenSeatTable(session);
        await("the arming log line", () -> loggedContaining("Auto-start pending for table"));

        // The owner (or anyone with the control) fires start_match manually first.
        JsonObject sm = new JsonObject();
        sm.addProperty("type", "start_match");
        sm.addProperty("tableId", xmage.table.getTableId().toString());
        Method hsm = WebProxySession.class.getDeclaredMethod("handleStartMatch", JsonObject.class);
        hsm.setAccessible(true);
        hsm.invoke(session, sm);
        await("the manual-start consumption log", () -> loggedContaining("consumed the pending auto-start"));

        // The seat then fills — but the owed start was already consumed by the manual one.
        joinHuman(xmage, "Friend");
        letWatchRun();

        Assert.assertEquals("the manual start must be the ONLY startMatch call",
                1, xmage.startMatchCalls);
        Assert.assertFalse("the manual path must not toast", sentStartError(ws));
        session.onWebSocketClose(1000, "test over");
    }

    // ── the table left the waiting lifecycle: pending dropped silently, no attempt ──

    @Test
    public void pendingDroppedSilentlyWhenTableLeavesWaitingLifecycle() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        xmage.startMatchResult = true;
        WebProxySession session = newSession(xmage);
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);

        createLlmPlusOpenSeatTable(session);
        await("the arming log line", () -> loggedContaining("Auto-start pending for table"));

        // The match starts out-of-band (e.g. via the desktop client / restart scenario):
        // the table leaves the waiting lifecycle with an open seat still open.
        xmage.realTable.setState(TableState.DUELING);
        xmage.table = new TableView(xmage.realTable); // the watch re-reads the fresh view
        letWatchRun();

        Assert.assertEquals("a table that is no longer waiting must never be started by the watch",
                0, xmage.startMatchCalls);
        Assert.assertTrue("the drop must be logged with the reason",
                loggedContaining("Dropping pending auto-start"));
        Assert.assertFalse("the drop must stay silent to the browser", sentStartError(ws));
        session.onWebSocketClose(1000, "test over");
    }

    // ── genuine failure: the error frame still goes out, and it is NOT retried ──

    @Test
    public void genuineFailureSendsErrorFrameExactlyOnce() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        xmage.startMatchResult = false; // the server-side reject path on a full READY_TO_START table
        WebProxySession session = newSession(xmage);
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);

        createLlmPlusOpenSeatTable(session);
        await("the arming log line", () -> loggedContaining("Auto-start pending for table"));

        joinHuman(xmage, "Friend");
        await("the genuine-failure error frame", () -> sentStartError(ws));
        await("the genuine-failure log", () -> loggedContaining("Pending auto-start failed"));

        letWatchRun();
        Assert.assertEquals("a genuine failure must NOT be retried again",
                1, xmage.startMatchCalls);
        Assert.assertTrue("a genuine re-attempt failure must still be logged at ERROR",
                appender.events.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getRenderedMessage() != null
                        && e.getRenderedMessage().contains("Pending auto-start failed")));
        long errorFrames = ws.sent.stream().filter(s -> s.contains("Failed to start match")).count();
        Assert.assertEquals("exactly one error frame — no 2 s toast spam", 1L, errorFrames);
        session.onWebSocketClose(1000, "test over");
    }

    // ── a create whose attempt FAILED (full table) must not arm a retry ──

    @Test
    public void genuineCreateTimeFailureDoesNotArmARetry() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        xmage.startMatchResult = false;
        WebProxySession session = newSession(xmage);
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);

        // Full layout: [LLM with deck-less AI auto-join, Human WITH deck (creator's own seat)].
        JsonArray seats = new JsonArray();
        seats.add(llmSeat());
        JsonObject human = new JsonObject();
        human.addProperty("type", "HUMAN");
        human.addProperty("deck", "24 Mountain\n12 Grizzly Bears");
        human.addProperty("isYou", true);
        human.addProperty("name", "tester");
        seats.add(human);
        createTable(session, seats);

        await("the genuine create-time failure", () -> sentStartError(ws));
        await("the create-time failure log", () -> loggedContaining("Create-time auto-start failed"));

        letWatchRun();
        Assert.assertEquals("a FAILED create must not arm the re-attempt (startMatch was called once, at create)",
                1, xmage.startMatchCalls);
        Assert.assertFalse("no arming line for a genuine create-time failure",
                loggedContaining("Auto-start pending for table"));
        session.onWebSocketClose(1000, "test over");
    }

    // ── the throw path: a throwing startMatch still sends exactly one error frame ──

    @Test
    public void throwingStartMatchConsumesPendingAndSendsOneErrorFrame() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        xmage.throwOnStartMatch = true;
        WebProxySession session = newSession(xmage);
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);

        createLlmPlusOpenSeatTable(session);
        await("the arming log line", () -> loggedContaining("Auto-start pending for table"));

        joinHuman(xmage, "Friend");
        await("the throw-path error frame", () -> sentStartError(ws));
        await("the throw-path log", () -> loggedContaining("Pending auto-start threw"));

        letWatchRun();
        Assert.assertEquals("the throw consumed the pending id — no second attempt",
                1, xmage.startMatchCalls);
        long errorFrames = ws.sent.stream().filter(s -> s.contains("Failed to start match")).count();
        Assert.assertEquals("exactly one error frame on the throw path", 1L, errorFrames);
        session.onWebSocketClose(1000, "test over");
    }

    // ── teardown drops the pending id ──

    @Test
    public void teardownDropsThePendingId() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        xmage.startMatchResult = true;
        WebProxySession session = newSession(xmage);
        attachWs(session);

        createLlmPlusOpenSeatTable(session);
        await("the arming log line", () -> loggedContaining("Auto-start pending for table"));

        session.onWebSocketClose(1000, "test over");
        java.lang.reflect.Field f = WebProxySession.class.getDeclaredField("pendingAutoStartTableId");
        f.setAccessible(true);
        Assert.assertNull("cleanup must drop the owed auto-start", f.get(session));
    }

    // ── source-level guard: the watch hook sits BEFORE the signature early-return ──

    @Test
    public void reAttemptHookSitsBeforeTheSignatureEarlyReturnInSource() throws Exception {
        String src = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/mage/webproxy/WebProxySession.java")));
        int tick = src.indexOf("private void tableWatchTick()");
        Assert.assertTrue("tableWatchTick not found", tick > 0);
        int hook = src.indexOf("tryPendingAutoStart(session, watched, state);", tick);
        int signature = src.indexOf("String signature = watched.getSeatsInfo()", tick);
        Assert.assertTrue("the re-attempt hook must exist in tableWatchTick", hook > tick);
        Assert.assertTrue("the hook must run BEFORE the signature-diff early-return",
                hook < signature);
        int earlyReturn = src.indexOf("if (action == TableWatchDecision.Action.SKIP)", hook);
        Assert.assertTrue("early return not found after the hook", earlyReturn > hook);
    }

    // ── shared reflection helper for MatchOptions (same as the auto-start test class) ──

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