package mage.server.game;

import mage.game.Game;
import mage.game.Table;
import mage.interfaces.callback.ClientCallback;
import mage.interfaces.callback.ClientCallbackMethod;
import mage.players.Player;
import mage.server.User;
import mage.server.managers.UserManager;
import mage.util.ThreadUtils;
import mage.view.GameClientMessage;
import mage.view.GameEndView;
import mage.view.GameView;
import mage.view.SimpleCardsView;
import org.apache.log4j.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * @author BetaSteward_at_googlemail.com
 */
public class GameSessionWatcher {

    protected static final Logger logger = Logger.getLogger(GameSessionWatcher.class);

    private final UserManager userManager;
    protected final UUID userId;
    protected final Game game;
    protected boolean killed = false;
    protected final boolean isPlayer;
    /** Match-wide: may opponents/spectators see LLM chat live? Passed in by the
     *  GameController from the match's MatchOptions; replays always set it true. */
    protected final boolean llmChatPublic;

    protected GameView lastGameView = null; // cached game view for non-game threads

    public GameSessionWatcher(UserManager userManager, UUID userId, Game game, boolean isPlayer) {
        this(userManager, userId, game, isPlayer, false);
    }

    public GameSessionWatcher(UserManager userManager, UUID userId, Game game, boolean isPlayer, boolean llmChatPublic) {
        this.userManager = userManager;
        this.userId = userId;
        this.game = game;
        this.isPlayer = isPlayer;
        this.llmChatPublic = llmChatPublic;
    }

    public boolean init() {
        return init(true);
    }

