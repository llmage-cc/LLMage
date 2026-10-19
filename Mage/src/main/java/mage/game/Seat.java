
package mage.game;

import mage.players.LLMPlayerConfig;
import mage.players.Player;
import mage.players.PlayerType;

import java.io.Serializable;

/**
 *
 * @author BetaSteward_at_googlemail.com
 */
public class Seat implements Serializable {

    // Official-server wire compat: upstream 1.4.61 computes
    // 5595719867240619273; our llmPlayerConfig field rides along harmlessly
    // under a matching suid — the proxy SENDS Seat (inside MatchOptions) on
    // create_table, and an unpinned mismatch makes stock servers reject the
    // call when deserializing our argument.
    private static final long serialVersionUID = 5595719867240619273L;

//    private static final Logger logger = Logger.getLogger(Seat.class);
    private PlayerType playerType;
    private Player player;
    private LLMPlayerConfig llmPlayerConfig;

    public Seat(PlayerType playerType) {
        this.playerType = playerType;
    }

    public PlayerType getPlayerType() {
        return playerType;
    }

    public void setPlayerType(PlayerType playerType) {
        this.playerType = playerType;
    }

    public Player getPlayer() {
        return player;
    }

    public void setPlayer(Player player) {
        this.player = player;
    }

    public LLMPlayerConfig getLlmPlayerConfig() {
        return llmPlayerConfig;
    }

    public void setLlmPlayerConfig(LLMPlayerConfig llmPlayerConfig) {
        this.llmPlayerConfig = llmPlayerConfig;
    }

}
