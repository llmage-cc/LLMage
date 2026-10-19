package mage.server.game;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import mage.game.Game;
import mage.game.Table;
import mage.game.match.Match;
import mage.game.match.MatchPlayer;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes {@code saved/<gameId>.json} — the searchable metadata sidecar for the
 * web client's replay browser.
 *
 * WHY A SIDECAR: the web proxy lists the server's saved/ directory and can read
 * its files, but a .game save is a multi-megabyte Java-serialized object graph
 * that only the SERVER can deserialize (doing so per listed row would take
 * seconds each). The browser therefore cannot extract player names, deck names,
 * or the result from the saves it lists — so the metadata is captured at save
 * time, while the match and its decks are still in memory, into a plain JSON
 * file the proxy can serve and the frontend can search/sort.
 *
 * The serialized Game itself does NOT carry MatchPlayer.getDeck() (out of scope
 * of what saveGame writes), so deck names would be unrecoverable after a server
 * restart — this is the only point they can be recorded.
 *
 * LIFECYCLE: saveGame runs twice per game (endGameWithResult, then again after
 * match.endGame() in endGameAndStartNextGame — where the win/draw bookkeeping
 * is final), so the LAST write carries the final scores. Writes are atomic
 * (tmp + rename) so a listing never sees a torn JSON.
 *
 * FAILURE POLICY (deliberate, not a silent fallback): a sidecar failure is
 * logged and swallowed — the .game replay is the primary artifact and is
 * already on disk at this point; failing the save call over a missing
 * convenience index would corrupt the thing this class exists to decorate.
 *
 * Tournament sub-table saves pass a null table (no reachable Match), so their
 * sidecars carry only game-level fields; their browser rows still get
 * names/dates from MatchView (finished-matches list) instead.
 */
public final class ReplayMetadata {

    private static final Logger logger = Logger.getLogger(ReplayMetadata.class);

    /** Same directory GameReplay.loadGame / GameController.saveGame use. */
    private static final String SAVED_DIR = "saved";

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private ReplayMetadata() {
    }

    /**
     * Write the sidecar for a game that was just saved. Called from
     * GameController.saveGame AFTER the .game write succeeded — a sidecar
     * without its .game would be a phantom row.
     *
     * @param game  the ended game (never null)
     * @param table the game's table (carries the match with players + decks);
     *              null when not reachable — the match block is then omitted
     */
    public static void write(Game game, Table table) {
        if (game == null || game.getId() == null) {
            throw new IllegalArgumentException("ReplayMetadata.write called with null game/gameId");
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("gameId", game.getId().toString());
        meta.put("startTime", iso(game.getStartTime()));
        meta.put("endTime", iso(game.getEndTime()));
        meta.put("winner", game.hasEnded() ? game.getWinner() : null);
        meta.put("turns", game.getState() != null ? game.getState().getTurnNum() : null);
        meta.put("savedAt", iso(new Date()));

        Match match = table != null ? table.getMatch() : null;
        if (table != null && match != null) {
            meta.put("tableId", table.getId() != null ? table.getId().toString() : null);
            meta.put("tableName", table.getName());
            meta.put("gameType", match.getOptions() != null ? match.getOptions().getGameType() : null);
            meta.put("deckType", match.getOptions() != null ? match.getOptions().getDeckType() : null);
            List<Map<String, Object>> players = new ArrayList<>();
            for (MatchPlayer mp : match.getPlayers()) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("name", mp.getName());
                p.put("wins", mp.getWins());
                p.put("quit", mp.hasQuit());
                p.put("deck", mp.getDeck() != null ? mp.getDeck().getName() : null);
                players.add(p);
            }
            meta.put("players", players);
        }

        File out = sidecarFile(game.getId());
        File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
        try {
            out.getParentFile().mkdirs();
            try (OutputStream os = new FileOutputStream(tmp);
                 Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
                w.write(GSON.toJson(meta));
            }
            if (!tmp.renameTo(out)) {
                throw new IOException("rename " + tmp + " -> " + out + " returned false");
            }
        } catch (IOException e) {
            logger.error("ReplayMetadata: cannot write " + out
                    + " — the replay browser row will show legacy data", e);
        }
    }

    /** Sidecar path for a saved game: saved/<gameId>.json. */
    public static File sidecarFile(UUID gameId) {
        return new File(SAVED_DIR, gameId.toString() + ".json");
    }

    private static String iso(Date d) {
        return d == null ? null : java.time.Instant.ofEpochMilli(d.getTime()).toString();
    }
}
