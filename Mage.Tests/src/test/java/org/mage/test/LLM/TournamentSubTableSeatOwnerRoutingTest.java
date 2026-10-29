package org.mage.test.LLM;

import mage.cards.decks.Deck;
import mage.cards.decks.DeckValidator;
import mage.cards.decks.DeckValidatorFactory;
import mage.constants.TableState;
import mage.game.Table;
import mage.game.TwoPlayerDuelType;
import mage.game.TwoPlayerMatch;
import mage.game.match.MatchOptions;
import mage.interfaces.LLMBridgeRegistry;
import mage.interfaces.LLMPromptPayload;
import mage.players.PlayerType;
import mage.server.LLMClientTransport;
import mage.server.TableController;
import mage.server.TableManagerImpl;
import mage.server.User;
import mage.server.game.GameFactory;
import mage.server.managers.ChatManager;
import mage.server.managers.ManagerFactory;
import mage.server.managers.ThreadExecutor;
import mage.server.managers.UserManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the tournament half of client-key LLM routing:
 *
 * <p>The LLM bridge resolves its destination browser as
 * {@code gameId → tableId → TableController.getOwnerUserId() → sessionId}. Tournament
 * sub-tables are created with no creator user, so that chain used to die at a null owner and
 * EVERY client-key seat in a tournament failed with {@code No browser session for LLM seat …}
 * (measured live: 8 seats, 156 errors, every sub-table quit). The fix passes the tournament
 * creator — the browser that configured the seats and holds their keys — into the sub-table as
 * its LLM-SEAT owner, while its owner for permissions/teardown stays absent.
 *
 * <p>Both halves are asserted here against a real {@link TableManagerImpl} + real
 * {@link TableController} and the real {@code LLMClientTransport.ownerUserIdForTable} /
 * {@code sessionIdForUser} resolution: only the game-registry lookup and the final
 * {@code Session.fireCallback} hop are stubbed (a real session needs the whole remoting stack).
 * No game thread, no network, no LLM call: {@code deliver} stops at the recorded callback.
 *
 * <p>The permission half is the reason the owner rides a SEPARATE field instead of
 * {@code TableController}'s {@code userId}: that field feeds {@code isOwner} →
 * {@code isTableOwner} (RPC + the owner-only close-table guard), the "not started yet and user
 * is the owner" teardown branch of {@code leaveTable}, and the table's {@code controllerName}.
 * All of them must keep seeing a sub-table as ownerless.
 */
public class TournamentSubTableSeatOwnerRoutingTest {

    private static final String GAME_TYPE = "Two Player Duel";
    private static final String TEST_DECK_TYPE = "u2-tourney-subtable-deck";
    private static final UUID ROOM_ID = UUID.randomUUID();
    private static final UUID TOURNAMENT_TABLE_ID = UUID.randomUUID();
    private static final UUID OWNER_USER_ID = UUID.randomUUID();
    private static final String OWNER_SESSION_ID = "tournament-creator-session";
    private static final String OWNER_NAME = "tournament-creator";

    private ScheduledExecutorService timeoutExecutor;

    /**
     * {@code TableController} builds its match through {@code GameFactory}, which knows only the
     * types registered at server startup. Register the one this fixture asks for (idempotent).
     */
    @BeforeAll
    static void registerGameType() {
        boolean known = GameFactory.instance.getGameTypes().stream()
                .anyMatch(view -> GAME_TYPE.equals(view.getName()));
        if (!known) {
            GameFactory.instance.addGameType(GAME_TYPE, new TwoPlayerDuelType(), TwoPlayerMatch.class);
        }
        // No deck plugin is on this module's classpath, so register a permissive validator
        // instead of letting TableController log a FATAL and carry a null validator.
        if (!DeckValidatorFactory.instance.getDeckTypes().contains(TEST_DECK_TYPE)) {
            DeckValidatorFactory.instance.addDeckType(TEST_DECK_TYPE, AnyDeckValidator.class);
        }
    }

    /** Permissive stand-in: this test never validates a deck. */
    public static final class AnyDeckValidator extends DeckValidator {

        /** DeckValidatorFactory instantiates deck validators through a public no-arg ctor. */
        public AnyDeckValidator() {
            super("U2 tournament-subtable test deck", "U2 test");
        }

        @Override
        public boolean validate(Deck deck) {
            return true;
        }

        @Override
        public int getDeckMinSize() {
            return 0;
        }

        @Override
        public int getSideboardMinSize() {
            return 0;
        }
    }

    @AfterEach
    void cleanUp() {
        LLMBridgeRegistry.clearTransport();
        if (timeoutExecutor != null) {
            timeoutExecutor.shutdownNow();
            timeoutExecutor = null;
        }
    }

    // ------------------------------------------------------------------
    // The fix: a tournament sub-table routes to the creating browser
    // ------------------------------------------------------------------

