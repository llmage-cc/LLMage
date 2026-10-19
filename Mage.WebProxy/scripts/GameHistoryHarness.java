/*
 * Standalone harness for the proxy-side rejoin history buffer

 *
 * Run with:  bash Mage.WebProxy/scripts/verify-rejoin-history.sh
 *
 * Lives outside src/main/java on purpose: it drives package-private internals
 * (GameHistory) and must never ship inside mage-web-proxy.jar.
 *
 * What is proven here (the frontend half is proven by
 * web/frontend/react/scripts/verify-rejoin-history.sh against the REAL store):
 *  (a) the buffer is bounded (caps hold under overflow, oldest evicted, chat and
 *      log windows are independent so log spam cannot evict chat);
 *  (b) the buffer is scoped to ONE game: a new game id clears it, so a backfill
 *      can never carry another game's lines;
 *  (c) the buffer is per-user and released on teardown — a session with a live
 *      game keeps it (that is the rejoin), a session with no live game drops it,
 *      and a second session for the same user shares the entry;
 *  (d) seq is process-global and strictly increasing, so a backfill can never
 *      collide with a seq the client already rendered (idempotent merge);
 *  (e) the backfill payload is well-formed JSON with the exact field names the
 *      frontend reads (type/gameId/events/chat), and a foreign game id yields
 *      empty snapshots rather than another game's history.
 */
