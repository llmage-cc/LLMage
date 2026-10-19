package mage.server;

import mage.MageException;
import mage.cards.decks.Deck;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.DeckValidatorError;
import mage.cards.decks.DeckValidatorFactory;
import mage.constants.RangeOfInfluence;
import mage.constants.TableState;
import mage.game.*;
import mage.game.draft.Draft;
import mage.game.draft.DraftPlayer;
import mage.game.events.Listener;
import mage.game.events.TableEvent;
import mage.game.match.Match;
import mage.game.match.MatchOptions;
import mage.game.match.MatchPlayer;
import mage.game.tournament.Tournament;
import mage.game.tournament.TournamentOptions;
import mage.game.tournament.TournamentPlayer;
import mage.players.Player;
import mage.players.PlayerType;
import mage.players.LLMPlayerConfig;
import mage.server.LLMPlayerHelper;
import mage.server.game.GameFactory;
import mage.server.game.PlayerFactory;
import mage.server.managers.ManagerFactory;
import mage.server.record.TableRecorderImpl;
import mage.server.tournament.TournamentFactory;
import mage.server.util.ServerMessagesUtil;
import mage.util.ThreadUtils;
import mage.view.ChatMessage;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * @author BetaSteward_at_googlemail.com, JayDi85
 */
public class TableController {

    private static final Logger logger = Logger.getLogger(TableController.class);

    /**
     * Sideboard timeout for AI-only matches (LLM vs LLM, bot games).
     * LLM sideboarding can take 5+ minutes per player at max reasoning effort.
     * 30 minutes gives plenty of room for 2 LLM players to sideboard sequentially.
     */
    private static final int AI_SIDEBOARD_TIME = 30 * 60; // 30 minutes

    /**
     * Max consecutive drawn games before an all-AI match is force-closed by
     * {@link #endGameAndStartNextGame()} instead of starting the next game.
     *
     * Why a consecutive-draw cap and not a wall-clock or games-played cap:
     * - A draw is the only result that awards no win, so a match where every
     *   game ends in an instant draw (degenerate tiny decks that deck out
     *   immediately) can NEVER satisfy winsNeeded and restarts games forever.
     *   Measured live: one cycle every ~90 s, each firing real LLM API calls,
     *   continuing even after the botgame runner process died.
     *   At ~90 s per cycle, capping at 5 draws bounds unattended burn to
     *   roughly 8 minutes instead of indefinitely.
     * - Legitimate all-AI matches essentially never produce 5 straight draws,
     *   so this cap stays invisible in normal play; a wall-clock alternative
     *   would kill legitimate long LLM matches (LLM games legitimately run
     *   many minutes per game).
     * - Human matches are excluded ENTIRELY by the allAI gate (same predicate
     *   as sideboard()): 5 draws in a row is vanishingly rare but possible
     *   for real players, and their table must never be force-closed.
     */
    private static final int MAX_CONSECUTIVE_DRAWS = 5;

    /**
     * How long {@link #submitDeck(UUID, DeckCardLists)} will HOLD an early
     * between-games submit while the table is still transitioning into
     * SIDEBOARDING (see the long note at the state gate). The hold runs OUTSIDE
     * this controller's monitor — the game thread takes that monitor
     * ({@code setupTimeout}/{@code cancelTimeout}, both synchronized) in the
     * middle of the very transition we are waiting for, so holding it would
     * deadlock until the deadline. 5 s is generous headroom for a slow
     * game-save on the just-ended game; past it the submit is refused with a
     * real reason rather than hanging the RPC caller indefinitely.
     */
    private static final long SIDEBOARD_SUBMIT_HOLD_MS = 5_000;

    /** Poll interval of the early-submit hold. */
    private static final long SIDEBOARD_SUBMIT_POLL_MS = 20;

    /**
     * Players whose SIDEBOARD prompt has been DELIVERED in the current
     * sideboarding phase (filled by the {@link TableEvent.EventType#SIDEBOARD}
     * listener as {@code ccSideboard} goes out, cleared when a phase starts in
     * {@link #sideboard()}).
     *
     * <p>Two uses:
     * <ul>
     *   <li>the early-submit hold waits for the player's OWN prompt, not merely
     *       the table state flip — {@code table.sideboard()} flips the state at
     *       the top of {@link #sideboard()}, while the per-player prompt (and
     *       the {@code MatchPlayer.setSideboarding()} that clears the
     *       done-flag) only fires later inside {@code MatchImpl.sideboard()};
     *       applying a submit in between would be clobbered by that clear and
     *       the seat would hang until the auto-sideboard timer;</li>
     *   <li>it distinguishes a genuinely-late submit (prompt already delivered
     *       earlier in this phase, table has moved on) from a legitimately
     *       early one (prompt not yet delivered), so late submits keep failing
     *       fast instead of being held for the full deadline.</li>
     * </ul>
     */
    private final Set<UUID> sideboardPromptedPlayers = ConcurrentHashMap.newKeySet();

    private final ManagerFactory managerFactory;
    private final UUID userId; // table owner/creator (null in tourney's table)
    private final UUID chatId;
    private final String controllerName;
    private final Table table;
    private final ConcurrentHashMap<UUID, UUID> userPlayerMap = new ConcurrentHashMap<>(); // human players only, use table.seats for AI

    private Match match;
    private MatchOptions options;
    private Tournament tournament;

    private ScheduledFuture<?> futureTimeout;
    protected final ScheduledExecutorService timeoutExecutor;

    /**
     * Consecutive-draw streak in this table's match, incremented by
     * {@link #endGameAndStartNextGame()} whenever a just-ended game was a draw.
     * Reset to 0 on any non-draw game. Only ever compared against
     * {@link #MAX_CONSECUTIVE_DRAWS} for all-AI matches. Volatile because
     * endGame() callbacks arrive on game listener threads.
     */
    private volatile int consecutiveDraws = 0;

    public TableController(ManagerFactory managerFactory, UUID roomId, UUID userId, MatchOptions options) {
        this.managerFactory = managerFactory;
        this.timeoutExecutor = managerFactory.threadExecutor().getTimeoutExecutor();
        this.userId = userId;
        this.options = options;
        this.match = GameFactory.instance.createMatch(options.getGameType(), options);
        if (userId != null) {
            Optional<User> user = managerFactory.userManager().getUser(userId);
            // TODO: Handle if user == null
            this.controllerName = user.map(User::getName).orElse("undefined");
        } else {
            this.controllerName = "System";
        }
        this.table = new Table(roomId, options.getGameType(), options.getName(), controllerName, DeckValidatorFactory.instance.createDeckValidator(options.getDeckType()),
                options.getPlayerTypes(), new TableRecorderImpl(managerFactory.userManager()), match, options.getBannedUsers(), options.isPlaneChase());
        this.chatId = managerFactory.chatManager().createTableChatSession(table);

        // Wire per-seat LLM config overrides from MatchOptions into Seat objects
        wireLlmConfigs();

        init();
    }

    public TableController(ManagerFactory managerFactory, UUID roomId, UUID userId, TournamentOptions options) {
        this.managerFactory = managerFactory;
        this.timeoutExecutor = managerFactory.threadExecutor().getTimeoutExecutor();
        this.userId = userId;
        this.options = options.getMatchOptions();
        tournament = TournamentFactory.instance.createTournament(options.getTournamentType(), options);
        if (userId != null) {
            Optional<User> user = managerFactory.userManager().getUser(userId);
            if (!user.isPresent()) {
                logger.fatal(new StringBuilder("User for userId ").append(userId).append(" could not be retrieved from UserManagerImpl").toString());
                controllerName = "[unknown]";
            } else {
                controllerName = user.get().getName();
            }
        } else {
            controllerName = "System";
        }
        table = new Table(roomId, options.getTournamentType(), options.getName(), controllerName, DeckValidatorFactory.instance.createDeckValidator(options.getMatchOptions().getDeckType()),
                options.getPlayerTypes(), new TableRecorderImpl(managerFactory.userManager()), tournament, options.getMatchOptions().getBannedUsers(), options.isPlaneChase());
        chatId = managerFactory.chatManager().createTableChatSession(table);

        // Wire per-seat LLM config overrides from TournamentOptions into Seat objects
        wireLlmConfigs();
    }

    private void init() {
        match.addTableEventListener(
                (Listener<TableEvent>) event -> {
                    try {
                        switch (event.getEventType()) {
                            case SIDEBOARD:
                                sideboard(event.getPlayerId(), event.getDeck());
                                break;
                        }
                    } catch (MageException ex) {
                        logger.fatal("Table event listener error", ex);
                    }
                }
        );
    }