    /**
     * @param sendNow send it or wait next tick, e.g. fill and flush, see GameController.startGame as example
     */
    public boolean init(boolean sendNow) {
        if (!killed) {
            Optional<User> user = userManager.getUser(userId);
            if (user.isPresent()) {
                ClientCallback call = new ClientCallback(ClientCallbackMethod.GAME_INIT, game.getId(), getGameView());
                if (sendNow) {
                    user.get().fireCallback(call);
                } else {
                    user.get().addCallback(call);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Sends queued messages in a separate thread, a caller is not blocked
     */
    public void flushCallbacks() {
        if (!killed) {
            userManager.getUser(userId).ifPresent(User::flushCallbacksQueue);
        }
    }

    public void update() {
        if (!killed) {
            userManager.getUser(userId).ifPresent(user -> user.fireCallback(new ClientCallback(ClientCallbackMethod.GAME_UPDATE, game.getId(), getGameView())));
        }

    }

    public void inform(final String message) {
        inform(message, true);
    }

    /**
     * @param message additional text to show in feedback panel after update the battlefield
     * @param sendNow send it or wait next tick, e.g. fill and flush, see GameController.startGame as example
     */
    public void inform(final String message, boolean sendNow) {
        if (!killed) {
            userManager.getUser(userId).ifPresent(user -> {
                ClientCallback call = new ClientCallback(ClientCallbackMethod.GAME_UPDATE_AND_INFORM, game.getId(), new GameClientMessage(getGameView(), null, message));
                if (sendNow) {
                    user.fireCallback(call);
                } else {
                    user.addCallback(call);
                }
            });
        }
    }

    public void informPersonal(final String message) {
        if (!killed) {
            userManager.getUser(userId).ifPresent(user -> user.fireCallback(new ClientCallback(ClientCallbackMethod.GAME_INFORM_PERSONAL, game.getId(), new GameClientMessage(getGameView(), null, message))));
        }

    }

    public void gameOver(final String message) {
        if (!killed) {
            userManager.getUser(userId).ifPresent(user -> {
                user.removeGameWatchInfo(game.getId());
                user.fireCallback(new ClientCallback(ClientCallbackMethod.GAME_OVER, game.getId(), new GameClientMessage(getGameView(), null, message)));
            });
        }
    }

    /**
     * Cleanup if Session ends
     */
    public void cleanUp() {

    }

    public void gameError(final String message) {
        if (!killed) {
            // TODO: implement shared link here (store game state, full battlefield info, history/logs, etc with shared link to web page)
            userManager.getUser(userId).ifPresent(user -> user.fireCallback(new ClientCallback(ClientCallbackMethod.GAME_ERROR, game.getId(), message)));
        }
    }

    public void setKilled() {
        killed = true;
    }

    /**
     * Bootstrap with default game view, so all non-game thread calls will be safe like start/watch
     */
    public void startWithGameView(GameView defaultGameView) {
        if (GameView.ENABLE_GAME_VIEW_CACHE) {
            this.lastGameView = defaultGameView;
        }
    }

    static public GameView generateDefaultGameViewForWatcher(Game game) {
        return new GameView(game.getState(), game, null, UUID.randomUUID());
    }

    public GameView getGameView() {
        // game view calculation can take some time and can be called from non-game thread,
        // so recalculate game view by game thread only to protect from ConcurrentModificationException
        // warning, don't forget to sync logci with GameSessionWatcher and GameSessionPlayer
        if (this.lastGameView != null && !ThreadUtils.isRunGameThread()) {
            return this.lastGameView;
        }

        // short processing for the watcher
        GameView gameView = new GameView(game.getState(), game, null, userId);
        // LLM chat visibility is a per-match flag: set it BEFORE the view is
        // cached, so non-game threads returning the cached view see the same value.
        gameView.setLlmChatPublic(llmChatPublic);
        processWatchedHands(game, userId, gameView);
        processLLMDebugData(game, gameView);

        if (GameView.ENABLE_GAME_VIEW_CACHE) {
            this.lastGameView = gameView;
        }

        return gameView;
    }

    protected static void processWatchedHands(Game game, UUID userId, GameView gameView) {
        gameView.getWatchedHands().clear();
        for (Player player : game.getPlayers().values()) {
            if (player.hasUserPermissionToSeeHand(userId)) {
                gameView.getWatchedHands().put(player.getName(), new SimpleCardsView(player.getHand().getCards(game), true));
            }
        }
    }

    public GameEndView getGameEndView(UUID playerId, Table table) {
        return new GameEndView(game.getState(), game, playerId, table);
    }

    public void endGameInfo(Table table) {
        if (!killed) {
            userManager.getUser(userId).ifPresent(user -> user.fireCallback(new ClientCallback(ClientCallbackMethod.END_GAME_INFO, game.getId(), getGameEndView(null, table))));
        }
    }

    public boolean isPlayer() {
        return isPlayer;
    }


    /**
     * Extract LLM debug data from any players that are LLM players.
     *
     * LLMPlayer lives in a plugin JAR loaded by a different classloader, so
     * {@code instanceof mage.player.llm.LLMPlayer} always returns false at runtime
     * (different Class object for the same name). Use a class-name string check
     * plus reflection to invoke getLLMDebugData() without a compile-time dependency
     * on the plugin JAR.
     *
     * Shared by GameSessionWatcher (spectator path) and GameSessionPlayer (player
     * path) so both see the same LLM debug data. Fail Fast: reflection errors are
     * logged at WARN — if the LLMPlayer method signature changes we want to know,
     * not silently lose debug data.
     */
    protected static void processLLMDebugData(Game game, GameView gameView) {
        java.util.List<mage.view.LLMConversationView> debugData = new java.util.ArrayList<>();
        for (Player p : game.getPlayers().values()) {
            if (p.getClass().getName().equals("mage.player.llm.LLMPlayer")) {
                try {
                    java.lang.reflect.Method getDebug = p.getClass().getMethod("getLLMDebugData");
                    mage.view.LLMConversationView data = (mage.view.LLMConversationView) getDebug.invoke(p);
                    if (data != null) {
                        debugData.add(data);
                    }
                } catch (Throwable e) {
                    // Fail Fast: log instead of swallowing. A signature change or
                    // classloader shift would otherwise silently drop LLM debug data.
                    logger.warn("LLMPlayer present but getLLMDebugData() failed: " + e.getMessage(), e);
                }
            }
        }
        if (!debugData.isEmpty()) {
            gameView.setLLMDebug(debugData);
        }
    }

}
