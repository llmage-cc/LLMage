package mage.webproxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Candidate 2: the
 * proxy-side ownership resolution behind the table list's {@code "owner": true}
 * flag — {@link WebProxySession#resolveOwnedTableIds} and the create-time record.
 *
 * <p>This is the server-authoritative half of the fix: the browser learns ownership
 * from the wire instead of only from its own {@code table_created} frame, so the owner
 * of a table they hold NO seat at keeps a Start Match control across a reload. The
 * contract pinned here is the one the method documents:
 *
 * <ul>
 *   <li>only NON-TOURNAMENT WAITING/READY_TO_START tables are ever probed (a started
 *       table is past the window; tournament starts are a different verb);</li>
 *   <li>a cache hit answers without an RPC — the whole point of
 *       {@link TableOwnership} (the table list is pushed every 2 s while seated);</li>
 *   <li>a DISCONNECTED session probes nothing and caches nothing (a probe could only
 *       fail, and remembering that failure as a negative would hide the owner's
 *       control) — fail-open, never a fabricated verdict;</li>
 *   <li>a probe that throws is not remembered as a negative either;</li>
 *   <li>the successful create RPC records ownership immediately, so the first
 *       table-list push after create carries the flag with no probe at all.</li>
 * </ul>
 *
 * <p>Tests drive the REAL methods on a real WebProxySession whose {@code xmageSession}
 * is a SessionImpl subclass standing in for the XMage server — the same technique as
 * {@link WebProxyCreateAutoStartTest} / {@link WebProxyRollbackTurnsTest}. TableViews
 * are built through the real {@link Table} + {@link FakeMatch} path, which is the same
 * bookkeeping the server builds them from.
 */
public class WebProxyResolveOwnedTablesTest {

    private WebProxyAuthCloseTest.RecordingAppender appender;

    @Before
    public void attachLogs() {
        TableOwnership.clear();
        appender = new WebProxyAuthCloseTest.RecordingAppender();
        appender.setThreshold(Level.TRACE);
        Logger.getRootLogger().addAppender(appender);
    }

    @After
    public void detachLogs() {
        TableOwnership.clear();
        Logger.getRootLogger().removeAppender(appender);
    }

    // ── the stand-in XMage session ──

    /**
     * A SessionImpl whose RPCs answer locally. {@code isTableOwner} counts its calls and
     * answers from a per-table map, so a test can assert BOTH the answer and how often it
     * was asked (the cache contract). {@code connected} models the live link: while false
     * the resolver must not probe at all.
     */
    static class FakeXmageSession extends SessionImpl {
        final UUID roomId = UUID.randomUUID();
        final List<UUID> ownerProbes = new CopyOnWriteArrayList<>();
        final Set<UUID> ownedTables = new LinkedHashSet<>();
        /** A table id whose probe throws — the torn-down-session case. */
        volatile UUID throwingTableId;
        volatile boolean connected = true;
        /** The TableView handed back by createTable (a real one, built from a real Table). */
        volatile TableView createdTable;

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
        public boolean isConnected() {
            return connected;
        }

        @Override
        public UUID getMainRoomId() {
            return roomId;
        }

        @Override
        public TableView createTable(UUID roomId, MatchOptions options) {
            TableView t = matchTable(this.roomId, options.getName(), TableState.WAITING);
            createdTable = t;
            return t;
        }

        @Override
        public boolean joinTable(UUID roomId, UUID tableId, String playerName, PlayerType playerType,
                                 int skill, DeckCardLists deckList, String password) {
            return true;
        }

        @Override
        public boolean isTableOwner(UUID roomId, UUID tableId) {
            ownerProbes.add(tableId);
            if (throwingTableId != null && throwingTableId.equals(tableId)) {
                throw new IllegalStateException("session torn down mid-call");
            }
            return ownedTables.contains(tableId);
        }

        /** connectStop arguments observed — the cleanup() keep/hard decision under test. */
        volatile Boolean lastAskForReconnect;
        volatile Boolean lastKeepMySessionActive;
        int connectStopCalls;

        @Override
        public synchronized void connectStop(boolean askForReconnect, boolean keepMySessionActive) {
            connectStopCalls++;
            lastAskForReconnect = askForReconnect;
            lastKeepMySessionActive = keepMySessionActive;
        }
    }

    private static WebProxySession newSession(FakeXmageSession xmage, String username) throws Exception {
        WebProxySession session = new WebProxySession();
        java.lang.reflect.Field xs = WebProxySession.class.getDeclaredField("xmageSession");
        xs.setAccessible(true);
        xs.set(session, xmage);
        java.lang.reflect.Field pc = WebProxySession.class.getDeclaredField("proxyClient");
        pc.setAccessible(true);
        pc.set(session, new WebProxyClient(username));
        return session;
    }

    // ── fixture tables through the real Table/TableView path ──

    private static TableView matchTable(UUID roomId, String name, TableState state) {
        MatchOptions opts = new MatchOptions(name, "Two Player Duel", false);
        Match match = new FakeMatch();
        setOptions(match, opts);
        Player p1 = new StubPlayer("seat0", RangeOfInfluence.ALL);
        Player p2 = new StubPlayer("seat1", RangeOfInfluence.ALL);
        match.addPlayer(p1, null);
        match.addPlayer(p2, null);
        List<PlayerType> types = new ArrayList<>();
        types.add(PlayerType.HUMAN);
        types.add(PlayerType.HUMAN);
        Table t = new Table(roomId, "Two Player Duel", name, "tester",
                (DeckValidator) null, types, null, match, Collections.emptySet(), false);
        t.setState(state);
        return new TableView(t);
    }

    /**
     * A concrete tournament for the isTournament() fixture path. TournamentSingleElimination
     * implements every Tournament method and is only declared abstract for subclassing, so a
     * no-op subclass is enough to build a real tournament Table + TableView. The bits
     * TableView's tournament branch reads are filled in explicitly: booster info
     * (getBoosterInfo().isEmpty()) and a non-null TournamentType (isLimited()).
     */
    static class StubTournament extends mage.game.tournament.TournamentSingleElimination {
        StubTournament(mage.game.tournament.TournamentOptions options) {
            super(options);
            setBoosterInfo("");
            mage.game.tournament.TournamentType type = new mage.game.tournament.TournamentType() { };
            setTournamentType(type);
        }

        @Override
        public void nextStep() {
            // no-op: this fixture is never played, only rendered into a TableView
        }
    }

    /** A tournament table (isTournament true) — a real tournament Table + TableView. */
    private static TableView tournamentTable(UUID roomId, String name) {
        mage.game.tournament.TournamentOptions options =
                new mage.game.tournament.TournamentOptions(name, "Two Player Duel", false);
        options.setPassword("");
        options.setTournamentType("Constructed Elimination");
        options.getMatchOptions().setDeckType("Constructed - Freeform");
        List<PlayerType> types = new ArrayList<>();
        types.add(PlayerType.HUMAN);
        types.add(PlayerType.HUMAN);
        StubTournament tournament = new StubTournament(options);
        Table t = new Table(roomId, "Constructed Elimination", name, "tester",
                (DeckValidator) null, types, null, tournament, Collections.emptySet(), false);
        t.setState(TableState.WAITING);
        return new TableView(t);
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

    // ── the flags themselves ──

    @Test
    public void flagsOnlyOurWaitingAndReadyTables() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        WebProxySession session = newSession(xmage, "alice");

        TableView mineWaiting = matchTable(xmage.roomId, "mine-waiting", TableState.WAITING);
        TableView mineReady = matchTable(xmage.roomId, "mine-ready", TableState.READY_TO_START);
        TableView mineDueling = matchTable(xmage.roomId, "mine-dueling", TableState.DUELING);
        TableView theirs = matchTable(xmage.roomId, "theirs", TableState.WAITING);
        TableView tournament = tournamentTable(xmage.roomId, "tourney");

        xmage.ownedTables.add(mineWaiting.getTableId());
        xmage.ownedTables.add(mineReady.getTableId());
        xmage.ownedTables.add(mineDueling.getTableId());
        xmage.ownedTables.add(tournament.getTableId());

        List<TableView> tables = new ArrayList<>();
        tables.add(mineWaiting);
        tables.add(mineReady);
        tables.add(mineDueling);
        tables.add(theirs);
        tables.add(tournament);

        Collection<UUID> owned = session.resolveOwnedTableIds(xmage, tables);

        Assert.assertTrue("WAITING own table must be flagged", owned.contains(mineWaiting.getTableId()));
        Assert.assertTrue("READY_TO_START own table must be flagged", owned.contains(mineReady.getTableId()));
        Assert.assertFalse("a DUELING table is past the window — never flagged",
                owned.contains(mineDueling.getTableId()));
        Assert.assertFalse("someone else's table must never be flagged",
                owned.contains(theirs.getTableId()));
        Assert.assertFalse("a tournament table is never flagged (different start verb)",
                owned.contains(tournament.getTableId()));
        Assert.assertEquals("exactly the two in-window own tables", 2, owned.size());
    }

    @Test
    public void positiveVerdictIsCachedAcrossCalls() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        WebProxySession session = newSession(xmage, "alice");
        TableView mine = matchTable(xmage.roomId, "mine", TableState.WAITING);
        xmage.ownedTables.add(mine.getTableId());

        session.resolveOwnedTableIds(xmage, Collections.singletonList(mine));
        Assert.assertEquals("one probe on a cold cache", 1, xmage.ownerProbes.size());

        session.resolveOwnedTableIds(xmage, Collections.singletonList(mine));
        session.resolveOwnedTableIds(xmage, Collections.singletonList(mine));
        Assert.assertEquals("a positive verdict must not be re-probed", 1, xmage.ownerProbes.size());
    }

    @Test
    public void negativeVerdictIsCachedWithinTtl() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        WebProxySession session = newSession(xmage, "alice");
        TableView theirs = matchTable(xmage.roomId, "theirs", TableState.WAITING);
        // not in ownedTables → probe answers false

        Collection<UUID> owned = session.resolveOwnedTableIds(xmage, Collections.singletonList(theirs));
        Assert.assertTrue("a non-owned table must not be flagged", owned.isEmpty());
        Assert.assertEquals(1, xmage.ownerProbes.size());

        session.resolveOwnedTableIds(xmage, Collections.singletonList(theirs));
        Assert.assertEquals("a negative verdict is cached for the TTL", 1, xmage.ownerProbes.size());
    }

    @Test
    public void disconnectedSessionProbesAndCachesNothing() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        WebProxySession session = newSession(xmage, "alice");
        TableView mine = matchTable(xmage.roomId, "mine", TableState.WAITING);
        xmage.ownedTables.add(mine.getTableId());
        xmage.connected = false;

        Collection<UUID> owned = session.resolveOwnedTableIds(xmage, Collections.singletonList(mine));

        Assert.assertTrue("a disconnected session cannot learn ownership", owned.isEmpty());
        Assert.assertEquals("no probe may run while disconnected", 0, xmage.ownerProbes.size());
        Assert.assertNull("nothing may be cached from a probe that never ran",
                TableOwnership.cached("alice", mine.getTableId(), System.currentTimeMillis()));
    }

    @Test
    public void throwingProbeIsNotRememberedAsNegative() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        WebProxySession session = newSession(xmage, "alice");
        TableView mine = matchTable(xmage.roomId, "mine", TableState.WAITING);
        xmage.ownedTables.add(mine.getTableId());
        xmage.throwingTableId = mine.getTableId();

        Collection<UUID> owned = session.resolveOwnedTableIds(xmage, Collections.singletonList(mine));

        Assert.assertTrue("a throwing probe cannot claim the table", owned.isEmpty());
        Assert.assertNull("a FAILED probe must never be cached as a false negative",
                TableOwnership.cached("alice", mine.getTableId(), System.currentTimeMillis()));

        // Once the probe works again the answer is found immediately (no stale negative).
        xmage.throwingTableId = null;
        Collection<UUID> retried = session.resolveOwnedTableIds(xmage, Collections.singletonList(mine));
        Assert.assertTrue("the very next resolve must find the ownership", retried.contains(mine.getTableId()));
    }

    @Test
    public void nullAndEmptyInputsAreSafe() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        WebProxySession session = newSession(xmage, "alice");

        Assert.assertTrue(session.resolveOwnedTableIds(null, null).isEmpty());
        Assert.assertTrue(session.resolveOwnedTableIds(xmage, null).isEmpty());
        Assert.assertTrue(session.resolveOwnedTableIds(xmage, Collections.<TableView>emptyList()).isEmpty());
        Assert.assertEquals("no probe for an empty table list", 0, xmage.ownerProbes.size());
    }

    // ── create-time record ──

    @Test
    public void createTimeRecordMakesTheFirstPushProbeFree() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        WebProxySession session = newSession(xmage, "alice");
        WebProxyAuthCloseTest.StubSession ws = new WebProxyAuthCloseTest.StubSession();
        session.onWebSocketConnect(ws);

        // Drive the REAL handleCreateTable; the fake createTable builds a real TableView.
        JsonArray seats = new JsonArray();
        seats.add(seat("COMPUTER_MAD", "", "AI-0"));
        seats.add(seat("HUMAN", "", "OpenSeat"));
        JsonObject json = new JsonObject();
        json.addProperty("type", "create_table");
        json.addProperty("name", "owned-tables test");
        json.addProperty("gameType", "Two Player Duel");
        json.addProperty("deckType", "Constructed - Freeform");
        json.addProperty("winsNeeded", 1);
        json.add("playerConfigs", seats);
        json.addProperty("deck", "");
        Method m = WebProxySession.class.getDeclaredMethod("handleCreateTable", JsonObject.class);
        m.setAccessible(true);
        m.invoke(session, json);

        TableView created = xmage.createdTable;
        Assert.assertNotNull("the fake session must have served the create RPC", created);
        UUID createdId = created.getTableId();
        Assert.assertEquals("the create RPC itself records ownership — no probe needed",
                Boolean.TRUE, TableOwnership.cached("alice", createdId, System.currentTimeMillis()));

        // The resolver must now flag the created table WITHOUT probing the server, even
        // though this fake session would answer false for it (ownedTables is untouched).
        int probesBefore = xmage.ownerProbes.size();
        Collection<UUID> owned = session.resolveOwnedTableIds(xmage, Collections.singletonList(created));
        Assert.assertTrue("the created table must be flagged from the create-time record",
                owned.contains(createdId));
        Assert.assertEquals("the create-time record must spare the probe",
                probesBefore, xmage.ownerProbes.size());
        session.onWebSocketClose(1000, "test over");
    }

    private static JsonObject seat(String type, String deck, String name) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("deck", deck);
        o.addProperty("isYou", false);
        o.addProperty("name", name);
        return o;
    }

    // ── the cleanup()-time keep decision (reload persistence) ──

    /**
     * The reload keystone: a browser close hard-disconnects unless the server-side
     * session is kept, and a hard disconnect makes the server DELETE a WAITING/READY
     * table whose owner it was (TableController.leaveTable) — taking the non-seated
     * owner's start control with it. Once this session has PROVEN ownership of an
     * unstarted table (the create-time record or a positive probe), the close must keep
     * the session exactly like a live game does.
     */
    @Test
    public void closeAfterOwningAnUnstartedTableKeepsSession() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        WebProxySession session = newSession(xmage, "alice");
        WebProxyAuthCloseTest.StubSession ws = new WebProxyAuthCloseTest.StubSession();
        session.onWebSocketConnect(ws);

        // Ownership proven by the create RPC (no probe needed) — the incident's shape:
        // [Computer-LLM, Human] table, owner holds no seat, nothing started.
        JsonArray seats = new JsonArray();
        seats.add(seat("COMPUTER_MAD", "", "AI-0"));
        seats.add(seat("HUMAN", "", "OpenSeat"));
        JsonObject json = new JsonObject();
        json.addProperty("type", "create_table");
        json.addProperty("name", "keep-alive test");
        json.addProperty("gameType", "Two Player Duel");
        json.addProperty("deckType", "Constructed - Freeform");
        json.addProperty("winsNeeded", 1);
        json.add("playerConfigs", seats);
        json.addProperty("deck", "");
        Method m = WebProxySession.class.getDeclaredMethod("handleCreateTable", JsonObject.class);
        m.setAccessible(true);
        m.invoke(session, json);
        Assert.assertNotNull("fixture must have created a table", xmage.createdTable);

        // The browser close (F5) — no game is live.
        session.onWebSocketClose(1001, "reload while owning an unstarted table");

        Assert.assertEquals("cleanup must have consulted connectStop once", 1, xmage.connectStopCalls);
        Assert.assertEquals("owning an unstarted table must KEEP the session "
                + "(a hard disconnect makes the server delete the table)",
                Boolean.TRUE, xmage.lastKeepMySessionActive);
        Assert.assertEquals("keep-alive must not ask for reconnect",
                Boolean.FALSE, xmage.lastAskForReconnect);
        Assert.assertTrue("the keep must be logged",
                appender.events.stream().anyMatch(e -> e.getRenderedMessage() != null
                        && e.getRenderedMessage().startsWith("Kept XMage session alive for")));
    }

    /**
     * Control: a close from a session that only ever saw OTHER people's tables must still
     * hard-disconnect — a negative verdict may never hold a session open.
     */
    @Test
    public void closeWithOnlyNegativeVerdictsStillDisconnectsHard() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        WebProxySession session = newSession(xmage, "alice");
        WebProxyAuthCloseTest.StubSession ws = new WebProxyAuthCloseTest.StubSession();
        session.onWebSocketConnect(ws);

        TableView theirs = matchTable(xmage.roomId, "theirs", TableState.WAITING);
        session.resolveOwnedTableIds(xmage, Collections.singletonList(theirs));
        Assert.assertEquals("the probe must have run", 1, xmage.ownerProbes.size());

        session.onWebSocketClose(1001, "close from the lobby");

        Assert.assertEquals("a negative verdict must not keep the session",
                Boolean.FALSE, xmage.lastKeepMySessionActive);
    }

    /**
     * A disconnect while NO ownership is known (never seen a table) keeps hard — the
     * pre-existing lobby-close behavior must survive this extension.
     */
    @Test
    public void closeWithoutAnyOwnershipStillDisconnectsHard() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        WebProxySession session = newSession(xmage, "alice");
        WebProxyAuthCloseTest.StubSession ws = new WebProxyAuthCloseTest.StubSession();
        session.onWebSocketConnect(ws);

        session.onWebSocketClose(1001, "close from the lobby with nothing owned");

        Assert.assertEquals("no ownership, no keep",
                Boolean.FALSE, xmage.lastKeepMySessionActive);
    }

    /**
     * A DUELING table's positive verdict does not count: the resolver never flags past the
     * start window, so no verdict — hence no keep — can come from one. Pins that the keep
     * signal really is scoped to in-window tables.
     */
    @Test
    public void duelingOwnershipProducesNoKeepSignal() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        WebProxySession session = newSession(xmage, "alice");
        TableView dueling = matchTable(xmage.roomId, "mine-dueling", TableState.DUELING);
        xmage.ownedTables.add(dueling.getTableId());

        session.resolveOwnedTableIds(xmage, Collections.singletonList(dueling));

        Assert.assertEquals("a started table is never probed", 0, xmage.ownerProbes.size());
        Assert.assertFalse("and never yields a keep signal",
                TableOwnership.hasPositiveVerdicts("alice"));
        Assert.assertNull("nothing may be cached for it",
                TableOwnership.cached("alice", dueling.getTableId(), System.currentTimeMillis()));
    }
}