    @Test
    @DisplayName("tournament sub-table reports the tournament creator as its LLM-seat owner")
    void subTableReportsTheTournamentCreatorAsOwner() {
        Fixture fixture = newFixture();
        TableController subTable = fixture.newTournamentMatchTable(OWNER_USER_ID);

        assertThat(subTable.getTable().isTournamentSubTable())
                .as("fixture sanity: this is a tournament sub-table")
                .isTrue();
        assertThat(subTable.getOwnerUserId())
                .as("the sub-table must resolve the browser that created the tournament — this was the null "
                        + "that made every client-key seat unroutable")
                .isEqualTo(OWNER_USER_ID);
    }

    @Test
    @DisplayName("live bridge lookup for a sub-table game delivers to the creating browser")
    void subTableGameDeliversToTheCreatingBrowser() {
        Fixture fixture = newFixture();
        TableController subTable = fixture.newTournamentMatchTable(OWNER_USER_ID);
        RecordingTransport transport = new RecordingTransport(fixture.managerFactory, subTable.getTable().getId());

        LLMPromptPayload payload = payload(transport.gameId);
        boolean delivered = transport.deliver(payload);

        assertThat(delivered)
                .as("the in-game lookup gameId → tableId → owner → session must find the creator's browser")
                .isTrue();
        assertThat(transport.fired.get())
                .as("the prompt must reach a browser")
                .isSameAs(payload);
        assertThat(transport.firedSession.get())
                .as("and it must be the creator's own session, not someone else's")
                .isEqualTo(OWNER_SESSION_ID);
    }