    public synchronized boolean joinTournament(UUID userId, String name, PlayerType playerType, int skill, DeckCardLists deckList, String password) throws GameException {
        try {
            return doJoinTournament(userId, name, playerType, skill, deckList, password);
        } catch (GameException ge) {
            throw ge;
        } catch (Throwable t) {
            logger.error("joinTournament unexpected error for player " + name + " type=" + playerType + ": " + t.getClass().getName() + " " + t.getMessage(), t);
            throw new GameException("Unexpected error joining tournament: " + t.getMessage());
        }
    }

    private synchronized boolean doJoinTournament(UUID userId, String name, PlayerType playerType, int skill, DeckCardLists deckList, String password) throws GameException {
        if (table.getState() != TableState.WAITING) {
            logger.debug("joinTournament rejected: table state is " + table.getState() + " (expected WAITING) for player " + name);
            return false;
        }

        Seat seat = table.getNextAvailableSeat(playerType);
        if (seat == null) {
            logger.debug("joinTournament rejected: no available seat for type " + playerType + " player " + name);
            throw new GameException("No available seats.");
        }
        Optional<User> _user = managerFactory.userManager().getUser(userId);
        if (!_user.isPresent()) {
            logger.fatal("couldn't get user " + name + " for join tournament userId = " + userId);
            return false;
        }
        User user = _user.get();
        // check password
        if (!table.getTournament().getOptions().getPassword().isEmpty() && playerType == PlayerType.HUMAN) {
            if (!table.getTournament().getOptions().getPassword().equals(password)) {
                logger.error("joinTournament rejected: wrong password tableId " + table.getId() + " for player " + name + " userId " + userId);
                user.showUserMessage("Join Table", "Wrong password.");
                return false;
            }
        }
        if (userPlayerMap.containsKey(userId) && playerType == PlayerType.HUMAN) {
            logger.error("joinTournament rejected: user already joined tableId " + table.getId() + " for player " + name + " userId " + userId);
            user.showUserMessage("Join Table", "You can join a table only one time.");
            return false;
        }
        Deck deck = null;
        if (!table.getTournament().getTournamentType().isLimited()) {
            if (deckList != null) {
                deck = Deck.load(deckList, false, false);
            } else {
                logger.debug("joinTournament rejected: null deckList for player " + name);
                user.showUserMessage("Join Table", "No valid deck selected!");
                return false;
            }
            // deck - P1 pool restriction (authoritative; independent of format legality)
            String p1Reason = P1DeckRestriction.rejectReason(seat, deck);
            if (p1Reason != null) {
                logger.error(P1DeckRestriction.logLine("joinTournament", name, table.getId(), deck));
                user.showUserMessage("Join Table", p1Reason);
                if (isOwner(userId)) {
                    logger.debug("New table removed because owner submitted a non-training deck for a P1 seat tableId " + table.getId());
                    managerFactory.tableManager().removeTable(table.getId());
                }
                return false;
            }
            if (!Main.isTestMode() && !table.getValidator().validate(deck)) {
                logger.debug("joinTournament rejected: deck validation failed for player " + name + " format=" + table.getValidator().getName());
                StringBuilder sb = new StringBuilder("You (").append(name).append(") have an invalid deck for the selected ").append(table.getValidator().getName()).append(" Format. \n\n");
                List<DeckValidatorError> errorsList = table.getValidator().getErrorsListSorted();
                errorsList.stream().forEach(error -> {
                    sb.append(error.getGroup()).append(": ").append(error.getMessage()).append("\n");
                });
                sb.append("\n\nSelect a deck that is appropriate for the selected format and try again!");
                user.showUserMessage("Join Table", sb.toString());
                if (isOwner(userId)) {
                    logger.debug("New table removed because owner submitted invalid deck tableId " + table.getId());
                    managerFactory.tableManager().removeTable(table.getId());
                }
                return false;
            }
        }
        // Check quit ratio.
        int quitRatio = table.getTournament().getOptions().getQuitRatio();
        if (quitRatio < user.getTourneyQuitRatio()) {
            logger.debug("joinTournament rejected: quit ratio " + user.getTourneyQuitRatio() + "% > required " + quitRatio + "% for player " + name);
            String message = new StringBuilder("Your quit ratio ").append(user.getTourneyQuitRatio())
                    .append("% is higher than the table requirement ").append(quitRatio).append('%').toString();
            user.showUserMessage("Join Table", message);
            return false;
        }

        // Check minimum rating.
        int minimumRating = table.getTournament().getOptions().getMinimumRating();
        int userRating;
        if (table.getTournament().getOptions().getMatchOptions().isLimited()) {
            userRating = user.getUserData().getLimitedRating();
        } else {
            userRating = user.getUserData().getConstructedRating();
        }
        if (userRating < minimumRating) {
            logger.debug("joinTournament rejected: rating " + userRating + " < required " + minimumRating + " for player " + name);
            String message = new StringBuilder("Your rating ").append(userRating)
                    .append(" is lower than the table requirement ").append(minimumRating).toString();
            user.showUserMessage("Join Table", message);
            return false;
        }

        Optional<Player> playerOptional = createPlayer(name, seat.getPlayerType(), skill);
        if (!playerOptional.isPresent()) {
            logger.error("joinTournament failed: createPlayer returned empty for type=" + seat.getPlayerType() + " name=" + name);
            throw new GameException("Playertype " + seat.getPlayerType() + " could not be created.");
        }
        Player player = playerOptional.get();
        if (!player.canJoinTable(table)) {
            logger.debug("joinTournament rejected: canJoinTable returned false for player " + name + " type=" + seat.getPlayerType());
            user.showUserMessage("Join Table", new StringBuilder("A ").append(seat.getPlayerType()).append(" player can't join this table.").toString());
            return false;
        }
        tournament.addPlayer(player, seat.getPlayerType());
        TournamentPlayer tournamentPlayer = tournament.getPlayer(player.getId());
        if (deck != null && tournamentPlayer != null) {
            tournamentPlayer.submitDeck(deck);
        }

        // Apply per-seat LLM config overrides from the GUI
        if (seat.getLlmPlayerConfig() != null) {
            if (tournamentPlayer != null) {
                tournamentPlayer.setLlmPlayerConfig(seat.getLlmPlayerConfig());
            }
            if (LLMPlayerHelper.isLLMPlayer(player)) {
                LLMPlayerHelper.applyOverrides(player, seat.getLlmPlayerConfig());
                logger.info("Applied GUI LLM config overrides for tournament player " + name);
            }
        }

        table.joinTable(player, seat);
        logger.debug("Player " + player.getName() + " id: " + player.getId() + " joined tableId: " + table.getId());
        //only inform human players and add them to sessionPlayerMap
        if (seat.getPlayer().isHuman()) {
            seat.getPlayer().setUserData(user.getUserData());
            user.addTable(player.getId(), table);
            user.ccJoinedTable(table.getRoomId(), table.getId(), table.getParentTableId(), true);
            userPlayerMap.put(userId, player.getId());
        }

        return true;
    }

    public boolean hasPlayer(UUID userId) {
        return userPlayerMap.containsKey(userId);
    }

    public synchronized boolean replaceDraftPlayer(Player oldPlayer, String name, PlayerType playerType, int skill) {
        Optional<Player> newPlayerOpt = createPlayer(name, playerType, skill);
        if (!newPlayerOpt.isPresent()) {
            logger.error("Can't replace draft player " + oldPlayer.getName() + " in table " + table.getId()
                    + ": can't create player of type " + playerType);
            return false;
        }
        if (table.getState() != TableState.DRAFTING) {
            logger.warn("Can't replace draft player " + oldPlayer.getName() + " in table " + table.getId()
                    + ": table state " + table.getState());
            return false;
        }
        Player newPlayer = newPlayerOpt.get();
        TournamentPlayer oldTournamentPlayer = tournament.getPlayer(oldPlayer.getId());
        tournament.removePlayer(oldPlayer.getId());
        tournament.addPlayer(newPlayer, playerType);

        TournamentPlayer newTournamentPlayer = tournament.getPlayer(newPlayer.getId());
        newTournamentPlayer.setState(oldTournamentPlayer.getState());
        newTournamentPlayer.setReplacedTournamentPlayer(oldTournamentPlayer);

        boolean draftReplaced = managerFactory.draftManager().getController(table.getId())
                .map(controller -> controller.replacePlayer(oldPlayer, newPlayer))
                .orElse(false);
        if (!draftReplaced) {
            logger.error("Draft player " + oldPlayer.getName() + " replaced in tournament but not in draft, table " + table.getId());
        }
        return true;
    }

