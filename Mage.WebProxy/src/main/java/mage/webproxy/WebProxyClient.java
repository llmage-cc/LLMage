package mage.webproxy;

import mage.choices.Choice;
import mage.constants.Constants;
import mage.interfaces.MageClient;
import mage.interfaces.callback.ClientCallback;
import mage.interfaces.callback.ClientCallbackMethod;
import mage.remote.Session;
import mage.utils.MageVersion;
import mage.game.events.PlayerQueryEvent;
import mage.players.PlayableObjectStats;
import mage.players.PlayableObjectsList;
import mage.view.AbilityPickerView;
import mage.view.AbilityView;
import mage.view.CardView;
import mage.view.CommandObjectView;
import mage.view.CommanderView;
import mage.view.GameClientMessage;
import mage.view.GameView;
import mage.view.PermanentView;
import mage.view.PlayerView;
import mage.view.SeatView;
import mage.view.TableClientMessage;
import mage.view.TableView;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import org.apache.log4j.Logger;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * MageClient implementation for the web proxy.
 *
 * Receives XMage callbacks, converts them to JSON messages, and pushes them
 * to the browser via a WebSocket sender callback.
 *
 * Pattern follows BridgeMageClient from mage-bench, but instead of forwarding
 * to an MCP callback handler, we serialize to JSON and send over WebSocket.
 */
public class WebProxyClient implements MageClient {

    private static final Logger logger = Logger.getLogger(WebProxyClient.class);
    private static final MageVersion version = new MageVersion(WebProxyClient.class);

    private final String username;
    private volatile Session session;
    private volatile BrowserSender wsSender;

    /**
     * Callback used to push JSON messages to the browser WebSocket.
     *
     * Returns true only when the message was handed to a live, open socket.
     * State versioning relies on this return value: a message dropped because
     * the socket is closed must not consume a state version, otherwise the
     * client sees a permanent version gap and answers with a full resync on
     * most updates.
     */
    @FunctionalInterface
    public interface BrowserSender {
        boolean send(String json);
    }

    // Event sequencing for the browser game log (chat, informs, log events) lives
    // in the shared GameHistory entry (keyed by username) so that it — and the
    // buffered events themselves — survive the new WebProxyClient/WebProxySession
    // that a WS reconnect constructs. See GameHistory for the rejoin-backfill
    // design.
    // State version sequencing — incremented ONLY when game state is sent to the
    // browser. The frontend uses this to detect lost state updates; mixing it with
    // eventSeq (which also counts chat/inform events) caused false resync gaps.
    private long stateSeq = 0;

    /**
     * Bounded per-game buffer of the log/chat events already sent to a browser,
     * replayed on rejoin. Shared by username so it survives the reconnect that
     * replaces this client object.
     */
    private final GameHistory history;

    /**
     * Highest server callback messageId whose GameView has been applied to the
     * browser's state channel (0 = none seen yet). The XMage server stamps this
     * id in emission order ({@code Session.fireCallback}) and the desktop client
     * drops callbacks that arrive below the newest one seen ("events can income
     * in wrong order, so outdated data must be ignored" —
     * {@code CallbackClientImpl.onCallback}); the proxy relays every GameView in
     * ARRIVAL order and stamps its own monotonic {@code stateSeq}, so an
     * inverted callback used to reach the browser as an older turn/phase under a
     * NEWER version — invisible to the frontend's forward-gap-only check.
     * Measured live (the earlier test evidence): 43 content regressions in 2,567
     * spectator state frames, 43/43 explained by messageId inversion arriving at
     * the proxy; stateSeq was strictly monotonic on the wire throughout.
     */
    private volatile int lastStateMessageId = 0;

    // Pending game state for serialization
    private volatile GameView lastGameView;
    private volatile UUID myPlayerId;
    private volatile UUID currentGameId;

    /**
     * Replay channel state (REPLAY_* callbacks). Deliberately SEPARATE from
     * currentGameId: a replay game id must never leak into live-game routing
     * (chat channel selection, rejoin checks, stop-watching). Set by the
     * REPLAY_GAME callback (the server's acknowledgment of replayInit), cleared
     * when the replay session ends.
     */
    private volatile UUID replayGameId;
    /**
     * True when this session sent a `replay_stop` for {@link #replayGameId}. The
     * server answers EVERY stop with REPLAY_DONE, and its payload is the free-form
     * result string; this flag is how the proxy distinguishes "the user stopped the
     * replay" from "end of replay" (a boundary: the ReplaySession stays alive) and
     * from the file-missing error, so the browser does not have to string-match.
     */
    private volatile boolean replayStopRequested;
    /** Last replay GameView sent — used by resendLatestGameState during a replay. */
    private volatile GameView lastReplayView;
    /** State version counter for the replay channel — independent of stateSeq, which
     *  belongs to the live state channel (frontend gap detection watches that one). */
    private final java.util.concurrent.atomic.AtomicLong replaySeq = new java.util.concurrent.atomic.AtomicLong(0);

    // Cached previous state map for delta computation (null = send full state next time)
    private volatile Map<String, Object> lastStateMap;
    // Whether the next send should be a full state (game_init, resync, or first update)
    private volatile boolean forceFullState = true;

    // Pending action choices (for GAME_SELECT — the UUIDs that map to each choice index)
    private volatile List<Object> pendingChoiceBackings;
    private volatile ClientCallbackMethod pendingActionType;
    // Whether the pending GAME_TARGET is required (can't be cancelled)
    private volatile boolean pendingTargetRequired;

    // play_card attempt tracking: set when the session sends play_card;
    // consumed by handleGameSelect to detect a no-op server response.
    private volatile UUID lastPlayCardAttemptId;
    // Last GAME_SELECT prompt (message + game cycle), for that comparison.
    private volatile String lastSelectPromptMessage;
    private volatile int lastSelectGameCycle = -1;

    // Table list cache (updated on JOINED_TABLE / table queries)
    private volatile Collection<TableView> lastTableList;

    // Idempotency gate for the START_GAME join+chat work (see StartGameJoinGate).
    private final StartGameJoinGate startGameJoinGate = new StartGameJoinGate();

