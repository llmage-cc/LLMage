
package mage.view;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import mage.game.Game;
import mage.game.GameEndReason;
import mage.game.GameState;
import mage.game.Table;
import mage.game.match.Match;
import mage.game.match.MatchPlayer;
import mage.players.Player;

/**
 * @author LevelX2
 */
public class GameEndView implements Serializable {

    // Pinned to the value this class computed on the unmodified 1.4.62 tree
    // (measured 2026-10-20 with serialver + ObjectStreamClass.lookup against
    // Mage.Common/target/classes). The official 1.4.61 desktop client's
    // GameEndView computes the same value, so pinning keeps the END_GAME_INFO
    // payload readable in both directions: an older client skips the additive
    // endReason field by name, and our proxy reads the older server's view.
    // Re-derive only on a deliberate wire break.
    private static final long serialVersionUID = 6881988264274364339L;

    private PlayerView clientPlayer = null;
    private final List<PlayerView> players = new ArrayList<>();
    private final Date startTime;
    private final Date endTime;
    private String gameInfo;
    private final String matchInfo;
    private final String additionalInfo;
    private boolean won;
    private final MatchView matchView;
    private int wins;
    private int loses;
    private final int winsNeeded;
    /**
     * Why the game ended for the player this view belongs to
     * ({@link mage.game.GameEndReason} values), or null for the spectator /
     * watcher view, which has no subject player. ADDITIVE wire field: an older
     * client skips it by name under the pinned serialVersionUID above.
     */
    private final String endReason;

    public GameEndView(GameState state, Game game, UUID playerId, Table table) {
        startTime = game.getStartTime();
        endTime = game.getEndTime();

        // set result message
        int winner = 0;
        Player you = null;
        for (Player player : state.getPlayers().values()) {
            PlayerView playerView = new PlayerView(player, state, game, playerId, null);
            if (playerView.getPlayerId().equals(playerId)) {
                clientPlayer = playerView;
                you = player;
                won = you.hasWon(); // needed to control image
            }
            players.add(playerView);
            if (player.hasWon()) {
                winner++;
            }
        }
        if (you != null) {
            if (you.hasWon()) {
                gameInfo = "You won the game on turn " + game.getTurnNum() + ".";
            } else if (winner > 0) {
                gameInfo = "You lost the game on turn " + game.getTurnNum() + ".";
            } else {
                gameInfo = "Game is a draw on Turn " + game.getTurnNum() + ".";
            }
        }
        matchView = new MatchView(table);

        Match match = table.getMatch();
        MatchPlayer matchWinner = null;
        MatchPlayer subjectMatchPlayer = null;
        winsNeeded = match.getOptions().getWinsNeeded();
        StringBuilder additonalText = new StringBuilder();
        for (MatchPlayer matchPlayer : match.getPlayers()) {
            if (matchPlayer.getPlayer().equals(you)) {
                wins = matchPlayer.getWins();
                subjectMatchPlayer = matchPlayer;
            }
            if (matchPlayer.isMatchWinner()) {
                matchWinner = matchPlayer;
            }
            if (matchPlayer.getPlayer().hasTimerTimeout()) {
                if (matchPlayer.getPlayer().equals(you)) {
                    additonalText.append("You run out of time. ");
                } else {
                    additonalText.append(matchPlayer.getName()).append(" runs out of time. ");
                }
            } else if (matchPlayer.getPlayer().hasIdleTimeout()) {
                if (matchPlayer.getPlayer().equals(you)) {
                    additonalText.append("You lost the match for being idle. ");
                } else {
                    additonalText.append(matchPlayer.getName()).append(" lost for being idle. ");
                }
            } else if (matchPlayer.hasQuit()) {
                if (matchPlayer.getPlayer().equals(you)) {
                    additonalText.append("You have quit the match. ");
                } else {
                    additonalText.append(matchPlayer.getName()).append(" has quit the match. ");
                }
            }
        }

        if (matchWinner != null) {
            if (matchWinner.getPlayer().equals(you)) {
                matchInfo = "You won the match!";
            } else {
                matchInfo = new StringBuilder(matchWinner.getName()).append(" won the match!").toString();
            }
        } else {
            matchInfo = new StringBuilder("You need ").append(winsNeeded - wins == 1 ? "one more win " : winsNeeded - wins + " more wins ").append("to win the match.").toString();
        }
        additionalInfo = additonalText.toString();

        // Reason for THIS player only: a watcher (playerId == null) has no subject
        // seat, so it stays null rather than reporting someone else's exit. The
        // timer/idle/quit flags are read from the same Player/MatchPlayer this
        // constructor already inspects — they are authoritative over the reason
        // the engine recorded, because timeout/idle/quit all enter the game end
        // through an ordinary concede.
        endReason = playerId == null ? null : GameEndReason.resolve(
                you != null && you.hasTimerTimeout(),
                you != null && you.hasIdleTimeout(),
                subjectMatchPlayer != null && subjectMatchPlayer.hasQuit(),
                state.getEndReason());
    }

    public Date getStartTime() {
        return startTime;
    }

    public Date getEndTime() {
        return endTime;
    }

    public List<PlayerView> getPlayers() {
        return players;
    }

    public String getGameInfo() {
        return gameInfo;
    }

    public String getMatchInfo() {
        return matchInfo;
    }

    public String getAdditionalInfo() {
        return additionalInfo;
    }

    public boolean hasWon() {
        return won;
    }

    public MatchView getMatchView() {
        return matchView;
    }

    public int getWins() {
        return wins;
    }

    public int getLoses() {
        return loses;
    }

    public int getWinsNeeded() {
        return winsNeeded;
    }

    public PlayerView getClientPlayer() {
        return clientPlayer;
    }

    /**
     * Why the game ended for this view's player: one of the
     * {@link mage.game.GameEndReason} values ("llm_failure", "concede",
     * "timeout", "idle", "quit") or "unknown" when nothing was recorded (a
     * played-out win or a draw). Null on the spectator/watcher view, which has
     * no subject player.
     */
    public String getEndReason() {
        return endReason;
    }

}