    /**
     * Check and join real player
     */
    public synchronized boolean joinTable(UUID userId, String name, PlayerType playerType, int skill, DeckCardLists deckList, String password) throws MageException {
        // user - must exist
        Optional<User> _user = managerFactory.userManager().getUser(userId);
        if (!_user.isPresent()) {
            logger.error("Join Table: can't find user to join " + name + " Id = " + userId);
            return false;
        }

        // table - already joined
        User user = _user.get();
        if (userPlayerMap.containsKey(userId) && playerType == PlayerType.HUMAN) {
            logger.error("joinTable rejected: user already joined tableId " + table.getId() + " for player " + name + " userId " + userId);
            user.showUserMessage("Join Table", new StringBuilder("You can join a table only one time.").toString());
            return false;
        }

        // table - already started
        if (table.getState() != TableState.WAITING) {
            logger.error("joinTable rejected: table " + table.getId() + " is in state " + table.getState() + " (expected WAITING) for player " + name + " userId " + userId);
            user.showUserMessage("Join Table", "No available seats.");
            return false;
        }

        // table - wrong password
        if (!table.getMatch().getOptions().getPassword().isEmpty() && playerType == PlayerType.HUMAN) {
            if (!table.getMatch().getOptions().getPassword().equals(password)) {
                logger.error("joinTable rejected: wrong password tableId " + table.getId() + " for player " + name + " userId " + userId);
                user.showUserMessage("Join Table", "Wrong password.");
                return false;
            }
        }

        // table - no more free seats
        Seat seat = table.getNextAvailableSeat(playerType);
        if (seat == null) {
            logger.error("joinTable rejected: no available seat for type " + playerType + " tableId " + table.getId() + " for player " + name + " userId " + userId);
            user.showUserMessage("Join Table", "No available seats.");
            return false;
        }

        // deck - try to load real deck (any unknown cards will raise exception error here)
        // P.S. Quick start button can raise error here on memory problems, but it's ok
        // (real table creating is unaffected and will not create empty table)
        Deck deck = Deck.load(deckList, false, false);

        // deck - P1 pool restriction (authoritative; independent of format legality)
        String p1Reason = P1DeckRestriction.rejectReason(seat, deck);
        if (p1Reason != null) {
            logger.error(P1DeckRestriction.logLine("joinTable", name, table.getId(), deck));
            user.showUserMessage("Join Table", p1Reason);
            if (isOwner(userId)) {
                logger.debug("New table removed because owner submitted a non-training deck for a P1 seat tableId " + table.getId());
                managerFactory.tableManager().removeTable(table.getId());
            }
            return false;
        }

        // deck - validate
        if (!Main.isTestMode() && !table.getValidator().validate(deck)) {
            StringBuilder sb = new StringBuilder("You (").append(name).append(") have an invalid deck for the selected ").append(table.getValidator().getName()).append(" Format. \n\n");
            List<DeckValidatorError> errorsList = table.getValidator().getErrorsListSorted();
            errorsList.stream().forEach(error -> {
                sb.append(error.getGroup()).append(": ").append(error.getMessage()).append("\n");
            });
            sb.append("\n\nSelect a deck that is appropriate for the selected format and try again!");
            user.showUserMessage("Join Table", sb.toString());
            logger.error("joinTable rejected: invalid deck format=" + table.getValidator().getName() + " tableId " + table.getId() + " for player " + name + " userId " + userId);

            // owner must create table with valid deck only
            if (isOwner(userId)) {
                logger.debug("New table removed because owner submitted invalid deck tableId " + table.getId());
                managerFactory.tableManager().removeTable(table.getId());
            }
            return false;
        }

        // user - restrict by quit ratio
        int quitRatio = table.getMatch().getOptions().getQuitRatio();
        if (quitRatio < user.getMatchQuitRatio()) {
            logger.error("joinTable rejected: quit ratio " + user.getMatchQuitRatio() + "% > required " + quitRatio + "% tableId " + table.getId() + " for player " + name + " userId " + userId);
            String message = new StringBuilder("Your quit ratio ").append(user.getMatchQuitRatio())
                    .append("% is higher than the table requirement ").append(quitRatio).append('%').toString();
            user.showUserMessage("Join Table", message);
            return false;
        }

        // user - restrict by rating
        int minimumRating = table.getMatch().getOptions().getMinimumRating();
        int userRating;
        if (table.getMatch().getOptions().isLimited()) {
            userRating = user.getUserData().getLimitedRating();
        } else {
            userRating = user.getUserData().getConstructedRating();
        }
        if (userRating < minimumRating) {
            logger.error("joinTable rejected: rating " + userRating + " < required " + minimumRating + " tableId " + table.getId() + " for player " + name + " userId " + userId);
            String message = new StringBuilder("Your rating ").append(userRating)
                    .append(" is lower than the table requirement ").append(minimumRating).toString();
            user.showUserMessage("Join Table", message);
            return false;
        }

        // user - restrict by deck power level and cards colors (see edh power level for details)
        int edhPowerLevel = table.getMatch().getOptions().getEdhPowerLevel();
        if (edhPowerLevel > 0 && table.getValidator().getName().toLowerCase(Locale.ENGLISH).equals("commander")) {
            int deckEdhPowerLevel = table.getValidator().getEdhPowerLevel(deck, new ArrayList<>(), new ArrayList<>());
            if (deckEdhPowerLevel % 100 > edhPowerLevel) {
                logger.error("joinTable rejected: deck power level " + (deckEdhPowerLevel % 100) + " > allowed " + edhPowerLevel + " tableId " + table.getId() + " for player " + name + " userId " + userId);
                String message = new StringBuilder("Your deck appears to be too powerful for this table.\n\nReduce the number of extra turn cards, infect, counters, fogs, reconsider your commander. ")
                        .append("\nThe table requirement has a maximum power level of ").append(edhPowerLevel).append(" whilst your deck has a calculated power level of ")
                        .append(deckEdhPowerLevel % 100).toString();
                user.showUserMessage("Join Table", message);
                return false;
            }

            boolean restrictedColor = false;
            String badColor = "";
            int colorVal = edhPowerLevel % 10;
            if (colorVal == 6 && deckEdhPowerLevel >= 10000000) {
                restrictedColor = true;
                badColor = "white";
            }
            if (colorVal == 4 && deckEdhPowerLevel % 10000000 >= 1000000) {
                restrictedColor = true;
                badColor = "blue";
            }
            if (colorVal == 3 && deckEdhPowerLevel % 1000000 >= 100000) {
                restrictedColor = true;
                badColor = "black";
            }
            if (colorVal == 2 && deckEdhPowerLevel % 100000 >= 10000) {
                restrictedColor = true;
                badColor = "red";
            }
            if (colorVal == 1 && deckEdhPowerLevel % 10000 >= 1000) {
                restrictedColor = true;
                badColor = "green";
            }
            if (restrictedColor) {
                logger.error("joinTable rejected: deck contains restricted color " + badColor + " tableId " + table.getId() + " for player " + name + " userId " + userId);
                String message = new StringBuilder("Your deck contains ")
                        .append(badColor)
                        .append(".  The creator of the table has requested no ")
                        .append(badColor)
                        .append(" cards to be on the table!").toString();
                user.showUserMessage("Join Table", message);
                return false;
            }
        }

        // player - try to create (human, ai, etc). The seat's LLMPlayerConfig (if any)
        // rides into construction via LLMPlayerConfig.setPendingPlayerConfig so
        // LLMPlayer.init() applies it BEFORE context-window detection / thinking probe
        // — those dial the SEAT's endpoint, not the server default (a dead default
        // endpoint used to fail the whole creation here).
        Optional<Player> playerOpt = createPlayer(name, seat.getPlayerType(), skill,
                seat.getLlmPlayerConfig());
        if (!playerOpt.isPresent()) {
            String message = "Could not create player " + name + " of type " + seat.getPlayerType();
            logger.warn("User: " + user.getName() + " => " + message);
            user.showUserMessage("Join Table", message);
            return false;
        }

        // player - restrict by player type, e.g. draft bot can join to tourney only
        Player player = playerOpt.get();
        if (!player.canJoinTable(table)) {
            logger.error("joinTable rejected: canJoinTable returned false for type " + seat.getPlayerType() + " tableId " + table.getId() + " for player " + name + " userId " + userId);
            user.showUserMessage("Join Table", "A " + seat.getPlayerType() + " player can't join this table.");
            return false;
        }

        // all fine, player can be added

        // Apply per-seat LLM config overrides from the GUI if the seat has overrides
        if (seat.getLlmPlayerConfig() != null && LLMPlayerHelper.isLLMPlayer(player)) {
            LLMPlayerHelper.applyOverrides(player, seat.getLlmPlayerConfig());
            logger.info("Applied GUI LLM config overrides for player " + name);
        }

        match.addPlayer(player, deck);
        table.joinTable(player, seat);

        //only inform human players and add them to sessionPlayerMap
        if (seat.getPlayer().isHuman()) {
            seat.getPlayer().setUserData(user.getUserData());
            if (!table.isTournamentSubTable()) {
                user.addTable(player.getId(), table);
            }
            user.ccJoinedTable(table.getRoomId(), table.getId(), table.getParentTableId(), false);
            userPlayerMap.put(userId, player.getId());
        }

        return true;
    }