    public WebProxyClient(String username) {
        this.username = username;
        this.history = GameHistory.acquire(username);
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public void setWsSender(BrowserSender sender) {
        this.wsSender = sender;
    }

    public Session getSession() {
        return session;
    }

    public String getUsername() {
        return username;
    }

    public UUID getCurrentGameId() {
        return currentGameId;
    }

    /**
     * Record attachment to a live game at the earliest moment the proxy knows it:
     * the successful rejoin (WebProxySession.handleRejoinGame), NOT the server's
     * re-sent GAME_INIT. That callback is held client-side until the connect flow
     * completes (measured ~2 s in the F-REJOIN-2 repro), and the WS-close
     * keep-alive decision in WebProxySession.cleanup() reads this field. A close
     * inside that window with a still-null id disconnects HARD
     * (keepSession=false): the server sees DisconnectedByUser, removes the seat
     * and concedes the match, and every later reconnect finds no rejoinable game
     * — only lobby frames, forever (F-REJOIN-2, two-browser repro 2026-09-27).
     */
    public void setCurrentGameId(UUID gameId) {
        currentGameId = gameId;
    }

    /**
     * Forget the current live game id. Called when the session stops watching a
     * game: currentGameId is otherwise never cleared on that path, and a stale
     * value misroutes room chat into a dead game chat AND makes the replay guard
     * (replay_load refuses while a live game is attached) permanently reject
     * replay loads in that session.
     */
    public void clearCurrentGameId() {
        currentGameId = null;
    }

    // ── Replay channel accessors ──

    /** The game id of the replay session this client is driving, or null. */
    public UUID getReplayGameId() {
        return replayGameId;
    }

    /** Note that a `replay_stop` was requested — see {@link #replayStopRequested}. */
    public void markReplayStopRequested() {
        replayStopRequested = true;
    }

    /** Clear all replay-channel state (after REPLAY_DONE, or on a fresh replay_init). */
    public void clearReplayState() {
        replayGameId = null;
        replayStopRequested = false;
        lastReplayView = null;
    }

    /**
     * Listener fired when the replay session ENDS server-side (REPLAY_DONE with
     * reason 'stopped' or 'error' — the boundary case keeps the session alive).
     * The owning WebProxySession uses it to drop its spectator flag, which was
     * raised by replay_load: a replay whose file turns out to be missing ends
     * asynchronously, so the flag cannot be reset from the request handler.
     */
    @FunctionalInterface
    public interface ReplayEndListener {
        void onReplayEnded();
    }

    private volatile ReplayEndListener replayEndListener;

    public void setReplayEndListener(ReplayEndListener listener) {
        this.replayEndListener = listener;
    }

    /**
     * Total states (track length) of the loaded replay, or -1 when no frame has
     * arrived yet. Read from the last replay frame the proxy sent, so the seek
     * handler can range-check an index BEFORE the RPC: an out-of-range seek
     * throws inside the server's CALL thread, which logs the trace but answers
     * the client with nothing — the browser would hang instead of seeing an
     * error (measured 2026-09-21, seek(265) of a 265-state replay).
     */
    public int getReplayStateCount() {
        GameView view = lastReplayView;
        return view != null ? view.getReplayStateCount() : -1;
    }

    /**
     * Re-send the last known game state to the browser. Used by the resync
     * flow when the client detects a state version gap or the tab regains
     * visibility after being backgrounded.
     *
     * While a REPLAY is loaded this resends the last REPLAY frame instead — the
     * browser is showing the replay board, and resending a stale live-game state
     * would swap the viewer's board out from under it (the tab-visibility
     * recovery in websocket.ts fires request_resync on every focus).
     */
    public void resendLatestGameState() {
        GameView replayView = lastReplayView;
        if (replayView != null) {
            logger.info("[" + username + "] Resync requested during replay " + replayGameId
                    + " — resending last replay frame (turn=" + replayView.getTurn() + ")");
            sendReplayState(replayView, replayGameId, "replay_update");
            return;
        }
        GameView gv = lastGameView;
        if (gv == null) {
            logger.info("[" + username + "] Resync requested but no cached GameView — nothing to resend");
            return;
        }
        logger.info("[" + username + "] Resending latest game state for resync (turn=" + gv.getTurn() + ")");
        forceFullState = true;  // ensure delta logic sends full state, not a diff
        sendGameStateToBrowser(gv, currentGameId, "game_update");
    }

    public UUID getPlayerId() {
        return myPlayerId;
    }

    /**
     * Serialize and send a replay GameView to the browser as `replay_init` /
     * `replay_update` (REPLAY_INIT / REPLAY_UPDATE callbacks).
     *
     * Uses the SAME {@link GameStateSerializer#buildStateMap} the live channel
     * uses — the replay GameView is constructed by ReplaySession exactly like a
     * live one (GameView(state, game, null, null)), so `myPlayerId` is null and
     * the board renders in the spectator shape: no hidden hands, no "me".
     * The state is sent FULL every time (no delta): replay navigation is
     * non-monotonic (previous/skip backwards) and the frontend viewer is
     * read-only, so the delta machinery buys nothing and would need its own
     * cache. `lastStateMap` / `stateSeq` are deliberately NOT touched — they
     * belong to the live state channel.
     */
    public void sendReplayState(GameView gameView, UUID gameId, String type) {
        if (gameView == null) {
            // Fail Fast: callers must not hand us a null GameView.
            throw new IllegalArgumentException("sendReplayState called with null GameView (type=" + type + ")");
        }
        lastReplayView = gameView;
        long version = replaySeq.incrementAndGet();
        // myPlayerId = null: the replay GameView was built for no particular player.
        Map<String, Object> stateMap = GameStateSerializer.buildStateMap(gameView, null, version);
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("type", type);
        envelope.put("gameId", gameId != null ? gameId.toString() : null);
        // True replay position (state index in the saved game) — lets the viewer
        // show a real frame indicator and step back correctly (2026-09-20).
        envelope.put("replayStateIndex", gameView.getReplayStateIndex());
        // Track length — sizes the viewer's position control (2026-09-21).
        envelope.put("replayStateCount", gameView.getReplayStateCount());
        envelope.put("state", stateMap);
        sendToBrowser(gsonToJson(envelope));
    }

    public List<Object> getPendingChoiceBackings() {
        return pendingChoiceBackings;
    }

    /**
     * Record an outgoing play_card attempt (from WebProxySession.handlePlayCard).
     * handleGameSelect keeps the current prompt snapshot (lastSelectPromptMessage /
     * lastSelectGameCycle) on every GAME_SELECT; when the NEXT GAME_SELECT arrives
     * with the same message within a few game cycles while an attempt is pending,
     * the server no-op'd the play (nothing was castable) — the browser gets a
     * visible error instead of silence. The cycle delta must be ≥1 (never
     * == 0): gameCycle is applyEffectsCounter and applyEffects() runs on every
     * priority-loop iteration, so the re-prompt is always strictly later.
     * A real cast flow produces a different prompt next (choose ability, mana
     * payment, targets...), which clears the attempt.
     */
    public void recordPlayCardAttempt(UUID cardId) {
        this.lastPlayCardAttemptId = cardId;
    }

    /** Clear the pending play_card attempt (used when a different prompt type arrives). */
    public void clearPlayCardAttempt() {
        this.lastPlayCardAttemptId = null;
    }

    public ClientCallbackMethod getPendingActionType() {
        return pendingActionType;
    }

    public boolean isPendingTargetRequired() {
        return pendingTargetRequired;
    }

    public void clearPendingAction() {
        pendingChoiceBackings = null;
        pendingActionType = null;
        pendingTargetRequired = false;
    }

    @Override
    public MageVersion getVersion() {
        return version;
    }

    @Override
    public void connected(String message) {
        logger.info("[" + username + "] Connected to XMage server: " + message);
        // The connect flow will send the "connected" message after getting tables
    }

    @Override
    public void disconnected(boolean askToReconnect, boolean keepMySessionActive) {
        logger.info("[" + username + "] Disconnected (askToReconnect=" + askToReconnect + ", keepSession=" + keepMySessionActive
                + ", gameId=" + currentGameId + ")");
        // When a game is live, the user's browser page is what holds the seat —
        // the WS drop is almost always a page refresh or a transient network
        // blip, not intent to abandon the match. Signal both so the frontend's
        // auto-reconnect path fires (it resends pendingConnectData on the next
        // WS open) and the server keeps the underlying session alive so the
        // rejoin path finds the seat (previously always false/false).
        boolean gameLive = currentGameId != null;
        sendToBrowser(jsonError("disconnected", "Connection to XMage server lost"));

        if (gameLive && !keepMySessionActive) {
            logger.warn("[" + username + "] Live game " + currentGameId
                    + " — browser should auto-reconnect and trigger rejoin_game");
        }
    }

    @Override
    public void showMessage(String message) {
        logger.info("[" + username + "] Server message: " + message);
        sendToBrowser(jsonMessage("server_message", message));
    }

    @Override
    public void showError(String message) {
        logger.error("[" + username + "] Server error: " + message);
        recordServerError(message);
        sendToBrowser(jsonError("server_error", message));
    }

    // ── Async server-error capture (connect-failure reporting) ──

    /** Last error delivered via the async showError callback (auth failures). */
    private volatile String lastServerError = "";
    private volatile long lastServerErrorAt = 0;

    private void recordServerError(String message) {
        if (message != null && !message.isEmpty()) {
            lastServerError = message;
            lastServerErrorAt = System.currentTimeMillis();
        }
    }

    /** Timestamp (ms) when {@link #getLastServerMessage()} was recorded, 0 when none. */
    public long getLastServerMessageAt() {
        return lastServerErrorAt;
    }

    /**
     * Last text the XMage server pushed via SHOW_USERMESSAGE / SERVER_MESSAGE.
     * Used by the submit_deck path: TableController.submitDeck reports a refusal
     * as a plain boolean, and its accompanying user message is the only carrier
     * of the real reason — the old path rendered an
     * empty "Failed to submit deck: ").
     */
    public String getLastServerMessage() {
        return lastServerError;
    }

    /**
     * Wait up to {@code timeoutMillis} for a SHOW_USERMESSAGE/SERVER_MESSAGE that
     * arrived at or after {@code sinceMillis}. The server fires the user-message
     * callback immediately before its RPC response returns, but the two travel
     * the remoting link on different paths — a short bounded wait closes that
     * race without hanging the WS thread. Returns "" on timeout/no message.
     */
    public String waitForServerMessageSince(long sinceMillis, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            String msg = lastServerError;
            if (!msg.isEmpty() && lastServerErrorAt >= sinceMillis) {
                return msg;
            }
            if (System.currentTimeMillis() >= deadline) {
                return "";
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
        }
    }

    /**
     * Wait up to {@code timeoutMillis} for an async server error (the XMage
     * server reports login failures via showError AFTER connectUser returns
     * false, behind a 3 s anti-bruteforce sleep). Returns the captured message
     * or "" if none arrives.
     */
    public String waitForLastError(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (!lastServerError.isEmpty() && lastServerErrorAt >= connectStartedAt) {
                return lastServerError;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return lastServerError.isEmpty() ? "" : lastServerError;
    }

    /** Timestamp of the connect attempt (set by WebProxySession.handleConnect). */
    private volatile long connectStartedAt = 0;

    public void markConnectStarted() {
        connectStartedAt = System.currentTimeMillis();
    }

    @Override
    public void onNewConnection() {
        logger.info("[" + username + "] New connection established");
    }

    @Override
    public void onCallback(ClientCallback callback) {
        ClientCallbackMethod method = callback.getMethod();
        // Decompress data first — XMage compresses large game state callbacks
        // and getData() throws IllegalStateException if not decompressed first
        try {
            callback.decompressData();
        } catch (Throwable t) {
            logger.error("[" + username + "] Failed to decompress callback " + method, t);
            sendToBrowser(jsonError("callback_error", "Failed to decompress callback " + method + ": " + t.getMessage(),
                    stackToString(t)));
            return;
        }
        Object data = callback.getData();
        logger.info("[" + username + "] onCallback: " + method
            + " objectId=" + callback.getObjectId()
            + " messageId=" + callback.getMessageId()
            + " dataClass=" + (data != null ? data.getClass().getSimpleName() : "null"));

        try {
            handleCallback(method, callback);
        } catch (Throwable t) {
            logger.error("[" + username + "] Error handling callback " + method, t);
            sendToBrowser(jsonError("callback_error", "Error handling " + method + ": " + t.getMessage(),
                    stackToString(t)));
        }
    }

    private void handleCallback(ClientCallbackMethod method, ClientCallback callback) {
        switch (method) {
            case GAME_INIT:
            case GAME_UPDATE:
            case GAME_UPDATE_AND_INFORM:
                handleGameUpdate(callback);
                break;

            case LLM_PROMPT:
                {
                    // Client-key bridge: prompt for
                    // the browser to POST to the provider with the user's key.
                    // Payload has NO key — the browser attaches its own.
                    Object promptData = callback.getData();
                    if (!(promptData instanceof mage.interfaces.LLMPromptPayload)) {
                        logger.error("[" + username + "] LLM_PROMPT with unexpected data: "
                                + (promptData != null ? promptData.getClass().getName() : "null"));
                        break;
                    }
                    mage.interfaces.LLMPromptPayload payload = (mage.interfaces.LLMPromptPayload) promptData;
                    Map<String, Object> msg = new HashMap<>();
                    msg.put("type", "llm_prompt");
                    msg.put("requestId", payload.requestId.toString());
                    msg.put("seatId", payload.seatId.toString());
                    msg.put("gameId", payload.gameId.toString());
                    msg.put("playerName", payload.playerName);
                    msg.put("requestBody", payload.requestBody);
                    msg.put("apiEndpoint", payload.apiEndpoint);
                    msg.put("stream", payload.stream);
                    msg.put("timeoutSeconds", payload.timeoutSeconds);
                    msg.put("clientKeyTag", payload.clientKeyTag);
                    sendToBrowser(gsonToJson(msg));
                }
                break;

            case GAME_INFORM_PERSONAL:
                handleGameInformPersonal(callback);
                break;

            case GAME_ERROR:
                Object errorData = callback.getData();
                String errorMsg = errorData != null ? errorData.toString() : "Unknown game error";
                sendToBrowser(jsonError("game_error", errorMsg));
                break;

            case GAME_OVER:
                handleGameOver(callback);
                break;

            case START_GAME:
                handleStartGame(callback);
                break;

            case JOINED_TABLE:
                handleJoinedTable(callback);
                break;

            case CHATMESSAGE:
                handleChatMessage(callback);
                break;

            case SHOW_USERMESSAGE:
            case SERVER_MESSAGE:
                Object msgData = callback.getData();
                // Auth failures arrive here (server's sendErrorMessageToClient
                // during a refused login) — record for waitForLastError(). The
                // server's payload shape is [title, text] (Session.sendErrorMessageToClient
                // / User.showUserMessage); only the TEXT is user-facing — taking
                // toString() of the list would leak "[Error while connecting to
                // server...]" into connect/submit error frames (the same
                // unwrap RegisterAuthClient does with get(1)).
                if (msgData instanceof java.util.List<?> && ((java.util.List<?>) msgData).size() == 2) {
                    Object text = ((java.util.List<?>) msgData).get(1);
                    recordServerError(text != null ? text.toString() : null);
                } else if (msgData != null) {
                    recordServerError(msgData.toString());
                }
                sendToBrowser(jsonMessage(method.name().toLowerCase(), msgData != null ? msgData.toString() : ""));
                break;

            case WATCHGAME:
                {
                    Object watchData = callback.getData();
                    logger.info("[" + username + "] Watch game callback: dataClass=" + (watchData != null ? watchData.getClass().getSimpleName() : "null")
                        + " objectId=" + callback.getObjectId());
                    // The gameId is in the callback's objectId (not in TableClientMessage for in-progress games)
                    UUID gameId = callback.getObjectId();
                    if (gameId != null) {
                        currentGameId = gameId;
                        logger.info("[" + username + "] Calling watchGame(" + currentGameId + ")");
                        if (session != null) {
                            boolean ok = session.watchGame(currentGameId);
                            logger.info("[" + username + "] watchGame returned " + ok);
                        }
                    } else if (watchData instanceof TableClientMessage) {
                        // Fallback: try to get gameId from TableClientMessage
                        TableClientMessage tcm = (TableClientMessage) watchData;
                        if (tcm.getGameId() != null) {
                            currentGameId = tcm.getGameId();
                            logger.info("[" + username + "] Calling watchGame(" + currentGameId + ") from TableClientMessage");
                            if (session != null) {
                                boolean ok = session.watchGame(currentGameId);
                                logger.info("[" + username + "] watchGame returned " + ok);
                            }
                        }
                    }
                }
                break;

            case SHOW_TOURNAMENT:
                {
                    Object tourData = callback.getData();
                    UUID tournamentId = callback.getObjectId();
                    String tableIdStr = null;
                    if (tourData instanceof TableClientMessage) {
                        TableClientMessage tcm = (TableClientMessage) tourData;
                        if (tcm.getCurrentTableId() != null) {
                            tableIdStr = tcm.getCurrentTableId().toString();
                        }
                    }
                    logger.info("[" + username + "] Show tournament: tournamentId=" + tournamentId + " tableId=" + tableIdStr);

                    // Fetch the full TournamentView and send it to the browser
                    Map<String, Object> tourMsg = new HashMap<>();
                    tourMsg.put("type", "show_tournament");
                    tourMsg.put("tournamentId", tournamentId != null ? tournamentId.toString() : null);
                    tourMsg.put("tableId", tableIdStr);
                    if (tournamentId != null && session != null) {
                        try {
                            mage.view.TournamentView tourView = session.getTournament(tournamentId);
                            if (tourView != null) {
                                tourMsg.put("tournament", serializeTournamentView(tourView));
                            }
                        } catch (Exception e) {
                            logger.warn("[" + username + "] Failed to fetch tournament view: " + e.getMessage());
                        }
                    }
                    sendToBrowser(gsonToJson(tourMsg));
                }
                break;

            case TOURNAMENT_UPDATE:
                {
                    // Server pushes tournament state updates (round changes, game results, etc.)
                    Object tourData = callback.getData();
                    UUID tournamentId = callback.getObjectId();
                    if (tourData instanceof mage.view.TournamentView) {
                        mage.view.TournamentView tourView = (mage.view.TournamentView) tourData;
                        Map<String, Object> tourMsg = new HashMap<>();
                        tourMsg.put("type", "tournament_update");
                        tourMsg.put("tournamentId", tournamentId != null ? tournamentId.toString() : null);
                        tourMsg.put("tournament", serializeTournamentView(tourView));
                        sendToBrowser(gsonToJson(tourMsg));
                    }
                }
                break;

            case TOURNAMENT_OVER:
                {
                    // Tournament finished. Callback data is typically null (server-side
                    // TODO: unused), so we send just the winner name if we can derive
                    // it from the last tournament_update, else an empty body. The
                    // frontend clears its tournament-running state either way.
                    Map<String, Object> overMsg = new HashMap<>();
                    overMsg.put("type", "tournament_over");
                    overMsg.put("tournamentId", callback.getObjectId() != null ? callback.getObjectId().toString() : null);
                    // Try to extract winner from last tournament update if available.
                    // (WebProxyClient doesn't cache TournamentView, so winner
                    //  extraction is best-effort — frontend shows generic completion
                    //  if absent.)
                    sendToBrowser(gsonToJson(overMsg));
                }
                break;

            case GAME_SELECT:
            case GAME_ASK:
            case GAME_TARGET:
            case GAME_CHOOSE_ABILITY:
            case GAME_CHOOSE_CHOICE:
            case GAME_CHOOSE_PILE:
            case GAME_PLAY_MANA:
            case GAME_PLAY_XMANA:
            case GAME_GET_AMOUNT:
            case GAME_GET_MULTI_AMOUNT:
                handleActionPrompt(method, callback);
                break;

            case GAME_REDRAW_GUI:
                // Client-side redraw — no data to send, the browser redraws on game_update
                break;

            case END_GAME_INFO:
                logger.info("[" + username + "] End game info received");
                handleGameOver(callback); // GameEndView payload — same shape the frontend gameEnd state already consumes
                break;

            case SIDEBOARD:
            case CONSTRUCT: {
                // Between-games deck editing request (User.ccSideboard / ccConstruct).
                // SIDEBOARD flag=false → normal sideboarding; flag=true or CONSTRUCT →
                // limited construct_sideboard. Stock opens a deck editor; the browser
                // gets the card lists and renders its own sideboarding view.
                Object data = callback.getData();
                if (data instanceof TableClientMessage) {
                    TableClientMessage tcm = (TableClientMessage) data;
                    sendSideboardPrompt(tcm, method == ClientCallbackMethod.CONSTRUCT);
                } else {
                    // Fail Fast: never forward an unexpected payload silently.
                    logger.error("[" + username + "] " + method + " with unexpected data: "
                            + (data != null ? data.getClass().getName() : "null"));
                    sendToBrowser(jsonError("callback_error", method + " with unexpected data: "
                            + (data != null ? data.getClass().getSimpleName() : "null")));
                }
                break;
            }

            case USER_REQUEST_DIALOG:
                {
                    Object dialogData = callback.getData();
                    if (dialogData instanceof mage.view.UserRequestMessage) {
                        mage.view.UserRequestMessage urm = (mage.view.UserRequestMessage) dialogData;
                        Map<String, Object> msg = new HashMap<>();
                        msg.put("type", "user_request");
                        msg.put("title", urm.getTitle() != null ? urm.getTitle() : "");
                        msg.put("message", urm.getMessage() != null ? urm.getMessage() : "");
                        msg.put("relatedUserName", urm.getRelatedUserName() != null ? urm.getRelatedUserName() : "");
                        msg.put("relatedUserId", urm.getRelatedUserId() != null ? urm.getRelatedUserId().toString() : null);
                        msg.put("gameId", urm.getGameId() != null ? urm.getGameId().toString() : null);
                        msg.put("button1Text", urm.getButton1Text() != null ? urm.getButton1Text() : null);
                        msg.put("button1Action", urm.getButton1Action() != null ? urm.getButton1Action().name() : null);
                        msg.put("button2Text", urm.getButton2Text() != null ? urm.getButton2Text() : null);
                        msg.put("button2Action", urm.getButton2Action() != null ? urm.getButton2Action().name() : null);
                        sendToBrowser(gsonToJson(msg));
                    } else {
                        // Fail Fast: a USER_REQUEST_DIALOG with unexpected data must not
                        // silently degrade to a useless toString() message.
                        logger.error("[" + username + "] USER_REQUEST_DIALOG with unexpected data: "
                                + (dialogData != null ? dialogData.getClass().getName() : "null"));
                        sendToBrowser(jsonError("callback_error", "USER_REQUEST_DIALOG with unexpected data: "
                                + (dialogData != null ? dialogData.getClass().getSimpleName() : "null")));
                    }
                }
                break;

            case VIEW_SIDEBOARD:
                // Server telling us to show sideboard — the sideboard data is already
                // serialized in the player state. Frontend opens ZonePopup on click.
                logger.info("[" + username + "] VIEW_SIDEBOARD callback received");
                sendToBrowser(jsonMessage("view_sideboard", ""));
                break;

            case VIEW_LIMITED_DECK: {
                logger.info("[" + username + "] VIEW_LIMITED_DECK callback received");
                // The server answers a PlayerAction.VIEW_LIMITED_DECK
                // (GameController.viewDeckOrSideboard → User.ccViewLimitedDeck) with a
                // TableClientMessage whose DeckView is the requesting viewer's deck
                // (MatchPlayer.getDeckForViewer — deck name replaced by the seat's name).
                // Serialize it with the SAME routine the sideboard prompt uses; an empty
                // payload here was the long-standing stub that made the browser
                // console.log "not yet implemented".
                Object deckData = callback.getData();
                if (deckData instanceof TableClientMessage) {
                    sendLimitedDeck((TableClientMessage) deckData);
                } else {
                    // Fail Fast: never forward an unexpected payload silently.
                    logger.error("[" + username + "] VIEW_LIMITED_DECK with unexpected data: "
                            + (deckData != null ? deckData.getClass().getName() : "null"));
                    sendToBrowser(jsonError("callback_error", "VIEW_LIMITED_DECK with unexpected data: "
                            + (deckData != null ? deckData.getClass().getSimpleName() : "null")));
                }
                break;
            }

            // ── Replay channel (REPLAY_* from ReplaySession / ReplayManagerImpl) ──
            //
            // The server machinery already exists and is reused as-is:
            //   replayInit  -> ReplaySession created -> REPLAY_GAME (objectId = gameId, no data)
            //   startReplay -> REPLAY_INIT  (objectId = gameId, data = GameView, state index 0)
            //   next/previous/skipForward -> REPLAY_UPDATE (GameView at the new index)
            //   boundary or stop -> REPLAY_DONE (objectId = gameId, data = result string)
            // Every REPLAY_* GameView is serialized with the SAME GameStateSerializer
            // the live channel uses, wrapped in a replay_* envelope, so the frontend
            // renders it with the components it already has.

            case REPLAY_GAME:
                {
                    // Acknowledgment of replayInit: the replay session now exists
                    // server-side. The browser's next step is replay_start.
                    UUID gameId = callback.getObjectId();
                    replayGameId = gameId;
                    replayStopRequested = false;
                    Map<String, Object> msg = new HashMap<>();
                    msg.put("type", "replay_game");
                    msg.put("gameId", gameId != null ? gameId.toString() : null);
                    logger.info("[" + username + "] REPLAY_GAME for " + gameId + " — replay session ready");
                    sendToBrowser(gsonToJson(msg));
                }
                break;

            case REPLAY_INIT:
            case REPLAY_UPDATE:
                {
                    Object replayData = callback.getData();
                    if (!(replayData instanceof GameView)) {
                        // Fail Fast: never forward an unexpected replay payload silently.
                        logger.error("[" + username + "] " + method + " with unexpected data: "
                                + (replayData != null ? replayData.getClass().getName() : "null"));
                        sendToBrowser(jsonError("callback_error", method + " with unexpected data: "
                                + (replayData != null ? replayData.getClass().getSimpleName() : "null")));
                        break;
                    }
                    UUID gameId = callback.getObjectId();
                    if (gameId != null) {
                        replayGameId = gameId;
                    }
                    String type = method == ClientCallbackMethod.REPLAY_INIT ? "replay_init" : "replay_update";
                    logger.info("[" + username + "] " + method + " for " + gameId
                            + " turn=" + ((GameView) replayData).getTurn());
                    sendReplayState((GameView) replayData, gameId, type);
                }
                break;

            case REPLAY_DONE:
                {
                    // Payload is the result string ("stopped replay" | "end of replay"
                    // | "Replay file not found or corrupt"). Classify it here — the
                    // reason enum is proxy-side information the browser acts on
                    // (close the viewer vs. keep it open at a boundary).
                    Object doneData = callback.getData();
                    String result = doneData != null ? doneData.toString() : "";
                    UUID gameId = callback.getObjectId() != null ? callback.getObjectId() : replayGameId;
                    String reason;
                    if (replayStopRequested || "stopped replay".equals(result)) {
                        reason = "stopped";
                    } else if ("end of replay".equals(result)) {
                        reason = "boundary";
                    } else {
                        reason = "error";
                    }
                    Map<String, Object> msg = new HashMap<>();
                    msg.put("type", "replay_done");
                    msg.put("gameId", gameId != null ? gameId.toString() : null);
                    msg.put("reason", reason);
                    msg.put("result", result);
                    logger.info("[" + username + "] REPLAY_DONE for " + gameId
                            + " reason=" + reason + " result=" + result);
                    // 'stopped' and 'error' end the replay session server-side
                    // (ReplayManagerImpl.endReplay) — forget it so a later
                    // replay_start for a DIFFERENT game cannot be attributed to
                    // this one. A 'boundary' keeps the session alive: the user can
                    // still step back, so the id stays known.
                    if (!"boundary".equals(reason)) {
                        clearReplayState();
                        ReplayEndListener listener = replayEndListener;
                        if (listener != null) {
                            listener.onReplayEnded();
                        }
                    }
                    sendToBrowser(gsonToJson(msg));
                }
                break;

            default:
                logger.debug("[" + username + "] Unhandled callback: " + method);
                break;
        }
    }

    // ── Game state ──

    private void handleGameUpdate(ClientCallback callback) {
        Object data = callback.getData();
        GameView gameView = null;

        // Capture the gameId BEFORE any event message is sent: GAME_UPDATE_AND_INFORM
        // carries a log line, and the shared history buffer is keyed by game id —
        // resolving it after the send would silently drop the first line of a game
        // from the rejoin replay.
        if (callback.getObjectId() != null) {
            currentGameId = callback.getObjectId();
        }

        if (data instanceof GameView) {
            gameView = (GameView) data;
        } else if (data instanceof GameClientMessage) {
            // GAME_UPDATE_AND_INFORM can send a GameClientMessage wrapping a GameView
            GameClientMessage gcm = (GameClientMessage) data;
            gameView = gcm.getGameView();
            // If there's a message, send it as an event to the game log
            if (gcm.getMessage() != null && !gcm.getMessage().isEmpty()) {
                Map<String, Object> event = new HashMap<>();
                event.put("type", "action");
                event.put("message", gcm.getMessage());
                sendGameEvent(event, false);
            }
        } else {
            logger.warn("[" + username + "] GAME_UPDATE with unhandled data type: " + (data != null ? data.getClass() : "null"));
            return;
        }

        if (gameView == null) {
            logger.warn("[" + username + "] GAME_UPDATE with null GameView");
            return;
        }

        // Out-of-order/stale GameView guard (evidence: the earlier test evidence).
        // The server stamps every callback with a per-session monotonically
        // increasing messageId in EMISSION order (Session.fireCallback), but the
        // proxy can RECEIVE callbacks inverted (measured live: 96 inverted
        // adjacent messageId pairs in one spectator session, 43 content
        // regressions in 2,567 state frames — 43/43 explained by arrival
        // inversion, 0 by server-side stale emission). Relaying an inverted
        // GameView stamps it with a NEWER stateSeq while carrying OLDER content,
        // so the frontend's forward-gap-only check cannot see it and the header
        // regresses to an earlier turn/phase until the next frame. The desktop
        // client drops exactly this class of callback
        // ("events can income in wrong order, so outdated data must be ignored"
        // — CallbackClientImpl.onCallback ignores outdated UPDATE-type
        // callbacks); this guard is the proxy's equivalent. It runs AFTER the
        // inform text was forwarded above: the log line is content, not state,
        // and game-log entries never repeat, so dropping them would lose event
        // history. GAME_INIT is never dropped (it is a full rebuild) — it only
        // raises the watermark. The resync path (resendLatestGameState) sends no
        // callback and is untouched. Admission and the send share one monitor so
        // concurrent callback threads cannot interleave an admitted state with a
        // later one.
        final int messageId = callback.getMessageId();
        final boolean isInit = callback.getMethod() == ClientCallbackMethod.GAME_INIT;
        synchronized (this) {
            if (!isInit && messageId > 0 && messageId < lastStateMessageId) {
                logger.info("[" + username + "] Skipping stale game state to browser: turn=" + gameView.getTurn()
                    + " phase=" + gameView.getPhase() + " messageId=" + messageId
                    + " < lastStateMessageId=" + lastStateMessageId
                    + " method=" + callback.getMethod()
                    + " (out-of-order callback; a newer state is already on the browser)");
                return;
            }
            noteStateMessageId(messageId);

            lastGameView = gameView;
            myPlayerId = gameView.getMyPlayer() != null ? gameView.getMyPlayer().getPlayerId() : null;
            if (callback.getObjectId() != null) {
                currentGameId = callback.getObjectId();
            }

            logger.info("[" + username + "] Sending game state to browser: turn=" + gameView.getTurn()
                + " phase=" + gameView.getPhase() + " players=" + gameView.getPlayers().size()
                + " messageId=" + messageId
                + " method=" + callback.getMethod()
                + " gameId=" + currentGameId);

            sendGameStateToBrowser(gameView, callback.getObjectId(),
                isInit ? "game_init" : "game_update");
        }
    }

    /**
     * Raise the stale-frame watermark to a callback whose GameView was applied.
     * Never lowers it: a callback delivered late (inverted) carries an older
     * emission id and must not re-open the window for already-superseded frames.
     * A fresh connection starts at 0 and its first GAME_INIT re-bases the id.
     */
    private synchronized void noteStateMessageId(int messageId) {
        if (messageId > 0) {
            lastStateMessageId = Math.max(lastStateMessageId, messageId);
        }
    }

    /**
     * Serialize and send a game state update to the browser.
     * Called from handleGameUpdate and from action prompt handlers to ensure
     * the browser always has the latest board state.
     *
     * Delta optimization: if we have a cached previous state and this isn't a
     * forced full-state send (game_init, resync, first update), compute a diff
     * and send only the changed top-level fields as a "game_delta" message.
     * The frontend merges the delta into its cached state. If the diff is empty
     * or the delta would be larger than ~80% of the full state, send full state.
     */
    private void sendGameStateToBrowser(GameView gameView, UUID gameId, String type) {
        if (gameView == null) return;
        if (gameId != null) {
            currentGameId = gameId;
        }

        // State version: advanced ONLY after the message was actually handed to an
        // OPEN browser socket, so consecutive state sends have consecutive
        // versions on the wire. A send dropped because the socket closed must not
        // burn a version — that produced a permanent gap on the client, which then
        // requested a full resync on most updates. Chat/inform events use eventSeq
        // and must not
        // create gaps in this channel either (they caused false resyncs before).
        // The method is synchronized so concurrent JBoss callback threads cannot
        // hand out the same version or send versions out of order.
        synchronized (this) {
            long version = stateSeq + 1;

            Map<String, Object> stateMap = GameStateSerializer.buildStateMap(gameView, myPlayerId, version);

            // game_init and resync always send full state
            boolean sendFull = "game_init".equals(type) || forceFullState || lastStateMap == null;

            // Rejoin/reload history backfill: GAME_INIT re-attaches the browser to a
            // game that may be hours old, but carries a GameView snapshot with no
            // event history (see the NOTE in GameStateSerializer.buildStateMap), and
            // the frontend keeps its log/chat only in memory. Send the buffered
            // events BEFORE the state push, so a rejoin's panels are populated and
            // the log never falls back to the join banner. Only on the
            // game_init of a game that actually has buffered history — a delta or a
            // resync (where the client still holds its own log) never re-sends it.
            if ("game_init".equals(type) && currentGameId != null) {
                sendHistoryBackfill(currentGameId.toString());
            }

            String payload;
            if (sendFull) {
                Map<String, Object> envelope = new HashMap<>();
                envelope.put("type", type);
                envelope.put("state", stateMap);
                payload = gson.toJson(envelope);
            } else {
                // Compute delta — only top-level keys that changed
                Map<String, Object> delta = computeStateDelta(lastStateMap, stateMap);
                int fullSize = gson.toJson(stateMap).length();
                int deltaSize = delta.isEmpty() ? 0 : gson.toJson(delta).length();

                Map<String, Object> envelope = new HashMap<>();
                if (delta.isEmpty() || (fullSize > 0 && deltaSize > fullSize * 0.8)) {
                    // If delta is empty or > 80% of full state, just send full state
                    envelope.put("type", "game_update");
                    envelope.put("state", stateMap);
                } else {
                    envelope.put("type", "game_delta");
                    envelope.put("delta", delta);
                    envelope.put("stateSeq", version);
                }
                payload = gson.toJson(envelope);
            }

            if (!trySendToBrowser(payload)) {
                // Dropped (socket closed or send threw): do NOT consume the version
                // and do NOT advance the cached state map, so the next send reuses
                // this version and stays consecutive with what the client last saw.
                logger.warn("[" + username + "] State send dropped before reaching the browser — "
                        + "stateSeq stays at " + stateSeq + " (version " + version + " not consumed)");
                return;
            }

            stateSeq = version;
            lastStateMap = stateMap;
            forceFullState = false;
        }
    }

    /**
     * Hand the buffered game-log/chat history to the browser before the rejoin's
     * state push. Fail Fast on a buffer that does not belong to the rejoin target:
     * the alternative (silently sending another game's log) is exactly the
     * cross-game contamination this must not introduce.
     *
     * No-op when the buffer holds nothing for this game — a fresh game has no
     * history, and the frontend then simply keeps its existing (empty) panels.
     */
    private void sendHistoryBackfill(String gameId) {
        List<Map<String, Object>> logEvents = history.logSnapshot(gameId);
        List<Map<String, Object>> chat = history.chatSnapshot(gameId);
        if (logEvents.isEmpty() && chat.isEmpty()) {
            return;
        }
        String boundGameId = history.currentGameId();
        if (!gameId.equals(boundGameId)) {
            // Unreachable while the snapshots above are non-empty (they return
            // empty for a foreign game) — but if it ever changes, the browser must
            // not receive mismatched history.
            throw new IllegalStateException("History backfill game mismatch: requested " + gameId
                    + " but the buffer belongs to " + boundGameId);
        }
        Map<String, Object> msg = GameHistory.buildBackfillMessage(gameId, logEvents, chat);
        logger.info("[" + username + "] Sending history backfill for game " + gameId
                + ": " + logEvents.size() + " log event(s), " + chat.size() + " chat line(s)");
        sendToBrowser(gsonToJson(msg));
    }

    /**
     * Compute a top-level diff between two state maps.
     * Returns a map containing only the keys whose values differ.
     * For list/map values, we compare by JSON string (structural equality).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> computeStateDelta(Map<String, Object> oldState, Map<String, Object> newState) {
        Map<String, Object> delta = new HashMap<>();
        for (Map.Entry<String, Object> entry : newState.entrySet()) {
            String key = entry.getKey();
            Object newVal = entry.getValue();
            Object oldVal = oldState.get(key);

            if (oldVal == null && newVal == null) continue;
            if (oldVal == null || newVal == null || !jsonEquals(oldVal, newVal)) {
                delta.put(key, newVal);
            }
        }
        return delta;
    }

    /**
     * Structural equality via JSON serialization. Compares the JSON string
     * representation of two values — handles Maps, Lists, and primitives.
     */
    private boolean jsonEquals(Object a, Object b) {
        return gson.toJson(a).equals(gson.toJson(b));
    }

    private void handleGameInformPersonal(ClientCallback callback) {
        Object data = callback.getData();
        if (data == null) return;
        // The server wraps the text in a GameClientMessage (GAME_INFORM_PERSONAL,
        // GameSessionWatcher.informPersonal) — `data.toString()` would render the
        // OBJECT IDENTITY ("mage.view.GameClientMessage@2b3c3deb") into the game
        // log. Unwrap to the actual message, exactly as the GAME_UPDATE_AND_INFORM
        // branch above does. Measured live 2026-09-23: a turn rollback put
        // "mage.view.GameClientMessage@..." in the log in place of its
        // announcement.
        String message;
        if (data instanceof GameClientMessage) {
            message = ((GameClientMessage) data).getMessage();
        } else {
            message = data.toString();
        }
        if (message == null || message.isEmpty()) return;

        Map<String, Object> event = new HashMap<>();
        event.put("type", "action");
        event.put("message", message);

        // Categorize event for animation triggers (see frontend _triggerEventAnimation)
        event.put("category", categorizeEventMessage(message));
        sendGameEvent(event, false);
    }

    /**
     * Record and send one game-log/chat event.
     *
     * The event is handed to the shared {@link GameHistory} for buffering BEFORE it
     * goes on the wire, so a browser that drops between the send and the rejoin
     * still gets it in the backfill. The buffer is the rejoin/reload history
     * — a
     * dropped send (socket closed) is deliberately still buffered, because the
     * next session replays it.
     *
     * @param isChat true for a chat line (talk/whisper), false for a game-log entry
     */
    private void sendGameEvent(Map<String, Object> event, boolean isChat) {
        UUID gameId = currentGameId;
        String gameIdStr = gameId != null ? gameId.toString() : null;
        history.recordEvent(gameIdStr, event, isChat);

        Map<String, Object> eventMsg = new HashMap<>();
        eventMsg.put("type", "event");
        eventMsg.put("event", event);
        sendToBrowser(gsonToJson(eventMsg));
    }

    /**
     * Categorize a game log message into an animation category.
     * The frontend uses this to trigger CSS keyframe animations on affected
     * card elements. Inspired by Argentum's typed ClientEvent system.
     */
    private String categorizeEventMessage(String message) {
        if (message == null) return "generic";
        String lower = message.toLowerCase();
        if (lower.contains("draw") || lower.contains("drew")) return "draw";
        if (lower.contains("damage") || lower.contains("dealt")) return "damage";
        if (lower.contains("destroy") || lower.contains("destroyed") || lower.contains("dies") || lower.contains("died")) return "destroy";
        if (lower.contains("tap") || lower.contains("tapped")) return "tap";
        if (lower.contains("untap") || lower.contains("untapped")) return "untap";
        if (lower.contains("exile") || lower.contains("exiled")) return "exile";
        if (lower.contains("reveal") || lower.contains("revealed")) return "reveal";
        if (lower.contains("counter") && (lower.contains("spell") || lower.contains("ability"))) return "counter";
        if (lower.contains("sacrifice") || lower.contains("sacrificed")) return "sacrifice";
        if (lower.contains("discard") || lower.contains("discarded")) return "discard";
        if (lower.contains("enter") || lower.contains("enters") || lower.contains("entered")) return "enter";
        if (lower.contains("attack") || lower.contains("attacker") || lower.contains("attacks")) return "attack";
        if (lower.contains("block") || lower.contains("blocker")) return "block";
        if (lower.contains("life") && (lower.contains("gain") || lower.contains("lost") || lower.contains("lose"))) return "life";
        if (lower.contains("token")) return "token";
        return "generic";
    }

    // ── Chat ──

    private void handleChatMessage(ClientCallback callback) {
        Object data = callback.getData();
        if (data == null) return;

        // NOTE: CHATMESSAGE's objectId is the CHAT id, not the game id, so
        // currentGameId is resolved from START_GAME / GAME_INIT / GAME_UPDATE
        // instead. That is the correct attribution for the rejoin buffer: game-log
        // lines broadcast through the game chat channel belong to the game whose
        // chat the session joined in handleStartGame, and room chat simply rides
        // along in the client's single chat panel (which is never reset between
        // games — see gameplaySlice).

        // Chat messages come as a ChatMessage object (not a String)
        String message;
        String from;
        String messageType = "talk"; // default
        if (data instanceof mage.view.ChatMessage) {
            mage.view.ChatMessage cm = (mage.view.ChatMessage) data;
            message = cm.getMessage() != null ? cm.getMessage() : "";
            // Determine message type: GAME messages go to game log, TALK to chat tab
            if (cm.getMessageType() != null) {
                messageType = cm.getMessageType().name().toLowerCase();
            }
            // For GAME/SYSTEM/STATUS messages, use the chat username or "Game" as source.
            // For TALK/WHISPER, use the actual chat username.
            if ("game".equals(messageType) || "system".equals(messageType) || "status".equals(messageType)) {
                from = cm.getUsername() != null && !cm.getUsername().isEmpty() ? cm.getUsername() : "Game";
            } else {
                from = cm.getUsername() != null && !cm.getUsername().isEmpty() ? cm.getUsername() : "Unknown";
            }
            // NOTE: MOTD is NOT delivered through this callback: the server's
            // Message of the Day (ServerMessagesUtil, server.msg.txt) is a PULL —
            // the desktop client fetches it in TablesPanel.reloadServerMessages via
            // SessionImpl.getServerMessages (Mage.Common SessionImpl.java:1569). The
            // proxy fetches the same lines in WebProxySession.handleGetRoomUsers and
            // puts them on the users_list response (lobbySlice.setMotd).
        } else {
            message = data.toString();
            from = "Unknown";
            messageType = "system";
        }

        Map<String, Object> event = new HashMap<>();
        // GAME/SYSTEM/STATUS messages go to the game log as "action" events.
        // TALK/WHISPER messages go to the chat tab. Use the server-side enum name
        // ("talk") for the event type so frontend and backend agree on naming.
        boolean isChat = !("game".equals(messageType) || "system".equals(messageType) || "status".equals(messageType));
        event.put("type", isChat ? "talk" : "action");
        event.put("message", message);
        event.put("from", from);
        // Forward the RAW server message type (lower-cased ChatMessage.MessageType
        // enum name: talk / whisper_from / whisper_to / user_info / status / game)
        // so the frontend can route lobby chat lines between its Talk and System
        // tabs (utils/lobbyChat.routeChat). ADDITIVE field — the existing
        // `type` talk/action classification above is unchanged.
        event.put("chatType", messageType);
        sendGameEvent(event, isChat);
    }

    // ── Game lifecycle ──

    private void handleStartGame(ClientCallback callback) {
        Object data = callback.getData();
        // START_GAME's objectId is the gameId — capture it for sendPlayer* calls
        if (callback.getObjectId() != null) {
            currentGameId = callback.getObjectId();
        }
        String gameId = currentGameId != null ? currentGameId.toString() : (data != null ? data.toString() : "");
        logger.info("[" + username + "] Game started: gameId=" + gameId);

        // The server re-sends START_GAME
        // for the same gameId (forced-join safety net, rejoin duplicates). Answering every
        // duplicate re-ran joinGame + joinChat on each one; the gate makes that work happen
        // exactly once per game session. The duplicate is still forwarded to the browser
        // below — the frontend's log/prompt lifecycle already treats a repeated gameId as a
        // rejoin, and suppressing the announcement would break the rejoin view switch.
        boolean joinNow = false;
        if (session != null && currentGameId != null) {
            joinNow = startGameJoinGate.shouldJoin(currentGameId);
            if (!joinNow) {
                logger.info("[" + username + "] Duplicate START_GAME for gameId=" + currentGameId
                        + " — join+chat already done for this game session, skipping");
            }
        }

        // Bind the history buffer to this game. A DIFFERENT game id (match 2, a new
        // table in the same session) clears the buffered log/chat — the between-games
        // reset. A re-announced SAME id (rejoin) keeps it, which is what makes the
        // backfill possible. Mirrors the frontend's markGameStart() rule.
        // Bound on the UUID string that sendHistoryBackfill also keys on, so the two
        // can never disagree about which game the buffer holds. A START_GAME without
        // a resolvable game id (no objectId, no payload) binds nothing: there is no
        // game to attribute the events to yet, and GAME_INIT will carry the id.
        if (currentGameId != null) {
            history.beginGame(currentGameId.toString());
        }

        // Acknowledge the game join immediately, exactly like the Swing client
        // (GamePanel.java:863). Without this the server's safety net
        // (GameController.sendInfoAboutPlayersNotJoinedYetAndTryToFixIt) waits
        // ~10 s for a join ack that never comes, then force-pushes the start —
        // every web game paid that 10 s penalty.
        if (joinNow) {
            boolean joined = session.joinGame(currentGameId);
            if (!joined) {
                logger.error("[" + username + "] joinGame failed for gameId=" + currentGameId);
            }
        }

        // Join the game chat session so we receive game log messages
        // (informPlayers broadcasts via the chat system as MessageType.GAME)
        if (joinNow) {
            try {
                mage.remote.SessionImpl impl = (mage.remote.SessionImpl) session;
                java.util.Optional<UUID> chatIdOpt = impl.getGameChatId(currentGameId);
                if (chatIdOpt.isPresent()) {
                    boolean joined = impl.joinChat(chatIdOpt.get());
                    logger.info("[" + username + "] Joined game chat: chatId=" + chatIdOpt.get() + " success=" + joined);
                } else {
                    logger.warn("[" + username + "] No game chat ID found for gameId=" + currentGameId);
                }
            } catch (ClassCastException e) {
                logger.warn("[" + username + "] Session is not SessionImpl, cannot join game chat");
            } catch (Exception e) {
                logger.error("[" + username + "] Failed to join game chat: " + e.getMessage());
            }
        }

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "start_game");
        msg.put("gameId", gameId);
        sendToBrowser(gsonToJson(msg));
    }

    /**
     * Is the MATCH decided, judged from the match itself rather than the viewer's
     * own row? A match is decided exactly when some player reached the needed wins,
     * or a player quit (which ends the match even when no one reached the score —
     * MatchImpl.checkIfMatchEnds: {@code wins >= winsNeeded} OR {@code activePlayers < 2}).
     *
     * WHY THE MATCH AND NOT THE VIEWER: {@link mage.view.GameEndView} carries
     * {@code wins} only for the viewer's own MatchPlayer (and never assigns
     * {@code loses}), so a viewer-based decision can only ever recognize the
     * WINNER's session. The losing player — and any spectator — saw a stale
     * currentGameId for the rest of the page life, and every later replay_load in
     * that session was refused by the live-game guard in
     * WebProxySession.handleReplayLoad

     *
     * {@link mage.view.MatchView#getPlayerList()} carries one row per match player
     * with the match-level win count (MatchView.initMatchTable → MatchPlayer.getWins,
     * i.e. the FINAL score: this runs from END_GAME_INFO, which MatchImpl.endGame
     * fires after addWin/setQuit/checkIfMatchEnds).
     *
     * The viewer's own numbers are used as a FALLBACK (verbatim pre-existing
     * behavior) whenever the match rows cannot be trusted as match wins: no
     * MatchView, a tournament summary (its rows carry POINTS, not wins), or an
     * empty row list. Missing data never invents a "decided" verdict beyond that
     * fallback, so a Bo3 between-game (match not decided) always keeps its id and
     * its buffered history.
     *
     * @param matchView          the match summary from the end-of-game payload (may be null)
     * @param winsNeeded         match wins needed from the payload
     * @param viewerWins         the viewer's own match wins (fallback; 0 for a spectator)
     * @param viewerLoses        the viewer's own match losses (fallback; always 0 today —
     *                           GameEndView never assigns it — kept for payload honesty)
     * @return true only when the payload proves the match is over
     */
    static boolean matchDecidedFrom(mage.view.MatchView matchView, int winsNeeded,
            int viewerWins, int viewerLoses) {
        if (matchView != null && !matchView.isTournament()
                && matchView.getPlayerList() != null && !matchView.getPlayerList().isEmpty()) {
            if (winsNeeded <= 0) {
                // The score target is missing from the payload: an absent target must
                // never be read as "0 wins needed" (that would declare EVERY match
                // over and drop a live Bo3's rejoin window). Only an explicit quit —
                // the other way MatchImpl.checkIfMatchEnds ends a match — decides here.
                for (mage.view.MatchPlayerView row : matchView.getPlayerList()) {
                    if (row != null && row.hasQuit()) {
                        return true;
                    }
                }
                return false;
            }
            for (mage.view.MatchPlayerView row : matchView.getPlayerList()) {
                if (row != null && (row.getWins() >= winsNeeded || row.hasQuit())) {
                    return true;
                }
            }
            // Rows present and proving nothing: the match is still open (Bo3 game 1
            // at 1-0). Deliberately DO NOT fall through to the viewer numbers — with
            // real match rows available, they are the verdict.
            return false;
        }
        // Pre-existing decision, kept verbatim for the no-match-data case.
        return viewerWins >= winsNeeded || viewerLoses >= winsNeeded;
    }

    private void handleGameOver(ClientCallback callback) {
        Object data = callback.getData();
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "game_over");
        // Two different callbacks land here:
        //   END_GAME_INFO → GameEndView (rich: result, hasWon, matchScore, life totals)
        //   GAME_OVER     → GameClientMessage (just a `message` string, NO hasWon)
        // The GAME_OVER one used to fall through to data.toString(), shipping
        // "mage.view.GameClientMessage@59ccb3c6" to the browser — and because the frontend
        // applied every game_over unconditionally, that garbage overwrote the correct
        // result and rendered "💀 Defeat" for the player who WON.
        // Serialize the real payload shape instead, and mark this variant so the client can
        // tell a rich summary from a partial one.
        if (data instanceof mage.view.GameEndView) {
            mage.view.GameEndView gev = (mage.view.GameEndView) data;
            msg.put("result", gev.getGameInfo() != null ? gev.getGameInfo() : "");
            msg.put("hasWon", gev.hasWon());
            msg.put("matchInfo", gev.getMatchInfo() != null ? gev.getMatchInfo() : "");
            msg.put("additionalInfo", gev.getAdditionalInfo() != null ? gev.getAdditionalInfo() : "");
            // Game + match duration windows (epoch ms, null when unknown) — the desktop
            // GameEndDialog's Statistics tab shows both (GameEndDialog.java:66-73,
            // Format.getDuration). Game window: GameEndView.startTime/endTime (the
            // just-ended game). Match window: the embedded MatchView's start/end — the
            // same source the lobby's finished-matches serializer already ships
            // (WebProxySession.serializeFinishedMatches). Gson drops null map values,
            // so an absent field means "unknown"; the client formats what it gets and
            // hides the row otherwise.
            msg.put("gameStartMs", gev.getStartTime() != null ? gev.getStartTime().getTime() : null);
            msg.put("gameEndMs", gev.getEndTime() != null ? gev.getEndTime().getTime() : null);
            if (gev.getMatchView() != null) {
                msg.put("matchScore", gev.getMatchView().getResult() != null ? gev.getMatchView().getResult() : "");
                msg.put("matchStartMs", gev.getMatchView().getStartTime() != null ? gev.getMatchView().getStartTime().getTime() : null);
                msg.put("matchEndMs", gev.getMatchView().getEndTime() != null ? gev.getMatchView().getEndTime().getTime() : null);
            }
            // Player life totals
            java.util.List<Map<String, Object>> players = new ArrayList<>();
            if (gev.getPlayers() != null) {
                for (mage.view.PlayerView pv : gev.getPlayers()) {
                    Map<String, Object> p = new HashMap<>();
                    p.put("name", pv.getName());
                    p.put("life", pv.getLife());
                    players.add(p);
                }
            }
            msg.put("players", players);
            msg.put("complete", true);
            // Clear currentGameId only when the MATCH is decided (either side
            // reached the needed wins) — END_GAME_INFO fires once per game, and
            // in a Bo3 the table moves to sideboarding where a reload must still
            // rejoin. A stale id after match end would needlessly keep the XMage
            // session alive on the next WS close (cleanup() consults it for the
            // keep decision).
            //
            // The decision is computed from the MATCH (MatchView.playerList), not
            // from the viewer's own row: GameEndView fills `wins` only for the
            // viewer's own MatchPlayer and never assigns `loses` at all
            // (GameEndView.java:30-31,69-71,141-147 — the field has a getter and no
            // writer), so a viewer-based check can only ever clear the session of
            // the player who WON the match. The losing player's session kept a stale
            // id for the rest of the page life and every later replay_load in it was
            // refused by WebProxySession.handleReplayLoad's live-game guard

            boolean matchDecided = matchDecidedFrom(gev.getMatchView(), gev.getWinsNeeded(),
                    gev.getWins(), gev.getLoses());
            if (matchDecided) {
                currentGameId = null;
                // Match over: empty the buffered log/chat — there is no live game
                // left to rejoin, so the history has no reader, and it must not be
                // replayed into the next match (the registry entry itself is
                // released when the session is torn down, see WebProxySession
                // cleanup → GameHistory.release). A Bo3 between-game (match NOT
                // decided) deliberately KEEPS the buffer: the table is sideboarding
                // and a reload must still rejoin and restore its log.
                history.clear();
            }
        } else if (data instanceof mage.view.GameClientMessage) {
            // Partial: a bare "you won/lost" line with no win flag or score. `complete=false`
            // tells the frontend to fill in only the gaps, never to clobber a rich summary.
            mage.view.GameClientMessage gcm = (mage.view.GameClientMessage) data;
            String text = gcm.getMessage() != null ? gcm.getMessage() : "";
            logger.warn("[" + username + "] Game over with a partial payload (GameClientMessage): " + text);
            msg.put("result", text);
            msg.put("complete", false);
            // ── A WATCHER's game just ended: drop the (now stale) game id ──
            // The rich END_GAME_INFO above never reaches a watcher: the server fires
            // it from GameController.endGameInfo, which loops seated GameSessionPlayers
            // only (the standing "TODO: inform watchers about game end and who won"),
            // so a spectator's ONLY end-of-game signal is this partial GAME_OVER —
            // verified in the failing session's proxy log (0 "End game info received"
            // lines across 4 watched games; GAME_OVER always arrived as
            // GameClientMessage). The watcher then never attaches to the match's next
            // game (same log: WATCHGAME/GAME_UPDATE only for game 1), so currentGameId
            // kept a finished game for the rest of the page life and every later
            // replay_load was refused by WebProxySession.handleReplayLoad's live-game
            // guard.
            //
            // Why clearing is safe for a watcher ONLY (hence the payload check): a
            // watcher has no seat to rejoin — WebProxySession.cleanup deliberately
            // does not keep a spectator's XMage session ("watched games aren't
            // restorable on reconnect upstream"), and rejoin_game is gated to seated
            // tables — so there is no rejoin window to lose. A SEATED player's
            // payload view is built for them (GameSessionPlayer.getGameView →
            // prepareGameView with playerId), so the Bo3 between-game window for
            // players keeps its id and buffered history exactly as before; game 2's
            // own GAME_INIT/START_GAME re-sets the id for a watcher who watches again.
            mage.view.GameView overView = gcm.getGameView();
            if (overView != null && !overView.isPlayer()) {
                currentGameId = null;
                history.clear();
            }
        } else {
            // Fail Fast: an unrecognized game-over payload is a bug, not something to
            // paper over with toString() — that is exactly how the Defeat bug shipped.
            logger.error("[" + username + "] Unhandled game-over payload type: "
                    + (data != null ? data.getClass().getName() : "null"));
            sendToBrowser(jsonError("game_error", "Game over, but the result payload was unrecognized — see proxy logs"));
            return;
        }
        sendToBrowser(gsonToJson(msg));
    }

