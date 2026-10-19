package mage.server.game;

import mage.server.managers.ReplayManager;
import mage.server.managers.ManagerFactory;
import org.apache.log4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author BetaSteward_at_googlemail.com
 */
public class ReplayManagerImpl implements ReplayManager {

    private static final Logger logger = Logger.getLogger(ReplayManagerImpl.class);

    private final ConcurrentHashMap<String, ReplaySession> replaySessions = new ConcurrentHashMap<>();
    private final ManagerFactory managerFactory;

    public ReplayManagerImpl(ManagerFactory managerFactory) {
        this.managerFactory = managerFactory;
    }

    @Override
    public void replayGame(UUID gameId, UUID userId) {
        ReplaySession replaySession = new ReplaySession(managerFactory, gameId, userId);
        replaySessions.put(gameId.toString() + userId.toString(), replaySession);
        managerFactory.userManager().getUser(userId).ifPresent(user -> user.ccReplayGame(gameId));
    }

    @Override
    public void startReplay(UUID gameId, UUID userId) {
        ReplaySession session = replaySessions.get(gameId.toString() + userId.toString());
        if (session != null) {
            session.replay();
        } else {
            logger.warn("Replay session not found for startReplay gameId=" + gameId);
        }
    }

    @Override
    public void stopReplay(UUID gameId, UUID userId) {
        ReplaySession session = replaySessions.get(gameId.toString() + userId.toString());
        if (session != null) {
            session.stop();
        }
    }

    @Override
    public void nextPlay(UUID gameId, UUID userId) {
        ReplaySession session = replaySessions.get(gameId.toString() + userId.toString());
        if (session != null) {
            session.next();
        } else {
            logger.warn("Replay session not found for nextPlay gameId=" + gameId);
        }
    }

    @Override
    public void previousPlay(UUID gameId, UUID userId) {
        ReplaySession session = replaySessions.get(gameId.toString() + userId.toString());
        if (session != null) {
            session.previous();
        } else {
            logger.warn("Replay session not found for previousPlay gameId=" + gameId);
        }
    }

    @Override
    public void skipForward(UUID gameId, UUID userId, int moves) {
        ReplaySession session = replaySessions.get(gameId.toString() + userId.toString());
        if (session != null) {
            session.next(moves);
        }
    }

    @Override
    public void seekTo(UUID gameId, UUID userId, int stateIndex) {
        ReplaySession session = replaySessions.get(gameId.toString() + userId.toString());
        if (session == null) {
            // Fail Fast: a seek against a missing session must not look like a
            // delivered frame. nextPlay/previousPlay warn; a seek is only ever
            // sent by the web viewer, where silence would strand the position
            // control at a frame that was never rendered.
            throw new IllegalStateException("Replay session not found for seekTo gameId=" + gameId);
        }
        session.seek(stateIndex);
    }

    @Override
    public void endReplay(UUID gameId, UUID userId) {
        replaySessions.remove(gameId.toString() + userId.toString());
    }

    /**
     * Remove replay sessions that were created more than {@code maxAgeMs} ago and
     * have never been explicitly ended via {@link #endReplay}. Abandoned sessions
     * (user disconnects without ending the replay) otherwise leak forever since
     * replaySessions only grows. Safe to call periodically from a scheduled task.
     *
     * @param maxAgeMs maximum age in milliseconds; sessions older than this are removed
     * @return the number of sessions removed
     */
    @Override
    public int cleanupStaleSessions(long maxAgeMs) {
        if (replaySessions.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int removed = 0;
        // Use an explicit iterator to avoid ConcurrentModificationException and
        // to support removal during traversal on ConcurrentHashMap's entrySet.
        for (Map.Entry<String, ReplaySession> entry : replaySessions.entrySet()) {
            ReplaySession session = entry.getValue();
            if (session == null) {
                continue;
            }
            if (now - session.getCreatedAtMs() > maxAgeMs) {
                // remove(key) is atomic and safe on ConcurrentHashMap
                if (replaySessions.remove(entry.getKey()) != null) {
                    removed++;
                    logger.info("Removed stale replay session " + entry.getKey()
                            + " (age " + (now - session.getCreatedAtMs()) + "ms)");
                }
            }
        }
        return removed;
    }

}
