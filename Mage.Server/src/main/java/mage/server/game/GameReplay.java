package mage.server.game;

import mage.game.Game;
import mage.game.GameState;
import mage.game.GameStates;
import mage.server.LLMPlayerHelper;
import mage.server.util.PluginUtil;
import mage.util.CopierObjectInputStream;
import mage.view.LLMConversationView;
import org.apache.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Replay system — loads a saved .game file and walks through GameState snapshots.
 *
 * @author BetaSteward_at_googlemail.com
 */
public class GameReplay {

    private static final Logger logger = Logger.getLogger(GameReplay.class);

    private final GameStates savedGame;
    private final Game game;
    private int stateIndex;
    /** Index of the last state returned by next()/previous(); -1 before any call. */
    private int lastIndex = -1;
    /**
     * Why the last {@link #loadGame} returned null — null when it succeeded. Carried
     * so ReplaySession can surface the real cause (over-budget / incompatible /
     * missing) as a terminal REPLAY_DONE instead of a generic message.
     */
    private String loadError;

    public GameReplay(UUID gameId) {
        this.game = loadGame(gameId);
        if (this.game != null) {
            this.savedGame = game.getGameStates();
        } else {
            this.savedGame = new GameStates();
        }
    }

    public void start() {
        this.stateIndex = 0;
        this.lastIndex = -1;
    }

    /** Total number of states in this saved game — the size of the position track. */
    public int getStateCount() {
        return savedGame.getSize();
    }

    /**
     * Place the cursor so the NEXT {@link #next()} returns {@code targetIndex},
     * for a direct jump to a saved-state index (the web viewer's position
     * control). Returns false when the index is outside the saved range — the
     * caller must fail loudly, not clamp.
     *
     * Frame 0 is reachable through this path: previous() alone cannot step back
     * onto it (its cursor yields null at index 0), so seeking backward past the
     * current position would otherwise strand the first frame.
     */
    public boolean seekTo(int targetIndex) {
        if (targetIndex < 0 || targetIndex >= savedGame.getSize()) {
            return false;
        }
        this.stateIndex = targetIndex;
        return true;
    }

    public GameState next() {
        if (this.stateIndex < savedGame.getSize()) {
            lastIndex = stateIndex;
            return savedGame.get(stateIndex++);
        }
        return null;
    }

    public GameState previous() {
        if (this.stateIndex > 0) {
            // Original cursor semantics: decrement first, then return.
            // Navigation behavior is preserved; only lastIndex tracking is
            // added so getCurrentStateIndex() reports the correct value.
            --stateIndex;
            lastIndex = stateIndex;
            return savedGame.get(stateIndex);
        }
        return null;
    }

    public Game getGame() {
        return this.game;
    }

    /**
     * Returns the index of the state that was last returned by next()/previous().
     * Returns -1 if no state has been returned yet (e.g. before start() or if
     * next()/previous() returned null).
     *
     * Previously this derived the index from stateIndex with off-by-one math
     * that broke after previous() (stateIndex-1 was wrong after decrement).
     * Now we track the actual last-returned index directly.
     */
    public int getCurrentStateIndex() {
        return lastIndex;
    }

    /**
     * Build LLM conversation views for the given state index by looking up
     * replayLLMData from any LLM players in the deserialized game.
     *
     * @param stateIdx the GameState index to look up LLM exchanges for
     * @return list of LLMConversationView (one per LLM player that had an exchange at this state),
     *         or null if no LLM data exists for this state
     */
    public List<LLMConversationView> getLLMDataForState(int stateIdx) {
        if (game == null) return null;
        List<LLMConversationView> result = new ArrayList<>();
        for (mage.players.Player p : game.getPlayers().values()) {
            List<LLMConversationView> views = LLMPlayerHelper.buildReplayViews(p, stateIdx);
            if (views != null) {
                result.addAll(views);
            }
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * Build the LLM bucket map for the state-index range {@code (fromExclusive, toInclusive]},
     * for the web viewer's jumped-range backfill.
     *
     * The map is a TreeMap keyed by the TRUE state index of each bucket (ascending
     * order is part of the wire contract), containing only indices that actually
     * carry a bucket — empty indices are simply absent, exactly like the client's
     * per-index store. Returns null when the range is empty or nothing was recorded.
     *
     * @param fromExclusive the last state index the viewer already has (-1 before the first frame)
     * @param toInclusive   the state index being delivered now
     */
    public Map<Integer, List<LLMConversationView>> getLLMDataForRange(int fromExclusive, int toInclusive) {
        if (game == null || toInclusive <= fromExclusive) return null;
        Map<Integer, List<LLMConversationView>> result = new TreeMap<>();
        for (int idx = Math.max(0, fromExclusive + 1); idx <= toInclusive; idx++) {
            List<LLMConversationView> views = getLLMDataForState(idx);
            if (views != null) {
                result.put(idx, views);
            }
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * Does the replay's game have at least one LLM player seat? Uses the same
     * classloader-safe reflection check as {@link #getLLMDataForState}
     * (LLMPlayerHelper.isLLMPlayer) — never instanceof, which is always false
     * across the plugin classloader boundary.
     */
    public boolean hasLlmPlayers() {
        if (game == null) return false;
        for (mage.players.Player p : game.getPlayers().values()) {
            if (LLMPlayerHelper.isLLMPlayer(p)) {
                return true;
            }
        }
        return false;
    }

    private Game loadGame(UUID gameId) {
        // Bound the decoded size BEFORE the object graph is materialised, so an
        // over-budget save fails fast with a catchable exception instead of OOMing
        // the CALL thread (see ReplaySizeLimitInputStream).
        try (InputStream file = Files.newInputStream(Paths.get("saved/" + gameId.toString() + ".game"));
             InputStream buffer = new BufferedInputStream(file);
             InputStream codec = ReplayCodec.wrapForRead(buffer);
             InputStream bounded = new ReplaySizeLimitInputStream(codec);
             ObjectInput input = new CopierObjectInputStream(PluginUtil.classLoader, bounded)) {
            Game loadGame = (Game) input.readObject();
            GameStates states = (GameStates) input.readObject();
            loadGame.loadGameStates(states);
            // Repopulate gameCards/meldCards from the loaded states so the
            // replay can look up any card by id (CardView NPEs otherwise). This
            // is the complete fix proven on 1672/1672 frames — the
            // readObject reinit alone only got an empty map.
            loadGame.rebuildGameCardsFromStates();
            return loadGame;
        } catch (ClassNotFoundException e) {
            loadError = "This replay can't be opened: it was saved by an incompatible build.";
            logger.fatal("Cannot load game. Class not found.", e);
        } catch (IOException e) {
            // An over-budget save is refused here as a typed, catchable exception
            // (never an OOM). Distinguish it from a genuinely missing/corrupt file
            // so the client can show the real reason.
            ReplaySizeLimitException limit = ReplaySizeLimitException.unwrap(e);
            if (limit != null) {
                loadError = limit.getMessage();
                logger.error("Replay " + gameId + " not loaded — " + limit.getMessage());
            } else {
                loadError = "Replay file not found or corrupt";
                logger.fatal("Cannot load game:" + gameId, e);
            }
        }
        return null;
    }

    /**
     * Why the last load returned null — the over-budget message, an incompatibility
     * message, or the generic "not found or corrupt". Null when the load succeeded.
     */
    public String getLoadError() {
        return loadError;
    }
}