    /**
     * Between-games deck editing prompt (SIDEBOARD / CONSTRUCT callbacks).
     * Serializes the DeckView card lists so the browser can render its sideboarding
     * UI. construct=true means limited deck construction (CONSTRUCT callback or
     * SIDEBOARD flag=true in the stock client's terms).
     */
    private void sendSideboardPrompt(TableClientMessage tcm, boolean construct) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "sideboard_prompt");
        msg.put("tableId", tcm.getCurrentTableId() != null ? tcm.getCurrentTableId().toString() : null);
        msg.put("parentTableId", tcm.getParentTableId() != null ? tcm.getParentTableId().toString() : null);
        msg.put("time", tcm.getTime());
        // Stock semantics (CallbackClientImpl): SIDEBOARD flag=true → limited
        // construct_sideboard; CONSTRUCT → limited build. Both are deck-editing
        // requests, so the browser gets construct=true for either.
        msg.put("construct", construct || tcm.getFlag());
        msg.put("deckName", tcm.getDeck() != null && tcm.getDeck().getName() != null ? tcm.getDeck().getName() : "");
        msg.put("cards", serializeSimpleCardList(tcm.getDeck() != null ? tcm.getDeck().getCards() : null));
        msg.put("sideboard", serializeSimpleCardList(tcm.getDeck() != null ? tcm.getDeck().getSideboard() : null));
        sendToBrowser(gsonToJson(msg));
    }

    /**
     * "View deck" answer (VIEW_LIMITED_DECK callback). The server resolves the target
     * seat's deck (MatchPlayer.getDeckForViewer) and ships it as a DeckView on a
     * TableClientMessage; serialize the two card lists with the same routine the
     * sideboard prompt uses so the browser renders one card-row shape everywhere.
     * Field names mirror the sideboard_prompt precedent (deckName/cards/sideboard,
     * entries {name,setCode,cardNumber,count}).
     */
    private void sendLimitedDeck(TableClientMessage tcm) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "view_limited_deck");
        msg.put("tableId", tcm.getCurrentTableId() != null ? tcm.getCurrentTableId().toString() : null);
        msg.put("parentTableId", tcm.getParentTableId() != null ? tcm.getParentTableId().toString() : null);
        msg.put("time", tcm.getTime());
        // DeckView.getName() is the SEAT name for this path — User.ccViewLimitedDeck
        // ships MatchPlayer.getDeckForViewer(), which replaces the real deck name with
        // the player's name (Tiny Leaders hides it that way upstream). The browser
        // still knows which seat it asked for; deckName is the display fallback.
        msg.put("deckName", tcm.getDeck() != null && tcm.getDeck().getName() != null ? tcm.getDeck().getName() : "");
        msg.put("cards", serializeSimpleCardList(tcm.getDeck() != null ? tcm.getDeck().getCards() : null));
        msg.put("sideboard", serializeSimpleCardList(tcm.getDeck() != null ? tcm.getDeck().getSideboard() : null));
        sendToBrowser(gsonToJson(msg));
    }

    /** SimpleCardsView → [{name, setCode, cardNumber, qty}] (collapsed by print run). */
    private List<Map<String, Object>> serializeSimpleCardList(mage.view.SimpleCardsView view) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (view == null) {
            return out;
        }
        Map<String, Map<String, Object>> byPrint = new java.util.LinkedHashMap<>();
        for (mage.view.SimpleCardView cv : view.values()) {
            String setCode = cv.getExpansionSetCode() != null ? cv.getExpansionSetCode() : "";
            String cardNumber = cv.getCardNumber() != null ? cv.getCardNumber() : "";
            // SimpleCardView carries no name — resolve via CardRepository by set+number,
            // the same lookup DeckUtil.construct performs in the stock client.
            CardInfo info = CardRepository.instance.findCard(setCode, cardNumber);
            if (info == null) {
                // Fail Fast: same policy as stock DeckUtil (log.fatal on missing card).
                // Shared by the sideboard prompt AND the view-deck path — name the
                // card identity, not the caller, so either failure is diagnosable.
                logger.error("[" + username + "] card-list serialization: unknown card set="
                        + setCode + " cid=" + cardNumber);
                throw new IllegalStateException("Unknown card in deck card list: set="
                        + setCode + " number=" + cardNumber);
            }
            String key = info.getName() + "|" + setCode + "|" + cardNumber;
            Map<String, Object> entry = byPrint.get(key);
            if (entry == null) {
                entry = new HashMap<>();
                entry.put("name", info.getName());
                entry.put("setCode", setCode);
                entry.put("cardNumber", cardNumber);
                // Field name "count" matches the deck-editor wire convention (LegalityService/deck.ts DeckEntry).
                entry.put("count", 1);
                byPrint.put(key, entry);
            } else {
                entry.put("count", ((Integer) entry.get("count")) + 1);
            }
        }
        out.addAll(byPrint.values());
        return out;
    }

    private void handleJoinedTable(ClientCallback callback) {
        Object data = callback.getData();
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "table_joined");
        // JOINED_TABLE's payload is a TableClientMessage (NOT a TableView), so the
        // `data instanceof TableView` branch never fired and this message used to reach the
        // browser with NO tableId — leaving the client unable to tell which table it was
        // seated at (and therefore unable to offer "Start Match" to the owner). The tableId
        // is in the callback's objectId, exactly like WATCHGAME.
        String tableName = null;
        if (callback.getObjectId() != null) {
            msg.put("tableId", callback.getObjectId().toString());
        }
        if (data instanceof TableView) {
            TableView table = (TableView) data;
            msg.put("tableId", table.getTableId().toString());
            tableName = table.getTableName();
        } else if (data instanceof mage.view.TableClientMessage) {
            mage.view.TableClientMessage tcm = (mage.view.TableClientMessage) data;
            if (tcm.getCurrentTableId() != null) {
                msg.put("tableId", tcm.getCurrentTableId().toString());
            }
        }
        if (tableName != null) {
            msg.put("tableName", tableName);
        }
        sendToBrowser(gsonToJson(msg));
    }

    // ── Action prompts (Phase 2-3) ──

    private void handleActionPrompt(ClientCallbackMethod method, ClientCallback callback) {
        Object data = callback.getData();
        currentGameId = callback.getObjectId();
        pendingActionType = method;

        // GAME_CHOOSE_ABILITY sends an AbilityPickerView, not a GameClientMessage
        if (method == ClientCallbackMethod.GAME_CHOOSE_ABILITY && data instanceof AbilityPickerView) {
            AbilityPickerView apv = (AbilityPickerView) data;
            if (apv.getGameView() != null) {
                lastGameView = apv.getGameView();
                myPlayerId = lastGameView.getMyPlayer() != null ? lastGameView.getMyPlayer().getPlayerId() : null;
                sendGameStateToBrowser(apv.getGameView(), callback.getObjectId(), "game_update");
            }
            handleGameChooseAbilityAPV(apv);
            return;
        }

        if (data instanceof GameClientMessage) {
            GameClientMessage gcm = (GameClientMessage) data;
            GameView gameView = gcm.getGameView();
            if (gameView != null) {
                lastGameView = gameView;
                myPlayerId = gameView.getMyPlayer() != null ? gameView.getMyPlayer().getPlayerId() : null;
                // Send the updated game state to the browser alongside the action prompt.
                // Without this, the board state can be stale (e.g. land played but not
                // showing on battlefield) until the next separate GAME_UPDATE arrives.
                sendGameStateToBrowser(gameView, callback.getObjectId(), "game_update");
            }

            // A non-GAME_SELECT prompt means the last play_card attempt (if any)
            // took effect — a no-op'd attempt only ever produces another GAME_SELECT.
            if (method != ClientCallbackMethod.GAME_SELECT) {
                clearPlayCardAttempt();
            }

            switch (method) {
                case GAME_SELECT:
                    handleGameSelect(gcm, gameView);
                    break;
                case GAME_ASK:
                    handleGameAsk(gcm, gameView);
                    break;
                case GAME_TARGET:
                    handleGameTarget(gcm, gameView);
                    break;
                case GAME_CHOOSE_CHOICE:
                    handleGameChooseChoice(gcm, gameView);
                    break;
                case GAME_CHOOSE_ABILITY:
                    handleGameChooseAbility(gcm, gameView);
                    break;
                case GAME_GET_AMOUNT:
                    handleGameGetAmount(gcm, gameView);
                    break;
                case GAME_CHOOSE_PILE:
                    handleGameChoosePile(gcm, gameView);
                    break;
                case GAME_PLAY_MANA:
                case GAME_PLAY_XMANA:
                    handleGamePlayMana(gcm, gameView, method);
                    break;
                case GAME_GET_MULTI_AMOUNT:
                    handleGameGetMultiAmount(gcm, gameView);
                    break;
                default:
                    // Fail Fast: unknown action prompt type must NOT silently
                    // degrade to a generic prompt. The user would get unusable
                    // OK/Cancel buttons instead of a clear error. Send an error
                    // message to the browser so the frontend can display it.
                    String promptMsg = "Unhandled action prompt type: " + method
                        + ". This client doesn't support this prompt type."
                        + (gcm.getMessage() != null ? " Server message: " + gcm.getMessage() : "");
                    logger.error("[" + username + "] " + promptMsg);
                    sendToBrowser(jsonError("error", promptMsg));
                    break;
            }
        } else {
            logger.warn("[" + username + "] Action prompt " + method + " with non-GameClientMessage data: "
                + (data != null ? data.getClass() : "null"));
        }
    }

    /**
     * GAME_SELECT: Player can play a card/ability or pass priority.
     * Choices come from gameView.getCanPlayObjects() — each entry is a UUID
     * that gets sent back via sendPlayerUUID.
     * Pass priority = sendPlayerBoolean(gameId, false).
     */
    private void handleGameSelect(GameClientMessage gcm, GameView gameView) {
        List<Object> backings = new ArrayList<>();
        List<Map<String, Object>> choices = new ArrayList<>();
        int idx = 0;

        // Add getCanPlayObjects (lands that can be played, activated abilities on battlefield)
        if (gameView != null && gameView.getCanPlayObjects() != null && !gameView.getCanPlayObjects().isEmpty()) {
            PlayableObjectsList playable = gameView.getCanPlayObjects();
            Map<UUID, PlayableObjectStats> playableObjects = playable.getObjects();
            logger.info("[" + username + "] GAME_SELECT: " + playableObjects.size() + " playable objects");

            // Pass 1 — resolve every key a view can actually see.
            //
            // Some keys deliberately refer to a card NO view renders:
            // PlayerImpl.getPlayableObjects (Mage/src/main/java/mage/players/PlayerImpl.java:4556-4558)
            // registers an ability under BOTH ability.getSourceId() and
            // card.getMainCard().getId() for multi-faced cards, so that the GUI can
            // mark the main card playable too. For a transformed permanent the
            // "main card" is the double-faced container card — it is not a
            // battlefield permanent (the up-face half is), not a hand/graveyard/
            // exile card and not a stack object, so findCardViewById cannot resolve
            // it. Emitting such a key as "Unknown" shipped a mislabelled
            // planeswalker ability.
            Map<UUID, CardView> resolvedViews = new HashMap<>();
            for (Map.Entry<UUID, PlayableObjectStats> entry : playableObjects.entrySet()) {
                CardView cardView = findCardViewById(entry.getKey(), gameView);
                if (cardView != null) {
                    resolvedViews.put(entry.getKey(), cardView);
                }
            }

            // Pass 1a — for keys NO view renders as a card, try the command zone.
            //
            // Command objects are the only playable zone whose entries are not
            // all CardViews: findCardViewById answers with a CardView or
            // nothing (Commanders qualify — CommanderView extends CardView —
            // while emblems/planes/dungeons do not). A non-card command object
            // CAN carry playable activated abilities: the Momir Basic emblem is
            // exactly that ("{X}, Discard a card: create a token that's a copy
            // of a random creature card with mana value X"). Because the lookup
            // returned nothing, the check below refused to label its
            // choice and DROPPED it — Momir Basic was unplayable in the web
            // client.
            //
            // Gated on the object actually having playable abilities: a command
            // object without them is still unlabellable and must keep hitting
            // the fail-fast below, not get a choice of its own.
            Map<UUID, CommandObjectView> resolvedCommandViews = new HashMap<>();
            for (Map.Entry<UUID, PlayableObjectStats> entry : playableObjects.entrySet()) {
                if (resolvedViews.containsKey(entry.getKey())) {
                    continue; // a CardView already labels this key (CommanderView included)
                }
                PlayableObjectStats stats = entry.getValue();
                if (stats == null || stats.getPlayableAmount() == 0) {
                    continue; // no playable ability — nothing to activate
                }
                CommandObjectView commandView = findCommandObjectViewById(entry.getKey(), gameView);
                if (commandView != null) {
                    resolvedCommandViews.put(entry.getKey(), commandView);
                }
            }

            // Pass 1b — for the duplicated key, the SAME ActivatedAbility objects
            // (same ability ids) are registered under the viewable permanent's key.
            // That identity is the alias: it resolves the label without inventing one.
            Map<UUID, List<UUID>> abilityIdsByObject = new HashMap<>();
            for (Map.Entry<UUID, PlayableObjectStats> entry : playableObjects.entrySet()) {
                PlayableObjectStats stats = entry.getValue();
                abilityIdsByObject.put(entry.getKey(),
                        stats != null ? stats.getPlayableAbilityIds() : new ArrayList<UUID>());
            }

            // Pass 2 — build the choices.
            for (Map.Entry<UUID, PlayableObjectStats> entry : playableObjects.entrySet()) {
                UUID objectId = entry.getKey();
                CardView cardView = resolvedViews.get(objectId);
                if (cardView == null) {
                    UUID aliasKey = findPlayableAliasKey(objectId, abilityIdsByObject, resolvedViews.keySet());
                    if (aliasKey != null) {
                        // The SAME abilities are already emitted under the viewable
                        // key (PlayerImpl.getPlayableObjects registers a multi-faced
                        // card's ability under BOTH ability.getSourceId() and
                        // card.getMainCard().getId()). Emitting both keys produced TWO
                        // identical choices — one of them clickable on the board, the
                        // other (this main-card key) matching no rendered card, so the
                        // frontend kept it as a stray button and the DecisionPopup
                        // window opened for a plain priority pass whenever Jace, Vryn's
                        // Prodigy had a tap ability available. The desktop client marks the
                        // main card playable via the SAME single menu, so one choice
                        // per ability set is the correct shape: skip the duplicate.
                        logger.info("[" + username + "] GAME_SELECT: playable object " + objectId
                            + " is the main-card key of playable object " + aliasKey
                            + " (same ability ids) — duplicate choice suppressed (labelled \""
                            + resolvedViews.get(aliasKey).getName() + "\" by that key)");
                        continue;
                    }
                }
                // Pass 1a's resolution: a non-card command object (emblem, plane,
                // dungeon) that carries playable abilities. Only consulted when no
                // CardView labelled the key — a commander stays on the CardView
                // path above, untouched.
                CommandObjectView commandView = cardView == null ? resolvedCommandViews.get(objectId) : null;
                if (cardView == null && commandView == null) {
                    // Fail Fast: a playable object with no viewable card, no
                    // aliasing playable object and no playable-ability command
                    // object cannot be labelled. Do NOT emit a bogus "Unknown"
                    // choice; surface the gap to the
                    // browser and skip the unlabelled entry.
                    String message = "Playable object " + objectId + " has no viewable card and no"
                        + " aliasing playable object — cannot label this choice (ability ids: "
                        + abilityIdsByObject.get(objectId) + ")";
                    logger.error("[" + username + "] " + message);
                    sendToBrowser(jsonError("error", message));
                    continue;
                }

                Map<String, Object> choice = new HashMap<String, Object>();
                choice.put("index", idx);
                choice.put("id", objectId.toString());
                String label = commandView != null ? commandView.getName() : cardView.getName();
                choice.put("name", label);
                logger.info("[" + username + "] GAME_SELECT choice " + idx + ": " + label + " (id=" + objectId + ")");
                if (commandView != null) {
                    // Emblem / plane / dungeon: an ACTIVATE-only command object.
                    // It has no cast or land play, so neither the hand nor the
                    // commander branch applies; the emblem badge in the command
                    // zone is the activation surface (PlayerBoard.tsx). The rules
                    // travel with the choice (and the badge already shows them as
                    // its tooltip — GameStateSerializer's `emblems` list).
                    choice.put("action", "activate");
                    List<String> rules = commandView.getRules();
                    if (rules != null && !rules.isEmpty()) {
                        choice.put("rules", rules);
                    }
                } else {
                    // Determine the action label by checking the card's actual zone.
                    // The old heuristic ("not in my hand" → battlefield → "activate")
                    // was backwards: a flashback spell in the graveyard would get
                    // labeled "activate" instead of "cast". Check the hand first,
                    // then battlefield, then fall back to activate for other zones
                    // (graveyard flashback, exile, command zone).
                    boolean inHand = gameView.getMyHand() != null && gameView.getMyHand().get(objectId) != null;
                    // A play from a CONTROLLED/WATCHED hand is a hand play
                    // too: the controlling session plays the controlled player's
                    // cards during their turn, and the desktop client casts them
                    // from the switched hand view the same way. Label it
                    // cast/land, not activate.
                    if (!inHand && isForeignHandCard(objectId, gameView)) {
                        inHand = true;
                    }
                    // A commander cast from the command zone resolves via
                    // findCardViewById → CommandObjectList (CommanderView). Label it
                    // "cast" (with mana cost) like a hand card, NOT "activate" — the
                    // frontend treats command-zone casts like hand casts.
                    boolean inCommandZone = cardView instanceof CommanderView;
                    if (inHand || inCommandZone) {
                        choice.put("action", cardView.isLand() ? "land" : "cast");
                        String manaCost = cardView.getManaCostStr();
                        if (manaCost != null && !manaCost.isEmpty()) {
                            choice.put("manaCost", manaCost);
                        }
                    } else {
                        // On battlefield (activated ability) or in another zone
                        // (flashback, etc.) — label as activate; the frontend's
                        // off-board choice fallback catches non-battlefield cards.
                        choice.put("action", "activate");
                    }
                }

                choices.add(choice);
                backings.add(objectId);
                idx++;
            }
        }

        Map<String, Serializable> options = gcm.getOptions();
        if (options != null) {
            Object possibleAttackers = options.get("possibleAttackers");
            Object possibleBlockers = options.get("possibleBlockers");
            if (possibleAttackers instanceof List) {
                @SuppressWarnings("unchecked")
                List<UUID> attackerIds = (List<UUID>) possibleAttackers;
                logger.info("[" + username + "] GAME_SELECT has " + attackerIds.size() + " possibleAttackers");
                for (UUID attackerId : attackerIds) {
                    CardView cardView = findCardViewById(attackerId, gameView);
                    Map<String, Object> choice = new HashMap<>();
                    choice.put("index", idx);
                    choice.put("id", attackerId.toString());
                    if (cardView != null) {
                        choice.put("name", cardView.getName());
                    } else {
                        choice.put("name", "Attacker");
                    }
                    choice.put("action", "attack");
                    choices.add(choice);
                    backings.add(attackerId);
                    idx++;
                }
            }
            if (possibleBlockers instanceof List) {
                @SuppressWarnings("unchecked")
                List<UUID> blockerIds = (List<UUID>) possibleBlockers;
                logger.info("[" + username + "] GAME_SELECT has " + blockerIds.size() + " possibleBlockers");
                for (UUID blockerId : blockerIds) {
                    CardView cardView = findCardViewById(blockerId, gameView);
                    Map<String, Object> choice = new HashMap<>();
                    choice.put("index", idx);
                    choice.put("id", blockerId.toString());
                    if (cardView != null) {
                        choice.put("name", cardView.getName());
                    } else {
                        choice.put("name", "Blocker");
                    }
                    choice.put("action", "block");
                    choices.add(choice);
                    backings.add(blockerId);
                    idx++;
                }
            }
        }

        pendingChoiceBackings = backings;

        // Failed play_card detection: a play_card UUID
        // the server couldn't act on leaves it re-sending the same bare priority
        // GAME_SELECT. Detect that and toast instead of silently swallowing the
        // click. A real cast flow produces a DIFFERENT prompt next (choose ability,
        // mana payment, targets...), which clears the attempt via the non-GAME_SELECT
        // guard above or via a changed message.
        //
        // Correlation must NOT be "same gameCycle": gameCycle is applyEffectsCounter
        // (GameView.java:224) and applyEffects() runs on every priority-loop
        // iteration (GameImpl.playPriority), so the re-prompt always lands at
        // snapshot cycle + 1 (occasionally +2/+3 when state-trigger cascades run
        // extra applyEffects passes). A re-prompt within a few cycles with an
        // identical message is the no-op signature; a real cast never re-prompts
        // with the identical bare priority message.
        if (lastPlayCardAttemptId != null) {
            String newPrompt = gcm.getMessage() != null ? gcm.getMessage() : "";
            int newCycle = gameView != null ? gameView.getGameCycle() : -1;
            int cycleDelta = lastSelectGameCycle >= 0 ? newCycle - lastSelectGameCycle : -1;
            boolean noOpResponse = lastSelectPromptMessage != null
                    && lastSelectPromptMessage.equals(newPrompt)
                    && cycleDelta >= 1 && cycleDelta <= 5;
            if (noOpResponse) {
                CardView attempted = findCardViewById(lastPlayCardAttemptId, gameView);
                String cardName = attempted != null ? attempted.getName() : lastPlayCardAttemptId.toString();
                String failMsg = "Can't play \"" + cardName + "\" right now (not castable/activatable in this game state)";
                logger.info("[" + username + "] play_card rejected by server (cycleDelta=" + cycleDelta + "): " + cardName);
                sendToBrowser(jsonError("error", failMsg));
            }
            lastPlayCardAttemptId = null;
        }

        // Snapshot this prompt for the next comparison
        lastSelectPromptMessage = gcm.getMessage() != null ? gcm.getMessage() : "";
        lastSelectGameCycle = gameView != null ? gameView.getGameCycle() : -1;

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "action_prompt");
        msg.put("action_type", "select");
        String selectMsg = gcm.getMessage() != null ? gcm.getMessage() : "Select card to play or pass priority";
        msg.put("message", selectMsg);
        msg.put("choices", choices);
        msg.put("can_pass", true);

        // Structured metadata: detect combat vs card selection context
        // (options was already declared earlier in this method)
        if (options != null) {
            if (options.containsKey("possibleAttackers")) {
                msg.put("context", "declare_attackers");
            } else if (options.containsKey("possibleBlockers")) {
                msg.put("context", "declare_blockers");
            } else {
                msg.put("context", "select_card");
            }
            // Pass SPECIAL_BUTTON (e.g. "All attack") to the frontend
            Object specialButton = options.get("specialButton");
            if (specialButton instanceof String) {
                msg.put("special_button", specialButton);
            }
        } else {
            msg.put("context", "select_card");
        }
        msg.put("choice_count", choices.size());
        sendToBrowser(gsonToJson(msg));
    }

    /**
     * GAME_ASK: Yes/no dialog (mulligan, keep hand, etc.)
     * Response: sendPlayerBoolean(gameId, true/false)
     *
     * Button labels come from the engine, not from us. Every GAME_ASK producer
     * ships its button texts in the options map ("UI.left.btn.text" labels the
     * button that answers TRUE, "UI.right.btn.text" the one that answers FALSE —
     * HumanPlayer.chooseMulligan:403-405 and HumanPlayer.chooseUse:437-444; the
     * Swing client labels its buttons from the same keys, FeedbackPanel.java:185).
     * Mulligan asks arrive as left="Mulligan"/right="Keep", so the boolean
     * mapping (left→true, right→false) already matches the web client's
     * BooleanPrompt (yes button → booleanChoice(true)); only the labels were
     * wrong — hardcoded "Yes"/"No" contradicted the question ("Mulligan down to
     * 6 cards? [Yes] [No]", where Yes throws the hand away).
     */
    private void handleGameAsk(GameClientMessage gcm, GameView gameView) {
        pendingChoiceBackings = null;

        Map<String, Serializable> options = gcm.getOptions();

        // Fail fast: every GAME_ASK the engine can fire (chooseUse, chooseMulligan)
        // populates both option keys with non-empty text. Missing/empty labels are an
        // engine-contract violation — surfacing them as an unlabeled or mislabeled
        // pair would invite exactly the wrong-button click this fix removes. (The
        // trigger-order prompt handler above follows the same rule: "surface it
        // loudly instead of shipping another unanswerable prompt.")
        String left = options != null && options.get("UI.left.btn.text") instanceof String
                ? (String) options.get("UI.left.btn.text") : null;
        String right = options != null && options.get("UI.right.btn.text") instanceof String
                ? (String) options.get("UI.right.btn.text") : null;
        if (left == null || left.trim().isEmpty() || right == null || right.trim().isEmpty()) {
            String loud = "GAME_ASK arrived without button labels in options"
                    + " (left=" + (left == null ? "null" : "'" + left + "'")
                    + ", right=" + (right == null ? "null" : "'" + right + "'")
                    + ", message=" + (gcm.getMessage() != null ? gcm.getMessage() : "null") + ")";
            logger.error("[" + username + "] " + loud);
            sendToBrowser(jsonError("error", loud));
            return;
        }

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "action_prompt");
        msg.put("action_type", "boolean");
        String displayedMessage = gcm.getMessage() != null ? gcm.getMessage() : "Yes or No?";
        msg.put("message", displayedMessage);

        // Structured metadata: detect mulligan context
        String lowerMsg = (gcm.getMessage() != null ? gcm.getMessage() : "").toLowerCase();
        if (lowerMsg.contains("mulligan") || lowerMsg.contains("keep") || lowerMsg.contains("hand")) {
            msg.put("context", "mulligan");
        } else {
            msg.put("context", "yes_no");
        }
        msg.put("yes_label", left);
        msg.put("no_label", right);

        // ── Auto-answer key material ──
        // The engine keys remembered yes/no answers by `source.getOriginalId() + "#" +
        // autoAnswerMessage` (id map) or by autoAnswerMessage alone (text map)
        // (HumanPlayer.chooseUse:455-465). Both values ride the ask's options map
        // (PlayerQueryEvent.askEvent:149-157 adds Constants.Option.ORIGINAL_ID;
        // HumanPlayer.chooseUse:453 adds AUTO_ANSWER_MESSAGE) but were dropped by this
        // proxy — without them the browser cannot build the key its own future lookup
        // must match, so an "always answer" affordance would store a dead entry.
        //
        // Additive JSON fields only: this payload is a proxy-built HashMap serialized
        // with Gson (not a Serializable view), so no serialVersionUID / wire-compat
        // class is touched.
        Serializable originalIdOption = options != null ? options.get(Constants.Option.ORIGINAL_ID) : null;
        if (originalIdOption instanceof UUID) {
            msg.put("original_id", originalIdOption.toString());
        }
        // The ID-variant needs the ask's ability id; source-less asks (chooseMulligan)
        // have none, and the browser then offers only the text variant — the same
        // gating the desktop client applies (HelperPanel.java:532-539 disables the
        // id items when originalId == null).
        Serializable autoAnswerOption = options != null ? options.get(Constants.Option.AUTO_ANSWER_MESSAGE) : null;
        String autoAnswerMessage = autoAnswerOption instanceof String ? (String) autoAnswerOption : null;
        if (autoAnswerMessage == null) {
            // Desktop fallback (FeedbackPanel.java:106-111): when the ask carries no
            // filtered message, the DISPLAYED question text is the key. A source-less
            // ask has nothing to filter (autoAnswerMessage == message in chooseUse).
            autoAnswerMessage = displayedMessage;
        }
        if (autoAnswerMessage != null && !autoAnswerMessage.isEmpty()) {
            msg.put("auto_answer_message", autoAnswerMessage);
        }
        sendToBrowser(gsonToJson(msg));
    }

    /**
     * GAME_TARGET: Select target(s) for a spell/ability.
     * Valid targets come from gcm.getTargets() (Set<UUID>).
     * Response: sendPlayerUUID(gameId, targetUUID) per target, or sendPlayerBoolean(false) to cancel.
     */
    @SuppressWarnings("unchecked")
    private void handleGameTarget(GameClientMessage gcm, GameView gameView) {
        Set<UUID> targets = gcm.getTargets();
        boolean required = gcm.isFlag();
        pendingTargetRequired = required;
        boolean triggerOrderPrompt = false;

        Map<String, Serializable> options = gcm.getOptions();

        // For library searches (Once Upon a Time, fetch lands, etc.), the valid
        // targets are in options.get("possibleTargets"), not in gcm.getTargets().
        if (targets == null || targets.isEmpty()) {
            if (options != null && options.containsKey("possibleTargets")) {
                Object possibleTargets = options.get("possibleTargets");
                if (possibleTargets instanceof Set) {
                    targets = (Set<UUID>) possibleTargets;
                    logger.info("[" + username + "] GAME_TARGET: using possibleTargets from options (" + targets.size() + " targets)");
                }
            }
        }

        // Trigger-order prompt ("Pick triggered ability (goes to the stack first)",
        // the engine fires
        // PlayerQueryEvent.QueryType.PICK_ABILITY (HumanPlayer.chooseTriggeredAbility,
        // HumanPlayer.java:1599), which GameController turns into
        // `new CardsView(abilities, game)` (GameController.java:927). Those
        // candidates arrive in cardsView1 with NEITHER gcm.getTargets() NOR
        // options["possibleTargets"], so without the mapping below `targets` stayed
        // null, every candidate was marked invalid and the prompt was unanswerable
        // in the UI (a live game was blocked 13 minutes).
        //
        // Each candidate is an AbilityView keyed by its ability UUID, and that UUID
        // is exactly the response the engine consumes (HumanPlayer.chooseTriggeredAbility
        // matches `ability.getId().equals(responseId)`), i.e. the candidates ARE the
        // selectable targets.
        boolean triggerOrder = options != null
                && options.get("queryType") == PlayerQueryEvent.QueryType.PICK_ABILITY;
        if (triggerOrder && (targets == null || targets.isEmpty())) {
            Set<UUID> abilityIds = new LinkedHashSet<>();
            if (gcm.getCardsView1() != null) {
                for (CardView candidate : gcm.getCardsView1().values()) {
                    if (candidate.getId() != null) {
                        abilityIds.add(candidate.getId());
                    }
                }
            }
            if (abilityIds.isEmpty()) {
                // Cannot happen: chooseTriggeredAbility only prompts for >= 2 abilities
                // and CardsView(abilities, game) keys each resolved one. Surface it
                // loudly instead of shipping another unanswerable prompt.
                String loud = "Trigger-order prompt arrived with no ability candidates "
                        + "(queryType=PICK_ABILITY, cardsView1="
                        + (gcm.getCardsView1() != null ? gcm.getCardsView1().size() : "null") + ")";
                logger.error("[" + username + "] " + loud);
                sendToBrowser(jsonError("error", loud));
            } else {
                targets = abilityIds;
                triggerOrderPrompt = true;
                logger.info("[" + username + "] GAME_TARGET: trigger-order (PICK_ABILITY) — using "
                        + abilityIds.size() + " ability candidate(s) from cardsView1 as selectable targets");
            }
        }

        logger.info("[" + username + "] GAME_TARGET: targets=" + (targets != null ? targets.size() : "null")
            + " required=" + required + " message=" + gcm.getMessage());

        List<Object> backings = new ArrayList<>();
        List<Map<String, Object>> targetList = new ArrayList<>();

        if (targets != null) {
            // Build a lookup map from cardsView1 (library search cards) so we can
            // resolve target IDs that aren't on the battlefield/hand (e.g. library cards).
            Map<UUID, CardView> lookAtCardMap = new HashMap<>();
            if (gcm.getCardsView1() != null) {
                for (CardView card : gcm.getCardsView1().values()) {
                    if (card.getId() != null) lookAtCardMap.put(card.getId(), card);
                }
            }

            for (UUID targetId : targets) {
                CardView cardView = findCardViewById(targetId, gameView);
                // If not found on board, check the look-at cards (library search)
                if (cardView == null) {
                    cardView = lookAtCardMap.get(targetId);
                }
                Map<String, Object> target = new HashMap<>();
                target.put("id", targetId.toString());
                if (cardView != null) {
                    target.put("name", candidateDisplayName(cardView));
                    // For trigger-order candidates the AbilityView's own rules text is
                    // the trigger text (AbilityView.java:25 `rules.add(ability.getRule())`)
                    // — carry it so the popup can show WHAT is being ordered, not just
                    // which card it came from.
                    if (cardView instanceof AbilityView) {
                        target.put("abilityText", candidateAbilityText(cardView));
                    }
                    target.put("type", cardView.isCreature() ? "creature" : "permanent");
                } else {
                    // Check if it's a player
                    String playerName = null;
                    if (gameView != null) {
                        for (PlayerView player : gameView.getPlayers()) {
                            if (targetId.equals(player.getPlayerId())) {
                                playerName = player.getName();
                                break;
                            }
                        }
                    }
                    target.put("name", playerName != null ? playerName : "Unknown");
                    target.put("type", playerName != null ? "player" : "unknown");
                }
                logger.info("[" + username + "] Target: id=" + targetId + " name=" + target.get("name") + " type=" + target.get("type"));
                targetList.add(target);
                backings.add(targetId);
            }
        }

        pendingChoiceBackings = backings;

        // Build structured metadata for the frontend — instead of parsing the
        // message string, the frontend can read these explicit flags.
        // Inspired by Argentum's LegalActionInfo with needsTargets, minTargets, etc.
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "action_prompt");
        msg.put("action_type", "target");
        // Prompt attribution: the engine puts the prompt's TRUE source into
        // options["secondMessage"] (MessageToClient -> GameImpl.addMessageToOptions
        // -> Constants.Option.SECOND_MESSAGE; the desktop client renders it as the
        // secondary line, FeedbackPanel.java:87). Use it.
        //
        // It must NOT be built from lastSelectedCardName: that field is set by ANY
        // select_choice / select_target click (WebProxySession) and is therefore
        // whatever the player last touched, not the source of THIS prompt. The
        // client saw Guide of Souls' attack trigger titled "Select an attacking
        // creature for Amped Raptor" and a planeswalker's target prompt as
        // "... for Unknown" for exactly that reason.
        String baseMessage = gcm.getMessage() != null ? gcm.getMessage() : "Choose target";
        Map<String, Serializable> srcOptions = gcm.getOptions();
        Object secondMessage = srcOptions != null ? srcOptions.get("secondMessage") : null;
        if (secondMessage instanceof String && !((String) secondMessage).isEmpty()) {
            baseMessage = baseMessage + " for " + secondMessage;
        }
        msg.put("message", baseMessage);
        msg.put("targets", targetList);
        msg.put("required", required);
        msg.put("can_cancel", !required);

        // Any-number target UX: for mechanics like "exile any number of
        // permanents" (TargetPermanent(0, MAX)), the server re-fires
        // GAME_TARGET per selection and passes the already-chosen set +
        // the finish button label via options. Surface both to the frontend
        // so the prompt can show "N chosen" and a "Done" button instead of
        // a bare Skip. Mirrors desktop FeedbackPanel.java:190.
        Map<String, Serializable> tgtOptions = gcm.getOptions();
        if (tgtOptions != null) {
            Object doneText = tgtOptions.get("UI.right.btn.text");
            if (doneText instanceof String) {
                msg.put("done_button_text", (String) doneText);
            }
            Object chosen = tgtOptions.get("chosenTargets");
            if (chosen instanceof Set) {
                @SuppressWarnings("unchecked")
                Set<UUID> chosenIds = (Set<UUID>) chosen;
                List<String> chosenStrs = new ArrayList<>();
                for (UUID cid : chosenIds) {
                    chosenStrs.add(cid.toString());
                }
                msg.put("chosen_targets", chosenStrs);
                msg.put("chosen_count", chosenIds.size());
            }
        }

        // Structured metadata: target count + valid target types
        //
        // The engine does
        // NOT transmit min/max targets for a GAME_TARGET — GameSessionPlayer.target()
        // builds a GameClientMessage whose min/max stay 0 (only the amount/multi-amount
        // ctors fill them; see GameClientMessage.java ctors at 59 and 68). `targets`
        // here is the CANDIDATE list, so reporting `targets.size()` as the number to
        // select is an invention of this proxy: a one-card fetchland search over 8
        // legal candidates announced "Select 8 targets", and the trigger prompt
        // announced "Select 0 targets". The desktop client never makes that claim — it
        // shows the prompt message and relies on the engine's own "Done" button, which
        // HumanPlayer.getOptions() adds once the target's minimum is satisfied
        // (HumanPlayer.java:843-853).
        //
        // So: `target_count` is a claim about how many targets must be selected, and the
        // engine only ever makes that claim for the trigger-order family (1). For every
        // other target prompt it transmits NOTHING — not even "1" — so the honest value
        // is 0, and the UI words the prompt without a count. The candidate list size is
        // reported separately as `max_targets`, which is what it actually is: the upper
        // bound of what can be clicked right now.
        //
        // (2026-09-17 correction: the first version of this change fell back to
        // `candidateCount` when no Done button was present, which still fabricated
        // "Select 13 targets" on a Marsh Flats search — 13 being the candidate count.
        // Found live by the SESSION A test agent; the fallback is what was wrong, not
        // the concept.)
        int candidateCount = targets != null ? targets.size() : 0;
        int targetCount = triggerOrderPrompt ? 1 : 0;
        msg.put("target_count", targetCount);
        msg.put("needs_targets", candidateCount > 0);
        msg.put("min_targets", required ? 1 : 0);
        msg.put("max_targets", candidateCount);
        // item 3 (arrows bug): a NOT_TARGET choice ("exile a card from your
        // hand" — Chrome Mox imprint, surveil-style lists) is a card LIST, not spell
        // targeting. The engine stamps the flag on the Target object (Target.isNotTarget,
        // HumanPlayer.getOptions puts it in the prompt options); forward it so the web UI
        // does not draw source→target arrows for a plain choose-a-card prompt.
        Object notTarget = tgtOptions != null ? tgtOptions.get("notTarget") : null;
        msg.put("not_target", Boolean.TRUE.equals(notTarget));
        // Trigger-order is a ONE-pick prompt even though it lists N candidates: the
        // frontend needs this flag to label the entries card + ability text and to
        // word the prompt as an ordering question.
        msg.put("trigger_order", triggerOrderPrompt);

        // Collect valid target types from the target list
        Set<String> validTargetTypes = new java.util.TreeSet<>();
        for (Map<String, Object> t : targetList) {
            String tType = (String) t.get("type");
            if (tType != null) validTargetTypes.add(tType);
        }
        msg.put("valid_target_types", new ArrayList<>(validTargetTypes));

        // Detect special target contexts from the message. Deliberately uses the
        // engine's own prompt message, NOT baseMessage: baseMessage may now carry the
        // appended source name, and a card called e.g. "Blockade Runner" would
        // otherwise flip the context to declare_blockers.
        String lowerMsg = (gcm.getMessage() != null ? gcm.getMessage() : "").toLowerCase();
        if (triggerOrderPrompt) {
            // The message text ("Pick triggered ability…") does not contain any of the
            // keywords below, so without this the frontend saw context="default" and
            // had no way to know this was the trigger-order prompt family.
            msg.put("context", "trigger_order");
        } else if (lowerMsg.contains("attack") || lowerMsg.contains("attacker")) {
            msg.put("context", "declare_attackers");
        } else if (lowerMsg.contains("block") || lowerMsg.contains("blocker")) {
            msg.put("context", "declare_blockers");
        } else if (lowerMsg.contains("library")) {
            msg.put("context", "library_search");
        } else if (lowerMsg.contains("graveyard")) {
            msg.put("context", "graveyard_target");
        } else if (lowerMsg.contains("exile")) {
            msg.put("context", "exile_target");
        } else {
            msg.put("context", "default");
        }

        // Include cardsView1 (cards being looked at, e.g. during surveil/scry/fetch land)
        // The desktop client shows these in a ShowCardsDialog alongside the target prompt.
        if (gcm.getCardsView1() != null && !gcm.getCardsView1().isEmpty()) {
            // Build a set of valid target IDs for the popup to highlight.
            // We compare by String (UUID.toString() is deterministic), so a single
            // Set.contains() is sufficient — no need to loop targets again.
            Set<String> validTargetIdStrs = new HashSet<>();
            if (targets != null) {
                for (UUID tId : targets) {
                    validTargetIdStrs.add(tId.toString());
                }
            }
            logger.info("[" + username + "] GAME_TARGET: targets=" + (targets != null ? targets.size() : "null")
                + " validIdStrs=" + validTargetIdStrs.size());
            List<Map<String, Object>> lookAtCards = new ArrayList<>();
            for (CardView card : gcm.getCardsView1().values()) {
                String cardIdStr = card.getId() != null ? card.getId().toString() : null;
                boolean isTarget = cardIdStr != null && validTargetIdStrs.contains(cardIdStr);
                Map<String, Object> c = new HashMap<>();
                c.put("name", candidateDisplayName(card));
                c.put("typeLine", card.getTypeText());
                c.put("manaCost", card.getManaCostStr());
                c.put("rules", card.getRules());
                c.put("id", cardIdStr);
                // Ability candidates (trigger order) are AbilityViews whose name is the
                // literal "Ability" and whose art lives on the SOURCE card. Keep the
                // source name + its image so the popup and any image lookup use them
                // instead of requesting a non-existent "Ability" card.
                if (card instanceof AbilityView) {
                    CardView source = ((AbilityView) card).getSourceCard();
                    if (source != null) {
                        c.put("sourceName", source.getName());
                        c.put("sourceImageUrl", GameStateSerializer.buildImageUrl(source.getName()));
                    }
                    c.put("abilityText", candidateAbilityText(card));
                }
                c.put("imageUrl", GameStateSerializer.buildImageUrl(candidateDisplayName(card)));
                // Mark whether this card is a valid target
                c.put("isValidTarget", isTarget);
                lookAtCards.add(c);
            }
            msg.put("lookAtCards", lookAtCards);
            // Also pass the valid target IDs directly
            msg.put("lookAtCardsValidIds", new ArrayList<>(validTargetIdStrs));
            logger.info("[" + username + "] GAME_TARGET includes cardsView1 with " + lookAtCards.size()
                + " cards, " + validTargetIdStrs.size() + " valid targets");
            // Per-card validity is verbose — log at debug, not info (scry/surveil
            // otherwise floods the server log with one line per revealed card).
            if (logger.isDebugEnabled()) {
                for (Map<String, Object> c : lookAtCards) {
                    logger.debug("[" + username + "]   card: " + c.get("name") + " id=" + c.get("id") + " valid=" + c.get("isValidTarget"));
                }
            }
        }

        sendToBrowser(gsonToJson(msg));
    }

    /**
     * Display name for a GAME_TARGET candidate that may be an AbilityView.
     *
     * An AbilityView's {@code name} is the literal string "Ability"
     * (AbilityView.java:21) and the real identity lives in {@code sourceName}
     * (the card that owns the ability). Labelling the tile "Ability" is what made
     * the trigger-order prompt unreadable AND made the frontend request a
     * non-existent "Ability" card image.
     *
     * Returns the SOURCE card's name for an ability view (the engine-rendered
     * trigger text is carried separately in "abilityText"), so the UI can label
     * the entry by card + ability text without re-parsing a concatenated string.
     */
    private static String candidateDisplayName(CardView card) {
        if (!(card instanceof AbilityView)) {
            return card.getName();
        }
        AbilityView av = (AbilityView) card;
        CardView source = av.getSourceCard();
        if (source != null && source.getName() != null) {
            return source.getName();
        }
        // No source card on the view — the ability text is all we have. Never fall
        // back to the literal "Ability" that caused the defect.
        String abilityText = candidateAbilityText(card);
        return abilityText != null ? abilityText : "Ability";
    }

    /**
     * The rules/trigger text of an ability candidate, or null when the view is
     * not an ability. AbilityView stores exactly one rule — the engine-rendered
     * {@code ability.getRule()} (AbilityView.java:25).
     *
     * {@code TriggeredAbilityImpl.getRule()} leaves the self-reference as the
     * literal "{this}" unless a source name is passed (AbilityImpl.formatRule,
     * used by the engine's own "Ability triggers: <card> - <rule>" log line).
     * Substitute the source card name so the popup names the trigger exactly the
     * way the game log does.
     */
    private static String candidateAbilityText(CardView card) {
        if (!(card instanceof AbilityView)) {
            return null;
        }
        List<String> rules = card.getRules();
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        String text = String.join("\n", rules);
        CardView source = ((AbilityView) card).getSourceCard();
        if (source != null && source.getName() != null && text.contains("{this}")) {
            text = text.replace("{this}", source.getName());
        }
        return text;
    }

    /**
     * GAME_CHOOSE_CHOICE: Select from a list of named choices (e.g., "choose starting player").
     * Data: GameClientMessage with a Choice object.
     * Response: sendPlayerString(gameId, choiceKey) or sendPlayerString(gameId, choiceValue)
     */
    private void handleGameChooseChoice(GameClientMessage gcm, GameView gameView) {
        Choice choice = gcm.getChoice();
        List<Object> backings = new ArrayList<>();
        List<Map<String, Object>> choicesList = new ArrayList<>();

        if (choice != null) {
            // Choice has key choices (Map<String, String>) or simple choices (Set<String>)
            Map<String, String> keyChoices = choice.getKeyChoices();
            if (keyChoices != null && !keyChoices.isEmpty()) {
                // Per-option relevance: mage.choices.ChoiceCreatureType.onChooseStart ranks
                // every type (me = -100, opponent = -10, unused = 0), and the desktop client
                // sorts the dialog by it (PickChoiceDialog.java:116-127 — choice.getSortData()).
                // Forward the engine's value verbatim so the browser can group the suggested
                // types. An option the engine did not rank gets NO order field — never invent
                // a value (null-safe: getSortData is null when no sort data was set at all).
                Map<String, Integer> sortData = choice.getSortData();
                int idx = 0;
                for (Map.Entry<String, String> entry : keyChoices.entrySet()) {
                    Map<String, Object> c = new HashMap<>();
                    c.put("index", idx);
                    c.put("key", entry.getKey());
                    c.put("label", entry.getValue());
                    if (sortData != null) {
                        Integer order = sortData.get(entry.getKey());
                        if (order != null) {
                            c.put("order", order);
                        }
                    }
                    choicesList.add(c);
                    backings.add(entry.getKey()); // send back the key
                    idx++;
                }
            } else {
                Set<String> simpleChoices = choice.getChoices();
                if (simpleChoices != null) {
                    int idx = 0;
                    for (String ch : simpleChoices) {
                        Map<String, Object> c = new HashMap<>();
                        c.put("index", idx);
                        c.put("label", ch);
                        choicesList.add(c);
                        backings.add(ch);
                        idx++;
                    }
                }
            }
        }

        pendingChoiceBackings = backings;

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "action_prompt");
        msg.put("action_type", "choose_choice");
        String choiceMsg = gcm.getMessage() != null ? gcm.getMessage() :
            (choice != null && choice.getMessage() != null ? choice.getMessage() : "Choose an option");
        msg.put("message", choiceMsg);
        msg.put("options", choicesList);

        // Structured metadata: detect context from message
        String lowerMsg = choiceMsg.toLowerCase();
        if (lowerMsg.contains("order") || lowerMsg.contains("trigger") || lowerMsg.contains("stack")) {
            msg.put("context", "order_triggers");
        } else if (lowerMsg.contains("starting player") || lowerMsg.contains("play first")) {
            msg.put("context", "choose_starting_player");
        } else if (lowerMsg.contains("mode") || lowerMsg.contains("choose one")) {
            msg.put("context", "choose_mode");
        } else if (lowerMsg.contains("color")) {
            msg.put("context", "choose_color");
        } else {
            msg.put("context", "choose_option");
        }
        msg.put("choice_count", choicesList.size());
        sendToBrowser(gsonToJson(msg));
    }

    /**
     * GAME_CHOOSE_ABILITY from AbilityPickerView: Select which ability to activate.
     * AbilityPickerView has choices as Map<UUID, String> (ability UUID → ability rule text).
     * Response: sendPlayerUUID(gameId, abilityUUID)
     */
    private void handleGameChooseAbilityAPV(AbilityPickerView apv) {
        Map<UUID, String> choices = apv.getChoices();
        List<Object> backings = new ArrayList<>();
        List<Map<String, Object>> abilitiesList = new ArrayList<>();

        if (choices != null) {
            int idx = 0;
            for (Map.Entry<UUID, String> entry : choices.entrySet()) {
                Map<String, Object> a = new HashMap<>();
                a.put("index", idx);
                a.put("id", entry.getKey().toString());
                a.put("name", entry.getValue());
                abilitiesList.add(a);
                backings.add(entry.getKey()); // UUID backing
                idx++;
            }
        }

        pendingChoiceBackings = backings;

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "action_prompt");
        msg.put("action_type", "choose_ability");
        msg.put("message", apv.getMessage() != null ? apv.getMessage() : "Choose ability");
        msg.put("abilities", abilitiesList);
        sendToBrowser(gsonToJson(msg));
    }

    /**
     * GAME_CHOOSE_ABILITY: Select which ability to activate (e.g., dual land mana abilities).
     * Data: GameClientMessage with options map containing ability UUIDs.
     * Response: sendPlayerUUID(gameId, abilityUUID)
     */
    private void handleGameChooseAbility(GameClientMessage gcm, GameView gameView) {
        Map<String, Serializable> options = gcm.getOptions();
        List<Object> backings = new ArrayList<>();
        List<Map<String, Object>> abilitiesList = new ArrayList<>();

        if (options != null) {
            Object abilitiesObj = options.get("abilities");
            if (abilitiesObj instanceof Map) {
                Map<?, ?> abilities = (Map<?, ?>) abilitiesObj;
                int idx = 0;
                for (Map.Entry<?, ?> entry : abilities.entrySet()) {
                    Map<String, Object> a = new HashMap<>();
                    a.put("index", idx);
                    a.put("id", entry.getKey().toString());
                    a.put("name", entry.getValue() != null ? entry.getValue().toString() : "Unknown");
                    abilitiesList.add(a);
                    try {
                        backings.add(UUID.fromString(entry.getKey().toString()));
                    } catch (IllegalArgumentException e) {
                        backings.add(entry.getKey().toString());
                    }
                    idx++;
                }
            }
        }

        pendingChoiceBackings = backings;

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "action_prompt");
        msg.put("action_type", "choose_ability");
        msg.put("message", gcm.getMessage() != null ? gcm.getMessage() : "Choose ability");
        msg.put("abilities", abilitiesList);
        sendToBrowser(gsonToJson(msg));
    }

    /**
     * GAME_GET_AMOUNT: Choose a number (e.g., how much damage, how many counters).
     * Data: GameClientMessage with min/max fields.
     * Response: sendPlayerInteger(gameId, amount)
     */
    private void handleGameGetAmount(GameClientMessage gcm, GameView gameView) {
        pendingChoiceBackings = null;

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "action_prompt");
        msg.put("action_type", "amount");
        String amountMsg = gcm.getMessage() != null ? gcm.getMessage() : "Choose amount";
        msg.put("message", amountMsg);
        msg.put("min", gcm.getMin());
        msg.put("max", gcm.getMax());

        // Structured metadata: detect X-cost context
        String lowerMsg = amountMsg.toLowerCase();
        if (lowerMsg.contains("x") || lowerMsg.contains("mana")) {
            msg.put("context", "x_cost");
        } else if (lowerMsg.contains("damage")) {
            msg.put("context", "damage_amount");
        } else if (lowerMsg.contains("counter")) {
            msg.put("context", "counter_amount");
        } else if (lowerMsg.contains("life")) {
            msg.put("context", "life_amount");
        } else {
            msg.put("context", "amount");
        }
        sendToBrowser(gsonToJson(msg));
    }

    /**
     * GAME_CHOOSE_PILE: Choose between two piles of cards (e.g., Fact or Fiction).
     * Data: GameClientMessage with cardsView1 (pile1) and cardsView2 (pile2).
     * Response: sendPlayerInteger(gameId, pileNumber) — 0 or 1
     */
    private void handleGameChoosePile(GameClientMessage gcm, GameView gameView) {
        pendingChoiceBackings = null;

        List<Map<String, Object>> pile1 = new ArrayList<>();
        List<Map<String, Object>> pile2 = new ArrayList<>();

        if (gcm.getCardsView1() != null) {
            for (CardView card : gcm.getCardsView1().values()) {
                pile1.add(serializeCardSimple(card));
            }
        }
        if (gcm.getCardsView2() != null) {
            for (CardView card : gcm.getCardsView2().values()) {
                pile2.add(serializeCardSimple(card));
            }
        }

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "action_prompt");
        msg.put("action_type", "choose_pile");
        msg.put("message", gcm.getMessage() != null ? gcm.getMessage() : "Choose a pile");
        msg.put("pile1", pile1);
        msg.put("pile2", pile2);
        msg.put("context", "choose_pile");
        sendToBrowser(gsonToJson(msg));
    }

    /**
     * GAME_PLAY_MANA / GAME_PLAY_XMANA: Choose how to pay mana.
     * The player can either:
     * - Click auto-pay to let the server auto-tap lands
     * - Click individual untapped lands on the battlefield to tap for mana manually
     * Manual tapping sends sendPlayerUUID(gameId, landId) which activates the
     * land's mana ability, same as the desktop client.
     */
    private void handleGamePlayMana(GameClientMessage gcm, GameView gameView, ClientCallbackMethod method) {
        pendingChoiceBackings = null;

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "action_prompt");
        msg.put("action_type", "play_mana");
        String manaMsg = gcm.getMessage() != null ? gcm.getMessage() : "Pay mana";
        msg.put("message", manaMsg);
        msg.put("auto_pay_available", true);
        // Mirror Java client: show Special button only when special actions are available
        // (e.g. fetch lands, convoke, delve). gameView.getSpecial() is set by the server
        // when state.getSpecialActions() is non-empty for the priority player in pay-mana mode.
        msg.put("special_available", gameView != null && gameView.getSpecial());

        // Per-source "the ENGINE says this source can pay the CURRENT unpaid cost"
        // flags (the server keys them under the play_mana prompt option
        // "manaPayableForThisCost", HumanPlayer.OPTION_MANA_PAYABLE_FOR_THIS_COST).
        // Needed because a conditional any-colour source (Secluded Courtyard,
        // Cavern of Souls, …) cannot be characterised from its rule text alone —
        // only the engine knows whether its restriction holds for the object/cost
        // being paid. Forwarded verbatim; absent means "the server did not
        // evaluate", which the frontend must treat as "unknown — do not tap"
        // (never as payable). Value on the wire: permanentId -> boolean.
        //
        // The option name is literal here on purpose: Mage.WebProxy depends on
        // mage-common, NOT on mage-player-human, so the constant cannot be
        // referenced from this module.
        Map<String, Object> payableBySource = new HashMap<>();
        Map<String, Serializable> payOptions = gcm.getOptions();
        if (payOptions != null) {
            Object rawPayable = payOptions.get("manaPayableForThisCost");
            if (rawPayable instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawPayable).entrySet()) {
                    if (entry.getKey() == null) {
                        continue;
                    }
                    Object payable = entry.getValue();
                    if (payable instanceof Boolean) {
                        payableBySource.put(entry.getKey().toString(), payable);
                    }
                }
            }
        }
        if (!payableBySource.isEmpty()) {
            msg.put("mana_payable_for_this_cost", payableBySource);
        }

        // Structured metadata: detect X-mana vs regular mana payment
        if (method == ClientCallbackMethod.GAME_PLAY_XMANA) {
            msg.put("context", "x_mana_payment");
        } else {
            msg.put("context", "mana_payment");
        }
        sendToBrowser(gsonToJson(msg));
    }

    /**
     * GAME_GET_MULTI_AMOUNT: Choose multiple amounts (e.g., distribute damage).
     */
    private void handleGameGetMultiAmount(GameClientMessage gcm, GameView gameView) {
        pendingChoiceBackings = null;

        List<Map<String, Object>> amounts = new ArrayList<>();
        if (gcm.getMessages() != null) {
            for (mage.util.MultiAmountMessage msg : gcm.getMessages()) {
                Map<String, Object> a = new HashMap<>();
                a.put("label", msg.message);
                a.put("min", msg.min);
                a.put("max", msg.max);
                a.put("default", msg.defaultValue);
                amounts.add(a);
            }
        }

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "action_prompt");
        msg.put("action_type", "multi_amount");
        String multiAmountMsg = gcm.getMessage() != null ? gcm.getMessage() : "Choose amounts";
        msg.put("message", multiAmountMsg);
        msg.put("amounts", amounts);
        msg.put("min", gcm.getMin());
        msg.put("max", gcm.getMax());

        // Structured metadata: detect damage distribution context
        String lowerMsg = multiAmountMsg.toLowerCase();
        if (lowerMsg.contains("damage")) {
            msg.put("context", "damage_distribution");
        } else if (lowerMsg.contains("counter")) {
            msg.put("context", "counter_distribution");
        } else {
            msg.put("context", "multi_amount");
        }
        sendToBrowser(gsonToJson(msg));
    }

    /**
     * Simple card serialization for pile/choice displays.
     */
    private Map<String, Object> serializeCardSimple(CardView card) {
        Map<String, Object> c = new HashMap<>();
        c.put("name", card.getName());
        c.put("imageUrl", GameStateSerializer.buildImageUrl(card.getName()));
        c.put("typeLine", card.getTypeText());
        return c;
    }

    /**
     * Resolve a playable-object key that no view can render (the "main card" key
     * for a multi-faced card — PlayerImpl.getPlayableObjects registers the same
     * ActivatedAbility under both ability.getSourceId() and
     * card.getMainCard().getId(), and the main-card container is not present in
     * any GameView zone) to a viewable key that registered the SAME ability ids,
     * so the choice can carry the real card name instead of "Unknown".
     * <p>
     * Fail Fast: returning null means the object genuinely cannot be labelled;
     * the caller surfaces it to the browser and skips the entry rather than
     * emitting a bogus name.
     *
     * @param objectId         the unresolvable playable-object key
     * @param abilityIdsByObject ability ids registered per playable-object key
     * @param viewableKeys     keys that DID resolve to a CardView
     * @return a viewable key sharing an ability id with {@code objectId}, or null
     */
    private UUID findPlayableAliasKey(UUID objectId, Map<UUID, List<UUID>> abilityIdsByObject,
            Set<UUID> viewableKeys) {
        assert objectId != null : "findPlayableAliasKey called with null objectId";
        assert abilityIdsByObject != null : "findPlayableAliasKey called with null abilityIdsByObject";
        List<UUID> objectAbilityIds = abilityIdsByObject.get(objectId);
        if (objectAbilityIds == null || objectAbilityIds.isEmpty()) {
            return null;
        }
        for (UUID candidate : viewableKeys) {
            if (candidate.equals(objectId)) {
                continue;
            }
            List<UUID> candidateAbilityIds = abilityIdsByObject.get(candidate);
            if (candidateAbilityIds == null || candidateAbilityIds.isEmpty()) {
                continue;
            }
            for (UUID abilityId : objectAbilityIds) {
                if (candidateAbilityIds.contains(abilityId)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * Find a CardView by UUID in the game view (searches hand, battlefield, stack, graveyard, exile).
     */
    private CardView findCardViewById(UUID id, GameView gameView) {
        if (gameView == null) return null;
        // Search my hand
        if (gameView.getMyHand() != null) {
            CardView card = gameView.getMyHand().get(id);
            if (card != null) return card;
        }
        // Search foreign hands: while this session controls another player's
        // TURN the engine offers that player's hand cards as playables
        // (GameSessionPlayer.prepareGameView sets canPlayObjects from the priority
        // player's getPlayableObjects), and a player who granted this user hand
        // permission shows up in watchedHands. Both arrive as SimpleCardViews
        // (id + set/number), exactly the shape the stock client converts with
        // CardsViewUtil.convertSimple — without this lookup the fail-fast
        // below would drop every such choice and control-the-turn would be
        // unusable from the web client.
        CardView foreign = findForeignHandCardViewById(id, gameView);
        if (foreign != null) return foreign;
        // Search battlefield of all players
        for (PlayerView player : gameView.getPlayers()) {
            if (player.getBattlefield() != null) {
                PermanentView perm = player.getBattlefield().get(id);
                if (perm != null) return perm;
            }
            if (player.getGraveyard() != null) {
                CardView card = player.getGraveyard().get(id);
                if (card != null) return card;
            }
            if (player.getExile() != null) {
                CardView card = player.getExile().get(id);
                if (card != null) return card;
            }
            // Command zone: commanders are playable from there (cast for their
            // commander tax). CommanderView extends CardView, so it satisfies the
            // return type. Emblems/planes/dungeons are CommandObjectView but not
            // CardView, so they are skipped HERE — that is a type constraint of
            // this method, NOT a statement that they carry no playable ability
            // (the Momir emblem does: findCommandObjectViewById labels it).
            if (player.getCommandObjectList() != null) {
                for (CommandObjectView cmd : player.getCommandObjectList()) {
                    if (id.equals(cmd.getId()) && cmd instanceof CardView) {
                        return (CardView) cmd;
                    }
                }
            }
        }
        // Search stack
        if (gameView.getStack() != null) {
            CardView card = gameView.getStack().get(id);
            if (card != null) return card;
        }
        return null;
    }

    /**
     * True when the id names a card in a controlled or watched hand.
     *
     * Cheap membership test (no card-DB lookup) for callers that only need to know
     * where the object lives — the labeling pass below uses it to give a play from
     * a controlled player's hand the same cast/land action a play from my own hand
     * gets (both are plays into the game, unlike a battlefield activation).
     */
    private boolean isForeignHandCard(UUID id, GameView gameView) {
        if (id == null) return false;
        return handContainsCard(gameView.getOpponentHands(), id)
                || handContainsCard(gameView.getWatchedHands(), id);
    }

    private boolean handContainsCard(Map<String, mage.view.SimpleCardsView> hands, UUID id) {
        if (hands == null) return false;
        for (mage.view.SimpleCardsView hand : hands.values()) {
            if (hand != null && hand.get(id) != null) return true;
        }
        return false;
    }

    /**
     * Resolve a foreign-hand card (controlled turn / granted permission) by UUID.
     *
     * Both sources are {@code SimpleCardsView}s (id + set code + collector number),
     * so the card is rebuilt through the DB exactly like
     * {@code CardsViewUtil.convertSimple} does in the stock client. Returns null
     * when the id belongs to no foreign hand — the caller keeps its fallback.
     */
    private CardView findForeignHandCardViewById(UUID id, GameView gameView) {
        if (id == null) return null;
        return findForeignHandCardViewById(id, gameView.getOpponentHands(), gameView.getWatchedHands());
    }

    private CardView findForeignHandCardViewById(UUID id,
            Map<String, mage.view.SimpleCardsView> opponentHands,
            Map<String, mage.view.SimpleCardsView> watchedHands) {
        CardView fromOpponent = foreignHandCard(id, opponentHands);
        if (fromOpponent != null) return fromOpponent;
        return foreignHandCard(id, watchedHands);
    }

    private CardView foreignHandCard(UUID id, Map<String, mage.view.SimpleCardsView> hands) {
        if (hands == null) return null;
        for (mage.view.SimpleCardsView hand : hands.values()) {
            if (hand == null) continue;
            mage.view.SimpleCardView simple = hand.get(id);
            if (simple == null) continue;
            CardInfo info = CardRepository.instance.findCard(
                    simple.getExpansionSetCode(), simple.getCardNumber());
            if (info == null) {
                // Fail Fast: an unresolvable set+number means the card DB and the
                // game disagree — a silent null would drop the choice later with a
                // misleading "no viewable card" error.
                throw new IllegalStateException("Unknown card in a controlled/watched hand: set="
                        + simple.getExpansionSetCode() + " number=" + simple.getCardNumber());
            }
            mage.cards.Card mock = info.createMockCard();
            if (mock == null) {
                throw new IllegalStateException("Card DB row cannot create a card for a"
                        + " controlled/watched hand: set=" + simple.getExpansionSetCode()
                        + " number=" + simple.getCardNumber());
            }
            return new CardView(mock, simple);
        }
        return null;
    }

    /**
     * Find a non-card command object (emblem / plane / dungeon) by UUID in any
     * player's command zone.
     * <p>
     * findCardViewById deliberately answers only CardViews: its return type is
     * used by callers that dereference CardView methods, and an EmblemView is
     * not one. Command objects that carry playable activated abilities —
     * the Momir Basic emblem being the motivating case ("{X}, Discard a card:
     * create a token that's a copy of a random creature card with mana value X")
     * — therefore had NO view at all, and the GAME_SELECT labelling pass dropped
     * their choice via the fail-fast. This is the widened lookup for that
     * path: the caller decides what to do with the view (label name + rules;
     * emblems/planes/dungeons are activate-only, so they never take the cast or
     * land branches).
     * <p>
     * Fail Fast: returns null when the id belongs to no command object — the
     * caller then keeps the error path for the genuinely unlabellable key.
     */
    private CommandObjectView findCommandObjectViewById(UUID id, GameView gameView) {
        if (gameView == null || id == null) return null;
        for (PlayerView player : gameView.getPlayers()) {
            if (player.getCommandObjectList() == null) {
                continue;
            }
            for (CommandObjectView cmd : player.getCommandObjectList()) {
                if (id.equals(cmd.getId()) && !(cmd instanceof CardView)) {
                    return cmd;
                }
            }
        }
        return null;
    }

    private final com.google.gson.Gson gson = WebProxySession.GSON;

    // ── Helpers ──

    private void sendToBrowser(String json) {
        trySendToBrowser(json);
    }

    /**
     * Push a JSON message to the browser, reporting whether it actually left the
     * proxy. Returns true only when the message was handed to an open WebSocket —
     * the state channel relies on this to decide whether a state version may be
     * consumed (a dropped message must not burn a version; see
     * sendGameStateToBrowser).
     */
    private boolean trySendToBrowser(String json) {
        BrowserSender sender = wsSender;
        if (sender == null) {
            // No browser attached (session not yet connected). Not an error for
            // fire-and-forget messages, but the caller must know nothing was sent.
            return false;
        }
        return sender.send(json);
    }

    private String gsonToJson(Map<String, Object> map) {
        return gson.toJson(map);
    }

    private String jsonError(String type, String message) {
        return jsonError(type, message, null);
    }

    /**
     * Render a Throwable's stack trace as a bounded string for
     * the error toast's collapsible details section. Capped so a runaway trace
     * cannot bloat the WS frame (the frontend renders it in a scrollable <pre>).
     */
    private static final int MAX_STACK_CHARS = 8000;

    private static String stackToString(Throwable t) {
        if (t == null) return null;
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        String s = sw.toString();
        return s.length() > MAX_STACK_CHARS ? s.substring(0, MAX_STACK_CHARS) + "\n… (truncated)" : s;
    }

    /**
     * Error frame with an optional DETAILS/stack field. The
     * frontend's ErrorToast renders `details` in a collapsible section. A null
     * or blank details adds no key at all, so every existing error frame stays
     * byte-identical to before.
     */
    private String jsonError(String type, String message, String details) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", type);
        msg.put("message", message);
        if (details != null && !details.trim().isEmpty()) {
            msg.put("details", details);
        }
        return gsonToJson(msg);
    }

    private String jsonMessage(String type, String message) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", type);
        msg.put("message", message);
        return gsonToJson(msg);
    }

    // ── Static factory for table list messages ──

    public String serializeTableList(Collection<TableView> tables) {
        return serializeTableList(tables, null);
    }

    /**
     * Serialize the lobby's table list, optionally flagging the tables THIS session
     * owns (`owner: true` per table).
     *
     * <p>The browser learned ownership only from its own {@code table_created} frame, so a
     * page reload lost it and the owner of a table they hold NO seat at saw no start
     * control anywhere. {@code controllerName} is DISPLAY text (it carries the
     * opponent list and scores once a match runs) and must not be parsed, so the flag
     * is resolved server-side through {@code TableController.isOwner(userId)} — the
     * same check {@code startMatch} enforces — and rides the list as a boolean.
     *
     * <p>The flag is only ever consulted for non-tournament WAITING/READY_TO_START
     * tables (see {@code WebProxySession#resolveOwnedTableIds}); {@code ownedTableIds}
     * may be null (the call sites that have no session to ask), in which case no
     * table carries the flag and the browser's grid falls back to its own memory —
     * byte-identical to the pre-change payload for those fields.
     *
     * @param tables        the room read, may be null
     * @param ownedTableIds table ids proven to be owned by this session, or null
     */
    public String serializeTableList(Collection<TableView> tables, Collection<UUID> ownedTableIds) {
        List<Map<String, Object>> tableList = new ArrayList<>();
        if (tables != null) {
            for (TableView table : tables) {
                Map<String, Object> t = new HashMap<>();
                t.put("id", table.getTableId().toString());
                t.put("name", table.getTableName());
                t.put("gameType", table.getGameType());
                // Parse seatsInfo "current/max" (e.g. "2/4") if available; fall back to seats.size()
                String seatsInfo = table.getSeatsInfo();
                int playerCount = 0;
                int maxPlayers = 0;
                if (seatsInfo != null && seatsInfo.contains("/")) {
                    String[] parts = seatsInfo.split("/");
                    try {
                        playerCount = Integer.parseInt(parts[0].trim());
                        maxPlayers = Integer.parseInt(parts[1].trim());
                    } catch (NumberFormatException ignored) {}
                }
                if (maxPlayers == 0) {
                    maxPlayers = table.getSeats() != null ? table.getSeats().size() : 2;
                }
                t.put("playerCount", playerCount);
                t.put("maxPlayers", maxPlayers);
                t.put("state", table.getTableState() != null ? table.getTableState().name() : "UNKNOWN");
                // Include seat player names and game IDs so the frontend can detect
                // tables where the user is already seated (for rejoin after refresh)
                List<String> seatNames = new ArrayList<>();
                if (table.getSeats() != null) {
                    for (SeatView seat : table.getSeats()) {
                        if (seat.getPlayerName() != null && !seat.getPlayerName().isEmpty()) {
                            seatNames.add(seat.getPlayerName());
                        }
                    }
                }
                t.put("seatNames", seatNames);
                // Include game IDs for tables with active games (for rejoin)
                if (table.getGames() != null && !table.getGames().isEmpty()) {
                    List<String> gameIds = new ArrayList<>();
                    for (UUID gameId : table.getGames()) {
                        gameIds.add(gameId.toString());
                    }
                    t.put("gameIds", gameIds);
                }
                // Stock-lobby grid fields (additive): deck type, create time, rated /
                // password / spectators / tournament flags, skill level and owner line.
                // Gson omits null map values, so an absent field means "unknown" to the
                // browser; the stock desktop client ignores these unknown keys.
                t.put("deckType", table.getDeckType());
                t.put("createTimeMs", table.getCreateTime() != null ? Long.valueOf(table.getCreateTime().getTime()) : null);
                t.put("rated", table.isRated());
                t.put("passworded", table.isPassworded());
                t.put("spectatorsAllowed", table.getSpectatorsAllowed());
                t.put("isTournament", table.isTournament());
                t.put("skillLevel", table.getSkillLevel() != null ? table.getSkillLevel().toString() : null);
                t.put("controllerName", table.getControllerName());
                // Candidate 2: the authoritative "this table is YOURS" flag (see the
                // method doc). Only ever put when it is true — an absent key means
                // "unknown/not yours", so a proxy that cannot ask never emits a false
                // claim and the field costs nothing on the wire for other people's
                // tables.
                if (ownedTableIds != null && ownedTableIds.contains(table.getTableId())) {
                    t.put("owner", true);
                }
                tableList.add(t);
            }
        }
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "table_list");
        msg.put("tables", tableList);
        return gsonToJson(msg);
    }

    /**
     * Serialize a TournamentView into a JSON-compatible Map for the browser.
     * Includes tournament name/type/state, players (standings), and rounds
     * with games (including tableId/gameId for watch buttons).
     */
    public static Map<String, Object> serializeTournamentView(mage.view.TournamentView tourView) {
        Map<String, Object> t = new HashMap<>();
        t.put("name", tourView.getTournamentName());
        t.put("type", tourView.getTournamentType());
        t.put("state", tourView.getTournamentState());
        t.put("watchingAllowed", tourView.isWatchingAllowed());

        // Players (standings table)
        List<Map<String, Object>> players = new ArrayList<>();
        if (tourView.getPlayers() != null) {
            for (mage.view.TournamentPlayerView p : tourView.getPlayers()) {
                Map<String, Object> pv = new HashMap<>();
                pv.put("name", p.getName());
                pv.put("state", p.getState());
                pv.put("points", p.getPoints());
                pv.put("results", p.getResults());
                pv.put("quit", p.hasQuit());
                players.add(pv);
            }
        }
        t.put("players", players);

        // Rounds with games
        List<Map<String, Object>> rounds = new ArrayList<>();
        if (tourView.getRounds() != null) {
            for (mage.view.RoundView round : tourView.getRounds()) {
                Map<String, Object> rv = new HashMap<>();
                List<Map<String, Object>> games = new ArrayList<>();
                if (round.getGames() != null) {
                    for (mage.view.TournamentGameView game : round.getGames()) {
                        Map<String, Object> gv = new HashMap<>();
                        gv.put("roundNum", game.getRoundNum());
                        gv.put("gameId", game.getGameId() != null ? game.getGameId().toString() : null);
                        gv.put("tableId", game.getTableId() != null ? game.getTableId().toString() : null);
                        gv.put("state", game.getState());
                        gv.put("result", game.getResult());
                        gv.put("players", game.getPlayers());
                        // "Dueling" state means the game is in progress and can be watched
                        gv.put("canWatch", game.getState() != null && game.getState().startsWith("Dueling"));
                        games.add(gv);
                    }
                }
                rv.put("games", games);
                rounds.add(rv);
            }
        }
        t.put("rounds", rounds);

        return t;
    }
}