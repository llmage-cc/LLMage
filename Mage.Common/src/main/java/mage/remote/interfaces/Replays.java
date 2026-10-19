
package mage.remote.interfaces;

import java.util.UUID;

/**
 * @author noxx
 */
public interface Replays {

    boolean replayGame(UUID gameId);

    boolean startReplay(UUID gameId);

    boolean stopReplay(UUID gameId);

    boolean nextPlay(UUID gameId);

    boolean previousPlay(UUID gameId);

    boolean skipForward(UUID gameId, int moves);

    /**
     * Jump to a saved-state index (0-based), for the web viewer's position
     * control. Unlike nextPlay/previousPlay this reaches frame 0 and costs one
     * round-trip regardless of distance.
     */
    boolean seekTo(UUID gameId, int stateIndex);
}
