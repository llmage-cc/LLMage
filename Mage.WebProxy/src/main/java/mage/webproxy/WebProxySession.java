package mage.webproxy;
import mage.constants.MatchBufferTime;
import mage.constants.MatchTimeLimit;
import mage.constants.MultiplayerAttackOption;
import mage.constants.PlayerAction;
import mage.constants.RangeOfInfluence;
import mage.constants.SkillLevel;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.DeckCardInfo;
import mage.game.match.MatchOptions;
import mage.game.mulligan.MulliganType;
import mage.remote.Connection;
import mage.remote.MageRemoteException;
import mage.remote.SessionImpl;
import mage.players.LLMPlayerConfig;
import mage.players.PlayerType;
import mage.players.net.UserData;
import mage.players.net.UserGroup;
import mage.players.net.UserSkipPrioritySteps;
import mage.constants.TableState;
import mage.view.TableView;
import mage.interfaces.callback.ClientCallbackMethod;
import org.apache.log4j.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages one WebSocket connection from a browser and one XMage client session.
 *
 * Lifecycle:
 * 1. Browser connects to /ws
 * 2. Browser sends {"type": "connect", "server": "...", "port": ..., "username": "...", "password": "..."}
 * 3. This creates a SessionImpl + WebProxyClient, connects to XMage server
 * 4. Browser sends {"type": "watch_game", "tableId": "..."} or {"type": "join_table"...}
 * 5. XMage callbacks flow back through WebProxyClient → WebSocket → browser
 */
public class WebProxySession {

    private static final Logger logger = Logger.getLogger(WebProxySession.class);
    private static final int PING_INTERVAL_SEC = 20;
    static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    // ── Input bounds ──
    /** Max chars for a pasted decklist. */
    private static final int MAX_DECKLIST_LENGTH = 64 * 1024;
    /** Max chars for a chat message. */
    private static final int MAX_CHAT_LENGTH = 500;
    /** Max seats in create_table / multi_amount entries. */
    private static final int MAX_SEATS = 8;

    /**
     * Max chars for a token name in token_versions / token_art_preference. The longest
     * real XMage token name is well under 60 chars; the cap is slack but firm, so a
     * client bug cannot push an unbounded string into the relay map or a Scryfall URL.
     */
    private static final int MAX_TOKEN_NAME_LENGTH = 200;

