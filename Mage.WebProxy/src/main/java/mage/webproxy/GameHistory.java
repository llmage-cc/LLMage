package mage.webproxy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, per-username buffer of the game-log and chat `event` payloads the
 * browser has already been sent, so a rejoin can replay them.
 *
 * Root cause it fixes: on reconnect the browser re-runs connect → rejoin_game →
 * GAME_INIT, and GAME_INIT carries a {@link mage.view.GameView} snapshot only —
 * {@link GameStateSerializer#buildStateMap} deliberately emits no `events` array
 * (see the NOTE above its `stateSeq` line). The frontend keeps chat and game-log
 * entries only in its in-memory Zustand store, so every reconnect and every page
 * refresh came back with the panels emptied back to the join banner

 *
 * Why this class is keyed by username and not a field on the session/client:
 * a WS drop + reconnect constructs a NEW {@link WebProxySession} (one per
 * connection, {@link WebProxyServer}) and a NEW {@link WebProxyClient}
 * ({@link WebProxySession#handleConnect}), while the XMage session itself is
 * deliberately KEPT ALIVE for a live game ({@link WebProxySession#cleanup} →
 * connectStop(false, true)). The buffer therefore has to outlive both proxy
 * objects; the username is the only stable key (one live game per user in this
 * client — a second tab for the same username takes over the same XMage session,
 * see the anon same-host takeover path).
 *
 * GUARANTEE IMPLEMENTED: at-least-once re-delivery of the last
 * {@link #MAX_LOG_EVENTS} log events and {@link #MAX_CHAT_EVENTS} chat lines of
 * the CURRENT game, made idempotent on the client by the event `seq`
 * (frontend `mergeHistory` unions by seq and re-sorts, so a client that already
 * holds seq 1..120 sees a backfill of 1..150 as 1..150 with no duplicates).
 * Replay is sent immediately BEFORE the `game_init` state push of a rejoin, i.e.
 * ahead of any live event that can follow it.
 *
 * LIMITS (stated, not hidden):
 *  - Events evicted by the caps above are not restorable. Both caps are ≥ the
 *    frontend's own 200-per-panel cap, so a reload still restores a full panel.
 *  - The buffer is per username, so two different XMage accounts are isolated, but
 *    two tabs of the SAME username share one buffer and one seq counter — which is
 *    correct for this client (they share one XMage session and one game).
 *  - A session teardown with no live game drops the entry; the seq counter does
 *    NOT reset (it is process-global), so a re-created entry can never emit a seq
 *    the client has already rendered for a different event.
 *
 * Thread safety: per-entry state is guarded by the instance monitor (events are
 * appended from JBoss callback threads); the registry map is a ConcurrentHashMap
 * and its capacity/refcount bookkeeping is guarded by {@link #REGISTRY_LOCK}.
 */
final class GameHistory {

    /**
     * Log (game-log) events kept per game. The frontend caps the log panel at 200
     * entries; 500 leaves headroom for a log-heavy stretch. ~100 KB per live game
     * at ~200 B/event.
     */
    static final int MAX_LOG_EVENTS = 500;

    /**
     * Chat lines kept per game. Chat is sparse next to game-log output, so it gets
     * its own deque — a single shared window would let log spam evict the chat
     * history a player is actually looking for. 200 matches the frontend's
     * MAX_CHAT_ENTRIES.
     */
    static final int MAX_CHAT_EVENTS = 200;

    /**
     * Registry cap. Worst case 64 × (500 + 200) events ≈ 9 MB, and it means an
     * abandoned game (tab closed mid-game, never reconnected) cannot accumulate
     * without bound. Eviction takes the least-recently-touched entry, preferring
     * one no live session is holding.
     */
    static final int MAX_USERS = 64;

    private static final Object REGISTRY_LOCK = new Object();
    private static final Map<String, GameHistory> REGISTRY = new ConcurrentHashMap<>();

    /**
     * Process-global event sequence. Events from every user/game share one counter
     * so that no two events ever carry the same `seq` — the frontend dedupes and
     * sorts a backfill by seq, and a restarting counter could otherwise make a
     * backfill collide with entries already in the client's store.
     */
    private static final AtomicLong EVENT_SEQ = new AtomicLong();

    /** Game id the buffered events belong to; a change clears the deques. */
    private String gameId;

    private final Deque<Map<String, Object>> logEvents = new ArrayDeque<>();
    private final Deque<Map<String, Object>> chatEvents = new ArrayDeque<>();

    /** Live WebProxyClient count (the session that owns this entry plus its peers). */
    private int refs = 0;

    private long lastTouchedAt = System.currentTimeMillis();

    private GameHistory() {
    }

    // ── Registry ──

    /**
     * Get-or-create the history for a username and take a reference to it.
     * Called once per WebProxyClient (i.e. per WS connection) — see
     * {@link WebProxyClient#WebProxyClient(String)}.
     */
    static GameHistory acquire(String username) {
        if (username == null || username.isEmpty()) {
            // Fail Fast: an anonymous history cannot be keyed, so a rejoin would
            // silently never find it.
            throw new IllegalArgumentException("GameHistory.acquire called with a null/empty username");
        }
        synchronized (REGISTRY_LOCK) {
            GameHistory hist = REGISTRY.get(username);
            if (hist == null) {
                if (REGISTRY.size() >= MAX_USERS) {
                    evictLeastRecentlyTouchedLocked();
                }
                hist = new GameHistory();
                REGISTRY.put(username, hist);
            }
            hist.refs++;
            hist.lastTouchedAt = System.currentTimeMillis();
            return hist;
        }
    }

    /**
     * Drop the entry for a username — the game-log/chat buffer is not needed by
     * any future connection. The two lifecycle hooks that already clear per-game
     * proxy state call this: a decided match (WebProxyClient.handleGameOver) and a
     * session teardown with no live game (WebProxySession.cleanup).
     */
    static void drop(String username) {
        if (username == null) return;
        synchronized (REGISTRY_LOCK) {
            REGISTRY.remove(username);
        }
    }

    /**
     * Release one session's reference to the entry. Drops the entry only when the
     * last referencing session is gone AND that session had no live game to
     * protect ({@code gameLive == false}) — a live game MUST keep its buffer: that
     * is exactly the reload/reconnect case this class exists for, and the XMage
     * session itself is kept alive on the same decision
     * (WebProxySession.cleanup's {@code connectStop(false, gameLive)}).
     */
    static void release(String username, boolean gameLive) {
        if (username == null) return;
        synchronized (REGISTRY_LOCK) {
            GameHistory hist = REGISTRY.get(username);
            if (hist == null) return;
            if (hist.refs > 0) hist.refs--;
            hist.lastTouchedAt = System.currentTimeMillis();
            if (hist.refs == 0 && !gameLive) {
                REGISTRY.remove(username);
            }
        }
    }

    /** Number of live registry entries — for the standalone harness. */
    static int registrySize() {
        return REGISTRY.size();
    }

    /** Remove every entry — for the standalone harness. */
    static void clearRegistry() {
        synchronized (REGISTRY_LOCK) {
            REGISTRY.clear();
        }
    }

    private static void evictLeastRecentlyTouchedLocked() {
        String freeVictim = null;
        long oldestFree = Long.MAX_VALUE;
        String anyVictim = null;
        long oldestAny = Long.MAX_VALUE;
        for (Map.Entry<String, GameHistory> e : REGISTRY.entrySet()) {
            long t = e.getValue().lastTouchedAt;
            if (t < oldestAny) {
                oldestAny = t;
                anyVictim = e.getKey();
            }
            if (e.getValue().refs == 0 && t < oldestFree) {
                oldestFree = t;
                freeVictim = e.getKey();
            }
        }
        String victim = freeVictim != null ? freeVictim : anyVictim;
        if (freeVictim == null && victim != null) {
            // Every entry belongs to a live session. The map must stay bounded, so
            // the oldest is evicted anyway; the only cost is that that user's rejoin
            // history is lost (the client then restores nothing and keeps working).
            System.err.println("[GameHistory] registry full (" + MAX_USERS
                    + " users) — evicting in-use entry for " + victim
                    + "; rejoin history for that user is lost");
        }
        if (victim != null) {
            REGISTRY.remove(victim);
        }
    }

    // ── Per-game buffer ──

    /**
     * Assign the event's `seq` and buffer it, atomically — two callback threads
     * cannot then invert a pair's order in the deque relative to their seq numbers.
     *
     * A null gameId means "not in a game" (room chat): the event still gets a
     * monotonic seq so the client's ordering/dedupe keeps working, but nothing is
     * buffered — a room-chat line must never be replayed into a game's panel.
     *
     * @param isChat true for a chat line (talk/whisper), false for a game-log entry
     */
    synchronized void recordEvent(String gameId, Map<String, Object> event, boolean isChat) {
        event.put("seq", EVENT_SEQ.incrementAndGet());
        lastTouchedAt = System.currentTimeMillis();
        if (gameId == null) {
            return;
        }
        beginGame(gameId);
        Deque<Map<String, Object>> target = isChat ? chatEvents : logEvents;
        int cap = isChat ? MAX_CHAT_EVENTS : MAX_LOG_EVENTS;
        target.addLast(event);
        while (target.size() > cap) {
            target.removeFirst();
        }
    }

    /**
     * Bind the buffer to a game id, clearing it when the game changes (Bo3 game 2,
     * a different table, a new match in the same session). Idempotent for the
     * current game. This is the between-games reset, kept working by construction:
     * the browser gets a fresh (empty) history for a genuinely new game and the
     * log therefore cannot show the previous game's lines.
     */
    synchronized void beginGame(String gameId) {
        if (gameId == null) {
            throw new IllegalArgumentException("GameHistory.beginGame called with a null gameId");
        }
        if (!gameId.equals(this.gameId)) {
            this.gameId = gameId;
            logEvents.clear();
            chatEvents.clear();
        }
    }

    /** Buffered log events for a game, oldest first; empty for a different game. */
    synchronized List<Map<String, Object>> logSnapshot(String gameId) {
        if (gameId == null || !gameId.equals(this.gameId)) {
            return Collections.emptyList();
        }
        return copyOf(logEvents);
    }

    /** Buffered chat lines for a game, oldest first. See {@link #logSnapshot}. */
    synchronized List<Map<String, Object>> chatSnapshot(String gameId) {
        if (gameId == null || !gameId.equals(this.gameId)) {
            return Collections.emptyList();
        }
        return copyOf(chatEvents);
    }

    /** True when anything is buffered for the given game. */
    synchronized boolean hasHistory(String gameId) {
        if (gameId == null || !gameId.equals(this.gameId)) return false;
        return !logEvents.isEmpty() || !chatEvents.isEmpty();
    }

    /**
     * Empty the buffer and unbind the game (match over / game abandoned). The
     * registry entry itself stays until the session is torn down
     * ({@link #release}) or the LRU evicts it, so a still-connected player moving
     * on to a new game keeps writing into the same, now-empty, buffer instead of an
     * orphaned one.
     */
    synchronized void clear() {
        lastTouchedAt = System.currentTimeMillis();
        logEvents.clear();
        chatEvents.clear();
        gameId = null;
    }

    /** The game the buffer currently belongs to (null before the first event). */
    synchronized String currentGameId() {
        return gameId;
    }

    /**
     * Copy the deque into a detached unmodifiable list, so a concurrent append
     * cannot mutate the payload while it is being serialized. The event maps
     * themselves are never mutated after being recorded.
     */
    private static List<Map<String, Object>> copyOf(Deque<Map<String, Object>> deque) {
        List<Map<String, Object>> out = new ArrayList<>(deque.size());
        out.addAll(deque);
        return Collections.unmodifiableList(out);
    }

    /**
     * Build the `history_backfill` payload the browser receives before the
     * rejoin's `game_init` state push. Defined here, next to the buffers, so the
     * wire contract has exactly one definition (WebProxyClient.sendHistoryBackfill
     * only sends this).
     *
     * The field names are the frontend contract
     * (src/types/messages.ts HistoryBackfillMessage + gameplaySlice.mergeHistory):
     * `type`, `gameId`, `events` (log lines), `chat` (chat lines). Each event
     * carries its own `seq`, which is what makes the client-side merge idempotent.
     */
    static Map<String, Object> buildBackfillMessage(String gameId, List<Map<String, Object>> logEvents,
                                                   List<Map<String, Object>> chat) {
        if (gameId == null || gameId.isEmpty()) {
            throw new IllegalArgumentException("history_backfill needs a gameId");
        }
        if (logEvents == null || chat == null) {
            // Fail Fast: the frontend iterates both arrays; a null would surface
            // there as a merge error instead of a clear proxy-side failure.
            throw new IllegalArgumentException("history_backfill needs both event lists (log="
                    + logEvents + ", chat=" + chat + ")");
        }
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "history_backfill");
        msg.put("gameId", gameId);
        msg.put("events", logEvents);
        msg.put("chat", chat);
        return msg;
    }

    /** Snapshot of the registry for the standalone harness: username → [log, chat, refs]. */
    static Map<String, int[]> registryStats() {
        Map<String, int[]> out = new HashMap<>();
        synchronized (REGISTRY_LOCK) {
            for (Map.Entry<String, GameHistory> e : REGISTRY.entrySet()) {
                GameHistory h = e.getValue();
                synchronized (h) {
                    out.put(e.getKey(), new int[] { h.logEvents.size(), h.chatEvents.size(), h.refs });
                }
            }
        }
        return out;
    }
}
