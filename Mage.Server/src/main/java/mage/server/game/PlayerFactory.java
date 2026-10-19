

package mage.server.game;

import mage.constants.RangeOfInfluence;
import mage.players.Player;
import mage.players.PlayerType;
import org.apache.log4j.Logger;

import java.lang.reflect.Constructor;
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
        try {
            Class playerTypeClass = playerTypes.get(playerType);
            if (playerTypeClass != null) {
                Constructor<?> con = playerTypeClass.getConstructor(String.class, RangeOfInfluence.class, int.class);
                Player player = (Player) con.newInstance(name, range, skill);
                logger.trace("Player created: " + name + " - " + player.getId());
                return Optional.of(player);
            } else {
                logger.fatal("Unknown player type: " + playerType);
            }
        } catch (Exception ex) {
            logger.fatal("PlayerFactory error ", ex);
        }
        return Optional.empty();
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
}