    /**
     * Shared thread pool for background tasks (table setup, model fetching).
     * Intentionally static: shared across all WebProxySession instances because
     * background tasks are short-lived and session-scoped pools would create too
     * many threads. This executor is NEVER shut down per-session — it should be
     * shut down when the proxy server stops via {@link #shutdownBackgroundExecutor()}.
     * Threads are daemon threads so they won't prevent JVM exit.
     *
     * Bounded: a fixed-cap pool with a bounded queue that REJECTS
     * on saturation. The old cachedThreadPool let one chatty client pin unbounded
     * JVM threads (auto-join loops hold a thread with Thread.sleep) — a DoS.
     * Rejection is surfaced to the client as an error, not swallowed (Fail Fast).
     */
    private static final int BG_POOL_CORE = 4;
    private static final int BG_POOL_MAX = 16;
    private static final int BG_POOL_QUEUE = 32;
    private static final java.util.concurrent.ThreadPoolExecutor backgroundExecutor =
        new java.util.concurrent.ThreadPoolExecutor(
            BG_POOL_CORE, BG_POOL_MAX, 60L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(BG_POOL_QUEUE),
            r -> {
                Thread t = new Thread(r, "WebProxy-Background");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    /** Core: minimum always-alive threads. */
    static int backgroundPoolCoreSize() { return BG_POOL_CORE; }
    /** Max: pool hard cap. */
    static int backgroundPoolMaxSize() { return BG_POOL_MAX; }
    /** Queue capacity before new submissions are rejected. */
    static int backgroundPoolQueueSize() { return BG_POOL_QUEUE; }

    /**
     * Shut down the static background executor. Should be called once when the
     * proxy server stops (e.g. from WebProxyServer.stop()). Idempotent — safe to
     * call multiple times. After shutdown, new background tasks will be rejected.
     */
    static void shutdownBackgroundExecutor() {
        backgroundExecutor.shutdownNow();
        logger.info("Background executor shut down");
    }

    /**
     * Submit a background task on the bounded pool, surfacing saturation as an
     * explicit client error instead of an unhandled RejectedExecutionException
     * (Fail Fast — the top-level catch would otherwise leak the raw exception).
     */
    private void submitBackground(String what, Runnable task) {
        try {
            backgroundExecutor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            logger.error("Background pool saturated (" + what + ") — rejecting");
            sendError("Server busy — too many background tasks in flight (" + what + ")");
        }
    }

    private volatile Session wsSession;
    private volatile WebProxyClient proxyClient;
    private volatile SessionImpl xmageSession;
    private volatile Connection connection;
    private volatile ScheduledExecutorService pingExecutor;
    private volatile boolean closed = false;

    /**
     * The avatar id THIS session last pushed to the server, clamped to the
     * drawable range by {@link #normalizeAvatarId}. Set in {@link #handleConnect}
     * from the connect message and updated by {@link #handleSetAvatar}.
     *
     * Why a field: the mid-session UserData rebuilds
     * ({@link #handleUpdatePhaseStops}, {@link #handleSetAvatar}) construct a FULL
     * {@code UserData} from scratch, so every other preference must be threaded
     * back in — previously the avatar was the hardcoded 0 and nothing had to be
     * remembered. Defaults to the desktop's DEFAULT_AVATAR_ID for a session that
     * has not connected yet (those rebuilds refuse to run unconnected anyway).
     */
    private volatile int sessionAvatarId = AVATAR_DEFAULT_ID;

    /**
     * The per-host connection-cap slot this session reserved at connect time, as
     * {@code host:port} ({@link PerHostConnectionLimiter}); null when no slot
     * was taken (cap unset/0, or the connect never got that far). Set exactly once
     * in {@link #handleConnect} and cleared by {@link #cleanup()} after releasing —
     * the local-copy-then-null shape makes a double teardown a no-op instead of a
     * double release. Only touched from the WS message thread (handleConnect) and
     * the teardown thread (cleanup), and nulled before the release call.
     */
    private volatile String hostSlotKey;
    /**
     * Capabilities of the XMage server this session connected to (probed once,
     * right after connectStart — this fork). Null until connected; a
     * stock server yields llm=false so every LLM path below is gated off.
     */
    private volatile ServerCapabilities serverCapabilities;

    /**
     * Application close code for a deliberate auth rejection
     * RFC 6455 reserves
     * 4000–4999 for private/application use; the browser surfaces the code in
     * the CloseEvent, so the client can refuse to auto-reconnect on it.
     */
    static final int CLOSE_CODE_AUTH_REJECTED = 4401;

    /**
     * Fix 2: a deliberate close (auth rejection, dead-socket send failure) makes
     * Jetty surface a ClosedChannelException to onWebSocketError, which used to
     * log a 15-frame ERROR trace for a close we chose to make. This flag is set
     * immediately before such a close and consumed by onWebSocketError, so the
     * deliberate close logs one INFO line and a GENUINE error still logs at
     * ERROR with its trace. Consume-once on purpose: a sticky flag could
     * swallow a real error that follows on the same session.
     */
    private volatile boolean deliberateClose = false;

    // ── Table-state watch ──

    /**
     * The table whose seat/state changes the browser must be told about while the user is
     * seated but the match has not started (null = no watch).
     */
    private volatile UUID watchedTableId;
    /**
     * Last "seats|state" signature pushed for the watched table. null forces a push on the
     * next tick, so a freshly seated user gets a table_list without asking (a WS message is
     * not timer-throttled — see the watch's doc comment).
     */
    private volatile String lastWatchedTableSignature;

    /**
     * Consecutive table-watch ticks that did not find the watched table. Reset on any
     * successful read; only a run of {@link TableWatchDecision#MISS_TOLERANCE} stops the watch.
     * Counts on the watch thread only.
     */
    private int tableWatchMisses;
    /** Single-thread scheduler for the table watch; daemon, shut down in cleanup(). */
    private volatile ScheduledExecutorService tableWatchExecutor;
    /** Interval between table-state checks. Mirrors the stock client's 1 s UpdateSeatsTask. */
    private static final int TABLE_WATCH_INTERVAL_SEC = 2;

    /**
     * Candidate 1: the table whose
     * create-time auto-start was skipped because a seat was deliberately left open
     * ({@link CreateAutoStartOutcome#SKIPPED_SEATS_OPEN}) and whose match must therefore be
     * started by the proxy once the last seat fills. Null = nothing pending.
     *
     * <p>Without this, a [Computer-LLM, open Human] table reaches 2/2 READY_TO_START with
     * nobody able to start it: the owner holds no seat (no waiting panel, no grid action)
     * and the joiner is not the owner, so no browser ever sends start_match. The create-time
     * outcome is exactly the signal to retry on — SKIPPED_SEATS_OPEN means "startMatch was
     * not attempted because the table was known not to be full", so when the watch later
     * observes the table full and READY_TO_START, the proxy retries in the owner's place.
     * Set on the create auto-join task; consumed (cleared) by
     * {@link #tryPendingAutoStart(SessionImpl, TableView, TableState)} — it fires at most
     * once, and is dropped on manual start, teardown, or any state where the start would be
     * wrong (genuine failure included — a failing start is not retried forever, see
     * tryPendingAutoStart; WAITING keeps it armed — the open seat is still open).
     */
    private volatile UUID pendingAutoStartTableId;

    // ── Authorization state ──

    /**
     * True once this WS session successfully passed proxy-level authentication
     * (shared-secret token, if configured). When -Dwebproxy.authToken is unset
     * (the localhost default), no token is required and this flips true on any
     * connect — preserving the local dev workflow.
     */
    private volatile boolean authed = false;

    /**
     * True while the XMage session is attached to a game as a spectator/watcher
     * rather than a seated player. Set false on join paths, true
     * on watch paths. Game-action handlers reject messages while this is set so
     * a spectator cannot fire concede/pass_priority/select_target/etc. against a
     * game they aren't playing.
     */
    private volatile boolean spectator = false;

    /** Simple fixed-window rate limiter: creation ops per session. */
    private final java.util.concurrent.atomic.AtomicLong lastCreateOpAt = new java.util.concurrent.atomic.AtomicLong(0);
    /**
     * Per-type fetch-bucket timestamps (message type → last accepted time).
     *
     * Separate bucket PER VERB, not one shared window: opening the New Game or
     * Tournament dialog primes `fetch_models` AND `fetch_personalities` in the
     * SAME tick (lobbySlice showGameSetup / showTournamentSetupDialog), and a
     * shared window answered the second of the pair with a user-facing
     * "Rate limit" error toast on a perfectly normal path (the test change,
     * 2026-09-27). Per-type windows keep BOTH messages working on dialog open
     * while each verb keeps its own storm protection — a hostile/replayed client
     * spamming either verb still gets the loud rate-limit error.
     *
     * Both verbs stay in the limited set: `fetch_models` dials the LLM endpoint
     * (handleFetchModels → fetchModelsFromEndpoint on the background pool) and is
     * the original reason this bucket exists; `fetch_personalities` is a local
     * registry read (Personality.allNames()), but each request still serializes
     * and sends a full list, so unbounded repeats stay capped too.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> lastFetchAtByType =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Every live WebProxySession, so a token-art preference chosen by one player can
     * be relayed to the others at the same table: a player who has not set their own
     * choice receives the one chosen at the table, which requires the choice to leave the
     * browser). Static because sessions are independent objects; entries are removed
     * in cleanup(). A ConcurrentHashMap-as-set, keyed by identity.
     */
    private static final java.util.Set<WebProxySession> LIVE_SESSIONS =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<WebProxySession, Boolean>());

    /** Minimum milliseconds between create_table / create_tournament per session. */
    private static final long CREATE_OP_MIN_INTERVAL_MS = 5000;
    /** Minimum milliseconds between two messages of the SAME fetch verb per session. */
    private static final long FETCH_MIN_INTERVAL_MS = 1000;

    /**
     * What THIS WebProxySession has registered in {@link AnonNameGuard} —
     * recorded at the first collision check so the release in cleanup() targets
     * the right entry even if a later connect attempt targets a different server.
     * Null until the first handleConnect collision check runs.
     */
    private volatile AnonNameGuard.ActiveConnect activeConnect = null;

    /**
     * Account-name bounds the proxy pre-flights for the `register` verb.
     * Values mirror the defaults this repo ships in config.xml
     * (minUserNameLength=3, maxUserNameLength=14, invalidUserNamePattern)
     * and the server's own Session.validateUserName rules. The SERVER remains the
     * authority — this only spares the user a full dial + 3 s callback hold for the
     * common rejections.
     */
    static final int DEFAULT_MIN_USERNAME_LENGTH = 3;
    static final int DEFAULT_MAX_USERNAME_LENGTH = 14;
    /** Allowlist mirror of config.xml's `invalidUserNamePattern="[^a-z0-9_]"`. */
    static final String DEFAULT_USERNAME_ALLOWED = "[a-zA-Z0-9_]+";

    /**
     * How long the register flow waits for the server's outcome text after the RPC
     * returned. The text rides a SHOW_USERMESSAGE callback that
     * {@code SessionImpl.CallbackHandler} holds for 3 s (register sessions never
     * receive a server state, so the pre-state gate always applies) — 6 s is two
     * gates' worth of slack. The boolean result is authoritative regardless: a
     * timeout only means the reason text is missing, never that a success is lost.
     */
    private static final long REGISTER_MESSAGE_WAIT_MS = 6000;

    /** True while a `register` dial is in flight for this session (one at a time). */
    private volatile boolean registerInFlight = false;

    void onWebSocketConnect(Session session) {
        this.wsSession = session;
        // Register before any message arrives: the token-art relay walks this set to
        // find every session seated in the same game (see handleTokenArtPreference).
        LIVE_SESSIONS.add(this);
        logger.info("WebSocket connected from " + session.getRemoteSocketAddress());
    }

    /**
     * Proxy-level shared-secret auth.
     * -Dwebproxy.authToken unset (default): auth is a no-op — localhost dev
     * keeps working exactly as before. When set: every message type except
     * "connect" requires a prior authenticated session, and "connect" itself
     * must carry the token in its authToken field. Returns true when the
     * message may proceed.
     */
    private boolean requireAuthFor(String type, com.google.gson.JsonObject json) {
        String expected = System.getProperty("webproxy.authToken", "").trim();
        if (expected.isEmpty()) {
            // env delivery (preferred): a -D flag puts the token in the JVM
            // command line, which any local user can read via
            // /proc/<pid>/cmdline. Compose/launchers set WEBPROXY_AUTH_TOKEN.
            String envToken = System.getenv("WEBPROXY_AUTH_TOKEN");
            if (envToken != null) expected = envToken.trim();
        }
        if (expected.isEmpty()) {
            authed = true; // no token configured — open proxy (localhost default)
            return true;
        }
        if ("connect".equals(type) || "register".equals(type)) {
            String provided = json.has("authToken") && !json.get("authToken").isJsonNull()
                    ? json.get("authToken").getAsString().trim() : "";
            if (provided.equals(expected)) {
                authed = true;
                return true;
            }
            // Both entry points carry the token: a fresh user must be able to
            // REGISTER before any successful connect exists, so a
            // token-bearing `register` frame authenticates exactly like a
            // `connect` frame. The error frame is named after the caller's flow
            // so the register dialog shows it where the user is looking.
            logger.warn("Rejected " + type + " with bad/missing auth token");
            sendTypedMessage("register".equals(type) ? "register_error" : "connect_error",
                    "message", "Bad or missing auth token");
            closeSocketAfterConnectRejection("Auth token required");
            return false;
        }
        // Typed rejection for the fetch verbs: the browser's
        // models spinner (lobbySlice `modelsLoading`) resolves ONLY on
        // `models_list` / `models_error`, so the generic `error` frame left it
        // spinning forever when a fetch raced the connect frame (auto-reconnect
        // opens the socket, the user's refresh click fires before the connect
        // message completes). Answer with the SAME typed frame the handler's own
        // failure paths use (handleFetchModels) — message text unchanged. Every
        // other type keeps the generic error: the auth SEMANTICS are untouched,
        // only the error TYPE for these two fetch verbs changes.
        logger.warn("Rejected unauthenticated message type: " + type);
        if ("fetch_models".equals(type)) {
            sendTypedMessage("models_error", "message", AUTH_REQUIRED_MESSAGE);
        } else if ("fetch_personalities".equals(type)) {
            // Symmetry: the frontend has no personalities_error consumer TODAY (the
            // personality list has no spinner), but the typed frame is the honest
            // one — a generic `error` here is indistinguishable from any other
            // proxy error. Additive: an older frontend logs it as an unknown type
            // and ignores it, exactly as it ignored the generic error.
            sendTypedMessage("personalities_error", "message", AUTH_REQUIRED_MESSAGE);
        } else {
            sendError(AUTH_REQUIRED_MESSAGE);
        }
        return false;
    }

    /** Message text for the proxy-level auth rejection (one definition, all sites). */
    static final String AUTH_REQUIRED_MESSAGE = "Not authenticated — connect with an auth token first";

    /**
     * Close the WS after a connect rejection the USER can fix by resubmitting
     * the form (bad auth token, wrong password/over-long username, bad XMage
     * target). Best effort: the container's close path triggers cleanup(),
     * which is safe to run for a session that never reached XMage — and for a
     * half-built one from a failed login (cleanup() connectStops it and
     * releases the client's GameHistory).
     *
     * Why closing is mandatory: the browser's WS manager treats an OPEN socket
     * as "already connected" and silently drops any retried connect() — a
     * rejected tab would sit on a dead-but-open socket forever, ignoring the
     * user's corrected credentials (measured live 2026-09-22: a 15-char
     * username rejection wedged the form until reload). Closing lets the
     * client reconnect and resend. Fix 2: marks the close deliberate so
     * onWebSocketError logs one INFO line instead of an ERROR + 15-frame
     * ClosedChannelException trace for a close we chose to make. Fix 3: uses
     * the dedicated application close code 4401 + reason so the browser can
     * refuse to auto-reconnect on it — an auto-reconnect would resend the
     * SAME rejected credentials and loop forever; the user must edit the
     * form (connect_error is delivered before the close frame).
     */
    private void closeSocketAfterConnectRejection(String reason) {
        try {
            if (wsSession != null && wsSession.isOpen()) {
                deliberateClose = true;
                wsSession.close(CLOSE_CODE_AUTH_REJECTED, reason,
                        org.eclipse.jetty.websocket.api.Callback.NOOP);
            }
        } catch (Throwable t) {
            logger.warn("Failed to close socket after connect rejection: " + t.getMessage());
        }
    }

    /**
     * Fixed-window rate limiter for expensive per-session ops.
     * Returns false when the last accepted op was less than
     * {@link #CREATE_OP_MIN_INTERVAL_MS} ago.
     */
    private boolean tryAcquireCreateOpSlot() {
        long now = System.currentTimeMillis();
        long last = lastCreateOpAt.get();
        if (now - last < CREATE_OP_MIN_INTERVAL_MS) {
            return false;
        }
        return lastCreateOpAt.compareAndSet(last, now) || lastCreateOpAt.get() != last;
    }

    /**
     * Fetch-bucket rate limiter — cheap / read-only primers, one window PER TYPE.
     *
     * Per-type (not shared) because the frontend primes BOTH verbs in one tick on
     * dialog open; a shared window rejected the second of the pair (found live,
     * 2026-09-27). Each verb keeps its own minimum interval, so a burst
     * of EITHER verb is still capped — the protection stays, it no longer fires
     * on the normal pair.
     */
    private boolean tryAcquireFetchSlot(String type) {
        java.util.concurrent.atomic.AtomicLong bucket =
                lastFetchAtByType.computeIfAbsent(type, k -> new java.util.concurrent.atomic.AtomicLong(0));
        long now = System.currentTimeMillis();
        long last = bucket.get();
        if (now - last < FETCH_MIN_INTERVAL_MS) {
            return false;
        }
        return bucket.compareAndSet(last, now) || bucket.get() != last;
    }

    /** Max inbound WS message size — deck lists can be large; 1 MiB is generous. */
    private static final int MAX_MESSAGE_LENGTH = 1024 * 1024;

    /** Message types allowed only for seated players, not spectators. */
    private static final java.util.Set<String> PLAYER_ONLY_ACTIONS = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList(
            "pass_priority", "skip_priority", "cancel_skip", "undo",
            "select_choice", "play_card", "select_special", "boolean_choice", "amount_choice",
            "multi_amount_choice", "choose_pile", "select_target", "tap_mana",
            "special_mana", "use_mana", "concede", "concede_match",
            "user_request_response", "hold_priority", "rollback_turns",
            "trigger_order", "submit_deck", "toggle_mana_auto_pay",
            "toggle_use_first_mana", "update_phase_stops",
            // view_limited_deck: PLAYER-ONLY. It sends PlayerAction.VIEW_LIMITED_DECK,
            // whose GameController branch resolves the sender's playerId via
            // userPlayerMap — a spectator has none, so requestPlayer is null and the
            // request dies silently server-side (viewDeckOrSideboard returns at
            // `requestPlayer == null`). Rejecting here surfaces the reason to the
            // browser instead of a silent no-op. Same reason the sibling
            // view_sideboard belongs here; that one predates this set and is left
            // untouched.
            "view_limited_deck",
            // auto_answer sets/clears THIS player's auto-answer memory (a prompt answer
            // is player-only) — a spectator has no prompts of its own to remember, so it
            // must not be able to write into a seated player's memory either.
            "auto_answer",
            // revoke_hand_permission drops the grants THIS player issued for its
            // OWN hand: GameController.sendPlayerAction resolves the sender via
            // userPlayerMap, so a spectator's null playerId makes it a silent no-op.
            // Rejecting here surfaces the reason (same reasoning as view_limited_deck).
            "revoke_hand_permission",
            // allow_hand_requests flips THIS player's own UserData flag; it takes
            // the GameController DEFAULT branch (`game.sendPlayerAction(action,
            // getPlayerId(userId), null)`), which is also a silent no-op for a spectator
            // (state.getPlayer(null) returns null). Player-only.
            "allow_hand_requests",
            // leave_table drops THIS user's own seat at a waiting table. The
            // engine resolves the leaver by userPlayerMap on the table's own room and
            // a spectator occupies no seat, so for a spectator it is a silent no-op —
            // reject it here to surface the reason instead (same reasoning as
            // submit_deck's seat check). The handler additionally verifies the user is
            // actually seated before calling leave.
            "leave_table")));
    // NOTE: request_hand_permission is deliberately NOT player-only. The desktop
    // client offers it to WATCHERS too (PlayAreaPanel.addPopupMenuWatcher:479-481 —
    // "Request permission to see hand cards (name)"), and the engine supports that
    // path end to end: GameController.requestPermissionToSeeHandCards tolerates a
    // null requestingPlayerId (`requestingPlayerId == null || ...`:759), the one-
    // time restriction keys on it, GameSessionPlayer.requestPermissionToSeeHandCards
    // fires the dialog at the GRANTER with the watcher's userId as relatedUser, and
    // GameSessionWatcher.processWatchedHands grants the hand per USER id (125) — so
    // a spectator session receives the granted hand exactly like a seated one.

    /** Message types gated by the create rate limiter — expensive table/tournament creation only. */
    private static final java.util.Set<String> CREATE_RATE_LIMITED = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList(
            "create_table", "create_tournament")));

    /** Message types gated by the fetch rate limiter — model/personality primers.
     * Kept separate from CREATE so opening a dialog doesn't block the user's next
     * Start Game click. */
    private static final java.util.Set<String> FETCH_RATE_LIMITED = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList(
            "fetch_models", "fetch_personalities")));

    void onWebSocketText(String message) {
        try {
            // Hard cap inbound size before any parsing happens.
            if (message.length() > MAX_MESSAGE_LENGTH) {
                logger.warn("Rejected oversized WS message (" + message.length() + " chars)");
                sendError("Message too large (max " + MAX_MESSAGE_LENGTH + " chars)");
                return;
            }
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(message).getAsJsonObject();
            // A has() guard before reading "type" — malformed frames used to NPE.
            if (!json.has("type") || json.get("type").isJsonNull()) {
                sendError("Missing message type");
                return;
            }
            String type = json.get("type").getAsString();
            logger.info("WebSocket message: " + type);

            // Proxy-level shared-secret token. When -Dwebproxy.authToken is set,
            // only token-bearing "connect" messages may proceed; every other type
            // requires an already-authed session. Unset (localhost default) = open.
            if (!authed && !requireAuthFor(type, json)) {
                return; // requireAuthFor sent the error
            }

            // Per-session rate limit on creation ops.
            if (CREATE_RATE_LIMITED.contains(type) && !tryAcquireCreateOpSlot()) {
                sendError("Rate limit — wait before creating tables again");
                return;
            }
            // Fetch bucket — model/personality primers (per-TYPE window; the
            // dialog-open pair sends both in one tick and must not reject).
            if (FETCH_RATE_LIMITED.contains(type) && !tryAcquireFetchSlot(type)) {
                sendError("Rate limit — wait before fetching again (" + type + ")");
                return;
            }

            // Spectators cannot send game actions.
            if (spectator && PLAYER_ONLY_ACTIONS.contains(type)) {
                logger.warn("Rejected spectator game-action message: " + type);
                sendError("Spectators cannot send game actions (" + type + ")");
                return;
            }

            switch (type) {
                case "connect":
                    handleConnect(json);
                    break;
                case "register":
                    handleRegister(json);
                    break;
                case "get_tables":
                    handleGetTables();
                    break;
                case "watch_game":
                    handleWatchGame(json);
                    break;
                case "watch_tournament_table":
                    handleWatchTournamentTable(json);
                    break;
                case "refresh_tournament":
                    handleRefreshTournament(json);
                    break;
                case "join_table":
                    handleJoinTable(json);
                    break;
                case "create_table":
                    handleCreateTable(json);
                    break;
                case "start_match":
                    handleStartMatch(json);
                    break;
                case "leave_table":
                    handleLeaveTable(json);
                    break;
                case "submit_deck":
                    handleSubmitDeck(json);
                    break;
                case "get_tournament_types":
                    handleGetTournamentTypes();
                    break;
                case "create_tournament":
                    handleCreateTournament(json);
                    break;
                case "join_tournament":
                    handleJoinTournament(json);
                    break;
                case "start_tournament":
                    handleStartTournament(json);
                    break;
                case "send_chat":
                    handleSendChat(json);
                    break;
                case "pass_priority":
                    handlePassPriority();
                    break;
                case "skip_priority":
                    handleSkipPriority(json);
                    break;
                case "cancel_skip":
                    handleCancelSkip();
                    break;
                case "undo":
                    handleUndo();
                    break;
                case "select_choice":
                    handleSelectChoice(json);
                    break;
                case "play_card":
                    handlePlayCard(json);
                    break;
                case "select_special":
                    handleSelectSpecial(json);
                    break;
                case "boolean_choice":
                    handleBooleanChoice(json);
                    break;
                case "amount_choice":
                    handleAmountChoice(json);
                    break;
                case "multi_amount_choice":
                    handleMultiAmountChoice(json);
                    break;
                case "choose_pile":
                    handleChoosePile(json);
                    break;
                case "select_target":
                    handleSelectTarget(json);
                    break;
                case "tap_mana":
                    handleTapMana(json);
                    break;
                case "special_mana":
                    handleSpecialMana();
                    break;
                case "use_mana":
                    handleUseMana(json);
                    break;
                case "concede":
                    handleConcede();
                    break;
                case "concede_match":
                    handleConcedeMatch();
                    break;
                case "user_request_response":
                    handleUserRequestResponse(json);
                    break;
                case "hold_priority":
                    handleHoldPriority(json);
                    break;
                case "rollback_turns":
                    handleRollbackTurns();
                    break;
                case "stop_watching":
                    handleStopWatching();
                    break;
                case "trigger_order":
                    handleTriggerOrder(json);
                    break;
                case "auto_answer":
                    handleAutoAnswer(json);
                    break;
                case "view_sideboard":
                    handleViewSideboard();
                    break;
                case "view_limited_deck":
                    handleViewLimitedDeck(json);
                    break;
                case "request_hand_permission":
                    handleRequestHandPermission(json);
                    break;
                case "revoke_hand_permission":
                    handleRevokeHandPermission();
                    break;
                case "allow_hand_requests":
                    handleAllowHandRequests(json);
                    break;
                case "toggle_mana_auto_pay":
                    handleToggleManaAutoPay(json);
                    break;
                case "toggle_use_first_mana":
                    handleToggleUseFirstMana(json);
                    break;
                case "update_phase_stops":
                    handleUpdatePhaseStops(json);
                    break;
                case "set_avatar":
                    handleSetAvatar(json);
                    break;
                case "fetch_models":
                    requireServerLlmGate("fetch_models");
                    handleFetchModels(json);
                    break;
                case "llm_decision":
                    requireServerLlmGate("llm_decision");
                    handleLlmDecision(json);
                    break;
                case "llm_error":
                    requireServerLlmGate("llm_error");
                    handleLlmError(json);
                    break;
                case "fetch_personalities":
                    requireServerLlmGate("fetch_personalities");
                    handleFetchPersonalities();
                    break;
                case "ping":
                    // Keepalive ping — no response needed, just don't warn
                    logger.debug("Keepalive ping received");
                    break;
                case "request_resync":
                    handleRequestResync();
                    break;
                case "rejoin_game":
                    handleRejoinGame(json);
                    break;
                case "card_search":
                    handleCardSearch(json);
                    break;
                case "card_lookup":
                    handleCardLookup(json);
                    break;
                case "card_versions":
                    handleCardVersions(json);
                    break;
                case "token_versions":
                    handleTokenVersions(json);
                    break;
                case "token_art_preference":
                    handleTokenArtPreference(json);
                    break;
                case "legality_check":
                    handleLegalityCheck(json);
                    break;
                case "get_p1_decks":
                    // Offering surface for the COMPUTER_P1 deck restriction: the
                    // 55 training decklists the seat picker must render.
                    if (requireServerP1Gate("get_p1_decks")) {
                        sendToBrowser(P1DeckOffering.decksMessage().toString());
                    }
                    break;
                case "get_finished_matches":
                    handleGetFinishedMatches();
                    break;
                case "get_room_users":
                    handleGetRoomUsers();
                    break;
                case "get_replay_files":
                    handleGetReplayFiles();
                    break;
                case "replay_load":
                    handleReplayLoad(json);
                    break;
                case "replay_start":
                    handleReplayStart();
                    break;
                case "replay_stop":
                    handleReplayStop();
                    break;
                case "replay_next":
                    handleReplayNext();
                    break;
                case "replay_previous":
                    handleReplayPrevious();
                    break;
                case "replay_skip":
                    handleReplaySkip(json);
                    break;
                case "replay_seek":
                    handleReplaySeek(json);
                    break;
                default:
                    logger.warn("Unknown WebSocket message type: " + type);
                    sendError("Unknown message type: " + type);
            }
        } catch (Throwable t) {
            // Log the full detail server-side; give the browser a
            // generic error + short correlation id instead of the raw exception
            // message (which leaks internals: class names, file paths, stack info).
            String correlationId = java.util.UUID.randomUUID().toString().substring(0, 8);
            logger.error("Error processing WebSocket message [" + correlationId + "]: "
                    + sanitizeForLog(message), t);
            sendError("Internal error (ref " + correlationId + ") — see proxy logs");
        }
    }

    void onWebSocketClose(int statusCode, String reason) {
        logger.info("WebSocket closed: " + statusCode + " " + reason);
        cleanup();
    }

    void onWebSocketError(Throwable cause) {
        // Fix 2: a deliberate close (auth rejection / dead-socket send failure)
        // makes Jetty surface a ClosedChannelException here — a close we chose
        // to make, not a failure to report. Consume the flag and log one INFO
        // line; any other error still logs at ERROR with its full trace.
        if (deliberateClose) {
            deliberateClose = false;
            String detail = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            logger.info("WebSocket error after deliberate close (expected): " + detail);
            cleanup();
            return;
        }
        logger.error("WebSocket error", cause);
        cleanup();
    }

    // ── Connection ──

    /**
     * Check whether the given XMage server host is in the allowlist.
     * The allowlist is configurable via the "webproxy.allowedXmageHosts" system
     * property (comma-separated); defaults to localhost,127.0.0.1::1 so the
     * proxy can only reach an XMage server on the same machine. This prevents
     * SSRF: a local process cannot use the proxy to connect to arbitrary
     * host:port targets.
     */
    static boolean isAllowedXmageHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String allowed = System.getProperty("webproxy.allowedXmageHosts", "localhost,127.0.0.1,::1");
        for (String h : allowed.split(",")) {
            if (h.trim().equalsIgnoreCase(host)) return true;
        }
        return false;
    }

    /**
     * Browser verb {@code register} — create an XMage account on the target server.
     *
     * <p>Desktop reference: {@code RegisterUserDialog.btnRegisterActionPerformed}
     * (Mage.Client .../dialog/RegisterUserDialog.java) builds a {@code Connection}
     * with host/port/username/password/EMAIL and calls
     * {@code SessionHandler.register(connection)} →
     * {@code SessionImpl.sendAuthRegister(connection)} →
     * server RPC {@code MageServer.authRegister(sessionId, userName, password, email)}
     * → {@code MageServerImpl.authRegister} → {@code SessionManagerImpl.registerUser}
     * → {@code Session.registerUser(userName, password, email)}.
     *
     * <p>LOGIN vs REGISTER: the engine distinguishes them by RPC, not by any flag on
     * the connect handshake. {@code connectStart} calls {@code connectUser} (which
     * under {@code authenticationActivated} only accepts an ALREADY-REGISTERED
     * account), while {@code sendAuthRegister} calls {@code authRegister} on a
     * throwaway remoting session. The password the desktop user types is NOT what
     * the account ends up with either: the server generates a 10-char password,
     * stores THAT, and mails it to the given address (or, in test mode, returns it
     * in the info message). So this verb mirrors the desktop exactly: username +
     * email matter, the password field is only a formality/confirmation.
     *
     * <p>WIRE CONTRACT (keep the browser side in sync — src/types/messages.ts
     * RegisterRequest, src/utils/connectPayload.ts buildRegisterRequest,
     * src/store/slices/connectionSlice.ts registerAccount):
     *
     *   {"type":"register","username":"<name>","password":"<pw>","email":"<addr>",
     *    "server":"<host>"|absent,"port":<n>|absent,"authToken":"<t>"|absent}
     *
     *   server/port follow {@code connect}'s rules verbatim: OPTIONAL when the proxy
     *   pins its target ({@code -Dwebproxy.pinnedXmageTarget}), REQUIRED otherwise —
     *   the same resolver ({@link WebProxyServer#resolveConnectTarget}) decides, and a
     *   pinned target still passes the SSRF allowlist.
     *
     *   Answer, exactly one frame per request:
     *     {"type":"register_success","message":"<server text>"}
     *     {"type":"register_error","message":"<reason>"}
     *
     * <p>Fail Fast (AGENTS.md): a missing username, missing/blank email, malformed
     * server/port, non-allowlisted host, a second register while one is in flight, or
     * an unusable XMage address is refused with {@code register_error} naming the
     * rule. The email is REQUIRED here even though the wire allows it empty: the
     * server's {@code validateEmail} rejects a blank address as "Email address cannot
     * be blank", so an empty field would waste a full dial (and, in test mode, would
     * still have created the account first), and the desktop dialog's own hint names
     * the email as the delivery path for the initial password.
     *
     * <p>Runs on the shared background pool: the dial blocks for seconds (server
     * version check, the 3 s callback hold in {@code SessionImpl.CallbackHandler},
     * plus the anti-bruteforce sleep on failures), so it must not run on the WS
     * message thread. Exactly one register per session can be in flight
     * ({@link #registerInFlight}).
     */
    private void handleRegister(com.google.gson.JsonObject json) {
        // One registration per browser session, concurrent ones refused: the
        // server-side path sleeps 3 s on failures and holds callbacks for 3 s —
        // several in flight would pile dials onto the XMage server and race their
        // answers into the same browser frame type.
        if (registerInFlight) {
            logger.warn("Rejected duplicate register while one is already in flight");
            sendTypedMessage("register_error", "message",
                    "A registration is already in progress — wait for its result");
            return;
        }

        RegisterRequest request = parseRegisterRequest(
                json, System.getProperty("webproxy.pinnedXmageTarget", ""));
        if (request.error != null) {
            logger.warn("register rejected: " + request.error);
            sendRegisterError(request.error);
            return;
        }

        registerInFlight = true;
        logger.info("Registration request for '" + sanitizeForLog(request.username) + "' via "
                + request.host + ":" + request.port);
        submitBackground("register", () -> performRegister(request));
    }

    /**
     * Everything a `register` frame carries, after parsing + validation. Exactly one
     * of {@code error} / the field set is populated.
     *
     * <p>Extracted from {@link #handleRegister} so the parse, the mapping onto
     * {@link Connection}'s fields and every fail-fast rule are unit-testable without
     * a dial (WebProxyRegisterTest) — the dial itself needs a live server and is
     * covered by the harness/live run.
     */
    static final class RegisterRequest {
        /** Non-null when the frame was refused; the field set is then unpopulated. */
        final String error;
        final String host;
        final int port;
        final String username;
        final String password;
        final String email;

        private RegisterRequest(String error, String host, int port,
                                String username, String password, String email) {
            this.error = error;
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
            this.email = email;
        }

        static RegisterRequest rejected(String error) {
            return new RegisterRequest(error, null, 0, null, null, null);
        }

        static RegisterRequest accepted(String host, int port,
                                        String username, String password, String email) {
            return new RegisterRequest(null, host, port, username, password, email);
        }

        /** The XMage connection the dial uses — mirrors RegisterUserDialog's Connection build. */
        Connection toConnection() {
            Connection connection = new Connection();
            connection.setHost(host);
            connection.setPort(port);
            connection.setUsername(username);
            connection.setPassword(password);
            connection.setEmail(email);
            connection.setProxyType(Connection.ProxyType.NONE);
            return connection;
        }
    }

    /**
     * Parse + validate a `register` frame.
     *
     * <p>Target resolution is {@link WebProxyServer#resolveConnectTarget} — the SAME
     * resolver `connect` uses, so the pinned-target / required-fields / invalid-port
     * rules cannot drift between the two entry points. The host then passes the same
     * SSRF allowlist. Account-name rules mirror the server's own
     * ({@link #validateRegisterUsername}); the email is required because the server
     * delivers the initial password through it.
     */
    static RegisterRequest parseRegisterRequest(com.google.gson.JsonObject json, String pinnedProperty) {
        WebProxyServer.ConnectTarget target = WebProxyServer.resolveConnectTarget(json, pinnedProperty);
        if (target.error != null) {
            return RegisterRequest.rejected(target.error);
        }
        if (!isAllowedXmageHost(target.host)) {
            return RegisterRequest.rejected("XMage server host '" + target.host + "' is not in the allowlist. "
                    + "Configure -Dwebproxy.allowedXmageHosts=host1,host2 to add hosts.");
        }

        if (!json.has("username") || json.get("username").isJsonNull()) {
            return RegisterRequest.rejected("Missing username");
        }
        String username = json.get("username").getAsString().trim();
        if (username.isEmpty()) {
            return RegisterRequest.rejected("Username is required");
        }
        String usernameProblem = validateRegisterUsername(username);
        if (usernameProblem != null) {
            return RegisterRequest.rejected(usernameProblem);
        }

        String password = json.has("password") && !json.get("password").isJsonNull()
                ? json.get("password").getAsString() : "";
        if (!json.has("email") || json.get("email").isJsonNull()) {
            return RegisterRequest.rejected("Missing email");
        }
        String email = json.get("email").getAsString().trim();
        if (email.isEmpty()) {
            return RegisterRequest.rejected("Email is required — the server sends the initial password to it");
        }

        return RegisterRequest.accepted(target.host, target.port, username, password, email);
    }

    /** The blocking half of {@link #handleRegister}, on the shared background pool. */
    private void performRegister(RegisterRequest request) {
        // Dedicated client per dial: SessionImpl.connectStop at the end of the
        // register task calls disconnected() on it — on the shared client that
        // would push a spurious "connection lost" frame to a browser that never
        // logged in (see RegisterAuthClient).
        RegisterAuthClient authClient = new RegisterAuthClient();
        SessionImpl registerSession = new SessionImpl(authClient);
        try {
            boolean registered = registerSession.sendAuthRegister(request.toConnection());
            // The server's human-readable outcome rides an async SHOW_USERMESSAGE
            // callback that the client's pre-server-state gate holds ~3 s
            // (SessionImpl.CallbackHandler — a register session never reaches a
            // server state). WAITING FOR IT IS ONLY MEANINGFUL ON FAILURE:
            //  - failure: the RPC returns false immediately and
            //    doRemoteWorkAndHandleErrors then sleeps 3 s waiting for exactly
            //    this message ("mustWaitServerMessageOnFail"), so the connection
            //    is still up when the callback lands — the text arrives (the
            //    server's own reason: name taken, invalid email, mail transport
            //    missing) and is worth waiting for;
            //  - success: connectStop runs as soon as the RPC returns true, so the
            //    held callback is torn down with the connection before the gate
            //    releases it. Measured live (2026-09-27): RPC DONE at +99 ms,
            //    session disconnected at +120 ms, no callback ever delivered — a
            //    wait here can only burn its full timeout and yield "". The
            //    boolean is authoritative for success; the desktop's own dialog
            //    shows its fixed "Registration succeeded" text for the same reason.
            String serverText = registered
                    ? authClient.lastMessage()
                    : authClient.awaitMessage(REGISTER_MESSAGE_WAIT_MS);
            if (registered) {
                logger.info("Registration succeeded for '" + sanitizeForLog(request.username)
                        + "'" + (serverText.isEmpty() ? "" : ": " + sanitizeForLog(serverText)));
                if (serverText.isEmpty()) {
                    // Still a success — the boolean is authoritative. The browser
                    // says "check the email"; no invented detail is attached.
                    Map<String, Object> msg = new HashMap<>();
                    msg.put("type", "register_success");
                    msg.put("message", "Registration succeeded");
                    sendToBrowser(gsonToJson(msg));
                } else {
                    sendTypedMessage("register_success", "message", rawOrEmpty(serverText));
                }
            } else {
                String reason = !serverText.isEmpty() ? serverText
                        : "Registration failed — check the server log for details";
                logger.error("Registration failed for '" + sanitizeForLog(request.username) + "': "
                        + sanitizeForLog(reason));
                sendRegisterError(reason);
            }
        } catch (Throwable t) {
            // Fail Fast: the dial itself broke (server offline, version mismatch,
            // SSRF refused by remoting). Report the class + message, never a bare
            // "failed" that hides which side to fix.
            String detail = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            logger.error("Registration dial failed for '" + sanitizeForLog(request.username) + "'", t);
            sendRegisterError("Could not reach the XMage server: " + detail);
        } finally {
            // sendAuthRegister already stops the session on every path; this is
            // the belt-and-braces for the throw path (connectStop is idempotent).
            try {
                registerSession.connectStop(false, false);
            } catch (Throwable t) {
                logger.warn("Register session teardown failed: " + t.getMessage());
            }
            registerInFlight = false;
        }
    }

    /**
     * Pre-flight of the SERVER's account-name rules
     * ({@code Session.validateUserName}, {@code validateUserNameLength}; values from
     * config.xml, whose defaults this proxy cannot read remotely — so the checks use
     * the same configured defaults the repo ships and the message names the limit).
     *
     * <p>Deliberately duplicated rather than "approximately" enforced: the server's
     * own check is the authority, and this exists only so the common rejections do
     * not each cost a dial + 3 s wait. Anything this misses the server still refuses,
     * with its own text surfaced verbatim.
     */
    private static String validateRegisterUsername(String username) {
        if (username.length() > DEFAULT_MAX_USERNAME_LENGTH) {
            return "User name may not be longer than " + DEFAULT_MAX_USERNAME_LENGTH + " characters";
        }
        if (username.length() < DEFAULT_MIN_USERNAME_LENGTH) {
            return "User name may not be shorter than " + DEFAULT_MIN_USERNAME_LENGTH + " characters";
        }
        if (!username.matches(DEFAULT_USERNAME_ALLOWED)) {
            return "User name '" + username + "' includes not allowed characters: use a-z, A-Z and 0-9";
        }
        return null;
    }

    /** `register_error` frame (the register flow's answer type). */
    private void sendRegisterError(String message) {
        sendTypedMessage("register_error", "message", rawOrEmpty(message));
    }

    private void handleConnect(com.google.gson.JsonObject json) {
        // Already-connected guard — a second "connect" over a live session
        // previously overwrote pingExecutor (leaking the old scheduler thread)
        // and abandoned the XMage session without connectStop.
        if (xmageSession != null) {
            logger.warn("Rejected duplicate connect while already connected as "
                    + (proxyClient != null ? proxyClient.getUsername() : "?"));
            sendTypedMessage("connect_error", "message", "Already connected — refresh to start a new session");
            return;
        }

        // Public-hosting pin: -Dwebproxy.pinnedXmageTarget=host:port pins the
        // XMage server every browser session connects to, ignoring the
        // client-supplied host/port entirely. Used in Docker deployments where
        // the browser cannot know (and must not choose) the container address.
        // With a pin, server/port are OPTIONAL in the connect message (the
        // connect form hides them — see -Dwebproxy.hideServerTarget and
        // GET /config); without a pin both remain required. The pinned target
        // still passes the allowlist check below.
        WebProxyServer.ConnectTarget target =
                WebProxyServer.resolveConnectTarget(json, System.getProperty("webproxy.pinnedXmageTarget", ""));
        if (target.error != null) {
            logger.error("connect rejected: " + target.error);
            sendTypedMessage("connect_error", "message", rawOrEmpty(target.error));
            // Close with 4401: the user fixes the target in the form and
            // resubmits — an OPEN socket would wedge the retry (see
            // closeSocketAfterConnectRejection).
            closeSocketAfterConnectRejection("Connect rejected — fix the server target");
            return;
        }
        String xmageServer = target.host;
        int xmagePort = target.port;
        if (target.pinned) {
            logger.info("Using pinned XMage target " + xmageServer + ":" + xmagePort
                    + " (client-supplied value ignored)");
        }
        String username = requireStringOrError(json, "username");
        if (username == null) return;
        String password = json.has("password") && !json.get("password").isJsonNull()
                ? json.get("password").getAsString() : "";
        // Shared-IP mitigation:
        // the browser sends a persistent browserId (web:<username>:<uuid>) so
        // the XMage server can tell users apart behind the VPS's single IP.
        // Falls back to "" when absent (old clients, direct WS probes).
        String browserId = json.has("browserId") && !json.get("browserId").isJsonNull()
                ? json.get("browserId").getAsString() : "";
        // Avatar: the browser's chosen avatar, persisted client-side with the
        // rest of xmage-settings. Absent (old bundles, direct WS probes) means the
        // default. A wire value is clamped by normalizeAvatarId — see its comment
        // for why a cosmetic id is repaired rather than refused.
        int requestedAvatarId = json.has("avatarId") && !json.get("avatarId").isJsonNull()
                ? json.get("avatarId").getAsInt() : AVATAR_DEFAULT_ID;
        int avatarId = normalizeAvatarId(requestedAvatarId);
        if (avatarId != requestedAvatarId) {
            logger.warn("Repaired out-of-range avatarId " + requestedAvatarId
                    + " to the default " + avatarId + " for " + username);
        }

        // SSRF guard: reject client-specified XMage hosts not in the allowlist.
        // The proxy must only connect to allowlisted XMage servers (default:
        // localhost only) so a local process cannot pivot through the proxy to
        // arbitrary host:port targets.
        if (!isAllowedXmageHost(xmageServer)) {
            String error = "XMage server host '" + xmageServer + "' is not in the allowlist. "
                + "Configure -Dwebproxy.allowedXmageHosts=host1,host2 to add hosts.";
            logger.error("Rejected connect to unallowed XMage host: " + xmageServer);
            sendTypedMessage("connect_error", "message", rawOrEmpty(error));
            // Close with 4401: the operator fixes the allowlist, the user can
            // only resubmit — an OPEN socket would wedge the retry (see
            // closeSocketAfterConnectRejection).
            closeSocketAfterConnectRejection("Connect rejected — XMage host not allowed");
            return;
        }

        // Per-host concurrent-connection cap. A hosted proxy's users all share this
        // machine's IP at xmage.today, so an unbounded number of concurrent
        // connections looks like a bot farm and invites an IP-level ban. The slot is
        // reserved BEFORE the dial (so N simultaneous attempts cannot overshoot the
        // cap) and released in cleanup() — every teardown path runs it. Refusal is
        // an explicit browser frame + warn log, never a silent drop.
        int connectionCap = PerHostConnectionLimiter.connectionCap();
        if (connectionCap > PerHostConnectionLimiter.UNLIMITED) {
            if (!PerHostConnectionLimiter.shared().acquire(xmageServer, xmagePort, connectionCap)) {
                logger.warn("Rejected connect to " + xmageServer + ":" + xmagePort
                        + " — at the per-host connection cap (" + connectionCap + ")"
                        + " [webproxy.maxConnectionsPerHost]");
                sendTypedMessage("connect_error", "message",
                        "Connection limit reached for this server — try again shortly");
                closeSocketAfterConnectRejection(
                        "Connect rejected — per-host connection limit reached");
                return;
            }
            // Remember the reserved slot so cleanup() can free it on EVERY teardown.
            hostSlotKey = PerHostConnectionLimiter.hostKey(xmageServer, xmagePort);
        }

        // Anon same-name collision guard:
        // the XMage server runs anon mode with ANON_IDENTIFY_BY_HOST=true, so a
        // same-name connect from the same host SILENTLY KILLS the older session —
        // and every browser dialed through this proxy shares the proxy's host,
        // so any two webui users with the same anon name kick each other in a
        // ping-fail/reconnect loop. The desktop client gets the same treatment
        // from its own machine, but there the "same host" is truthful; here it
        // is an artifact of hosting. The guard runs BEFORE the dial so the older
        // session is never touched. Same-name re-connect from the SAME browserId
        // is the legitimate reconnect-takeover path (page refresh with saved
        // creds; restoreSessionId is not forwarded through the proxy) and must
        // pass. Collision is scoped per target server so a multi-server proxy
        // never blocks across servers.
        String targetHost = target.host;
        int targetPort = target.port;
        String anonName = json.get("username").getAsString();
        String anonBrowserId = json.has("browserId") && !json.get("browserId").isJsonNull()
                ? json.get("browserId").getAsString() : "";
        AnonNameGuard.Decision guardDecision = AnonNameGuard.shared()
                .checkAndRegister(this, targetHost, targetPort, anonName, anonBrowserId);
        if (!guardDecision.allowed) {
            logger.warn("Rejected anon same-name collision: " + anonName
                    + " already connected via a different browser at " + targetHost + ":" + targetPort
                    + " (browserId " + (anonBrowserId.isEmpty() ? "<absent>" : anonBrowserId) + ")");
            sendTypedMessage("connect_error", "message",
                    "Name in use by another session — pick a different name");
            // Close with 4401: user-fixable (edit the name and resubmit) — an
            // OPEN socket would wedge the retry (see closeSocketAfterConnectRejection).
            closeSocketAfterConnectRejection("Name in use by another session");
            return;
        }
        activeConnect = guardDecision.active;

        // Create XMage client + session
        proxyClient = new WebProxyClient(username);
        proxyClient.markConnectStarted(); // for waitForLastError (auth-failure capture)
        xmageSession = new SessionImpl(proxyClient);
        proxyClient.setSession(xmageSession);
        proxyClient.setWsSender(this::sendToBrowser);
        // A replay that ends server-side (stop, or a missing/corrupt file found at
        // replay_start time) must drop this session's spectator flag — the flag is
        // raised by replay_load, and the file-missing case only surfaces
        // asynchronously via REPLAY_DONE, never at replay_load time.
        proxyClient.setReplayEndListener(() -> spectator = false);

        // Set up connection
        connection = new Connection();
        connection.setHost(xmageServer);
        connection.setPort(xmagePort);
        connection.setUsername(username);
        connection.setPassword(password);
        connection.setProxyType(Connection.ProxyType.NONE);
        // Mitigation 2: give the XMage server a per-user string to distinguish
        // users behind the shared VPS IP. Empty when the browser didn't send one.
        connection.setUserIdStr(browserId);

        // UserData — configure for manual step-by-step play.
        // In UserSkipPrioritySteps, true means "stop here" (don't skip), so all
        // true = stop at every phase: upkeep, draw, main, combat, end of turn, etc.
        // This mirrors the XMage desktop client with all "skip" checkboxes CHECKED
        // (i.e. the player chooses to stop at each step rather than auto-skipping).
        UserSkipPrioritySteps skipSteps = new UserSkipPrioritySteps();
        skipSteps.getYourTurn().setUpkeep(true);
        skipSteps.getYourTurn().setDraw(true);
        skipSteps.getYourTurn().setMain1(true);
        skipSteps.getYourTurn().setBeforeCombat(true);
        skipSteps.getYourTurn().setEndOfCombat(true);
        skipSteps.getYourTurn().setMain2(true);
        skipSteps.getYourTurn().setEndOfTurn(true);
        skipSteps.getOpponentTurn().setUpkeep(true);
        skipSteps.getOpponentTurn().setDraw(true);
        skipSteps.getOpponentTurn().setMain1(true);
        skipSteps.getOpponentTurn().setBeforeCombat(true);
        skipSteps.getOpponentTurn().setEndOfCombat(true);
        skipSteps.getOpponentTurn().setMain2(true);
        skipSteps.getOpponentTurn().setEndOfTurn(true);
        skipSteps.setStopOnDeclareBlockersWithZeroPermanents(true);
        skipSteps.setStopOnDeclareBlockersWithAnyPermanents(true);
        skipSteps.setStopOnAllMainPhases(true);
        skipSteps.setStopOnAllEndPhases(true);
        skipSteps.setStopOnStackNewObjects(true);
        UserData userData = buildWebProxyUserData(skipSteps, browserId, avatarId);
        connection.setUserData(userData);
        // Remember the effective avatar so a later mid-session rebuild (phase
        // stops, set_avatar) threads it back into the fresh UserData.
        sessionAvatarId = avatarId;
        logger.info("Avatar for " + username + ": id=" + avatarId
                + (avatarId != requestedAvatarId ? " (repaired from " + requestedAvatarId + ")" : ""));

        logger.info("Connecting to XMage server at " + xmageServer + ":" + xmagePort + " as " + username);

        if (!xmageSession.connectStart(connection)) {
            // The server reports auth failures (wrong password, unregistered
            // name, deactivated account) via an ASYNC showError callback AFTER
            // connectUser returns false (its 3 s anti-bruteforce sleep delays
            // it) — getLastError() is empty at this point. Capture that message
            // so the browser gets the real reason instead of an empty string.
            String error = xmageSession.getLastError();
            String asyncError = proxyClient.waitForLastError(4000);
            String message = !asyncError.isEmpty() ? asyncError
                    : !error.isEmpty() ? error
                    : "Login failed — wrong username or password";
            logger.error("Failed to connect to XMage server: " + message);
            sendTypedMessage("connect_error", "message", rawOrEmpty(message));
            // Close with 4401: the user fixes their credentials in the form and
            // resubmits — an OPEN socket would make the browser's WS manager
            // drop the retried connect() as "already connected", wedging the
            // form until reload (measured live 2026-09-22, 15-char username).
            // The half-built session is torn down by cleanup() via the close.
            closeSocketAfterConnectRejection("Login failed — resubmit with corrected credentials");
            return;
        }

        logger.info("Connected to XMage server");

        // Probe server capabilities: what THIS server can do
        // (LLM seats, scrubber, sidecar) — derived from the server's own
        // ServerState, never from the target address. Drives the connected
        // message's "capabilities" object and every gate below.
        serverCapabilities = ServerCapabilities.probe(xmageSession);
        logger.info("Server capabilities: " + GSON.toJson(serverCapabilities.toWire()));

        // Start ping keepalive
        startPing();

        try {
            // Send connected message — carries the capability flags the frontend
            // gates its UI on (llm=false hides every LLM control and message path).
            Map<String, Object> connectedMsg = new HashMap<>();
            connectedMsg.put("type", "connected");
            connectedMsg.put("username", rawOrEmpty(username));
            connectedMsg.put("capabilities", serverCapabilities.toWire());
            sendToBrowser(gsonToJson(connectedMsg));

            // Auto-request tables, then check for rejoinable games.
            // Re-read the field: cleanup() nulls xmageSession from the WS-close
            // thread, so reading the field twice can observe different values
            // (it was nulled between connectStart and here in the repro). A
            // volatile field read into a local is the single-session-view fix.
            SessionImpl session = xmageSession;
            if (session == null) {
                // Fail Fast: the browser socket died during connect and cleanup()
                // tore the XMage session down. Tell the browser instead of NPEing
                // into the generic "Internal error" path.
                logger.error("Session torn down during connect (browser socket closed mid-connect)"
                        + " — aborting connect for " + username);
                sendTypedMessage("connect_error", "message",
                        "Connection lost during connect — reload to retry");
                return;
            }
            handleGetTables(session);
            checkForRejoinableGame(session, session.getMainRoomId(), username);
            // Reload case: the frontend's seated flags are gone but the seat is not, so the
            // seat-change watch has to be re-armed from the server's own view.
            startTableWatchForSeatedTable(session);

            // Join the MAIN ROOM chat so lobby talk/whisper/system lines reach the
            // browser while the user sits in the lobby. Desktop parity:
            // TablesPanel.showTables → ChatPanelBasic.connect →
            // SessionHandler.joinChat(chatRoomId). Without this the proxy SENDS room
            // chat fine (handleSendChat falls back to the room chat id) but never
            // RECEIVES anything from it — CHATMESSAGE callbacks only fire for chat
            // sessions this client has joined (server-side ChatManagerImpl keeps a
            // per-session list). Best-effort, same posture as the game-chat join in
            // WebProxyClient.handleStartGame: a failure logs and continues, it must
            // not break the connect flow.
            try {
                java.util.Optional<UUID> roomChatIdOpt = session.getRoomChatId(session.getMainRoomId());
                if (roomChatIdOpt.isPresent()) {
                    boolean joined = session.joinChat(roomChatIdOpt.get());
                    logger.info("[" + username + "] Joined room chat: chatId=" + roomChatIdOpt.get() + " success=" + joined);
                } else {
                    logger.warn("[" + username + "] No room chat ID found for room " + session.getMainRoomId());
                }
            } catch (Exception e) {
                logger.error("[" + username + "] Failed to join room chat: " + e.getMessage());
            }
        } catch (Throwable t) {
            // If anything after startPing() throws, cancel the ping executor
            // to prevent a thread leak. cleanup() also handles this, but it's
            // only called on WebSocket close — the onWebSocketText catch block
            // sends an error but doesn't clean up the executor, so it would leak.
            logger.error("Error after startPing — cancelling ping executor", t);
            if (pingExecutor != null) {
                pingExecutor.shutdownNow();
                pingExecutor = null;
            }
            throw t;
        }
    }

    /**
     * Scan all tables for one where the user is already seated and the game
     * is in DUELING state. If found, send a rejoin_game message to the browser
     * so it can re-attach to the in-progress game via joinGame(gameId).
     */
    private void checkForRejoinableGame(SessionImpl session, UUID roomId, String playerName) {
        try {
            Collection<TableView> tables = session.getTables(roomId);
            if (tables == null) return;
            for (TableView table : tables) {
                // Only tables with an active game
                if (table.getTableState() != mage.constants.TableState.DUELING) continue;
                // Check if this user is seated at this table
                boolean found = false;
                if (table.getSeats() != null) {
                    for (mage.view.SeatView seat : table.getSeats()) {
                        // The seat may carry the typed seat name, not the username.
                        if (SeatAliases.isCreatorSeat(playerName, seat.getPlayerName())) {
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) continue;
                // Get the latest game ID from this table
                if (table.getGames() == null || table.getGames().isEmpty()) continue;
                UUID gameId = table.getGames().get(table.getGames().size() - 1);
                logger.info("Found rejoinable game: table=" + table.getTableId()
                    + " gameId=" + gameId + " state=" + table.getTableState());
                Map<String, Object> msg = new HashMap<>();
                msg.put("type", "rejoin_game");
                msg.put("gameId", gameId.toString());
                msg.put("tableId", table.getTableId().toString());
                sendToBrowser(gsonToJson(msg));
                return;  // only rejoin the first matching game
            }
        } catch (Throwable t) {
            logger.warn("Error checking for rejoinable game", t);
        }
    }

    /**
     * Overload for callers that already hold a session snapshot (see
     * {@link #handleConnect}) — avoids re-reading the volatile field, which can
     * have been nulled by a concurrent {@code cleanup()}.
     */
    private void handleGetTables(SessionImpl session) {
        try {
            UUID roomId = session.getMainRoomId();
            if (roomId == null) {
                sendError("No main room available");
                return;
            }

            Collection<TableView> tables = session.getTables(roomId);
            String tableJson = proxyClient.serializeTableList(tables, resolveOwnedTableIds(session, tables));
            sendToBrowser(tableJson);

        } catch (MageRemoteException e) {
            logger.error("Error getting tables", e);
            sendError(rawOrEmpty(e.getMessage()));
        }
    }

    private void handleGetTables() {
        SessionImpl session = xmageSession;
        if (session == null) {
            sendError("Not connected to XMage server");
            return;
        }
        handleGetTables(session);
    }

    /**
     * Candidate 2: which of
     * {@code tables} this session's account is the OWNER of — the ids the table list
     * serializer flags with {@code "owner": true}.
     *
     * <p><b>Which tables.</b> Only NON-TOURNAMENT tables still in WAITING or
     * READY_TO_START are considered. That is the whole window in which the browser
     * consults the flag: the grid offers the non-seated owner a Start Match control on
     * exactly such a row, and the proxy's own pending auto-start lives in the same
     * state. A table that already started is past that window, so probing it would cost
     * an RPC per push for an answer nothing reads. Tournament tables are excluded for
     * the same reason — their start flow is a different verb ({@code start_tournament})
     * and a different control.
     *
     * <p><b>Where answers come from.</b> {@link TableOwnership} first: a positive
     * verdict is immortal (ownership is immutable for a live table — it is the creating
     * session's user id held by the TableController) and a negative one is TTL-bounded
     * (see the class comment for why). On a cache miss the authoritative RPC is probed —
     * {@link SessionImpl#isTableOwner}, the very check {@code startMatch} enforces
     * server-side — and the answer is remembered.
     *
     * <p><b>Fail-safe on a probe that cannot run or fails.</b> The username is the key,
     * exactly like the username-based seat checks, and a failed probe must never be
     * remembered as a false negative. {@code SessionImpl.isTableOwner} swallows its RPC
     * exceptions and answers {@code false} (its body catches {@code MageException} and
     * {@code Throwable} and falls through to {@code return false}), so by return value
     * alone a FAILED probe is indistinguishable from a genuine "not the owner". Two
     * consequences, both deliberate:
     * <ul>
     *   <li>Nothing is probed while {@code session.isConnected()} is false. The call could
     *       only fail, and remembering that failure as a negative would hide the owner's
     *       control without any evidence. That path caches NOTHING and returns whatever
     *       the cache already knew — fail-open, and never a fabricated verdict.</li>
     *   <li>A probe that DID run and answered {@code false} is remembered as a negative,
     *       but a negative expires after {@link TableOwnership#NEGATIVE_TTL_MS}. So even an
     *       RPC failure that answered {@code false} can hide the owner's control for at
     *       most that window, never for the lifetime of the proxy (which is exactly why
     *       negatives are TTL-bounded in the cache). A probe that answers {@code true}
     *       could only have come from a successful RPC, so it is immortal.</li>
     * </ul>
     *
     * <p>Never throws: the table-watch tick calls this on its push path, and a resolve
     * failure must not take the watch (or the get_tables reply) down with it.
     *
     * @param session the XMage session this WS session is connected through (both call
     *                sites pass a non-null session they already hold)
     * @param tables  the room's table list as just read; may be null
     * @return the ids of the tables this account owns; empty when none are knowable
     */
    Collection<UUID> resolveOwnedTableIds(SessionImpl session, Collection<TableView> tables) {
        List<UUID> owned = new ArrayList<>();
        if (session == null || tables == null || tables.isEmpty() || proxyClient == null) {
            return owned;
        }
        String username = proxyClient.getUsername();
        if (username == null || username.isEmpty()) {
            return owned;
        }
        long now = System.currentTimeMillis();
        // See the fail-safe note above: only ask while the session can actually answer.
        boolean probeAllowed = session.isConnected();
        UUID roomId = null; // read lazily — only a probe that is actually going to run needs it
        for (TableView table : tables) {
            if (table == null || table.getTableId() == null || table.isTournament()) {
                continue;
            }
            TableState state = table.getTableState();
            if (state != TableState.WAITING && state != TableState.READY_TO_START) {
                continue;
            }
            UUID tableId = table.getTableId();
            Boolean cached = TableOwnership.cached(username, tableId, now);
            if (cached == null) {
                if (!probeAllowed) {
                    continue; // cannot ask right now — never remember a verdict we did not get
                }
                if (roomId == null) {
                    roomId = session.getMainRoomId();
                    if (roomId == null) {
                        // Not Fail Fast: this runs on the table-watch push path, where
                        // throwing would kill the watch. There is nothing to look up, so
                        // no table is flagged; the next push (a fresh connect) retries.
                        logger.warn("Cannot probe table ownership for " + username
                                + ": getMainRoomId() returned null (connected="
                                + session.isConnected() + ")");
                        probeAllowed = false;
                        continue;
                    }
                }
                boolean owner;
                try {
                    owner = session.isTableOwner(roomId, tableId);
                } catch (Throwable t) {
                    // SessionImpl swallows its own RPC failures; this is belt-and-braces
                    // for a session torn down mid-call. Do NOT remember a verdict here.
                    logger.warn("Table ownership probe failed for table " + tableId
                            + " — leaving it unflagged", t);
                    continue;
                }
                TableOwnership.remember(username, tableId, owner, now);
                cached = owner;
            }
            if (cached) {
                owned.add(tableId);
            }
        }
        return owned;
    }

    // ── Spectator mode ──

    private void handleWatchGame(com.google.gson.JsonObject json) {
        if (xmageSession == null) {
            sendError("Not connected to XMage server");
            return;
        }

        String tableIdStr = requireStringOrError(json, "tableId");
        if (tableIdStr == null) return;
        UUID tableId = parseUuidOrError(tableIdStr, "tableId");
        if (tableId == null) return;

        UUID roomId = xmageSession.getMainRoomId();

        logger.info("Watching table " + tableId);
        boolean success = xmageSession.watchTable(roomId, tableId);
        if (!success) {
            String error = xmageSession.getLastError();
            sendError("Failed to watch table: " + rawOrEmpty(error != null ? error : "unknown"));
            return;
        }
        spectator = true; // watching = spectator; game actions are rejected while set

        logger.info("Watching table " + tableId + " — waiting for game to start");
        // The WATCHGAME callback will fire with the gameId in its objectId.
        // We then need to call watchGame(gameId) to start receiving game state.
        // This is handled in WebProxyClient.handleCallback() under case WATCHGAME.
    }

    /**
     * Watch a tournament sub-table (a match within a running tournament).
     * Uses watchTournamentTable which calls server.roomWatchTournament(sessionId, tableId).
     * The WATCHGAME callback will fire with the gameId, same as regular watch.
     * Browser sends: {"type": "watch_tournament_table", "tableId": "uuid"}
     */
    private void handleWatchTournamentTable(com.google.gson.JsonObject json) {
        if (xmageSession == null) {
            sendError("Not connected to XMage server");
            return;
        }

        if (!json.has("tableId")) {
            sendError("Missing tableId");
            return;
        }

        String tableIdStr = json.get("tableId").getAsString();
        UUID tableId = parseUuidOrError(tableIdStr, "tableId");
        if (tableId == null) return;

        logger.info("Watching tournament table " + tableId);
        boolean success = xmageSession.watchTournamentTable(tableId);
        if (!success) {
            String error = xmageSession.getLastError();
            sendError("Failed to watch tournament table: " + rawOrEmpty(error != null ? error : "unknown"));
            return;
        }
        spectator = true; // watching = spectator

        logger.info("Watching tournament table " + tableId + " — waiting for WATCHGAME callback");
    }

    /**
     * Refresh tournament data (called by frontend polling).
     * Browser sends: {"type": "refresh_tournament", "tournamentId": "uuid"}
     * Responds with a tournament_update message containing the full TournamentView.
     */
    private void handleRefreshTournament(com.google.gson.JsonObject json) {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not connected to XMage server");
            return;
        }

        if (!json.has("tournamentId")) {
            sendError("Missing tournamentId");
            return;
        }

        UUID tournamentId = parseUuidOrError(json.get("tournamentId").getAsString(), "tournamentId");
        if (tournamentId == null) return;
        try {
            mage.view.TournamentView tourView = xmageSession.getTournament(tournamentId);
            if (tourView != null) {
                Map<String, Object> tourMsg = new HashMap<>();
                tourMsg.put("type", "tournament_update");
                tourMsg.put("tournamentId", tournamentId.toString());
                tourMsg.put("tournament", WebProxyClient.serializeTournamentView(tourView));
                sendToBrowser(gsonToJson(tourMsg));
            }
        } catch (Exception e) {
            logger.warn("Failed to refresh tournament: " + e.getMessage());
        }
    }

    // ── Replay ──
    //
    // Thin request wrappers over the EXISTING server/remote machinery (no engine
    // changes): SessionImpl.replayGame / startReplay / stopReplay / nextPlay /
    // previousPlay / skipForward (Replays interface) and getFinishedMatches.
    // Server callbacks come back through WebProxyClient's REPLAY_* cases.
    //
    // The replay file list is read from the server's own `saved/` directory. That
    // is the ONLY place that makes sense: GameReplay.loadGame() opens
    // `saved/<gameId>.game` RELATIVE to the SERVER process's working directory,
    // so a file the proxy found elsewhere would not be loadable, and the proxy
    // cannot ask the server "what files do you have?" — the remote API has no
    // such call. The id list the frontend receives is exactly the set of ids
    // replayInit can succeed on. (The desktop client instead shows a JFileChooser
    // so the user points at a file the CLIENT can see — which only works because
    // client and server share a machine/directory there; it is not an alternative
    // mechanism.) Reading it is best-effort in ONE respect only: an unreadable
    // directory yields an empty list, which the UI renders as "no local replays"
    // — there is nothing to fail on, the directory may simply not exist.

    /**
     * List finished match records of the main room so the browser can offer a
     * per-game "Replay" action (desktop parity: TablesPanel's tableCompleted +
     * MatchesTableModel "Replay" button).
     * Browser sends: {"type": "get_finished_matches"}
     * Responds: {"type": "finished_matches", "matches": [...MatchView...]}
     */
    private void handleGetFinishedMatches() {
        if (xmageSession == null) {
            sendError("Not connected to XMage server");
            return;
        }
        UUID roomId = xmageSession.getMainRoomId();
        if (roomId == null) {
            sendError("No main room available");
            return;
        }
        try {
            Collection<mage.view.MatchView> matches = xmageSession.getFinishedMatches(roomId);
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "finished_matches");
            msg.put("matches", serializeFinishedMatches(matches));
            sendToBrowser(gsonToJson(msg));
        } catch (MageRemoteException e) {
            logger.error("Error getting finished matches", e);
            sendError("Failed to get finished matches: " + rawOrEmpty(e.getMessage()));
        }
    }

    /**
     * Serialize the server's finished-match records (MatchView) for the browser.
     * Field names follow the existing JSON convention (camelCase, ids as strings).
     * `games` is the list of game UUIDs in that match — the desktop client replays
     * ONE of them (it opens a chooser when a match has several games), so the
     * frontend gets the full list and lets the user pick.
     */
    static List<Map<String, Object>> serializeFinishedMatches(Collection<mage.view.MatchView> matches) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (matches == null) return out;
        for (mage.view.MatchView m : matches) {
            Map<String, Object> e = new HashMap<>();
            e.put("matchId", m.getMatchId() != null ? m.getMatchId().toString() : null);
            e.put("tableId", m.getTableId() != null ? m.getTableId().toString() : null);
            e.put("name", m.getName());
            e.put("gameType", m.getGameType());
            e.put("deckType", m.getDeckType());
            e.put("players", m.getPlayers());
            e.put("result", m.getResult());
            e.put("tournament", m.isTournament());
            e.put("replayAvailable", m.isReplayAvailable());
            e.put("startTime", m.getStartTime() != null ? m.getStartTime().getTime() : null);
            e.put("endTime", m.getEndTime() != null ? m.getEndTime().getTime() : null);
            e.put("rated", m.isRated());
            List<Map<String, Object>> playerRows = new ArrayList<>();
            if (m.getPlayerList() != null) {
                for (mage.view.MatchPlayerView p : m.getPlayerList()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("name", p.getName());
                    row.put("wins", p.getWins());
                    row.put("quit", p.hasQuit());
                    row.put("deckName", p.getDeckName());
                    playerRows.add(row);
                }
            }
            e.put("playerList", playerRows);
            List<String> games = new ArrayList<>();
            if (m.getGames() != null) {
                for (UUID g : m.getGames()) {
                    games.add(g.toString());
                }
            }
            e.put("games", games);
            out.add(e);
        }
        return out;
    }

    /**
     * List the main room's online users so the browser can show the lobby's
     * right-hand users column (desktop parity: the Swing client's users/flags
     * column). Follows the {@link #handleGetFinishedMatches()} request/response
     * pattern.
     *
     * Browser sends: {"type": "get_room_users"}
     * Responds: {"type": "users_list", "users": [...UsersView...], "motd": [...]}
     *
     * The response also carries the server's Message-of-the-Day lines (the same
     * strings the desktop client renders in its blue bottom strip —
     * TablesPanel.reloadServerMessages pulls them via SessionImpl.getServerMessages,
     * which decompresses ServerMessagesUtil.getMessages()). Bundling the pull here
     * keeps it one round trip; the frontend renders them via MotdStrip and routes
     * every MOTD-shaped chat type to the System tab regardless (see
     * utils/lobbyChat.routeChat).
     */
    private void handleGetRoomUsers() {
        if (xmageSession == null) {
            sendError("Not connected to XMage server");
            return;
        }
        UUID roomId = xmageSession.getMainRoomId();
        if (roomId == null) {
            sendError("No main room available");
            return;
        }
        try {
            Collection<mage.view.RoomUsersView> roomUsers = xmageSession.getRoomUsers(roomId);
            List<Map<String, Object>> users = new ArrayList<>();
            for (mage.view.RoomUsersView room : roomUsers) {
                users.addAll(serializeRoomUsers(room));
            }
            List<String> motd = xmageSession.getServerMessages();
            if (motd == null) {
                motd = new ArrayList<>();
            }
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "users_list");
            msg.put("users", users);
            msg.put("motd", motd);
            sendToBrowser(gsonToJson(msg));
        } catch (MageRemoteException e) {
            logger.error("Error getting room users", e);
            sendError("Failed to get room users: " + rawOrEmpty(e.getMessage()));
        }
    }

    /**
     * Serialize one room's user records (mage.view.RoomUsersView →
     * mage.view.UsersView) for the browser. Field names follow the existing JSON
     * convention (camelCase); every field is one UsersView actually exposes —
     * nothing invented.
     */
    static List<Map<String, Object>> serializeRoomUsers(mage.view.RoomUsersView room) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (room == null || room.getUsersView() == null) return out;
        for (mage.view.UsersView u : room.getUsersView()) {
            if (u == null) continue;
            Map<String, Object> e = new HashMap<>();
            e.put("flagName", u.getFlagName());
            e.put("userName", u.getUserName());
            e.put("matchHistory", u.getMatchHistory());
            e.put("matchQuitRatio", u.getMatchQuitRatio());
            e.put("tourneyHistory", u.getTourneyHistory());
            e.put("tourneyQuitRatio", u.getTourneyQuitRatio());
            e.put("infoGames", u.getInfoGames());
            e.put("infoPing", u.getInfoPing());
            e.put("generalRating", u.getGeneralRating());
            e.put("constructedRating", u.getConstructedRating());
            e.put("limitedRating", u.getLimitedRating());
            out.add(e);
        }
        return out;
    }

    /**
     * List replay files present in the server's `saved/` directory (see the
     * section comment above for why the directory is read here).
     * Browser sends: {"type": "get_replay_files"}
     * Responds: {"type": "replay_files", "gameIds": ["<uuid>"...], "directory": "<abs path>"}
     *
     * The directory is `-Dwebproxy.replayDir` (default "saved", which is exactly
     * the relative path GameReplay.loadGame resolves against the SERVER's working
     * directory). The resolved ABSOLUTE path is reported to the browser so a
     * proxy whose working directory differs from the server's is immediately
     * visible instead of silently listing nothing.
     */
    private void handleGetReplayFiles() {
        java.io.File dir = new java.io.File(REPLAY_SAVED_DIR);
        List<String> ids = new ArrayList<>();
        // Per-file searchable metadata, best-effort: a legacy save has no sidecar
        // JSON yet (null fields) — the row still works, it just shows less.
        Map<String, Map<String, Object>> fileMeta = new HashMap<>();
        if (dir.isDirectory()) {
            java.io.File[] files = dir.listFiles(
                    (d, name) -> name.endsWith(".game"));
            if (files != null) {
                for (java.io.File f : files) {
                    String name = f.getName();
                    String uuid = name.substring(0, name.length() - ".game".length());
                    try {
                        UUID.fromString(uuid);
                    } catch (IllegalArgumentException e) {
                        // The desktop client only accepts <uuid>.game; a file named
                        // otherwise can never be loaded (replayInit takes a UUID), so
                        // listing it would offer the user a guaranteed failure.
                        logger.warn("Skipping non-UUID replay file: " + sanitizeForLog(name));
                        continue;
                    }
                    ids.add(uuid);
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("bytes", f.length());
                    meta.put("savedAtMs", f.lastModified());
                    // Sidecar JSON written by the server at save time
                    // (mage.server.game.ReplayMetadata) — parsed, never evaluated.
                    java.io.File sidecar = new java.io.File(REPLAY_SAVED_DIR, uuid + ".json");
                    if (sidecar.isFile()) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> parsed = GSON.fromJson(
                                    new String(java.nio.file.Files.readAllBytes(sidecar.toPath()),
                                            java.nio.charset.StandardCharsets.UTF_8),
                                    Map.class);
                            if (parsed != null) {
                                meta.putAll(parsed);
                            }
                        } catch (Exception e) {
                            // A torn/corrupt sidecar degrades the row to legacy
                            // display; it must not remove a loadable replay.
                            logger.warn("Replay sidecar unreadable for " + sanitizeForLog(uuid) + ": " + e.getMessage());
                        }
                    }
                    fileMeta.put(uuid, meta);
                }
            }
            java.util.Collections.sort(ids);
        } else {
            logger.warn("Replay directory '" + dir.getAbsolutePath() + "' does not exist —"
                    + " returning an empty replay list (is the proxy's working directory the"
                    + " server's? override with -Dwebproxy.replayDir)");
        }
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "replay_files");
        msg.put("directory", dir.getAbsolutePath());
        msg.put("exists", dir.isDirectory());
        msg.put("gameIds", ids);
        msg.put("fileMeta", fileMeta);
        sendToBrowser(gsonToJson(msg));
    }

    /**
     * Directory the SERVER loads replays from. Default `saved` is relative to the
     * process working directory, matching GameReplay.loadGame's own
     * `Paths.get("saved/" + gameId + ".game")` — so it is correct whenever the
     * proxy shares the server's working directory (the normal layout). Override
     * with -Dwebproxy.replayDir when it does not.
     */
    static final String REPLAY_SAVED_DIR = System.getProperty("webproxy.replayDir", "saved").trim();

    /**
     * Size in bytes of a replay file, or null when it is not readable from the
     * proxy. Reported in the `replay_loading` frame so the browser can show the
     * size and a "this may take a moment" hint instead of an apparently hung
     * socket. A null is NOT an error here — the file may simply be unreadable from
     * the proxy's filesystem while still loading fine server-side (that is exactly
     * the split-container case), and the load itself must proceed either way.
     */
    private static Long replayFileSize(UUID gameId) {
        java.io.File f = new java.io.File(REPLAY_SAVED_DIR, gameId.toString() + ".game");
        return f.isFile() ? Long.valueOf(f.length()) : null;
    }

    /**
     * Load a replay: creates the server-side ReplaySession for the given game.
     * Browser sends: {"type": "replay_load", "gameId": "uuid"}
     * The server answers with a REPLAY_GAME callback (-> "replay_game" message);
     * the browser then sends replay_start to receive the first frame.
     *
     * Fails fast: a malformed/missing gameId yields an error message, and a
     * non-UUID string is rejected before it reaches the server.
     */
    private void handleReplayLoad(com.google.gson.JsonObject json) {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not connected to XMage server");
            return;
        }
        String gameIdStr = requireStringOrError(json, "gameId");
        if (gameIdStr == null) return;
        UUID gameId = parseUuidOrError(gameIdStr, "gameId");
        if (gameId == null) return;

        // A replay and a live game share one client callback stream; navigating
        // both at once would interleave two boards into one viewer. Refuse rather
        // than silently drop frames.
        if (proxyClient.getCurrentGameId() != null) {
            sendError("Cannot load a replay while in a game — stop watching/playing first");
            return;
        }
        if (proxyClient.getReplayGameId() != null && !gameId.equals(proxyClient.getReplayGameId())) {
            sendError("A replay is already loaded (" + proxyClient.getReplayGameId()
                    + ") — stop it before loading another");
            return;
        }

        logger.info("Loading replay " + gameId);
        // Acknowledge BEFORE the blocking load. The server deserializes the whole
        // Game + GameStates (gzip) inside replayInit, which takes seconds for a
        // multi-MB save (5.2 MB measured at ~4.8 s), and until REPLAY_GAME arrives
        // the browser cannot tell "still loading" from "the request never arrived"
        // This is an INTERIM frame only:
        // replay_game / replay_init / replay_done stay the completion signals, so a
        // client that ignores this message behaves exactly as before.
        Map<String, Object> loading = new HashMap<>();
        loading.put("type", "replay_loading");
        loading.put("gameId", gameId.toString());
        loading.put("bytes", replayFileSize(gameId));
        sendToBrowser(gsonToJson(loading));

        // A replay is a spectator-style channel: while one is loaded, game-action
        // messages must be refused. `spectator` is the existing gate for exactly
        // that (PLAYER_ONLY_ACTIONS), and every join path already resets it.
        spectator = true;
        if (!xmageSession.replayGame(gameId)) {
            spectator = false;
            String error = xmageSession.getLastError();
            sendError("Failed to load replay " + gameId + ": " + rawOrEmpty(error != null ? error : "unknown"));
            return;
        }
        // No response here: REPLAY_GAME callback -> "replay_game" message.
    }

    /**
     * Start replay playback (first frame + subsequent frames on demand).
     * Browser sends: {"type": "replay_start"}
     * Server answers with REPLAY_INIT (-> "replay_init" with a GameView) or, if
     * the file is missing/corrupt, REPLAY_DONE ("Replay file not found or corrupt").
     */
    private void handleReplayStart() {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not connected to XMage server");
            return;
        }
        UUID gameId = requireLoadedReplayId();
        if (gameId == null) return;
        logger.info("Starting replay " + gameId);
        if (!xmageSession.startReplay(gameId)) {
            String error = xmageSession.getLastError();
            sendError("Failed to start replay " + gameId + ": " + rawOrEmpty(error != null ? error : "unknown"));
        }
        // Frames arrive as REPLAY_INIT / REPLAY_UPDATE callbacks.
    }

    /**
     * Stop the replay: ends the server-side ReplaySession and closes the viewer.
     * Browser sends: {"type": "replay_stop"}
     * The server answers with REPLAY_DONE ("stopped replay").
     */
    private void handleReplayStop() {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not connected to XMage server");
            return;
        }
        UUID gameId = requireLoadedReplayId();
        if (gameId == null) return;
        // Mark BEFORE the call: the REPLAY_DONE that answers it must be
        // classified as 'stopped', not as a boundary/error.
        proxyClient.markReplayStopRequested();
        logger.info("Stopping replay " + gameId);
        if (!xmageSession.stopReplay(gameId)) {
            String error = xmageSession.getLastError();
            sendError("Failed to stop replay " + gameId + ": " + rawOrEmpty(error != null ? error : "unknown"));
            return;
        }
        // Replay is over — this session is no longer in spectator mode.
        spectator = false;
    }

    /**
     * Step the replay one frame forward / backward, or skip N frames forward.
     * Browser sends: {"type": "replay_next"} | {"type": "replay_previous"}
     *                 | {"type": "replay_skip", "moves": 10}
     * Frames arrive as REPLAY_UPDATE; at either end the server sends REPLAY_DONE
     * with "end of replay" and KEEPS the session alive (stepping back still works).
     */
    private void handleReplayNext() {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not connected to XMage server");
            return;
        }
        UUID gameId = requireLoadedReplayId();
        if (gameId == null) return;
        if (!xmageSession.nextPlay(gameId)) {
            String error = xmageSession.getLastError();
            sendError("Failed to advance replay " + gameId + ": " + rawOrEmpty(error != null ? error : "unknown"));
        }
    }

    private void handleReplayPrevious() {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not connected to XMage server");
            return;
        }
        UUID gameId = requireLoadedReplayId();
        if (gameId == null) return;
        if (!xmageSession.previousPlay(gameId)) {
            String error = xmageSession.getLastError();
            sendError("Failed to rewind replay " + gameId + ": " + rawOrEmpty(error != null ? error : "unknown"));
        }
    }

    private void handleReplaySkip(com.google.gson.JsonObject json) {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not connected to XMage server");
            return;
        }
        UUID gameId = requireLoadedReplayId();
        if (gameId == null) return;
        // moves is required — no silent default (Fail Fast).
        if (!json.has("moves") || json.get("moves").isJsonNull()) {
            sendError("Missing moves");
            return;
        }
        int moves;
        try {
            moves = json.get("moves").getAsInt();
        } catch (RuntimeException e) {
            sendError("Invalid moves (not a number)");
            return;
        }
        if (moves < 1 || moves > MAX_REPLAY_SKIP) {
            sendError("moves must be between 1 and " + MAX_REPLAY_SKIP);
            return;
        }
        logger.info("Skipping replay " + gameId + " forward " + moves + " frames");
        if (!xmageSession.skipForward(gameId, moves)) {
            String error = xmageSession.getLastError();
            sendError("Failed to skip replay " + gameId + ": " + rawOrEmpty(error != null ? error : "unknown"));
        }
    }

    /** Max frames one replay_skip may advance (the desktop client's button uses 10). */
    private static final int MAX_REPLAY_SKIP = 100;

    /**
     * Jump to a saved-state index — the viewer's position control (scrubber).
     * Browser sends: {"type": "replay_seek", "stateIndex": 137}
     * One round-trip regardless of distance, and frame 0 is reachable (unlike
     * replay_previous, which cannot step back onto the first frame).
     * Answers with REPLAY_UPDATE for the target frame.
     */
    private void handleReplaySeek(com.google.gson.JsonObject json) {
        // Scrubber RPC is an LLMage-server addition for 1.4.61 (upstream Replays
        // has no seekTo — measured). Gate on the server capability.
        requireServerLlmGate("replay_seek");
        if (xmageSession == null || proxyClient == null) {
            sendError("Not connected to XMage server");
            return;
        }
        UUID gameId = requireLoadedReplayId();
        if (gameId == null) return;
        // stateIndex is required — no silent default (Fail Fast).
        if (!json.has("stateIndex") || json.get("stateIndex").isJsonNull()) {
            sendError("Missing stateIndex");
            return;
        }
        int stateIndex;
        try {
            stateIndex = json.get("stateIndex").getAsInt();
        } catch (RuntimeException e) {
            sendError("Invalid stateIndex (not a number)");
            return;
        }
        if (stateIndex < 0) {
            sendError("stateIndex must be >= 0");
            return;
        }
        // Range-check HERE, not just server-side. An out-of-range seek throws in
        // the server's CALL thread: the trace is logged but the client gets no
        // answer at all, so the browser would wait forever (measured 2026-09-21 —
        // seek(265) of a 265-state replay). The proxy knows the track length from
        // the last frame it sent, so it can refuse locally and say why.
        int count = proxyClient.getReplayStateCount();
        if (count > 0 && stateIndex >= count) {
            sendError("stateIndex " + stateIndex + " is outside the replay range 0.." + (count - 1));
            return;
        }
        logger.info("Seeking replay " + gameId + " to state index " + stateIndex);
        if (!xmageSession.seekTo(gameId, stateIndex)) {
            String error = xmageSession.getLastError();
            sendError("Failed to seek replay " + gameId + ": " + rawOrEmpty(error != null ? error : "unknown"));
        }
    }

    /**
     * The replay game id this session is driving, or null after sending the
     * appropriate error. Shared by every replay playback handler: without a
     * loaded replay there is nothing to start/stop/step, and the server's
     * ReplayManagerImpl would only log a warning and no-op (silent failure).
     */
    private UUID requireLoadedReplayId() {
        UUID gameId = proxyClient.getReplayGameId();
        if (gameId == null) {
            sendError("No replay loaded — send replay_load first");
            return null;
        }
        return gameId;
    }

    // ── Player mode ──

    private void handleCreateTable(com.google.gson.JsonObject json) {
        if (xmageSession == null) {
            sendError("Not connected to XMage server");
            return;
        }

        UUID roomId = xmageSession.getMainRoomId();
        String tableName = json.has("name") ? json.get("name").getAsString() : "Web Game";
        String gameType = json.has("gameType") ? json.get("gameType").getAsString() : "Two Player Duel";
        String deckType = json.has("deckType") ? json.get("deckType").getAsString() : "Constructed - Freeform";
        int winsNeeded = json.has("winsNeeded") ? json.get("winsNeeded").getAsInt() : 1;
        boolean vsAI = json.has("vsAI") && json.get("vsAI").getAsBoolean();

        MatchOptions options = new MatchOptions(tableName, gameType, false);
        options.setDeckType(deckType);
        options.setWinsNeeded(winsNeeded);
        // Password / matchmaking constraints — only set when the web UI sends them
        // (empty password = open table; blank password strings must NOT override).
        if (json.has("password") && !json.get("password").getAsString().isEmpty()) {
            options.setPassword(json.get("password").getAsString());
        } else {
            options.setPassword("");
        }
        // quitRatio: default 100 so conceded players can still join stock games;
        // allow the UI to tighten it for ranked/competitive tables.
        options.setQuitRatio(json.has("quitRatio") ? json.get("quitRatio").getAsInt() : 100);
        if (json.has("minimumRating")) {
            options.setMinimumRating(json.get("minimumRating").getAsInt());
        }
        if (json.has("edhPowerLevel")) {
            options.setEdhPowerLevel(json.get("edhPowerLevel").getAsInt());
        }

        // ── Extended match options from the web UI ──
        if (json.has("attackOption")) {
            try { options.setAttackOption(MultiplayerAttackOption.valueOf(json.get("attackOption").getAsString())); }
            catch (IllegalArgumentException e) { logger.warn("Invalid attackOption: " + json.get("attackOption")); }
        }
        if (json.has("range")) {
            try { options.setRange(RangeOfInfluence.valueOf(json.get("range").getAsString())); }
            catch (IllegalArgumentException e) { logger.warn("Invalid range: " + json.get("range")); }
        }
        if (json.has("skillLevel")) {
            try { options.setSkillLevel(SkillLevel.valueOf(json.get("skillLevel").getAsString())); }
            catch (IllegalArgumentException e) { logger.warn("Invalid skillLevel: " + json.get("skillLevel")); }
        }
        if (json.has("timeLimit")) {
            try { options.setMatchTimeLimit(MatchTimeLimit.valueOf(json.get("timeLimit").getAsString())); }
            catch (IllegalArgumentException e) { logger.warn("Invalid timeLimit: " + json.get("timeLimit")); }
        }
        if (json.has("bufferTime")) {
            try { options.setMatchBufferTime(MatchBufferTime.valueOf(json.get("bufferTime").getAsString())); }
            catch (IllegalArgumentException e) { logger.warn("Invalid bufferTime: " + json.get("bufferTime")); }
        }
        if (json.has("mulliganType")) {
            try { options.setMullgianType(MulliganType.valueOf(json.get("mulliganType").getAsString())); }
            catch (IllegalArgumentException e) { logger.warn("Invalid mulliganType: " + json.get("mulliganType")); }
        }
        if (json.has("freeMulligans"))
            options.setFreeMulligans(json.get("freeMulligans").getAsInt());
        if (json.has("customStartLifeEnabled") && json.get("customStartLifeEnabled").getAsBoolean()) {
            options.setCustomStartLifeEnabled(true);
            if (json.has("customStartLife")) options.setCustomStartLife(json.get("customStartLife").getAsInt());
        }
        if (json.has("customStartHandSizeEnabled") && json.get("customStartHandSizeEnabled").getAsBoolean()) {
            options.setCustomStartHandSizeEnabled(true);
            if (json.has("customStartHandSize")) options.setCustomStartHandSize(json.get("customStartHandSize").getAsInt());
        }
        if (json.has("planeChase")) options.setPlaneChase(json.get("planeChase").getAsBoolean());
        if (json.has("rollbackTurnsAllowed")) options.setRollbackTurnsAllowed(json.get("rollbackTurnsAllowed").getAsBoolean());
        if (json.has("spectatorsAllowed")) options.setSpectatorsAllowed(json.get("spectatorsAllowed").getAsBoolean());
        // Share LLM chat (reasoning, game plan — which quote hidden hands) with
        // opponents+spectators during live play. Default off; replays always carry it.
        if (json.has("llmChatPublic")) options.setLlmChatPublic(json.get("llmChatPublic").getAsBoolean());
        if (json.has("rated")) options.setRated(json.get("rated").getAsBoolean());

        // Collect per-seat decks and LLM configs for auto-join after table creation
        final List<SeatConfig> seatConfigs = new ArrayList<>();

        // New: parse playerConfigs array for explicit multi-player / mixed-type setups
        if (json.has("playerConfigs") && json.get("playerConfigs").isJsonArray()) {
            com.google.gson.JsonArray playerConfigs = json.getAsJsonArray("playerConfigs");
            // Cap seats — unbounded arrays are a memory/CPU burn vector.
            if (playerConfigs.size() > MAX_SEATS) {
                sendError("Too many player seats (max " + MAX_SEATS + ")");
                return;
            }
            for (int i = 0; i < playerConfigs.size(); i++) {
                com.google.gson.JsonObject pc = playerConfigs.get(i).getAsJsonObject();
                String typeStr = pc.has("type") ? pc.get("type").getAsString() : "HUMAN";
                PlayerType pt;
                try {
                    pt = PlayerType.valueOf(typeStr);
                } catch (IllegalArgumentException e) {
                    logger.warn("Unknown player type: " + typeStr + ", defaulting to HUMAN");
                    pt = PlayerType.HUMAN;
                }
                options.getPlayerTypes().add(pt);

                String name = pc.has("name") ? pc.get("name").getAsString() : (pt.isAI() ? "AI-" + i : proxyClient.getUsername());
                String deckText = pc.has("deck") ? pc.get("deck").getAsString() : "";
                DeckCardLists deck = null;
                if (!deckText.isEmpty()) {
                    try {
                        deck = DeckLoader.parseDeckList(deckText);
                    } catch (Throwable t) {
                        // AGENTS.md RPC rule 3: log at every failure point — a browser-only
                        // toast leaves no server-side trace and costs hours of debugging.
                        logger.error("Decklist parse failed for seat " + i + " (" + t.getClass().getSimpleName() + "): " + t.getMessage());
                        sendError("Invalid decklist for seat " + i + ": " + rawOrEmpty(t.getMessage()));
                        return;
                    }
                }
                if (pt == PlayerType.COMPUTER_P1) {
                    // P1 seat: default an absent deck to the deterministic training
                    // default (never an arbitrary list), and reject an explicitly
                    // supplied deck that is not one of the 55 training lists — the
                    // offering surface is not a substitute for the server gate.
                    requireServerP1("create_table seat '" + name + "'");
                    if (deck == null) {
                        deck = P1DeckOffering.defaultDeck();
                        logger.info("P1 seat " + i + " ('" + name + "') has no deck -> default '"
                                + mage.cards.decks.P1DeckPool.instance().defaultDeckName() + "'");
                    } else if (!P1DeckOffering.isTrainingDeck(deck)) {
                        logger.error("create_table rejected: P1 seat " + i + " ('" + name
                                + "') submitted a non-training deck");
                        sendError("P1 seat '" + name + "' may only use one of the 55 training decks "
                                + "(pick one from the offered list).");
                        return;
                    }
                }
                LLMPlayerConfig llmConfig = null;
                if (pt == PlayerType.COMPUTER_LLM && pc.has("llmConfig")) {
                    requireServerLlm("create_table seat '" + name + "'");
                    com.google.gson.JsonObject lcfg = pc.getAsJsonObject("llmConfig");
                    try {
                        llmConfig = parseLLMConfig(lcfg);
                    } catch (IllegalArgumentException e) {
                        // Seat-endpoint allowlist / URL validation — report the
                        // real reason to the browser, not the generic wrapper.
                        sendError("Invalid LLM config for seat '" + name + "': " + rawOrEmpty(e.getMessage()));
                        return;
                    }
                }
                seatConfigs.add(new SeatConfig(name, pt, deck, llmConfig));
            }
        } else {
            // Backward compatibility: old vsAI flag + single deck field
            options.getPlayerTypes().add(PlayerType.HUMAN);
            if (vsAI) {
                options.getPlayerTypes().add(PlayerType.COMPUTER_MAD);
            } else {
                options.getPlayerTypes().add(PlayerType.HUMAN);
            }
            String deckText = json.has("deck") ? json.get("deck").getAsString() : "";
            DeckCardLists deck = null;
            if (!deckText.isEmpty()) {
                try {
                    deck = DeckLoader.parseDeckList(deckText);
                } catch (Throwable t) {
                    // AGENTS.md RPC rule 3: log at every failure point.
                    logger.error("Decklist parse failed for legacy deck field (" + t.getClass().getSimpleName() + "): " + t.getMessage());
                    sendToBrowser("{\"type\":\"error\",\"message\":\"Invalid decklist: " + escapeJson(t.getMessage()) + "\"}");
                    return;
                }
            }
            seatConfigs.add(new SeatConfig(proxyClient.getUsername(), PlayerType.HUMAN, deck, null));
            if (vsAI) {
                seatConfigs.add(new SeatConfig("AI-Opponent", PlayerType.COMPUTER_MAD, null, null));
            } else {
                seatConfigs.add(new SeatConfig("Player2", PlayerType.HUMAN, null, null));
            }
        }

        // Build LLM configs list in seat order for MatchOptions (only COMPUTER_LLM seats)
        List<LLMPlayerConfig> llmConfigs = new ArrayList<>();
        for (SeatConfig sc : seatConfigs) {
            if (sc.playerType == PlayerType.COMPUTER_LLM && sc.llmConfig != null) {
                llmConfigs.add(sc.llmConfig);
            }
        }
        if (!llmConfigs.isEmpty()) {
            // Second gate — a COMPUTER_LLM seat without a config object would
            // otherwise slip past the per-seat check above.
            requireServerLlm("create_table with LLM seats");
            options.setLlmPlayerConfigs(llmConfigs);
        }
        // Setup-time thinking-shape warning: non-fatal, sent
        // BEFORE the table exists so the user can cancel and fix the seat.
        sendThinkingShapeWarnings("create_table", seatConfigs);

        // User-controlled table name — sanitize before it hits the log.
        logger.info("Creating table: name=" + sanitizeForLog(tableName) + " gameType=" + gameType + " deckType=" + deckType + " winsNeeded=" + winsNeeded + " seats=" + options.getPlayerTypes().size());
        logger.info("MatchOptions deckType before RPC: " + options.getDeckType());
        TableView table = xmageSession.createTable(roomId, options);
        if (table == null) {
            String error = xmageSession.getLastError();
            logger.error("Failed to create table: " + error);
            sendError("Failed to create table: " + rawOrEmpty(error != null ? error : "unknown"));
            return;
        }

        logger.info("Created table: " + table.getTableId());
        // Candidate 2: record ownership NOW so the very next table-list push (the browser
        // requests one as soon as it processes table_created) carries "owner": true without
        // paying a probe RPC — the create RPC itself is the proof that this account owns the
        // table. Same cache the get_tables/table-watch path reads (see resolveOwnedTableIds).
        TableOwnership.remember(proxyClient.getUsername(), table.getTableId(), true,
                System.currentTimeMillis());
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "table_created");
        msg.put("tableId", table.getTableId().toString());
        msg.put("tableName", table.getTableName());
        sendToBrowser(gsonToJson(msg));

        // Creator case: this session must see its own table's seats fill even when the tab
        // is hidden and its 2 s poll is throttled. The watch pushes a table_list on
        // every seat/state change.
        //
        // ARM IT INSIDE THE AUTO-JOIN TASK, not here. Arming before the creator's own seat-0
        // join made the very first tick race that join: `scheduleAtFixedRate(..., 0...)`
        // fires immediately, the table is not in getTables() yet, and the watch concluded the
        // table was gone and shut itself down — measured live 2026-09-17 in testing
        // (reproduced 4x), whose control showed the owner received ZERO pushes while a
        // joiner's identical watch on the same table pushed normally. The miss tolerance in
        // tableWatchTick() covers any remaining race; this removes its cause.
        submitBackground("create_table auto-join", () -> {
            try {
                startTableWatch(table.getTableId());
                boolean allJoined = true;
                // Count the seats this task DELIBERATELY leaves open. Any such seat means
                // the table is not full at create time, so a create-time startMatch
                // returning false is the expected outcome, not a failure to toast at the
                // browser (the table watch keeps pushing the seat state; re-attempting the
                // start once the last seat fills is deliberately not handled here).
                int seatsLeftOpen = 0;
                // Only the FIRST human seat is the creator's own; any further
                // HUMAN seat is meant for a different person to join manually. The
                // frontend never sends a seat name, so every human seat defaulted to
                // the creator's username and the second one was rejected server-side
                // ("user already joined tableId"), leaving the table unusable

                boolean creatorSeatTaken = false;
                for (int i = 0; i < seatConfigs.size(); i++) {
                    SeatConfig sc = seatConfigs.get(i);
                    DeckCardLists deck = sc.deck;
                    if (deck == null && sc.playerType.isAI()) {
                        deck = generateSimpleDeck();
                        logger.info("Generated simple deck for AI seat " + i + " (" + deck.getCards().size() + " cards)");
                    }
                    if (deck == null) {
                        // Human seat with no deck — skip auto-join, user will join manually
                        seatsLeftOpen++;
                        continue;
                    }
                    if (!sc.playerType.isAI()) {
                        if (creatorSeatTaken) {
                            logger.info("Seat " + i + " is a second HUMAN seat — leaving it open for"
                                    + " another player to join (only the creator's own seat is auto-joined)");
                            seatsLeftOpen++;
                            continue;
                        }
                        creatorSeatTaken = true;
                    }
                    // Wait briefly between joins to avoid races
                    if (i > 0) Thread.sleep(800);
                    // Timestamp BEFORE the RPC: TableController.joinTable reports a
                    // refusal as a plain boolean and delivers the REAL reason through a
                    // SHOW_USERMESSAGE callback ("Join Table", <text>) — the only carrier
                    // of it. xmageSession.getLastError() is set ONLY on connect failures,
                    // so reading it alone produced the reported bug's empty reason
                    // ("Seat 0 failed to join: "). Anything the server sends at or after
                    // this stamp belongs to THIS join (the same race the submit_deck
                    // refusal path closes).
                    long joinStartedAt = System.currentTimeMillis();
                    boolean joined = xmageSession.joinTable(roomId, table.getTableId(), sc.name, sc.playerType, sc.playerType.isAI() ? 0 : 1, deck, "");
                    logger.info("Seat " + i + " (" + sc.name + " type=" + sc.playerType + ") joinTable returned " + joined);
                    if (!joined) {
                        String reason = joinFailureReason(joinStartedAt);
                        logger.error("Seat " + i + " (" + sc.name + ") failed to join table "
                                + table.getTableId() + ": " + sanitizeForLog(reason));
                        sendError("Seat " + i + " failed to join: " + rawOrEmpty(reason));
                        allJoined = false;
                        break;
                    }
                    // This human seat was joined UNDER THIS ACCOUNT — remember the
                    // name so the reload/rejoin and submit_deck seat-identity checks still
                    // recognize it (SeatAliases; a typed seat name is not the username).
                    if (!sc.playerType.isAI()) {
                        SeatAliases.recordCreatorSeat(proxyClient.getUsername(), sc.name);
                    }
                }

                // Auto-start if all AI seats joined and at least one seat is AI
                if (allJoined) {
                    boolean hasAI = false;
                    for (SeatConfig sc : seatConfigs) {
                        if (sc.playerType.isAI()) {
                            hasAI = true;
                            break;
                        }
                    }
                    if (hasAI) {
                        Thread.sleep(500);
                        // The skip/attempt/failure-report decision lives in
                        // createTimeAutoStart() so its contract is unit-testable.
                        CreateAutoStartOutcome outcome = createTimeAutoStart(
                                seatsLeftOpen, seatConfigs.size(), roomId, table.getTableId());
                        if (outcome == CreateAutoStartOutcome.SKIPPED_SEATS_OPEN) {
                            // Candidate 1: remember that this table's start is still owed —
                            // the watch (armed above in this same task) re-attempts once the
                            // last seat fills and the table reports READY_TO_START. Exactly
                            // one re-attempt: the pending id is cleared when it fires, and
                            // dropped on manual start / teardown / state change (see
                            // tryPendingAutoStart).
                            pendingAutoStartTableId = table.getTableId();
                            logger.info("Auto-start pending for table " + table.getTableId()
                                    + " — the proxy will start the match when the last seat fills");
                        }
                        if (outcome == CreateAutoStartOutcome.STARTED) {
                            // If no human seat was joined (all AI), auto-watch the game
                            // so the web client user can spectate.
                            boolean hasHuman = false;
                            for (SeatConfig sc : seatConfigs) {
                                if (!sc.playerType.isAI()) {
                                    hasHuman = true;
                                    break;
                                }
                            }
                            if (!hasHuman) {
                                Thread.sleep(500);
                                logger.info("All seats are AI — auto-watching table " + table.getTableId());
                                boolean watched = xmageSession.watchTable(roomId, table.getTableId());
                                logger.info("watchTable returned " + watched);
                                // The WATCHGAME callback will fire with the gameId,
                                // which triggers watchGame(gameId) in WebProxyClient.
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                logger.error("Error in auto-join/start for table " + table.getTableId(), t);
                sendError("Table setup error: " + rawOrEmpty(t.getMessage()));
            }
        });
    }

    /**
     * Outcome of the create-time auto-start attempt. Exposed as an enum
     * because the caller's next action (auto-watch the game) depends on it, and
     * the "expected vs genuine failure" distinction is the feature's whole contract.
     */
    enum CreateAutoStartOutcome {
        /** A seat was intentionally left open — no attempt made, no error toast. */
        SKIPPED_SEATS_OPEN,
        /** startMatch returned true. */
        STARTED,
        /** All seats joined and startMatch still returned false — surfaced to the browser. */
        FAILED
    }

    /**
     * Run the create-time auto-start, distinguishing the EXPECTED not-full case
     * from a genuine failure.
     *
     * <p>When the auto-join task deliberately left a seat open (a human seat with
     * no deck, or a second HUMAN seat meant for another player), the table is
     * known not to be full at create time and the server-side
     * {@code changeTableStateToStarting} can only reject the start. Skipping the
     * attempt is therefore correct, and its failure must NOT reach the browser as
     * a "Failed to start match" toast (the empty-reason toast the owner saw in the
     * incident). The skip is logged, never silent — Fail Fast means surfacing the
     * decision, not hiding it.
     *
     * <p>When every seat was joined and the start STILL fails, that is a genuine
     * failure: it is logged at ERROR and reported to the browser exactly as before.
     *
     * @param seatsLeftOpen   seats the auto-join task deliberately skipped (>0 = table not full)
     * @param totalSeats      total seats in the table options (context for the log line)
     * @param roomId          main room id (never null here — the caller read it)
     * @param tableId         the just-created table
     * @return what happened; STARTED means the caller should consider auto-watching
     */
    CreateAutoStartOutcome createTimeAutoStart(int seatsLeftOpen, int totalSeats,
                                               UUID roomId, UUID tableId) {
        if (seatsLeftOpen > 0) {
            logger.info("Skipping create-time auto-start for table " + tableId
                    + " — " + seatsLeftOpen + " seat(s) left open on purpose for a human to join"
                    + " (" + totalSeats + " seats total); the match starts when a seated player"
                    + " uses Start Match");
            return CreateAutoStartOutcome.SKIPPED_SEATS_OPEN;
        }
        logger.info("Auto-starting match for table " + tableId);
        boolean started = xmageSession.startMatch(roomId, tableId);
        logger.info("startMatch returned " + started);
        if (started) {
            return CreateAutoStartOutcome.STARTED;
        }
        String e = xmageSession.getLastError();
        logger.error("Create-time auto-start failed for table " + tableId
                + " with all seats joined. lastError: " + e);
        sendError("Failed to start match: " + (e != null ? e : ""));
        return CreateAutoStartOutcome.FAILED;
    }

    private void handleStartMatch(com.google.gson.JsonObject json) {
        String tableIdStr = json.has("tableId") && !json.get("tableId").isJsonNull()
                ? json.get("tableId").getAsString() : null;
        logger.info("ENTERED handleStartMatch tableId=" + tableIdStr);

        if (xmageSession == null) {
            logger.warn("handleStartMatch: no XMage session (not connected) — dropping start_match for tableId=" + tableIdStr);
            sendError("Not connected to XMage server");
            return;
        }

        if (tableIdStr == null) {
            logger.warn("handleStartMatch: missing tableId in start_match message");
            sendError("Missing tableId");
            return;
        }
        UUID tableId;
        try {
            tableId = UUID.fromString(tableIdStr);
        } catch (IllegalArgumentException e) {
            logger.warn("handleStartMatch: invalid UUID in field 'tableId': " + tableIdStr);
            sendError("Invalid tableId (not a UUID): " + rawOrEmpty(tableIdStr));
            return;
        }

        UUID roomId = xmageSession.getMainRoomId();
        if (roomId == null) {
            // Fail Fast: without the main room id startMatch would be sent with a
            // null room and the server-side table lookup rejects it silently.
            logger.error("handleStartMatch: getMainRoomId() returned null (connected="
                    + xmageSession.isConnected() + ") — cannot start tableId=" + tableId);
            sendError("Cannot start match: main room unavailable (see proxy logs)");
            return;
        }

        logger.info("Starting match for table " + tableId);
        boolean success = xmageSession.startMatch(roomId, tableId);
        if (!success) {
            String error = xmageSession.getLastError();
            // The server-side matchStart RPC returns false for the two reject paths
            // (table not found / table state not READY_TO_START), both of which log
            // server-side only at debug. Record the client-side view here.
            logger.error("startMatch returned false. tableId=" + tableId + " roomId=" + roomId
                    + " connected=" + xmageSession.isConnected() + ". lastError: " + error);
            sendError("Failed to start match: " + rawOrEmpty(error));
            return;
        }

        logger.info("Match started for table " + tableId);
        // The table is no longer waiting for seats — the watch has nothing left to push.
        stopTableWatch("match started for table " + tableId);
        // Candidate 1: the owner beat the proxy to it — the owed auto-start is no longer owed.
        if (tableId.equals(pendingAutoStartTableId)) {
            pendingAutoStartTableId = null;
            logger.info("Manual start_match consumed the pending auto-start for table " + tableId);
        }
    }

    /**
     * Leave a waiting table (the desktop TableWaitingDialog's
     * "Cancel" → SessionHandler.leaveTable → Session.leaveTable → the engine's
     * roomLeaveTableOrTournament). PLAYER-ONLY (see PLAYER_ONLY_ACTIONS): a
     * spectator occupies no seat, so leaving is meaningless for it.
     *
     * <p>Browser sends: {"type": "leave_table", "tableId": "..."} — tableId
     * optional; when absent the proxy resolves it from the table it is currently
     * watching (startTableWatch recorded it), so the frontend can leave without
     * tracking the id itself.
     *
     * <p>Fail Fast / seat check: tableIds are enumerable via get_tables, so the
     * handler verifies this user actually occupies a seat before calling leave
     * (same guard submit_deck uses, {@link #isTableSeatOfUser}); a foreign tableId
     * is refused with an explicit error rather than a silent no-op.
     *
     * <p>On success the watch is stopped and a typed {@code table_left} frame is
     * sent so the frontend clears its waiting panel.
     */
    private void handleLeaveTable(com.google.gson.JsonObject json) {
        if (xmageSession == null) {
            sendError("Not connected to XMage server");
            return;
        }

        // tableId is OPTIONAL: fall back to the table currently being watched.
        String tableIdStr = json.has("tableId") && !json.get("tableId").isJsonNull()
                ? json.get("tableId").getAsString() : null;
        UUID tableId;
        if (tableIdStr == null || tableIdStr.isEmpty()) {
            UUID watched = watchedTableId;
            if (watched == null) {
                sendError("Cannot leave table: no table specified and none is being watched");
                return;
            }
            tableId = watched;
            logger.info("handleLeaveTable: no tableId supplied — using watched table " + tableId);
        } else {
            try {
                tableId = UUID.fromString(tableIdStr);
            } catch (IllegalArgumentException e) {
                logger.warn("handleLeaveTable: invalid UUID in field 'tableId': " + tableIdStr);
                sendError("Invalid tableId (not a UUID): " + rawOrEmpty(tableIdStr));
                return;
            }
        }

        // Seat check (mirrors submit_deck): only the user's OWN seat may be dropped.
        if (!isTableSeatOfUser(tableId)) {
            logger.warn("Rejected leave_table for table " + tableId + " — user is not seated there");
            sendError("Cannot leave the table — you are not seated there");
            return;
        }

        UUID roomId = xmageSession.getMainRoomId();
        if (roomId == null) {
            logger.error("handleLeaveTable: getMainRoomId() returned null (connected="
                    + xmageSession.isConnected() + ") — cannot leave tableId=" + tableId);
            sendError("Cannot leave table: main room unavailable (see proxy logs)");
            return;
        }

        logger.info("Leaving table " + tableId + " (room " + roomId + ")");
        boolean success = xmageSession.leaveTable(roomId, tableId);
        if (!success) {
            String error = xmageSession.getLastError();
            // The desktop TableWaitingDialog swallows this ("already started, so leave
            // no more possible") — but the web panel must tell the user WHY nothing
            // happened rather than sit on a dead Leave button.
            logger.error("leaveTable returned false. tableId=" + tableId + " connected="
                    + xmageSession.isConnected() + ". lastError: " + error);
            sendError("Failed to leave table: " + rawOrEmpty(error)
                    + " (the match may have already started)");
            return;
        }

        logger.info("Left table " + tableId);
        // The watched table is (about to be) gone — stop pushing its list.
        stopTableWatch("left table " + tableId);

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "table_left");
        msg.put("tableId", tableId.toString());
        sendToBrowser(gsonToJson(msg));
    }

    /**
     * Between-games deck submission (sideboarding / limited construct).
     * Browser sends: {"type": "submit_deck", "tableId": "...",
     *                 "deck": {"cards": [{"name","setCode","cardNumber","qty"}...],
     *                          "sideboard": [{"name","setCode","cardNumber","qty"}...]}}
     * Mirrors the stock client's SessionHandler.submitDeck → session.submitDeck(tableId, DeckCardLists).
     */
    private void handleSubmitDeck(com.google.gson.JsonObject json) {
        if (xmageSession == null) {
            sendError("Not connected to XMage server");
            return;
        }

        String tableIdStr = requireStringOrError(json, "tableId");
        if (tableIdStr == null) return;
        UUID tableId = parseUuidOrError(tableIdStr, "tableId");
        if (tableId == null) return;

        // Seat check (K3 follow-up): tableIds are enumerable via get_tables, so
        // without this guard any seated player could overwrite another table's
        // between-games deck by replaying submit_deck with a foreign tableId.
        if (!isTableSeatOfUser(tableId)) {
            logger.warn("Rejected submit_deck for table " + tableId + " — user is not seated there");
            sendError("Cannot submit a deck — you are not seated at that table");
            return;
        }

        com.google.gson.JsonObject deckJson = json.getAsJsonObject("deck");
        if (deckJson == null) {
            sendError("submit_deck: missing deck object");
            return;
        }

        DeckCardLists deckList = new DeckCardLists();
        try {
            java.util.List<DeckCardInfo> main = parseDeckCardArray(deckJson.get("cards"));
            java.util.List<DeckCardInfo> side = parseDeckCardArray(deckJson.get("sideboard"));
            deckList.getCards().addAll(main);
            deckList.getSideboard().addAll(side);
            // Fail Fast: JSON entries bypass DeckLoader.parseDeckList, so re-check the
            // [SET:NUM] codes here too — a code that names a different card must not be
            // submitted silently (same rule the engine enforces in Deck.createCard).
            DeckLoader.verifyDeckCardNames(deckList);
        } catch (RuntimeException e) {
            // Fail Fast: malformed deck JSON gets an explicit error, not a partial submit.
            sendError("submit_deck: " + e.getMessage());
            return;
        }

        if (deckList.getCards().isEmpty() && deckList.getSideboard().isEmpty()) {
            sendError("submit_deck: deck is empty");
            return;
        }

        logger.info("Submitting deck for table " + tableId
                + " (" + deckList.getCards().size() + " main / " + deckList.getSideboard().size() + " sb entries)");
        // Timestamp BEFORE the RPC: the server reports a refusal through a plain
        // boolean, and its accompanying SHOW_USERMESSAGE (the only carrier of the
        // real reason) is dispatched around the same moment. Anything recorded at
        // or after this stamp belongs to THIS submit.
        long submitStartedAt = System.currentTimeMillis();
        boolean success = xmageSession.submitDeck(tableId, deckList);
        if (!success) {
            String error = xmageSession.getLastError();
            // Prefer the server's own text: TableController now sends a
            // SHOW_USERMESSAGE with an actionable reason for every refusal
            // It hops
            // the remoting link asynchronously, hence the short bounded wait.
            String serverText = proxyClient != null
                    ? proxyClient.waitForServerMessageSince(submitStartedAt, SUBMIT_REFUSAL_REASON_WAIT_MS)
                    : "";
            String reason;
            if (!serverText.isEmpty()) {
                reason = serverText;
            } else if (error != null && !error.trim().isEmpty()) {
                reason = error;
            } else {
                // Fail-visible fallback. Never emit the bare "Failed to submit
                // deck: " (empty reason) again — an empty refusal tells the
                // player nothing and was the reported bug's user-facing half.
                reason = "The server refused the deck submission (no reason text arrived)."
                        + " A deck can only be submitted while sideboarding; if a game just"
                        + " ended, the server may still be setting the next one up — try"
                        + " again in a moment.";
            }
            logger.error("submit_deck refused for table " + tableId
                    + " (connected=" + xmageSession.isConnected() + "). lastError=" + error
                    + " serverText=" + (serverText.isEmpty() ? "<none>" : serverText));
            sendError("Failed to submit deck: " + rawOrEmpty(reason));
        }
    }

    /**
     * How long the submit_deck refusal path waits for the server's
     * SHOW_USERMESSAGE reason after the boolean refusal came back. The server
     * dispatches both in immediate succession; 1.5 s covers the callback hop
     * without stalling the browser visibly (and only ever on the refusal path).
     */
    private static final long SUBMIT_REFUSAL_REASON_WAIT_MS = 1500;

    /**
     * How long the join-refusal path waits for the server's SHOW_USERMESSAGE
     * reason after {@code joinTable} / {@code joinTournamentTable} returned false.
     * The server fires the user message immediately before its RPC reply returns,
     * but the two travel the remoting link on different paths — the same bounded
     * wait the submit_deck refusal path uses.
     */
    private static final long JOIN_REFUSAL_REASON_WAIT_MS = 1500;

    /**
     * The user-facing reason a seat join failed, VERBATIM from the server.
     *
     * <p>{@code TableController.joinTable} answers with a plain boolean and carries
     * the real rejection text on an async {@code SHOW_USERMESSAGE} callback
     * ("Join Table", &lt;text&gt;): user-already-seated, no free seat, wrong password,
     * invalid deck, quit-ratio/rating/power-level limits, and player-creation failure
     * (the LLM-seat case — the server-side seat dial failed, e.g. the
     * provider endpoint is unreachable). {@code xmageSession.getLastError()} is set
     * ONLY on connect failures and is therefore EMPTY on every join refusal — reading
     * it alone produced the reported bug's unfixable
     * {@code "Seat 0 failed to join: "} with an empty reason.
     *
     * <p>Precedence mirrors the submit_deck path: the server's own text first, then
     * lastError (the connect-failure flavor), then a fail-visible fallback that never
     * emits a bare trailing colon.
     */
    private String joinFailureReason(long joinStartedAt) {
        String serverText = proxyClient != null
                ? proxyClient.waitForServerMessageSince(joinStartedAt, JOIN_REFUSAL_REASON_WAIT_MS)
                : "";
        if (!serverText.trim().isEmpty()) {
            return serverText;
        }
        String lastError = xmageSession != null ? xmageSession.getLastError() : null;
        if (lastError != null && !lastError.trim().isEmpty()) {
            return lastError;
        }
        logger.error("Seat join refused but the server sent no reason text"
                + " (no SHOW_USERMESSAGE within " + JOIN_REFUSAL_REASON_WAIT_MS
                + " ms; connected=" + (xmageSession != null && xmageSession.isConnected()) + ")");
        return "The server refused the seat (no reason text arrived — check the proxy/server log).";
    }

    /** [{name, setCode, cardNumber, qty}] JSON array → DeckCardInfo list. */
    private static java.util.List<DeckCardInfo> parseDeckCardArray(com.google.gson.JsonElement el) {
        java.util.List<DeckCardInfo> out = new java.util.ArrayList<>();
        if (el == null || !el.isJsonArray()) {
            throw new IllegalArgumentException("expected a JSON array of cards");
        }
        for (com.google.gson.JsonElement item : el.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                throw new IllegalArgumentException("card entry is not an object: " + item);
            }
            com.google.gson.JsonObject o = item.getAsJsonObject();
            String name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : null;
            String setCode = o.has("setCode") && !o.get("setCode").isJsonNull() ? o.get("setCode").getAsString() : null;
            String cardNumber = o.has("cardNumber") && !o.get("cardNumber").isJsonNull() ? o.get("cardNumber").getAsString() : null;
            // "count" is the deck-editor wire convention (LegalityService.buildDeck).
            int count = o.has("count") && !o.get("count").isJsonNull() ? o.get("count").getAsInt() : 1;
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("card entry missing name: " + o);
            }
            // Mirror DeckLoader/LegalityService: set/number optional in input, empty string when absent.
            out.add(new DeckCardInfo(name, cardNumber != null ? cardNumber : "", setCode != null ? setCode : "", count));
        }
        return out;
    }

    // ── Tournament support ──

    /**
     * Send available tournament types and tournament-eligible game types to the browser.
     * Browser sends: {"type": "get_tournament_types"}
     * Browser receives: {"type": "tournament_types", "tournamentTypes": [...], "gameTypes": [...], "deckTypes": [...]}
     */
    private void handleGetTournamentTypes() {
        if (xmageSession == null) {
            sendError("Not connected to XMage server");
            return;
        }
        try {
            java.util.List<mage.view.TournamentTypeView> tTypes = xmageSession.getTournamentTypes();
            java.util.List<mage.view.GameTypeView> gTypes = xmageSession.getTournamentGameTypes();
            String[] deckTypes = xmageSession.getDeckTypes();

            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "tournament_types");

            // Serialize tournament types
            java.util.List<Map<String, Object>> tTypeList = new ArrayList<>();
            for (mage.view.TournamentTypeView tt : tTypes) {
                Map<String, Object> t = new HashMap<>();
                t.put("name", tt.getName());
                t.put("minPlayers", tt.getMinPlayers());
                t.put("maxPlayers", tt.getMaxPlayers());
                t.put("numBoosters", tt.getNumBoosters());
                t.put("draft", tt.isDraft());
                t.put("limited", tt.isLimited());
                t.put("cubeBooster", tt.isCubeBooster());
                t.put("elimination", tt.isElimination());
                t.put("random", tt.isRandom());
                t.put("reshuffled", tt.isReshuffled());
                t.put("richMan", tt.isRichMan());
                t.put("jumpstart", tt.isJumpstart());
                tTypeList.add(t);
            }
            msg.put("tournamentTypes", tTypeList);

            // Serialize game types
            java.util.List<String> gTypeNames = new ArrayList<>();
            for (mage.view.GameTypeView gt : gTypes) {
                gTypeNames.add(gt.getName());
            }
            msg.put("gameTypes", gTypeNames);

            // Deck types (convert String[] to List for JSON)
            msg.put("deckTypes", java.util.Arrays.asList(deckTypes));

            sendToBrowser(gsonToJson(msg));
        } catch (Exception e) {
            logger.error("Error getting tournament types", e);
            sendError("Failed to get tournament types: " + rawOrEmpty(e.getMessage()));
        }
    }

    /**
     * Create a tournament table and auto-join AI seats + human seat.
     * Browser sends: {"type": "create_tournament", "name": "...", "tournamentType": "...",
     *   "gameType": "...", "deckType": "...", "winsNeeded": 3, "numRounds": 3,
     *   "numPlayers": 4, "singleMultiplayerGame": false, "isSingleGameTourney": false,
     *   "allowSpectators": true, "rollbackTurnsAllowed": false, "rated": false,
     *   "timeLimit": "NONE", "bufferTime": "NONE", "skillLevel": "CASUAL",
     *   "quitRatio": 100, "minimumRating": 0,
     * "playerConfigs": [{type, deck, isYou, llmConfig?}...]}
     * Browser receives: {"type": "tournament_created", "tableId": "...", "tableName": "..."}
     */
    private void handleCreateTournament(com.google.gson.JsonObject json) {
        if (xmageSession == null) {
            sendError("Not connected to XMage server");
            return;
        }

        UUID roomId = xmageSession.getMainRoomId();
        String tableName = json.has("name") ? json.get("name").getAsString() : "Web Tournament";
        String tournamentTypeName = json.has("tournamentType") ? json.get("tournamentType").getAsString() : "";
        String gameType = json.has("gameType") ? json.get("gameType").getAsString() : "Two Player Duel";
        String deckType = json.has("deckType") ? json.get("deckType").getAsString() : "Constructed - Freeform";
        int winsNeeded = json.has("winsNeeded") ? json.get("winsNeeded").getAsInt() : 2;
        boolean isSingleMultiplayerGame = json.has("singleMultiplayerGame") && json.get("singleMultiplayerGame").getAsBoolean();

        // Build TournamentOptions
        mage.game.tournament.TournamentOptions tOptions = new mage.game.tournament.TournamentOptions(tableName, gameType, isSingleMultiplayerGame);
        tOptions.setTournamentType(tournamentTypeName);
        tOptions.setPassword("");
        tOptions.setWatchingAllowed(json.has("allowSpectators") && json.get("allowSpectators").getAsBoolean());
        tOptions.setQuitRatio(json.has("quitRatio") ? json.get("quitRatio").getAsInt() : 100);
        tOptions.setMinimumRating(json.has("minimumRating") ? json.get("minimumRating").getAsInt() : 0);

        // Number of rounds (Swiss only — elimination types don't use it)
        if (json.has("numRounds")) {
            tOptions.setNumberRounds(json.get("numRounds").getAsInt());
        }

        // Match options
        tOptions.getMatchOptions().setDeckType(deckType);
        tOptions.getMatchOptions().setWinsNeeded(winsNeeded);
        tOptions.getMatchOptions().setAttackOption(MultiplayerAttackOption.MULTIPLE);
        tOptions.getMatchOptions().setRange(RangeOfInfluence.ALL);
        if (json.has("rollbackTurnsAllowed")) tOptions.getMatchOptions().setRollbackTurnsAllowed(json.get("rollbackTurnsAllowed").getAsBoolean());
        if (json.has("rated")) tOptions.getMatchOptions().setRated(json.get("rated").getAsBoolean());
        if (json.has("skillLevel")) {
            try { tOptions.getMatchOptions().setSkillLevel(SkillLevel.valueOf(json.get("skillLevel").getAsString())); }
            catch (IllegalArgumentException e) { logger.warn("Invalid skillLevel: " + json.get("skillLevel")); }
        }
        if (json.has("timeLimit")) {
            try { tOptions.getMatchOptions().setMatchTimeLimit(MatchTimeLimit.valueOf(json.get("timeLimit").getAsString())); }
            catch (IllegalArgumentException e) { logger.warn("Invalid timeLimit: " + json.get("timeLimit")); }
        }
        if (json.has("bufferTime")) {
            try { tOptions.getMatchOptions().setMatchBufferTime(MatchBufferTime.valueOf(json.get("bufferTime").getAsString())); }
            catch (IllegalArgumentException e) { logger.warn("Invalid bufferTime: " + json.get("bufferTime")); }
        }

        // Parse player configs
        final List<SeatConfig> seatConfigs = new ArrayList<>();
        if (json.has("playerConfigs") && json.get("playerConfigs").isJsonArray()) {
            com.google.gson.JsonArray playerConfigs = json.getAsJsonArray("playerConfigs");
            for (int i = 0; i < playerConfigs.size(); i++) {
                com.google.gson.JsonObject pc = playerConfigs.get(i).getAsJsonObject();
                String typeStr = pc.has("type") ? pc.get("type").getAsString() : "HUMAN";
                PlayerType pt;
                try {
                    pt = PlayerType.valueOf(typeStr);
                } catch (IllegalArgumentException e) {
                    logger.warn("Unknown player type: " + typeStr + ", defaulting to HUMAN");
                    pt = PlayerType.HUMAN;
                }
                tOptions.getPlayerTypes().add(pt);

                String name = pc.has("name") ? pc.get("name").getAsString() : (pt.isAI() ? "AI-" + i : proxyClient.getUsername());
                String deckText = pc.has("deck") ? pc.get("deck").getAsString() : "";
                DeckCardLists deck = null;
                if (!deckText.isEmpty()) {
                    try {
                        deck = DeckLoader.parseDeckList(deckText);
                    } catch (Throwable t) {
                        // AGENTS.md RPC rule 3: log at every failure point — a browser-only
                        // toast leaves no server-side trace and costs hours of debugging.
                        logger.error("Decklist parse failed for seat " + i + " (" + t.getClass().getSimpleName() + "): " + t.getMessage());
                        sendError("Invalid decklist for seat " + i + ": " + rawOrEmpty(t.getMessage()));
                        return;
                    }
                }
                if (pt == PlayerType.COMPUTER_P1) {
                    // Same P1 restriction as the match create path (see above).
                    requireServerP1("create_tournament seat '" + name + "'");
                    if (deck == null) {
                        deck = P1DeckOffering.defaultDeck();
                        logger.info("P1 tournament seat " + i + " ('" + name + "') has no deck -> default '"
                                + mage.cards.decks.P1DeckPool.instance().defaultDeckName() + "'");
                    } else if (!P1DeckOffering.isTrainingDeck(deck)) {
                        logger.error("create_tournament rejected: P1 seat " + i + " ('" + name
                                + "') submitted a non-training deck");
                        sendError("P1 seat '" + name + "' may only use one of the 55 training decks "
                                + "(pick one from the offered list).");
                        return;
                    }
                }
                LLMPlayerConfig llmConfig = null;
                if (pt == PlayerType.COMPUTER_LLM && pc.has("llmConfig")) {
                    requireServerLlm("create_tournament seat '" + name + "'");
                    com.google.gson.JsonObject lcfg = pc.getAsJsonObject("llmConfig");
                    try {
                        llmConfig = parseLLMConfig(lcfg);
                    } catch (IllegalArgumentException e) {
                        // Seat-endpoint allowlist / URL validation — report the
                        // real reason to the browser, not the generic wrapper.
                        sendError("Invalid LLM config for seat '" + name + "': " + rawOrEmpty(e.getMessage()));
                        return;
                    }
                }
                seatConfigs.add(new SeatConfig(name, pt, deck, llmConfig));
            }
        }

        // Build LLM configs list for MatchOptions
        List<LLMPlayerConfig> llmConfigs = new ArrayList<>();
        for (SeatConfig sc : seatConfigs) {
            if (sc.playerType == PlayerType.COMPUTER_LLM && sc.llmConfig != null) {
                llmConfigs.add(sc.llmConfig);
            }
        }
        if (!llmConfigs.isEmpty()) {
            requireServerLlm("create_tournament with LLM seats");
            tOptions.getMatchOptions().setLlmPlayerConfigs(llmConfigs);
        }
        // Setup-time thinking-shape warning: non-fatal, sent
        // BEFORE the tournament exists so the user can cancel and fix a seat.
        sendThinkingShapeWarnings("create_tournament", seatConfigs);

        logger.info("Creating tournament: name=" + tableName + " type=" + tournamentTypeName + " players=" + tOptions.getPlayerTypes().size());
        TableView table = xmageSession.createTournamentTable(roomId, tOptions);
        if (table == null) {
            String error = xmageSession.getLastError();
            logger.error("Failed to create tournament table: " + error);
            sendError("Failed to create tournament: " + rawOrEmpty(error != null ? error : "unknown"));
            return;
        }

        logger.info("Created tournament table: " + table.getTableId());
        // Candidate 2: same create-time ownership record as handleCreateTable. NOTE: the
        // table-list resolver deliberately never FLAGS a tournament table (their start is a
        // different verb — start_tournament — over a different control), so this entry is
        // not read by today's table list; it is recorded so the create RPC's proof of
        // ownership is in the same cache if a tournament-side control ever consults it.
        TableOwnership.remember(proxyClient.getUsername(), table.getTableId(), true,
                System.currentTimeMillis());
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "tournament_created");
        msg.put("tableId", table.getTableId().toString());
        msg.put("tableName", table.getTableName());
        sendToBrowser(gsonToJson(msg));

        // Auto-join seats in background — same pattern as handleCreateTable
        submitBackground("create_tournament auto-join", () -> {
            try {
                boolean allJoined = true;
                for (int i = 0; i < seatConfigs.size(); i++) {
                    SeatConfig sc = seatConfigs.get(i);
                    DeckCardLists deck = sc.deck;
                    if (deck == null && sc.playerType.isAI()) {
                        deck = generateSimpleDeck();
                        logger.info("Generated simple deck for AI tournament seat " + i);
                    }
                    if (deck == null) {
                        // Human seat with no deck — skip, user joins manually or it's an AI-only tournament
                        continue;
                    }
                    if (i > 0) Thread.sleep(800);
                    // Same server-reason capture as the match path: joinTournamentTable
                    // reports a refusal as a plain boolean and its SHOW_USERMESSAGE
                    // ("Join Table", <text>) is the only carrier of the real reason —
                    // getLastError() stays empty, which produced an unfilled
                    // "Tournament seat N failed to join: " frame.
                    long joinStartedAt = System.currentTimeMillis();
                    boolean joined = xmageSession.joinTournamentTable(roomId, table.getTableId(), sc.name, sc.playerType, sc.playerType.isAI() ? 0 : 1, deck, "");
                    logger.info("Tournament seat " + i + " (" + sc.name + " type=" + sc.playerType + ") joinTable returned " + joined);
                    if (!joined) {
                        String reason = joinFailureReason(joinStartedAt);
                        logger.error("Tournament seat " + i + " (" + sc.name + ") failed to join table "
                                + table.getTableId() + ": " + sanitizeForLog(reason));
                        sendError("Tournament seat " + i + " failed to join: " + rawOrEmpty(reason));
                        allJoined = false;
                        break;
                    }
                    // Same as the match path — a human seat joined under this account
                    // must stay recognizable by its typed name after a reload (SeatAliases).
                    if (!sc.playerType.isAI()) {
                        SeatAliases.recordCreatorSeat(proxyClient.getUsername(), sc.name);
                    }
                }

                // Auto-start if all seats are AI (no human waiting)
                if (allJoined) {
                    boolean hasHuman = false;
                    for (SeatConfig sc : seatConfigs) {
                        if (sc.playerType == PlayerType.HUMAN && !sc.playerType.isAI()) {
                            hasHuman = true;
                            break;
                        }
                    }
                    if (!hasHuman) {
                        Thread.sleep(500);
                        logger.info("Auto-starting tournament for table " + table.getTableId());
                        boolean started = xmageSession.startTournament(roomId, table.getTableId());
                        logger.info("startTournament returned " + started);
                        if (!started) {
                            String e = xmageSession.getLastError();
                            sendError("Failed to start tournament: " + rawOrEmpty(e != null ? e : ""));
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                logger.error("Error in auto-join/start for tournament " + table.getTableId(), t);
                sendError("Tournament setup error: " + rawOrEmpty(t.getMessage()));
            }
        });
    }

    /**
     * Join an existing tournament table as a human player.
     * Browser sends: {"type": "join_tournament", "tableId": "...", "deck": "..."}
     * Browser receives: {"type": "tournament_joined", "tableId": "..."}
     */
    private void handleJoinTournament(com.google.gson.JsonObject json) {
        if (xmageSession == null) {
            sendError("Not connected to XMage server");
            return;
        }
        String tableIdStr = requireStringOrError(json, "tableId");
        if (tableIdStr == null) return;
        UUID tableId = parseUuidOrError(tableIdStr, "tableId");
        if (tableId == null) return;
        String deckList = json.has("deck") && !json.get("deck").isJsonNull()
                ? json.get("deck").getAsString() : "";
        if (deckList.length() > MAX_DECKLIST_LENGTH) {
            sendError("Decklist too long (max " + MAX_DECKLIST_LENGTH + " chars)");
            return;
        }

        DeckCardLists deck;
        try {
            deck = DeckLoader.parseDeckList(deckList);
        } catch (Throwable t) {
            sendError("Invalid decklist: " + rawOrEmpty(t.getMessage()));
            return;
        }

        UUID roomId = xmageSession.getMainRoomId();
        String playerName = proxyClient.getUsername();
        logger.info("Joining tournament table " + tableId + " as " + playerName);
        boolean success = xmageSession.joinTournamentTable(roomId, tableId, playerName, PlayerType.HUMAN, 1, deck, "");
        if (!success) {
            String error = xmageSession.getLastError();
            sendError("Failed to join tournament: " + rawOrEmpty(error != null ? error : "unknown"));
            return;
        }
        spectator = false; // joining = seated player
        logger.info("Successfully joined tournament " + tableId);
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "tournament_joined");
        msg.put("tableId", tableIdStr);
        sendToBrowser(gsonToJson(msg));
    }

    /**
     * Start a tournament (when all players have joined).
     * Browser sends: {"type": "start_tournament", "tableId": "..."}
     */
    private void handleStartTournament(com.google.gson.JsonObject json) {
        if (xmageSession == null) {
            sendError("Not connected to XMage server");
            return;
        }
        String tableIdStr = requireStringOrError(json, "tableId");
        if (tableIdStr == null) return;
        UUID tableId = parseUuidOrError(tableIdStr, "tableId");
        if (tableId == null) return;
        UUID roomId = xmageSession.getMainRoomId();
        logger.info("Starting tournament for table " + tableId);
        boolean success = xmageSession.startTournament(roomId, tableId);
        if (!success) {
            String error = xmageSession.getLastError();
            sendError("Failed to start tournament: " + rawOrEmpty(error));
            return;
        }
        logger.info("Tournament started for table " + tableId);
    }

    /**
     * Generate a simple 40-card Freeform deck: 10 creatures, 10 spells, 20 basic lands.
     * Uses hardcoded card names that exist in XMage's database.
     */
    private static DeckCardLists generateSimpleDeck() {
        DeckCardLists deck = new DeckCardLists();
        // Simple creatures — all classic cards that definitely exist
        String[] creatures = {"Grizzly Bears", "Hill Giant", "Air Elemental", "Earth Elemental",
            "Savannah Lions", "Merfolk of the Pearl Trident", "Scathe Zombies", "Hurloon Minotaur",
            "Goblin Balloon Brigade", "War Mammoth"};
        for (String c : creatures) {
            deck.getCards().add(new DeckCardInfo(c, null, null));
        }
        // Simple spells
        String[] spells = {"Lightning Bolt", "Giant Growth", "Counterspell", "Dark Ritual",
            "Healing Salve", "Terror", "Unsummon", "Disenchant",
            "Fireball", "Stone Rain"};
        for (String s : spells) {
            deck.getCards().add(new DeckCardInfo(s, null, null));
        }
        // Basic lands — 4 each = 20 total
        for (int i = 0; i < 4; i++) deck.getCards().add(new DeckCardInfo("Mountain", null, null));
        for (int i = 0; i < 4; i++) deck.getCards().add(new DeckCardInfo("Forest", null, null));
        for (int i = 0; i < 4; i++) deck.getCards().add(new DeckCardInfo("Island", null, null));
        for (int i = 0; i < 4; i++) deck.getCards().add(new DeckCardInfo("Swamp", null, null));
        for (int i = 0; i < 4; i++) deck.getCards().add(new DeckCardInfo("Plains", null, null));
        return deck;
    }

    private void handleSendChat(com.google.gson.JsonObject json) {
        if (xmageSession == null) {
            sendError("Not connected");
            return;
        }
        String message = json.has("message") && !json.get("message").isJsonNull()
                ? json.get("message").getAsString() : "";
        if (message.isEmpty()) return;
        // Bounded chat input.
        if (message.length() > MAX_CHAT_LENGTH) {
            sendError("Chat message too long (max " + MAX_CHAT_LENGTH + " chars)");
            return;
        }

        // During a game, route chat to the game chat channel so other players and
        // spectators in the match see it. Otherwise fall back to the main room chat.
        // Mirrors the desktop client: GamePanel connects gameChatPanel to getGameChatId.
        UUID currentGameId = proxyClient != null ? proxyClient.getCurrentGameId() : null;
        if (currentGameId != null) {
            java.util.Optional<UUID> gameChatOpt = xmageSession.getGameChatId(currentGameId);
            if (gameChatOpt.isPresent()) {
                boolean success = xmageSession.sendChatMessage(gameChatOpt.get(), message);
                logger.info("Sent game chat message (gameId=" + currentGameId + "): " + sanitizeForLog(message) + " (success=" + success + ")");
                return;
            }
            logger.warn("No game chat ID for gameId=" + currentGameId + ", falling back to room chat");
        }

        UUID roomId = xmageSession.getMainRoomId();
        java.util.Optional<UUID> chatIdOpt = xmageSession.getRoomChatId(roomId);
        if (chatIdOpt.isPresent()) {
            boolean success = xmageSession.sendChatMessage(chatIdOpt.get(), message);
            logger.info("Sent room chat message: " + sanitizeForLog(message) + " (success=" + success + ")");
        } else {
            logger.warn("No room chat ID available for room " + roomId);
        }
    }

    private void handleJoinTable(com.google.gson.JsonObject json) {
        if (xmageSession == null) {
            sendError("Not connected to XMage server");
            return;
        }

        String tableIdStr = requireStringOrError(json, "tableId");
        if (tableIdStr == null) return;
        UUID tableId = parseUuidOrError(tableIdStr, "tableId");
        if (tableId == null) return;
        String deckList = json.has("deck") && !json.get("deck").isJsonNull()
                ? json.get("deck").getAsString() : "";
        // Bounded decklist input.
        if (deckList.length() > MAX_DECKLIST_LENGTH) {
            sendError("Decklist too long (max " + MAX_DECKLIST_LENGTH + " chars)");
            return;
        }

        logger.info("Join table request: tableId=" + tableIdStr + ", deckList length=" + deckList.length());

        // Parse the pasted decklist
        DeckCardLists deck;
        try {
            deck = DeckLoader.parseDeckList(deckList);
            logger.info("Deck parsed: " + deck.getCards().size() + " main, " + deck.getSideboard().size() + " sideboard");
            StringBuilder cardNames = new StringBuilder();
            for (DeckCardInfo card : deck.getCards()) {
                cardNames.append(card.getCardName()).append(" x").append(card.getAmount()).append(" [").append(card.getSetCode()).append(":").append(card.getCardNumber()).append("], ");
            }
            logger.info("Main deck cards: " + cardNames);
        } catch (IllegalArgumentException e) {
            logger.error("Deck parse error: " + e.getMessage(), e);
            sendError("Invalid decklist: " + rawOrEmpty(e.getMessage()));
            return;
        } catch (Throwable t) {
            logger.error("Unexpected deck parse error", t);
            sendError("Deck parse error: " + rawOrEmpty(t.getMessage()));
            return;
        }

        UUID roomId = xmageSession.getMainRoomId();
        String playerName = proxyClient.getUsername();

        logger.info("Calling joinTable: roomId=" + roomId + ", tableId=" + tableId + ", player=" + playerName);
        boolean success = xmageSession.joinTable(roomId, tableId, playerName, PlayerType.HUMAN, 1, deck, "");
        if (!success) {
            String error = xmageSession.getLastError();
            logger.error("joinTable returned false. lastError: " + error);
            if (error == null || error.isEmpty()) {
                error = "Unknown error (server did not provide a reason — check XMage server logs)";
            }
            sendError("Failed to join table: " + rawOrEmpty(error));
            return;
        }

        spectator = false; // joining = seated player
        logger.info("Successfully joined table " + tableId);

        // A seated player's lobby view must learn that the seats filled even in a hidden tab
        // (no browser timer fires there) — see startTableWatch.
        startTableWatch(tableId);

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "table_joined");
        msg.put("tableId", tableIdStr);
        sendToBrowser(gsonToJson(msg));
    }

    private void handlePassPriority() {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }

        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }

        // If there's a pending dialog, respond to it rather than sending a pass action
        ClientCallbackMethod pendingType = proxyClient.getPendingActionType();
        if (pendingType != null) {
            switch (pendingType) {
                case GAME_PLAY_MANA:
                case GAME_PLAY_XMANA:
                    // Mana payment is required — do NOT cancel via pass priority.
                    // The only way to cancel is the "Cancel Spell" button in the
                    // PlayManaPrompt, which sends boolean_choice(false). If we cancel
                    // here, the spell rollback leaves a timing gap where the user can
                    // pass priority and let the unpaid spell resolve from the stack.
                    logger.warn("Pass priority ignored during " + pendingType + " — use Cancel Spell button");
                    return;
                case GAME_SELECT:
                case GAME_ASK:
                    // Send sendPlayerBoolean(false) to decline/pass the current step.
                    // Don't send PASS_PRIORITY_CANCEL_ALL_ACTIONS here — it can
                    // interfere with game state bookmarks if a spell is being cast.
                    // Skip flags are cleared by the skip button's own cancel handler.
                    xmageSession.sendPlayerBoolean(gameId, false);
                    break;
                case GAME_TARGET:
                    // For optional targets, false cancels.
                    // For required targets, pass priority does nothing — the player
                    // must explicitly choose a target. Send an error so the frontend
                    // knows the pass was rejected (the prompt stays visible).
                    if (proxyClient.isPendingTargetRequired()) {
                        logger.warn("Pass priority ignored during required GAME_TARGET — player must choose a target");
                        sendError("You must choose a target — this action can't be skipped");
                        return;
                    } else {
                        xmageSession.sendPlayerBoolean(gameId, false);
                    }
                    break;
                case GAME_CHOOSE_ABILITY:
                    // During mana payment, the server may ask the player to choose
                    // which mana ability to activate (e.g. a land with multiple mana
                    // abilities). Canceling the ability choice returns to the mana
                    // payment prompt — do NOT clearPendingAction, because the server
                    // is still in the playMana loop and will re-prompt.
                    xmageSession.sendPlayerString(gameId, null);
                    // Don't clear pending action — server will re-prompt with GAME_PLAY_MANA
                    return;
                case GAME_CHOOSE_CHOICE:
                    xmageSession.sendPlayerString(gameId, null);
                    break;
                case GAME_GET_AMOUNT:
                    // Send 0 to cancel
                    xmageSession.sendPlayerInteger(gameId, 0);
                    break;
                default:
                    // Unknown dialog — try boolean false
                    xmageSession.sendPlayerBoolean(gameId, false);
                    break;
            }
        } else {
            // No pending dialog — the player wants to pass priority.
            // Send sendPlayerBoolean(false) for a single-step pass, matching the
            // desktop client's feedback panel "Cancel" button behavior. This
            // passes only the current step/phase. The "Skip Turn" button (F4) is
            // a separate action (handleSkipPriority) that sends
            // PASS_PRIORITY_UNTIL_NEXT_TURN. Previously this sent
            // PASS_PRIORITY_UNTIL_NEXT_TURN which skipped the entire rest of
            // the turn on a single "Pass Priority" click — far too aggressive.
            // If the server isn't waiting for a boolean response, this is a
            // safe no-op (the response is stored but ignored).
            logger.info("Pass priority with no pending dialog — single-step pass");
            xmageSession.sendPlayerBoolean(gameId, false);
        }
        proxyClient.clearPendingAction();
    }

    private void handleSkipPriority(com.google.gson.JsonObject json) {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }
        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }

        // Do NOT send skip actions while a modal dialog is pending.
        // The Java client checks isUserImputActive() (modal dialogs + chat focus).
        // GAME_SELECT is NOT a modal dialog — skip is valid there (it's just
        // "pass priority until X" during the normal play-or-pass prompt).
        ClientCallbackMethod pendingType = proxyClient.getPendingActionType();
        if (pendingType != null && pendingType != ClientCallbackMethod.GAME_SELECT) {
            logger.warn("Skip ignored during pending dialog " + pendingType + " — respond to the dialog first");
            return;
        }

        String skipType = json.has("skip") ? json.get("skip").getAsString() : "next_turn";
        PlayerAction action;
        switch (skipType) {
            case "end_of_turn":
                action = PlayerAction.PASS_PRIORITY_UNTIL_TURN_END_STEP;
                break;
            case "next_main":
                action = PlayerAction.PASS_PRIORITY_UNTIL_NEXT_MAIN_PHASE;
                break;
            case "next_turn":
                action = PlayerAction.PASS_PRIORITY_UNTIL_NEXT_TURN;
                break;
            case "next_turn_skip_stack":
                action = PlayerAction.PASS_PRIORITY_UNTIL_NEXT_TURN_SKIP_STACK;
                break;
            case "my_next_turn":
                action = PlayerAction.PASS_PRIORITY_UNTIL_MY_NEXT_TURN;
                break;
            case "stack_resolved":
                action = PlayerAction.PASS_PRIORITY_UNTIL_STACK_RESOLVED;
                break;
            case "end_step_before_my_turn":
                action = PlayerAction.PASS_PRIORITY_UNTIL_END_STEP_BEFORE_MY_NEXT_TURN;
                break;
            default:
                action = PlayerAction.PASS_PRIORITY_UNTIL_NEXT_TURN;
        }
        logger.info("Skip priority: " + skipType + " → " + action);
        xmageSession.sendPlayerAction(action, gameId, null);
        proxyClient.clearPendingAction();
    }

    /**
     * Cancel any active skip (PASS_PRIORITY_UNTIL_*).
     * Sends PASS_PRIORITY_CANCEL_ALL_ACTIONS which stops the auto-pass.
     */
    private void handleCancelSkip() {
        if (xmageSession == null || proxyClient == null) {
            return;
        }
        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) return;
        logger.info("Cancel skip");
        xmageSession.sendPlayerAction(PlayerAction.PASS_PRIORITY_CANCEL_ALL_ACTIONS, gameId, null);
    }

    private void handleUndo() {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }
        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }
        logger.info("Undo");
        xmageSession.sendPlayerAction(PlayerAction.UNDO, gameId, null);
    }

    private void handleSelectChoice(com.google.gson.JsonObject json) {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }

        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }

        int choiceIndex = json.get("index").getAsInt();
        List<Object> backings = proxyClient.getPendingChoiceBackings();
        if (backings == null) {
            // Stale select_choice: the first click was already served and cleared
            // the pending list. This is the normal double-click path — not an
            // error the user needs to see. Log and no-op.
            logger.warn("Ignoring stale select_choice index=" + choiceIndex
                    + " (no pending choice list — original click already submitted)");
            return;
        }
        // Bounds check against the live pending list. A wrong-index click is a
        // real error (the list exists; the choice made no sense).
        if (choiceIndex < 0 || choiceIndex >= backings.size()) {
            sendError("Invalid choice index: " + choiceIndex);
            return;
        }

        Object chosen = backings.get(choiceIndex);
        logger.info("Selecting choice " + choiceIndex + ": " + chosen);

        if (chosen instanceof UUID) {
            UUID uuid = (UUID) chosen;
            xmageSession.sendPlayerUUID(gameId, uuid);
        } else if (chosen instanceof String) {
            String str = (String) chosen;
            xmageSession.sendPlayerString(gameId, str);
        } else {
            sendError("Unknown choice backing type: " + chosen.getClass());
            return;
        }

        proxyClient.clearPendingAction();
    }

    /**
     * Attempt to play/cast a card by UUID without a matching GAME_SELECT choice.
     *
     * The desktop client sends sendPlayerUUID(cardId) on ANY hand-card click
     * (DefaultActionCallback.mouseClicked); the server-side HumanPlayer.priority()
     * then looks up useable abilities for that object and starts the cast/activate
     * flow (choose spell ability → mana payment → targets). If the card has no
     * castable ability, the server simply re-prompts priority (a new GAME_SELECT
     * arrives and nothing happened) — that's the graceful rejection path.
     *
     * The web client uses this for hand cards that are NOT in getCanPlayObjects()
     * (e.g. a spell the player can't currently afford): clicking it now attempts
     * the cast instead of silently no-oping.
     *
     * Valid only while a GAME_SELECT prompt is pending — that's the only state
     * where the server is waiting for a priority response from this player.
     */
    private void handlePlayCard(com.google.gson.JsonObject json) {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }
        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }
        if (!json.has("cardId") || !json.get("cardId").isJsonPrimitive()) {
            sendError("Missing cardId");
            return;
        }
        UUID cardId;
        try {
            cardId = UUID.fromString(json.get("cardId").getAsString());
        } catch (IllegalArgumentException e) {
            sendError("Invalid cardId (not a UUID)");
            return;
        }

        ClientCallbackMethod pendingType = proxyClient.getPendingActionType();
        if (pendingType != ClientCallbackMethod.GAME_SELECT) {
            // Fail Fast: sending a UUID during another pending dialog (e.g. mana
            // payment, target selection) would be interpreted as that dialog's
            // answer and corrupt the flow. Reject loudly, don't guess.
            sendError("Cannot play a card now — a '" + pendingType + "' dialog is pending");
            return;
        }

        logger.info("Play card attempt: " + cardId);
        proxyClient.recordPlayCardAttempt(cardId);
        xmageSession.sendPlayerUUID(gameId, cardId);
        // NOTE: intentionally NOT clearPendingAction() — the server either starts
        // the cast flow (next prompt replaces this one) or re-sends GAME_SELECT
        // (nothing was played). Clearing here would break a follow-up select_choice
        // that races the server's re-prompt.
    }

    /**
     * Handle "All Attack" and other special button responses.
     * The server expects sendPlayerString(gameId, "special") to trigger
     * the special button action (e.g. declaring all creatures as attackers).
     */
    private void handleSelectSpecial(com.google.gson.JsonObject json) {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }
        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }
        String specialValue = json.has("value") ? json.get("value").getAsString() : "special";
        logger.info("Select special: " + specialValue);
        xmageSession.sendPlayerString(gameId, specialValue);
        proxyClient.clearPendingAction();
    }

    private void handleBooleanChoice(com.google.gson.JsonObject json) {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }

        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }

        // Fail Fast on a malformed message: json.get("value") is null when the field is
        // absent (or misspelled — "choice" instead of "value" is the easy mistake), and
        // getAsBoolean() then NPEs with no clue who sent what. Report it instead: this
        // was hit live 2026-09-21 by the token-art E2E probe, which sent
        // {"type":"boolean_choice","choice":false} and killed the whole play loop with
        // "Cannot invoke JsonElement.getAsBoolean() because the return value is null".
        if (!json.has("value") || json.get("value").isJsonNull()) {
            sendError("boolean_choice requires a boolean 'value' field");
            return;
        }
        boolean choice = json.get("value").getAsBoolean();
        ClientCallbackMethod pendingType = proxyClient.getPendingActionType();
        logger.info("Boolean choice: " + choice + " (pending: " + pendingType + ")");

        // Stale-click guard: the frontend keeps buttons live after answering, so a
        // second click arrives after the server already consumed the choice
        // (pendingType == null). Forwarding that boolean would land on a *later*
        // prompt — misdelivered answers with no user awareness. The
        // user's intent was plainly already served; drop with a log line instead.
        if (pendingType == null) {
            logger.warn("Ignoring stale boolean_choice (no pending action) — the original answer was already submitted");
            return;
        }

        // During GAME_PLAY_MANA, sendPlayerBoolean cancels the payment regardless
        // of true/false. The Java client has the same behavior — its FeedbackPanel
        // "OK" button sends sendPlayerBoolean(false) during mana payment to cancel.
        // We allow both true and false here for consistency with the Java client.

        xmageSession.sendPlayerBoolean(gameId, choice);
        proxyClient.clearPendingAction();
    }

    private void handleAmountChoice(com.google.gson.JsonObject json) {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }

        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }

        int amount;
        try {
            amount = json.get("value").getAsInt();
        } catch (RuntimeException e) {
            sendError("Missing or invalid amount value");
            return;
        }
        // Sane range — protects against absurd-value probes.
        if (amount < 0 || amount > 1_000_000_000) {
            sendError("Amount out of range (0..1000000000)");
            return;
        }
        logger.info("Amount choice: " + amount);

        xmageSession.sendPlayerInteger(gameId, amount);
        proxyClient.clearPendingAction();
    }

    private void handleMultiAmountChoice(com.google.gson.JsonObject json) {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }
        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }

        // Send amounts as space-separated string (XMage expects this format —
        // Java client's PickMultiNumberDialog.getMultiAmount uses Collectors.joining(" "))
        if (!json.has("amounts") || !json.get("amounts").isJsonArray()) {
            sendError("Missing amounts array");
            return;
        }
        com.google.gson.JsonArray amounts = json.getAsJsonArray("amounts");
        // Bounded multi-amount arrays.
        if (amounts.size() > MAX_SEATS) {
            sendError("Too many amount entries (max " + MAX_SEATS + ")");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < amounts.size(); i++) {
            if (i > 0) sb.append(" ");
            try {
                int v = amounts.get(i).getAsInt();
                if (v < 0 || v > 1_000_000_000) {
                    sendError("Amount entry out of range (0..1000000000)");
                    return;
                }
                sb.append(v);
            } catch (RuntimeException e) {
                sendError("Invalid amount entry at index " + i);
                return;
            }
        }
        logger.info("Multi-amount choice: " + sb.toString());
        xmageSession.sendPlayerString(gameId, sb.toString());
        proxyClient.clearPendingAction();
    }

    /**
     * GAME_CHOOSE_PILE response: the player picks one of two piles (e.g. Fact or
     * Fiction). The backend (handleGameChoosePile) sets pendingChoiceBackings = null,
     * so this MUST NOT go through handleSelectChoice (which would NPE on the null
     * backings list). The server expects sendPlayerInteger(gameId, 0|1).
     */
    private void handleChoosePile(com.google.gson.JsonObject json) {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }
        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }
        if (!json.has("pile")) {
            sendError("Missing pile index");
            return;
        }
        int pile = json.get("pile").getAsInt();
        if (pile != 0 && pile != 1) {
            sendError("Pile index must be 0 or 1, got " + pile);
            return;
        }
        // Java client sends sendPlayerBoolean: true = pile1, false = pile2.
        // The server checks response.getBoolean() first, so we must send a boolean.
        logger.info("Choose pile: " + pile + " (sending boolean " + (pile == 0) + ")");
        xmageSession.sendPlayerBoolean(gameId, pile == 0);
        proxyClient.clearPendingAction();
    }

    private void handleSelectTarget(com.google.gson.JsonObject json) {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }

        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }

        String targetIdStr = requireStringOrError(json, "targetId");
        if (targetIdStr == null) return;
        UUID targetId = parseUuidOrError(targetIdStr, "targetId");
        if (targetId == null) return;

        // Stale-click guard — same semantics as handleBooleanChoice. The frontend
        // leaves resolved prompts live, so a duplicate/late click arrives after the
        // server already consumed the answer (pendingType == null). Forwarding it
        // would land on a *later* prompt — misdelivered answers with no user
        // awareness (9 sends in one burst). The user's intent was already served;
        // log and drop.
        ClientCallbackMethod pendingType = proxyClient.getPendingActionType();
        logger.info("Selecting target: " + targetIdStr + " (pending: " + pendingType + ")");
        if (pendingType == null) {
            logger.warn("Ignoring stale select_target " + targetIdStr
                    + " (no pending action) — the original answer was already submitted");
            return;
        }

        xmageSession.sendPlayerUUID(gameId, targetId);
        proxyClient.clearPendingAction();
    }

    /**
     * Manual mana tapping: the player clicks an untapped land on the battlefield
     * to activate its mana ability. This sends sendPlayerUUID(gameId, landId) which
     * the server interprets as activating the land's mana ability during GAME_PLAY_MANA.
     */
    private void handleTapMana(com.google.gson.JsonObject json) {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }

        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }

        // Gatekeep: only accept tap_mana while the server is actually asking the
        // player to pay mana. A spoofed/out-of-sequence message outside
        // GAME_PLAY_MANA / GAME_PLAY_XMANA would otherwise be forwarded straight
        // to the server (XMage would likely ignore it, but the proxy should not
        // be a dumb pipe — Fail Fast with an error to the browser).
        ClientCallbackMethod pendingType = proxyClient.getPendingActionType();
        if (pendingType != ClientCallbackMethod.GAME_PLAY_MANA
                && pendingType != ClientCallbackMethod.GAME_PLAY_XMANA) {
            sendError("Tap mana ignored — not in a mana payment prompt (pending: "
                    + (pendingType != null ? pendingType : "none") + ")");
            logger.warn("tap_mana rejected outside GAME_PLAY_MANA/XMANA (pending: " + pendingType + ")");
            return;
        }

        if (!json.has("landId")) {
            sendError("Missing landId");
            return;
        }

        String landIdStr = requireStringOrError(json, "landId");
        if (landIdStr == null) return;
        UUID landId = parseUuidOrError(landIdStr, "landId");
        if (landId == null) return;

        logger.info("Tapping land for mana: " + landIdStr);
        // sendPlayerUUID during GAME_PLAY_MANA activates the permanent's mana ability
        xmageSession.sendPlayerUUID(gameId, landId);
        // Don't clear pending action — the player may need to tap more lands
    }

    /**
     * Special mana actions during GAME_PLAY_MANA — e.g. fetch lands (Misty Rainforest),
     * convoke, delve, or other alternate mana payment methods.
     * Sends sendPlayerString(gameId, "special") which triggers activateSpecialAction
     * on the server, showing the player their available special actions.
     */
    private void handleSpecialMana() {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }
        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }
        ClientCallbackMethod pendingType = proxyClient.getPendingActionType();
        if (pendingType != ClientCallbackMethod.GAME_PLAY_MANA
                && pendingType != ClientCallbackMethod.GAME_PLAY_XMANA) {
            sendError("Special mana ignored — not in a mana payment prompt (pending: "
                    + (pendingType != null ? pendingType : "none") + ")");
            return;
        }
        logger.info("Special mana action requested");
        xmageSession.sendPlayerString(gameId, "special");
        // Don't clear pending action — server will re-prompt after special action
    }

    /**
     * Use mana from the mana pool during GAME_PLAY_MANA.
     * Sends sendPlayerManaType(gameId, playerId, manaType) which tells the server
     * to use one mana of the specified type from the player's pool.
     */
    private void handleUseMana(com.google.gson.JsonObject json) {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not connected");
            return;
        }
        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }

        // Gatekeep: same as handleTapMana — only meaningful during mana payment.
        ClientCallbackMethod pendingType = proxyClient.getPendingActionType();
        if (pendingType != ClientCallbackMethod.GAME_PLAY_MANA
                && pendingType != ClientCallbackMethod.GAME_PLAY_XMANA) {
            sendError("Use mana ignored — not in a mana payment prompt (pending: "
                    + (pendingType != null ? pendingType : "none") + ")");
            logger.warn("use_mana rejected outside GAME_PLAY_MANA/XMANA (pending: " + pendingType + ")");
            return;
        }

        if (!json.has("color")) {
            sendError("Missing color");
            return;
        }
        String colorStr = json.get("color").getAsString();

        // Convert color string to ManaType
        mage.constants.ManaType manaType;
        switch (colorStr) {
            case "white": manaType = mage.constants.ManaType.WHITE; break;
            case "blue": manaType = mage.constants.ManaType.BLUE; break;
            case "black": manaType = mage.constants.ManaType.BLACK; break;
            case "red": manaType = mage.constants.ManaType.RED; break;
            case "green": manaType = mage.constants.ManaType.GREEN; break;
            case "colorless": manaType = mage.constants.ManaType.COLORLESS; break;
            default: return;
        }

        // The player whose mana pool to use must be the session's own player.
        // Player UUIDs are visible in serialized game state, so a spectator or
        // opponent could otherwise spend someone else's mana.
        String playerIdStr = requireStringOrError(json, "playerId");
        if (playerIdStr == null) return;
        UUID playerId = parseUuidOrError(playerIdStr, "playerId");
        if (playerId == null) return;
        UUID myPlayerId = proxyClient.getPlayerId();
        if (myPlayerId == null || !myPlayerId.equals(playerId)) {
            logger.warn("Rejected use_mana for playerId " + playerId
                    + " (session player: " + myPlayerId + ")");
            sendError("use_mana rejected — playerId is not this session's player");
            return;
        }

        logger.info("Using mana from pool: " + colorStr + " for player " + playerId);
        xmageSession.sendPlayerManaType(gameId, playerId, manaType);
    }

    private void handleConcede() {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }

        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }

        logger.info("Conceding game " + gameId);
        xmageSession.sendPlayerAction(PlayerAction.CONCEDE, gameId, null);
    }

    /**
     * Concede the entire match (not just the current game).
     *
     * Mirrors the stock Swing client's concede-match path exactly: PlayAreaPanel's
     * "Match" concede button raises a USER_REQUEST_DIALOG carrying
     * CLIENT_CONCEDE_MATCH, and MageFrame maps that action to
     * SessionHandler.quitMatch(gameId) → session.quitMatch(gameId) → the
     * matchQuit RPC. quitMatch concedes the current game AND forfeits the
     * remaining games of the match (GameController.quitMatch →
     * GameSessionPlayer.quitGame → Player.quit → concede + "quits the match.").
     *
     * Do NOT go back to sendPlayerAction(CLIENT_CONCEDE_MATCH...): that action
     * has no server-side handler anywhere (GameController.fireEvent has no case
     * for it; PlayerImpl.sendPlayerAction has no case and no default), so the
     * button was a silent no-op.
     */
    private void handleConcedeMatch() {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }

        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }

        logger.info("Conceding match for game " + gameId + " (quitMatch — stock client path)");
        boolean success = xmageSession.quitMatch(gameId);
        if (!success) {
            // Fail Fast: a failed concede must reach the player — the old
            // CLIENT_CONCEDE_MATCH send failed silently and looked like success.
            String error = xmageSession.getLastError();
            logger.error("Concede match failed: quitMatch returned false for game " + gameId
                    + " (connected=" + xmageSession.isConnected() + "). lastError: " + error);
            sendError("Failed to concede match: " + rawOrEmpty(error));
        }
    }

    /**
     * Response to a USER_REQUEST_DIALOG (e.g. rollback-permission request).
     * Mirrors the stock client's default path: sendPlayerAction(action, gameId,
     * relatedUserId) — see MageFrame.sendUserReplay.
     *
     * PlayerActions the proxy itself would legitimately produce from a
     * USER_REQUEST_DIALOG button. The server only ever sets these
     * as button actions (GameSessionPlayer.requestPermissionTo*); anything else
     * arriving over the wire is an injection attempt and is rejected.
     */
    private static final java.util.Set<PlayerAction> USER_REQUEST_ALLOWED_ACTIONS = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList(
            PlayerAction.ADD_PERMISSION_TO_SEE_HAND_CARDS,
            PlayerAction.ADD_PERMISSION_TO_ROLLBACK_TURN,
            PlayerAction.DENY_PERMISSION_TO_ROLLBACK_TURN)));

    private void handleUserRequestResponse(com.google.gson.JsonObject json) {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }

        String actionName = json.has("action") && !json.get("action").isJsonNull()
                ? json.get("action").getAsString() : null;
        if (actionName == null || actionName.isEmpty()) {
            sendError("user_request_response requires an action");
            return;
        }

        PlayerAction action;
        try {
            action = PlayerAction.valueOf(actionName);
        } catch (IllegalArgumentException e) {
            // Fail Fast: unknown action names must not be silently dropped.
            sendError("Unknown player action: " + actionName);
            return;
        }
        if (!USER_REQUEST_ALLOWED_ACTIONS.contains(action)) {
            // A client could otherwise fire CONCEDE / UNDO / ROLLBACK_TURNS etc.
            // against any game on the server. Only the permission-dialog responses
            // the proxy itself renders are accepted here.
            logger.warn("Rejected user_request_response action not on whitelist: " + action);
            sendError("Action not allowed via user_request_response: " + action);
            return;
        }

        // The response must be for the game the proxy is currently attached
        // to — no cross-game injection.
        UUID currentGameId = proxyClient.getCurrentGameId();
        String gameIdStr = json.has("gameId") && !json.get("gameId").isJsonNull()
                ? json.get("gameId").getAsString() : null;
        UUID gameId;
        if (gameIdStr == null) {
            gameId = currentGameId;
        } else {
            gameId = parseUuidOrError(gameIdStr, "gameId");
            if (gameId == null) return;
        }
        if (gameId == null || currentGameId == null || !gameId.equals(currentGameId)) {
            logger.warn("Rejected user_request_response with gameId " + gameId
                    + " != current game " + currentGameId);
            sendError("gameId does not match the current game");
            return;
        }

        String relatedUserIdStr = json.has("relatedUserId") && !json.get("relatedUserId").isJsonNull()
                ? json.get("relatedUserId").getAsString() : null;
        UUID relatedUserId = null;
        if (relatedUserIdStr != null) {
            relatedUserId = parseUuidOrError(relatedUserIdStr, "relatedUserId");
            if (relatedUserId == null) return;
        }

        logger.info("User request response: action=" + action + " gameId=" + gameId
                + " relatedUserId=" + relatedUserId);
        xmageSession.sendPlayerAction(action, gameId, relatedUserId);
    }

    /**
     * Hold priority — prevents the server from auto-passing after casting/activating.
     * The player must explicitly pass priority each step.
     * Browser sends: {"type": "hold_priority", "hold": true|false}
     */
    private void handleHoldPriority(com.google.gson.JsonObject json) {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }

        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }

        boolean hold = json.has("hold") && json.get("hold").getAsBoolean();
        logger.info("Hold priority: " + hold);
        xmageSession.sendPlayerAction(hold ? PlayerAction.HOLD_PRIORITY : PlayerAction.UNHOLD_PRIORITY, gameId, null);
    }

    /**
     * Rollback turns (if allowed by game settings).
     * Uses ROLLBACK_TURNS which rolls back to the start of the current turn.
     *
     * ROOT CAUSE: the
     * desktop client sends an Integer turn count with ROLLBACK_TURNS
     * (PlayAreaPanel.java: `sendPlayerAction(PlayerAction.ROLLBACK_TURNS, gameId,
     * turnsToRollBack)`), and the game side only acts when
     * `data instanceof Integer` (GameController.sendPlayerAction, case ROLLBACK_TURNS).
     * This handler sent `null`, so the whole case fell through silently — no
     * rollback, no error, no log. Send 0 = "to the start of the current turn"
     * (the web UI has no turn picker; GameImpl.canRollbackTurns(0) succeeds whenever
     * the current turn's own snapshot exists — turnNum - 0 > 0 for every turn after
     * the first, and saveRollBackGameState stored it at the turn start).
     *
     * Fail Fast: the RPC result is checked and the outcome is logged; an RPC that
     * never reached the server toasts the user (in-game rejections travel back as
     * GAME_INFORM_PERSONAL log lines — "That turn is not available for rollback." /
     * "You can only request a rollback if you have priority." — which the frontend
     * already appends to the game log).
     */
    private void handleRollbackTurns() {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }

        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }

        logger.info("Rollback turns for game " + gameId);
        // 0 = roll back to the start of the CURRENT turn (see method javadoc).
        boolean sent = xmageSession.sendPlayerAction(PlayerAction.ROLLBACK_TURNS, gameId, 0);
        if (!sent) {
            // The RPC never left the proxy (disconnected / session dead). The
            // game-side rejections DO reach the player as inform lines; this one
            // would otherwise be a second silent swallow.
            logger.error("Rollback turns: sendPlayerAction returned false (session not connected) for game " + gameId);
            sendError("Rollback failed: not connected to the game server");
        }
    }

    /**
     * Stop watching a game (spectator mode).
     * Uses CLIENT_STOP_WATCHING which leaves the spectator session.
     */
    private void handleStopWatching() {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }

        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }

        logger.info("Stop watching game " + gameId);
        spectator = false;
        xmageSession.sendPlayerAction(PlayerAction.CLIENT_STOP_WATCHING, gameId, null);
        // The watch is over — drop the game id so room chat no longer routes into
        // the dead game chat and a replay can be loaded in this session.
        proxyClient.clearCurrentGameId();
    }

    /**
     * Triggered ability auto-ordering.
     * Browser sends: {"type": "trigger_order", "action": "first"|"last"|"name_first"|"name_last"|"reset_all",
     *                "abilityId": "uuid", "ruleText": "text"}
     * Maps to PlayerAction.TRIGGER_AUTO_ORDER_* + sendPlayerUUID.
     */
    private void handleTriggerOrder(com.google.gson.JsonObject json) {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }

        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }

        String action = json.has("action") ? json.get("action").getAsString() : "reset_all";
        UUID abilityId = null;
        if (json.has("abilityId") && !json.get("abilityId").isJsonNull()) {
            try {
                abilityId = UUID.fromString(json.get("abilityId").getAsString());
            } catch (IllegalArgumentException e) {
                sendError("Invalid ability ID: " + json.get("abilityId"));
                return;
            }
        }
        String ruleText = json.has("ruleText") ? json.get("ruleText").getAsString() : null;

        logger.info("Trigger order: " + action + " abilityId=" + abilityId + " ruleText=" + ruleText);
        switch (action) {
            case "first":
                xmageSession.sendPlayerAction(PlayerAction.TRIGGER_AUTO_ORDER_ABILITY_FIRST, gameId, abilityId);
                xmageSession.sendPlayerUUID(gameId, abilityId);
                break;
            case "last":
                xmageSession.sendPlayerAction(PlayerAction.TRIGGER_AUTO_ORDER_ABILITY_LAST, gameId, abilityId);
                xmageSession.sendPlayerUUID(gameId, null);
                break;
            case "name_first":
                if (ruleText != null) {
                    xmageSession.sendPlayerAction(PlayerAction.TRIGGER_AUTO_ORDER_NAME_FIRST, gameId, ruleText);
                    xmageSession.sendPlayerUUID(gameId, abilityId);
                }
                break;
            case "name_last":
                if (ruleText != null) {
                    xmageSession.sendPlayerAction(PlayerAction.TRIGGER_AUTO_ORDER_NAME_LAST, gameId, ruleText);
                    xmageSession.sendPlayerUUID(gameId, null);
                }
                break;
            case "reset_all":
                xmageSession.sendPlayerAction(PlayerAction.TRIGGER_AUTO_ORDER_RESET_ALL, gameId, null);
                break;
            default:
                sendError("Unknown trigger order action: " + action);
        }
    }

    /**
     * Remembered auto-answers for recurring yes/no prompts + the replacement-effect
     * reset.
     *
     * Desktop reference: {@code HelperPanel.handleAutoAnswerPopupMenuEvent}
     * (Mage.Client .../HelperPanel.java:493-521) — the submenu of the yes/no dialog's
     * buttons; that client sends the SAME PlayerActions this verb forwards. The
     * yes/no-reset entry of the desktop's battlefield "Auto-answers" submenu
     * (PlayAreaPanel.java:275-300) is the same REQUEST_AUTO_ANSWER_RESET_ALL.
     *
     * WIRE CONTRACT (keep the browser side in sync — src/types/messages.ts
     * AutoAnswerRequest, src/store/slices/gameplaySlice.ts):
     *
     *   {"type":"auto_answer","action":"<action>",
     *    "originalId":"<uuid>"|null,   // id_yes / id_no ONLY (else null)
     *    "message":"<text>"|null}      // all four yes/no actions (else null)
     *
     *   action              PlayerAction (HumanPlayer.sendPlayerAction)   data
     *   ------------------  --------------------------------------------  ---------------------------
     *   id_yes              REQUEST_AUTO_ANSWER_ID_YES                    originalId + '#' + message
     *   id_no               REQUEST_AUTO_ANSWER_ID_NO                     originalId + '#' + message
     *   text_yes            REQUEST_AUTO_ANSWER_TEXT_YES                  message
     *   text_no             REQUEST_AUTO_ANSWER_TEXT_NO                   message
     *   reset_all           REQUEST_AUTO_ANSWER_RESET_ALL                 null
     *   reset_replacement   RESET_AUTO_SELECT_REPLACEMENT_EFFECTS         null
     *
     *   The two key forms are the ENGINE's own (HumanPlayer.chooseUse:459-464):
     *   the id map is keyed {@code source.getOriginalId() + "#" + autoAnswerMessage}
     *   and the text map is keyed {@code autoAnswerMessage}. `message` must therefore
     *   be the ask's AUTO_ANSWER_MESSAGE option — NOT the text the user saw: the
     *   displayed prompt carries a controlling-player hint and the engine's own
     *   message has source names replaced by {this} (HumanPlayer.chooseUse:438-453).
     *   Both values ride the boolean prompt payload (WebProxyClient.handleGameAsk
     *   writes `original_id` / `auto_answer_message`), so a client builds the key
     *   from exactly the bytes the engine will look up later.
     *
     * Trigger-order auto-answers deliberately do NOT live here:
     * TRIGGER_AUTO_ORDER_RESET_ALL already rides the existing `trigger_order` verb's
     * "reset_all" action (handleTriggerOrder) — one verb per feature family.
     *
     * Fail Fast (AGENTS.md): an unknown action, a missing/empty `message`, or an
     * id-variant without a UUID `originalId` is refused with a browser error. A
     * silently substituted key would make the memory an inert no-op with no feedback
     * (the engine looks up by exact key and simply never matches).
     */
    private void handleAutoAnswer(com.google.gson.JsonObject json) {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }

        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }

        if (!json.has("action") || json.get("action").isJsonNull()) {
            sendError("auto_answer requires an 'action' field");
            return;
        }
        String action = json.get("action").getAsString();

        // ── resets: no key material, the engine clears the whole map ──
        if ("reset_all".equals(action)) {
            logger.info("Auto-answer: reset_all (yes/no) for game " + gameId);
            boolean sent = xmageSession.sendPlayerAction(PlayerAction.REQUEST_AUTO_ANSWER_RESET_ALL, gameId, null);
            if (!sent) {
                logger.error("Auto-answer reset_all: sendPlayerAction returned false (session not connected) for game " + gameId);
                sendError("Auto-answer reset failed: not connected to the game server");
            }
            return;
        }
        if ("reset_replacement".equals(action)) {
            logger.info("Auto-answer: reset_replacement for game " + gameId);
            boolean sent = xmageSession.sendPlayerAction(PlayerAction.RESET_AUTO_SELECT_REPLACEMENT_EFFECTS, gameId, null);
            if (!sent) {
                logger.error("Auto-answer reset_replacement: sendPlayerAction returned false (session not connected) for game " + gameId);
                sendError("Auto-answer reset failed: not connected to the game server");
            }
            return;
        }

        boolean yes;
        boolean byId;
        switch (action) {
            case "id_yes":
                yes = true;
                byId = true;
                break;
            case "id_no":
                yes = false;
                byId = true;
                break;
            case "text_yes":
                yes = true;
                byId = false;
                break;
            case "text_no":
                yes = false;
                byId = false;
                break;
            default:
                sendError("Unknown auto_answer action: " + action
                        + " (expected id_yes|id_no|text_yes|text_no|reset_all|reset_replacement)");
                return;
        }

        // `message` = the ask's autoAnswerMessage (the engine's key material). Never
        // defaulted from anything else: a mismatched key is a silent no-op later.
        String message = json.has("message") && !json.get("message").isJsonNull()
                ? json.get("message").getAsString() : null;
        if (message == null || message.isEmpty()) {
            sendError("auto_answer " + action + " requires the ask's 'message' (its autoAnswerMessage)");
            return;
        }

        String data;
        if (byId) {
            String originalId = json.has("originalId") && !json.get("originalId").isJsonNull()
                    ? json.get("originalId").getAsString() : null;
            if (originalId == null || originalId.isEmpty()) {
                sendError("auto_answer " + action + " requires 'originalId' (the ask's source ability id)");
                return;
            }
            try {
                UUID.fromString(originalId);
            } catch (IllegalArgumentException e) {
                // A malformed id would be stored as a dead key — refuse it loudly.
                sendError("auto_answer " + action + ": 'originalId' is not a UUID: " + originalId);
                return;
            }
            data = originalId + '#' + message;
        } else {
            data = message;
        }

        PlayerAction playerAction = byId
                ? (yes ? PlayerAction.REQUEST_AUTO_ANSWER_ID_YES : PlayerAction.REQUEST_AUTO_ANSWER_ID_NO)
                : (yes ? PlayerAction.REQUEST_AUTO_ANSWER_TEXT_YES : PlayerAction.REQUEST_AUTO_ANSWER_TEXT_NO);

        logger.info("Auto-answer: " + action + " (" + (byId ? "id+text" : "text") + " key) for game " + gameId);
        boolean sent = xmageSession.sendPlayerAction(playerAction, gameId, data);
        if (!sent) {
            logger.error("Auto-answer " + action + ": sendPlayerAction returned false (session not connected) for game " + gameId);
            sendError("Auto-answer failed: not connected to the game server");
        }
    }

    /**
     * View sideboard (for limited formats).
     * Uses VIEW_SIDEBOARD which opens the sideboard view on the server side.
     */
    private void handleViewSideboard() {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }

        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }

        logger.info("View sideboard for game " + gameId);
        xmageSession.sendPlayerAction(PlayerAction.VIEW_SIDEBOARD, gameId, null);
    }

    /**
     * View a seat's deck.
     * Browser sends: {"type": "view_limited_deck", "playerId": "<uuid>"}
     * Maps to PlayerAction.VIEW_LIMITED_DECK + the target seat's player UUID.
     * The server allows SELF or COMPUTER targets only (GameController.viewDeckOrSideboard:
     * other-human requests are rejected with a "trying to cheat" log), and answers
     * through the VIEW_LIMITED_DECK callback, which WebProxyClient serializes as a
     * `view_limited_deck` message carrying the deck's card lists.
     */
    private void handleViewLimitedDeck(com.google.gson.JsonObject json) {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }

        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }

        if (!json.has("playerId") || json.get("playerId").isJsonNull()) {
            sendError("Missing playerId");
            return;
        }
        UUID targetPlayerId;
        try {
            targetPlayerId = UUID.fromString(json.get("playerId").getAsString());
        } catch (IllegalArgumentException e) {
            logger.warn("handleViewLimitedDeck: invalid UUID in field 'playerId': " + json.get("playerId"));
            sendError("Invalid playerId (not a UUID): " + rawOrEmpty(json.get("playerId").getAsString()));
            return;
        }

        logger.info("View deck for game " + gameId + " targetPlayerId=" + targetPlayerId);
        // The boolean result must be consumed: a dead session would otherwise be an
        // invisible no-op (AGENTS.md RPC rule 3 — log at every failure point).
        boolean sent = xmageSession.sendPlayerAction(PlayerAction.VIEW_LIMITED_DECK, gameId, targetPlayerId);
        if (!sent) {
            logger.error("View deck: sendPlayerAction returned false (session not connected) for game "
                    + gameId + " targetPlayerId=" + targetPlayerId);
            sendError("View deck failed: not connected to the game server");
        }
    }

    // ── Hand-request / permission management ──
    //
    // Desktop reference (PlayAreaPanel.java:403-434, the "Cards on hand (name)"
    // submenu of the battlefield right-click):
    //   * a NON-self seat:    "Request permission to see the hand cards"
    //                         → PlayerAction.REQUEST_PERMISSION_TO_SEE_HAND_CARDS + that playerId
    //   * YOUR OWN seat:      checkbox "Allow hand requests from other users"
    //                         → PERMISSION_REQUESTS_ALLOWED_ON / _OFF (data null)
    //                         + "Revoke all permission(s) to see your hand cards"
    //                         → REVOKE_PERMISSIONS_TO_SEE_HAND_CARDS (data null)
    //   * a WATCHER's menu:   "Request permission to see hand cards (name)"
    //                         → the same REQUEST action (PlayAreaPanel.java:479-481)
    //
    // The RESPONSE half (granter clicks Accept → ADD_PERMISSION_TO_SEE_HAND_CARDS)
    // already rides user_request_response; these three verbs are the INITIATING half.
    //
    // WIRE CONTRACT (keep the browser side in sync — src/types/messages.ts
    // RequestHandPermissionRequest and src/store/slices/gameplaySlice.ts):
    //
    //   {"type":"request_hand_permission","playerId":"<uuid>"}   // GRANTER's player UUID
    //   {"type":"revoke_hand_permission"}                        // own hand, no fields
    //   {"type":"allow_hand_requests","enabled":true|false}      // own hand; true also
    //                                                            // clears the one-request-per-user memory
    //
    // Verbs → engine:
    //   request_hand_permission → PlayerAction.REQUEST_PERMISSION_TO_SEE_HAND_CARDS, data=playerId
    //   revoke_hand_permission  → PlayerAction.REVOKE_PERMISSIONS_TO_SEE_HAND_CARDS, data=null
    //   allow_hand_requests     → PlayerAction.PERMISSION_REQUESTS_ALLOWED_ON|_OFF, data=null
    //
    // SPECTATOR GATING: only request_hand_permission is watcher-allowed (the desktop
    // has a watcher entry for exactly that action and the engine path tolerates a
    // null requestingPlayerId); revoke/allow act on the sender's OWN seat and are in
    // PLAYER_ONLY_ACTIONS.

    /**
     * Ask another seat's owner for permission to see their hand.
     * Browser sends: {@code {"type": "request_hand_permission", "playerId": "<uuid>"}}
     * where playerId is the GRANTER's player UUID (self-request is refused by the
     * engine: GameController.requestPermissionToSeeHandCards:759 — same-seat requests
     * are silently dropped, so the menu never offers the item on your own header).
     *
     * The engine answers the request in one of three ways, all of them already wired
     * through existing channels:
     *  - human granter → USER_REQUEST_DIALOG at the granter (UserRequestDialog.tsx),
     *    whose Accept button sends ADD_PERMISSION_TO_SEE_HAND_CARDS;
     *  - non-human granter → the grant is immediate (GameController:772-775), so the
     *    next state push simply carries the watched hand;
     *  - granter disallows requests / already granted → a server user message
     *    (show_usermessage) surfaces in the browser toast.
     */
    private void handleRequestHandPermission(com.google.gson.JsonObject json) {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }

        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }

        if (!json.has("playerId") || json.get("playerId").isJsonNull()) {
            sendError("Missing playerId");
            return;
        }
        UUID targetPlayerId;
        try {
            targetPlayerId = UUID.fromString(json.get("playerId").getAsString());
        } catch (IllegalArgumentException e) {
            logger.warn("handleRequestHandPermission: invalid UUID in field 'playerId': " + json.get("playerId"));
            sendError("Invalid playerId (not a UUID): " + rawOrEmpty(json.get("playerId").getAsString()));
            return;
        }

        logger.info("Request hand permission for game " + gameId + " targetPlayerId=" + targetPlayerId
                + " (spectator=" + spectator + ")");
        boolean sent = xmageSession.sendPlayerAction(
                PlayerAction.REQUEST_PERMISSION_TO_SEE_HAND_CARDS, gameId, targetPlayerId);
        if (!sent) {
            logger.error("Request hand permission: sendPlayerAction returned false (session not connected) for game "
                    + gameId + " targetPlayerId=" + targetPlayerId);
            sendError("Request permission failed: not connected to the game server");
        }
    }

    /**
     * Revoke every permission granted on YOUR OWN hand this game.
     * Browser sends: {@code {"type": "revoke_hand_permission"}}.
     * Engine: REVOKE_PERMISSIONS_TO_SEE_HAND_CARDS → PlayerImpl.revokePermissionToSeeHandCards
     * (clears usersAllowedToSeeHandCards), so every watcher/player holding a grant
     * stops receiving the hand on the next state push. Player-only: the grant set
     * belongs to the sender's own seat.
     */
    private void handleRevokeHandPermission() {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }

        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }

        logger.info("Revoke hand permissions for game " + gameId);
        boolean sent = xmageSession.sendPlayerAction(PlayerAction.REVOKE_PERMISSIONS_TO_SEE_HAND_CARDS, gameId, null);
        if (!sent) {
            logger.error("Revoke hand permissions: sendPlayerAction returned false (session not connected) for game "
                    + gameId);
            sendError("Revoke failed: not connected to the game server");
        }
    }

    /**
     * Allow/deny hand requests on YOUR OWN hand.
     * Browser sends: {@code {"type": "allow_hand_requests", "enabled": true|false}}.
     * Engine: PERMISSION_REQUESTS_ALLOWED_ON|_OFF (PlayerImpl.sendPlayerAction:2776-2783).
     * ON also RESETS the one-request-per-user memory (resetRequestedHandPlayersList),
     * which is why the desktop re-activates the checkbox to allow fresh requests —
     * the same semantics are documented on the menu item. Player-only (own seat).
     */
    private void handleAllowHandRequests(com.google.gson.JsonObject json) {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }

        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }

        if (!json.has("enabled") || json.get("enabled").isJsonNull()) {
            // Fail Fast: the desktop checkbox always knows its state; a payload without
            // one must not silently pick a direction and flip the wrong way.
            sendError("allow_hand_requests requires an 'enabled' boolean");
            return;
        }
        boolean enabled = json.get("enabled").getAsBoolean();
        logger.info("Allow hand requests: " + enabled + " for game " + gameId);
        boolean sent = xmageSession.sendPlayerAction(
                enabled ? PlayerAction.PERMISSION_REQUESTS_ALLOWED_ON
                        : PlayerAction.PERMISSION_REQUESTS_ALLOWED_OFF,
                gameId, null);
        if (!sent) {
            logger.error("Allow hand requests: sendPlayerAction returned false (session not connected) for game " + gameId);
            sendError("Hand-request setting failed: not connected to the game server");
        }
    }

    /**
     * Toggle mana auto-payment preference.
     * Browser sends: {"type": "toggle_mana_auto_pay", "enabled": true|false}
     * When enabled, the server auto-pays mana from the player's pool.
     */
    private void handleToggleManaAutoPay(com.google.gson.JsonObject json) {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }

        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }

        boolean enabled = json.has("enabled") && json.get("enabled").getAsBoolean();
        logger.info("Toggle mana auto-pay: " + enabled);
        xmageSession.sendPlayerAction(enabled ? PlayerAction.MANA_AUTO_PAYMENT_ON : PlayerAction.MANA_AUTO_PAYMENT_OFF, gameId, null);
    }

    /**
     * Toggle "use first mana ability" preference.
     * Browser sends: {"type": "toggle_use_first_mana", "enabled": true|false}
     * When enabled, the server auto-selects the first mana ability when tapping lands
     * that have multiple abilities (e.g., dual lands). Matches Java client's
     * "Use first mana ability when tapping lands" checkbox.
     */
    private void handleToggleUseFirstMana(com.google.gson.JsonObject json) {
        if (xmageSession == null || proxyClient == null) {
            sendError("Not in a game");
            return;
        }

        UUID gameId = proxyClient.getCurrentGameId();
        if (gameId == null) {
            sendError("No active game");
            return;
        }

        boolean enabled = json.has("enabled") && json.get("enabled").getAsBoolean();
        logger.info("Toggle use first mana ability: " + enabled);
        xmageSession.sendPlayerAction(enabled ? PlayerAction.USE_FIRST_MANA_ABILITY_ON : PlayerAction.USE_FIRST_MANA_ABILITY_OFF, gameId, null);
    }

    /**
     * Update phase stop settings mid-session.
     * Browser sends: {"type": "update_phase_stops", "stops": {
     *   "yourTurn": {"upkeep": true, "draw": true, "main1": true, "beforeCombat": true,
     *                "endOfCombat": true, "main2": true, "endOfTurn": true},
     *   "opponentTurn": {"upkeep": true, "draw": true, "main1": true, "beforeCombat": true,
     *                    "endOfCombat": true, "main2": true, "endOfTurn": true},
     *   "stopOnDeclareBlockersWithZeroPermanents": true,
     *   "stopOnDeclareBlockersWithAnyPermanents": true,
     *   "stopOnAllMainPhases": true,
     *   "stopOnAllEndPhases": true,
     *   "stopOnStackNewObjects": true
     * }}
     *
     * Rebuilds UserSkipPrioritySteps from the JSON and calls connection.setUserData()
     * to push the new settings to the server mid-session.
     */
    private void handleUpdatePhaseStops(com.google.gson.JsonObject json) {
        if (xmageSession == null || connection == null) {
            sendError("Not connected to XMage server");
            return;
        }

        if (!json.has("stops")) {
            sendError("Missing 'stops' object in update_phase_stops");
            return;
        }

        com.google.gson.JsonObject stops = json.getAsJsonObject("stops");

        UserSkipPrioritySteps skipSteps = new UserSkipPrioritySteps();

        // Your turn stops
        if (stops.has("yourTurn")) {
            com.google.gson.JsonObject yt = stops.getAsJsonObject("yourTurn");
            skipSteps.getYourTurn().setUpkeep(getBool(stops, yt, "upkeep", true));
            skipSteps.getYourTurn().setDraw(getBool(stops, yt, "draw", true));
            skipSteps.getYourTurn().setMain1(getBool(stops, yt, "main1", true));
            skipSteps.getYourTurn().setBeforeCombat(getBool(stops, yt, "beforeCombat", true));
            skipSteps.getYourTurn().setEndOfCombat(getBool(stops, yt, "endOfCombat", true));
            skipSteps.getYourTurn().setMain2(getBool(stops, yt, "main2", true));
            skipSteps.getYourTurn().setEndOfTurn(getBool(stops, yt, "endOfTurn", true));
        }

        // Opponent turn stops
        if (stops.has("opponentTurn")) {
            com.google.gson.JsonObject ot = stops.getAsJsonObject("opponentTurn");
            skipSteps.getOpponentTurn().setUpkeep(getBool(stops, ot, "upkeep", true));
            skipSteps.getOpponentTurn().setDraw(getBool(stops, ot, "draw", true));
            skipSteps.getOpponentTurn().setMain1(getBool(stops, ot, "main1", true));
            skipSteps.getOpponentTurn().setBeforeCombat(getBool(stops, ot, "beforeCombat", true));
            skipSteps.getOpponentTurn().setEndOfCombat(getBool(stops, ot, "endOfCombat", true));
            skipSteps.getOpponentTurn().setMain2(getBool(stops, ot, "main2", true));
            skipSteps.getOpponentTurn().setEndOfTurn(getBool(stops, ot, "endOfTurn", true));
        }

        // Global stop options
        skipSteps.setStopOnDeclareBlockersWithZeroPermanents(
                stops.has("stopOnDeclareBlockersWithZeroPermanents") && stops.get("stopOnDeclareBlockersWithZeroPermanents").getAsBoolean());
        skipSteps.setStopOnDeclareBlockersWithAnyPermanents(
                stops.has("stopOnDeclareBlockersWithAnyPermanents") && stops.get("stopOnDeclareBlockersWithAnyPermanents").getAsBoolean());
        skipSteps.setStopOnAllMainPhases(
                stops.has("stopOnAllMainPhases") && stops.get("stopOnAllMainPhases").getAsBoolean());
        skipSteps.setStopOnAllEndPhases(
                stops.has("stopOnAllEndPhases") && stops.get("stopOnAllEndPhases").getAsBoolean());
        skipSteps.setStopOnStackNewObjects(
                stops.has("stopOnStackNewObjects") && stops.get("stopOnStackNewObjects").getAsBoolean());

        // Rebuild UserData with the new skip steps. Preserve the original
        // userIdStr (browserId) and the session's avatar so phase-stop
        // changes don't wipe the shared-IP mitigation
        // or reset the player's
        // avatar to the default.
        UserData userData = buildWebProxyUserData(skipSteps, connection.getUserIdStr(), sessionAvatarId);
        connection.setUserData(userData);

        logger.info("Updated phase stops: yourTurn=" + skipSteps.getYourTurn()
                + " opponentTurn=" + skipSteps.getOpponentTurn());
    }

    /** Helper: get a boolean from a JSON object with a fallback default. */
    private static boolean getBool(com.google.gson.JsonObject parent, com.google.gson.JsonObject child, String key, boolean fallback) {
        return child.has(key) ? child.get(key).getAsBoolean() : fallback;
    }

    // ── Resync ──

    /**
     * Client detected a state version gap (or tab regained visibility) and wants
     * a full state refresh. We ask the proxy client to re-send the latest game
     * state, then send a resync_complete marker so the client clears its
     * resyncRequested flag.
     */
    private void handleRequestResync() {
        if (proxyClient == null) {
            sendError("Not connected to XMage server");
            return;
        }
        logger.info("Client requested state resync");
        proxyClient.resendLatestGameState();
        sendTypedMessage("resync_complete");
    }

    /**
     * Re-attach to an in-progress game after a page refresh.
     * The browser sends {"type": "rejoin_game", "gameId": "..."} after
     * receiving a rejoin_game notification from checkForRejoinableGame.
     * This calls joinGame(gameId) which re-attaches the client to the
     * existing game session, causing the server to send GAME_INIT + state.
     */
    private void handleRejoinGame(com.google.gson.JsonObject json) {
        if (xmageSession == null) {
            sendError("Not connected to XMage server");
            return;
        }
        String gameIdStr = requireStringOrError(json, "gameId");
        if (gameIdStr == null) return;
        UUID gameId = parseUuidOrError(gameIdStr, "gameId");
        if (gameId == null) return;

        // The game must belong to a table the user is seated at — gameIds are
        // enumerable via get_tables, so an arbitrary rejoin would attach the
        // session to (and leak state of) someone else's game.
        if (!isGameSeatOfUser(gameId)) {
            logger.warn("Rejected rejoin_game for non-seated game " + gameId);
            sendError("Cannot rejoin — you are not seated at that game's table");
            return;
        }

        logger.info("Rejoining game " + gameId);
        boolean success = xmageSession.joinGame(gameId);
        if (!success) {
            String error = xmageSession.getLastError();
            logger.error("joinGame returned false. lastError: " + error);
            sendError("Failed to rejoin game: " + rawOrEmpty(error != null ? error : "unknown"));
            return;
        }
        spectator = false; // rejoining a seated game = player
        // F-REJOIN-2 (two-browser repro 2026-09-27): record the game id NOW.
        // Until the server's re-sent GAME_INIT is finally processed client-side
        // (held by SessionImpl with the connect flow, measured ~2 s), the fresh
        // client's currentGameId is still null. cleanup()'s keep-alive decision
        // reads exactly this field — a browser close inside that window
        // (F5 twice in quick succession, reload during a reconnect burst)
        // therefore took the HARD disconnect path (connectStop(false,false)):
        // the server saw DisconnectedByUser, removed the seat from the table and
        // the match conceded; every later reconnect found no rejoinable game and
        // the player stranded in the lobby. Recording the id here keeps the
        // session (KEEP_MY_OLD_SESSION) on all closes after a successful rejoin.
        if (proxyClient != null) {
            proxyClient.setCurrentGameId(gameId);
        }
        logger.info("Successfully rejoined game " + gameId);
        // The server will send GAME_INIT + game state via callbacks.
        // Send a start_game message so the frontend switches to game view.
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "start_game");
        msg.put("gameId", gameIdStr);
        sendToBrowser(gsonToJson(msg));
    }

    /**
     * K3 follow-up (submit_deck seat check): check whether this session's
     * username occupies a seat at the given tableId, across all tables in the
     * main room. Mirrors {@link #isGameSeatOfUser} but keyed on tableId.
     */
    private boolean isTableSeatOfUser(UUID tableId) {
        try {
            UUID roomId = xmageSession.getMainRoomId();
            if (roomId == null) return false;
            Collection<TableView> tables = xmageSession.getTables(roomId);
            if (tables == null) return false;
            String playerName = proxyClient != null ? proxyClient.getUsername() : null;
            if (playerName == null) return false;
            for (TableView table : tables) {
                if (!tableId.equals(table.getTableId())) continue;
                if (table.getSeats() == null) return false;
                for (mage.view.SeatView seat : table.getSeats()) {
                    // The seat may carry this user's typed seat name, not the username.
                    if (SeatAliases.isCreatorSeat(playerName, seat.getPlayerName())) {
                        return true;
                    }
                }
                return false; // matched the table but not seated
            }
            return false;
        } catch (Throwable t) {
            logger.error("isTableSeatOfUser failed for table " + tableId, t);
            return false;
        }
    }

    /**
     * Check whether the given gameId belongs to a DUELING table
     * where this session's username occupies a seat. Mirrors the scan in
     * {@link #checkForRejoinableGame} but returns a boolean for the guard.
     */
    private boolean isGameSeatOfUser(UUID gameId) {
        try {
            UUID roomId = xmageSession.getMainRoomId();
            if (roomId == null) return false;
            Collection<TableView> tables = xmageSession.getTables(roomId);
            if (tables == null) return false;
            String playerName = proxyClient != null ? proxyClient.getUsername() : null;
            if (playerName == null) return false;
            for (TableView table : tables) {
                if (table.getGames() == null || !table.getGames().contains(gameId)) continue;
                if (table.getSeats() == null) continue;
                for (mage.view.SeatView seat : table.getSeats()) {
                    // The seat may carry the user's typed seat name, not the username.
                    if (SeatAliases.isCreatorSeat(playerName, seat.getPlayerName())) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            logger.error("Error checking seat ownership for game " + gameId, t);
            return false;
        }
    }

    // ── Ping/keepalive ──

    private void startPing() {
        pingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            // Username goes into a thread name — sanitize (no newlines/ANSI).
            Thread t = new Thread(r, "WebProxy-Ping-"
                    + (proxyClient != null ? sanitizeForLog(proxyClient.getUsername()) : "unknown"));
            t.setDaemon(true);
            return t;
        });
        pingExecutor.scheduleAtFixedRate(() -> {
            try {
                if (xmageSession != null && xmageSession.isConnected()) {
                    xmageSession.ping();
                }
            } catch (Throwable t) {
                logger.warn("Ping failed", t);
            }
        }, PING_INTERVAL_SEC, PING_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    // ── Cleanup ──

    private void cleanup() {
        if (closed) return;
        closed = true;

        // Release this session's per-host connection-cap slot on EVERY teardown
        // path (WS close, WS error, deliberate connect-rejection close). Copy-then-null
        // so a second teardown cannot double-release; the release is idempotent anyway
        // (PerHostConnectionLimiter.release reports whether a slot was actually held).
        String slot = hostSlotKey;
        hostSlotKey = null;
        if (slot != null && !PerHostConnectionLimiter.shared().release(slot)) {
            logger.warn("Connection-cap slot for " + slot + " was not held at teardown —"
                    + " bookkeeping mismatch (the cap count may be stale)");
        }

        // Deregister from the anon same-name guard on EVERY teardown path, same
        // posture as the host-slot release above. Copy-then-null; the release is
        // identity-checked in the guard so a name re-registered by someone else
        // after this session died is never dropped.
        AnonNameGuard.ActiveConnect active = activeConnect;
        activeConnect = null;
        if (active != null) {
            AnonNameGuard.shared().release(active);
        }

        // Deregister from the cross-session relay and drop this player's token-art
        // choices for the game, so a long-lived proxy does not accumulate state for
        // finished games. Done before the session teardown below because that can
        // block on the XMage side.
        LIVE_SESSIONS.remove(this);
        {
            WebProxyClient c = this.proxyClient;
            if (c != null) {
                TokenArtRelay.forgetPlayer(c.getCurrentGameId(), c.getPlayerId());
            }
        }

        if (pingExecutor != null) {
            pingExecutor.shutdownNow();
            pingExecutor = null;
        }

        if (tableWatchExecutor != null) {
            // Fast teardown path: the session is gone, nothing to be polite about.
            tableWatchExecutor.shutdownNow();
            tableWatchExecutor = null;
            watchedTableId = null;
        }
        // Candidate 1: a pending auto-start is owned by this session's watch — with the
        // session gone there is nothing left to fire it, so drop it rather than leave a
        // stale id behind.
        pendingAutoStartTableId = null;

        WebProxyClient client = proxyClient;
        if (xmageSession != null) {
            try {
                // B2/B3: a browser WebSocket close is almost always F5 or a
                // transient network blip, not intent to leave the match.
                // When a game is live, keep the XMage session active so the
                // server keeps the seat (KEEP_MY_OLD_SESSION ->
                // DisconnectedByUserButKeepTables upstream) and the reload's
                // reconnect path can reclaim the user instance (anon same-host
                // takeover) and rejoin via joinGame. Without this, the server
                // sees DisconnectedByUser, removes the user from all tables
                // and concedes the match within ~150 ms of the reload —
                // measured live 2026-09-12 (game 7a930d6b).
                // Safety nets unchanged: the server expires the kept session
                // on inactivity, and a player who never comes back is
                // auto-conceded by the 600 s response-idle timeout.
                // Spectators get no keep: watched games aren't restorable on
                // reconnect upstream (User.onLostConnection stops all watches
                // even under KeepTables), so a kept spectator session would
                // linger until timeout with nothing to rejoin.
                //
                // Candidate 2:
                // a WAITING/READY_TO_START table this account OWNS is a second reason to
                // keep. The server-side user instance holds the ownership (the
                // TableController's userId); a HARD disconnect removes the instance
                // (DisconnectedByUser -> removeUserFromAllTables -> removeUser), the
                // reload's reconnect mints a NEW user id, and isTableOwner then answers
                // FALSE for a table the same account just owned — the owner would lose
                // their table (server-side: TableController.leaveTable DELETES a
                // WAITING/READY table when its owner disconnects) and with it the very
                // start control this fix is about. Keeping the session (same-host
                // reconnect reclaims the SAME instance) preserves both.
                //
                // The signal is the cache in TableOwnership: a POSITIVE verdict exists
                // for this account exactly when resolveOwnedTableIds saw an in-window
                // (WAITING/READY_TO_START, non-tournament) table it owns. Only positives
                // count — "not the owner" of someone else's table must never hold a
                // session open. Same safety nets as the game case: the server expires
                // the kept session on inactivity (3 min to session-expire, 8 min to
                // full user removal — UserManagerImpl timeouts).
                boolean gameLive = !spectator && client != null && client.getCurrentGameId() != null;
                boolean ownsUnstartedTable = !spectator && client != null
                        && TableOwnership.hasPositiveVerdicts(client.getUsername());
                boolean keepSession = gameLive || ownsUnstartedTable;
                xmageSession.connectStop(false, keepSession);
                if (gameLive) {
                    logger.info("Kept XMage session alive for live game " + client.getCurrentGameId()
                            + " (browser WS closed — expect reconnect + rejoin)");
                } else if (ownsUnstartedTable) {
                    logger.info("Kept XMage session alive for " + client.getUsername()
                            + "'s unstarted owned table(s) (browser WS closed — expect reconnect)");
                }
            } catch (Throwable t) {
                logger.warn("Error disconnecting XMage session", t);
            }
            xmageSession = null;
        }

        // Release this connection's reference to the shared per-user history buffer
        // (GameHistory). It stays alive only when a game is live — that is the
        // reconnect/rejoin case the buffer exists for, and it is the same lifetime
        // the kept XMage session has. Otherwise the entry is dropped, so an
        // abandoned game cannot accumulate log/chat indefinitely
        // (GameHistory.MAX_USERS / MAX_LOG_EVENTS).
        if (client != null) {
            boolean gameLive = !spectator && client.getCurrentGameId() != null;
            GameHistory.release(client.getUsername(), gameLive);
        }

        proxyClient = null;
    }

    // ── WebSocket send ──

    private final Object wsLock = new Object();

    // ── Table-state watch ──

    /**
     * Watch the table this session is seated at while the match has NOT started, and PUSH
     * a fresh {@code table_list} to the browser whenever its seats/state change.
     *
     * <p>Why this exists: the seated owner's "Start Match" button used to appear only when
     * the browser's own 2 s {@code setInterval} poll fired. Chromium throttles timers in
     * background/hidden tabs (roughly one tick per minute), so the owner's view could sit
     * 40–60 s stale while the joiner's was current — indistinguishable from the real B1
     * blocker (measured: 41 s and 58 s owner-side lag; 41 s gap between table_list frames in
     * the creator's WS transcript). Shortening the interval does not help: the throttle
     * applies to any period.
     *
     * <p>The server pushes no table-change callback for a WAITING table, so the proxy polls
     * the existing {@code getTables} RPC (the same thing the stock Swing client's
     * UpdateSeatsTask does every 1 s for its waiting dialog) — server-side, where no browser
     * timer throttling exists — and forwards only on change. A WebSocket message is not a
     * timer, so the browser receives it immediately even while hidden. The page timer stays
     * as a cheap backstop and is no longer what makes the control appear.
     *
     * <p>Scope/bounds: one watch per browser session, only while the user is seated at a
     * table that has not started (WAITING / READY_TO_START), one light RPC per
     * {@link #TABLE_WATCH_INTERVAL_SEC}s, self-terminating when the match starts or the table
     * disappears, and shut down with the session in {@link #cleanup()}.
     */
    private synchronized void startTableWatch(UUID tableId) {
        if (tableId == null) {
            return;
        }
        watchedTableId = tableId;
        // Force a push on the first tick: a user who just joined/created learns the current
        // seat state without having to ask for it.
        lastWatchedTableSignature = null;
        tableWatchMisses = 0;
        if (tableWatchExecutor == null || tableWatchExecutor.isShutdown()) {
            tableWatchExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "WebProxy-TableWatch-"
                        + (proxyClient != null ? sanitizeForLog(proxyClient.getUsername()) : "unknown"));
                t.setDaemon(true);
                return t;
            });
            // scheduleAtFixedRate on a single-thread executor: a slow getTables RPC delays the
            // next tick instead of stacking overlapping RPCs.
            tableWatchExecutor.scheduleAtFixedRate(this::tableWatchTick, 0, TABLE_WATCH_INTERVAL_SEC, TimeUnit.SECONDS);
        }
        logger.info("Table watch started for tableId=" + tableId);
    }

    /** Stop the table watch (idempotent). */
    private synchronized void stopTableWatch(String reason) {
        boolean wasWatching = watchedTableId != null;
        watchedTableId = null;
        lastWatchedTableSignature = null;
        if (tableWatchExecutor != null) {
            // shutdown(), not shutdownNow(): a tick that decides the watch is over calls this
            // from the watch thread itself, and shutdownNow() would interrupt the very thread
            // running the call. shutdown() cancels the periodic task without interrupting.
            tableWatchExecutor.shutdown();
            tableWatchExecutor = null;
        }
        if (wasWatching) {
            logger.info("Table watch stopped: " + reason);
        }
    }

    /** One table-watch tick: re-read the table, push a table_list when it changed. */
    private void tableWatchTick() {
        UUID tableId = watchedTableId;
        SessionImpl session = xmageSession;
        WebProxyClient client = proxyClient;
        if (tableId == null || session == null || client == null || !session.isConnected()) {
            return;
        }
        try {
            UUID roomId = session.getMainRoomId();
            if (roomId == null) {
                return;
            }
            Collection<TableView> tables = session.getTables(roomId);
            TableView watched = null;
            if (tables != null) {
                for (TableView candidate : tables) {
                    if (tableId.equals(candidate.getTableId())) {
                        watched = candidate;
                        break;
                    }
                }
            }
            if (watched == null) {
                // A miss is NOT proof the table is gone: the watch is armed at create time and
                // the creator's own seat join lands milliseconds later on another thread, so the
                // first tick can read a list that predates the table. Tolerate a few misses
                // before shutting down (measured live 2026-09-17: a 66 ms miss killed the watch
                // outright, leaving the owner dependent on the throttled page timer — the very
                // bug this watch exists to fix).
                tableWatchMisses++;
                if (!TableWatchDecision.shouldStopAfterMiss(tableWatchMisses)) {
                    logger.info("Table watch miss " + tableWatchMisses + "/"
                            + TableWatchDecision.MISS_TOLERANCE + " for tableId=" + tableId
                            + " — table not in this list yet, retrying");
                    return;
                }
                stopTableWatch("watched table " + tableId + " is gone (" + tableWatchMisses
                        + " consecutive misses)");
                return;
            }
            tableWatchMisses = 0;
            TableState state = watched.getTableState();
            if (state == null) {
                // Fail Fast: the RPC returned a table with no state, so there is no meaningful
                // change to compare. Skip this tick and say so — the next one re-reads.
                logger.error("Table watch read tableId=" + tableId + " with null state — skipping tick");
                return;
            }
            // Candidate 1 (non-seated owner cannot start match): BEFORE the signature-diff
            // early-return, so a table the pending flag armed on AFTER a tick had already seen
            // it full still gets its single re-attempt — change detection alone would never
            // fire for a table that reached 2/2 between the arming tick and this one.
            tryPendingAutoStart(session, watched, state);
            String signature = watched.getSeatsInfo() + "|" + state.name();
            TableWatchDecision.Action action = TableWatchDecision.decide(
                    lastWatchedTableSignature, signature, state.name());
            if (action == TableWatchDecision.Action.SKIP) {
                return; // nothing changed — do not churn the browser
            }
            lastWatchedTableSignature = signature;
            sendToBrowser(client.serializeTableList(tables, resolveOwnedTableIds(session, tables)));
            logger.info("Table watch push: tableId=" + tableId + " state=" + signature);
            if (action == TableWatchDecision.Action.PUSH_STOP) {
                stopTableWatch("match left the waiting state (state=" + state + ")");
            }
        } catch (Throwable t) {
            // Never let a watch failure touch the JBoss callback threads; log and let the
            // next tick retry (the session's own disconnect handling owns hard failures).
            logger.warn("Table watch tick failed for tableId=" + tableId, t);
        }
    }

    /**
     * Candidate 1: fire the owed
     * create-time auto-start, at most ONCE, when the watched table is full and ready.
     *
     * <p>Runs on every watch tick, before the signature-diff early-return (see the call
     * site). A create-time outcome of {@link CreateAutoStartOutcome#SKIPPED_SEATS_OPEN}
     * armed {@link #pendingAutoStartTableId}; this method starts the match in the owner's
     * stead once the human seat has actually filled. Semantics pinned here:
     *
     * <ul>
     *   <li><b>Exactly once</b> — the pending id is cleared BEFORE the attempt, so a slow
     *       or throwing {@code startMatch} cannot double-fire on the next tick. A genuine
     *       failure is NOT retried again either (the error frame has gone out; retrying
     *       forever would spam the browser with toasts every 2 s — the incident's failure
     *       is a missing start, not a failing one).</li>
     *   <li><b>Never with open seats</b> — an empty SeatView (open seat) holds the pending
     *       id armed and logs why the start is still being withheld.</li>
     *   <li><b>No-op off-state</b> — only READY_TO_START fires. WAITING (the normal
     *       pre-fill state — the open seat is still open) keeps the start owed. Any other
     *       state drops the pending id with an INFO line (owner started it manually; the
     *       table died; the match already began).</li>
     *   <li><b>Toast semantics preserved</b> — the expected conditions (open seat,
     *       wrong state) stay silent (INFO log, no frame); a genuine {@code startMatch}
     *       failure still logs at ERROR and sends the error frame.</li>
     * </ul>
     *
     * @param session the XMage session this watch reads through (never null here — the
     *                caller checked)
     * @param watched the watched table as just read from the server
     * @param state   the table's state as just read from the server (never null here)
     */
    private void tryPendingAutoStart(SessionImpl session, TableView watched, TableState state) {
        UUID pending = pendingAutoStartTableId;
        if (pending == null || !pending.equals(watched.getTableId())) {
            return; // nothing owed, or owed for a different table
        }
        if (state == TableState.WAITING) {
            // The normal pre-fill state: the deliberately open seat has not been taken yet.
            // Keep the start owed — this is exactly what the pending id is waiting for.
            // (No per-tick log: the watch repeats every 2 s and the arming line already
            // states the plan.)
            return;
        }
        if (state != TableState.READY_TO_START) {
            // The table left the waiting lifecycle while the start was owed: the owner
            // started it manually, it died, or the match already began. Either way the
            // owed start is no longer owed — drop it, say why, stay silent to the browser.
            pendingAutoStartTableId = null;
            logger.info("Dropping pending auto-start for table " + pending
                    + " — table state is now " + state + " (not READY_TO_START)");
            return;
        }
        // Open-seat guard: READY_TO_START with an empty seat must never happen server-side,
        // but the proxy relies on its own read here — a SeatView with no player name is an
        // open seat, and starting over one would be a server reject. Hold the pending id
        // (a later tick may see the seat filled); do NOT consume it.
        if (watched.getSeats() != null) {
            for (mage.view.SeatView seat : watched.getSeats()) {
                if (seat.getPlayerName() == null || seat.getPlayerName().isEmpty()) {
                    logger.info("Pending auto-start for table " + pending
                            + " held — a seat is still open (READY_TO_START with an empty seat"
                            + " is not startable)");
                    return;
                }
            }
        }
        // Consume BEFORE attempting: exactly one re-attempt per table, whatever happens below.
        pendingAutoStartTableId = null;
        UUID roomId = session.getMainRoomId();
        if (roomId == null) {
            // Fail Fast: without the main room id startMatch would be sent with a null room
            // and the server-side table lookup would reject it silently (same gate as
            // handleStartMatch).
            logger.error("Pending auto-start for table " + pending
                    + " cannot fire: getMainRoomId() returned null (connected="
                    + session.isConnected() + ")");
            sendError("Cannot start match: main room unavailable (see proxy logs)");
            return;
        }
        logger.info("Last seat filled — re-attempting auto-start for table " + pending
                + " (create-time start was skipped for an open seat)");
        boolean started;
        try {
            started = session.startMatch(roomId, pending);
        } catch (Throwable t) {
            logger.error("Pending auto-start threw for table " + pending + ". lastError: "
                    + session.getLastError(), t);
            sendError("Failed to start match: " + rawOrEmpty(t.getMessage()));
            return;
        }
        logger.info("startMatch (pending auto-start) returned " + started);
        if (!started) {
            String e = session.getLastError();
            // Genuine failure: the table WAS full and READY_TO_START, the server still said
            // no — surface it exactly like the create-time genuine failure.
            logger.error("Pending auto-start failed for table " + pending
                    + " with all seats occupied and state READY_TO_START. lastError: " + e);
            sendError("Failed to start match: " + (e != null ? e : ""));
            return;
        }
        logger.info("Pending auto-start succeeded — match starting for table " + pending);
    }

    /**
     * Start the watch for whatever table this session is seated at and that has not started
     * yet. Used on connect: a browser reload loses the frontend's own "I am seated" flags,
     * and the watch still has to push seat changes to it.
     *
     * <p>Candidate 2: the same
     * reload also loses the browser's "I created this table" memory, and a NON-SEATED
     * owner has no seat for the scan above to find — but they still own an unstarted
     * table whose seats/state their grid must track (it carries the Start Match
     * control, and Chromium throttles the page's own poll in background tabs). When no
     * seated table matched, arm the watch for an owned in-window table instead — the
     * watch pushes the table list (ownership flag included) on every change, exactly
     * as it does for the seated creator.
     */
    private void startTableWatchForSeatedTable(SessionImpl session) {
        try {
            if (proxyClient == null) {
                return;
            }
            UUID roomId = session.getMainRoomId();
            if (roomId == null) {
                return;
            }
            String playerName = proxyClient.getUsername();
            Collection<TableView> tables = session.getTables(roomId);
            if (tables == null) {
                return;
            }
            for (TableView table : tables) {
                TableState state = table.getTableState();
                if (state != TableState.WAITING && state != TableState.READY_TO_START) {
                    continue;
                }
                if (table.getSeats() == null) {
                    continue;
                }
                for (mage.view.SeatView seat : table.getSeats()) {
                    // A reload returns with the frontend's seated flags gone; the
                    // seat may carry the user's typed seat name, not the username.
                    if (SeatAliases.isCreatorSeat(playerName, seat.getPlayerName())) {
                        startTableWatch(table.getTableId());
                        return;
                    }
                }
            }
            // No seat matched: a reloaded non-seated owner still gets a watch on the
            // table they own (see the javadoc). The cache was just warmed by the
            // connect-time handleGetTables push, so this normally costs no RPC.
            for (UUID ownedId : resolveOwnedTableIds(session, tables)) {
                startTableWatch(ownedId);
                logger.info("Table watch armed for owned (non-seated) table " + ownedId);
                return;
            }
        } catch (Throwable t) {
            logger.warn("Failed to scan for a seated table to watch", t);
        }
    }

    // ── Deck editor: card search + legality (no XMage server required) ──

    /**
     * Handle card_search: run CardCriteria against the local CardRepository.
     * Works against the proxy's own card DB, so it is usable even when the XMage
     * server is unreachable.
     */
    private void handleCardSearch(com.google.gson.JsonObject json) {
        String requestId = json.has("requestId") && !json.get("requestId").isJsonNull()
            ? json.get("requestId").getAsString() : null;
        com.google.gson.JsonObject criteria = json.has("criteria") && json.get("criteria").isJsonObject()
            ? json.getAsJsonObject("criteria") : new com.google.gson.JsonObject();
        try {
            com.google.gson.JsonObject result = CardSearchService.search(criteria);
            com.google.gson.JsonObject resp = new com.google.gson.JsonObject();
            resp.addProperty("type", "card_search_result");
            if (requestId != null) resp.addProperty("requestId", requestId);
            resp.add("result", result);
            sendToBrowser(resp.toString());
        } catch (Throwable t) {
            logger.error("card_search failed", t);
            com.google.gson.JsonObject resp = new com.google.gson.JsonObject();
            resp.addProperty("type", "card_search_result");
            if (requestId != null) resp.addProperty("requestId", requestId);
            resp.addProperty("error", t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            sendToBrowser(resp.toString());
        }
    }

    /**
     * Handle card_lookup: resolve an explicit list of (setCode, cardNumber) ownership keys.
     * This is what the CollectionViewer needs — its localStorage collection IS a key list,
     * so asking by key is exact where a set-only card_search was a guess (see
     * CardSearchService.lookupByKeys).
     */
    private void handleCardLookup(com.google.gson.JsonObject json) {
        String requestId = json.has("requestId") && !json.get("requestId").isJsonNull()
            ? json.get("requestId").getAsString() : null;
        try {
            com.google.gson.JsonElement keysEl = json.get("keys");
            com.google.gson.JsonArray keys = keysEl != null && keysEl.isJsonArray() ? keysEl.getAsJsonArray() : null;
            com.google.gson.JsonObject result = CardSearchService.lookupByKeys(keys);
            com.google.gson.JsonObject resp = new com.google.gson.JsonObject();
            resp.addProperty("type", "card_lookup_result");
            if (requestId != null) resp.addProperty("requestId", requestId);
            resp.add("result", result);
            sendToBrowser(resp.toString());
        } catch (Throwable t) {
            logger.error("card_lookup failed", t);
            com.google.gson.JsonObject resp = new com.google.gson.JsonObject();
            resp.addProperty("type", "card_lookup_result");
            if (requestId != null) resp.addProperty("requestId", requestId);
            resp.addProperty("error", t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            sendToBrowser(resp.toString());
        }
    }

    /**
     * Handle legality_check: run all deck validators over the provided deck and
     * return the legality matrix + EDH power level + bracket details.
     */
    private void handleLegalityCheck(com.google.gson.JsonObject json) {
        String requestId = json.has("requestId") && !json.get("requestId").isJsonNull()
            ? json.get("requestId").getAsString() : null;
        com.google.gson.JsonObject deckJson = json.has("deck") && json.get("deck").isJsonObject()
            ? json.getAsJsonObject("deck") : null;
        try {
            if (deckJson == null) {
                throw new IllegalArgumentException("legality_check missing 'deck' object");
            }
            com.google.gson.JsonObject result = LegalityService.check(deckJson);
            com.google.gson.JsonObject resp = new com.google.gson.JsonObject();
            resp.addProperty("type", "legality_result");
            if (requestId != null) resp.addProperty("requestId", requestId);
            resp.add("result", result);
            sendToBrowser(resp.toString());
        } catch (Throwable t) {
            logger.error("legality_check failed", t);
            com.google.gson.JsonObject resp = new com.google.gson.JsonObject();
            resp.addProperty("type", "legality_result");
            if (requestId != null) resp.addProperty("requestId", requestId);
            resp.addProperty("error", t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            sendToBrowser(resp.toString());
        }
    }

    /**
     * Handle card_versions: return all printings (different arts) of a single card by name.
     * Used by the deck editor's right-click "Change art" action.
     */
    private void handleCardVersions(com.google.gson.JsonObject json) {
        String requestId = json.has("requestId") && !json.get("requestId").isJsonNull()
            ? json.get("requestId").getAsString() : null;
        String cardName = json.has("cardName") && !json.get("cardName").isJsonNull()
            ? json.get("cardName").getAsString() : null;
        try {
            com.google.gson.JsonObject result = CardSearchService.allVersions(cardName);
            com.google.gson.JsonObject resp = new com.google.gson.JsonObject();
            resp.addProperty("type", "card_versions_result");
            if (requestId != null) resp.addProperty("requestId", requestId);
            resp.add("result", result);
            sendToBrowser(resp.toString());
        } catch (Throwable t) {
            logger.error("card_versions failed", t);
            com.google.gson.JsonObject resp = new com.google.gson.JsonObject();
            resp.addProperty("type", "card_versions_result");
            if (requestId != null) resp.addProperty("requestId", requestId);
            resp.addProperty("error", t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            sendToBrowser(resp.toString());
        }
    }

    /**
     * Handle token_versions: the ordered art candidates for a token name, plus the
     * engine's intended default art.
     *
     * `engineSetCode` (optional) is the set code the engine recorded for THIS token
     * (from the source card). It picks which candidate is the default so a Soldier
     * created by a Ravnica card shows a Ravnica Soldier. It is never turned into a URL
     * here or anywhere else — the engine's set is reliable, its image index is not
     * (see TokenArtService's class comment).
     *
     * The one Scryfall name search per token runs on the background pool because it
     * blocks on network; the WebSocket thread stays free. Results are cached per name,
     * so repeat requests (every tile of the same token, later games) cost nothing.
     */
    private void handleTokenVersions(com.google.gson.JsonObject json) {
        String requestId = json.has("requestId") && !json.get("requestId").isJsonNull()
            ? json.get("requestId").getAsString() : null;
        String tokenName = json.has("tokenName") && !json.get("tokenName").isJsonNull()
            ? json.get("tokenName").getAsString() : null;
        String engineSetCode = json.has("engineSetCode") && !json.get("engineSetCode").isJsonNull()
            ? json.get("engineSetCode").getAsString() : null;

        if (tokenName == null || tokenName.isEmpty()) {
            sendError("token_versions requires a non-empty tokenName");
            return;
        }
        if (tokenName.length() > MAX_TOKEN_NAME_LENGTH) {
            sendError("token_versions tokenName too long (max " + MAX_TOKEN_NAME_LENGTH + ")");
            return;
        }

        final String reqId = requestId;
        final String name = tokenName;
        final String setCode = engineSetCode;
        final WebProxySession self = this;
        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                com.google.gson.JsonObject resp = new com.google.gson.JsonObject();
                resp.addProperty("type", "token_versions_result");
                if (reqId != null) resp.addProperty("requestId", reqId);
                try {
                    resp.add("result", TokenArtService.resolve(name, setCode).toJson(name));
                } catch (Throwable t) {
                    logger.error("token_versions failed for '" + name + "'", t);
                    resp.addProperty("error",
                            t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
                }
                self.sendToBrowser(resp.toString());
            }
        });
    }

    /**
     * Handle token_art_preference: a player's chosen art for a token type, relayed to
     * the other players at the same table.
     *
     * A null/empty `url` clears the choice, so the token falls back to the engine's
     * default. The URL is validated against the Scryfall image-host allowlist before
     * it is stored and broadcast — this string reaches every opponent's board, so an
     * unvalidated value would be a cross-player vector.
     *
     * NOT EVERY context has a table to relay to, and none of them is an error: the
     * choice is a VIEWER preference first — the browser applies it to localStorage
     * the moment the user picks (utils/tokenArt.ts) — and sharing it is a bonus
     * that needs a SEATED live game (a gameId to scope the table, a playerId to
     * attribute the choice). The two non-seated contexts:
     *
     *  - a REPLAY is loaded: `replayGameId` is set while `currentGameId` stays
     * null by design (replay ids must never leak into live-game routing). Changing
     *    art during a replay changed the token on screen but
     *    errored with "requires an active game".
     *  - SPECTATING a live game: `currentGameId` is set but `playerId` is null
     *    (the watched GameView carries no "my player").
     *
     * The old code required gameId+playerId and sent an error otherwise — a toast
     * for a message whose local effect had already landed. Now the message is
     * accepted and logged as viewer-local; nothing is recorded and nothing is
     * broadcast (there is no table to broadcast to).
     */
    private void handleTokenArtPreference(com.google.gson.JsonObject json) {
        if (proxyClient == null) {
            sendError("Not connected");
            return;
        }
        String tokenName = json.has("tokenName") && !json.get("tokenName").isJsonNull()
            ? json.get("tokenName").getAsString() : null;
        if (tokenName == null || tokenName.isEmpty() || tokenName.length() > MAX_TOKEN_NAME_LENGTH) {
            sendError("token_art_preference requires a valid tokenName");
            return;
        }
        String url = json.has("url") && !json.get("url").isJsonNull()
            ? json.get("url").getAsString() : null;
        if (url != null && !url.isEmpty() && !isAllowedCardImageUrl(url)) {
            sendError("token_art_preference url is not an allowed card-image host");
            return;
        }

        UUID gameId = proxyClient.getCurrentGameId();
        UUID playerId = proxyClient.getPlayerId();
        if (gameId == null || playerId == null) {
            // No table to relay to (see the class comment above): the browser has
            // already applied the choice locally, so accept it quietly. Logged so
            // a "my art is not reaching the opponent" report can be traced to
            // "sent outside a seated game" instead of being silently invisible.
            logger.info("Token art preference: " + sanitizeForLog(tokenName) + " -> "
                + (url == null || url.isEmpty() ? "(cleared)" : sanitizeForLog(url))
                + " by " + sanitizeForLog(proxyClient.getUsername())
                + " recorded viewer-locally"
                + (proxyClient.getReplayGameId() != null
                    ? " (replay loaded — nothing to relay)"
                    : " (no seated live game — nothing to relay)"));
            return;
        }

        List<Map<String, Object>> snapshot;
        if (url == null || url.isEmpty()) {
            snapshot = TokenArtRelay.clear(gameId, playerId, tokenName);
        } else {
            String setCode = json.has("setCode") && !json.get("setCode").isJsonNull()
                ? json.get("setCode").getAsString() : null;
            String collectorNumber = json.has("collectorNumber") && !json.get("collectorNumber").isJsonNull()
                ? json.get("collectorNumber").getAsString() : null;
            snapshot = TokenArtRelay.record(gameId, playerId, proxyClient.getUsername(),
                    tokenName, url, setCode, collectorNumber);
        }

        // Broadcast to every live session in the SAME game, including the sender (so
        // the sender's own view is authoritatively confirmed rather than assumed).
        com.google.gson.JsonObject msg = new com.google.gson.JsonObject();
        msg.addProperty("type", "token_art_preferences");
        msg.addProperty("gameId", gameId.toString());
        msg.add("preferences", GSON.toJsonTree(snapshot));
        String payload = msg.toString();

        int sent = 0;
        for (WebProxySession s : LIVE_SESSIONS) {
            if (s == null || s.proxyClient == null) continue;
            UUID theirGame = s.proxyClient.getCurrentGameId();
            if (theirGame == null || !theirGame.equals(gameId)) continue;
            if (s.sendToBrowser(payload)) sent++;
        }
        logger.info("Token art preference: " + sanitizeForLog(tokenName) + " -> "
                + (url == null || url.isEmpty() ? "(cleared)" : sanitizeForLog(url))
                + " by " + sanitizeForLog(proxyClient.getUsername())
                + " in game " + gameId + " (" + sent + " session(s) notified)");
    }

    /**
     * Card-image host allowlist for a URL about to be BROADCAST to other players.
     * Mirrors the frontend's `utils/cardImage.ts` allowlist so a preference can never
     * introduce a host the client itself would refuse to render.
     */
    static boolean isAllowedCardImageUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            java.net.URI uri = new java.net.URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            return host.equals("api.scryfall.com")
                    || host.equals("cards.scryfall.io")
                    || host.equals("img.scryfall.io");
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    /**
     * Push a JSON message to the browser's open WebSocket. Returns true only when
     * the message was handed to a live socket — the state channel uses this to
     * decide whether a state version may be consumed (a dropped message must not
     * burn a version; see WebProxyClient.sendGameStateToBrowser).
     *
     * Note this is a "handed to the writer" acknowledgement, not a delivery
     * receipt: Jetty's sendText callback can still fail asynchronously, and the
     * browser may die before rendering. Only the synchronous "no open socket"
     * drop and the throw path report false.
     */
    boolean sendToBrowser(String json) {
        synchronized (wsLock) {
            if (wsSession != null && wsSession.isOpen()) {
                try {
                    wsSession.sendText(json, org.eclipse.jetty.websocket.api.Callback.from(() -> {}, t -> {
                        logger.error("WebSocket sendText failed: " + t.getMessage()
                                + " — message dropped: " + json.substring(0, Math.min(100, json.length())), t);
                        // The socket is dead — stop pumping state into it. Closing
                        // triggers onWebSocketClose → cleanup() → XMage disconnect,
                        // so JBoss callback threads don't keep serializing state for
                        // a browser that's gone. The browser's own liveness probe
                        // will reconnect and get a fresh session.
                        handleSendFailure();
                    }));
                    return true;
                } catch (Throwable e) {
                    logger.error("Error sending to browser: " + e.getMessage(), e);
                    handleSendFailure();
                    return false;
                }
            } else {
                logger.warn("sendToBrowser called but wsSession is " + (wsSession == null ? "null" : "closed") + " — message dropped: " + json.substring(0, Math.min(100, json.length())));
                return false;
            }
        }
    }

    /**
     * Close the dead WebSocket session; onWebSocketClose then runs cleanup().
     * Fix 2: the close is deliberate (the failure itself was already logged at
     * ERROR at the send site), so mark it — the subsequent ClosedChannelException
     * in onWebSocketError is duplication, not news.
     */
    private void handleSendFailure() {
        try {
            if (wsSession != null && wsSession.isOpen()) {
                deliberateClose = true;
                wsSession.close();
            }
        } catch (Throwable t) {
            logger.warn("Error closing dead WebSocket session", t);
        }
    }

    // ── JSON helpers ──

    private static String gsonToJson(Map<String, Object> map) {
        return GSON.toJson(map);
    }

    /**
     * Parse a UUID from a client-supplied string, or send a field-specific error
     * to the browser and return null (malformed UUIDs previously
     * threw IllegalArgumentException up to the top-level catch, leaking the raw
     * exception message and killing the handler with a confusing error).
     */
    private UUID parseUuidOrError(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid UUID in field '" + field + "': " + value);
            sendError("Invalid " + field + " (not a UUID): " + rawOrEmpty(value));
            return null;
        }
    }

    /**
     * Read a required string field or send an error and return null
     * (has() guards so missing fields get explicit errors
     * instead of NullPointerExceptions from the top-level catch).
     */
    private String requireStringOrError(com.google.gson.JsonObject json, String field) {
        if (!json.has(field) || json.get(field).isJsonNull()) {
            sendError("Missing " + field);
            return null;
        }
        return json.get(field).getAsString();
    }

    /**
     * Strip line breaks and ANSI escapes from user-controlled strings before
     * they enter log lines or thread names (log forging /
     * newline injection).
     */
    private static String sanitizeForLog(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\r\\n\\t]", " ")
                .replaceAll("\\x1b\\[[0-9;]*[A-Za-z]", "")
                .trim();
    }

    /**
     * Raw, null-safe value for a STRUCTURED message path (a field that Gson will
     * serialize itself — {@link #sendError}, {@link #sendTypedMessage}, or a
     * {@code msg.put(...)} that {@link #gsonToJson} serializes).
     *
     * <p>Do NOT pre-escape here. {@code GSON.toJson} html-escapes {@code '} as
     * {@code \u0027}; feeding it an already-escaped string double-escapes the
     * backslash, and the browser's {@code JSON.parse} then leaves the literal six
     * characters {@code \u0027} in the toast instead of an apostrophe
     * One serialization
     * by Gson is correct.
     *
     * <p>Null maps to the empty string — the same shape {@link #escapeJson} gave
     * (its callers concatenated the result, so a null used to render as "" and
     * must not start rendering as the text "null").
     */
    private static String rawOrEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * JSON-escape a string by hand (Gson's escaping, minus the surrounding quotes).
     *
     * <p>ONLY for a value embedded in a MANUALLY concatenated JSON literal — the
     * single remaining caller is the legacy-deck error at handleCreateTable
     * ({@code "…\"message\":\"" + escapeJson(x) + "\"}"), which no Gson pass ever
     * touches. Every STRUCTURED path (a Map serialized by {@link #gsonToJson}, or
     * {@link #sendTypedMessage}) must pass the RAW string via {@link #rawOrEmpty}:
     * pre-escaping there double-escapes and leaks the literal text {@code \u0027}
     * into browser toasts.
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        // Use Gson to handle all JSON escaping correctly, then strip surrounding quotes.
        String json = GSON.toJson(s);
        return json.substring(1, json.length() - 1);
    }

    /**
     * Builds the {@link UserData} for the web proxy connection.
     *
     * The constructor takes 16 positional args (UserGroup, int, 4 booleans,
     * UserSkipPrioritySteps, String, 5 booleans, int, 2 booleans, String) — the
     * classic "which false was which?" trap. This helper centralizes the magic
     * values in one named place so the call site reads clearly. The values are
     * tuned for manual step-by-step play (see skipSteps setup above).
     *
     * {@code avatarId}: the browser's chosen avatar, forwarded so the server
     * stores it on the User (Session.setUserData → User.setUserData → the seat's
     * PlayerImpl.userData at TableController join) and every PlayerView carries
     * it. Validated by {@link #normalizeAvatarId} before it gets here — the wire
     * value is clamped to the range the web client actually ships images for.
     *
     * Constructor arg order (see {@code Mage/src/main/java/mage/players/net/UserData.java}):
     *   userGroup, avatarId, allowRequestShowHandCards, confirmEmptyManaPool,
     *   userSkipPrioritySteps, flagName, askMoveToGraveOrder, manaPoolAutomatic,
     *   manaPoolAutomaticRestricted, passPriorityCast, passPriorityActivation,
     *   autoOrderTrigger, autoTargetLevel, useSameSettingsForReplacementEffects,
     *   useFirstManaAbility, userIdStr
     */
    private static UserData buildWebProxyUserData(UserSkipPrioritySteps skipSteps, String userIdStr, int avatarId) {
        // Server-side half: allowRequestShowHandCards is hardcoded
        // true for ALL connections — including pure watchers. That hands every
        // spectator the server-side right to request players' hands. Gate it on
        // -Dwebproxy.allowShowHandCards=true (default true preserves the localhost
        // bot-game workflow where the owner watches their own games; set false
        // for public hosting so watcher connections cannot request hands).
        boolean allowShowHand = !"false".equalsIgnoreCase(
                System.getProperty("webproxy.allowShowHandCards", "true"));
        return new UserData(
            UserGroup.PLAYER,       // userGroup — web client is a player, not spectator-only
            avatarId,               // avatarId — the browser's avatar choice (see normalizeAvatarId)
            allowShowHand,          // allowRequestShowHandCards — see gate above
            false,                  // confirmEmptyManaPool — don't prompt on empty pool
            skipSteps,              // userSkipPrioritySteps — set above for manual stepping
            "world",                // flagName — default flag
            false,                  // askMoveToGraveOrder — don't ask for graveyard order
            true,                   // manaPoolAutomatic — auto-add mana to pool
            true,                   // manaPoolAutomaticRestricted — …restricted to usable colors
            false,                  // passPriorityCast — don't auto-pass after casting
            false,                  // passPriorityActivation — don't auto-pass after activating
            true,                   // autoOrderTrigger — auto-order same-text triggers
            1,                      // autoTargetLevel — basic auto-targeting
            true,                   // useSameSettingsForReplacementEffects
            false,                  // useFirstManaAbility — don't auto-pick first mana ability
            userIdStr               // userIdStr — browserId for shared-IP discrimination
        );
    }

    /**
     * The avatar ids the web client can actually DRAW — the desktop's selectable
     * range plus the computer-seat avatar. Kept as its own list because the proxy
     * must clamp to what is SERVED (`Mage.WebProxy/web/frontend/avatars/`), which
     * is the 10…32 subset plus i64 — the desktop's wider set (special GIFs,
     * server-assigned ids ≥ 1000) has no image in the browser, so an id there
     * would render as the default avatar anyway.
     *
     * Mirrors `Mage.Common/src/main/java/mage/constants/Constants.java:42-44`
     * (MIN 10 / MAX 32 / DEFAULT 10) and `ComputerPlayer`'s avatar 64.
     */
    static final int AVATAR_MIN_ID = 10;
    static final int AVATAR_MAX_ID = 32;
    static final int AVATAR_DEFAULT_ID = 10;

    /**
     * Clamp a browser-supplied avatar id to a drawable one — the web analogue of
     * the desktop's own clamp (PlayerPanelExt.java:361-363) and of
     * PreferencesDialog's stored-value repair (3633-3639). The input is a wire
     * value (a hand-crafted WS frame can carry anything), so an out-of-range or
     * malformed id is repaired to the default rather than refused: the avatar is
     * cosmetic, and a refusal would block a connect the user cannot fix. Fail
     * Fast is preserved where it matters — the caller logs every repair, and
     * nothing downstream ever sees an id without a shipped image.
     */
    static int normalizeAvatarId(int avatarId) {
        if (avatarId < AVATAR_MIN_ID || avatarId > AVATAR_MAX_ID) {
            return AVATAR_DEFAULT_ID;
        }
        return avatarId;
    }

    /**
     * Update MY avatar mid-session.
     * Browser sends: {"type": "set_avatar", "avatarId": 12}
     * Browser receives: {"type": "avatar_set", "avatarId": 12} — the EFFECTIVE id
     * after clamping, so the picker can reflect a repaired value instead of
     * believing a number the server never stored.
     *
     * Desktop parity: PreferencesDialog pushes the new avatar the moment it is
     * picked (`AvatarPanel` click handler → `SessionHandler.updatePreferencesForServer
     * (getUserData())`, PreferencesDialog.java:4009-4011), so a change applies to
     * the running session instead of only the next connect.
     *
     * The rebuilt UserData carries the CURRENT skip steps and browserId forward —
     * a fresh UserData from the connect-time defaults would silently revert every
     * phase stop the user configured (the trap handleUpdatePhaseStops documents).
     *
     * Not player-only: this writes the USER's own preferences, not game state, so
     * a spectator may change its avatar too.
     *
     * Known upstream side effect (recorded, not introduced here): the push goes
     * through {@code SessionImpl.updatePreferencesForServer}, which passes
     * {@code null} for userIdStr, and the server's {@code Session.setUserData}
     * assigns that field unconditionally — so a mid-session preference change
     * blanks the admin-visible {@code User.userIdStr} until the next connect. The
     * desktop client has exactly the same behavior on every preferences change
     * (same call, same nulls); the browserId still rides UserData itself.
     */
    private void handleSetAvatar(com.google.gson.JsonObject json) {
        if (xmageSession == null || connection == null) {
            sendError("Not connected to XMage server");
            return;
        }
        UserData current = connection.getUserData();
        if (current == null) {
            // Fail Fast: the connect path always sets UserData before dialing, so a
            // null here means the session is not in a state this handler understands.
            sendError("Internal error — session has no UserData to update");
            logger.error("set_avatar on a session whose Connection has no UserData");
            return;
        }

        int requested = json.has("avatarId") && !json.get("avatarId").isJsonNull()
                ? json.get("avatarId").getAsInt() : AVATAR_DEFAULT_ID;
        int avatarId = normalizeAvatarId(requested);
        if (avatarId != requested) {
            logger.warn("Repaired out-of-range avatarId " + requested + " to the default " + avatarId);
        }

        // Preserve the live skip steps + userIdStr: only the avatar changes.
        UserData userData = buildWebProxyUserData(
                current.getUserSkipPrioritySteps(), connection.getUserIdStr(), avatarId);
        connection.setUserData(userData);
        sessionAvatarId = avatarId;

        // Push it to the server so every seat's PlayerView carries the new id on
        // the next state update. Surface a failed push instead of swallowing it.
        if (!xmageSession.updatePreferencesForServer(userData)) {
            logger.error("set_avatar: the server did not accept the preference update");
            sendError("Avatar changed for this session, but the server refused the update — it will apply from your next connect");
        } else {
            logger.info("Updated avatar: id=" + avatarId);
        }
        sendAvatarSet(avatarId);
    }

    /** Echo the effective avatar id back to the browser. */
    private void sendAvatarSet(int avatarId) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "avatar_set");
        msg.put("avatarId", avatarId);
        sendToBrowser(gsonToJson(msg));
    }

    // ── LLM Model Fetching ──

    /**
     * Fetches available personality names from the Personality registry so the
     * web UI can show a dropdown instead of free-text for personality/bot name.
     *
     * Browser sends: {"type": "fetch_personalities"}
     * Browser receives: {"type": "personalities_list", "personalities": ["alex", "blake"...]}
     */
    private void handleFetchPersonalities() {
        List<String> names = mage.players.Personality.allNames();
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "personalities_list");
        msg.put("personalities", names);
        sendToBrowser(gsonToJson(msg));
    }

    /**
     * Browser → server: provider response for a pending client-key LLM
     * exchange. The rawResponse is the provider's
     * full JSON body (stream:false is forced in client-key mode). The proxy
     * relays it via SessionImpl.llmDecision → MageServerImpl.llmDecision →
     * LLMBridgeRegistry.complete. No key ever appears here.
     */
    private void handleLlmDecision(com.google.gson.JsonObject json) {
        SessionImpl session = xmageSession;
        if (session == null) {
            sendError("llm_decision rejected — not connected to an XMage server");
            return;
        }
        String requestId = requireStringOrError(json, "requestId");
        if (requestId == null) return;
        String rawResponse = json.has("rawResponse") && !json.get("rawResponse").isJsonNull()
                ? json.get("rawResponse").getAsString() : "";
        if (rawResponse.isEmpty()) {
            sendError("llm_decision rejected — empty rawResponse for requestId " + requestId);
            return;
        }
        String thinking = json.has("thinking") && !json.get("thinking").isJsonNull()
                ? json.get("thinking").getAsString() : null;
        int httpStatus = 0;
        if (json.has("httpStatus") && json.get("httpStatus").isJsonPrimitive()) {
            try { httpStatus = json.get("httpStatus").getAsInt(); } catch (NumberFormatException ignored) { }
        }
        java.util.UUID rid;
        try {
            rid = java.util.UUID.fromString(requestId);
        } catch (IllegalArgumentException e) {
            sendError("llm_decision rejected — malformed requestId: " + requestId);
            return;
        }
        mage.interfaces.LLMPromptPayload.Decision decision =
                new mage.interfaces.LLMPromptPayload.Decision(rid, rawResponse, thinking, httpStatus);
        boolean sent = session.llmDecision(decision);
        if (!sent) {
            sendError("Failed to deliver LLM decision to server (connection down?) — requestId " + requestId);
        }
    }

    /**
     * Browser → server: the browser's provider call failed (HTTP error,
     * network error, CORS refusal). Relayed to the server, which fails the
     * pending future; the seat's normal retry/concede logic takes over.
     */
    private void handleLlmError(com.google.gson.JsonObject json) {
        SessionImpl session = xmageSession;
        if (session == null) {
            sendError("llm_error rejected — not connected to an XMage server");
            return;
        }
        String requestId = requireStringOrError(json, "requestId");
        if (requestId == null) return;
        String message = json.has("message") && !json.get("message").isJsonNull()
                ? json.get("message").getAsString() : "LLM call failed in browser";
        boolean retryable = json.has("retryable") && json.get("retryable").isJsonPrimitive()
                && json.get("retryable").getAsBoolean();
        int httpStatus = 0;
        if (json.has("httpStatus") && json.get("httpStatus").isJsonPrimitive()) {
            try { httpStatus = json.get("httpStatus").getAsInt(); } catch (NumberFormatException ignored) { }
        }
        java.util.UUID rid;
        try {
            rid = java.util.UUID.fromString(requestId);
        } catch (IllegalArgumentException e) {
            sendError("llm_error rejected — malformed requestId: " + requestId);
            return;
        }
        mage.interfaces.LLMPromptPayload.Failure failure =
                new mage.interfaces.LLMPromptPayload.Failure(rid, message, retryable, httpStatus);
        boolean sent = session.llmError(failure);
        if (!sent) {
            sendError("Failed to deliver LLM error to server (connection down?) — requestId " + requestId);
        }
    }

    /**
     * Fetches available LLM models from the given API endpoint so the web UI
     * can show a dropdown instead of a free-text model field. Mirrors the
     * desktop client's LLMConfigDialog.fetchModelsFromEndpoint logic.
     *
     * Browser sends: {"type": "fetch_models", "endpoint": "http://localhost:11434/v1/chat/completions"}
     * Browser receives: {"type": "models_list", "models": ["model1", "model2"...]}
     * or {"type": "models_error", "message": "..."} on failure.
     */
    private void handleFetchModels(com.google.gson.JsonObject json) {
        String endpoint = json.has("endpoint") ? json.get("endpoint").getAsString() : "";
        // Optional API key — sent as Bearer on the OpenAI /v1/models path so the
        // model dropdown works with authenticated cloud providers.
        String apiKey = json.has("apiKey") && !json.get("apiKey").isJsonNull()
                ? json.get("apiKey").getAsString() : "";
        if (endpoint.isEmpty()) {
            // The seat left the Provider field empty (the "(server default)"
            // placeholder): there is no endpoint in this browser to list models
            // from. Say that plainly. The old frontend substituted a fabricated
            // localhost default here, which the host allowlist then rejected while
            // naming the WRONG host — and the rejection text leaked JVM-flag jargon
            // into the setup dialog.
            sendTypedMessage("models_error", "message",
                    "This seat has no model endpoint set — it uses the server's configured"
                    + " default. Set a Provider/endpoint to list its models here.");
            return;
        }

        // SSRF protection:
        // - http/https only
        // - host must be on the explicit allowlist (-Dwebproxy.allowedModelHosts;
        // default localhost,127.0.0.1::1 — the local Ollama use case)
        // - resolved-address checks block private/link-local/CGNAT/benchmark/ULA
        //   ranges (loopback is allowed by the allowlist contract, not implied)
        // - redirects are NOT followed automatically; any Location target is
        //   re-validated through the same path
        // - response body capped (see fetchOllamaModels/fetchOpenAIModels)
        // - requires a connected session when the proxy is auth-gated
        try {
            java.net.URL url = new java.net.URL(endpoint);
            if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol())) {
                sendTypedMessage("models_error", "message", "Only http/https endpoints are allowed");
                return;
            }
            String host = url.getHost();
            // Block cloud metadata endpoints (AWS/GCP/Azure) — the primary SSRF risk
            if ("169.254.169.254".equals(host) || "169.254.170.2".equals(host)
                    || "metadata.google.internal".equals(host)) {
                sendTypedMessage("models_error", "message", "Cloud metadata endpoints are blocked");
                return;
            }
            if (!isAllowedModelHost(host)) {
                // Player-facing text only — this frame renders in the New Game
                // dialog. Naming a JVM flag here ("Configure
                // -Dwebproxy.allowedModelHosts=…") leaks operator jargon into a
                // player's screen; the host IS named (it is the actionable part for
                // a seat whose own endpoint is blocked) and the operator detail is
                // logged instead.
                logger.warn("Rejected fetch_models to unallowed host: " + host
                        + " — add it to the model-endpoint allowlist (-Dwebproxy.allowedModelHosts)"
                        + " to serve this seat's model list");
                sendTypedMessage("models_error", "message",
                        "Could not reach the model list for this provider: host '" + host
                        + "' is blocked on this server. Contact the server operator.");
                return;
            }
        } catch (java.net.MalformedURLException e) {
            sendTypedMessage("models_error", "message", "Invalid endpoint URL: " + e.getMessage());
            return;
        }

        submitBackground("fetch_models", () -> {
            try {
                List<String> models = fetchModelsFromEndpoint(endpoint, apiKey);
                if (models.isEmpty()) {
                    sendTypedMessage("models_error", "message", "No models found at endpoint");
                    return;
                }
                Map<String, Object> msg = new HashMap<>();
                msg.put("type", "models_list");
                msg.put("models", models);
                // Carry the endpoint's thinking-shape
                // classification so the seat form can warn BEFORE the game
                // starts (a strict gateway + thinking params = game-fatal 400
                // at mulligan). Deterministic — the same rule the engine's
                // request builder uses; no extra network call. ollamaClassified
                // = the endpoint receives the thinking{} object; otherwise
                // bare reasoning_effort (strict-classified).
                msg.put("endpoint", endpoint);
                msg.put("ollamaClassified", ThinkingShapeClassifier.isOllamaEndpoint(endpoint));
                sendToBrowser(gsonToJson(msg));
            } catch (Exception e) {
                logger.warn("Failed to fetch models from " + endpoint + ": " + e.getMessage());
                sendTypedMessage("models_error", "message", rawOrEmpty(e.getMessage()));
            }
        });
    }

    /**
     * Model-endpoint host allowlist. Configurable via the
     * "webproxy.allowedModelHosts" system property (comma-separated); defaults
     * to localhost variants so the default local-Ollama workflow keeps working.
     * Unlike the old policy (blocklist + loopback-always-allowed), this is an
     * explicit allowlist: an unlisted host is rejected outright, so the proxy
     * can't be used to port-scan loopback or reach arbitrary internal services.
     */
    static boolean isAllowedModelHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String allowed = System.getProperty("webproxy.allowedModelHosts", "localhost,127.0.0.1,::1");
        for (String h : allowed.split(",")) {
            if (h.trim().equalsIgnoreCase(host)) return true;
        }
        return false;
    }

    /**
     * Query the API endpoint for available models.
     * Tries Ollama /api/tags first, then falls back to OpenAI-compatible /v1/models.
     * The API key is sent as a Bearer header on the OpenAI path (cloud providers
     * reject unauthenticated /v1/models with 401/403). Returns an empty list on
     * any failure.
     */
    private static List<String> fetchModelsFromEndpoint(String endpoint, String apiKey) throws Exception {
        List<String> models = new ArrayList<>();
        // Derive base URL from the chat completions endpoint
        String baseUrl = endpoint;
        if (baseUrl.endsWith("/v1/chat/completions")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - "/v1/chat/completions".length());
        } else if (baseUrl.endsWith("/v1")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - "/v1".length());
        } else {
            baseUrl = baseUrl.replaceAll("/v1/chat/completions$", "").replaceAll("/v1$", "");
        }

        // Try Ollama /api/tags first (only if the endpoint looks like Ollama)
        if (endpoint.contains("ollama") || endpoint.contains("11434")) {
            models = fetchOllamaModels(baseUrl + "/api/tags");
            if (!models.isEmpty()) return models;
        }

        // Fall back to OpenAI-compatible /v1/models
        models = fetchOpenAIModels(baseUrl + "/v1/models", apiKey);
        return models;
    }

    /**
     * Open an HTTP connection to a model-list endpoint with SSRF protection
     * Resolves the hostname and rejects internal addresses, then
     * connects to the RESOLVED IP so a rebinding hostname can't redirect the
     * connection to an internal service after validation.
     *
     * Loopback IS allowed here: the caller (handleFetchModels) has already
     * enforced the host allowlist, and the default local-Ollama workflow
     * (http://localhost:11434) requires it. Everything else internal is blocked:
     * link-local (incl. cloud metadata 169.254.0.0/16), site-local (RFC1918),
     * CGNAT 100.64.0.0/10, benchmark 198.18.0.0/15, IPv6 ULA fc00::/7,
     * multicast, and any-local.
     *
     * Redirects are NOT followed (setInstanceFollowRedirects(false)). The
     * response-reading loops below treat 3xx as an error, and a Location target
     * must pass this same validation before it would ever be opened.
     */
    private static java.net.HttpURLConnection openValidatedConnection(java.net.URI uri) throws Exception {
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("Endpoint URL has no host: " + uri);
        }
        java.net.InetAddress addr = java.net.InetAddress.getByName(host);
        if (isBlockedInternalAddress(addr)) {
            throw new IllegalArgumentException("Endpoint host '" + host + "' resolves to a non-public address ("
                    + addr.getHostAddress() + ") — blocked");
        }
        // Connect to the resolved IP for plain HTTP (full rebinding protection).
        // For HTTPS, connect to the ORIGINAL hostname — TLS SNI and certificate
        // validation require it; the IP check above still blocks rebinding to
        // internal ranges, and the residual swap window is milliseconds.
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            java.net.URI pinned = new java.net.URI(uri.getScheme(), uri.getUserInfo(),
                    addr.getHostAddress(), uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment());
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) pinned.toURL().openConnection();
            conn.setRequestProperty("Host", host + (uri.getPort() > 0 ? ":" + uri.getPort() : ""));
            conn.setInstanceFollowRedirects(false);
            return conn;
        }
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) uri.toURL().openConnection();
        conn.setInstanceFollowRedirects(false);
        return conn;
    }

    /**
     * Internal-address blocklist. Loopback is NOT blocked here —
     * the host allowlist at the entry point decides loopback; everything else
     * private/special is rejected.
     */
    private static boolean isBlockedInternalAddress(java.net.InetAddress addr) {
        if (addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            int o0 = b[0] & 0xFF, o1 = b[1] & 0xFF;
            // CGNAT 100.64.0.0/10 (100.64–127)
            if (o0 == 100 && o1 >= 64 && o1 <= 127) return true;
            // Benchmark/testing 198.18.0.0/15 (198.18–19)
            if (o0 == 198 && (o1 == 18 || o1 == 19)) return true;
            return false;
        }
        if (b.length == 16) {
            // IPv6 unique local fc00::/7
            if ((b[0] & 0xFE) == 0xFC) return true;
            // IPv4-mapped ::ffff:0:0/96 — re-check the embedded v4 address
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) { mapped = false; break; }
            }
            if (mapped && b[10] == (byte) 0xFF && b[11] == (byte) 0xFF) {
                byte[] v4 = { b[12], b[13], b[14], b[15] };
                try {
                    return isBlockedInternalAddress(java.net.InetAddress.getByAddress(v4));
                } catch (java.net.UnknownHostException impossible) {
                    return true; // 4-byte array is always a valid v4 address
                }
            }
            return false;
        }
        // Unknown address family — treat as blocked (Fail Fast).
        return true;
    }

    /** Max bytes read from a model-list endpoint (heap exhaustion guard). */
    private static final int MODEL_FETCH_MAX_BYTES = 256 * 1024;

    private static List<String> fetchOllamaModels(String url) throws Exception {
        List<String> models = new ArrayList<>();
        java.net.URI uri = java.net.URI.create(url);
        java.net.HttpURLConnection conn = openValidatedConnection(uri);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            if (status >= 300 && status < 400) {
                throw new IllegalStateException("Endpoint returned redirect HTTP " + status
                        + " — redirects are not followed for model fetching");
            }
            conn.disconnect();
            return models;
        }
        try (java.io.InputStream is = conn.getInputStream();
             java.io.BufferedReader br = new java.io.BufferedReader(
                 new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
                if (sb.length() > MODEL_FETCH_MAX_BYTES) {
                    throw new IllegalStateException("Model list response exceeds " + MODEL_FETCH_MAX_BYTES + " bytes — aborting");
                }
            }
            conn.disconnect();

            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(sb.toString()).getAsJsonObject();
            if (root.has("models")) {
                for (com.google.gson.JsonElement el : root.getAsJsonArray("models")) {
                    com.google.gson.JsonObject m = el.getAsJsonObject();
                    if (m.has("name")) {
                        models.add(m.get("name").getAsString());
                    }
                }
            }
        }
        return models;
    }

    private static List<String> fetchOpenAIModels(String url, String apiKey) throws Exception {
        List<String> models = new ArrayList<>();
        java.net.URI uri = java.net.URI.create(url);
        java.net.HttpURLConnection conn = openValidatedConnection(uri);
        conn.setRequestMethod("GET");
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        }
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            if (status >= 300 && status < 400) {
                throw new IllegalStateException("Endpoint returned redirect HTTP " + status
                        + " — redirects are not followed for model fetching");
            }
            conn.disconnect();
            return models;
        }
        try (java.io.InputStream is = conn.getInputStream();
             java.io.BufferedReader br = new java.io.BufferedReader(
                 new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
                if (sb.length() > MODEL_FETCH_MAX_BYTES) {
                    throw new IllegalStateException("Model list response exceeds " + MODEL_FETCH_MAX_BYTES + " bytes — aborting");
                }
            }
            conn.disconnect();

            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(sb.toString()).getAsJsonObject();
            if (root.has("data")) {
                for (com.google.gson.JsonElement el : root.getAsJsonArray("data")) {
                    com.google.gson.JsonObject m = el.getAsJsonObject();
                    if (m.has("id")) {
                        models.add(m.get("id").getAsString());
                    }
                }
            }
        }
        return models;
    }

    /**
     * The thinking dialect a web-created seat will run with. Web seats cannot
     * override the dialect (LLMPlayerConfig has no dialect field), so a seat
     * runs the SERVER default — the same property LLMPlayer.init reads for
     * table seats ({@code xmage.llm.thinking_dialect}; table seats are always
     * constructed with isSecondLlm=false, so the llm2.* variant never applies
     * to them). Unset means the engine's own default, "effort".
     */
    static String serverDefaultThinkingDialect() {
        String v = System.getProperty("xmage.llm.thinking_dialect");
        return v != null && !v.isEmpty() ? v : "effort";
    }

    /**
     * Emit a SETUP-TIME {@code llm_setup_warning} for every LLM seat whose
     * thinking shape is classified strict (non-Ollama endpoint + effort
     * dialect → bare reasoning_effort) while thinking is enabled. Non-fatal by
     * design — the game is NOT blocked; the user is warned in the setup dialog
     * before it dies at mulligan. Deterministic classification only: no
     * network call, no token burn.
     */
    private void sendThinkingShapeWarnings(String opName, List<SeatConfig> seatConfigs) {
        String dialect = serverDefaultThinkingDialect();
        for (SeatConfig sc : seatConfigs) {
            if (sc.playerType != PlayerType.COMPUTER_LLM || sc.llmConfig == null) {
                continue;
            }
            boolean thinkingEnabled = sc.llmConfig.isThinkingEnabled() == null
                    || sc.llmConfig.isThinkingEnabled();
            String endpoint = sc.llmConfig.getApiEndpoint() == null ? "" : sc.llmConfig.getApiEndpoint();
            if (ThinkingShapeClassifier.isStrictShape(endpoint, dialect, thinkingEnabled)) {
                String warning = ThinkingShapeClassifier.strictWarning(sc.name, sc.llmConfig.getModel());
                logger.warn(opName + " thinking-shape warning for seat '" + sc.name + "': " + warning);
                Map<String, Object> msg = new HashMap<>();
                msg.put("type", "llm_setup_warning");
                msg.put("seat", sc.name);
                msg.put("message", warning);
                sendToBrowser(gsonToJson(msg));
            }
        }
    }

    /**
     * Parses an LLM player config from the JSON sent by the frontend setup UI.
     *
     * Extracted from handleCreateTable — the inline version was ~50 lines of
     * repetitive {@code if (lcfg.has(...))} calls. This helper keeps the field
     * mapping in one place so adding a new config field only touches this method
     * (plus the frontend form). Note: empty strings are skipped (server default
     * applies), and {@code modelContextWindow: 0} means "auto-detect" so it's
     * only applied when > 0.
     */
    static LLMPlayerConfig parseLLMConfig(com.google.gson.JsonObject lcfg) {
        LLMPlayerConfig c = new LLMPlayerConfig();
        // ── Core ──
        if (lcfg.has("model") && !lcfg.get("model").getAsString().isEmpty())
            c.setModel(lcfg.get("model").getAsString());
        if (lcfg.has("apiEndpoint") && !lcfg.get("apiEndpoint").getAsString().isEmpty()) {
            // Seat-endpoint SSRF guard (public hosting): the per-seat endpoint is
            // client-controlled and the server-side seat will send real HTTP
            // requests to it (chat calls — and any Bearer key the seat carries).
            // Apply the same explicit host allowlist as fetch_models here at the
            // browser→proxy boundary, so an unallowed host never reaches
            // LLMPlayer.applyOverrides. No silent fallback — reject the whole
            // create_table request.
            String ep = lcfg.get("apiEndpoint").getAsString();
            String host;
            try {
                java.net.URL u = new java.net.URL(ep);
                if (!"http".equals(u.getProtocol()) && !"https".equals(u.getProtocol())) {
                    throw new IllegalArgumentException("only http/https endpoints are allowed");
                }
                host = u.getHost();
            } catch (java.net.MalformedURLException e) {
                throw new IllegalArgumentException("invalid seat apiEndpoint URL: " + ep);
            }
            if ("169.254.169.254".equals(host) || "169.254.170.2".equals(host)
                    || "metadata.google.internal".equals(host)) {
                throw new IllegalArgumentException("cloud metadata endpoints are blocked");
            }
            if (!isAllowedModelHost(host)) {
                throw new IllegalArgumentException("LLM endpoint host '" + host
                        + "' is not in the model-endpoint allowlist (-Dwebproxy.allowedModelHosts)");
            }
            c.setApiEndpoint(ep);
        }
        if (lcfg.has("apiKey") && !lcfg.get("apiKey").getAsString().isEmpty())
            c.setApiKey(lcfg.get("apiKey").getAsString());
        // Client-key bridge: the browser keeps the
        // key; the seat runs in browser-key mode. Rejected together with apiKey
        // below — a seat cannot carry both (ambiguity about which key the
        // provider call would use).
        if (lcfg.has("useClientKey")) {
            boolean uck = lcfg.get("useClientKey").getAsBoolean();
            if (uck && c.getApiKey() != null && !c.getApiKey().isEmpty()) {
                throw new IllegalArgumentException("seat cannot set both apiKey and useClientKey — "
                        + "useClientKey means the key stays in the browser");
            }
            c.setUseClientKey(uck);
            if (uck) {
                String tag = lcfg.has("clientKeyTag") && !lcfg.get("clientKeyTag").isJsonNull()
                        ? lcfg.get("clientKeyTag").getAsString() : "";
                if (tag.isEmpty()) {
                    throw new IllegalArgumentException("useClientKey seat is missing clientKeyTag — "
                            + "the browser must tag every client-key seat so the prompt can name "
                            + "which locally-stored key to use");
                }
                c.setClientKeyTag(tag);
            }
        }
        if (lcfg.has("reasoningEffort") && !lcfg.get("reasoningEffort").getAsString().isEmpty()) {
            String re = lcfg.get("reasoningEffort").getAsString();
            if (!"default".equals(re)) c.setReasoningEffort(re);
        }
        if (lcfg.has("thinkingEnabled")) c.setThinkingEnabled(lcfg.get("thinkingEnabled").getAsBoolean());
        if (lcfg.has("temperatureEnabled")) c.setTemperatureEnabled(lcfg.get("temperatureEnabled").getAsBoolean());
        if (lcfg.has("temperature") && !lcfg.get("temperature").isJsonNull())
            c.setTemperature(lcfg.get("temperature").getAsDouble());
        if (lcfg.has("maxTokens")) c.setMaxTokens(lcfg.get("maxTokens").getAsInt());
        if (lcfg.has("timeoutSeconds")) c.setTimeoutSeconds(lcfg.get("timeoutSeconds").getAsInt());
        // ── Behavior ──
        if (lcfg.has("streamingEnabled")) c.setStreamingEnabled(lcfg.get("streamingEnabled").getAsBoolean());
        if (lcfg.has("verboseLogging")) c.setVerboseLogging(lcfg.get("verboseLogging").getAsBoolean());
        if (lcfg.has("deckAssessmentEnabled")) c.setDeckAssessmentEnabled(lcfg.get("deckAssessmentEnabled").getAsBoolean());
        if (lcfg.has("concedeEnabled")) c.setConcedeEnabled(lcfg.get("concedeEnabled").getAsBoolean());
        if (lcfg.has("askReason")) c.setAskReason(lcfg.get("askReason").getAsBoolean());
        if (lcfg.has("transcriptDir") && !lcfg.get("transcriptDir").getAsString().isEmpty())
            c.setTranscriptDir(lcfg.get("transcriptDir").getAsString());
        // ── Personality ──
        if (lcfg.has("personalityEnabled")) c.setPersonalityEnabled(lcfg.get("personalityEnabled").getAsBoolean());
        if (lcfg.has("personalityName") && !lcfg.get("personalityName").getAsString().isEmpty())
            c.setPersonalityName(lcfg.get("personalityName").getAsString());
        if (lcfg.has("botName") && !lcfg.get("botName").getAsString().isEmpty())
            c.setBotName(lcfg.get("botName").getAsString());
        // ── Conversation ──
        if (lcfg.has("conversationMaxExchanges")) c.setConversationMaxExchanges(lcfg.get("conversationMaxExchanges").getAsInt());
        if (lcfg.has("conversationMaxHistoryChars")) c.setConversationMaxHistoryChars(lcfg.get("conversationMaxHistoryChars").getAsInt());
        if (lcfg.has("conversationThinkCharsPerMsg")) c.setConversationThinkCharsPerMsg(lcfg.get("conversationThinkCharsPerMsg").getAsInt());
        // ── Summarization ──
        if (lcfg.has("summarizeTriggerChars")) c.setSummarizeTriggerChars(lcfg.get("summarizeTriggerChars").getAsInt());
        if (lcfg.has("summarizeContextRatio")) c.setSummarizeContextRatio(lcfg.get("summarizeContextRatio").getAsDouble());
        // modelContextWindow: 0 means "auto-detect" — only apply explicit > 0 values
        if (lcfg.has("modelContextWindow") && lcfg.get("modelContextWindow").getAsInt() > 0)
            c.setModelContextWindow(lcfg.get("modelContextWindow").getAsInt());
        if (lcfg.has("summarizeTargetChars")) c.setSummarizeTargetChars(lcfg.get("summarizeTargetChars").getAsInt());
        if (lcfg.has("summarizeTargetRatio")) c.setSummarizeTargetRatio(lcfg.get("summarizeTargetRatio").getAsDouble());
        if (lcfg.has("summarizeKeepExchanges")) c.setSummarizeKeepExchanges(lcfg.get("summarizeKeepExchanges").getAsInt());
        return c;
    }

    /**
     * Simple holder for per-seat configuration during table creation.
     */
    private static class SeatConfig {
        final String name;
        final PlayerType playerType;
        final DeckCardLists deck;
        final LLMPlayerConfig llmConfig;

        SeatConfig(String name, PlayerType playerType, DeckCardLists deck, LLMPlayerConfig llmConfig) {
            this.name = name;
            this.playerType = playerType;
            this.deck = deck;
            this.llmConfig = llmConfig;
        }
    }

    // ── JSON send helpers ──

    /** Send an error message to the browser. Uses gson.toJson() for safe serialization. */
    private void sendError(String message) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "error");
        msg.put("message", message);
        sendToBrowser(gsonToJson(msg));
    }

    /**
     * Fail-fast gate for message-dispatch sites: sends a browser error and
     * returns false when the connected server lacks the capability. Unlike
     * {@link #requireServerLlm(String)} (throw-based, for handlers that parse
     * first), this variant never throws into the dispatch switch.
     */
    private boolean requireServerLlmGate(String what) {
        ServerCapabilities caps = serverCapabilities;
        if (caps != null && caps.supportsLlm()) {
            return true;
        }
        logger.warn("Rejected '" + what + "' — connected server has no LLM capability");
        sendError("This XMage server does not support LLM features (" + what + ")");
        return false;
    }

    /**
     * Fail-fast gate for every LLM-carrying request path.
     * Throws when the connected server has no COMPUTER_LLM player type; the
     * caller's handler catch turns it into a browser-visible error. UI hiding
     * is the first gate — this one catches a crafted/buggy client message.
     */
    private void requireServerLlm(String what) {
        ServerCapabilities caps = serverCapabilities;
        if (caps == null || !caps.supportsLlm()) {
            throw new IllegalStateException(
                    "The connected XMage server does not support LLM players — cannot " + what);
        }
    }

    /**
     * Fail-fast gate for the P1 deck-offering request: sends a browser error and
     * returns false when the connected server has no COMPUTER_P1 capability. Never
     * throws into the dispatch switch. Mirrors {@link #requireServerLlmGate}.
     */
    private boolean requireServerP1Gate(String what) {
        ServerCapabilities caps = serverCapabilities;
        if (caps != null && caps.supportsP1()) {
            return true;
        }
        logger.warn("Rejected '" + what + "' — connected server has no P1 capability");
        sendError("This XMage server does not support P1 (Argentum policy-net) seats (" + what + ")");
        return false;
    }

    /**
     * Throw-based P1 gate for handlers that parse first (mirrors
     * {@link #requireServerLlm(String)}); the caller catches and surfaces it.
     */
    private void requireServerP1(String what) {
        ServerCapabilities caps = serverCapabilities;
        if (caps == null || !caps.supportsP1()) {
            throw new IllegalStateException(
                    "The connected XMage server does not support P1 (Argentum policy-net) seats — cannot " + what);
        }
    }

    /**
     * Send a typed message with no payload.
     */
    private void sendTypedMessage(String type) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", type);
        sendToBrowser(gsonToJson(msg));
    }

    /** Send a typed message with a single key-value payload. */
    private void sendTypedMessage(String type, String key, String value) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", type);
        msg.put(key, value);
        sendToBrowser(gsonToJson(msg));
    }

    // ── WebSocket endpoint (annotation-based) ──

    /**
     * Jetty 12 annotation-based WebSocket endpoint.
     * Each WebSocket connection creates a new instance of this class,
     * which owns a WebProxySession for XMage connection management.
     */
    @WebSocket
    public static class WebProxyWebSocketEndpoint {

        private final WebProxySession proxySession;

        public WebProxyWebSocketEndpoint() {
            this.proxySession = new WebProxySession();
        }

        @OnWebSocketOpen
        public void onOpen(Session session) {
            proxySession.onWebSocketConnect(session);
        }

        @OnWebSocketMessage
        public void onMessage(String message) {
            proxySession.onWebSocketText(message);
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason) {
            proxySession.onWebSocketClose(statusCode, reason);
        }

        @OnWebSocketError
        public void onError(Throwable cause) {
            proxySession.onWebSocketError(cause);
        }
    }
}