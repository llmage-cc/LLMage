package mage.server.game;

import mage.game.Game;
import mage.game.GameState;
import mage.interfaces.callback.ClientCallback;
import mage.interfaces.callback.ClientCallbackMethod;
import mage.server.managers.ManagerFactory;
import mage.view.GameView;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * @author BetaSteward_at_googlemail.com
 */
public class ReplaySession implements GameCallback {

    private final ManagerFactory managerFactory;
    private final GameReplay replay;
    protected final UUID userId;
    /** The game this replay is for; retained so failure paths never deref a null Game. */
    private final UUID gameId;
    /** Wall-clock creation time, used by ReplayManagerImpl.cleanupStaleSessions. */
    private final long createdAtMs = System.currentTimeMillis();
    /**
     * Index of the last state DELIVERED to this viewer, or -1 before the first
     * frame. Drives the skipped-range LLM backfill: a forward jump (replay_skip
     * / replay_seek) delivers ONE frame, so the buckets of the indices in
     * between must ride along or the viewer's cumulative history misses them

     */
    private int lastDeliveredIndex = -1;

    /**
     * Does the loaded replay's game have at least one LLM player seat?
     * Computed once (lazy) from the deserialized game, sent on every frame as
     * the additive `replayHasLlmPlayers` field. Frame-independent: the
     * per-frame `llmDebug`/`hasLlmPlayers` cannot tell "this game has no LLM"
     * from "this frame carries no exchange", which is exactly the distinction
     * the viewer's empty-state copy needs. Null until computed.
     */
    private Boolean gameHasLlmPlayers = null;

    ReplaySession(ManagerFactory managerFactory, UUID gameId, UUID userId) {
        this.managerFactory = managerFactory;
        this.replay = new GameReplay(gameId);
        this.gameId = gameId;
        this.userId = userId;
    }

    public void replay() {
        if (replay.getGame() == null) {
            // The load failed — a missing/corrupt file, or an over-budget save the
            // size guard refused (so the CALL thread never OOMs). Surface the real
            // reason as a terminal REPLAY_DONE: the web renders it as an error and
            // leaves the viewer; the caller's own session is untouched.
            String reason = replay.getLoadError();
            managerFactory.userManager().getUser(userId).ifPresent(user ->
                    user.fireCallback(new ClientCallback(ClientCallbackMethod.REPLAY_DONE, gameId,
                            reason != null ? reason : "Replay file not found or corrupt")));
            return;
        }
        replay.start();
        lastDeliveredIndex = -1;
        GameState state = replay.next();
        GameView view = new GameView(state, replay.getGame(), null, null);
        attachLLMData(view, replay.getCurrentStateIndex());
        // True position for the web viewer's frame indicator (desktop ignores it).
        view.setReplayStateIndex(replay.getCurrentStateIndex());
        // Track length, for the web viewer's position control (desktop ignores it).
        view.setReplayStateCount(replay.getStateCount());
        // Frame-independent LLM-presence flag (see gameHasLlmPlayers).
        view.setReplayHasLlmPlayers(hasLlmPlayers());
        // Flat game log for the frame (same fields every REPLAY_UPDATE carries).
        attachGameLog(view, replay.getGame(), state);
        managerFactory.userManager().getUser(userId).ifPresent(user ->
                user.fireCallback(new ClientCallback(ClientCallbackMethod.REPLAY_INIT, replay.getGame().getId(), view)));
        lastDeliveredIndex = replay.getCurrentStateIndex();

    }

    public void stop() {
        endGameWithResult("stopped replay");
    }

    public synchronized void next() {
        updateGame(replay.next(), replay.getGame());
    }

    public synchronized void next(int moves) {
        GameState state = null;
        for (int i = 0; i < moves; i++) {
            state = replay.next();
            if (state == null) break;
        }
        updateGame(state, replay.getGame());
    }

    public synchronized void previous() {
        updateGame(replay.previous(), replay.getGame());
    }

    /**
     * Jump straight to a saved-state index (0-based), for the web viewer's
     * position control. Frame 0 IS reachable here — unlike previous(), which
     * cannot step back onto the first frame (its cursor yields null there) —
     * so a viewer that seeks backward keeps every frame available.
     *
     * The seek moves the cursor directly (O(1)), so a drag of the position
     * control costs the same as a single Next.
     */
    public synchronized void seek(int index) {
        if (!replay.seekTo(index)) {
            throw new IllegalArgumentException(
                    "seek(" + index + ") outside the saved range 0.." + (replay.getStateCount() - 1));
        }
        // next() returns the state the cursor now points at and records it as
        // the current index, so the viewer's position indicator stays exact.
        updateGame(replay.next(), replay.getGame());
    }

    /** Returns wall-clock creation time, for stale-session cleanup. */
    public long getCreatedAtMs() {
        return createdAtMs;
    }