package mage.webproxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class GameHistoryHarness {

    private static int failures = 0;

    private static void check(String name, boolean cond) {
        check(name, cond, "");
    }

    private static void check(String name, boolean cond, String detail) {
        if (cond) {
            System.out.println("  PASS  " + name);
        } else {
            failures++;
            System.out.println("  FAIL  " + name + " " + detail);
        }
    }

    private static void section(String t) {
        System.out.println("\n## " + t);
    }

    private static Map<String, Object> event(String message) {
        Map<String, Object> e = new HashMap<>();
        e.put("type", "action");
        e.put("message", message);
        return e;
    }

    private static Map<String, Object> chat(String from, String message) {
        Map<String, Object> e = new HashMap<>();
        e.put("type", "talk");
        e.put("message", message);
        e.put("from", from);
        return e;
    }

    private static long seqOf(Map<String, Object> e) {
        return ((Number) e.get("seq")).longValue();
    }

    public static void main(String[] args) {
        GameHistory.clearRegistry();

        section("BOUNDS — the log window holds the newest MAX_LOG_EVENTS, oldest first");
        {
            GameHistory h = GameHistory.acquire("bounds-user");
            for (int i = 0; i < GameHistory.MAX_LOG_EVENTS + 200; i++) {
                h.recordEvent("G1", event("line " + i), false);
            }
            List<Map<String, Object>> snap = h.logSnapshot("G1");
            check("cap holds (" + GameHistory.MAX_LOG_EVENTS + ")", snap.size() == GameHistory.MAX_LOG_EVENTS,
                    String.valueOf(snap.size()));
            check("the oldest lines were evicted",
                    "line 200".equals(snap.get(0).get("message")), String.valueOf(snap.get(0).get("message")));
            check("the newest line is the tail",
                    ("line " + (GameHistory.MAX_LOG_EVENTS + 199)).equals(
                            snap.get(snap.size() - 1).get("message")),
                    String.valueOf(snap.get(snap.size() - 1).get("message")));
        }

        section("BOUNDS — the chat window is independent, so log spam cannot evict chat");
        {
            GameHistory h = GameHistory.acquire("chat-user");
            h.recordEvent("G1", chat("QA-B", "a rare chat line"), true);
            for (int i = 0; i < GameHistory.MAX_LOG_EVENTS + 100; i++) {
                h.recordEvent("G1", event("log spam " + i), false);
            }
            List<Map<String, Object>> chat = h.chatSnapshot("G1");
            check("the chat line survived " + (GameHistory.MAX_LOG_EVENTS + 100) + " log events",
                    chat.size() == 1 && "a rare chat line".equals(chat.get(0).get("message")),
                    String.valueOf(chat.size()));
            check("the log window is full, not shared with chat",
                    h.logSnapshot("G1").size() == GameHistory.MAX_LOG_EVENTS);
            // and the chat cap itself
            for (int i = 0; i < GameHistory.MAX_CHAT_EVENTS + 50; i++) {
                h.recordEvent("G1", chat("QA-B", "chat " + i), true);
            }
            List<Map<String, Object>> full = h.chatSnapshot("G1");
            check("chat cap holds (" + GameHistory.MAX_CHAT_EVENTS + ")", full.size() == GameHistory.MAX_CHAT_EVENTS,
                    String.valueOf(full.size()));
            check("chat keeps the newest lines",
                    ("chat " + (GameHistory.MAX_CHAT_EVENTS + 49)).equals(full.get(full.size() - 1).get("message")),
                    String.valueOf(full.get(full.size() - 1).get("message")));
        }

        section("SCOPE — a different game id clears the buffer (no cross-game leakage)");
        {
            GameHistory h = GameHistory.acquire("scope-user");
            h.recordEvent("GAME-1", event("game 1: QA-A wins"), false);
            h.recordEvent("GAME-1", chat("QA-A", "gg"), true);
            h.beginGame("GAME-2");
            check("the log is empty for the new game", h.logSnapshot("GAME-2").isEmpty());
            check("the chat is empty for the new game", h.chatSnapshot("GAME-2").isEmpty());
            check("hasHistory is false for the new game", !h.hasHistory("GAME-2"));
            // The between-games reset must also hold against a snapshot for the OLD id.
            check("a snapshot for the ended game no longer returns its lines",
                    h.logSnapshot("GAME-1").isEmpty() && h.chatSnapshot("GAME-1").isEmpty());
            // A re-announcement of the SAME id keeps the buffer (rejoin).
            h.recordEvent("GAME-2", event("game 2: QA-A plays Forest"), false);
            h.beginGame("GAME-2");
            check("a rejoin of the SAME game keeps its history", h.hasHistory("GAME-2"));
        }

        section("SCOPE — a foreign game id never returns another game's events");
        {
            GameHistory h = GameHistory.acquire("foreign-user");
            h.recordEvent("GAME-A", event("game A line"), false);
            check("snapshot for a game the buffer never saw is empty",
                    h.logSnapshot("GAME-B").isEmpty() && h.chatSnapshot("GAME-B").isEmpty());
        }

        section("LIFETIME — kept for a live game, dropped when the session ends without one");
        {
            GameHistory.clearRegistry();
            GameHistory c1 = GameHistory.acquire("life-user");
            c1.recordEvent("G1", event("live game line"), false);

            // Reconnect: the WS session dies (live game => kept), a new session
            // connects and adopts the SAME buffer.
            GameHistory.release("life-user", true);
            check("entry survives a teardown while a game is live", GameHistory.registrySize() == 1);
            GameHistory c2 = GameHistory.acquire("life-user");
            check("the reconnected session sees the same buffer",
                    c2.logSnapshot("G1").size() == 1 && "live game line".equals(c2.logSnapshot("G1").get(0).get("message")));

            // Two sessions alive at once (a second tab), then both end with no live
            // game: the entry must survive the first release and go on the last.
            GameHistory c3 = GameHistory.acquire("life-user");
            check("a second concurrent session shares the entry (refs=2)",
                    GameHistory.registryStats().get("life-user")[2] == 2);
            GameHistory.release("life-user", false);
            check("entry survives while another session still references it", GameHistory.registrySize() == 1,
                    "refs=" + GameHistory.registryStats().get("life-user")[2]);
            GameHistory.release("life-user", false);
            check("entry is dropped when the last session ends with no live game",
                    GameHistory.registrySize() == 0);
        }

        section("LIFETIME — an abandoned game cannot accumulate forever (registry cap)");
        {
            GameHistory.clearRegistry();
            for (int i = 0; i < GameHistory.MAX_USERS; i++) {
                GameHistory h = GameHistory.acquire("user-" + i);
                h.recordEvent("G1", event("line"), false);
                GameHistory.release("user-" + i, true);   // live game: entry kept
            }
            check("registry is at its cap", GameHistory.registrySize() == GameHistory.MAX_USERS);
            GameHistory h = GameHistory.acquire("one-more");
            GameHistory.release("one-more", true);
            check("a new user evicts an entry instead of growing the map",
                    GameHistory.registrySize() <= GameHistory.MAX_USERS, String.valueOf(GameHistory.registrySize()));
            check("the newest user's buffer is present",
                    GameHistory.registryStats().containsKey("one-more"));
        }

        section("SEQ — process-global, strictly increasing, survives entry eviction");
        {
            GameHistory.clearRegistry();
            GameHistory a = GameHistory.acquire("seq-a");
            a.recordEvent("G1", event("a1"), false);
            a.recordEvent("G1", event("a2"), false);
            long lastA = seqOf(a.logSnapshot("G1").get(1));

            // A different user must never reuse seqs (the client dedupes by seq).
            GameHistory b = GameHistory.acquire("seq-b");
            b.recordEvent("G2", event("b1"), false);
            long firstB = seqOf(b.logSnapshot("G2").get(0));
            check("a different user gets a higher seq", firstB > lastA, lastA + " -> " + firstB);

            // Drop the entry entirely (match end) and re-acquire: seqs must still
            // move forward, so a stale client cannot mistake a new event for an old.
            GameHistory.release("seq-a", false);
            check("entry dropped", GameHistory.registrySize() == 1);
            GameHistory a2 = GameHistory.acquire("seq-a");
            a2.recordEvent("G3", event("a3"), false);
            long afterDrop = seqOf(a2.logSnapshot("G3").get(0));
            check("seq does not restart after an entry is dropped", afterDrop > firstB,
                    firstB + " -> " + afterDrop);

            // Room chat (no game): seq advances, nothing is buffered.
            Map<String, Object> room = chat("QA-B", "lobby talk");
            a2.recordEvent(null, room, true);
            check("a room-chat line still gets a seq", seqOf(room) > afterDrop);
            check("a room-chat line is NOT buffered into a game",
                    a2.logSnapshot("G3").size() == 1 && a2.chatSnapshot("G3").isEmpty());
        }

        section("PAYLOAD — the serialized backfill matches what the frontend reads");
        {
            GameHistory h = GameHistory.acquire("payload-user");
            h.recordEvent("GAME-P", event("QA-A plays Plateau"), false);
            h.recordEvent("GAME-P", chat("QA-B", "nice"), true);

            Map<String, Object> msg = GameHistory.buildBackfillMessage("GAME-P",
                    h.logSnapshot("GAME-P"), h.chatSnapshot("GAME-P"));

            // Serialized exactly as WebProxyClient does (WebProxySession.GSON is a
            // plain new Gson(); same instance shape, no mage-common on the harness
            // classpath).
            String json = new com.google.gson.Gson().toJson(msg);
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            check("type is history_backfill",
                    "history_backfill".equals(parsed.get("type").getAsString()));
            check("gameId is present", "GAME-P".equals(parsed.get("gameId").getAsString()));
            check("events is an array of one", parsed.get("events").isJsonArray()
                    && parsed.getAsJsonArray("events").size() == 1);
            check("chat is an array of one", parsed.get("chat").isJsonArray()
                    && parsed.getAsJsonArray("chat").size() == 1);

            JsonObject e0 = parsed.getAsJsonArray("events").get(0).getAsJsonObject();
            check("a log event carries seq/type/message",
                    e0.has("seq") && e0.get("seq").getAsLong() > 0
                            && "action".equals(e0.get("type").getAsString())
                            && e0.has("message"));
            JsonObject c0 = parsed.getAsJsonArray("chat").get(0).getAsJsonObject();
            check("a chat line carries seq/type/message/from",
                    c0.has("seq") && "talk".equals(c0.get("type").getAsString())
                            && c0.has("message") && "QA-B".equals(c0.get("from").getAsString()));

            // The frontend reads exactly `events` and `chat`; confirm nothing else is
            // needed for a well-formed payload (no extra required fields).
            check("payload has exactly the four contract fields", parsed.size() == 4,
                    parsed.keySet().toString());
        }

        section("FAIL FAST — an unkeyed buffer cannot be created silently");
        {
            boolean threw = false;
            try {
                GameHistory.acquire(null);
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            check("acquire(null) throws instead of creating an unaddressable entry", threw);
            threw = false;
            try {
                GameHistory.acquire("");
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            check("acquire(\"\") throws", threw);
            threw = false;
            try {
                GameHistory h = GameHistory.acquire("fail-user");
                h.beginGame(null);
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            check("beginGame(null) throws", threw);
        }

        section("CONCURRENCY — parallel appends keep the caps and produce unique seqs");
        {
            GameHistory.clearRegistry();
            final GameHistory h = GameHistory.acquire("conc-user");
            final int threads = 8;
            final int perThread = 500;
            Thread[] ts = new Thread[threads];
            final List<Thread> running = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                Thread thread = new Thread(() -> {
                    for (int i = 0; i < perThread; i++) {
                        h.recordEvent("G1", event("t" + tid + "-" + i), false);
                    }
                });
                ts[t] = thread;
            }
            for (Thread t : ts) t.start();
            for (Thread t : ts) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    throw new IllegalStateException("harness interrupted", e);
                }
            }
            List<Map<String, Object>> snap = h.logSnapshot("G1");
            check("cap holds under concurrent appends",
                    snap.size() == GameHistory.MAX_LOG_EVENTS, String.valueOf(snap.size()));
            boolean unique = new java.util.HashSet<>(seqs(snap)).size() == snap.size();
            check("every buffered event has a unique seq", unique);
            boolean sorted = true;
            for (int i = 1; i < snap.size(); i++) {
                if (seqOf(snap.get(i - 1)) >= seqOf(snap.get(i))) { sorted = false; break; }
            }
            check("buffer order matches seq order", sorted);
        }

        GameHistory.clearRegistry();
        System.out.println("\n" + (failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED"));
        System.exit(failures == 0 ? 0 : 1);
    }

    private static List<Long> seqs(List<Map<String, Object>> events) {
        List<Long> out = new ArrayList<>();
        for (Map<String, Object> e : events) out.add(seqOf(e));
        return out;
    }

    private GameHistoryHarness() {
    }
}
