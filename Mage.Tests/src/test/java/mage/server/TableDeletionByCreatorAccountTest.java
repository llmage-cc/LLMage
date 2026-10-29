package mage.server;

import mage.cards.decks.Deck;
import mage.cards.decks.DeckValidator;
import mage.cards.decks.DeckValidatorFactory;
import mage.constants.TableState;
import mage.game.Table;
import mage.game.TwoPlayerDuelType;
import mage.game.TwoPlayerMatch;
import mage.game.match.MatchOptions;
import mage.players.PlayerType;
import mage.server.game.GameFactory;
import mage.server.managers.ChatManager;
import mage.server.managers.ConfigSettings;
import mage.server.managers.GamesRoomManager;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the deletion rule: a lobby table must be deletable by the same ACCOUNT that created it,
 * even after a reconnect minted a NEW session user id.
 *
 * <p>Two layers are asserted:
 * <ul>
 *   <li>the pure rule {@link TableManagerImpl#canRemoveTable(UUID, String, UUID, String)} - the
 *       exact predicate the deletion path applies - for the null / blank / no-creator edges that
 *       a real {@code TableController} cannot be built with;</li>
 *   <li>the real {@code removeTable(userId, tableId)} path over a real {@link TableManagerImpl}
 *       and real {@link TableController} (stubbed {@code ManagerFactory}: no remoting, no game
 *       thread), because that is where the account lookup happens.</li>
 * </ul>
 *
 * <p>The widened rule must stay OUT of {@link TableManagerImpl#isTableOwner}: that predicate also
 * gates {@code swapSeats} and the client-visible {@code tableIsOwner} RPC (Start / Move-seats),
 * so the tests below assert it still answers false for the reconnected creator - deletion and
 * nothing else.
 *
 * <p>What this class does NOT cover: the {@code tableRemove} RPC hop in {@code MageServerImpl}
 * (needs the remoting stack) and the web client's can-remove hint (another repo). Both only carry
 * the session's user id into {@code removeTable}, which is what is exercised here.
 */
public class TableDeletionByCreatorAccountTest {

    private static final String GAME_TYPE = "Two Player Duel";
    private static final String TEST_DECK_TYPE = "u2-orphan-table-deck";
    private static final UUID ROOM_ID = UUID.randomUUID();
    private static final String CREATOR_NAME = "crashed-create-table-client";
    private static final String OTHER_NAME = "somebody-else";

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
            super("U2 orphan-table test deck", "U2 test");
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
        if (timeoutExecutor != null) {
            timeoutExecutor.shutdownNow();
            timeoutExecutor = null;
        }
    }

    // ------------------------------------------------------------------
    // The defect: same account, new session id -> deletion allowed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the creator account can delete its table after a reconnect minted a new user id")
    void reconnectedCreatorAccountCanDeleteItsTable() {
        Fixture fixture = newFixture();
        Account creator = fixture.newAccount(CREATOR_NAME);
        Account reconnected = fixture.newAccount(CREATOR_NAME); // same account, new session user id
        UUID tableId = fixture.newTable(creator.id);

        assertThat(fixture.tableManager.isTableOwner(tableId, reconnected.id))
                .as("the UUID rule is unchanged: a new session id is NOT the owner")
                .isFalse();

        assertThat(fixture.tableManager.removeTable(reconnected.id, tableId))
                .as("the same ACCOUNT must be able to clear its own stranded table")
                .isTrue();
        assertThat(fixture.tableManager.getController(tableId))
                .as("an accepted delete must actually remove the controller")
                .isEmpty();
        assertThat(fixture.tableManager.getTable(tableId))
                .as("an accepted delete must actually remove the table from the lobby")
                .isNull();
    }

    @Test
    @DisplayName("the widest scope gained is deletion: seat ownership stays false")
    void deletionOnlyNoSeatOrStartAuthority() {
        Fixture fixture = newFixture();
        Account creator = fixture.newAccount(CREATOR_NAME);
        Account reconnected = fixture.newAccount(CREATOR_NAME);
        UUID tableId = fixture.newTable(creator.id);

        // swapSeats() and the client-visible tableIsOwner RPC (Start / Move-seats controls) both
        // gate on isTableOwner. It must keep answering false for the reconnected account.
        assertThat(fixture.tableManager.isTableOwner(tableId, reconnected.id)).isFalse();
        fixture.tableManager.swapSeats(tableId, reconnected.id, 0, 1); // must be a no-op, no throw
        assertThat(fixture.controllerOf(tableId).getTable().getState())
                .as("an ownership check must not change the table's state")
                .isEqualTo(TableState.WAITING);
    }

    @Test
    @DisplayName("the creating session still deletes by UUID without any account lookup")
    void creatorSessionStillDeletesByUuid() {
        Fixture fixture = newFixture();
        Account creator = fixture.newAccount(CREATOR_NAME);
        UUID tableId = fixture.newTable(creator.id);
        int lookupsBefore = fixture.userLookups.get();

        assertThat(fixture.tableManager.removeTable(creator.id, tableId))
                .as("the original UUID rule must not regress")
                .isTrue();
        assertThat(fixture.userLookups.get())
                .as("the UUID leg must not pay for a user lookup")
                .isEqualTo(lookupsBefore);
    }

    // ------------------------------------------------------------------
    // Everything that must still be refused
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a different account name cannot delete the table")
    void differentAccountNameIsRefused() {
        Fixture fixture = newFixture();
        Account creator = fixture.newAccount(CREATOR_NAME);
        Account intruder = fixture.newAccount(OTHER_NAME);
        UUID tableId = fixture.newTable(creator.id);

        assertThat(fixture.tableManager.removeTable(intruder.id, tableId))
                .as("only the account that created the table may delete it")
                .isFalse();
        assertThat(fixture.tableManager.getTable(tableId))
                .as("a refused delete must leave the table in the lobby")
                .isNotNull();
        assertThat(fixture.controllerOf(tableId).getTable().getState())
                .as("a refused delete must leave the table untouched")
                .isEqualTo(TableState.WAITING);
    }

    @Test
    @DisplayName("a null session user id is refused")
    void nullUserIdIsRefused() {
        Fixture fixture = newFixture();
        Account creator = fixture.newAccount(CREATOR_NAME);
        UUID tableId = fixture.newTable(creator.id);
        int lookupsBefore = fixture.userLookups.get();

        assertThat(fixture.tableManager.removeTable(null, tableId))
                .as("no session user id -> no deletion")
                .isFalse();
        assertThat(fixture.tableManager.getTable(tableId)).isNotNull();
        assertThat(fixture.userLookups.get())
                .as("a null requester must not reach the user manager")
                .isEqualTo(lookupsBefore);
    }

    @Test
    @DisplayName("an unknown user id is refused, never matched by an empty name")
    void unknownUserIdIsRefused() {
        Fixture fixture = newFixture();
        Account creator = fixture.newAccount(CREATOR_NAME);
        UUID tableId = fixture.newTable(creator.id);

        assertThat(fixture.tableManager.removeTable(UUID.randomUUID(), tableId))
                .as("a user id the user manager does not know has no name to match")
                .isFalse();
        assertThat(fixture.tableManager.getTable(tableId)).isNotNull();
    }

    @Test
    @DisplayName("a lookup error fails the request loudly instead of quietly denying it")
    void lookupErrorIsNotSwallowed() {
        Fixture fixture = newFixture();
        Account creator = fixture.newAccount(CREATOR_NAME);
        Account reconnected = fixture.newAccount(CREATOR_NAME);
        UUID tableId = fixture.newTable(creator.id);
        fixture.throwingUserIds.add(reconnected.id);

        assertThatThrownBy(() -> fixture.tableManager.removeTable(reconnected.id, tableId))
                .as("a broken user lookup must not be turned into a silent 'not the owner'")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("user store unavailable");
        assertThat(fixture.tableManager.getTable(tableId))
                .as("a failed request must leave the table where it was")
                .isNotNull();
    }

    @Test
    @DisplayName("the admin branch is untouched: an admin still deletes somebody else's table")
    void adminBranchStillWorks() {
        Fixture fixture = newFixture();
        Account creator = fixture.newAccount(CREATOR_NAME);
        Account admin = fixture.newAccount("the-admin");
        UUID tableId = fixture.newTable(creator.id);
        fixture.requesterIsAdmin.set(true);

        assertThat(fixture.tableManager.removeTable(admin.id, tableId))
                .as("removeTable's admin branch must keep working")
                .isTrue();
        assertThat(fixture.tableManager.getTable(tableId)).isNull();
    }

    @Test
    @DisplayName("an account literally named 'System' cannot delete a creatorless tournament sub-table")
    void systemNameCannotDeleteCreatorlessSubTable() {
        Fixture fixture = newFixture();
        Account systemNamed = fixture.newAccount("System");
        Table subTable = fixture.tableManager.createTournamentMatchTable(
                ROOM_ID, UUID.randomUUID(), fixture.options);
        subTable.setTournamentSubTable(UUID.randomUUID());
        UUID subTableId = subTable.getId();

        assertThat(subTable.getControllerName())
                .as("fixture sanity: a creatorless sub-table is displayed as System-owned")
                .isEqualTo("System");
        int lookupsBefore = fixture.userLookups.get();
        assertThat(fixture.tableManager.removeTable(systemNamed.id, subTableId))
                .as("a display name is not an account: the name leg needs a real creator id")
                .isFalse();
        assertThat(fixture.tableManager.getTable(subTableId)).isNotNull();
        assertThat(fixture.userLookups.get())
                .as("a creatorless table can never match by name, so the refused delete must not "
                        + "even resolve the requester (the sub-table teardown invariant)")
                .isEqualTo(lookupsBefore);
    }

    // ------------------------------------------------------------------
    // The pure rule: the edges a real TableController cannot be built with
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the pure rule: same name, different user id matches; exact name only")
    void pureRuleMatchesTheSameAccountName() {
        UUID creatorId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();

        assertThat(TableManagerImpl.canRemoveTable(creatorId, "alice", otherId, "alice"))
                .as("same account name after a reconnect")
                .isTrue();
        assertThat(TableManagerImpl.canRemoveTable(creatorId, "alice", otherId, "ALICE"))
                .as("account names are unique case-sensitively; a case-insensitive compare would "
                        + "let a second, case-differing account delete another's table")
                .isFalse();
        assertThat(TableManagerImpl.canRemoveTable(creatorId, "alice", otherId, "alice2")).isFalse();
        assertThat(TableManagerImpl.canRemoveTable(creatorId, "alice", otherId, "alic")).isFalse();
    }

    @Test
    @DisplayName("the pure rule: a null requester id never matches, not even its own table")
    void pureRuleNullRequesterNeverMatches() {
        UUID creatorId = UUID.randomUUID();

        assertThat(TableManagerImpl.canRemoveTable(creatorId, "alice", null, "alice")).isFalse();
        assertThat(TableManagerImpl.canRemoveTable(creatorId, "alice", null, null)).isFalse();
        assertThat(TableManagerImpl.canRemoveTable(null, "System", null, "System")).isFalse();
    }

    @Test
    @DisplayName("the pure rule: a null or blank creator name never matches")
    void pureRuleNullOrBlankCreatorNameNeverMatches() {
        UUID creatorId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();

        assertThat(TableManagerImpl.canRemoveTable(creatorId, null, otherId, null)).isFalse();
        assertThat(TableManagerImpl.canRemoveTable(creatorId, null, otherId, "alice")).isFalse();
        assertThat(TableManagerImpl.canRemoveTable(creatorId, "", otherId, "")).isFalse();
        assertThat(TableManagerImpl.canRemoveTable(creatorId, "   ", otherId, "   ")).isFalse();
        assertThat(TableManagerImpl.canRemoveTable(creatorId, "\t", otherId, "\t")).isFalse();
    }

    @Test
    @DisplayName("the pure rule: a null or blank requesting name never matches")
    void pureRuleNullOrBlankRequesterNameNeverMatches() {
        UUID creatorId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();

        assertThat(TableManagerImpl.canRemoveTable(creatorId, "alice", otherId, null)).isFalse();
        assertThat(TableManagerImpl.canRemoveTable(creatorId, "alice", otherId, "")).isFalse();
        assertThat(TableManagerImpl.canRemoveTable(creatorId, "alice", otherId, "  ")).isFalse();
    }

    @Test
    @DisplayName("the pure rule: a creatorless table has no account to match by name")
    void pureRuleCreatorlessTableNeverMatchesByName() {
        UUID otherId = UUID.randomUUID();

        assertThat(TableManagerImpl.canRemoveTable(null, "System", otherId, "System"))
                .as("tournament sub-tables are display-named System and must stay ownerless")
                .isFalse();
    }

    @Test
    @DisplayName("the pure rule: the UUID match wins even when both names are missing")
    void pureRuleUuidMatchWinsWithoutNames() {
        UUID creatorId = UUID.randomUUID();

        assertThat(TableManagerImpl.canRemoveTable(creatorId, null, creatorId, null))
                .as("the original rule compares user ids only; names must not be needed for it")
                .isTrue();
        assertThat(TableManagerImpl.canRemoveTable(creatorId, "", creatorId, ""))
                .as("the original rule is unchanged for a blank controller name")
                .isTrue();
    }

    // ------------------------------------------------------------------
    // Fixture: real table manager + real controllers, stubbed server seams
    // ------------------------------------------------------------------

    private Fixture newFixture() {
        timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger userLookups = new AtomicInteger();
        Map<UUID, User> users = new HashMap<>();
        java.util.Set<UUID> throwingUserIds = new java.util.HashSet<>();
        AtomicBoolean requesterIsAdmin = new AtomicBoolean();

        Map<String, Function<Object[], Object>> userManagerHandlers = new HashMap<>();
        userManagerHandlers.put("getUser", args -> {
            userLookups.incrementAndGet();
            UUID id = (UUID) args[0];
            if (id != null && throwingUserIds.contains(id)) {
                throw new IllegalStateException("user store unavailable");
            }
            return Optional.ofNullable(id == null ? null : users.get(id));
        });
        userManagerHandlers.put("isAdmin", args -> requesterIsAdmin.get() && args[0] != null);
        userManagerHandlers.put("getUsers", args -> java.util.Collections.emptyList());
        UserManager userManager = stub(UserManager.class, userManagerHandlers);

        Map<String, Function<Object[], Object>> chatManagerHandlers = new HashMap<>();
        chatManagerHandlers.put("createTableChatSession", args -> UUID.randomUUID());
        ChatManager chatManager = stub(ChatManager.class, chatManagerHandlers);

        Map<String, Function<Object[], Object>> threadExecutorHandlers = new HashMap<>();
        threadExecutorHandlers.put("getTimeoutExecutor", args -> timeoutExecutor);
        ThreadExecutor threadExecutor = stub(ThreadExecutor.class, threadExecutorHandlers);

        Map<String, Function<Object[], Object>> configHandlers = new HashMap<>();
        configHandlers.put("isSaveGameActivated", args -> false);
        ConfigSettings configSettings = stub(ConfigSettings.class, configHandlers);

        // removeTable's teardown calls gamesRoomManager().removeTable(...); a null manager would
        // NPE before the table leaves the map, hiding the assertion under test.
        GamesRoomManager gamesRoomManager = stub(GamesRoomManager.class, new HashMap<>());

        Map<String, Function<Object[], Object>> managerHandlers = new HashMap<>();
        managerHandlers.put("userManager", args -> userManager);
        managerHandlers.put("chatManager", args -> chatManager);
        managerHandlers.put("threadExecutor", args -> threadExecutor);
        managerHandlers.put("configSettings", args -> configSettings);
        managerHandlers.put("gamesRoomManager", args -> gamesRoomManager);
        ManagerFactory managerFactory = stub(ManagerFactory.class, managerHandlers);

        MatchOptions options = new MatchOptions("orphan-table", GAME_TYPE, false);
        options.setDeckType(TEST_DECK_TYPE);
        options.getPlayerTypes().add(PlayerType.HUMAN);
        options.getPlayerTypes().add(PlayerType.HUMAN);

        TableManagerImpl tableManager = new TableManagerImpl(managerFactory);
        return new Fixture(managerFactory, tableManager, options, users, throwingUserIds,
                requesterIsAdmin, userLookups);
    }

    /** A server-side account: one User instance per login, so one user id per session. */
    private static final class Account {

        final UUID id;
        final String name;

        Account(User user) {
            this.id = user.getId();
            this.name = user.getName();
        }
    }

    private static final class Fixture {

        final ManagerFactory managerFactory;
        final TableManagerImpl tableManager;
        final MatchOptions options;
        final Map<UUID, User> users;
        final java.util.Set<UUID> throwingUserIds;
        final AtomicBoolean requesterIsAdmin;
        final AtomicInteger userLookups;

        Fixture(ManagerFactory managerFactory, TableManagerImpl tableManager, MatchOptions options,
                Map<UUID, User> users, java.util.Set<UUID> throwingUserIds,
                AtomicBoolean requesterIsAdmin, AtomicInteger userLookups) {
            this.managerFactory = managerFactory;
            this.tableManager = tableManager;
            this.options = options;
            this.users = users;
            this.throwingUserIds = throwingUserIds;
            this.requesterIsAdmin = requesterIsAdmin;
            this.userLookups = userLookups;
        }

        /** Registers a real {@link User} under its own id - the account the server would see. */
        Account newAccount(String name) {
            User user = new User(managerFactory, name, "test", null);
            users.put(user.getId(), user);
            return new Account(user);
        }

        /** Exactly what the create-table RPC does: a plain match table created by this user. */
        UUID newTable(UUID creatorUserId) {
            Table table = tableManager.createTable(ROOM_ID, creatorUserId, options);
            return table.getId();
        }

        TableController controllerOf(UUID tableId) {
            return tableManager.getController(tableId)
                    .orElseThrow(() -> new IllegalStateException("no controller for table " + tableId));
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