    @Test
    @DisplayName("a sub-table without an owner still fails with the honest bridge error")
    void subTableWithoutOwnerFailsHonestly() {
        Fixture fixture = newFixture();
        // Server-side tournament creation: no browser configured the seats, so there is no owner
        // to route to. That must stay a loud, immediate failure — never a silent stall.
        TableController subTable = fixture.newTournamentMatchTable(null);
        RecordingTransport transport = new RecordingTransport(fixture.managerFactory, subTable.getTable().getId());
        LLMBridgeRegistry.setTransport(transport);
        assertThat(subTable.getOwnerUserId()).isNull();

        assertThat(transport.deliver(payload(transport.gameId)))
                .as("an ownerless sub-table is not routable — unchanged behaviour for server-created tournaments")
                .isFalse();
        assertThatThrownBy(() -> LLMBridgeRegistry.request(payload(transport.gameId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No browser session for LLM seat");
        assertThat(transport.fired.get()).isNull();
    }

    // ------------------------------------------------------------------
    // Why the owner rides a separate field: nothing else may move
    // ------------------------------------------------------------------

    @Test
    @DisplayName("routing owner is not the permission owner: no close-table right, no name, no owner teardown")
    void subTableStaysOwnerlessForPermissionsAndDisplay() {
        Fixture fixture = newFixture();
        TableController subTable = fixture.newTournamentMatchTable(OWNER_USER_ID);
        UUID subTableId = subTable.getTable().getId();

        assertThat(subTable.isOwner(OWNER_USER_ID))
                .as("isOwner gates the owner-only RPCs and the not-started teardown branch — must stay false")
                .isFalse();
        assertThat(fixture.tableManager.isTableOwner(subTableId, OWNER_USER_ID))
                .as("the creator must not become 'table owner' on a sub-table")
                .isFalse();
        assertThat(fixture.tableManager.removeTable(OWNER_USER_ID, subTableId))
                .as("the creator must not be able to close a running sub-table through the removeTable RPC")
                .isFalse();
        assertThat(fixture.controllerOf(subTableId).getTable().getState())
                .as("a refused close must leave the table exactly where it was")
                .isEqualTo(TableState.WAITING);
        assertThat(subTable.getTable().getControllerName())
                .as("a sub-table is still presented as System-owned, not as the creator's table")
                .isEqualTo("System");
        assertThat(fixture.userLookups.get())
                .as("constructing a sub-table must not resolve the creator user (no controllerName/owner wiring)")
                .isZero();
    }

    @Test
    @DisplayName("plain tables keep reporting their creator as both routing and permission owner")
    void plainTableStillReportsItsCreator() {
        Fixture fixture = newFixture();
        Table table = fixture.tableManager.createTable(ROOM_ID, OWNER_USER_ID, fixture.options);
        TableController controller = fixture.controllerOf(table.getId());

        assertThat(controller.getOwnerUserId())
                .as("a regular table's route is its creator, as before")
                .isEqualTo(OWNER_USER_ID);
        assertThat(controller.isOwner(OWNER_USER_ID))
                .as("and its creator still owns it for permissions/teardown")
                .isTrue();
        assertThat(table.getControllerName())
                .as("a regular table is still presented under its creator's name")
                .isEqualTo(OWNER_NAME);
    }

    // ------------------------------------------------------------------
    // Fixture: real table manager + real controllers, stubbed server seams
    // ------------------------------------------------------------------

    private Fixture newFixture() {
        timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger userLookups = new AtomicInteger();
        AtomicReference<User> ownerUserRef = new AtomicReference<>();

        Map<String, Function<Object[], Object>> userManagerHandlers = new HashMap<>();
        userManagerHandlers.put("getUser", args -> {
            userLookups.incrementAndGet();
            return Optional.ofNullable(ownerUserRef.get());
        });
        userManagerHandlers.put("isAdmin", args -> false);
        userManagerHandlers.put("getUserByName", args -> Optional.ofNullable(ownerUserRef.get()));
        userManagerHandlers.put("getUsers", args -> java.util.Collections.emptyList());
        UserManager userManager = stub(UserManager.class, userManagerHandlers);

        Map<String, Function<Object[], Object>> chatManagerHandlers = new HashMap<>();
        chatManagerHandlers.put("createTableChatSession", args -> UUID.randomUUID());
        ChatManager chatManager = stub(ChatManager.class, chatManagerHandlers);

        Map<String, Function<Object[], Object>> threadExecutorHandlers = new HashMap<>();
        threadExecutorHandlers.put("getTimeoutExecutor", args -> timeoutExecutor);
        ThreadExecutor threadExecutor = stub(ThreadExecutor.class, threadExecutorHandlers);

        Map<String, Function<Object[], Object>> managerHandlers = new HashMap<>();
        managerHandlers.put("userManager", args -> userManager);
        managerHandlers.put("chatManager", args -> chatManager);
        managerHandlers.put("threadExecutor", args -> threadExecutor);
        // Filled in below: the real table manager is the seam under test (ownerUserIdForTable).
        AtomicReference<TableManagerImpl> tableManagerRef = new AtomicReference<>();
        managerHandlers.put("tableManager", args -> tableManagerRef.get());
        ManagerFactory managerFactory = stub(ManagerFactory.class, managerHandlers);

        // The creating browser's user, keyed in the user manager by the id the server resolves.
        User ownerUser = new User(managerFactory, OWNER_NAME, "test", null);
        ownerUser.setSessionId(OWNER_SESSION_ID);
        ownerUserRef.set(ownerUser);

        MatchOptions options = new MatchOptions("u2-tourney-subtable", GAME_TYPE, false);
        options.setDeckType(TEST_DECK_TYPE);
        options.getPlayerTypes().add(PlayerType.COMPUTER_LLM);
        options.getPlayerTypes().add(PlayerType.COMPUTER_LLM);

        TableManagerImpl tableManager = new TableManagerImpl(managerFactory);
        tableManagerRef.set(tableManager);
        return new Fixture(managerFactory, tableManager, options, userLookups);
    }

    private static LLMPromptPayload payload(UUID gameId) {
        return new LLMPromptPayload(UUID.randomUUID(), UUID.randomUUID(), gameId,
                "deepseek-v4.1-flash-kestrel", "{}", "https://llm.example/v1/chat/completions",
                false, 10, "client-key-tag");
    }

    private static final class Fixture {

        final ManagerFactory managerFactory;
        final TableManagerImpl tableManager;
        final MatchOptions options;
        final AtomicInteger userLookups;

        Fixture(ManagerFactory managerFactory, TableManagerImpl tableManager, MatchOptions options,
                AtomicInteger userLookups) {
            this.managerFactory = managerFactory;
            this.tableManager = tableManager;
            this.options = options;
            this.userLookups = userLookups;
        }

        /** Exactly what {@code TournamentController.startMatch} does, minus the game start. */
        TableController newTournamentMatchTable(UUID llmSeatOwnerUserId) {
            Table table = tableManager.createTournamentMatchTable(ROOM_ID, llmSeatOwnerUserId, options);
            table.setTournamentSubTable(TOURNAMENT_TABLE_ID);
            return controllerOf(table.getId());
        }

        TableController controllerOf(UUID tableId) {
            return tableManager.getController(tableId)
                    .orElseThrow(() -> new IllegalStateException("no controller for table " + tableId));
        }
    }

    /**
     * The real transport: {@code ownerUserIdForTable} goes through the real {@link TableManagerImpl}
     * and the real {@link TableController#getOwnerUserId()}, and {@code sessionIdForUser} through the
     * stubbed user manager. Only the game→table link (a game-registry entry) and the final remoting
     * hop are replaced, because neither exists without a started game.
     */
    private static final class RecordingTransport extends LLMClientTransport {

        final UUID gameId = UUID.randomUUID();
        final UUID tableId;
        final AtomicReference<LLMPromptPayload> fired = new AtomicReference<>();
        final AtomicReference<String> firedSession = new AtomicReference<>();

        RecordingTransport(ManagerFactory managerFactory, UUID tableId) {
            super(managerFactory);
            this.tableId = tableId;
        }

        @Override
        protected UUID tableIdForGame(UUID gameId) {
            return this.gameId.equals(gameId) ? tableId : null;
        }

        @Override
        protected boolean fireToSession(String sessionId, UUID gameId, LLMPromptPayload payload) {
            fired.set(payload);
            firedSession.set(sessionId);
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Minimal interface stubs (no Mockito on this module's classpath)
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
        return null;
    }
}
