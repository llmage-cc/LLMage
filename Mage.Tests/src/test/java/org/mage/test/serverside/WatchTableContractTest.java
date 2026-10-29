package org.mage.test.serverside;

import mage.MageException;
import mage.constants.TableState;
import mage.game.TwoPlayerDuelType;
import mage.game.TwoPlayerMatch;
import mage.game.match.MatchOptions;
import mage.interfaces.MageClient;
import mage.interfaces.MageServer;
import mage.interfaces.callback.ClientCallback;
import mage.interfaces.callback.ClientCallbackMethod;
import mage.players.PlayerType;
import mage.remote.SessionImpl;
import mage.server.TableController;
import mage.server.User;
import mage.server.game.GameFactory;
import mage.server.managers.ManagerFactory;
import mage.server.managers.UserManager;
import mage.utils.MageVersion;
import org.jboss.remoting.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code watchTable} contract at both ends of the seam: a watch is only real for a table
 * that is already {@link TableState#DUELING}, and the client must report the server's answer
 * instead of blindly acking success.
 *
 * <p>Two legs, because the full path (staging watch → match start → GAME_INIT on the watcher)
 * needs a started game session and a full server harness — the {@code MageServerImpl} room,
 * session and game-manager wiring — that is far heavier than the contract itself:
 * <ul>
 *   <li><b>Server leg</b> — {@link TableController#watchTable} (the method
 *       {@code MageServerImpl.roomWatchTable} delegates to, and the only place the
 *       DUELING gate lives). {@code stagingTableWatchIsRefusedAndRegistersNoWatcher} and
 *       {@code duelingTableWatchIsAcceptedAndRegistersWatcher} drive a real
 *       {@code TableController} over a minimal two-seat {@code MatchOptions}; the watcher is a
 *       {@link User} whose {@code fireCallback} records what the controller sends, so
 *       "registered" is observed as the real {@code WATCHGAME} callback carrying the game id.</li>
 *   <li><b>Client leg</b> — {@link SessionImpl#watchTable} (the method every client calls).
 *       The four {@code session*} tests install a stub {@link MageServer} whose
 *       {@code roomWatchTable} answers exactly as {@code TableController} does, and assert the
 *       client returns that boolean (pre-fix it returned {@code true} for the staging case),
 *       keeps the {@code isConnected} guard, and still answers {@code false} on a
 *       {@link MageException}.</li>
 * </ul>
 *
 * <p><b>Not covered here:</b> {@code GAME_INIT}/{@code GAME_UPDATE} delivery to the watcher
 * after the match starts (that is the game-session half — {@code WATCHGAME} → {@code watchGame}
 * → game callbacks — and needs a started game), and {@code MageServerImpl.roomWatchTable}'s own
 * session/room lookup. The engine fix this test guards changes only the boolean propagation, so
 * the legs above are the contract's boundary.
 */
public class WatchTableContractTest {

    private static final String GAME_TYPE = "Two Player Duel";
    private static final String TEST_SESSION_ID = "watch-contract-session";
    private static final UUID ROOM_ID = UUID.randomUUID();
    private static final UUID TABLE_ID = UUID.randomUUID();

    private ScheduledExecutorService timeoutExecutor;

    /**
     * {@code TableController} builds its match through {@code GameFactory}, which knows only the
     * types registered at server startup. Register the one this fixture asks for (idempotent, so
     * another test in the same JVM that already did it is fine).
     */
    @BeforeAll
    static void registerGameType() {
        boolean known = GameFactory.instance.getGameTypes().stream()
                .anyMatch(view -> GAME_TYPE.equals(view.getName()));
        if (!known) {
            GameFactory.instance.addGameType(GAME_TYPE, new TwoPlayerDuelType(), TwoPlayerMatch.class);
        }
    }

    @AfterEach
    void stopTimeoutExecutor() {
        if (timeoutExecutor != null) {
            timeoutExecutor.shutdownNow();
            timeoutExecutor = null;
        }
    }

    // ------------------------------------------------------------------
    // Server leg: TableController.watchTable's boolean + registration
    // ------------------------------------------------------------------

    @Test
    @DisplayName("staging table: watch is refused and registers no watcher")
    void stagingTableWatchIsRefusedAndRegistersNoWatcher() {
        Fixture fixture = newFixture();

        assertThat(fixture.controller.getTable().getState())
                .as("fixture must start in the pre-duel (staging) state")
                .isEqualTo(TableState.WAITING);

        boolean watched = fixture.controller.watchTable(fixture.watcher.getId());

        assertThat(watched)
                .as("a staging table must not be acked as watched — no watcher is registered for it")
                .isFalse();
        assertThat(fixture.watcher.callbacks)
                .as("a refused watch must not send WATCHGAME")
                .isEmpty();
        assertThat(fixture.userLookups.get())
                .as("the DUELING gate must short-circuit before any user/registration work")
                .isZero();
        assertThat(fixture.controller.getTable().getState())
                .as("a refused watch must not change the table state")
                .isEqualTo(TableState.WAITING);
    }

    @Test
    @DisplayName("dueling table: watch is accepted and WATCHGAME carries the game id")
    void duelingTableWatchIsAcceptedAndRegistersWatcher() throws Exception {
        Fixture fixture = newFixture();

        // Same order the server's own start path uses (TableController.startGame): create the
        // game on the match, then flip the table to DUELING. No game thread is needed here —
        // watchTable only needs the game id the watcher would be pointed at.
        fixture.controller.getMatch().startGame();
        fixture.controller.getTable().initGame();
        assertThat(fixture.controller.getTable().getState()).isEqualTo(TableState.DUELING);

        boolean watched = fixture.controller.watchTable(fixture.watcher.getId());

        assertThat(watched).as("a DUELING table must be acked as watched").isTrue();
        assertThat(fixture.userLookups.get()).isEqualTo(1);
        assertThat(fixture.watcher.callbacks).hasSize(1);
        ClientCallback callback = fixture.watcher.callbacks.get(0);
        assertThat(callback.getMethod())
                .as("the watcher is registered by being sent WATCHGAME")
                .isEqualTo(ClientCallbackMethod.WATCHGAME);
        assertThat(callback.getObjectId())
                .as("WATCHGAME must point the watcher at the current game")
                .isEqualTo(fixture.controller.getMatch().getGame().getId());
    }

    /** A real two-seat {@link TableController} plus the watcher whose callbacks we observe. */
    private Fixture newFixture() {
        timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger userLookups = new AtomicInteger();
        AtomicReference<RecordingUser> watcherRef = new AtomicReference<>();

        Map<String, Function<Object[], Object>> userManagerHandlers = new java.util.HashMap<>();
        userManagerHandlers.put("getUser", args -> {
            userLookups.incrementAndGet();
            return Optional.ofNullable(watcherRef.get());
        });
        userManagerHandlers.put("getUserByName", args -> Optional.ofNullable(watcherRef.get()));
        userManagerHandlers.put("getUsers", args -> java.util.Collections.emptyList());
        UserManager userManager = stub(UserManager.class, userManagerHandlers);

        Map<String, Function<Object[], Object>> chatManagerHandlers = new java.util.HashMap<>();
        chatManagerHandlers.put("createTableChatSession", chatArgs -> UUID.randomUUID());

        Map<String, Function<Object[], Object>> threadExecutorHandlers = new java.util.HashMap<>();
        threadExecutorHandlers.put("getTimeoutExecutor", execArgs -> timeoutExecutor);

        Map<String, Function<Object[], Object>> managerHandlers = new java.util.HashMap<>();
        managerHandlers.put("userManager", args -> userManager);
        managerHandlers.put("chatManager",
                args -> stub(mage.server.managers.ChatManager.class, chatManagerHandlers));
        managerHandlers.put("threadExecutor",
                args -> stub(mage.server.managers.ThreadExecutor.class, threadExecutorHandlers));
        ManagerFactory managerFactory = stub(ManagerFactory.class, managerHandlers);

        RecordingUser watcher = new RecordingUser(managerFactory);
        watcherRef.set(watcher);

        MatchOptions options = new MatchOptions("watch-contract", GAME_TYPE, false);
        options.getPlayerTypes().add(PlayerType.HUMAN);
        options.getPlayerTypes().add(PlayerType.HUMAN);

        TableController controller = new TableController(managerFactory, ROOM_ID, null, options);
        return new Fixture(controller, watcher, userLookups);
    }

    private static final class Fixture {

        final TableController controller;
        final RecordingUser watcher;
        final AtomicInteger userLookups;

        Fixture(TableController controller, RecordingUser watcher, AtomicInteger userLookups) {
            this.controller = controller;
            this.watcher = watcher;
            this.userLookups = userLookups;
        }
    }

    /**
     * Records what the controller sends instead of handing it to a remoting session. The real
     * {@link User#ccWatchGame} body still runs, so the recorded callback is the genuine
     * WATCHGAME message (method + game id) the server would deliver.
     */
    private static final class RecordingUser extends User {

        final List<ClientCallback> callbacks = new ArrayList<>();

        RecordingUser(ManagerFactory managerFactory) {
            super(managerFactory, "watch-contract-spectator", "test", null);
        }

        @Override
        public void fireCallback(ClientCallback call) {
            callbacks.add(call);
        }
    }

    // ------------------------------------------------------------------
    // Client leg: SessionImpl.watchTable propagates the server's answer
    // ------------------------------------------------------------------

    @Test
    @DisplayName("session: staging-table rejection is propagated (false, not a blind true)")
    void sessionReturnsFalseWhenServerRefusesAStagingTable() throws Exception {
        ServerStub server = new ServerStub(false);
        SessionImpl session = sessionWith(server.asServer(), new FakeRemotingClient(true));

        boolean watched = session.watchTable(ROOM_ID, TABLE_ID);

        assertThat(watched)
                .as("the client must report the server's refusal — a blind true is the false ack this pins")
                .isFalse();
        assertThat(server.watchCalls).hasSize(1);
        assertThat(server.watchCalls.get(0))
                .containsExactly(TEST_SESSION_ID, ROOM_ID, TABLE_ID);
    }

    @Test
    @DisplayName("session: DUELING-table acceptance is propagated (true)")
    void sessionReturnsTrueWhenServerAcceptsADuelingTable() throws Exception {
        ServerStub server = new ServerStub(true);
        SessionImpl session = sessionWith(server.asServer(), new FakeRemotingClient(true));

        assertThat(session.watchTable(ROOM_ID, TABLE_ID)).isTrue();
        assertThat(server.watchCalls).hasSize(1);
    }

    @Test
    @DisplayName("session: a MageException still answers false (error path preserved)")
    void sessionReturnsFalseWhenServerThrowsMageException() throws Exception {
        ServerStub server = new ServerStub(null); // null = throw
        SessionImpl session = sessionWith(server.asServer(), new FakeRemotingClient(true));

        assertThat(session.watchTable(ROOM_ID, TABLE_ID)).isFalse();
        assertThat(server.watchCalls).hasSize(1);
    }

    @Test
    @DisplayName("session: disconnected client answers false without calling the server")
    void sessionReturnsFalseWithoutServerCallWhenDisconnected() throws Exception {
        ServerStub server = new ServerStub(true);
        SessionImpl session = sessionWith(server.asServer(), new FakeRemotingClient(false));

        assertThat(session.watchTable(ROOM_ID, TABLE_ID)).isFalse();
        assertThat(server.watchCalls)
                .as("the isConnected guard must stay in front of the RPC")
                .isEmpty();
    }

    private static SessionImpl sessionWith(MageServer server, Client callbackClient) throws Exception {
        SessionImpl session = new SessionImpl(new NoopClient());
        setField(session, "server", server);
        setField(session, "callbackClient", callbackClient);
        setField(session, "sessionId", TEST_SESSION_ID);
        return session;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** A {@link MageServer} that answers {@code roomWatchTable} like {@code TableController} does. */
    private static final class ServerStub implements InvocationHandler {

        /** {@code true}/{@code false} = the server's answer; {@code null} = throw a MageException. */
        private final Boolean watchResult;
        final List<List<Object>> watchCalls = new ArrayList<>();

        ServerStub(Boolean watchResult) {
            this.watchResult = watchResult;
        }

        /** The stub as the {@link MageServer} the session talks to. */
        MageServer asServer() {
            return (MageServer) Proxy.newProxyInstance(MageServer.class.getClassLoader(),
                    new Class<?>[]{MageServer.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "roomWatchTable":
                    watchCalls.add(args == null ? java.util.Collections.emptyList() : Arrays.asList(args));
                    if (watchResult == null) {
                        throw new MageException("table is not DUELING");
                    }
                    return watchResult;
                case "toString":
                    return "MageServerStub";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        }
    }

    /**
     * {@code Client.isConnected()} is the only thing {@link SessionImpl#isConnected()} asks the
     * remoting client; overriding it lets the propagation tests exercise the connected path
     * without a socket.
     */
    private static final class FakeRemotingClient extends Client {

        private final boolean connected;

        FakeRemotingClient(boolean connected) {
            this.connected = connected;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }
    }

    private static final class NoopClient implements MageClient {

        @Override
        public void onCallback(ClientCallback callback) {
        }

        @Override
        public void onNewConnection() {
        }

        @Override
        public MageVersion getVersion() {
            return new MageVersion(MageVersion.MAGE_VERSION_MAJOR, MageVersion.MAGE_VERSION_MINOR,
                    MageVersion.MAGE_VERSION_RELEASE, MageVersion.MAGE_VERSION_RELEASE_INFO,
                    NoopClient.class);
        }

        @Override
        public void connected(String message) {
        }

        @Override
        public void disconnected(boolean askToReconnect, boolean keepMySessionActive) {
        }

        @Override
        public void showMessage(String message) {
        }

        @Override
        public void showError(String message) {
        }
    }

    // ------------------------------------------------------------------
    // Minimal stubs: only the methods TableController's construction and
    // watchTable path actually call are wired; everything else is a neutral
    // default so an unexpected call fails loudly (NPE/ignored) rather than
    // silently changing the fixture.
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, Map<String, Function<Object[], Object>> handlers) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "toString":
                            return type.getSimpleName() + "Stub";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            break;
                    }
                    Function<Object[], Object> handler = handlers.get(method.getName());
                    if (handler != null) {
                        return handler.apply(args);
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == char.class) {
            return (char) 0;
        }
        if (returnType == float.class) {
            return 0f;
        }
        if (returnType == double.class) {
            return 0d;
        }
        if (returnType == Optional.class) {
            return Optional.empty();
        }
        if (returnType == List.class || returnType == Collection.class) {
            return java.util.Collections.emptyList();
        }
        if (returnType == Set.class) {
            return java.util.Collections.emptySet();
        }
        if (returnType == Map.class) {
            return java.util.Collections.emptyMap();
        }
        return null;
    }
}
