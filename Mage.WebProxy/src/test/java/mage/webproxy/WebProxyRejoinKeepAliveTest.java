package mage.webproxy;

import mage.interfaces.MageClient;
import mage.players.PlayerType;
import mage.remote.SessionImpl;
import mage.view.TableView;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

/**
 * F-REJOIN-2 regression: a successful {@code rejoin_game} must record the live game id
 * on the client IMMEDIATELY, not only via the server's later callbacks.
 *
 * Reproduced live 2026-09-27 with two real browsers (see
 * {@code the earlier findings}): reload #1 reconnects and rejoins;
 * reload #2 lands ~1.5 s later, while the server's re-sent GAME_INIT is still held
 * client-side by SessionImpl's connect flow (CONNECT_WAIT_BEFORE_PROCESS_ANY_CALLBACKS).
 * In that window the fresh WebProxyClient's {@code currentGameId} was still null, so
 * {@link WebProxySession#cleanup()}'s keep-alive decision
 * ({@code gameLive = client.getCurrentGameId() != null}) read false and the session was
 * disconnected HARD: the server saw DisconnectedByUser, removed the seat and conceded the
 * match, and every later reconnect found nothing rejoinable — the player was stranded in
 * the lobby (only table_list frames) forever.
 *
 * Contract pinned here:
 *  - before any rejoin, a close with no game id disconnects hard
 *    ({@code connectStop(askForReconnect=false, keepMySessionActive=false)});
 *  - after the joinGame RPC behind a successful rejoin_game succeeds, the id is recorded
 *    on the client BEFORE the success log — so a close in the GAME_INIT window keeps the
 *    session ({@code keepMySessionActive=true});
 *  - a FAILED joinGame must NOT record the id (no keep-alive for a game we are not in).
 *
 * The test drives the REAL {@code handleRejoinGame} on a real WebProxySession whose
 * xmageSession is a SessionImpl subclass standing in for the XMage server, using the same
 * technique as {@link WebProxyAutoStartRetryTest}.
 */
public class WebProxyRejoinKeepAliveTest {

    private WebProxyAuthCloseTest.RecordingAppender appender;
    private WebProxySession currentSession;

    @Before
    public void attachLogs() {
        appender = new WebProxyAuthCloseTest.RecordingAppender();
        appender.setThreshold(Level.TRACE);
        Logger.getRootLogger().addAppender(appender);
    }

    @After
    public void closeSession() {
        if (currentSession != null) {
            try {
                currentSession.onWebSocketClose(1000, "test over");
            } catch (Throwable t) {
                // Teardown must not mask the test's own failure.
            }
        }
        currentSession = null;
        Logger.getRootLogger().removeAppender(appender);
    }

    /**
     * Stand-in for the XMage server on the rejoin path: joinGame either succeeds or
     * reports a server-side failure; getTables answers with a DUELING table the user is
     * seated at (what {@code checkForRejoinableGame} needs to offer the rejoin).
     */
    static class FakeXmageSession extends SessionImpl {
        final UUID roomId = UUID.randomUUID();
        volatile boolean joinGameResult = true;
        int joinGameCalls;
        /** The fixture game — recorded gameId for the rejoin RPC (isGameSeatOfUser match). */
        volatile mage.game.Game game;
        /** connectStop arguments observed — the keep/hard decision under test. */
        volatile Boolean lastAskForReconnect;
        volatile Boolean lastKeepMySessionActive;
        int connectStopCalls;

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
        public boolean isConnected() {
            return true;
        }

        @Override
        public boolean joinGame(UUID gameId) {
            joinGameCalls++;
            return joinGameResult;
        }

        @Override
        public synchronized void connectStop(boolean askForReconnect, boolean keepMySessionActive) {
            connectStopCalls++;
            lastAskForReconnect = askForReconnect;
            lastKeepMySessionActive = keepMySessionActive;
        }

