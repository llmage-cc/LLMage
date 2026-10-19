package mage.webproxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-game relay for player-chosen token art.
 *
 * <h2>What is shared and what is not</h2>
 * A player's choice of art for a token type is broadcast to the other players at
 * that table, so an opponent's Saproling can be shown with the art its controller
 * picked. Choices are scoped to a game: they are not written to the server or to
 * player accounts (the server has no per-user settings surface for this, and adding
 * one would be a core-engine change). The viewer-side counterpart — "show the
 * opponent's tokens as MY preferred art", including overriding a choice the opponent
 * made — is deliberately NOT relayed: it is a display preference of the viewer and
 * lives in the browser (see the frontend's tokenArt store slice).
 *
 * <h2>Lifecycle</h2>
 * Sessions register here while open and deregister on cleanup. Choices for a game
 * are dropped when the last session in that game deregisters, so a long-lived proxy
 * does not accumulate state for finished games.
 *
 * Fail Fast: a preference for an unknown/empty URL is rejected by the handler rather
 * than stored, so a client bug cannot put a broken URL into everyone's board.
 */
final class TokenArtRelay {

    private static final org.apache.log4j.Logger logger =
            org.apache.log4j.Logger.getLogger(TokenArtRelay.class);

    /** Cap per game — 8 seats × a generous number of distinct tokens. */
    private static final int MAX_CHOICES_PER_GAME = 4096;

    /** One player's choice for one token name. */
    static final class Choice {
        final String url;
        final String setCode;
        final String collectorNumber;
        final String playerName;

        Choice(String url, String setCode, String collectorNumber, String playerName) {
            this.url = url;
            this.setCode = setCode;
            this.collectorNumber = collectorNumber;
            this.playerName = playerName;
        }
    }

    /** gameId → (playerId → (tokenName → choice)). */
    private static final Map<UUID, Map<UUID, Map<String, Choice>>> byGame = new ConcurrentHashMap<>();

    private TokenArtRelay() {
    }

    /**
     * Record a player's choice for a token and return the game's full snapshot.
     *
     * @return every choice in the game after this update (empty list when the game
     *         has no choices)
     */
    static List<Map<String, Object>> record(UUID gameId, UUID playerId, String playerName,
                                            String tokenName, String url,
                                            String setCode, String collectorNumber) {
        if (gameId == null || playerId == null) {
            return Collections.emptyList();
        }
        Map<UUID, Map<String, Choice>> players = byGame.get(gameId);
        if (players == null) {
            players = new ConcurrentHashMap<>();
            byGame.put(gameId, players);
        }
        Map<String, Choice> tokens = players.get(playerId);
        if (tokens == null) {
            tokens = new ConcurrentHashMap<>();
            players.put(playerId, tokens);
        }
        int total = 0;
        for (Map<String, Choice> t : players.values()) total += t.size();
        if (total >= MAX_CHOICES_PER_GAME) {
            logger.warn("token art preferences for game " + gameId
                    + " hit the cap (" + MAX_CHOICES_PER_GAME + ") — dropping " + tokenName);
            return snapshot(gameId);
        }
        tokens.put(tokenName, new Choice(url, setCode, collectorNumber, playerName));
        return snapshot(gameId);
    }

    /** The game's current choices as JSON-ready maps. */
    static List<Map<String, Object>> snapshot(UUID gameId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (gameId == null) return out;
        Map<UUID, Map<String, Choice>> players = byGame.get(gameId);
        if (players == null) return out;
        for (Map.Entry<UUID, Map<String, Choice>> p : players.entrySet()) {
            for (Map.Entry<String, Choice> t : p.getValue().entrySet()) {
                Choice c = t.getValue();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("playerId", p.getKey().toString());
                row.put("tokenName", t.getKey());
                row.put("url", c.url);
                if (c.setCode != null) row.put("setCode", c.setCode);
                if (c.collectorNumber != null) row.put("collectorNumber", c.collectorNumber);
                if (c.playerName != null) row.put("playerName", c.playerName);
                out.add(row);
            }
        }
        return out;
    }

    /** Remove one player's choice (client asked to go back to the engine default). */
    static List<Map<String, Object>> clear(UUID gameId, UUID playerId, String tokenName) {
        if (gameId == null || playerId == null) return Collections.emptyList();
        Map<UUID, Map<String, Choice>> players = byGame.get(gameId);
        if (players == null) return Collections.emptyList();
        Map<String, Choice> tokens = players.get(playerId);
        if (tokens != null) tokens.remove(tokenName);
        return snapshot(gameId);
    }

    /** Drop a player's choices for a game (on disconnect). */
    static void forgetPlayer(UUID gameId, UUID playerId) {
        if (gameId == null || playerId == null) return;
        Map<UUID, Map<String, Choice>> players = byGame.get(gameId);
        if (players == null) return;
        players.remove(playerId);
        if (players.isEmpty()) byGame.remove(gameId);
    }

    /** Test hook / diagnostics: how many games hold state. */
    static int trackedGameCount() {
        return byGame.size();
    }

    /** Snapshot helper used by tests. */
    static Map<UUID, Map<UUID, Map<String, Choice>>> raw() {
        return new HashMap<>(byGame);
    }
}
