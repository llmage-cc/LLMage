

package mage.server.game;

import mage.constants.RangeOfInfluence;
import mage.players.Player;
import mage.players.PlayerType;
import org.apache.log4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.EnumMap;
import java.util.Optional;
import java.util.Set;

/**
 * @author BetaSteward_at_googlemail.com
 */
public enum PlayerFactory {

    instance;
    private static final Logger logger = Logger.getLogger(PlayerFactory.class);

    private final EnumMap<PlayerType, Class> playerTypes = new EnumMap<>(PlayerType.class);

    /**
     * Why the LAST creation attempt on THIS thread returned empty, or null when the
     * last attempt on this thread has no failure to report (never attempted, or the
     * attempt succeeded). A ThreadLocal keeps concurrent creations from reading each
     * other's cause: the caller (TableController) composes the user message on the
     * same thread that called createPlayer.
     */
    private static final ThreadLocal<String> lastCreationFailure = new ThreadLocal<>();

    /**
     * Cause text of the last failed creation on the calling thread — the exception
     * class plus its message (the real cause, reflection wrapper unwrapped), or a
     * note that no player class is registered for the requested type. null means
     * "nothing to report", so a caller must treat null as "no detail available".
     *
     * <p>Only meaningful AFTER createPlayer returned empty, and only on the thread
     * that made the call.
     */
    public static String getLastCreationFailure() {
        return lastCreationFailure.get();
    }


    /**
     * Create a player, optionally carrying a per-seat LLMPlayerConfig (the web/desktop
     * New-Table dialogs attach one to every COMPUTER_LLM seat). Delivery is a ThreadLocal
     * on LLMPlayerConfig (the config class lives in Mage, visible to BOTH the system and
     * plugin classloaders — the plugin class itself is not visible to Mage.Server code),
     * consumed inside LLMPlayer.init() BEFORE context-window detection and the thinking
     * probe so those dial the seat's endpoint/model, not the server default. Set →
     * construct → clear in finally: a stuck pending value can never leak to the next
     * creation on this thread. null/absent pending config = old behavior, byte-for-byte.
     */
    public Optional<Player> createPlayer(PlayerType playerType, String name, RangeOfInfluence range, int skill, mage.players.LLMPlayerConfig seatConfig) {
        if (seatConfig != null) {
            mage.players.LLMPlayerConfig.setPendingPlayerConfig(seatConfig);
        }
        try {
            return createPlayer(playerType, name, range, skill);
        } finally {
            if (seatConfig != null) {
                mage.players.LLMPlayerConfig.consumePendingPlayerConfig();
            }
        }
    }

    public Optional<Player> createPlayer(PlayerType playerType, String name, RangeOfInfluence range, int skill) {
        // Clear BEFORE the attempt: the caller reads the cause only after an empty
        // Optional, and a stale value from an earlier creation on this thread must
        // never be reported as this attempt's failure.
        lastCreationFailure.remove();
        try {
            Class playerTypeClass = playerTypes.get(playerType);
            if (playerTypeClass != null) {
                Constructor<?> con = playerTypeClass.getConstructor(String.class, RangeOfInfluence.class, int.class);
                Player player = (Player) con.newInstance(name, range, skill);
                logger.trace("Player created: " + name + " - " + player.getId());
                return Optional.of(player);
            } else {
                lastCreationFailure.set("no player class registered for type " + playerType);
                logger.fatal("Unknown player type: " + playerType);
            }
        } catch (Exception ex) {
            lastCreationFailure.set(describeCreationFailure(ex));
            logger.fatal("PlayerFactory error ", ex);
        }
        return Optional.empty();
    }

    /**
     * Render a creation exception as "class: message" for the user-facing join
     * failure. The reflection wrapper is unwrapped first, so the text names what
     * the constructor actually threw (a refused connection, a malformed endpoint
     * URL, a plugin init error) instead of InvocationTargetException.
     */
    private static String describeCreationFailure(Throwable ex) {
        Throwable cause = ex;
        while (cause instanceof InvocationTargetException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return cause.getClass().getName();
        }
        return cause.getClass().getName() + ": " + message;
    }

    public Set<PlayerType> getPlayerTypes() {
        return playerTypes.keySet();
    }

    public void addPlayerType(String name, Class playerType) {
        // will raise error and stop on unknown player and that's ok - it's require HumanPlayer anyway
        PlayerType type = PlayerType.getByDescription(name);
        if (playerType != null) {
            this.playerTypes.put(type, playerType);
        }
    }

    /**
     * Counterpart of {@link #addPlayerType}: drop a registered player class, so a
     * type that was never configured by config.xml goes back to "not registered"
     * (tests register a throwaway class and must not leave it behind for the next
     * case in the same JVM). No-op for an absent type.
     */
    public void removePlayerType(PlayerType playerType) {
        this.playerTypes.remove(playerType);
    }
}