    @Override
    public void endGameWithResult(final String result) {
        // Use the retained gameId (not replay.getGame().getId()) so a session whose
        // load failed (null game) can still be ended and removed without an NPE.
        managerFactory.userManager().getUser(userId).ifPresent(user ->
                user.fireCallback(new ClientCallback(ClientCallbackMethod.REPLAY_DONE, gameId, result)));

        managerFactory.replayManager().endReplay(gameId, userId);
    }

    private void updateGame(final GameState state, Game game) {
        if (state == null) {
            // Reached the beginning or end of the replay — notify client but keep session alive
            // so the user can still go in the other direction
            managerFactory.userManager().getUser(userId).ifPresent(user ->
                    user.fireCallback(new ClientCallback(ClientCallbackMethod.REPLAY_DONE, replay.getGame().getId(), "end of replay")));
        } else {
            GameView view = new GameView(state, game, null, null);
            int idx = replay.getCurrentStateIndex();
            attachLLMData(view, idx);
            // True position for the web viewer's frame indicator (desktop ignores it).
            view.setReplayStateIndex(idx);
            // Track length, for the web viewer's position control (desktop ignores it).
            view.setReplayStateCount(replay.getStateCount());
            // Frame-independent LLM-presence flag (see gameHasLlmPlayers).
            view.setReplayHasLlmPlayers(hasLlmPlayers());
            // Skipped-range backfill: a forward jump (skip/seek) delivers ONE
            // frame; carry the buckets of the indices the viewer never saw so
            // its cumulative history stays complete (bug 2). The range EXCLUDES
            // the delivered index itself — attachLLMData above already puts that
            // index's bucket into llmDebug (and when it has none, there is
            // nothing to exclude). Backward jumps need nothing: the client's
            // cursor filter drops buckets above the cursor and re-visiting an
            // index re-delivers its bucket anyway.
            if (idx > lastDeliveredIndex + 1) {
                java.util.Map<Integer, java.util.List<mage.view.LLMConversationView>> skipped =
                        replay.getLLMDataForRange(lastDeliveredIndex, idx - 1);
                if (skipped != null) {
                    view.setReplayLLMBuckets(skipped);
                }
            }
            // Flat game log for the frame (same fields every REPLAY_UPDATE carries).
            attachGameLog(view, game, state);
            managerFactory.userManager().getUser(userId).ifPresent(user ->
                    user.fireCallback(new ClientCallback(ClientCallbackMethod.REPLAY_UPDATE, replay.getGame().getId(), view)));
            lastDeliveredIndex = idx;
        }
    }

    /** Frame-independent "this game has an LLM seat" flag (lazy, cached). */
    private Boolean hasLlmPlayers() {
        if (gameHasLlmPlayers == null) {
            gameHasLlmPlayers = replay.hasLlmPlayers();
        }
        return gameHasLlmPlayers;
    }

    /**
     * Flat game-log delivery:
     * send the saved game's FINAL log with every frame plus the
     * entry count as of THIS frame; the viewer renders entries 0..count-1.
     * This is the whole reason the log is not serialized per frame — measured
     * +0.10% (flat) vs +2.46% (per-frame) on the 34.7 MB outlier.
     *
     * The count is not simply the snapshot's own count: after a turn rollback
     * the log was truncated and re-played (GameState.truncateGameLog), so frames
     * taken before a truncation are capped at the smallest later keep-target —
     * entries below it are provably the same lines in the final log. Frames
     * still capped keep replaying correctly; nothing is fabricated.
     *
     * Old saves (no log captured) and mid-game resets (Karn) carry nothing —
     * the viewer hides the tab for those rather than showing wrong lines.
     */
    private void attachGameLog(GameView view, Game game, GameState state) {
        List<String> log = game.getState().getGameLog();
        if (log == null || log.isEmpty()) {
            // Nothing captured for this save (written before the capture work) —
            // the viewer keeps the tab hidden rather than showing an empty panel.
            return;
        }
        view.setReplayGameLog(new ArrayList<>(log));
        view.setReplayGameLogCount(
                state.getGameLogSafeSlice(state.getGameLogTruncateEpoch(), state.getGameLogCount()));
    }

    /**
     * Attach LLM conversation data for the current state index to the GameView,
     * so the client-side debug window can show the LLM's reasoning during replay.
     */
    private void attachLLMData(GameView view, int stateIdx) {
        // Replays always show the full LLM record to anyone — the match is over,
        // so nothing is live to leak. llmChatPublic on the view tells the proxy
        // serializer to pass all entries through uncensored.
        view.setLlmChatPublic(true);
        java.util.List<mage.view.LLMConversationView> llmData = replay.getLLMDataForState(stateIdx);
        if (llmData != null && !llmData.isEmpty()) {
            view.setLLMDebug(llmData);
        }
    }

}
