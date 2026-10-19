package mage.server.managers;

import java.util.UUID;

public interface ReplayManager {
    void replayGame(UUID gameId, UUID userId);

    void startReplay(UUID gameId, UUID userId);

    void stopReplay(UUID gameId, UUID userId);

    void nextPlay(UUID gameId, UUID userId);

    void previousPlay(UUID gameId, UUID userId);

    void skipForward(UUID gameId, UUID userId, int moves);

    /**
     * Jump to a saved-state index (0-based) — see {@link Replays#seekTo}.
     */
    void seekTo(UUID gameId, UUID userId, int stateIndex);

    void endReplay(UUID gameId, UUID userId);

    /**
     * Remove replay sessions older than the given age. Call periodically to
     * reclaim sessions abandoned by disconnected users.
     *
     * @param maxAgeMs maximum age in milliseconds; sessions older than this are removed
     * @return number of sessions removed
     */
    int cleanupStaleSessions(long maxAgeMs);
}