    public void addPlayer(UUID userId, Player player, PlayerType playerType, Deck deck) throws GameException {
        if (table.getState() != TableState.WAITING) {
            logger.error("addPlayer rejected: table " + table.getId() + " is in state " + table.getState() + " (expected WAITING) for player " + player.getName() + " userId " + userId);
            return;
        }
        Seat seat = table.getNextAvailableSeat(playerType);
        if (seat == null) {
            throw new GameException("No available seats.");
        }
        match.addPlayer(player, deck);
        table.joinTable(player, seat);

        // Apply per-seat LLM config overrides for tournament sub-tables too
        if (seat.getLlmPlayerConfig() != null && LLMPlayerHelper.isLLMPlayer(player)) {
            LLMPlayerHelper.applyOverrides(player, seat.getLlmPlayerConfig());
        }

        if (player.isHuman()) {
            userPlayerMap.put(userId, player.getId());
        }
    }

    /**
     * Submit deck on sideboarding/construction (final deck).
     *
     * <p>NOT synchronized on purpose: an early between-games submit (the player
     * answered the sideboard prompt before the server finished the game-end
     * transition) must be able to WAIT for the transition, and the game thread
     * takes this controller's monitor in the middle of that transition
     * ({@code sideboard()} -> {@code setupTimeout(..)}/{@code cancelTimeout()},
     * both synchronized). Holding the monitor while waiting would deadlock
     * until the deadline; the actual state check + apply is delegated to
     * {@link #submitDeckSynchronized} so the critical section is unchanged.
     */
    public boolean submitDeck(UUID userId, DeckCardLists deckList) throws MageException {
        // Early-submit gate (see the note at the state check): when the phase
        // has not been entered for this player yet but the game-end transition
        // is provably in flight, the prompt has either not been delivered or
        // the state has not flipped — wait for BOTH before touching state.
        // The normal path (phase already entered) skips the gate entirely and
        // keeps its exact pre-fix behavior; only genuinely early submits wait.
        if (!isSideboardPhaseEntered(userId) && isEarlySideboardSubmit(userId)) {
            long deadline = System.currentTimeMillis() + SIDEBOARD_SUBMIT_HOLD_MS;
            logger.info("submitDeck: early submit during the game-end transition — holding for player "
                    + userPlayerMap.get(userId) + " (tableId " + table.getId() + ", state "
                    + table.getState() + ")");
            while (System.currentTimeMillis() < deadline) {
                if (isSideboardPhaseEntered(userId)) {
                    break;
                }
                // The transition can also RESOLVE to "no sideboarding" — the
                // just-ended game decided the match, so endGameAndStartNextGame()
                // closes the table (state FINISHED) instead of sideboarding.
                // Nothing is coming: stop waiting and refuse now. (Only the
                // table state is consulted here — Match.hasEnded() mutates match
                // bookkeeping and must not be called off the game thread.)
                if (table.getState() == TableState.FINISHED) {
                    break;
                }
                try {
                    Thread.sleep(SIDEBOARD_SUBMIT_POLL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!isSideboardPhaseEntered(userId)) {
                String reason = buildSubmitRefusalReason();
                logger.warn("submitDeck rejected after hold: " + reason
                        + " (tableId " + table.getId() + ")");
                notifySubmitRefused(userId, reason);
                return false;
            }
            logger.info("submitDeck: transition complete — applying the held submit for player "
                    + userPlayerMap.get(userId) + " (tableId " + table.getId() + ")");
        }
        return submitDeckSynchronized(userId, deckList);
    }

    private synchronized boolean submitDeckSynchronized(UUID userId, DeckCardLists deckList) throws MageException {
        UUID playerId = userPlayerMap.get(userId);

        // player - update tourney's player status
        // TODO: need research, why it here instead real time check?
        if (table.isTournamentSubTable()) {
            TournamentPlayer tournamentPlayer = table.getTournament().getPlayer(playerId);
            if (tournamentPlayer != null) {
                tournamentPlayer.setStateInfo(""); // reset sideboarding state
            }
        }

        // player - already quit
        if (table.isTournament()) {
            TournamentPlayer player = tournament.getPlayer(playerId);
            if (player == null || player.hasQuit()) {
                return true; // so the construct panel closes after submit
            }
        } else if (table.getMatch() != null) {
            MatchPlayer mPlayer = table.getMatch().getPlayer(playerId);
            if (mPlayer == null || mPlayer.hasQuit()) {
                return true; // so the construct panel closes after submit
            }
        }

        // tourney - too late for submit
        //
        // The legal
        // states are SIDEBOARDING and CONSTRUCTING, but a submit can legitimately
        // arrive while the table is still transitioning into sideboarding. The
        // window is real and client-visible: GameController.endGame() sends the
        // game-over frame to the clients BEFORE TableManager.endGame() ->
        // endGameAndStartNextGame() runs the post-game bookkeeping (a second
        // game save + match.endGame()) and only then flips the table state via
        // sideboard(). Measured live: a submit landing in that gap is rejected
        // here with state "Dueling" even though the client has already been told
        // the game is over. Such a submit is HELD before this method runs (see
        // submitDeck); by the time we get here the phase is entered. Every other
        // state keeps the original refusal, now with a real reason.
        if (table.getState() != TableState.SIDEBOARDING
                && table.getState() != TableState.CONSTRUCTING) {
            String reason = buildSubmitRefusalReason();
            logger.debug("submitDeck rejected: " + reason + " (tableId " + table.getId() + ")");
            notifySubmitRefused(userId, reason);
            return false;
        }

        // deck - try to load real deck (any unknown cards will raise exception error here)
        Deck deck = Deck.load(deckList, false, false);

        // workaround to keep deck name for Tiny Leaders because it must be hidden for players
        if (table.getState() == TableState.SIDEBOARDING && table.getMatch() != null) {
            MatchPlayer mPlayer = table.getMatch().getPlayer(playerId);
            if (mPlayer != null) {
                deck.setName(mPlayer.getDeck().getName());
            }
        }

        // deck - P1 pool restriction (authoritative; a P1 seat must not swap in
        // an off-pool list between games any more than at join time).
        Seat p1Seat = seatOfPlayer(playerId);
        String p1Reason = P1DeckRestriction.rejectReason(p1Seat, deck);
        if (p1Reason != null) {
            logger.error(P1DeckRestriction.logLine("submitDeck(sideboard)", playerId.toString(), table.getId(), deck));
            notifySubmitRefused(userId, p1Reason);
            return false;
        }

        // deck - validate
        if (!Main.isTestMode() && !table.getValidator().validate(deck)) {
            Optional<User> _user = managerFactory.userManager().getUser(userId);
            if (!_user.isPresent()) {
                logger.warn("submitDeck rejected: deck invalid and user " + userId + " no longer present (tableId " + table.getId() + ")");
                return false;
            }
            StringBuilder sb = new StringBuilder("Invalid deck for the selected ").append(table.getValidator().getName()).append(" format. \n\n");
            List<DeckValidatorError> errorsList = table.getValidator().getErrorsListSorted();
            errorsList.stream().forEach(error -> {
                sb.append(error.getGroup()).append(": ").append(error.getMessage()).append("\n");
            });
            sb.append("\n\nAdd enough cards and try again!");
            _user.get().showUserMessage("Submit deck", sb.toString());
            return false;
        }

        submitDeck(userId, playerId, deck);
        return true;
    }

    public void updateDeck(UUID userId, DeckCardLists deckList) throws MageException {
        UUID playerId = userPlayerMap.get(userId);
        // TODO: need refactor, many duplicated code like state check
        if (table.getState() != TableState.SIDEBOARDING
                && table.getState() != TableState.CONSTRUCTING) {
            return;
        }
        Deck deck = Deck.load(deckList, false, false);
        updateDeck(userId, playerId, deck);
    }

    /**
     * True when this submit is a LEGITIMATE early between-games submit: the
     * player is seated at a match (non-tournament) table whose current game has
     * ENDED with the match still alive and sideboarding allowed, but the table
     * has not yet been flipped to SIDEBOARDING by
     * {@link #endGameAndStartNextGame()}.
     *
     * <p>That predicate is exactly the client-visible situation: the game-over
     * frame has been delivered to the seats and the next phase is on its way.
     * Anything else (a table that is mid-game, a finished table, a table whose
     * match is over) is NOT an early submit and must keep the original
     * fail-fast refusal. See the state-check note in
     * {@link #submitDeckSynchronized}.
     */
    private boolean isEarlySideboardSubmit(UUID userId) {
        if (table.isTournament() || table.getMatch() == null) {
            return false;
        }
        UUID playerId = userPlayerMap.get(userId);
        if (playerId == null) {
            return false;
        }
        MatchPlayer mPlayer = table.getMatch().getPlayer(playerId);
        if (mPlayer == null || mPlayer.hasQuit()) {
            return false;
        }
        Game game = table.getMatch().getGame();
        if (game == null || !game.hasEnded()) {
            return false;                   // mid-game: nothing is early about it
        }
        if (table.getMatch().getGame().getGameType() == null
                || !table.getMatch().getGame().getGameType().isSideboardingAllowed()) {
            return false;                   // single-game match: no sideboard phase
        }
        return true;
    }

    /**
     * True once the table has actually entered the phase this player is waiting
     * for: the state is SIDEBOARDING (match) or CONSTRUCTING (limited build)
     * AND — for the sideboarding case — this player's prompt has been
     * delivered, so the {@code setSideboarding()} done-flag clear has already
     * run {@code MatchImpl.sideboard()} and is no longer able to clobber the
     * submit. The state alone is not enough: it flips at the TOP of
     * {@link #sideboard()}, several steps before the per-player prompt.
     */
    private boolean isSideboardPhaseEntered(UUID userId) {
        TableState state = table.getState();
        if (state == TableState.CONSTRUCTING) {
            return true;
        }
        if (state != TableState.SIDEBOARDING) {
            return false;
        }
        UUID playerId = userPlayerMap.get(userId);
        return playerId == null || sideboardPromptedPlayers.contains(playerId);
    }

    /**
     * Human-readable reason for a refused (or not-yet-enterable) submit, built
     * from the CURRENT table/match state so it is actionable for the player:
     * the client shows this text verbatim — the old refusal path
     * returned a bare false and the proxy therefore rendered an EMPTY reason,
     * "Failed to submit deck: ").
     */
    private String buildSubmitRefusalReason() {
        TableState state = table.getState();
        if (state == TableState.DUELING || state == TableState.STARTING) {
            // Distinguish the two flavours of "Dueling" for the player:
            //   * the game is still being played — nothing to submit yet;
            //   * the game has ended and the server is mid-transition into the
            //     between-games phase (the window this issue is about). The
            //     latter is normally HELD, not refused, so reaching here means
            //     the hold timed out or the submit arrived while an OLD game
            //     was still live.
            Game game = table.getMatch() != null ? table.getMatch().getGame() : null;
            if (game != null && !game.hasEnded()) {
                return "The current game is still in progress — a deck can only be submitted between games, while sideboarding.";
            }
            return "The server is still setting up the next sideboard phase after the game that just ended — try again in a moment.";
        }
        if (state == TableState.FINISHED) {
            return "This table is finished — its deck can no longer be changed.";
        }
        if (state == TableState.WAITING || state == TableState.READY_TO_START) {
            return "This match has not started yet — there is nothing to sideboard for.";
        }
        if (state == TableState.DRAFTING) {
            return "This table is drafting — submit the deck after the draft finishes.";
        }
        return "The table is in state " + state + ", which does not accept deck submissions right now.";
    }

    /**
     * Surface a refused submit over the existing user-message channel so the
     * refusal carries a REAL reason end-to-end (server -> SHOW_USERMESSAGE
     * callback -> proxy -> browser). The boolean return value alone cannot
     * carry text, and the proxy's fallback ({@code getLastError()}) is only
     * populated for connect failures — that is why the pre-fix refusal rendered
     * an empty toast. Non-fatal: a user that vanished mid-call is simply gone.
     */
    private void notifySubmitRefused(UUID userId, String reason) {
        managerFactory.userManager().getUser(userId).ifPresent(user ->
                user.showUserMessage("Submit deck", reason));
    }

    private void submitDeck(UUID userId, UUID playerId, Deck deck) {
        if (table.getState() == TableState.SIDEBOARDING) {
            match.submitDeck(playerId, deck);
            managerFactory.userManager().getUser(userId).ifPresent(user -> user.removeSideboarding(table.getId()));
        } else {
            managerFactory.tournamentManager().submitDeck(tournament.getId(), playerId, deck);
            managerFactory.userManager().getUser(userId).ifPresent(user -> user.removeConstructing(playerId));
        }
    }

    private void updateDeck(UUID userId, UUID playerId, Deck deck) {
        // strict mode - players can't add any lands while sideboarding in single game
        // ignore mode - players can add any lands while construction/sideboarding in draft tourney
        boolean ignoreMainBasicLands = table.isTournament() || table.isTournamentSubTable();
        if (table.isTournament()) {
            if (tournament != null) {
                // TODO: is it possible to update from direct call command in game?!
                managerFactory.tournamentManager().updateDeck(tournament.getId(), playerId, deck, ignoreMainBasicLands);
            } else {
                logger.fatal("Tournament == null  table: " + table.getId() + " userId: " + userId);
            }
        } else if (table.getState() == TableState.SIDEBOARDING) {
            match.updateDeck(playerId, deck, ignoreMainBasicLands);
        } else {
            // deck was meanwhile submitted so the autoupdate can be ignored
            // TODO: need research
            logger.warn("wtf, why it submitting?!");
        }
    }

    public boolean watchTable(UUID userId) {
        if (table.isTournament()) {
            managerFactory.userManager().getUser(userId).ifPresent(user -> user.ccShowTournament(table.getId(), table.getTournament().getId()));
            return true;
        } else {
            if (table.isTournamentSubTable() && !table.getTournament().getOptions().isWatchingAllowed()) {
                logger.debug("watchTable rejected: watching not allowed on tournament sub-table " + table.getId());
                return false;
            }
            if (table.getState() != TableState.DUELING) {
                logger.debug("watchTable rejected: table state " + table.getState() + " is not DUELING (tableId " + table.getId() + ")");
                return false;
            }
            // you can't watch your own game
            if (userPlayerMap.get(userId) != null) {
                logger.debug("watchTable rejected: user " + userId + " is already a player at table " + table.getId());
                return false;
            }
            Optional<User> _user = managerFactory.userManager().getUser(userId);
            if (!_user.isPresent()) {
                logger.debug("watchTable rejected: user " + userId + " not present (tableId " + table.getId() + ")");
                return false;
            }
            return _user.get().ccWatchGame(table.getId(), table.getParentTableId(), match.getGame().getId());
        }
    }

    public Optional<Player> createPlayer(String name, PlayerType playerType, int skill) {
        return createPlayer(name, playerType, skill, null);
    }

    public Optional<Player> createPlayer(String name, PlayerType playerType, int skill, mage.players.LLMPlayerConfig seatConfig) {
        Optional<Player> playerOpt;
        if (options == null) {
            playerOpt = PlayerFactory.instance.createPlayer(playerType, name, RangeOfInfluence.ALL, skill, seatConfig);
        } else {
            playerOpt = PlayerFactory.instance.createPlayer(playerType, name, options.getRange(), skill, seatConfig);
        }
        if (playerOpt.isPresent()) {
            Player player = playerOpt.get();
            logger.trace("Player " + player.getName() + " created id: " + player.getId());
        }
        return playerOpt;
    }

    /**
     * Wire per-seat LLM config overrides from MatchOptions into the Table's
     * Seat objects, so they're available when LLM players join.
     * <p>
     * The configs list is positionally aligned with LLM seats: each LLM seat
     * gets the config at the corresponding index. Null entries are intentional
     * and mean "no user override for this seat — use server defaults".
     */
    private void wireLlmConfigs() {
        if (options == null) return;
        List<mage.players.LLMPlayerConfig> configs = options.getLlmPlayerConfigs();
        if (configs == null || configs.isEmpty()) return;

        Seat[] seats = table.getSeats();
        int cfgIdx = 0;
        int wired = 0;
        for (Seat seat : seats) {
            if (seat.getPlayerType() == PlayerType.COMPUTER_LLM) {
                if (cfgIdx < configs.size()) {
                    mage.players.LLMPlayerConfig cfg = configs.get(cfgIdx++);
                    if (cfg != null) {
                        seat.setLlmPlayerConfig(cfg);
                        wired++;
                    }
                }
            }
        }
        if (wired > 0) {
            logger.info("Wired " + wired + " LLM player config(s) into seats");
        }
    }

    public void leaveTableAll() {
        for (UUID leavingUserId : userPlayerMap.keySet()) {
            leaveTable(leavingUserId);
        }
        closeTable();
    }

    public synchronized void leaveTable(UUID userId) {
        if (table == null) {
            logger.error("No table object - userId: " + userId);
            return;
        }
        if (table.isTournament() && tournament == null) {
            logger.error("No tournament object - userId: " + userId + "  table: " + table.getId());
            return;
        }
        if (this.userId != null && this.userId.equals(userId) // tourney sub tables have no creator user
                && (table.getState() == TableState.WAITING
                || table.getState() == TableState.READY_TO_START)) {
            // table not started yet and user is the owner, removeUserFromAllTablesAndChat the table
            managerFactory.tableManager().removeTable(table.getId());
        } else {
            UUID playerId = userPlayerMap.get(userId);
            if (playerId != null) {
                // TODO: wtf, need research and document all that checks, can be never used code
                if (table.getState() == TableState.WAITING || table.getState() == TableState.READY_TO_START) {
                    table.leaveNotStartedTable(playerId);
                    if (table.isTournament()) {
                        tournament.removePlayer(playerId);
                    } else {
                        match.quitMatch(playerId);
                    }
                    Optional<User> user = managerFactory.userManager().getUser(userId);
                    if (user.isPresent()) {
                        // leaveTable is synchronized and can be called from checkExpired, so a broadcast here
                        // holds both a single expire thread and a table monitor
                        // warning, massive broadcast must be done in async style
                        String leftUserName = user.get().getName();
                        managerFactory.threadExecutor().getCallExecutor().execute(() ->
                        managerFactory.chatManager().broadcast(chatId, leftUserName, "has left the table", ChatMessage.MessageColor.BLUE, true, null, ChatMessage.MessageType.STATUS, ChatMessage.SoundToPlay.PlayerLeft));
                        if (!table.isTournamentSubTable()) {
                            user.get().removeTable(playerId);
                        }
                    } else {
                        logger.debug("User not found - userId: " + userId + " tableId:" + table.getId());
                    }
                    userPlayerMap.remove(userId);
                } else if (table.getState() != TableState.FINISHED) {
                    if (table.isTournament()) {
                        logger.debug("Quit tournament sub tables for userId: " + userId);
                        managerFactory.tableManager().userQuitTournamentSubTables(tournament.getId(), userId);
                        logger.debug("Quit tournament  Id: " + table.getTournament().getId() + '(' + table.getTournament().getTournamentState() + ')');
                        managerFactory.tournamentManager().quit(tournament.getId(), userId);
                    } else {
                        MatchPlayer matchPlayer = match.getPlayer(playerId);
                        if (matchPlayer != null && !match.hasEnded() && !matchPlayer.hasQuit()) {
                            Game game = match.getGame();
                            if (game != null && !game.hasEnded()) {
                                Player player = match.getPlayer(playerId).getPlayer();
                                if (player != null && player.isInGame()) {
                                    managerFactory.gameManager().quitMatch(game.getId(), userId);
                                }
                                match.quitMatch(playerId);
                            } else {
                                if (table.getState() == TableState.SIDEBOARDING) {
                                    if (!matchPlayer.isDoneSideboarding()) {
                                        // submit deck to finish sideboarding and trigger match start / end
                                        matchPlayer.submitDeck(matchPlayer.getDeck());
                                    }
                                }
                                match.quitMatch(playerId);
                            }
                        }
                    }
                }
            } else {
                logger.error("No playerId found for userId: " + userId);
            }
        }
    }

    /**
     * Used from non tournament match to start
     *
     * @param userId owner of the tabel
     */
    public synchronized void startMatch(UUID userId) {
        if (isOwner(userId)) {
            startMatch();
        }
    }

    public synchronized void startMatch() {
        if (table.getState() == TableState.STARTING) {
            try {
                if (table.isTournamentSubTable()) {
                    logger.info("Tourney MATCH started id:" + match.getId() + " tournId: " + table.getTournament().getId());
                } else {
                    managerFactory.userManager().getUser(userId).ifPresent(user -> {
                        logger.info("Single MATCH started [" + match.getName() + "] " + match.getId() + " (" + user.getName() + ')');
                        logger.debug("- " + match.getOptions().getGameType() + " - " + match.getOptions().getDeckType());
                    });
                }
                match.startMatch();
                startGame(null);
            } catch (GameException e) {
                logger.fatal("Error starting match: " + e, e);
                match.endGame();
            }
        }
    }

    private void startGame(UUID choosingPlayerId) throws GameException {
        try {
            match.startGame();
            table.initGame();
            GameOptions gameOptions = new GameOptions();
            gameOptions.rollbackTurnsAllowed = match.getOptions().isRollbackTurnsAllowed();
            gameOptions.bannedUsers = match.getOptions().getBannedUsers();
            gameOptions.planeChase = match.getOptions().isPlaneChase();
            gameOptions.perPlayerEmblemCards = match.getOptions().getPerPlayerEmblemCards();
            gameOptions.globalEmblemCards = match.getOptions().getGlobalEmblemCards();
            match.getGame().setGameOptions(gameOptions);
            managerFactory.gameManager().createGameSession(match.getGame(), userPlayerMap, table.getId(), choosingPlayerId, gameOptions);
            String creator = null;
            StringBuilder opponent = new StringBuilder();
            for (Entry<UUID, UUID> entry : userPlayerMap.entrySet()) { // do only for no AI players
                if (match.getPlayer(entry.getValue()) != null && !match.getPlayer(entry.getValue()).hasQuit()) {
                    Optional<User> _user = managerFactory.userManager().getUser(entry.getKey());
                    if (_user.isPresent()) {
                        User user = _user.get();
                        user.ccGameStarted(table.getId(), table.getParentTableId(), match.getGame().getId(), entry.getValue());

                        if (creator == null) {
                            creator = user.getName();
                        } else {
                            if (opponent.length() > 0) {
                                opponent.append(" - ");
                            }
                            opponent.append(user.getName());
                        }
                    } else {
                        logger.error("Unable to find user: " + entry.getKey() + "  playerId: " + entry.getValue());
                        MatchPlayer matchPlayer = match.getPlayer(entry.getValue());
                        if (matchPlayer != null && !matchPlayer.hasQuit()) {
                            matchPlayer.setQuit(true);
                        }
                    }
                }
            }
            // Append AI opponents to the log file
            for (MatchPlayer mPlayer : match.getPlayers()) {
                if (!mPlayer.getPlayer().isHuman()) {
                    if (opponent.length() > 0) {
                        opponent.append(" - ");
                    }
                    opponent.append(mPlayer.getName());
                }
            }
            ServerMessagesUtil.instance.incGamesStarted();

            // log about game started
            logger.info("GAME started " + (match.getGame() != null ? match.getGame().getId() : "no Game") + " [" + match.getName() + "] " + creator + " - " + opponent);
            logger.debug("- matchId: " + match.getId() + " [" + match.getName() + ']');
            if (match.getGame() != null) {
                logger.debug("- chatId:  " + managerFactory.gameManager().getChatId(match.getGame().getId()));
            }
        } catch (Exception ex) {
            logger.fatal("Error starting game table: " + table.getId(), ex);
            if (table != null) {
                managerFactory.tableManager().removeTable(table.getId());
            }
            if (match != null) {
                Game game = match.getGame();
                if (game != null) {
                    managerFactory.gameManager().removeGame(game.getId());
                    // game ended by error, so don't add it to ended stats
                }
            }
        }
    }

    public synchronized void startTournament(UUID userId) {
        try {
            // Accept both READY_TO_START and STARTING — the client may call
            // startTournament directly (all-AI) or via the waiting dialog (human).
            TableState currentState = table.getState();
            if (userId.equals(this.userId)
                    && (currentState == TableState.STARTING || currentState == TableState.READY_TO_START)) {
                if (currentState == TableState.READY_TO_START) {
                    table.setState(TableState.STARTING);
                }
                tournament.setStartTime();
                managerFactory.tournamentManager().createTournamentSession(tournament, userPlayerMap, table.getId());
                for (Entry<UUID, UUID> entry : userPlayerMap.entrySet()) {
                    managerFactory.userManager().getUser(entry.getKey()).ifPresent(user -> {
                        logger.info(new StringBuilder("User ").append(user.getName()).append(" tournament started: ").append(tournament.getId()).append(" userId: ").append(user.getId()));
                        user.ccTournamentStarted(table.getId(), tournament.getId(), entry.getValue());
                    });
                }
                ServerMessagesUtil.instance.incTournamentsStarted();
            }
        } catch (Exception ex) {
            logger.fatal("Error starting tournament", ex);
            managerFactory.tableManager().removeTable(table.getId());
            managerFactory.tournamentManager().quit(tournament.getId(), userId);
        }
    }

    public void startDraft(Draft draft) {
        table.initDraft(draft);
        managerFactory.draftManager().createDraftSession(draft, userPlayerMap, table.getId());
        for (Entry<UUID, UUID> entry : userPlayerMap.entrySet()) {
            Optional<User> user = managerFactory.userManager().getUser(entry.getKey());
            if (user.isPresent()) {
                logger.info(new StringBuilder("User ").append(user.get().getName()).append(" draft started: ").append(draft.getId()).append(" userId: ").append(user.get().getId()));
                user.get().ccDraftStarted(table.getId(), draft.getId(), entry.getValue());
            } else {
                logger.fatal(new StringBuilder("Start draft user not found userId: ").append(entry.getKey()));
            }
        }
    }

    private void sideboard(UUID playerId, Deck deck) throws MageException {

        for (Entry<UUID, UUID> entry : userPlayerMap.entrySet()) {
            if (entry.getValue().equals(playerId)) {
                Optional<User> user = managerFactory.userManager().getUser(entry.getKey());
                int remaining = (int) futureTimeout.getDelay(TimeUnit.SECONDS);
                // Record the prompt as DELIVERED for this seat BEFORE the send:
                // this listener runs on the game thread INSIDE MatchImpl.sideboard()
                // (the SIDEBOARD table event fires from HumanPlayer.sideboard ->
                // match.fireSideboardEvent), i.e. immediately before
                // setSideboarding() would have been called for this player. The
                // early-submit hold (see submitDeck) treats "prompt delivered" as
                // the point where a submit can safely be applied.
                sideboardPromptedPlayers.add(playerId);
                user.ifPresent(user1 -> user1.ccSideboard(deck, table.getId(), table.getParentTableId(), remaining, options.isLimited()));
                break;
            }
        }
    }

    public int getRemainingTime() {
        return (int) futureTimeout.getDelay(TimeUnit.SECONDS);
    }

    public void construct() {
        table.construct();
    }

    public void initTournament() {
        table.initTournament();
    }

    public void endTournament(Tournament tournament) {
        table.endTournament();
        ServerMessagesUtil.instance.incTournamentsEnded();
    }

    public MatchOptions getOptions() {
        return options;
    }

    /**
     * Ends the current game and starts if neccessary the next game
     *
     * @return true if table can be closed
     */
    public boolean endGameAndStartNextGame() {
        // get player that chooses who goes first
        Game game = match.getGame();
        if (game == null) {
            return true;
        }
        ThreadUtils.setGameThreadStatus(game.getId(), ThreadUtils.THREAD_GAME_STATUS_AFTER_GAME);
        UUID choosingPlayerId = match.getChooser();
        // Snapshot draws: endGame() bumps match draws when the just-ended game
        // was a draw — that delta is the only reliable per-game draw signal.
        int drawsBefore = match.getDraws();
        match.endGame();
        if (match.getDraws() > drawsBefore) {
            consecutiveDraws++;
        } else {
            consecutiveDraws = 0;
        }
        if (managerFactory.configSettings().isSaveGameActivated() && !game.isSimulation()) {
            if (managerFactory.gameManager().saveGame(game.getId())) {
                match.setReplayAvailable(true);
            }
        }
        ServerMessagesUtil.instance.incGamesEnded();

        try {
            if (!match.hasEnded()) {
                // Consecutive-draw backstop, all-AI matches only (same allAI
                // predicate as sideboard()). A draw awards no win, so a match
                // where every game draws can NEVER satisfy winsNeeded and
                // restarts games forever.
                // Checked before sideboard() so a doomed match burns no further
                // LLM calls. Human matches are never capped: 5 straight draws
                // is vanishingly rare but possible for real players.
                boolean allAI = match.getPlayers().stream()
                        .allMatch(p -> p.hasQuit() || !p.getPlayer().isHuman());
                if (allAI && consecutiveDraws >= MAX_CONSECUTIVE_DRAWS) {
                    logger.warn("Closing all-AI table " + table.getId() + " (match " + match.getId() + "): "
                            + consecutiveDraws + " consecutive drawn games, cap " + MAX_CONSECUTIVE_DRAWS
                            + " reached - match would never end (degenerate instant-draw loop)");
                    managerFactory.chatManager().broadcast(chatId, "",
                            "Match closed: " + consecutiveDraws + " consecutive drawn games, cap "
                                    + MAX_CONSECUTIVE_DRAWS + " reached - the match can never finish, stopping the game loop.",
                            ChatMessage.MessageColor.RED, true, null, ChatMessage.MessageType.STATUS, null);
                    closeTable();
                    return true; // table was force-closed — caller may remove it
                }
                if (match.getGame() != null && match.getGame().getGameType().isSideboardingAllowed()) {
                    sideboard();
                }
                if (!match.hasEnded()) {
                    startGame(choosingPlayerId);
                } else {
                    closeTable();
                }
            } else {
                closeTable();
            }
        } catch (GameException ex) {
            logger.fatal(null, ex);
        } finally {
            // Defer game-controller removal until the inter-game transition is
            // complete (sideboard + next-game start). Client-key LLM seats
            // route their calls through
            // LLMBridgeRegistry -> LLMClientTransport.deliver(), which resolves
            // gameId -> tableId via gameManager.getTableIdForGame(gameId) — i.e.
            // the JUST-ENDED game's controller must still be registered while
            // sideboard()/revised-strategy LLM calls run. Removing it earlier
            // made every inter-game LLM call fail instantly with "No browser
            // session for LLM seat ..." and fatally killed the match at the
            // game-1 -> game-2 boundary.

            managerFactory.gameManager().removeGame(game.getId());
        }
        return match.hasEnded();
    }

    private void sideboard() {
        // New phase: forget the previous phase's prompt deliveries, so a stale
        // entry can never let an early submit skip the wait (see
        // sideboardPromptedPlayers). Filled again per player by the SIDEBOARD
        // table-event listener as each ccSideboard goes out.
        sideboardPromptedPlayers.clear();
        table.sideboard();
        // AI-only matches (LLM vs LLM, bot games) can take several minutes per
        // sideboarding call — deepseek at max effort needs 5+ min per player.
        // Use a much longer timeout so autoSideboard() doesn't fire while the
        // LLM is still thinking, which would falsely mark players as done and
        // cause the health check to remove the table mid-sideboard.
        boolean allAI = match.getPlayers().stream()
                .allMatch(p -> p.hasQuit() || !p.getPlayer().isHuman());
        int sideboardTimeout = allAI ? AI_SIDEBOARD_TIME : Match.SIDEBOARD_TIME;
        setupTimeout(sideboardTimeout);
        if (table.isTournamentSubTable()) {
            for (MatchPlayer matchPlayer : match.getPlayers()) {
                if (!matchPlayer.hasQuit()) {
                    TournamentPlayer tournamentPlayer = table.getTournament().getPlayer(matchPlayer.getPlayer().getId());
                    if (tournamentPlayer != null) {
                        tournamentPlayer.setStateInfo("sideboarding");
                    }
                }
            }
        }

        UUID gameId = match.getGame() == null ? null : match.getGame().getId();
        ThreadUtils.setGameThreadStatus(gameId, ThreadUtils.THREAD_GAME_STATUS_SIDEBOARD);
        try {
            try {
                match.sideboard();
            } finally {
                cancelTimeout();
                // make sure no sideboarding status after timeout/autosubmit, 
                // so user.onReconnect will skip completed sideboard
                for (UUID userId : userPlayerMap.keySet()) {
                    managerFactory.userManager().getUser(userId).ifPresent(user -> user.removeSideboarding(table.getId()));
                }
            }

            if (table.isTournamentSubTable()) {
                for (MatchPlayer matchPlayer : match.getPlayers()) {
                    TournamentPlayer tournamentPlayer = table.getTournament().getPlayer(matchPlayer.getPlayer().getId());
                    if (tournamentPlayer != null && tournamentPlayer.getStateInfo().equals("sideboarding")) {
                        tournamentPlayer.setStateInfo("");
                    }
                }
            }
        } finally {
            ThreadUtils.setGameThreadStatus(gameId, ThreadUtils.THREAD_GAME_STATUS_AFTER_GAME);
        }
    }

    /**
     * Tables of normal matches or tournament sub tables are no longer needed,
     * if the match ends.
     */
    private void closeTable() {
        this.matchEnd();
        table.closeTable();
    }

    private void matchEnd() {
        if (match != null) {
            for (Entry<UUID, UUID> entry : userPlayerMap.entrySet()) {
                MatchPlayer matchPlayer = match.getPlayer(entry.getValue());
                // opponent(s) left during sideboarding
                if (matchPlayer != null) {
                    if (!matchPlayer.hasQuit()) {
                        managerFactory.userManager().getUser(entry.getKey()).ifPresent(user -> {
                            if (table.getState() == TableState.SIDEBOARDING) {
                                StringBuilder sb = new StringBuilder();
                                if (table.isTournamentSubTable()) {
                                    sb.append("Your tournament match of round ");
                                    sb.append(table.getTournament().getRounds().size());
                                    sb.append(" is over. ");
                                } else {
                                    sb.append("Match [").append(match.getName()).append("] is over. ");
                                }
                                if (match.getPlayers().size() > 2) {
                                    sb.append("All your opponents have lost or quit the match.");
                                } else {
                                    sb.append("Your opponent has quit the match.");
                                }
                                user.showUserMessage("Match info", sb.toString());
                            }
                            // removeUserFromAllTablesAndChat table from user - table manager holds table for display of finished matches
                            if (!table.isTournamentSubTable()) {
                                user.removeTable(entry.getValue());
                            }
                        });
                    }
                }
            }
            // free resources no longer needed
            match.cleanUpOnMatchEnd(managerFactory.configSettings().isSaveGameActivated(), table.isTournament());
        }
    }

    private synchronized void setupTimeout(int seconds) {
        cancelTimeout();
        if (seconds > 0) {
            futureTimeout = timeoutExecutor.schedule(this::autoSideboard, seconds, TimeUnit.SECONDS);
        }
    }

    private synchronized void cancelTimeout() {
        if (futureTimeout != null) {
            futureTimeout.cancel(false);
        }
    }

    private void autoSideboard() {
        for (MatchPlayer player : match.getPlayers()) {
            if (!player.isDoneSideboarding()) {
                match.submitDeck(player.getPlayer().getId(), player.autoCompleteDeck(table.getValidator()));
            }
        }
    }

    public void endDraft(Draft draft) {
        if (!draft.isAbort()) {
            for (DraftPlayer player : draft.getPlayers()) {
                player.prepareDeck();
                tournament.getPlayer(player.getPlayer().getId()).setDeck(player.getDeck());
            }
        }
        tournament.clearDraft(); // free the draft resources after draft step has ended
        tournament.nextStep();
    }

    public void swapSeats(int seatNum1, int seatNum2) {
        if (table.getState() == TableState.READY_TO_START) {
            if (seatNum1 >= 0 && seatNum2 >= 0 && seatNum1 < table.getSeats().length && seatNum2 < table.getSeats().length) {
                Player swapPlayer = table.getSeats()[seatNum1].getPlayer();
                PlayerType swapType = table.getSeats()[seatNum1].getPlayerType();
                table.getSeats()[seatNum1].setPlayer(table.getSeats()[seatNum2].getPlayer());
                table.getSeats()[seatNum1].setPlayerType(table.getSeats()[seatNum2].getPlayerType());
                table.getSeats()[seatNum2].setPlayer(swapPlayer);
                table.getSeats()[seatNum2].setPlayerType(swapType);
            }
        }
    }

    public boolean isOwner(UUID userId) {
        if (userId == null) {
            return false;
        }
        return userId.equals(this.userId);
    }

    public Table getTable() {
        return table;
    }

    /** The seat occupied by the given player id, or null (used by the P1 gate). */
    private Seat seatOfPlayer(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        for (Seat s : table.getSeats()) {
            if (s.getPlayer() != null && playerId.equals(s.getPlayer().getId())) {
                return s;
            }
        }
        return null;
    }

    public UUID getChatId() {
        return chatId;
    }

    public Match getMatch() {
        return match;
    }

    public boolean isTournamentStillValid() {
        if (table.getTournament() != null) {
            if (table.getState() != TableState.WAITING && table.getState() != TableState.READY_TO_START && table.getState() != TableState.STARTING) {
                return managerFactory.tournamentManager().getTournamentController(table.getTournament().getId())
                        .map(tc -> tc.isTournamentStillValid(table.getState()))
                        .orElse(false);

            } else {
                // check if table creator is still a valid user, if not removeUserFromAllTablesAndChat table
                return managerFactory.userManager().getUser(userId).isPresent();
            }
        }
        return false;
    }

    public UUID getUserId(UUID playerId) {
        for (Map.Entry<UUID, UUID> entry : userPlayerMap.entrySet()) {
            if (entry.getValue().equals(playerId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Table owner/creator (null for tournament sub-tables). This is the user
     * whose browser created the table — the browser that holds per-seat user
     * API keys for client-key LLM seats (LLMClientTransport routing).
     */
    public UUID getOwnerUserId() {
        return userId;
    }

    public boolean isUserStillActive(UUID userId) {
        UUID playerId = userPlayerMap.get(userId);
        if (playerId != null) {
            if (tournament != null) {
                TournamentPlayer tournamentPlayer = tournament.getPlayer(playerId);
                if (tournamentPlayer != null) {
                    return tournamentPlayer.isInTournament();
                }
            } else if (match != null) {
                MatchPlayer matchPlayer = match.getPlayer(playerId);
                return matchPlayer != null && !matchPlayer.hasQuit();
            }
        }
        return false;
    }

    public boolean isMatchTableStillValid() {
        // removes active match only, not tourney
        if (table.isTournament()) {
            return true;
        }

        // tournament sub-tables are managed by TournamentController — don't remove them
        if (table.isTournamentSubTable()) {
            return true;
        }

        // only started games need to check
        if (Arrays.asList(
                TableState.WAITING,
                TableState.READY_TO_START,
                TableState.STARTING
        ).contains(table.getState())) {
            // waiting in start dialog
            return true;
        }
        if (match != null && !match.isDoneSideboarding()) {
            // waiting sideboard complete
            return true;
        }

        // no games in started match (error in match init code?)
        if (match.getGame() == null) {
            // Between games of a multi-game match (e.g. Bo3), the current game
            // may be null briefly while the next game is being set up. If no
            // player has won the match yet, keep the table alive rather than
            // incorrectly removing it as "unhealthy".
            boolean matchWon = match.getPlayers().stream()
                    .anyMatch(MatchPlayer::isMatchWinner);
            if (!matchWon) {
                return true; // match still in progress, between games
            }
            logger.error("- Match without started games:");
            logger.error("-- matchId:" + match.getId());
            return false; // critical error
        }

        // find player stats
        int validHumanPlayers = 0;
        int validAIPlayers = 0;

        // check humans
        for (Map.Entry<UUID, UUID> userPlayerEntry : userPlayerMap.entrySet()) {
            MatchPlayer matchPlayer = match.getPlayer(userPlayerEntry.getValue());

            // de-synced users and players listst?
            if (matchPlayer == null) {
                logger.error("- Match player not found in started game:");
                logger.error("-- matchId:" + match.getId());
                logger.error("-- userId:" + userPlayerEntry.getKey());
                logger.error("-- playerId:" + userPlayerEntry.getValue());
                continue;
            }

            if (matchPlayer.getPlayer().isHuman()) {
                if (matchPlayer.getPlayer().isInGame()) {
                    Optional<User> user = managerFactory.userManager().getUser(userPlayerEntry.getKey());

                    // user was logout or disconnected from server, but still in the game somehow
                    if (!user.isPresent() || !user.get().isActive()) {
                        logger.error("- Active user of match is missing: " + matchPlayer.getName());
                        logger.error("-- matchId:" + match.getId());
                        logger.error("-- userId:" + userPlayerEntry.getKey());
                        logger.error("-- playerId:" + userPlayerEntry.getValue());
                        return false; // critical error
                    }

                    // user exits on the server and match player has not quit -> player is valid
                    validHumanPlayers++;
                }
            }
        }

        // check AI
        for (MatchPlayer matchPlayer : match.getPlayers()) {
            if (!matchPlayer.getPlayer().isHuman()) {
                if (matchPlayer.getPlayer().isInGame()) {
                    validAIPlayers++;
                }
            }
        }

        // if someone can play 1 vs 1 (e.g. 2+ players) then keep table
        if (validAIPlayers + validHumanPlayers >= 2) {
            return true;
        }

        // Between games of a multi-game match (e.g. Bo3), players may briefly
        // not be isInGame() while transitioning from one game to the next.
        // If the match is still in progress (no winner yet), keep the table
        // alive so the next game can start.
        if (table.getState() == TableState.DUELING) {
            boolean matchWon = match.getPlayers().stream()
                    .anyMatch(MatchPlayer::isMatchWinner);
            if (!matchWon) {
                return true; // match still in progress, between games
            }
        }

        logger.debug("isMatchTableStillValid -> false (no valid players remain; tableId " + table.getId() + ", matchId " + match.getId() + ", state " + table.getState() + ")");
        return false;
    }

    void cleanUp() {
        if (!table.isTournamentSubTable()) {
            for (Map.Entry<UUID, UUID> entry : userPlayerMap.entrySet()) {
                managerFactory.userManager().getUser(entry.getKey()).ifPresent(user
                        -> user.removeTable(entry.getValue()));
            }

        }
        managerFactory.chatManager().destroyChatSession(chatId);
    }

    public synchronized TableState getTableState() {
        return table.getState();
    }

    public synchronized boolean changeTableStateToStarting() {
        if (table.getState() != TableState.READY_TO_START) {
            // tournament is not ready, can't start
            logger.debug("changeTableStateToStarting rejected: table state " + table.getState() + " is not READY_TO_START (tableId " + table.getId() + ")");
            return false;
        }
        if (!table.allSeatsAreOccupied()) {
            logger.debug("Not alle Seats are occupied: stop start tableId:" + table.getId());
            return false;
        }
        table.setState(TableState.STARTING);
        return true;
    }
}