        /**
         * A DUELING table with the tester seated — the shape checkForRejoinableGame
         * requires to send rejoin_game. Built through the real TableView(Table) path so the
         * seats/games lists come from the same accessors the proxy scans.
         *
         * Built ONCE and cached: the rejoin RPC's seat check (isGameSeatOfUser) re-reads
         * getTables and must see the SAME game id the first call advertised — a rebuilt
         * fixture would mint a new UUID per call and the check would (correctly) reject.
         */
        @Override
        public java.util.Collection<TableView> getTables(UUID roomId) {
            if (cachedView == null) {
                cachedView = duelingTableView();
            }
            return Collections.singletonList(cachedView);
        }

        private volatile TableView cachedView;

        private TableView duelingTableView() {
            try {
                mage.game.match.MatchOptions opts = new mage.game.match.MatchOptions("t", "Two Player Duel", false);
                opts.getPlayerTypes().add(PlayerType.HUMAN);
                opts.getPlayerTypes().add(PlayerType.HUMAN);
                mage.game.match.Match match = new mage.game.FakeMatch();
                for (int i = 0; i < 2; i++) {
                    match.addPlayer(new mage.players.StubPlayer("tester", mage.constants.RangeOfInfluence.ALL), null);
                }
                mage.game.Table t = new mage.game.Table(roomId, "Two Player Duel", "t", "tester",
                        (mage.cards.decks.DeckValidator) null, new java.util.ArrayList<>(opts.getPlayerTypes()),
                        null, match, Collections.emptySet(), false);
                // Seat the tester on the real table, then flip to DUELING (mid-game).
                t.joinTable(new mage.players.StubPlayer("tester", mage.constants.RangeOfInfluence.ALL), t.getSeats()[0]);
                t.setState(mage.constants.TableState.DUELING);
                // TableView only reports a game when match.getGames() is non-empty, and
                // checkForRejoinableGame skips tables with no games — so the fixture must
                // carry a real game. FakeGame is XMage's own test double (concrete GameImpl
                // that does nothing); plugins populate MatchImpl.games the same way.
                game = new mage.game.FakeGame();
                match.getGames().add(game);
                return new TableView(t);
            } catch (Exception e) {
                // The scan reads through this like any room read; a fixture failure here
                // is a test bug, surfaced loudly (Fail Fast).
                throw new IllegalStateException("fake dueling table fixture failed", e);
            }
        }
    }

    private static WebProxySession newSession(FakeXmageSession xmage) throws Exception {
        WebProxySession session = new WebProxySession();
        Field xs = WebProxySession.class.getDeclaredField("xmageSession");
        xs.setAccessible(true);
        xs.set(session, xmage);
        Field pc = WebProxySession.class.getDeclaredField("proxyClient");
        pc.setAccessible(true);
        pc.set(session, new WebProxyClient("tester"));
        return session;
    }

    private static WebProxyClient clientOf(WebProxySession session) throws Exception {
        Field pc = WebProxySession.class.getDeclaredField("proxyClient");
        pc.setAccessible(true);
        return (WebProxyClient) pc.get(session);
    }

    private void attachWs(WebProxySession session) {
        WebProxyAuthCloseTest.StubSession ws = new WebProxyAuthCloseTest.StubSession();
        session.onWebSocketConnect(ws);
        this.currentSession = session;
    }

    /** Invoke the real rejoin_game verb with the given gameId. */
    private static void rejoin(WebProxySession session, UUID gameId) throws Exception {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("type", "rejoin_game");
        json.addProperty("gameId", gameId.toString());
        Method m = WebProxySession.class.getDeclaredMethod("handleRejoinGame",
                com.google.gson.JsonObject.class);
        m.setAccessible(true);
        m.invoke(session, json);
    }

    // ── the regression ──

    /**
     * The exact failing sequence: rejoin succeeds → close BEFORE any server callback
     * arrives. Keep-alive MUST hold (gameLive true) because the rejoin just attached us
     * to a live game.
     */
    @Test
    public void closeAfterSuccessfulRejoinKeepsSession() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        WebProxySession session = newSession(xmage);
        attachWs(session);
        WebProxyClient client = clientOf(session);

