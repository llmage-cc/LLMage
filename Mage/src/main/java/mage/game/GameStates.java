
package mage.game;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import org.apache.log4j.Logger;

/**
 *
 * @author BetaSteward_at_googlemail.com
 */
public class GameStates implements Serializable {

    // Pin alongside GameState (same rationale, same corpus — .game files written
    // with the unmanaged UID must still load after the Sep-12 pruneOldest add).
    private static final long serialVersionUID = -892885620443015358L;

    private static final Logger logger = Logger.getLogger(GameStates.class);
    private final List<GameState> states;

    /**
     * Hard cap on saved states when replay saving is enabled. saveGame keeps
     * every state (removeBookmark skips pruning in that mode), so a long
     * replay-enabled match grows unbounded in memory. Drop
     * oldest states past the cap; savedStates bookmarks that point INTO a
     * dropped range then fail restoreState loudly with the existing
     * "bookmark does not exist" error, not silently.
     */
    private static final int MAX_SAVED_STATES = 10_000;

    public GameStates() {
        this.states = new ArrayList<>();
    }

    public void save(GameState gameState) {
        states.add(gameState.copy());
    }

    /** Caller-side cap — GameStates itself doesn't know if saveGame is on. */
    public void pruneOldest(int keepLatest) {
        if (states.size() > keepLatest) {
            int drop = states.size() - keepLatest;
            states.subList(0, drop).clear();
            logger.debug("Pruned " + drop + " oldest saved states (cap " + keepLatest + ")");
        }
    }

    /**
     * Drop every state AFTER the kept prefix — the kept prefix ends at the
     * LAST state whose turnNum is strictly below turnNum (size 0 when none).
     * Snapshots already recorded IN the target turn are dropped along with
     * the rest of the aborted timeline: the re-played turn re-records them,
     * so last-write-wins instead of stacking duplicate frames behind the
     * resume point.
     *
     * Used by turn rollback so the replay timeline ends where the rollback
     * rewound to and the re-played turns append after a preserved prefix —
     * one cohesive replay of the final game — instead of
     * the old clear()-everything that erased turns 1..N-1 from the replay
     * whenever a rollback fired.
     *
     * @param turnNum rollback target turn
     * @return number of states removed (0 when there is nothing to drop)
     */
    public int truncateFromTurn(int turnNum) {
        int lastKept = -1;
        for (int i = 0; i < states.size(); i++) {
            if (states.get(i).getTurnNum() < turnNum) {
                lastKept = i;
            }
        }
        int cut = lastKept + 1;
        if (cut < states.size()) {
            int removed = states.size() - cut;
            states.subList(cut, states.size()).clear();
            return removed;
        }
        return 0;
    }

    public int getSize() {
        return states.size();
    }

    public GameState rollback(int index) {
        if (!states.isEmpty() && index < states.size()) {
            while (states.size() > index + 1) {
                states.remove(states.size() - 1);
            }
            logger.trace("Rolling back state: " + index);
            return states.get(index);
        }
        return null;
    }

    public int remove(int index) {
        if (!states.isEmpty() && index < states.size()) {
            while (states.size() > index && !states.isEmpty()) {
                states.remove(states.size() - 1);
            }
        }
        return states.size();
    }

    public GameState get(int index) {
        if (index < states.size()) {
            return states.get(index);
        }
        return null;
    }

    public void clear() {
        states.clear();
    }
}