        UUID gameId = fixtureGameId(xmage);
        rejoin(session, gameId);

        Assert.assertEquals("the rejoin must have called the server's joinGame once", 1, xmage.joinGameCalls);
        Assert.assertEquals("a successful rejoin must record the live game id on the client",
                gameId, client.getCurrentGameId());
        Assert.assertTrue("success must be logged",
                appender.events.stream().anyMatch(e -> e.getRenderedMessage() != null
                        && e.getRenderedMessage().startsWith("Successfully rejoined game ")));

        // The close that used to strand the player: still no callback has set the id.
        session.onWebSocketClose(1001, "reload #2 in the GAME_INIT window");

        Assert.assertEquals("cleanup must have consulted connectStop exactly once", 1, xmage.connectStopCalls);
        Assert.assertEquals("a close after a successful rejoin must KEEP the session "
                + "(old behavior: hard disconnect -> seat dropped)", Boolean.TRUE, xmage.lastKeepMySessionActive);
        Assert.assertEquals("keep-alive must not ask for reconnect", Boolean.FALSE, xmage.lastAskForReconnect);
        Assert.assertTrue("the keep must be logged",
                appender.events.stream().anyMatch(e -> e.getRenderedMessage() != null
                        && e.getRenderedMessage().startsWith("Kept XMage session alive for live game ")));
    }

    /** The game id the fake's DUELING table carries — what a real rejoin would target. */
    private static UUID fixtureGameId(FakeXmageSession xmage) {
        // Trigger the fixture build (getTables builds it lazily) and read the id the
        // TableView exposes, exactly as a real client learns it from table_list.
        mage.view.TableView tv = xmage.getTables(xmage.roomId).iterator().next();
        Assert.assertEquals("fixture must expose exactly one game",
                1, tv.getGames().size());
        return tv.getGames().get(0);
    }

    /**
     * A rejoin whose joinGame RPC fails must NOT record the id: the next close takes the
     * ordinary hard path (we are not attached to any live game).
     */
    @Test
    public void failedRejoinDoesNotKeepSession() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        xmage.joinGameResult = false;
        WebProxySession session = newSession(xmage);
        attachWs(session);
        WebProxyClient client = clientOf(session);

        rejoin(session, fixtureGameId(xmage));

        Assert.assertEquals("the failed rejoin must not record a game id", null, client.getCurrentGameId());

        session.onWebSocketClose(1001, "close after failed rejoin");

        Assert.assertEquals("a close with no live game must not keep the session",
                Boolean.FALSE, xmage.lastKeepMySessionActive);
    }

    /**
     * Control: a close with no rejoin at all (never in a game) still hard-disconnects —
     * the fix must not turn every close into a keep.
     */
    @Test
    public void closeWithoutAnyGameStillDisconnectsHard() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        WebProxySession session = newSession(xmage);
        attachWs(session);

        session.onWebSocketClose(1001, "close from the lobby");

        Assert.assertEquals("lobby close must not keep the session",
                Boolean.FALSE, xmage.lastKeepMySessionActive);
    }

    /**
     * The rejoin scan itself must keep offering the game while the seat is intact (the
     * predicate set the strand is NOT about): DUELING + user seated + non-empty games.
     * Drives checkForRejoinableGame directly — it is the connect flow that calls it, and
     * this test pins the scan's own predicates.
     */
    @Test
    public void rejoinScanFindsDuelingSeatedTable() throws Exception {
        FakeXmageSession xmage = new FakeXmageSession();
        WebProxySession session = newSession(xmage);
        attachWs(session);

        Method m = WebProxySession.class.getDeclaredMethod("checkForRejoinableGame",
                mage.remote.SessionImpl.class, UUID.class, String.class);
        m.setAccessible(true);
        m.invoke(session, xmage, xmage.roomId, "tester");

        Assert.assertTrue("the scan must log the found table (Dueling + seated + has games)",
                appender.events.stream().anyMatch(e -> e.getRenderedMessage() != null
                        && e.getRenderedMessage().startsWith("Found rejoinable game: table=")));
    }
}
