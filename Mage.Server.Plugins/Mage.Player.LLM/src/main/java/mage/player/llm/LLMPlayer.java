package mage.player.llm;

import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.Mode;
import mage.abilities.Modes;
import mage.abilities.SpellAbility;
import mage.abilities.TriggeredAbility;
import mage.abilities.PlayLandAbility;
import mage.abilities.keyword.EquipAbility;
import mage.abilities.mana.ActivatedManaAbilityImpl;
import mage.Mana;
import mage.abilities.costs.mana.ManaCost;
import mage.cards.Card;
import mage.cards.Cards;
import mage.cards.decks.Deck;
import mage.choices.Choice;
import mage.constants.MultiAmountType;
import mage.constants.Outcome;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.SpellAbilityType;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.GameState;
import mage.game.combat.CombatGroup;
import mage.game.match.Match;
import mage.game.permanent.Permanent;
import mage.player.ai.ComputerPlayer;
import mage.players.PlayableObjectsList;
import mage.player.llm.*;
import mage.players.LLMPlayerConfig;
import mage.players.Personality;
import mage.players.Player;
import mage.players.PlayerImpl;
import mage.players.net.UserData;
import mage.players.net.UserGroup;
import mage.target.Target;
import mage.target.TargetAmount;
import mage.target.TargetCard;
import mage.util.CardUtil;
import mage.util.MultiAmountMessage;
import org.apache.log4j.Logger;

import java.util.*;
import java.io.Serializable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * LLM-driven XMage player. Delegates game decisions (priority, card selections,
 * combat, skips) to an external LLM/API. The LLM retries on transient failures
 * and crashes the game if all retries are exhausted — no silent fallbacks.
 */
public class LLMPlayer extends ComputerPlayer {

    // Replay stability: LLMPlayer instances are embedded in .game replays via
    // LLMExchangeRecord/replayLLMData. Without a pin, every plugin rebuild that
    // changes the class shape silently changes the computed serialVersionUID and
    // orphans every replay written before it. Pinned to the UID the current
    // corpus generation was written with — NOT the older
    // -5570206914330488239 generation, which covered only 4 already-orphaned
    // files. If a field change makes old replays fail to deserialize, that's
    // a deliberate format break — document it in the commit, don't bump the
    // pin silently. A plugin rebuild alone must never change this value.
    private static final long serialVersionUID = -21758999425528155L;

    private static final Logger logger = Logger.getLogger(LLMPlayer.class);

    private LLMConfig config;
    // protected (not private) so test subclasses can inject a stubbed client
    protected transient LLMClient client;
    private transient List<ActionEntry> currentActions;
    private static final int SELF_LOG_MAX = 1000;
    private final List<String> selfLog = new ArrayList<>();
    private final List<String> deckList = new ArrayList<>();  // card names only
    private String deckListFullText = null;  // full card text, for system prompt / sideboarding
    private int lastGameLogSize = 0;  // only show new events since last priority call
    /**
     * Truncation epoch of the log the cursor above was last synced with.
     * A turn rollback / undo truncates the log (GameState.truncateGameLog) but
     * leaves the index cursor past the re-played range — the LLM would be told
     * "no new events" for the rest of the game and never see the re-played
     * turn.
     */
    private int lastGameLogTruncateEpoch = 0;
    private long lastBoardHash = 0L;  // cursor for board-unchanged optimization
    private String gamePlan = null;
    private boolean gamePlanComputed = false;
    private transient String cachedAssessmentPrompt = null;
    private int mulliganCount = 0;
    // Startup probe (unsupported thinking params fail mid-game on the retry
    // ladder). Escape hatch via xmage.llm.skip_thinking_probe=true for sandboxed CI.
    private boolean skipThinkingProbe = false;
    /** xmage.llm.terse_answer_stop — when false, priority/target prompts do NOT
     *  send stop:["\n"] (fallback for endpoints that mishandle stop fields). */
    private boolean terseAnswerStop = true;

    // Replay data: maps GameState index → LLM exchange for replay correlation.
    // Non-transient so it serializes with the Game object into the .game file.
    // Capped at 500 entries via removeEldestEntry to bound memory.
    private static final int REPLAY_LLM_DATA_MAX = 500;
    private final Map<Integer, List<LLMExchangeRecord>> replayLLMData =
            new LinkedHashMap<Integer, List<LLMExchangeRecord>>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, List<LLMExchangeRecord>> eldest) {
                    return size() > REPLAY_LLM_DATA_MAX;
                }
            };

    /**
     * Zones (other than the hand) from which the engine can report a legal land
     * play. GRAVEYARD carries the "you may play lands from your graveyard"
     * effects (Crucible of Worlds, Icetill Explorer, Ramunap Excavator); EXILED
     * covers "you may play that card" effects that target lands (e.g. Ragavan's
     * exiled card, various impulse draws). Both are consulted through
     * {@code getPlayable(game, true, zone, false)}, which applies the engine's
     * own asThough/PLAY_FROM_NOT_OWN_HAND_ZONE approval — the plugin never
     * re-derives the permission rule.
     */
    private static final java.util.List<Zone> NON_HAND_PLAY_ZONES =
            Collections.unmodifiableList(Arrays.asList(Zone.GRAVEYARD, Zone.EXILED));

    private boolean postGameSummaryGenerated = false;
    // Written by the async reflection task, read by the web-UI view: volatile
    // for cross-thread visibility.
    private volatile String postGameReflection = null;  // reflection text for the web UI debug view
    private volatile String matchReview = null;       // post-game review written for sideboarding
    private String revisedStrategy = null;   // gameplan for game 2+, written after sideboarding

    // ── Async post-game reflection ──────────
    // The reflection used to run ON the game thread inside won()/lostForced()/
    // quit() (via generatePostGameSummary). A hung/slow provider then either
    // flipped the recorded result to "Game is a draw" or killed the match before
    // game 2.
    // Reflection runs OFF the game thread (async): match + sideboard proceed
    // immediately; failure = no postgame file, loud log (+ optional UI surface).
    // This resolves the Fail-Fast policy tension: a reflection failure is an
    // explicit, approved degradation
    // (no postgame file + logger.error with the full cause) — it is NEVER
    // match-fatal, and the recorded result is committed before any reflection
    // work is even snapshotted.
    //
    // Bounded single daemon thread + bounded queue: a slow provider cannot block
    // sideboarding or game 2 (they run on the game/sideboard threads), a hung
    // call cannot leak threads (one worker max), and an overflowing queue drops
    // the NEWEST task with a loud error instead of growing without bound.
    private static final int POSTGAME_REFLECTION_QUEUE_CAPACITY = 64;
    private static final java.util.concurrent.ThreadPoolExecutor POSTGAME_REFLECTION_EXECUTOR =
            createPostGameReflectionExecutor();

    private static java.util.concurrent.ThreadPoolExecutor createPostGameReflectionExecutor() {
        java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(
                1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<Runnable>(POSTGAME_REFLECTION_QUEUE_CAPACITY),
                runnable -> {
                    Thread t = new Thread(runnable, "LLM-postgame-reflection");
                    t.setDaemon(true);  // must never keep the JVM alive
                    return t;
                });
        // AbortPolicy (default): submission throws RejectedExecutionException,
        // which generatePostGameSummary turns into a loud, non-fatal degradation.
        return executor;
    }

    private transient volatile java.util.concurrent.Future<?> postGameReflectionFuture;
    // Diagnostic counter (tests + logs). NOT final and NOT an AtomicInteger: this
    // class is Serializable and rides inside .game replays — transient final
    // reference fields deserialize as null. A volatile int is initialized to 0
    // on read-back and is only ever incremented by the single reflection worker
    // (+ schedule-failure paths), so plain ++ is sufficient for a diagnostic.
    private transient volatile int postGameReflectionFailures;

    // --- Constructors ---

    public LLMPlayer(String name, RangeOfInfluence range, int skill) {
        super(name, range);
        init(false);
    }

    public LLMPlayer(String name, RangeOfInfluence range) {
        super(name, range);
        init(false);
    }

    public LLMPlayer(String name, RangeOfInfluence range, int skill, boolean isSecondLlm) {
        super(name, range);
        init(isSecondLlm);
    }

    /**
     * Helper to read an LLM system property, checking xmage.llm2.* first when
     * this player is the second LLM bot, falling back to xmage.llm.*.
     */
    private String llmProp(String suffix, boolean isSecondLlm) {
        if (isSecondLlm) {
            String v = System.getProperty("xmage.llm2." + suffix);
            if (v != null) return v;
        }
        return System.getProperty("xmage.llm." + suffix);
    }

    /**
     * Secret lookup for LLM API keys. Delivery order:
     *   1. system property xmage.llm[2].key   (tests/CI; set via -D)
     *   2. environment LLM1_KEY / LLM2_KEY    (launchers — the preferred path)
     * The env path exists because a -D flag puts the key in the JVM command
     * line, which any local user can read at /proc/<pid>/cmdline (see
     * The second seat mirrors llmProp's
     * fallback-to-the-first-seat order on the env level too.
     */
    private String secretProp(String suffix, boolean isSecondLlm) {
        String v = llmProp(suffix, isSecondLlm);
        if (v != null) return v;
        String env = System.getenv(isSecondLlm ? "LLM2_KEY" : "LLM1_KEY");
        if (env != null && !env.isEmpty()) return env;
        return isSecondLlm ? System.getenv("LLM1_KEY") : null;
    }

    private void init(boolean isSecondLlm) {
        // BotGameHandler sets this system property before createPlayer() for the
        // second LLM bot, so the right xmage.llm2.* props are read at construction
        // time (before applyLLMConfig() can override them).  The factory can't pass
        // a boolean through the plugin classloader boundary, so a system property
        // is the cleanest cross-classloader signal.
        if (!isSecondLlm && "true".equalsIgnoreCase(System.getProperty("xmage.llm.pending_second"))) {
            isSecondLlm = true;
            System.clearProperty("xmage.llm.pending_second");
        }

        this.human = false;
        userData = UserData.getDefaultUserDataView();
        userData.setAvatarId(64);
        userData.setGroupId(UserGroup.COMPUTER.getGroupId());
        userData.setFlagName("computer.png");

        this.config = new LLMConfig();
        if (llmProp("endpoint", isSecondLlm) != null)
            config.apiEndpoint(llmProp("endpoint", isSecondLlm));
        if (llmProp("model", isSecondLlm) != null)
            config.model(llmProp("model", isSecondLlm));
        String apiKey = secretProp("key", isSecondLlm);
        if (apiKey != null)
            config.apiKey(apiKey);
        // Reasoning effort from the start-server.sh -D flag (xmage.llm[2].reasoningeffort,
        // NO underscore in the suffix). Without this read, LLM1_EFFORT / LLM2_EFFORT set
        // in start-server.sh are silently ignored for GUI/web-created games: config keeps
        // the LLMConfig default ("max"), and only the \botgame path (BotGameHandler) honored
        // the property. Model/endpoint/key above are read the same way.
        if (llmProp("reasoningeffort", isSecondLlm) != null)
            config.reasoningEffort(llmProp("reasoningeffort", isSecondLlm));
        if (llmProp("verbose", isSecondLlm) != null)
            config.verboseLogging(Boolean.parseBoolean(llmProp("verbose", isSecondLlm)));
        if (llmProp("transcript_dir", isSecondLlm) != null)
            config.transcriptDir(llmProp("transcript_dir", isSecondLlm));
        if (llmProp("stream", isSecondLlm) != null)
            config.streamingEnabled(Boolean.parseBoolean(llmProp("stream", isSecondLlm)));
        if (llmProp("conversation_max_exchanges", isSecondLlm) != null)
            config.conversationMaxExchanges(Integer.parseInt(llmProp("conversation_max_exchanges", isSecondLlm)));
        if (llmProp("conversation_max_history_chars", isSecondLlm) != null)
            config.conversationMaxHistoryChars(Integer.parseInt(llmProp("conversation_max_history_chars", isSecondLlm)));
        if (llmProp("conversation_think_chars_per_msg", isSecondLlm) != null)
            config.conversationThinkCharsPerMsg(Integer.parseInt(llmProp("conversation_think_chars_per_msg", isSecondLlm)));
        if (llmProp("concede_enabled", isSecondLlm) != null)
            config.concedeEnabled(Boolean.parseBoolean(llmProp("concede_enabled", isSecondLlm)));
        if (llmProp("ask_reason", isSecondLlm) != null)
            config.askReason(Boolean.parseBoolean(llmProp("ask_reason", isSecondLlm)));
        if (llmProp("personality_enabled", isSecondLlm) != null)
            config.personalityEnabled(Boolean.parseBoolean(llmProp("personality_enabled", isSecondLlm)));
        if (llmProp("personality_name", isSecondLlm) != null)
            config.personalityName(llmProp("personality_name", isSecondLlm));
        if (llmProp("bot_name", isSecondLlm) != null)
            config.botName(llmProp("bot_name", isSecondLlm));
        if (llmProp("summarize_trigger_chars", isSecondLlm) != null)
            config.summarizeTriggerChars(Integer.parseInt(llmProp("summarize_trigger_chars", isSecondLlm)));
        if (llmProp("summarize_target_chars", isSecondLlm) != null)
            config.summarizeTargetChars(Integer.parseInt(llmProp("summarize_target_chars", isSecondLlm)));
        if (llmProp("summarize_keep_exchanges", isSecondLlm) != null)
            config.summarizeKeepExchanges(Integer.parseInt(llmProp("summarize_keep_exchanges", isSecondLlm)));
        if (llmProp("model_context_window", isSecondLlm) != null)
            config.modelContextWindow(Integer.parseInt(llmProp("model_context_window", isSecondLlm)));
        if (llmProp("summarize_context_ratio", isSecondLlm) != null)
            config.summarizeContextRatio(Double.parseDouble(llmProp("summarize_context_ratio", isSecondLlm)));
        if (llmProp("summarize_target_ratio", isSecondLlm) != null)
            config.summarizeTargetRatio(Double.parseDouble(llmProp("summarize_target_ratio", isSecondLlm)));
        if (llmProp("reasoning_effort_levels", isSecondLlm) != null)
            config.reasoningEffortLevels(llmProp("reasoning_effort_levels", isSecondLlm));
        if (llmProp("thinking_dialect", isSecondLlm) != null)
            config.thinkingDialect(llmProp("thinking_dialect", isSecondLlm));
        if (llmProp("thinking_budget_tokens", isSecondLlm) != null)
            config.thinkingBudgetTokens(llmProp("thinking_budget_tokens", isSecondLlm));
        if (llmProp("thinking_max_tokens", isSecondLlm) != null)
            config.thinkingMaxTokens(Integer.parseInt(llmProp("thinking_max_tokens", isSecondLlm)));
        if (llmProp("skip_thinking_probe", isSecondLlm) != null)
            skipThinkingProbe = Boolean.parseBoolean(llmProp("skip_thinking_probe", isSecondLlm));
        if (llmProp("terse_answer_stop", isSecondLlm) != null)
            terseAnswerStop = Boolean.parseBoolean(llmProp("terse_answer_stop", isSecondLlm));

        // Cap total output (reasoning + answer) to prevent reasoning loops from
        // exhausting token budget. Without this, thinking models can produce
        // 250K+ chars of reasoning then return an empty answer.
        if (llmProp("max_tokens", isSecondLlm) != null) {
            config.maxTokens(Integer.parseInt(llmProp("max_tokens", isSecondLlm)));
        }

        // Seat-config first: the web/desktop New-Table dialogs attach a per-seat
        // LLMPlayerConfig to every COMPUTER_LLM seat, and PlayerFactory delivers
        // it here BEFORE the context-window detection and the thinking probe below —
        // so those dial the SEAT's endpoint/model, not the server default. A dead
        // server-default endpoint used to fail the whole player creation
        // ("Could not create player ... of type Computer - LLM") even though the
        // seat carried a perfectly reachable endpoint. Same non-null
        // copy semantics as the post-creation applyOverrides() call; client-key validation
        // runs exactly as applyOverrides does it. The post-creation applyOverrides in
        // TableController remains — re-applying the same values is a cheap no-op that
        // keeps non-maintainer paths (raw-WS, plugins) working unchanged.
        mage.players.LLMPlayerConfig pendingConfig = mage.players.LLMPlayerConfig.consumePendingPlayerConfig();
        if (pendingConfig != null) {
            applyConfigFields(pendingConfig);
            logger.info("Applied seat LLM config at creation for " + this.getName()
                    + ": model=" + config.getModel()
                    + " endpoint=" + config.getApiEndpoint()
                    + (config.isUseClientKey() ? " client-key" : ""));
        }

        // Auto-detect model context window from Ollama (overrides manual setting if successful).
        // This is needed for regular table matches and tournament matches that don't go through
        // BotGameHandler. applyOverrides() will re-detect if the model changes via GUI config.
        // Skipped for test subclasses (isTestHarness), matching the thinking probe below: test
        // fixtures apply their endpoint/model AFTER construction, so detecting here would dial
        // LLMConfig's default endpoint (localhost Ollama) and throw "Connection refused" on any
        // host without a local Ollama — silently making the whole LLM test suite host-dependent.
        //
        // Client-key seats: the server has no key,
        // so it cannot probe the endpoint. The browser sends the detected
        // window with the seat config (modelContextWindow must be explicit in
        // client-key mode — enforced Fail-Fast in applyOverrides).
        if (!isTestHarness()) {
            if (config.isUseClientKey()) {
                if (config.getModelContextWindow() <= 0) {
                    throw new IllegalStateException("Client-key seat '" + getName()
                            + "' requires an explicit modelContextWindow — the server cannot "
                            + "auto-detect it without an API key. The web client must send the "
                            + "browser-detected window with the seat config.");
                }
            } else {
                config.fetchModelContextWindow();
            }
        }

        this.client = new LLMClient(config);

        // Startup probe: fail at table join, not at game-2-turn-30, when the
        // endpoint rejects the configured thinking params. The empty-answer retry
        // ladder silently dead-ends on models that don't accept the configured
        // dialect/effort — surfacing that here turns a mid-match crash into a
        // boot-time config error. One call, one word, cheapest tier.
        // Skipped for test subclasses (TestLLMPlayer overrides this off — tests
        // must never make live API calls during construction).
        // Client-key seats: skipped too — the probe would need the bridge, which
        // has no routing context before useDeck() (and the first real call
        // exercises the same path with the seat's full error reporting).
        if (!skipThinkingProbe && !isTestHarness() && !config.isUseClientKey()) {
            probeThinkingSupport();
        }
    }

    /** Test-harness detection hook — overridden by TestLLMPlayer to skip the probe. */
    protected boolean isTestHarness() { return false; }

    /**
     * One 1-token call with the full configured thinking payload. The endpoint
     * either accepts it (we proceed) or rejects it (we crash at startup with the
     * provider's error message, and the user fixes the config). A 2xx with an
     * empty answer is fine — we only care that the params were accepted.
     */
    private void probeThinkingSupport() {
        if (!config.isThinkingEnabled()) return;  // no thinking params to validate
        long t0 = System.currentTimeMillis();
        try {
            // Side-effect-free call: no transcript (initTranscript hasn't run yet),
            // no conversation state. buildRequestBody emits the full thinking
            // payload per config (dialect, effort/budget).
            client.chatNoSideEffects("You are a startup connectivity probe. Reply with one word.", "pong");
            logger.info("LLM thinking probe OK (" + config.getModel() + ", dialect="
                    + config.getThinkingDialect() + ", effort=" + config.getReasoningEffort() + ")"
                    + " in " + (System.currentTimeMillis() - t0) + "ms");
        } catch (Throwable t) {
            throw new RuntimeException("LLM startup thinking probe FAILED for model '"
                    + config.getModel() + "' at " + config.getApiEndpoint()
                    + " — the configured thinking params (dialect=" + config.getThinkingDialect()
                    + ", effort=" + config.getReasoningEffort() + ") are rejected by this endpoint."
                    + " Fix the thinking config, the endpoint, or the model before starting a game."
                    + " Underlying error: " + t.getMessage(), t);
        }
    }

    public LLMPlayer(final LLMPlayer player) {
        super(player);
        this.config = new LLMConfig(player.config);
        this.client = new LLMClient(config);
        this.deckList.addAll(player.deckList);
        // Propagate debug state (conversation history + streaming) from original
        // player's watcher so the next game's debug window picks up post-game analysis.
        this.client.copyDebugState(player.client);
        // Propagate post-game state for match continuity
        this.matchReview = player.matchReview;
        this.postGameReflection = player.postGameReflection;
        this.revisedStrategy = player.revisedStrategy;
        // Carry gameplan forward; gamePlanComputed stays false so ensureGamePlan()
        // re-evaluates (uses revisedStrategy on game 2+, fresh assessment on game 1)
        this.gamePlan = player.gamePlan;
        // INTENTIONAL OMISSION — per-game reset:
        // The following per-game state fields are deliberately NOT copied so each
        // game starts clean (the tournament sub-table flow uses this constructor):
        //   replayLLMData, replaySystemPrompt, selfLog, lastGameLogSize,
        //   lastBoardHash, cachedAssessmentPrompt, postGameSummaryGenerated
        // A future maintainer should NOT expect these to persist across games.
    }

    public LLMConfig getLLMConfig() { return config; }

    /**
     * Copy non-null fields from a seat LLMPlayerConfig onto this player's LLMConfig.
     * Shared by the creation-time path (LLMPlayer.init consumes the pending seat config
     * delivered by PlayerFactory, BEFORE context-window detection / thinking probe) and
     * the post-creation path (applyOverrides). No client reinitialization and no
     * re-detection here — callers own those.
     */
    private void applyConfigFields(mage.players.LLMPlayerConfig overrides) {
        if (overrides.getModel() != null && !overrides.getModel().isEmpty())
            config.model(overrides.getModel());
        if (overrides.getApiEndpoint() != null && !overrides.getApiEndpoint().isEmpty()) {
            // Key-exfiltration guard (public hosting): a per-seat override that changes
            // the endpoint must NOT carry the server's key to the new endpoint. The
            // override's apiKey is applied below ONLY when it is also provided; the
            // server key is cleared here so a blank key field can never redirect the
            // configured secret to an attacker-controlled host. Restoring the server
            // key on a later override requires re-starting with the server config
            // (Fail Fast — no fallback to the old secret).
            String endpointBeforeOverride = config.getApiEndpoint();
            config.apiEndpoint(overrides.getApiEndpoint());
            if (!java.util.Objects.equals(overrides.getApiEndpoint(), endpointBeforeOverride)) {
                config.apiKey("");
            }
        }
        if (overrides.getApiKey() != null && !overrides.getApiKey().isEmpty())
            config.apiKey(overrides.getApiKey());
        // Client-key bridge: when the seat runs in
        // browser-key mode, the server key must not linger in config — the
        // provider call happens in the browser, and a server key present here
        // would be a leak channel if anything ever fell back to sendRequest.
        if (overrides.getUseClientKey() != null) {
            config.useClientKey(overrides.getUseClientKey());
            if (overrides.getUseClientKey()) {
                config.apiKey("");
                if (overrides.getClientKeyTag() == null || overrides.getClientKeyTag().isEmpty()) {
                    throw new IllegalStateException("Client-key seat '" + this.getName()
                            + "' has no clientKeyTag — the browser must tag every client-key seat");
                }
                config.clientKeyTag(overrides.getClientKeyTag());
            }
        }
        if (overrides.getReasoningEffort() != null)
            config.reasoningEffort(overrides.getReasoningEffort());
        if (overrides.getReasoningEffortLevels() != null && !overrides.getReasoningEffortLevels().isEmpty())
            config.reasoningEffortLevels(String.join(",", overrides.getReasoningEffortLevels()));
        if (overrides.isThinkingEnabled() != null)
            config.thinkingEnabled(overrides.isThinkingEnabled());
        if (overrides.getTemperature() != null)
            config.temperature(overrides.getTemperature());
        if (overrides.getTemperatureEnabled() != null)
            config.temperatureEnabled(overrides.getTemperatureEnabled());
        if (overrides.getMaxTokens() != null)
            config.maxTokens(overrides.getMaxTokens());
        if (overrides.getTimeoutSeconds() != null)
            config.timeoutSeconds(overrides.getTimeoutSeconds());

        if (overrides.isStreamingEnabled() != null)
            config.streamingEnabled(overrides.isStreamingEnabled());
        if (overrides.isVerboseLogging() != null)
            config.verboseLogging(overrides.isVerboseLogging());
        if (overrides.isDeckAssessmentEnabled() != null)
            config.deckAssessmentEnabled(overrides.isDeckAssessmentEnabled());
        if (overrides.isConcedeEnabled() != null)
            config.concedeEnabled(overrides.isConcedeEnabled());
        if (overrides.isAskReason() != null)
            config.askReason(overrides.isAskReason());

        if (overrides.isPersonalityEnabled() != null)
            config.personalityEnabled(overrides.isPersonalityEnabled());
        if (overrides.getPersonalityName() != null)
            config.personalityName(overrides.getPersonalityName());
        if (overrides.getBotName() != null)
            config.botName(overrides.getBotName());
        if (overrides.getTranscriptDir() != null && !overrides.getTranscriptDir().isEmpty())
            config.transcriptDir(overrides.getTranscriptDir());

        if (overrides.getConversationMaxExchanges() != null)
            config.conversationMaxExchanges(overrides.getConversationMaxExchanges());
        if (overrides.getConversationMaxHistoryChars() != null)
            config.conversationMaxHistoryChars(overrides.getConversationMaxHistoryChars());
        if (overrides.getConversationThinkCharsPerMsg() != null)
            config.conversationThinkCharsPerMsg(overrides.getConversationThinkCharsPerMsg());

        if (overrides.getSummarizeTriggerChars() != null)
            config.summarizeTriggerChars(overrides.getSummarizeTriggerChars());
        if (overrides.getSummarizeContextRatio() != null)
            config.summarizeContextRatio(overrides.getSummarizeContextRatio());
        if (overrides.getModelContextWindow() != null)
            config.modelContextWindow(overrides.getModelContextWindow());
        if (overrides.getSummarizeTargetChars() != null)
            config.summarizeTargetChars(overrides.getSummarizeTargetChars());
        if (overrides.getSummarizeTargetRatio() != null)
            config.summarizeTargetRatio(overrides.getSummarizeTargetRatio());
        if (overrides.getSummarizeKeepExchanges() != null)
            config.summarizeKeepExchanges(overrides.getSummarizeKeepExchanges());
    }

    /**
     * Apply per-player LLM config overrides from the GUI's LLMConfigDialog.
     * Only non-null fields override the current config. Reinitializes the
     * LLMClient so the new settings take effect immediately.
     */
    public void applyOverrides(mage.players.LLMPlayerConfig overrides) {
        if (overrides == null) return;

        // Snapshot model/endpoint before applying overrides — used below to
        // decide whether the context window needs re-detection.
        String modelBefore = config.getModel();
        String endpointBefore = config.getApiEndpoint();

        applyConfigFields(overrides);


        // Reinitialize the client with the updated config.
        this.client = new LLMClient(config);

        // Re-detect the context window ONLY when the override changed the model
        // or endpoint. Re-running detection for an unchanged config is a
        // redundant failure mode: it can throw (Fail Fast on non-Ollama
        // endpoints without an explicit window) and kill the table join even
        // though the constructor already detected a valid window for this exact
        // model/endpoint pair.
        // Client-key seats: the server cannot probe (no key). The explicit
        // window from the seat config is authoritative; a changed model in
        // client-key mode without a fresh explicit window fails at the next
        // chat call through the bridge, with the provider's own error.
        if (!java.util.Objects.equals(config.getModel(), modelBefore)
                || !java.util.Objects.equals(config.getApiEndpoint(), endpointBefore)) {
            if (config.isUseClientKey()) {
                if (config.getModelContextWindow() <= 0) {
                    throw new IllegalStateException("Client-key seat '" + this.getName()
                            + "' changed model/endpoint without an explicit modelContextWindow — "
                            + "the server cannot auto-detect the window in client-key mode. "
                            + "The web client must send the browser-detected window.");
                }
            } else {
                config.fetchModelContextWindow();
            }
        }

        logger.info("Applied LLM config overrides for " + this.getName()
                + ": model=" + config.getModel()
                + " endpoint=" + config.getApiEndpoint()
                + " streaming=" + config.isStreamingEnabled());
    }

    @Override
    public boolean isStreaming() {
        return client != null && client.isStreamingInProgress();
    }

    /**
     * LLM seat: this player answers real decision prompts. Cards that stop a
     * "you may repeat any number of times" loop for plain AI must NOT truncate
     * an LLM seat — the model can decline, so the chain is bounded by its own
     * decisions (see {@code AdNauseam}, {@code LimDulsVault}).
     */
    @Override
    public boolean isLlmControlled() {
        return true;
    }

    /**
     * Build debug data for the client-side LLM conversation viewer.
     * Returns null if the client has no conversation history yet.
     */
    public mage.view.LLMConversationView getLLMDebugData() {
        if (client == null) return null;
        java.util.List<LLMClient.ConversationMessage> history = client.getConversationHistory();

        // Return null only if there's no history AND no streaming in progress
        if (history.isEmpty() && !client.isStreamingInProgress()) return null;

        // Fail Fast: reaching here with a non-null client but null config/model is a bug.
        // (client == null already returns early above; a non-null client implies config
        // was set, so a null config or model here indicates a real initialization failure.)
        if (config == null || config.getModel() == null) {
            throw new IllegalStateException("getLLMDebugData: config or model is null (config="
                    + config + ", model=" + (config != null ? config.getModel() : null) + ")");
        }
        String shortName = config.getModel().replaceAll("[:/].*", "");
        mage.view.LLMConversationView view = new mage.view.LLMConversationView(
                getName(), shortName, history.size() / 2, client.getTotalContextChars(),
                client.getModelContextWindow());

        // System prompt first
        String sysPrompt = client.getConversationSystemPrompt();
        if (sysPrompt != null) {
            view.messages.add(new mage.view.LLMConversationView.LLMMessageView("system", sysPrompt));
        }

        for (LLMClient.ConversationMessage msg : history) {
            mage.view.LLMConversationView.LLMMessageView mview =
                    new mage.view.LLMConversationView.LLMMessageView(
                            msg.role, msg.effectiveContent());
            mview.promptType = msg.promptType;
            mview.turn = msg.turn;
            mview.phaseStep = msg.phaseStep;
            mview.compactSummary = msg.condensed;
            view.messages.add(mview);
        }

        // Attach streaming state
        if (client.isStreamingInProgress()) {
            view.streaming = true;
            view.streamingReasoning = client.getStreamingReasoning();
            view.streamingContent = client.getStreamingContent();
            view.streamingStartedAt = client.getStreamingStartedAt();
        }

        // Attach pre-game gameplan and post-game reflection for the web UI
        view.gamePlan = gamePlan;
        view.postGameReflection = postGameReflection;
        view.matchReview = matchReview;

        return view;
    }
    public void setLLMConfig(LLMConfig c) { this.config = c; this.client = new LLMClient(c); }
    public void reconnect() { this.client = new LLMClient(config); }

    /**
     * Get the replay LLM data map (state index → list of exchanges at that state).
     * Used by GameController.saveGame() to serialize into the .game replay file.
     */
    public Map<Integer, List<LLMExchangeRecord>> getReplayLLMData() {
        return replayLLMData;
    }

    /**
     * The system prompt the conversation channel currently runs with (decklist
     * + sideboard +, after deck assessment, the gameplan). Serialized into the
     * .game replay so the replay viewer's LLM tab shows the same pre-game
     * block the live tab shows — a replay-time reconstruction would need the
     * old prompt template and exact deck serialization and is deliberately not
     * attempted (no-reconstruction rule). Set in useDeck(), refreshed in
     * ensureGamePlan().
     */
    private String replaySystemPrompt = null;

    /** The system prompt snapshot for the replay viewer (null on old replays). */
    public String getReplaySystemPrompt() {
        return replaySystemPrompt;
    }

    /**
     * Between-games text the live LLM tab shows as sections and the replay
     * viewer must show too. gamePlan/matchReview are set on the game thread
     * (assessment/sideboard); postGameReflection on the async post-game
     * executor — all volatile, and buildReplayViews only reads them after a
     * save/deserialize boundary (so a mid-write read can only ever miss the
     * not-yet-written text, never see torn state).
     */
    public String getGamePlan() { return gamePlan; }

    public String getMatchReview() { return matchReview; }

    public String getPostGameReflection() { return postGameReflection; }

    /**
     * Wrapper for client.chatConversation that records the exchange keyed to the
     * current GameState index for replay correlation. All LLM prompt calls should
     * go through this method instead of calling client.chatConversation directly.
     *
     * @param game           the current game
     * @param prompt         the full prompt text sent to the LLM
     * @param compactSummary short situation summary stored in conversation history
     * @param promptType     type of prompt ("priority", "attackers", "blockers", "target", etc.)
     * @return LLMResponse or null on failure
     */
    private static final int LLM_MAX_RETRIES = 5;
    private static final long LLM_RETRY_DELAY_MS = 2000;

    // ── Answer format validators ──────────────────────────────────────
    // These check whether the LLM's answer is in the expected format (just a
    // number, just yes/no, etc.) without extra reasoning text. If validation
    // fails, chatAndRecord re-requests a clean answer.

    /** Expects a single non-negative integer (e.g. "3" or "0"), optionally
     *  with "action:" prefix and/or " mana:..." suffix for mana plans.
     *  No other text allowed.
     *  The mana-plan separator accepts both the documented space form
     *  ("3 mana:L1,L2") and the colon form some models produce
     *  ("3:mana:L1,L2" — the colonified spelling of the same answer).
     *  Both must validate so
     *  the plan is PARSED, not silently dropped: parseManaPlan reads the
     *  "mana:" suffix wherever it appears on the first line. Anything that
     *  looks like a mana plan but doesn't match one of these two exact
     *  spellings FAILS validation (correction path) — never a silent
     *  degrade to no-plan. */
    private static final java.util.function.Predicate<String> ACTION_NUMBER_VALIDATOR = answer -> {
        if (answer == null || answer.isEmpty()) return false;
        String trimmed = answer.trim();
        // Strip optional "action:" prefix
        if (trimmed.toLowerCase().startsWith("action:")) {
            trimmed = trimmed.substring(7).trim();
        }
        String lower = trimmed.toLowerCase();
        // Mana-plan suffix: "N mana:..." (space form) or "N:mana:..." (colon form).
        int manaIdx = lower.indexOf("mana:");
        if (manaIdx < 0) {
            return trimmed.matches("\\d+");
        }
        if (manaIdx == 0) {
            return false;  // "mana:..." with no action number before it
        }
        String sep = trimmed.substring(manaIdx - 1, manaIdx); // char before "mana:"
        if (!sep.equals(" ") && !sep.equals(":")) {
            return false;  // unrecognized mana-plan syntax → fail validation
        }
        String numPart = trimmed.substring(0, manaIdx - 1);
        return numPart.matches("\\d+") && lower.substring(manaIdx).matches("mana:.*");
    };

    /**
     * Range-checked variant of {@link #ACTION_NUMBER_VALIDATOR} for the priority
     * action prompt.
     *
     * <p>
     * The format-only validator accepted ANY digit string, so an out-of-range
     * number (e.g. "7" with 3 actions offered) sailed through validation and
     * only failed at the dispatcher — a FATAL RuntimeException that rolled the
     * turn back. This validator knows the action list the LLM was actually
     * offered, so an out-of-range answer FAILS validation and goes down the
     * existing retry/correction path (chatAndRecord re-prompts with a hint
     * naming the valid range) instead of reaching the dispatcher.
     * <p>
     * The number is extracted with {@link #parseFirstNumber} — the exact same
     * routine the dispatcher uses — so "what validates" and "what gets executed"
     * can never disagree (e.g. "action:12" resolves the same way in both).
     * <p>
     * The call site DOES see the action list: {@code promptAndExecute} builds
     * {@code currentActions} and computes the highest selectable number
     * immediately before calling chatAndRecord, so the bound is passed in as a
     * parameter. (The validator constant itself is a static predicate with no
     * game context — that is why the range form is a per-call instance.)
     */
    public static final class RangedActionNumberValidator implements java.util.function.Predicate<String> {
     /** Number of real (non-skip) actions offered, 1..n. */
     public final int actionCount;
     /** Highest selectable number: n + 4 skip options, +1 more for CONCEDE. */
     public final int maxIndex;

        public RangedActionNumberValidator(int actionCount, boolean concedeEnabled) {
            this.actionCount = actionCount;
            this.maxIndex = actionCount + (concedeEnabled ? 5 : 4);
        }

        @Override
        public boolean test(String answer) {
            if (!ACTION_NUMBER_VALIDATOR.test(answer)) {
                return false;  // wrong format — the format path handles it
            }
            int n = parseFirstNumber(answer);
            return n >= 0 && n <= maxIndex;
        }
    }

    /** Build the range-checked action validator for the current action list. */
    public static RangedActionNumberValidator actionNumberInRange(int actionCount, boolean concedeEnabled) {
        return new RangedActionNumberValidator(actionCount, concedeEnabled);
    }

    /** Expects a single integer (e.g. "3" or "1"), no other text. */
    private static final java.util.function.Predicate<String> SIMPLE_NUMBER_VALIDATOR = answer -> {
        if (answer == null || answer.isEmpty()) return false;
        return answer.trim().matches("-?\\d+");
    };

    /** Expects "yes" or "no" (case-insensitive), no other text. */
    private static final java.util.function.Predicate<String> YES_NO_VALIDATOR = answer -> {
        if (answer == null || answer.isEmpty()) return false;
        String lower = answer.trim().toLowerCase();
        return lower.equals("yes") || lower.equals("no");
    };

    /** Expects "keep" or "mulligan" (case-insensitive), no other text. */
    private static final java.util.function.Predicate<String> KEEP_MULLIGAN_VALIDATOR = answer -> {
        if (answer == null || answer.isEmpty()) return false;
        String lower = answer.trim().toLowerCase();
        return lower.equals("keep") || lower.equals("mulligan");
    };

    /** Expects "1" or "2" (for pile selection), no other text. */
    private static final java.util.function.Predicate<String> PILE_VALIDATOR = answer -> {
        if (answer == null || answer.isEmpty()) return false;
        return answer.trim().matches("[12]");
    };

    /** Expects comma-separated numbers (e.g. "3" or "1,4"), no other text. */
    private static final java.util.function.Predicate<String> COMMA_NUMBERS_VALIDATOR = answer -> {
        if (answer == null || answer.isEmpty()) return false;
        return answer.trim().matches("[\\d,\\s]+");
    };

    /** Validates attacker declarations: "all", "none", or comma-separated
     *  attack entries like "1->player" or "2->pw:Jace, 3->player". */
    private static final java.util.function.Predicate<String> ATTACKERS_VALIDATOR = answer -> {
        if (answer == null || answer.isEmpty()) return false;
        String trimmed = answer.trim();
        if (trimmed.equalsIgnoreCase("all") || trimmed.equalsIgnoreCase("none")) return true;
        // Accept entries like "1->player", "1->pw:Name", comma-separated
        return trimmed.matches("(\\d+\\s*->\\s*(player|pw:.+?)\\s*,?\\s*)+");
    };

    /** Validates blocker declarations: "none", or comma-separated block entries
     *  like "1->2" (blocker_idx->attacker_idx). */
    private static final java.util.function.Predicate<String> BLOCKERS_VALIDATOR = answer -> {
        if (answer == null || answer.isEmpty()) return false;
        String trimmed = answer.trim();
        if (trimmed.equalsIgnoreCase("none")) return true;
        return trimmed.matches("(\\d+\\s*->\\s*\\d+\\s*,?\\s*)+");
    };

    /** Maps each validator to a human-readable description of the expected
     *  answer format, used in correction prompts so the LLM knows the exact
     *  format — not just "remove extra text." */
    /** Package-private→public test hook: the format hint shown in correction prompts. */
    public static String formatHintFor(java.util.function.Predicate<String> validator) {
        if (validator instanceof RangedActionNumberValidator) {
            RangedActionNumberValidator ranged = (RangedActionNumberValidator) validator;
            return "a single number between 0 and " + ranged.maxIndex
                    + " (0 = pass priority, 1-" + ranged.actionCount + " = the listed actions, "
                    + (ranged.actionCount + 1) + "-" + ranged.maxIndex + " = the SKIP/CONCEDE options)";
        }
        if (validator == ACTION_NUMBER_VALIDATOR) return "a single number (e.g. \"3\" or \"0\")";
        if (validator == SIMPLE_NUMBER_VALIDATOR) return "a single number (e.g. \"3\" or \"1\")";
        if (validator == COMMA_NUMBERS_VALIDATOR) return "comma-separated numbers (e.g. \"1,4\" or \"3\")";
        // Parser, validator, and prompt all agree on exactly 'yes'/'no'
        // (narrowing the parser to the prompt resolved it).
        if (validator == YES_NO_VALIDATOR) return "\"yes\" or \"no\"";
        if (validator == KEEP_MULLIGAN_VALIDATOR) return "\"keep\" or \"mulligan\"";
        if (validator == PILE_VALIDATOR) return "\"1\" or \"2\"";
        if (validator == ATTACKERS_VALIDATOR) return "\"all\", \"none\", or attack entries like \"1->player\"";
        if (validator == BLOCKERS_VALIDATOR) return "\"none\" or block entries like \"1->2\"";
        return "the expected format";
    }

    /**
     * Test hook: the validators are private constants, so tests reach them by
     * name. Returns the validator used by chatAndRecord for the given prompt
     * type. Unknown name throws — no silent null default (Fail Fast).
     */
    public static java.util.function.Predicate<String> validatorByName(String name) {
        switch (name) {
            case "action-number": return ACTION_NUMBER_VALIDATOR;
            case "simple-number": return SIMPLE_NUMBER_VALIDATOR;
            case "comma-numbers": return COMMA_NUMBERS_VALIDATOR;
            case "yes-no": return YES_NO_VALIDATOR;
            case "keep-mulligan": return KEEP_MULLIGAN_VALIDATOR;
            case "pile": return PILE_VALIDATOR;
            case "attackers": return ATTACKERS_VALIDATOR;
            case "blockers": return BLOCKERS_VALIDATOR;
            default:
                throw new IllegalArgumentException("No such validator: " + name);
        }
    }

    /**
     * Test hook: the action list the LLM would be offered right now, rendered as
     * descriptions (the same strings buildPrompt prints and index+1 selects).
     * Exposed so in-game tests can assert that an engine-legal action is actually
     * OFFERED (the graveyard-land and alt-cost issues are both about missing or
     * bogus entries in this list).
     */
    public List<String> actionDescriptionsForTest(Game game) {
        List<String> out = new ArrayList<>();
        for (ActionEntry entry : buildActionList(game)) {
            out.add(entry.description);
        }
        return out;
    }

    /**
     * Test hook: record an exchange as the decision path would, so the per-game
     * replay scoping can be exercised without a live model call.
     */
    public void recordReplayExchangeForTest(Game game, int stateIdx, String promptType,
                                            String compactSummary, String answer) {
        recordReplayExchange(game, stateIdx, promptType, compactSummary, null, answer,
                "test-model", game.getTurnNum(), "test/step");
    }

    /** Test hook: game id the current replay bucket belongs to (null = none yet). */
    public UUID replayRecordGameIdForTest() {
        return recordGameId;
    }

    /**
     * Test hook: run the real chatAndRecord accept/reject logic against a stubbed
     * client. Proves that an out-of-range action number fails validation and takes
     * the correction path (a second LLM call with the range in the prompt) rather
     * than reaching the dispatcher.
     */
    public LLMResponse chatAndRecordForTest(Game game, String prompt, String compactSummary,
                                            String promptType,
                                            java.util.function.Predicate<String> validator) {
        return chatAndRecord(game, prompt, compactSummary, promptType, validator);
    }


    /** Strips all leading "Decision:" prefixes that some models add to their
     *  answers. Loops to handle stacked prefixes (e.g. "Decision: Decision: 2"
     *  → "2") that can accumulate when a correction prompt quotes a bad answer
     *  back to the model and the model copies the prefix and adds its own.
     *  Called centrally in chatAndRecord so no validator ever sees a prefix. */
    private static String stripDecisionPrefix(String answer) {
        if (answer == null) return answer;
        String trimmed = answer.trim();
        while (trimmed.toLowerCase().startsWith("decision:")) {
            trimmed = trimmed.substring(9).trim();
        }
        return trimmed;
    }

    /** First line of an answer, trimmed — the line every downstream parser
     *  reads (parseActionNumber, presentTargetChoice, etc.). Splits BEFORE
     *  trimming so a leading blank line yields "" (which no validator
     *  accepts) instead of smuggling line 2 through as the answer. */
    public static String firstLineOf(String answer) {
        if (answer == null) return "";
        return answer.split("\\n", 2)[0].trim();
    }

    /** Extract the stated reason from an answer when askReason is on: the
     *  first line after the answer line, with an optional leading "REASON:"
     *  label stripped (any case). Returns null when there is no second line
     *  or it is empty. Best-effort by contract — callers must treat null as
     *  "no reason given", never as an error. */
    public static String extractStatedReason(String answer) {
        if (answer == null) return null;
        String[] lines = answer.split("\\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase();
            if (lower.startsWith("reason:")) {
                line = line.substring(7).trim();
            }
            return line.isEmpty() ? null : line;
        }
        return null;
    }

    /**
     * Strip the conversation-mimicry reasoning fence from a raw model answer
     * before any card-name parsing.
     * <p>
     * {@code LLMClient.buildAssistantMessage} labels condensed reasoning in
     * conversation history with a {@code [model_reasoning]…[/model_reasoning]}
     * fence. Models mimic that stored format and can emit the fence inside
     * their own answer; a card-name parser splits on {@code [;\n]+}, so a
     * stray fence token would be treated as a card name — observed as
     * "[model_reasoning]" reported back in the large-search retry prompt
     * ("The following card names were not recognized"). Strip the whole
     * fenced block first, then a bare opener cuts the rest of the answer
     * (an unclosed fence means everything after it is reasoning), then any
     * leftover close token. The fence's transcript purpose is unaffected —
     * this only cleans the ANSWER string fed to parsers.
     */
    public static String stripReasoningFence(String answer) {
        if (answer == null) return null;
        // Complete fenced blocks first (non-greedy, DOTALL).
        String out = answer.replaceAll(
                "\\Q" + LLMClient.REASONING_FENCE_OPEN + "\\E[\\s\\S]*?\\Q"
                        + LLMClient.REASONING_FENCE_CLOSE + "\\E", " ");
        // Unclosed opener: everything from it on is reasoning.
        int open = out.indexOf(LLMClient.REASONING_FENCE_OPEN);
        if (open >= 0) {
            out = out.substring(0, open);
        }
        // Any surviving bare close token.
        out = out.replace(LLMClient.REASONING_FENCE_CLOSE, " ");
        return out;
    }

    /**
     * Whether an answer passes validation for accept/reject purposes: the
     * whole string first, then — mirroring the downstream parsers, which
     * read only the first line — the first line alone. First-line acceptance
     * avoids a correction round-trip for answers like "5\nReasoning: ..."
     * (~94% of rejected answers had an already-valid first line; 202 wasted
     * re-prompts per 688 calls ≈ 44% of prompt spend).
     * The full original
     * answer stays in the conversation/replay records; only the accept/reject
     * decision changes.
     */
    public static boolean passesValidation(java.util.function.Predicate<String> validator, String answer) {
        if (validator == null) {
            throw new IllegalArgumentException("passesValidation called with null validator");
        }
        if (validator.test(answer)) return true;
        return validator.test(firstLineOf(answer));
    }

    private LLMResponse chatAndRecord(Game game, String prompt, String compactSummary, String promptType) {
        return chatAndRecord(game, prompt, compactSummary, promptType, null);
    }

    /**
     * Record an LLM exchange for replay correlation.
     * <p>
     * Per-game frame scoping: {@link #replayLLMData} maps GameState indices to
     * exchanges, and
     * those indices restart at 0 for every game in a match because each game
     * gets a fresh {@code GameStates} list. The map is a field of the PLAYER,
     * which the match layer REUSES across games ({@code MatchImpl.initGame} hands
     * the same player object to game 2+), so without a floor an exchange recorded
     * at index 40 of game 1 survives into game 2 where index 40 is a different
     * frame — measured as 46/88 (later 67/291) records matching no frame within
     * ±5.
     * <p>
     * {@code useDeck} clears the map when the engine calls it per game, but that
     * only covers the flows that build a NEW player copy per game (tournament
     * sub-tables). For a reused player the map is drained here on the first write
     * of a new game instead, keyed by game id — so a record can never reference an
     * earlier game's frames.
     */
    private void recordReplayExchange(Game game, int stateIdx, String promptType,
                                      String compactSummary, String thinking, String answer,
                                      String modelShort, int turn, String phaseStep) {
        UUID gameId = game.getId();
        // Per-game scoping: the player object is REUSED across games of a match
        // (MatchImpl.initGame), so a bucket left over from the previous game must
        // be dropped the first time this game records an exchange.
        if (recordGameId != null && !gameId.equals(recordGameId)) {
            logger.info("replayLLMData: new game " + gameId + " (was " + recordGameId + ") — dropping "
                    + replayLLMData.size() + " exchange bucket(s) from the previous game");
            replayLLMData.clear();
        }
        recordGameId = gameId;

        replayLLMData.computeIfAbsent(stateIdx, k -> new ArrayList<>()).add(
                new LLMExchangeRecord(stateIdx, promptType, compactSummary,
                        thinking, answer, getName(), modelShort, turn, phaseStep));
    }

    /**
     * Record a pre-game exchange the in-game recordReplayExchange path never
     * sees: the deck assessment (prompt + gameplan answer) and the game-2+
     * revised-strategy carry-over. These carry their FULL prompt text (there is
     * no game frame to reconstruct it from) and land in the stateIndex-0
     * bucket, which {@code LLMPlayerHelper.buildReplayViews} surfaces to the
     * replay viewer ahead of the turn-1 exchanges. Per-game scoping matches
     * recordReplayExchange: the first pre-game record of a new game drops the
     * previous game's buckets.
     *
     * Threading contract: GAME THREAD ONLY. Unlike the exchange recorder, this
     * method used to have a gameId-keyed async variant (for the sideboard match
     * review) — removed: endGameWithResult writes the .game save
     * BEFORE the async post-game worker runs, so an async preamble record could
     * never reach its own replay, and its clear-on-mismatch fired against
     * whichever game the player had reached by the time the slow call returned
     * (wiping the fresh next-game buckets — a real corruption path in Bo3).
     * Between-games text now reaches replays via serialized fields
     * (matchReview, postGameReflection) that buildReplayViews attaches.
     */
    private void recordReplayPreamble(Game game, String promptType, String fullPrompt,
                                      String thinking, String answer, String modelShort) {
        UUID gameId = game.getId();
        if (recordGameId != null && !recordGameId.equals(gameId)) {
            logger.info("replayLLMData: new game " + gameId + " (was " + recordGameId + ") — dropping "
                    + replayLLMData.size() + " exchange bucket(s) from the previous game");
            replayLLMData.clear();
        }
        recordGameId = gameId;
        LLMExchangeRecord rec = new LLMExchangeRecord(0, promptType, "",
                thinking, answer, getName(), modelShort, 0, "pre-game");
        rec.fullPrompt = fullPrompt;
        rec.preGame = true;
        replayLLMData.computeIfAbsent(0, k -> new ArrayList<>()).add(rec);
    }

    /**
     * Game id the current {@link #replayLLMData} contents belong to. Null until
     * the first exchange of the match is recorded; reset in {@code useDeck}.
     * Not serialized — it is a scoping hint, and the map itself is what the
     * replay file carries.
     */
    private transient UUID recordGameId = null;

    /** Retry backoff for HTTP failures: 2s, 4s, 8s, 16s between attempts —
     *  the provider's Retry-After wins when present. Only applied to
     *  retryable HTTP statuses (429/5xx); parse errors and 4xx other than
     *  429 fail immediately (they're deterministic — retrying just burns). */
    private static long retryDelayMs(int attempt, Throwable last) {
        if (last instanceof mage.player.llm.HttpStatusException) {
            mage.player.llm.HttpStatusException hse = (mage.player.llm.HttpStatusException) last;
            if (hse.retryAfterSeconds > 0) {
                return hse.retryAfterSeconds * 1000L;
            }
        }
        // Exponential base 2s, capped at 32s so a 5-attempt ladder waits at
        // most 2+4+8+16=30s of delay before the final fail.
        long d = LLM_RETRY_DELAY_MS << Math.min(attempt - 1, 4);
        return Math.min(d, 32_000L);
    }

    /**
     * Whether this failure is worth retrying at all. 429 + 5xx retry;
     * other 4xx (400 bad request, 401 auth, 404 model) are terminal.
     *
     * The classifier UNWRAPS the cause chain.
     * The browser-bridge path used to arrive here as a generic IOException
     * (BrowserCallException flattened in sendViaBridge), so its structured
     * retryable:false/httpStatus was invisible and dead/revoked keys burned
     * the full 5-attempt ladder (~30 s) before the same fatal throw. Now the
     * typed exception survives and this walker classifies it directly:
     *   - BrowserCallException: retryable flag from the browser's own report
     *     (derived from the HTTP status when it knows it).
     *   - HttpStatusException: 429/5xx retryable (existing rule).
     *   - everything else (network IOException, empty answer): retryable.
     */
    protected static boolean isRetryable(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof mage.interfaces.LLMBridgeRegistry.BrowserCallException) {
                mage.interfaces.LLMBridgeRegistry.BrowserCallException bce =
                        (mage.interfaces.LLMBridgeRegistry.BrowserCallException) cur;
                return bce.retryable;
            }
            if (cur instanceof mage.player.llm.HttpStatusException) {
                return ((mage.player.llm.HttpStatusException) cur).isRetryableWithBackoff();
            }
            // Guard against (illegal) self-referential cause chains.
            if (cur.getCause() == cur) break;
        }
        // No typed exception in the chain: empty-answer RuntimeExceptions and
        // IOExceptions (network drop) retry.
        return true;
    }

    /**
     * Call the LLM and record the exchange. If a validator is provided, check
     * the answer against it. If the answer fails validation (e.g. contains extra
     * reasoning text, wrong format), retry with a correction prompt asking the
     * LLM to reply with ONLY the expected format.
     * <p>
     * Ask-reason: when {@code config.isAskReason()} is on, the prompt gets
     * {@link LLMConfig#ASK_REASON_PROMPT_SUFFIX} appended — every prompt class
     * that flows through here (priority, mulligan, yes/no, targets, combat…)
     * asks for a REASON line after the answer line. The suffix goes to the
     * MODEL ONLY: it is not persisted into {@code prompt} here because the
     * same StringBuilder prompt is reused by callers for retry prompts, and a
     * second suffix would be appended on retry. Appending happens in
     * {@code chatAndRecordWithRetry} at the actual call site instead.
     *
     * @param validator  predicate that returns true if the answer is in the
     *                   expected format (e.g. just a number, just "yes"/"no",
     *                   just card names). Null = no validation (accept anything).
     */
    private LLMResponse chatAndRecord(Game game, String prompt, String compactSummary,
                                       String promptType,
                                       java.util.function.Predicate<String> validator) {
        return chatAndRecord(game, prompt, compactSummary, promptType, validator, false);
    }

    /**
     * combatDeclaration=true: combat calls (attackers/blockers) are made from
     * Step.beginStep() — BEFORE the engine's next saveState — so the frame
     * their record must reference is idx+1 (= current getSize()), not
     * getSize()-1. Verified on 7/7 discriminating records across 3 real LLM
     * game files: priority,
     * target and all other prompts are correct with getSize()-1 because
     * GameImpl.playPriority does `saveState(false)` immediately before invoking
     * player.priority(this). Using getSize() for ALL calls (the initially
     * suggested repair) would break the majority case — combat-only it is.
     */
    private LLMResponse chatAndRecord(Game game, String prompt, String compactSummary,
                                       String promptType,
                                       java.util.function.Predicate<String> validator,
                                       boolean combatDeclaration) {
        // Only record for replay on the real game (not AI simulations)
        int stateIdx = -1;
        if (!game.isSimulation()) {
            stateIdx = combatDeclaration
                    ? game.getGameStates().getSize()       // made from beginStep, pre-save
                    : game.getGameStates().getSize() - 1;
        }
        String modelShort = config.getModel().replaceAll("[:/].*", "");
        int turn = game.getTurnNum();
        String phaseStep = game.getPhase() != null ? game.getPhase().toString() : "?";
        if (game.getStep() != null) {
            phaseStep += " / " + game.getStep().toString();
        }

        LLMResponse resp;
        if (validator != null && terseAnswerStop && !config.isAskReason()) {
            // Terse-answer contract: when a strict format validator is present,
            // cut continuation text at the first newline so validators see ONLY
            // "3" not "3\nActually let me reconsider...". Verified honored by
            // ollama-cloud; the validator still runs as the authoritative check.
            // SKIPPED when askReason is on: the stop would cut the REASON line
            // the prompt just asked for.
            markNextCallTerse();
        }
        resp = chatAndRecordWithRetry(prompt, compactSummary, promptType, turn, phaseStep);

        // Strip "Decision:" prefixes centrally so no validator ever sees them.
        // This prevents feedback loops where the correction prompt quotes a
        // prefixed answer back to the model, which copies and adds another prefix.
        if (resp.answer != null) {
            String cleaned = stripDecisionPrefix(resp.answer);
            if (!cleaned.equals(resp.answer)) {
                logger.debug("Stripped Decision: prefix from answer: '" + resp.answer + "' -> '" + cleaned + "'");
                resp = new LLMResponse(resp.thinking, cleaned, resp.usage);
            }
        }

        // Ask-reason: extract the optional REASON line (best-effort — a
        // mangled/missing reason NEVER fails the move; the first-line parse
        // downstream is unchanged and decisive).
        if (config.isAskReason() && resp.answer != null) {
            String reason = extractStatedReason(resp.answer);
            if (reason != null && !reason.isEmpty()) {
                resp = new LLMResponse(resp.thinking, resp.answer, resp.usage, reason);
            }
        }

        if (stateIdx >= 0) {
            recordReplayExchange(game, stateIdx, promptType, compactSummary,
                    resp.thinking, resp.answer, modelShort, turn, phaseStep);
        }

        // Validate the answer format — if the LLM included extra text (reasoning,
        // explanations, etc.), re-request with a correction prompt.
        // First-line fallback: every downstream parser reads only the first
        // line, so an answer like "5\nReasoning: ..." is accepted without a
        // correction call when its first line is well-formed.

        if (validator != null && !passesValidation(validator, resp.answer)) {
            logger.warn("LLM answer failed format validation, re-requesting clean answer. Original: '"
                    + (resp.answer.length() > 100 ? resp.answer.substring(0, 100) + "..." : resp.answer) + "'");
            String formatHint = formatHintFor(validator);
            // Quote the already-stripped answer so the model doesn't see/copy a prefix
            String correctionPrompt = "Your previous answer was: \"" + resp.answer + "\"\n"
                    + "That answer is not in the correct format. The expected format is: " + formatHint + ".\n"
                    + "Reply with ONLY the answer in that format — no reasoning, no explanation, "
                    + "no preamble, no \"Decision:\" prefix. Just the answer itself.";
            // Format failures are the same overthinking symptom the empty-answer
            // ladder handles — descend one rung for the correction call so the
            // model gets a smaller thinking budget and a stronger nudge to answer
            // tersely. Attempt index 3 = one rung below configured effort.
            String correctionEffort = effortForAttempt(3);
            // Stored compact summary for the correction exchange: descriptive, not
            // the placeholder "format correction" (the string goes into conversation
            // history — future prompts AND the webui/transcripts read it).
            String correctionSummary = "Retry of the " + promptType + " decision — previous answer "
                    + "was not in the required format (" + formatHint + ").";
            LLMResponse corrected = chatAndRecordWithRetryWithEffort(correctionPrompt, correctionSummary,
                    promptType + "-corrected", turn, phaseStep, correctionEffort);
            // Strip prefix from the corrected answer too
            if (corrected.answer != null) {
                String cleanedCorrected = stripDecisionPrefix(corrected.answer);
                if (!cleanedCorrected.equals(corrected.answer)) {
                    logger.debug("Stripped Decision: prefix from corrected answer: '" + corrected.answer + "' -> '" + cleanedCorrected + "'");
                    corrected = new LLMResponse(corrected.thinking, cleanedCorrected, corrected.usage);
                }
            }
            if (validator.test(corrected.answer) || validator.test(firstLineOf(corrected.answer))) {
                // Record the corrected response too. Same descriptive summary as the
                // conversation-history path (chatAndRecordWithRetryWithEffort stored it
                // above); the replay record must not regress to the placeholder literal

                if (stateIdx >= 0) {
                    recordReplayExchange(game, stateIdx, promptType + "-corrected", correctionSummary,
                            corrected.thinking, corrected.answer, modelShort, turn, phaseStep);
                }
                return corrected;
            }
            // Correction also failed — return the original and let the caller
            // handle the parse failure (which will throw per Fail Fast).
            logger.error("LLM format correction also failed. Original: '" + resp.answer + "', corrected: '" + corrected.answer + "'");
        }

        return resp;
    }

    /**
     * Call the LLM with retries. Retries up to LLM_MAX_RETRIES times with a delay
     * between attempts. If all retries fail, throws the last exception.
     *
     * TODO: Make this more graceful eventually — a rogue LLM taking down a whole
     * server all the time is not ideal. For now, crashing surfaces bugs that
     * silent fallbacks would hide.
     */
    private LLMResponse chatAndRecordWithRetry(String prompt, String compactSummary) {
        return chatAndRecordWithRetry(prompt, compactSummary, null, null, null);
    }

    // protected (not private) for test-subclass access (effort-descent ladder tests)
    protected LLMResponse chatAndRecordWithRetry(String prompt, String compactSummary,
                                                String promptType, Integer turn, String phaseStep) {
        return chatAndRecordWithRetryWithEffort(prompt, compactSummary, promptType, turn, phaseStep, null);
    }

    /**
     * Retry loop with an optional starting-effort override. When baseEffortOverride
     * is non-null, attempt 1 runs at that effort instead of the configured one —
     * used by the format-correction path, which re-prompts one ladder rung below
     * configured because format failures are the same overthinking symptom as
     * empty answers.
     *
     * Empty-answer retry ladder:
     *   attempt 1   — configured reasoning effort (or baseEffortOverride when set)
     *   attempt 2   — same effort + nudge (most loops recover here)
     *   attempts 3+ — descend one ladder rung per attempt, + nudge. Descent
     *                 applies only while the failure mode is empty answers
     *                 (reasoning loops) — infrastructure errors retry at the
     *                 attempt-1 effort.
     *   final rung  — attempt LLM_MAX_RETRIES after an empty answer disables
     *                 thinking ENTIRELY for that call (chatConversationNoThinking):
     *                 the reasoning-effort descent only shrinks the reasoning
     *                 allowance, so a model that loops at every effort still
     *                 starves the answer. With thinking off the whole output
     *                 budget goes to the answer — the last lever that can break
     *                 the loop without substituting an agent-chosen action.
     *   exhausted   — throw. A model that still loops with thinking disabled is
     *                 seriously wrong; passing priority silently would hide it
     *                 (Fail Fast).
     */
    // Prompts bigger than this (chars) start the retry ladder one rung DOWN on
    // attempt 1 — at max effort, huge prompts burn 64K+ tokens on thinking and
    // return empty answers ~80% of the time (prompt-size-effort-loop
    // data). High effort answers the same decision in
    // seconds. Only engages when thinking is on and a ladder exists.
    private static final int PROMPT_SIZE_EFFORT_DESCENT_THRESHOLD_CHARS = 100_000;

    protected LLMResponse chatAndRecordWithRetryWithEffort(String prompt, String compactSummary,
                                                String promptType, Integer turn, String phaseStep,
                                                String baseEffortOverride) {
        // Ask-reason: the suffix is applied HERE — at the actual call boundary —
        // so retries of the same decision re-ask for the REASON line (each retry
        // rebuilds effectivePrompt from the caller's original prompt), while
        // format-correction calls (which pass their own correctionPrompt through
        // this same method) get it too. The caller's prompt object is never
        // mutated, so a caller that reuses its StringBuilder for a retry prompt
        // can't accumulate a second suffix.
        final String effectivePrompt;
        if (config.isAskReason() && prompt != null && !prompt.endsWith(LLMConfig.ASK_REASON_PROMPT_SUFFIX.trim())) {
            effectivePrompt = prompt + LLMConfig.ASK_REASON_PROMPT_SUFFIX;
        } else {
            effectivePrompt = prompt;
        }
        boolean lastFailureWasEmptyAnswer = false;
        Throwable lastException = null;
        for (int attempt = 1; attempt <= LLM_MAX_RETRIES; attempt++) {
            String effortOverride;
            if (attempt == 1) {
                // Prompt-size-aware first-attempt effort: oversized prompts at max
                // effort pay the empty-answer retry tax so reliably that starting
                // one rung lower (when a ladder exists) is strictly faster.
                if (baseEffortOverride != null) {
                    effortOverride = baseEffortOverride;  // format correction: starts one rung down
                } else if (prompt != null && prompt.length() >= PROMPT_SIZE_EFFORT_DESCENT_THRESHOLD_CHARS) {
                    String descended = effortForAttempt(3);
                    if (descended != null) {
                        logger.info("Prompt " + prompt.length() + " chars >= " + PROMPT_SIZE_EFFORT_DESCENT_THRESHOLD_CHARS
                                + " — starting at lowered effort '" + descended + "' (was " + config.getReasoningEffort() + ")");
                        effortOverride = descended;
                    } else {
                        effortOverride = null;
                    }
                } else {
                    effortOverride = null;
                }
            } else {
                effortOverride = (attempt > 1 && lastFailureWasEmptyAnswer)
                        ? effortForAttempt(attempt) : null;
            }
            // Final rung of an empty-answer ladder: turn thinking OFF entirely so
            // the answer stream gets the whole output budget. The effort descent
            // only shrinks the reasoning allowance; disabling thinking removes it,
            // so a reasoning blowout can never again starve the answer. Gates on
            // the PREVIOUS attempt also being an empty answer — infrastructure
            // failures (connection/5xx) retry with thinking untouched.
            boolean finalNoThinkingAttempt = (attempt == LLM_MAX_RETRIES) && lastFailureWasEmptyAnswer;
            lastFailureWasEmptyAnswer = false;
            try {
                LLMResponse resp;
                if (finalNoThinkingAttempt) {
                    // Consume the one-shot terse flag: the no-thinking call has no
                    // stop sequence, but the flag must not leak onto the next call.
                    nextCallTerse.remove();
                    logger.info("Final attempt " + attempt + "/" + LLM_MAX_RETRIES
                            + " after an empty answer — disabling thinking for this call"
                            + " (full output budget to the answer).");
                    resp = client.chatConversationNoThinking(effectivePrompt, compactSummary, promptType,
                            turn, phaseStep, effortOverride);
                } else if (nextCallTerse.get() != null) {
                    nextCallTerse.remove();
                    resp = client.chatConversationWithStop(effectivePrompt, compactSummary, promptType,
                            turn, phaseStep, effortOverride, TERSE_STOP);
                } else {
                    resp = client.chatConversation(effectivePrompt, compactSummary, promptType,
                            turn, phaseStep, effortOverride);
                }
                if (resp == null || resp.answer == null) {
                    throw new RuntimeException("LLM returned null response (attempt " + attempt + ")");
                }
                // Reasoning-bleed RECOVERY: a provider that
                // routes the WHOLE completion — including the decision — into the
                // reasoning channel and leaves content empty. Measured on
                // glm-5.3-flash @ opencode zen/go: every mulligan attempt INCLUDING
                // the thinking-disabled final one returned content="" with the decision
                // in reasoning_content (one attempt streamed exactly
                // "Keep — Two lands, one producing {C}, no fast ramp but reasonable
                // spells" with no ANSWER marker), so all 5 attempts were classified
                // empty and the seat conceded.
                //
                // This is NOT an empty answer: the content channel is blank, so there is
                // NO other output to read — the reasoning text IS the response, the
                // provider merely routed it to the other field. Promote it to the answer
                // so the decision pipeline can proceed.
                //
                // Discrimination is by finish_reason — the ONLY value that rules the
                // recovery out is `length`, never a length heuristic:
                //   * finish_reason == "length" -> the model was still producing output
                //     when the token budget ran out (the classic deepseek reasoning
                //     loop: huge reasoning, empty content). Genuine empty answer —
                //     keep descending the ladder, exactly as before (never promoted).
                //   * "stop", null, absent, or ANY other value -> do NOT gate the
                //     recovery on a value the gateway may omit. The first version of
                //     this fix required finish_reason == "stop" and was DEAD CODE
                //     live: the recording proxy captured 119/119 chunks of the long
                //     zen stream with "finish_reason":null and NO terminal stop chunk
                // while
                //     the real game still terminal-failed at turn 4 (5 empty answers
                //     with non-blank reasoning -> concede) and the promotion WARN
                //     never fired. A null/absent finish_reason is the NORMAL shape on
                //     exactly the streams that bleed, so requiring "stop" made the
                //     branch unsatisfiable.
                // The recovery is therefore independent of the stop value: with an
                // empty content channel and NON-BLANK reasoning the reasoning text IS
                // the response (there is no other output to read), and `length` — the
                // one positive truncation signal — remains the sole conservative
                // non-promote. The WARN below fires only on a real promotion.
                // The promoted text is handed UNCHANGED to the same validator /
                // correction / parser chain a content-channel answer would have hit, so
                // nothing is invented. A genuine empty answer (both channels blank) falls
                // through to the empty-answer ladder and the terminal concede.
                boolean lengthTruncated = "length".equalsIgnoreCase(resp.finishReason);
                if (resp.answer.trim().isEmpty()
                        && !lengthTruncated
                        && resp.thinking != null && !resp.thinking.trim().isEmpty()) {
                    String recoveredFirstLine = firstLineOf(resp.thinking);
                    logger.warn("LLM answer channel was empty but the reasoning channel carried the"
                            + " response (attempt " + attempt + "/" + LLM_MAX_RETRIES + ", "
                            + resp.thinking.length() + " chars, finish_reason=" + resp.finishReason
                            + ") — provider routed the completion to the reasoning field"
                            + " (glm-5.3-flash@zen shape); recovering it"
                            + " as the answer for this '" + promptType + "' decision."
                            + " First line: '" + recoveredFirstLine + "'");
                    addSelfLog("LLM answer was empty but reasoning held the decision '"
                            + recoveredFirstLine + "' — recovered from the reasoning channel.");
                    resp = new LLMResponse(resp.thinking, resp.thinking, resp.usage,
                            resp.statedReason, resp.finishReason);
                }
                if (resp.answer.trim().isEmpty()) {
                    // The model produced reasoning but no answer — it exhausted
                    // its token budget on thinking. Inject a nudge into the
                    // conversation history so the next retry sees a different
                    // prompt. This breaks the deterministic loop:
                    //   same prompt → same reasoning → same empty answer.
                    //
                    // Diagnostics are reasoning-TOKEN based, not thinking-text
                    // based: frontier providers (OpenAI/Anthropic) never return
                    // raw CoT, so on those models the reasoning text is empty
                    // even though the model reasoned and was billed for it.
                    // usage.reasoningTokens is provider-agnostic and already
                    // parsed for every endpoint that reports a usage object.
                    // The thinking-text hint remains as a when-available
                    // embellishment (it also injects lexical variation, which
                    // tokens cannot).
                    Integer reasoningTokens = (resp.usage != null) ? resp.usage.reasoningTokens : null;
                    boolean hasThinkingText = resp.thinking != null && !resp.thinking.isEmpty();

                    String thinkHint = "";
                    if (hasThinkingText) {
                        thinkHint = resp.thinking.length() > 40
                                ? resp.thinking.substring(0, 40)
                                : resp.thinking;
                        thinkHint = thinkHint.replaceAll("[\\n\\r]", " ").trim();
                    }

                    String reasonClause;
                    if (hasThinkingText) {
                        reasonClause = "produced reasoning (\"" + thinkHint + "...\")";
                    } else if (reasoningTokens != null && reasoningTokens > 0) {
                        reasonClause = "used " + reasoningTokens
                                + " reasoning tokens (billed, but the provider does not return the text)";
                    } else {
                        reasonClause = "produced no answer";
                    }
                    String nudge = "Your previous response " + reasonClause
                            + " but NO answer — you exhausted your token budget on thinking. "
                            + "Respond with ONLY the answer in the exact format the last prompt requested. Do not overthink this.";
                    // Tell the model its thinking level was lowered when the next
                    // attempt descends the effort ladder.
                    String nextEffort = effortForAttempt(attempt + 1);
                    if (nextEffort != null) {
                        nudge += " (Your reasoning level has been lowered to '" + nextEffort
                                + "' for this decision — decide immediately.)";
                    }
                    client.addFailureMessage(nudge);
                    logger.info("Injected retry nudge (" + reasonClause + "), effort="
                            + (effortOverride != null ? effortOverride : config.getReasoningEffort())
                            + ", for attempt " + (attempt + 1));

                    String thinkInfo;
                    if (hasThinkingText) {
                        thinkInfo = " (but " + resp.thinking.length()
                                + " chars of reasoning produced — likely token budget exhausted by thinking)";
                    } else if (reasoningTokens != null && reasoningTokens > 0) {
                        thinkInfo = " (" + reasoningTokens + " reasoning tokens billed, no answer produced"
                                + " — reasoning text hidden by provider)";
                    } else {
                        thinkInfo = " (no reasoning reported either)";
                    }
                    lastFailureWasEmptyAnswer = true;
                    throw new RuntimeException("LLM returned empty answer (attempt " + attempt + ")" + thinkInfo);
                }
                return resp;
            } catch (Throwable e) {
                if (e instanceof Error) throw (Error) e;
                lastException = e;
                logger.error("LLM call attempt " + attempt + "/" + LLM_MAX_RETRIES
                        + " failed: " + e.getMessage());
                if (!isRetryable(e)) {
                    // Terminal HTTP status (400/401/404 etc.) — fail immediately
                    // with the provider's message rather than burning 4 more attempts.
                    throw new RuntimeException("LLM call failed with non-retryable error on attempt "
                            + attempt + ": " + e.getMessage(), e);
                }
                if (attempt < LLM_MAX_RETRIES) {
                    long delayMs = retryDelayMs(attempt, e);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("LLM retry interrupted", ie);
                    }
                }
            }
        }
        if (lastFailureWasEmptyAnswer && config.isThinkingEnabled()) {
            String lowest = effortForAttempt(LLM_MAX_RETRIES);
            String descentInfo = (lowest != null)
                    ? " down to '" + lowest + "'"
                    : " (descent unavailable — configured effort '" + config.getReasoningEffort()
                        + "' not in ladder " + config.getReasoningEffortLevels() + ")";
            throw new RuntimeException("LLM empty-answer loop survived the full reasoning-effort descent"
                    + descentInfo + " and the thinking-disabled final attempt, " + LLM_MAX_RETRIES + " attempts."
                    + " Something is seriously wrong — the model loops even with thinking turned off."
                    + " Last error: " + lastException.getMessage(), lastException);
        }
        throw new RuntimeException("LLM call failed after " + LLM_MAX_RETRIES
                + " attempts. Last error: " + lastException.getMessage(), lastException);
    }

    // Format-correction prompts demand a single-token answer; the stop sequence
    // cuts any continuation so validators see ONLY the answer, not "3\nActually..."
    private static final String TERSE_STOP = "\n";
    private static final ThreadLocal<Boolean> nextCallTerse = new ThreadLocal<>();

    /**
     * Mark the NEXT chatConversation on this thread as terse-answer: emit
     * stop:["\n"] so the model can't ramble past the first line. Cleared after
     * each call. Used by chatAndRecord when a strict-format validator is present.
     */
    static void markNextCallTerse() {
        nextCallTerse.set(Boolean.TRUE);
    }

    /**
     * Reasoning effort for a retry attempt in the empty-answer ladder.
     * Attempts 1-2 use the configured effort (null = no override). Attempts 3+
     * descend one rung per attempt, clamped at the ladder's lowest level.
     *
     * Which ladder depends on the configured thinking dialect:
     *   dialect "effort" → config.reasoningEffortLevels (Strings; descends from
     *                      the rung below config.reasoningEffort).
     *   dialect "budget" → config.thinkingBudgetTokens (ints as Strings; rung
     *                      index = attempt - 3, independent of configured effort).
     *
     * Returns null (no override, configured effort) when thinking is disabled.
     * In effort mode, also returns null when the configured effort isn't in the
     * ladder — a descent ordering cannot be derived, so retries stay at the
     * configured effort rather than guessing.
     */
    private String effortForAttempt(int attempt) {
        if (!config.isThinkingEnabled()) return null;
        if (attempt <= 2) return null;
        if ("budget".equals(config.getThinkingDialect())) {
            List<Integer> ladder = config.getThinkingBudgetTokens();
            if (ladder == null || ladder.isEmpty()) {
                throw new IllegalStateException("thinkingDialect=budget but thinkingBudgetTokens ladder is empty");
            }
            int idx = Math.min(attempt - 2, ladder.size() - 1); // attempt 3 → second rung, clamped
            return String.valueOf(ladder.get(idx));
        }
        List<String> ladder = config.getReasoningEffortLevels();
        String base = config.getReasoningEffort();
        int idx = (ladder == null) ? -1 : ladder.indexOf(base);
        if (idx < 0) {
            logger.warn("Configured reasoning effort '" + base + "' not in effort ladder " + ladder
                    + " — effort descent disabled; retries stay at '" + base + "'."
                    + " Add the value to xmage.llm.reasoning_effort_levels to enable descent.");
            return null;
        }
        int descentIdx = idx + (attempt - 2); // attempt 3 → one rung below configured, etc.
        if (descentIdx >= ladder.size()) descentIdx = ladder.size() - 1; // clamp at lowest
        return ladder.get(descentIdx);
    }

    // Monitors the two log structures the game-end reflection reads while game
    // threads may still be appending. ConcurrentModificationException here kills
    // the game-end path, so
    // every mutation AND every snapshot take the matching lock — a bare
    // `new ArrayList<>(list)` is NOT atomic against a concurrent add().
    // selfLog is written by the player's own decision thread; the GameState
    // gameLog is written by whichever game-event thread calls informPlayers.
    // The gameLog side cannot be locked from the plugin (GameState is core), so
    // gameLog snapshots instead take the monitor that GameImpl's state writes are
    // serialized on — see snapshotGameLog().
    private static final Object SELF_LOG_LOCK = new Object();

    /**
     * Append to selfLog and trim to the last SELF_LOG_MAX entries, keeping
     * the most recent decisions. Bounds memory on very long games.
     * Synchronized so a snapshot taken from the game-end path (a different
     * thread during teardown) can never observe a half-applied trim — the
     * bulk subList().clear() is exactly the mutation a concurrent iterator
     * would trip over.
     */
    private void addSelfLog(String entry) {
        synchronized (SELF_LOG_LOCK) {
            selfLog.add(entry);
            if (selfLog.size() > SELF_LOG_MAX) {
                // Trim in bulk: remove oldest entries beyond the cap.
                int excess = selfLog.size() - SELF_LOG_MAX;
                selfLog.subList(0, excess).clear();
            }
        }
    }

    /** Atomic snapshot of selfLog — iterate THIS, never the field. */
    private List<String> snapshotSelfLog() {
        synchronized (SELF_LOG_LOCK) {
            return new ArrayList<>(selfLog);
        }
    }

    /**
     * Atomic snapshot of the GameState game log.
     * <p>
     * GameState.getGameLog() returns the live list, which LLMGameLogCollector
     * appends to from game-event threads. The collector adds through
     * {@code state.addGameLog(...)} with no synchronization, so a plain
     * {@code new ArrayList<>(gameLog)} races a concurrent append: ArrayList's
     * copy goes through toArray(), which reads size() and the backing array
     * separately — the observed failure mode is
     * ConcurrentModificationException from an iterator over a list captured
     * while an append landed (a web player left during the mulligan and the
     * game-end reflection copied the log mid-append), and an inconsistent/
     * overflowing array read is
     * the adjacent symptom.
     * <p>
     * The monitor is taken on the log instance so this copy is atomic against any
     * appender that synchronizes on the same list, and a racing appender that
     * does not is retried briefly. A log that is STILL unstable after the retries
     * is a bug in the append path, so throw with context rather than silently
     * emitting a truncated reflection prompt (Fail Fast).
     */
    private List<String> snapshotGameLog(Game game) {
        return copyLogWithRetry(game.getState().getGameLog(), "game " + game.getId());
    }

    /**
     * Copy a live log list, retrying briefly when a concurrent append lands
     * mid-copy. Public static so the game-end regression tests can drive it
     * directly (Mage.Tests is a different package/module).
     */
    public static List<String> copyLogWithRetry(List<String> log, String label) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                synchronized (log) {
                    return new ArrayList<>(log);
                }
            } catch (RuntimeException e) {
                lastFailure = e;
                LOGGER_FOR_SNAPSHOT.warn("log snapshot raced a concurrent append (" + label
                        + ", attempt " + attempt + "/3, size=" + log.size() + "): " + e);
                try {
                    Thread.sleep(5L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while snapshotting " + label, ie);
                }
            }
        }
        throw new IllegalStateException("log " + label + " was still being mutated after 3 snapshot"
                + " attempts (size=" + log.size() + ")", lastFailure);
    }

    /** Logger for the static snapshot helper (LLMPlayer's own logger is not static). */
    private static final org.apache.log4j.Logger LOGGER_FOR_SNAPSHOT =
            org.apache.log4j.Logger.getLogger(LLMPlayer.class);

    /**
     * Set the player's internal name via reflection (the name field has no public setter).
     * Used for bot name override from config.
     */
    private void setOwnName(String newName) {
        try {
            Class<?> current = this.getClass();
            java.lang.reflect.Field nameField = null;
            while (current != null && nameField == null) {
                try {
                    nameField = current.getDeclaredField("name");
                } catch (NoSuchFieldException e) {
                    current = current.getSuperclass();
                }
            }
            if (nameField != null) {
                nameField.setAccessible(true);
                nameField.set(this, newName);
            }
        } catch (Throwable t) {
            throw new RuntimeException("Failed to set own name to '" + newName + "'", t);
        }
    }

    /**
     * Client-derived placeholder seat names — names a client assigned to a
     * seat that carry no identity for the LLM bot, and are therefore safe (and
     * expected) to be replaced by a generated name in useDeck().
     * <p>
     * Producers:
     * <ul>
     *   <li>Desktop New-Table / New-Tournament dialogs: {@code "Computer"}
     *       (the 2-player AI seat, {@code ClientDefaultSettings.computerName})
     *       and {@code "Computer <n>"} for seats 2+ — both from
     *       {@code TablePlayerPanel.extractAiPlayerNumberFromLabel} /
     *       {@code NewPlayerPanel}'s default.</li>
     *   <li>Web proxy {@code WebProxySession.handleCreateTable} /
     *       {@code handleCreateTournament}: {@code "AI-<index>"} for an AI seat
     *       with no user-typed name ({@code pt.isAI() ? "AI-" + i : username}).</li>
     * </ul>
     * Everything else — a \botgame name ({@code "model-Name"},
     * {@code "AI-Bot-Name"}), a user-typed seat name from either dialog, or a
     * test-harness name — is a caller-assigned identity and must be preserved:
     * re-randomizing those lets two same-model LLM seats become identically
     * named, so the game log attributes every action to one indistinguishable
     * name.
     */
    public static boolean isPlaceholderBotName(String name) {
        if (name == null || name.isEmpty()) {
            return true;
        }
        if (name.equals("Computer")) {
            return true;
        }
        // Desktop multi-player seats: "Computer 2", "Computer 3"...
        if (name.matches("Computer \\d+")) {
            return true;
        }
        // Web proxy unnamed AI seats: "AI-0", "AI-1"...
        return name.matches("AI-\\d+");
    }

    // ==================================================================
    //  DECK
    // ==================================================================

    @Override
    public void useDeck(Deck deck, Game game) {
        super.useDeck(deck, game);
        deckList.clear();
        deck.getMaindeckCards().stream().map(Card::getName).sorted().forEach(deckList::add);

        // Reset per-game tracking so each game gets its own post-game review,
        // transcript file, and action log.
        postGameSummaryGenerated = false;
        postGameReflection = null;
        selfLog.clear();
        lastGameLogSize = 0;
        lastGameLogTruncateEpoch = 0;
        lastBoardHash = 0L;
        pendingAltCost = false;
        pendingManaPlan = null;
        skipMode = SkipMode.NONE;
        currentActions = null;
        mulliganCount = 0;
        // State indices restart per game — stale replay exchanges from the
        // previous game would otherwise be attributed to this game's frames.
        replayLLMData.clear();
        recordGameId = null;

        // Build full-text deck representation for system prompt / sideboarding
        // (grouped: 4x Island + one rules block)
        List<Card> sortedDeck = deck.getMaindeckCards().stream()
                .sorted(Comparator.comparing(Card::getName))
                .collect(Collectors.toList());
        deckListFullText = LLMGameSerializer.formatDecklistGrouped(sortedDeck);

        // NOTE (2026-10-03): decklist names are NO LONGER pre-registered with
        // the serializer. The old decklist-name skip heuristic (formatCardBrief
        // dropping rules text for own-decklist cards) is replaced by the
        // encounter-based full-text-once funnel: the per-(game, seat) seen-set
        // starts EMPTY each game and grows on first emission, so a decklist
        // card still gets its full text the first time it appears in a prompt
        // this game. That was the motivating failure: a live seat had Spell
        // Queller's full text in its system-prompt decklist on every call and
        // still concluded "no flash" — the decklist primer alone is not enough
        // for in-context recall; the funnel's explicit FULL TEXT marker at the
        // point of decision is.

        if (config.isVerboseLogging())
            logger.info("LLM deck captured: " + deckList.size() + " cards");

        // Seed system prompt with full-text decklist (gameplan appended after assessment)
        String systemWithDeck = config.getSystemPromptTemplate();
        if (deckListFullText != null && !deckListFullText.isEmpty()) {
            systemWithDeck += "\n\n=== YOUR DECKLIST ===\n" + deckListFullText;
        } else if (!deckList.isEmpty()) {
            systemWithDeck += "\n\n=== YOUR DECKLIST ===\n" + String.join("\n", deckList);
        }

        // Add sideboard to the system prompt so the LLM can factor its options into strategic thinking
        // (grouped like the decklist)
        Set<Card> sbCards = deck.getSideboard();
        if (!sbCards.isEmpty()) {
            systemWithDeck += "\n=== YOUR SIDEBOARD ===\n";
            List<Card> sbList = sbCards.stream()
                    .sorted(Comparator.comparing(Card::getName))
                    .collect(Collectors.toList());
            systemWithDeck += LLMGameSerializer.formatDecklistGrouped(sbList);
        }

        // Override bot name if configured (validated against personality registry).
        // If no name is configured, auto-generate a model-prefixed random name
        // from the personality pool — same as BotGameHandler does for \botgame.
        //
        // IMPORTANT: Only auto-generate if the current name is still a
        // client-derived placeholder — the desktop's "Computer"/"Computer <n>"
        // or the web proxy's "AI-<index>" (see isPlaceholderBotName). Both
        // callers set unique, deduplicated names in OTHER paths
        // (BotGameHandler.pickBotName() for \botgame, a user-typed seat name
        // for tables) that must be preserved: blindly overwriting those with a
        // fresh random draw lets two same-model LLM seats become identically
        // named ("deepseek-v4-flash-thorn" ×2), making the game log attribute
        // every action to one indistinguishable name.
        String configBotName = config.getBotName();
        if (configBotName != null && !configBotName.isEmpty()) {
            String resolved = Personality.resolveBotName(configBotName);
            if (resolved != null && !resolved.equals(this.getName())) {
                setOwnName(resolved);
                logger.info("Bot name overridden to: " + resolved);
            }
        } else {
            String currentName = this.getName();
            if (isPlaceholderBotName(currentName)) {
                // Auto-generate: pick a random name from the personality registry,
                // EXCLUDING names already in use by other players in this game.
                // Two LLM bots on the same model each drawing the same personality
                // suffix produces two identical names ("deepseek-v4-flash-thorn" ×2)
                // — the game log then attributes every action to one indistinguishable
                // name (the game-log name attribution problem). Without the game reference
                // this dedup cannot see the other seat, so pass it in via useDeck().
                List<String> pool = Personality.allNames();
                if (game != null) {
                    Set<String> taken = new HashSet<>();
                    for (Player other : game.getPlayers().values()) {
                        if (!other.getId().equals(getId()) && other.getName() != null) {
                            // Strip everything up to the LAST dash: "model-thorn" → "thorn"
                            taken.add(other.getName());
                            taken.add(other.getName().replaceFirst("^.*-", ""));
                        }
                    }
                    List<String> available = pool.stream()
                            .filter(n -> !taken.contains(n))
                            .collect(Collectors.toList());
                    if (available.isEmpty()) {
                        logger.warn("All " + pool.size() + " personality names taken in this game ("
                                + taken + ") — keeping default name. Name collision likely.");
                    } else {
                        pool = available;
                    }
                }
                if (!pool.isEmpty()) {
                    String modelShort = config.getModel().replaceAll("[:/].*", "");
                    String randomName = pool.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.size()));
                    String fullName = modelShort + "-" + randomName;
                    setOwnName(fullName);
                    logger.info("Auto-generated bot name: " + fullName);
                }
            } else {
                // Name was already set by BotGameHandler (with dedup) or the GUI — keep it.
                logger.info("Preserving existing bot name: " + currentName);
            }
        }

        // Inject personality (enabled by default; toggle with xmage.llm.personality_enabled)
        if (config.isPersonalityEnabled()) {
            String personalityName = config.getPersonalityName();
            if (personalityName == null || personalityName.isEmpty()) {
                personalityName = this.getName();
            }
            String personalitySection = Personality.buildPromptSection(true, personalityName);
            if (!personalitySection.isEmpty()) {
                systemWithDeck += personalitySection;
                logger.info("Injected personality for " + personalityName);
            }
        }

        // Defer deck assessment to the first chooseMulligan() call.
        // Running the LLM call here would block the game-start thread inside
        // match.startGame() -> initGame() -> addPlayer(), preventing the table
        // from reaching DUELING state and the client from showing the game.
        // Instead, cache the assessment prompt text so it fires during mulligan
        // when the game is already visible.
        //
        // Build AFTER the rename above: the prompt embeds
        // "You are playing as: <name>", so building it while the seat still
        // carries its placeholder ("AI-<n>") had the LLM introduce itself to
        // itself under the proxy's internal name (observed symptom:
        // "You are playing as: AI-0" / LLMWebSeatAutoRenameTest).
        gamePlanComputed = false;
        gamePlan = null;
        if (config.isDeckAssessmentEnabled()) {
            cachedAssessmentPrompt = LLMGameSerializer.buildDeckAssessmentPrompt(
                    game, this.getId(), deck, this.getName());
        } else {
            cachedAssessmentPrompt = null;
        }

        client.resetConversation(systemWithDeck);
        replaySystemPrompt = systemWithDeck;

        // Enable game state logging (only needed for LLM prompts)
        game.getState().setGameLogEnabled(true);

        // Initialize transcript now that we have game ID and player name
        client.initTranscript(game.getId().toString(), getName());
        // Client-key bridge routing context: gameId + seatId + display name.
        // Needed before any LLM call in browser-key mode.
        client.setBridgeContext(game.getId().toString(), this.getId().toString(), getName());
    }

    /**
     * System prompt used specifically for the deck assessment call.
     * Separate from the main game-playing system prompt — focuses on analysis and planning.
     */
    private static final String DECK_ASSESSMENT_SYSTEM_PROMPT =
            "You are an expert Magic: The Gathering deck analyst and strategist.\n"
            + "\n"
            + "Your task is to study a complete decklist with full card text and formulate\n"
            + "a concise, actionable gameplan before the match begins.\n"
            + "\n"
            + "Think about:\n"
            + "- The deck's archetype and primary win condition\n"
            + "- What constitutes a keepable opening hand\n"
            + "- Key card synergies and combos\n"
            + "- How to sequence early turns\n"
            + "- When and how to use the sideboard\n"
            + "\n"
            + "Write ONLY the final gameplan — no preamble, no markdown, just the plan.";

    /**
     * Run deck assessment on the first call (deferred from useDeck to avoid
     * blocking the game-start thread). Idempotent — subsequent calls are no-ops.
     */
    private void ensureGamePlan(Game game) {
        if (gamePlanComputed) return;
        gamePlanComputed = true;  // mark first so failure is final

        // Game 2+ with a revised strategy: inject it directly, skip LLM call
        if (revisedStrategy != null) {
            gamePlan = revisedStrategy;
            if (config.isVerboseLogging()) {
                logger.info("=== REVISED STRATEGY (game 2+) ===\n" + revisedStrategy + "\n=== END ===");
            }
            String enrichedPrompt = client.getConversationSystemPrompt();
            if (enrichedPrompt != null) {
                enrichedPrompt += "\n\n=== YOUR GAMEPLAN (Revised After Sideboarding) ===\n"
                        + "After reviewing the previous game and sideboarding, this is your updated plan:\n\n"
                        + revisedStrategy;
            }
            client.resetConversation(enrichedPrompt != null ? enrichedPrompt : config.getSystemPromptTemplate());
            replaySystemPrompt = client.getConversationSystemPrompt();
            // Game-2+ replay gap: the "revised strategy" IS this game's
            // gameplan, injected without an LLM call — record it so the replay
            // viewer shows the same pre-game block as game 1's assessment.
            recordReplayPreamble(game, "deck_assessment",
                    "(revised strategy carried from sideboarding — no LLM call)",
                    null, revisedStrategy,
                    config.getModel() != null ? config.getModel().replaceAll("[:/].*", "") : "?");
            return;
        }

        if (cachedAssessmentPrompt == null) return;

        if (config.isVerboseLogging()) {
            logger.info("=== PRE-GAME DECK ASSESSMENT ===");
            logger.info("Prompt length: " + cachedAssessmentPrompt.length() + " chars");
            logger.info(cachedAssessmentPrompt);
            logger.info("=== END DECK ASSESSMENT PROMPT ===");
        }

        // Capture BEFORE the null-out: the record carries the prompt the LLM
        // actually saw (system + user), which for this call is
        // DECK_ASSESSMENT_SYSTEM_PROMPT + cachedAssessmentPrompt.
        final String assessmentFullPrompt =
                DECK_ASSESSMENT_SYSTEM_PROMPT + "\n\n" + cachedAssessmentPrompt;
        LLMResponse assessResp;
        try {
            assessResp = client.chat(DECK_ASSESSMENT_SYSTEM_PROMPT, cachedAssessmentPrompt);
        } catch (RuntimeException e) {
            if (thrownAfterFullLadder(e)) {
                // Mulligan/assessment is the ONE path with no retry ladder
                // and no catch in Mulligan.executeMulliganPhase — a throw here
                // escapes init and ghosts the game at T1.
                // Concede instead; the engine's normal unwind records the loss.
                gamePlanComputed = false;  // allow a fresh attempt should a new game start
                gamePlan = null;
                cachedAssessmentPrompt = null;
                concedeOnTerminalFailure(game, e, "deck assessment (mulligan bootstrap)");
                return;
            }
            throw e;
        }
        cachedAssessmentPrompt = null;  // release memory

        // LLMClient.chat() now throws RuntimeException on failure — no null check needed
        gamePlan = assessResp.answer.trim();
        if (config.isVerboseLogging()) {
            if (assessResp.thinking != null && !assessResp.thinking.isEmpty())
                logger.info("=== ASSESSMENT THINKING (discarded) ===\n" + assessResp.thinking + "\n=== END ===");
            logger.info("=== GAMEPLAN (preserved) ===\n" + gamePlan + "\n=== END ===");
        }

        // Enrich system prompt with gameplan for subsequent LLM calls
        if (gamePlan != null) {
            String enrichedPrompt = client.getConversationSystemPrompt();
            if (enrichedPrompt != null) {
                enrichedPrompt += "\n\n=== YOUR GAMEPLAN ===\n"
                        + "Before the game started, you analyzed your deck thoroughly and formulated this plan:\n\n"
                        + gamePlan;
            }
            client.resetConversation(enrichedPrompt != null ? enrichedPrompt : config.getSystemPromptTemplate());
            replaySystemPrompt = client.getConversationSystemPrompt();
        }

        // Replay viewer (2026-09-30): the assessment exchange is a PRE-game
        // prompt — no in-game recordReplayExchange ever captures it, so a
        // replay showed nothing before turn 1. Record prompt + answer (+its
        // thinking) as a stateIndex-0 pre-game record.
        String assessModel = config.getModel() != null ? config.getModel().replaceAll("[:/].*", "") : "?";
        recordReplayPreamble(game, "deck_assessment", assessmentFullPrompt,
                assessResp.thinking, gamePlan, assessModel);
    }

    // ==================================================================
    //  MULLIGAN — LLM-driven with full bootstrap context
    // ==================================================================

    @Override
    public boolean chooseMulligan(Game game) {
        if (config == null) {
            return super.chooseMulligan(game);
        }
        mulliganCount++;

        // Run deck assessment on the first mulligan call, not during useDeck().
        // This allows the game to reach DUELING state immediately and the client
        // to show the game while the LLM thinks during the pre-game phase.
        ensureGamePlan(game);

        // Build bootstrap context on the first call (includes deck & opponent info)
        StringBuilder prompt = new StringBuilder();
        prompt.append("=== MULLIGAN DECISION ===\n");
        prompt.append("You are playing a game of Magic: The Gathering.\n");
        prompt.append("Format: London Mulligan (you always draw 7 cards after a mulligan,\n");
        prompt.append(" but after you keep, you must put one card on the bottom of your\n");
        prompt.append(" library for each mulligan you've taken — so keeping after 1\n");
        prompt.append(" mulligan = 6 cards, 2 mulligans = 5 cards, etc.)\n\n");
        // Decklist is in the system prompt — no need to repeat here.

        // Show opponents
        prompt.append("=== OPPONENTS ===\n");
        for (Player p : game.getPlayers().values()) {
            if (p.getId().equals(getId())) continue;
            prompt.append("  ").append(p.getName());
            if (p instanceof ComputerPlayer) {
                prompt.append(" (AI opponent)");
            } else if (p.isHuman()) {
                prompt.append(" (Human opponent)");
            }
            prompt.append("\n");
        }

        // Show current hand
        prompt.append("\nMulligan #").append(mulliganCount)
              .append(" (hand size: ").append(hand.size()).append(")\n\n");
        prompt.append("=== YOUR HAND ===\n");
        for (Card card : hand.getCards(game)) {
            // Mulligan counts as an encounter: the first sighting
            // here emits full text (marked) and marks the name, so later
            // priority prompts render shorthand.
            prompt.append("  ").append(LLMGameSerializer.formatCardForChoiceOnce(game, getId(), card)).append("\n");
        }

        prompt.append("\nShould you KEEP this hand or MULLIGAN?\n");
        prompt.append("Reply with ONLY 'keep' or 'mulligan'.");

        if (config.isVerboseLogging())
            logger.info("=== LLM MULLIGAN ===\n" + prompt + "\n=== END ===");

        LLMResponse resp;
        try {
            resp = chatAndRecord(game, prompt.toString(),
                    mulliganCount > 0
                        ? "Mulligan #" + mulliganCount + " (" + hand.size() + " cards)"
                        : "Opening hand (" + hand.size() + " cards)",
                    "mulligan", KEEP_MULLIGAN_VALIDATOR);
        } catch (RuntimeException e) {
            if (thrownAfterFullLadder(e)) {
                // chooseMulligan has no catch in Mulligan.executeMulliganPhase —
                // a throw here escapes init and ghosts the game (mulligan chains
                // legitimately repeat shapes, so a whole-log dedup also hides it).
                concedeOnTerminalFailure(game, e, "chooseMulligan");
                return true;  // benign: hand stands, engine unwinds via concede
            }
            throw e;
        }

        if (config.isVerboseLogging()) {
            logger.info("LLM mulligan response: '" + resp.answer + "'");
            if (resp.thinking != null && !resp.thinking.isEmpty())
                logger.info("=== LLM THINKING ===\n" + resp.thinking + "\n=== END ===");
        }

        // First line only — chatAndRecord accepts first-line-valid answers
        // (passesValidation), so trailing reasoning must not influence the
        // keep/mulligan interpretation (e.g. reasoning mentioning "keep" must
        // not flip a mulligan decision).
        String decision = firstLineOf(resp.answer).toLowerCase();
        boolean willMulligan = decision.contains("mulligan") && !decision.contains("keep");

        addSelfLog("Pre-game: " + (willMulligan ? "Mulligan" : "Keep") + " (" + hand.size() + " cards)");
        return willMulligan;
    }

    // ==================================================================
    //  PRIORITY
    //  Terminal-failure guard: an LLM
    //  failure that propagates out of a game-thread callback escapes to
    //  GameWorker.catch(Throwable) → LOGGER.fatal → game thread dies
    //  BEFORE endGameWithResult runs → the table lingers in DUELING
    //  forever (the "ghost game" signature). Instead of
    //  letting the exception escape, the callback requests a real
    //  CONCEDE — the engine's normal unwind (setConcedingPlayer →
    //  checkConcede → leave → checkIfGameIsOver → endGameWithResult →
    //  table close / next game) — and returns the engine's benign value.
    // ==================================================================

    /**
     * True when the given failure is a TERMINAL LLM failure (retrying can
     * never succeed): a non-retryable HTTP status (400/401/404…) or any
     * other non-retryable classification. Transient failures (429/5xx,
     * network blips, empty answers) are NOT terminal — the retry ladder
     * owns them; they throw only after exhausting LLM_MAX_RETRIES, which
     * this guard ALSO reads as terminal (the ladder already ran to the
     * end — one more round of 30 s cannot help a stuck provider).
     *
     * Used by the per-callback guards: on a terminal failure we concede
     * the game instead of letting the exception escape to GameWorker's
     * fatal catch (which kills the game thread without endGameWithResult
     * and strands the table in DUELING — no more silent ghost games).
     */
    // protected (not private) for test access — terminal-failure-endgame classifier pins
    protected static boolean thrownAfterFullLadder(Throwable t) {
        // chatAndRecordWithRetry wraps its exhausted-ladder throw in
        // "LLM call failed after N attempts" (and the empty-answer variant
        // before it). Those messages are the ladder-exhausted signature.
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String m = cur.getMessage();
            if (m != null && (m.contains("LLM call failed after")
                    || m.contains("survived the full reasoning-effort descent"))) {
                return true;
            }
            if (cur.getCause() == cur) break;
        }
        return isTerminalNonRetryable(t);
    }

    /** Non-retryable HTTP status (400/401/404 etc.) anywhere in the cause chain. */
    protected static boolean isTerminalNonRetryable(Throwable t) {
        return !isRetryable(t);
    }

    /** Root cause of the current terminal failure — set before every concede guard fires. */
    private transient Throwable lastTerminalFailure = null;

    /** Model name shortened for file naming (matches the inline pattern used at all write sites). */
    private static String modelShortOf(LLMConfig cfg) {
        return cfg.getModel().replaceAll("[:/].*", "");
    }

    /**
     * On a terminal LLM failure inside a game-thread callback, drive the
     * engine's REAL concede path and return a benign value for the caller.
     *
     * Concede (not a synthetic skip/pass) so the game END is recorded:
     * {@code concede(game)} → {@code game.setConcedingPlayer(playerId)} →
     * processed on the game thread ({@code checkConcede} → {@code leave}) →
     * {@code checkIfGameIsOver} → {@code endGameWithResult} → match end /
     * table close. The match then ends cleanly instead of ghosting. The
     * terminal failure is logged loudly (FATAL) — nothing is swallowed.
     *
     * @param cause the terminal failure (root cause for the log)
     * @param what  call-site label for the log line
     * @return the benign value the engine callback should return
     */
    private boolean concedeOnTerminalFailure(Game game, Throwable cause, String what) {
        this.lastTerminalFailure = cause;
        logger.fatal("LLM terminal failure in " + what + " — conceding game "
                + game.getId() + " so it ends cleanly (no ghost table). Root cause: "
                + cause.getMessage(), cause);
        addSelfLog(gameTurnStep(game) + ": TERMINAL LLM FAILURE in " + what
                + " — conceding the game (" + cause.getMessage() + ")");
        game.informPlayers(getLogName() + " loses the game (LLM provider failure).");
        concede(game);
        return true;
    }

    // Custom skip tracking (replaces PlayerImpl skip flags which never auto-cancel)
    public enum SkipMode { NONE, SKIP_STACK, SKIP_END_STEP, SKIP_NEXT_TURN }
    private SkipMode skipMode = SkipMode.NONE;
    private int skipSetAtTurn = -1;
    private int skipSetAtPhaseStep = -1;  // TurnStepType ordinal

    /**
     * Concrete engine rejection from the last failed action within the current
     * priority() retry loop, or null when the last action succeeded/was never
     * attempted. Set by promptAndExecute, read by the exhausted-retry path so
     * the loud report names the real reason instead of a generic "rejected
     * actions".
     *
     * This is the discriminator the defect was missing: the retry re-sent an
     * identical context (same rebuilt action list, same generic failure text),
     * so a deterministic model repeated the identical engine-rejected action
     * until the retry budget ran out and the game was killed.
     */
    private transient String lastActionRejectionDetail = null;

    @Override
    public boolean priority(Game game) {
        // Check and possibly clear active skips based on game state changes
        boolean shouldCancelSkip = false;

        if (skipMode != SkipMode.NONE) {
            // SKIP_STACK: persist while stack is non-empty, cancel when it empties
            if (skipMode == SkipMode.SKIP_STACK) {
                if (game.getStack().isEmpty()) {
                    shouldCancelSkip = true;
                }
            }
            // All other skips: cancel if stack becomes non-empty (something happened!)
            else if (!game.getStack().isEmpty()) {
                shouldCancelSkip = true;
            }
            // Always cancel skip on a new turn
            else if (game.getTurnNum() != skipSetAtTurn) {
                shouldCancelSkip = true;
            }
            // For SKIP_END_STEP: do NOT cancel on step change.
            // The point is to skip through ALL phases of your own turn
            // and the opponent's — only stack or turn change should wake us.
            // For SKIP_NEXT_TURN: cancel only when it's a NEW turn AND we're the active player
            else if (skipMode == SkipMode.SKIP_NEXT_TURN) {
                if (game.getTurnNum() != skipSetAtTurn && game.isActivePlayer(getId())) {
                    shouldCancelSkip = true;
                }
            }

            if (shouldCancelSkip) {
                skipMode = SkipMode.NONE;
                addSelfLog(gameTurnStep(game) + ": Skip auto-cancelled (state changed)");
            } else {
                // Skip still active, pass silently
                pass(game);
                return false;
            }
        }

        // LLM decision loop with retry on rejected actions (e.g. Force of Will
        // under Sphere of Resistance — XMage says it's playable but rejects it)
        //
        // The retry must DISCRIMINATE: the retry loop used to feed the model the
        // SAME rebuilt action list plus a generic "YOUR LAST ACTION FAILED"
        // notice, so a deterministic model re-picked the identical rejected
        // action on every attempt and the loop always ended at the RuntimeException
        // below — the last one in GameImpl.playPriority's inner catch, after the
        // 16th such failure, killed the whole game (observed: 3x FATAL
        // "Game error: ... T9.M1; active: glm-5.3-flash-casey" on a mana-plan /
        // 'YOUR LAST ACTION FAILED' repeat). Two changes make each retry carry
        // something NEW and remove the game-kill:
        //   1. on the FINAL retry, the prompt gets the accumulated engine
        //      rejection reason + an explicit "do not repeat the rejected action"
        //      directive (a last discriminating attempt, not a re-roll);
        //   2. if the model STILL picks a rejected action, we do NOT throw — we
        //      take the one unconditionally-legal action (pass priority) so the
        //      stuck state is surfaced LOUDLY (FATAL + self-log + transcript) and
        //      the game/match survives. Throwing here was never the intended
        //      escape: the mana-plan breaker at playMana (~L2919) is the engine's
        //      throw site for a genuinely bad plan, and it auto-restores; this
        //      path duplicated the escalation and let one unrecoverable decision
        //      end a whole match.
        lastActionRejectionDetail = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            boolean done;
            try {
                done = promptAndExecute(game, attempt == 2);
            } catch (RuntimeException e) {
                if (thrownAfterFullLadder(e)) {
                    // Terminal provider failure at priority — concede so the
                    // game ends with a recorded result instead of ghosting.
                    return concedeOnTerminalFailure(game, e, "priority");
                }
                throw e;
            }
            if (done) return true;
            // If the action was rejected, rebuild actions and retry
            // (promptAndExecute injected a failure message into the conversation)
        }
        // All retries exhausted. The LLM kept choosing actions the engine
        // rejected. Surface the stuck state loudly (FATAL + self-log; the
        // rejection detail was appended to the conversation by promptAndExecute)
        // and take the legal fallback — pass priority — instead of throwing and
        // killing the game/match over a single unrecoverable decision.
        String detail = (lastActionRejectionDetail != null)
                ? lastActionRejectionDetail : "unspecified engine rejection";
        String stuck = "LLM priority: stuck decision — LLM kept choosing rejected "
                + "actions after 3 attempts (" + detail + "). Taking the legal "
                + "fallback: pass priority.";
        logger.fatal(stuck);
        addSelfLog(gameTurnStep(game) + ": " + stuck);
        pass(game);
        return true;
    }

    /**
     * Prompt the LLM, parse its response, and execute the chosen action.
     * Returns true if the action succeeded, false if it was rejected (caller retries).
     *
     * @param finalAttempt true on the LAST retry of the priority loop. The prompt
     *                     then carries the accumulated engine-rejection detail and
     *                     an explicit do-not-repeat directive, so the retry is a
     *                     discriminating last attempt rather than a re-roll of the
     *                     identical context.
     */
    // protected (not private) for test-subclass access — the retry-discrimination
    // test overrides this to simulate repeated engine rejections offline.
    protected boolean promptAndExecute(Game game, boolean finalAttempt) {
        currentActions = buildActionList(game);
        if (currentActions.isEmpty()
                || currentActions.stream().allMatch(a -> a.actionType == ActionType.PASS)) {
            pass(game);
            return true;
        }

        // Trivial-decision fast path: a lone land play is deterministic — play it
        // without an LLM call. The LLM was looping on single-land windows, burning
        // full 64K-token reasoning budgets on a decision with one reasonable answer

        if (currentActions.size() == 1
                && currentActions.get(0).actionType == ActionType.PLAY_LAND) {
            ActionEntry land = currentActions.get(0);
            addSelfLog(gameTurnStep(game) + ": Auto-play land (trivial decision): " + land.description);
            if (config.isVerboseLogging())
                logger.info("Trivial fast path: auto-playing land " + land.description);
            if (executeAction(land, game)) {
                return true;
            }
            // Engine rejected the land — fall through to the LLM path rather than
            // silently passing (fail fast).
        }

        List<String> actionDescriptions = currentActions.stream()
                .map(a -> a.description)
                .collect(Collectors.toList());
        Map<String, UUID> landIdMap = new HashMap<>();
        // Prompt-log cursor: a turn rollback/undo TRUNCATES the log, but the
        // cursor is an index — left alone it stays past the re-played range and
        // the LLM never sees the re-played events again (it is told "no new
        // events" for the rest of the game). Re-derive it against the current
        // truncation epoch before building the prompt.
        GameState promptState = game.getState();
        if (promptState.getGameLogTruncateEpoch() != lastGameLogTruncateEpoch) {
            int rewind = promptState.getGameLogCursor(lastGameLogTruncateEpoch, lastGameLogSize);
            if (rewind != lastGameLogSize) {
                logger.info("Game log truncated (epoch " + lastGameLogTruncateEpoch + " -> "
                        + promptState.getGameLogTruncateEpoch() + "): prompt cursor "
                        + lastGameLogSize + " -> " + rewind + " (re-played events shown as new)");
            }
            lastGameLogSize = rewind;
            lastGameLogTruncateEpoch = promptState.getGameLogTruncateEpoch();
        }
        String userPrompt = LLMGameSerializer.buildPrompt(game, this.getId(), config, selfLog, lastGameLogSize, actionDescriptions, lastBoardHash, landIdMap);
        // On the LAST retry the model has already been shown the generic
        // "YOUR LAST ACTION FAILED" notice twice and still re-picked a rejected
        // action (a deterministic model repeats itself when the context does not
        // change). Give it something NEW: the accumulated concrete rejection
        // detail plus an explicit constraint naming the rejected entry, so the
        // final attempt is discriminating instead of a third identical roll.
        if (finalAttempt && lastActionRejectionDetail != null) {
            userPrompt = "RETRY CONSTRAINT: your previous choice was rejected by the game "
                    + "engine (" + lastActionRejectionDetail + "). You MUST NOT choose that "
                    + "same action again — pick a DIFFERENT entry from the list below, or "
                    + "pass priority.\n\n" + userPrompt;
            if (config.isVerboseLogging())
                logger.info("Final-retry constraint prepended: " + lastActionRejectionDetail);
        }
        lastGameLogSize = game.getState().getGameLog().size();
        lastBoardHash = LLMGameSerializer.computeBoardHash(game, this.getId());

        if (config.isVerboseLogging()) {
            logger.info("=== FULL LLM CONTEXT ===\n--- SYSTEM PROMPT ---\n"
                    + config.getSystemPromptTemplate() + "\n--- USER PROMPT ---\n" + userPrompt
                    + "\n=== END FULL LLM CONTEXT ===");
        }

        String compactSummary = LLMGameSerializer.buildSituationSummary(game, this.getId());
        // Range-checked validator: the offered list is currentActions (n entries) plus
        // the 4 SKIP options and optionally CONCEDE. An out-of-range answer now
        // FAILS validation → chatAndRecord's correction path re-prompts with the
        // valid range, instead of reaching the dispatcher's FATAL + turn rollback.
        LLMResponse resp = chatAndRecord(game, userPrompt, compactSummary, "priority",
                actionNumberInRange(currentActions.size(), config.isConcedeEnabled()));

        if (config.isVerboseLogging() && resp.thinking != null && !resp.thinking.isEmpty())
            logger.info("=== LLM THINKING ===\n" + resp.thinking + "\n=== END LLM THINKING ===");
        if (config.isVerboseLogging()) logger.info("LLM answer: '" + resp.answer + "'");

        int actionIndex = parseActionNumber(resp.answer);
        List<UUID> manaPlan = parseManaPlan(resp.answer, landIdMap);

        // If the LLM answered with a non-numeric word (e.g., "yes" or "no")
        // instead of an action number, interpret it intelligently:
        // - "yes" → take the first available action (resolve stack, cast, etc.)
        // - "no"  → pass priority (action 0)
        // This prevents game crashes when the LLM confuses a triggered ability
        // yes/no prompt with the action number prompt.
        //
        // ONLY apply this heuristic when there is exactly 1 non-pass action —
        // the LLM is clearly answering a binary question. With multiple actions
        // available, the LLM should be picking a number; interpreting "yes"/"no"
        // as action 1/0 would silently pick the wrong action.
        if (actionIndex == -1 && resp.answer != null && currentActions.size() == 1) {
            String cleaned = resp.answer.trim().toLowerCase();
            // Strip any "Decision:" prefixes
            while (cleaned.startsWith("decision:")) {
                cleaned = cleaned.substring(9).trim();
            }
            if ("yes".equals(cleaned) || "y".equals(cleaned)) {
                // LLM wants to act — take the first action (action 1)
                actionIndex = 1;
                logger.warn("LLM answered 'yes' to action prompt — interpreting as action 1. Answer: '"
                        + resp.answer + "'");
            } else if ("no".equals(cleaned) || "n".equals(cleaned)) {
                // LLM wants to decline — pass priority
                actionIndex = 0;
                logger.warn("LLM answered 'no' to action prompt — interpreting as pass priority. Answer: '"
                        + resp.answer + "'");
            }
        }

        int skipTurnIdx = currentActions.size() + 1;
        int skipEndStepIdx = currentActions.size() + 2;
        int skipStackIdx = currentActions.size() + 3;
        int cancelSkipIdx = currentActions.size() + 4;
        int concedeIdx = currentActions.size() + 5;

        if (actionIndex == skipTurnIdx) {
            addSelfLog(gameTurnStep(game) + ": Skip to next turn");
            return executeAction(new ActionEntry(ActionType.SKIP_TO_NEXT_TURN,
                    "SKIP TO YOUR NEXT TURN", null, null, false), game);
        }
        if (actionIndex == skipEndStepIdx) {
            addSelfLog(gameTurnStep(game) + ": Skip to end step before turn");
            return executeAction(new ActionEntry(ActionType.SKIP_TO_END_STEP,
                    "SKIP TO END STEP BEFORE YOUR TURN", null, null, false), game);
        }
        if (actionIndex == skipStackIdx) {
            addSelfLog(gameTurnStep(game) + ": Skip until stack resolves");
            return executeAction(new ActionEntry(ActionType.SKIP_STACK,
                    "SKIP UNTIL STACK RESOLVES", null, null, false), game);
        }
        if (actionIndex == cancelSkipIdx) {
            addSelfLog(gameTurnStep(game) + ": Cancel all skips");
            return executeAction(new ActionEntry(ActionType.CANCEL_SKIP,
                    "CANCEL ALL SKIPS", null, null, false), game);
        }
        if (actionIndex == concedeIdx && config.isConcedeEnabled()) {
            addSelfLog(gameTurnStep(game) + ": CONCEDE");
            if (config.isVerboseLogging())
                logger.info("LLM chose to CONCEDE");
            return executeAction(new ActionEntry(ActionType.CONCEDE,
                    "CONCEDE", null, null, false), game);
        }

        if (actionIndex == 0) {
            // Action 0 = pass priority (legitimate action, not a fallback)
            if (config.isVerboseLogging())
                logger.info("LLM chose to pass priority (action 0)");
            addSelfLog(gameTurnStep(game) + ": Pass priority");
            pass(game);
            return true;
        }

        if (actionIndex < 0 || actionIndex > currentActions.size()) {
            throw new RuntimeException("LLM returned invalid action index " + actionIndex
                    + " (valid range: 0-" + currentActions.size() + "). Answer: '" + resp.answer + "'");
        }

        ActionEntry chosen = currentActions.get(actionIndex - 1);
        // Attach mana plan if the LLM specified one and this is a cast/activate action
        if (manaPlan != null && !manaPlan.isEmpty()
                && (chosen.actionType == ActionType.CAST_SPELL || chosen.actionType == ActionType.ACTIVATE_ABILITY)) {
            chosen = new ActionEntry(chosen.actionType, chosen.description, chosen.cardOrAbilityId,
                    chosen.sourceId, chosen.noMana, chosen.useAltCost, manaPlan, chosen.sourceZone);
            if (config.isVerboseLogging())
                logger.info("LLM mana plan: " + manaPlan.stream().map(UUID::toString).collect(Collectors.joining(",")));
        }
        if (config.isVerboseLogging())
            logger.info("LLM chose action " + actionIndex + ": " + chosen.description);
        addSelfLog(gameTurnStep(game) + ": " + chosen.description);

        boolean success = executeAction(chosen, game);
        if (!success && chosen.actionType != ActionType.PASS) {
            // Fail Fast: do NOT auto-retry with alt cost. The LLM already sees
            // both normal and alt-cost entries in the action list. If it chose
            // normal cast and it failed, the LLM should explicitly choose the
            // alt cost on the next attempt. Silent auto-retry overrides the
            // LLM's deliberate choice — e.g., choosing normal Ragavan cast
            // (wants it to stay on board) but getting Dash instead (returns to
            // hand at end step), which is game-losing.
        }
        if (!success && chosen.actionType != ActionType.PASS) {
            String hints;
            switch (chosen.actionType) {
                case PLAY_LAND:
                    hints = "You may have already played a land this turn, or this card cannot be played as a land.";
                    break;
                case CAST_SPELL:
                    hints = "Check: Do you have enough mana of the right colors? "
                            + "Is there a valid target for this spell? "
                            + "Is it the right phase/step to cast this type of spell?";
                    break;
                case ACTIVATE_ABILITY:
                    hints = "Check: Are the activation costs payable? "
                            + "Does the ability have a valid target? "
                            + "Can this ability be activated right now?";
                    break;
                default:
                    hints = "This action cannot be performed right now.";
                    break;
            }
            String failMsg = "YOUR LAST ACTION FAILED: '" + chosen.description
                    + "' was rejected by the game engine. "
                    + hints + "\n"
                    + "Current stack: " + (game.getStack().isEmpty() ? "(empty)" :
                        game.getStack().stream().map(so ->
                            mage.player.llm.LLMGameSerializer.stackObjectDisplayName(so, game))
                            .collect(java.util.stream.Collectors.joining(", ")))
                    + "\nReview the current game state and choose a DIFFERENT action.";
            addSelfLog(failMsg);
            // Record the concrete rejection (action + engine hint) so the
            // NEXT retry — and the exhausted-retry report — names the real reason
            // instead of a generic "rejected actions". Kept overwrite-per-failure:
            // the final attempt's constraint must reflect the most recent failure.
            lastActionRejectionDetail = "'" + chosen.description + "' (" + hints + ")";
            if (config.isVerboseLogging())
                logger.info("Action failed: " + chosen.description + " — will retry");
            client.addFailureMessage(failMsg);
            return false;
        }
        return true;
    }

    private String gameTurnStep(Game game) {
        PhaseStep step = game.getTurnStepType();
        int turnNum = game.getTurnNum();
        if (step == null || turnNum <= 0) {
            // Pre-first-turn windows — starting-player choice, mulligan
            // keep/bottom, deck assessment — run before the turn structure
            // exists, so getTurnStepType() is null and the old label leaked
            // "Turn 1 null" into self-log/reflection. Name the window instead.
            return "Pre-game";
        }
        return "Turn " + turnNum + " " + step;
    }

    /**
     * Human-readable label for a permanent id used in self-log/diagnostic
     * messages — the permanent's name (plus a short id suffix so duplicate
     * names stay distinguishable), falling back to the raw id only when the
     * permanent is gone. Raw UUIDs in a self-log line are unreadable and
     * useless to the post-game reflection.
     */
    private static String permanentLabel(Game game, UUID permId) {
        if (permId == null) return "(unknown permanent)";
        Permanent perm = game != null ? game.getPermanent(permId) : null;
        if (perm == null) {
            // Not on the battlefield (already sacrificed/rolled back) — keep
            // the id but shorten it so the line stays scannable.
            String id = permId.toString();
            return "(gone: " + id.substring(0, Math.min(8, id.length())) + ")";
        }
        String name = perm.getName();
        if (name == null || name.isEmpty()) name = "(unnamed permanent)";
        String idStr = permId.toString().replaceAll("-", "");
        return name + " (" + idStr.substring(Math.max(0, idStr.length() - 4)) + ")";
    }

    // --- Action enums & builder ---

    private enum ActionType {
        PASS, PLAY_LAND, CAST_SPELL, ACTIVATE_ABILITY,
        SKIP_TO_NEXT_TURN, SKIP_TO_END_STEP, SKIP_STACK, CANCEL_SKIP, CONCEDE
    }

    private static class ActionEntry {
        final ActionType actionType;
        final String description;
        final UUID cardOrAbilityId;
        final UUID sourceId;
        final boolean noMana;
        final boolean useAltCost;        // true = auto-accept alt cost prompt during cast
        final List<UUID> manaPlan;       // ordered list of permanent IDs to tap for mana, null = auto-tap
        final Zone sourceZone;           // zone the source object lives in (null = battlefield)
        ActionEntry(ActionType t, String d, UUID id, UUID src, boolean nm) {
            this(t, d, id, src, nm, false, null);
        }
        ActionEntry(ActionType t, String d, UUID id, UUID src, boolean nm, boolean useAltCost) {
            this(t, d, id, src, nm, useAltCost, null);
        }
        ActionEntry(ActionType t, String d, UUID id, UUID src, boolean nm, boolean useAltCost, List<UUID> manaPlan) {
            this(t, d, id, src, nm, useAltCost, manaPlan, null);
        }
        ActionEntry(ActionType t, String d, UUID id, UUID src, boolean nm, boolean useAltCost, List<UUID> manaPlan, Zone sourceZone) {
            actionType = t; description = d; cardOrAbilityId = id; sourceId = src;
            noMana = nm; this.useAltCost = useAltCost; this.manaPlan = manaPlan;
            this.sourceZone = sourceZone;
        }
    }

    // Set by doCastSpell before calling playCard; cleared by choose() after auto-accept
    private boolean pendingAltCost = false;

    // Mana plan: when non-null, playMana() taps these specific permanents instead of auto-tapping.
    // Set by doCastSpell/activatePermanentAbility before the cast/activate call triggers
    // mana payment callbacks. Cleared after the action completes.
    // protected (not private) for test-subclass access (mana-plan ordering test).
    protected List<UUID> pendingManaPlan = null;

    // Circuit breaker: track repeated identical mana-plan failures. First
    // failure of a plan entry DECLINES the action (clean engine rollback +
    // failure feedback to the LLM); the same permanent failing the SAME
    // unpaid cost 3x in a row escalates to the throw (game error path) so a
    // deterministic re-prompt loop can't spin forever.
    // Reset on any successful playMana or new cast decision.
    // NOT reset by useDeck() — this is per-decision loop state, not cross-game state,
    // so leaking it between games is harmless (a new cast overwrites permId/signature).
    private UUID lastManaFailPermId = null;
    private String lastManaFailSignature = null;
    private int manaFailCount = 0;

    private List<ActionEntry> buildActionList(Game game) {
        List<ActionEntry> actions = new ArrayList<>();
        PlayableObjectsList playables = getPlayableObjects(game, Zone.ALL);

        // Payable abilities from the hand, computed once. Used to:
        // - gate cast/alt-cost entries on the card's BASE SpellAbility actually
        //   being payable (getPlayableAmount > 0 is NOT sufficient — it also
        // counts non-cast abilities like cycling);
        // surface hand activated abilities (cycling, channel...) as actions.
        List<ActivatedAbility> handPlayable = getPlayable(game, true, Zone.HAND, false);
        Set<UUID> castableCardIds = new HashSet<>();
        for (ActivatedAbility ability : handPlayable) {
            if (ability instanceof SpellAbility
                    && ((SpellAbility) ability).getSpellAbilityType() == SpellAbilityType.BASE) {
                castableCardIds.add(ability.getSourceId());
            }
        }

        boolean canStillPlayLand = canPlayLand();
        for (UUID cardId : getHand()) {
            Card card = game.getCard(cardId);
            if (card != null && card.isLand(game) && canStillPlayLand
                    && playables.getPlayableAmount(cardId) > 0)
                actions.add(new ActionEntry(ActionType.PLAY_LAND,
                        "Play land: " + card.getName(), cardId, null, false));
        }
        // Lands playable from zones OTHER than the hand ("you may play lands
        // from your graveyard" — Crucible of Worlds, Icetill
        // Explorer, Ramunap Excavator — produced no action at all because land
        // plays were enumerated from the hand only).
        //
        // The rule is NOT re-derived here: we ask the ENGINE which land plays are
        // legal from each zone. PlayerImpl.getPlayable(..., Zone.GRAVEYARD...)
        // only returns a PlayLandAbility when the PLAY_FROM_NOT_OWN_HAND_ZONE
        // asThough check approves it (PlayFromGraveyardControllerEffect) AND the
        // ability's own canActivate passes (lands-per-turn budget, sorcery timing,
        // active player) — see PlayerImpl.getPlayableFromObjectSingle. So a land
        // in this list is playable right now by the engine's own reckoning.
        for (Zone zone : NON_HAND_PLAY_ZONES) {
            for (ActivatedAbility ability : getPlayable(game, true, zone, false)) {
                if (!(ability instanceof PlayLandAbility)) continue;  // casts/other plays are handled elsewhere
                UUID cardId = ability.getSourceId();
                if (cardId == null) continue;
                Card card = game.getCard(cardId);
                if (card == null || !card.isLand(game)) continue;
                actions.add(new ActionEntry(ActionType.PLAY_LAND,
                        "Play land from " + zone + ": " + card.getName(), cardId, null, false));
            }
        }
        for (UUID cardId : getHand()) {
            Card card = game.getCard(cardId);
            if (card == null || card.isLand(game)) continue;
            if (castableCardIds.contains(cardId)) {
                // Normal cast entry
                actions.add(new ActionEntry(ActionType.CAST_SPELL,
                        "Cast: " + card.getName() + " (" + card.getManaCost().getText() + ")",
                        cardId, null, false, false));

                // Separate per-alt-cost entries so LLM can explicitly choose which mode
                List<String> altCosts = LLMGameSerializer.getAlternativeCostTexts(card, game, getId());
                for (int j = 0; j < altCosts.size(); j++) {
                    actions.add(new ActionEntry(ActionType.CAST_SPELL,
                            "Cast: " + card.getName() + " — " + altCosts.get(j),
                            cardId, null, false, true));
                }
            }
            // Hand activated abilities (cycling, channel, etc.) — previously never
            // offered, leaving mana-screwed players with no visible outs

            for (ActivatedAbility ability : handPlayable) {
                if (!cardId.equals(ability.getSourceId())) continue;
                if (ability instanceof SpellAbility) continue; // cast entries above
                if (ability instanceof ActivatedManaAbilityImpl) continue; // auto-handled by payMana
                actions.add(new ActionEntry(ActionType.ACTIVATE_ABILITY,
                        "Activate: " + card.getName() + " — " + shortRule(ability.getRule()),
                        ability.getId(), cardId, false, false, null, Zone.HAND));
            }
        }
        for (Permanent perm : game.getBattlefield().getAllPermanents().stream()
                .filter(p -> p.isControlledBy(getId())).collect(Collectors.toList())) {
            // Skip mana-tap actions — these are noise for the LLM. Mana payment is
            // auto-handled by ComputerPlayer.payMana() when casting spells or activating
            // abilities. Showing "Tap Mountain for R" wastes prompt tokens and distracts
            // the LLM from meaningful actions. Non-mana activated abilities remain.
            // (Removed: mana ability iteration that generated tap-for-mana action entries.)

            // Non-mana activated abilities
            if (playables.getPlayableAmount(perm.getId()) > 0) {
                Map<UUID, ActivatedAbility> abs = getPlayableActivatedAbilities(
                        perm, Zone.BATTLEFIELD, game);
                if (abs.isEmpty() && config.isVerboseLogging()) {
                    // Debug: permanent has playableAmount > 0 but no activated abilities
                    // — may indicate Clue token or token ability not being surfaced
                    logger.info("buildActionList: " + perm.getName() + " (ID:" + perm.getId()
                            + " isToken:" + perm.isToken() + ") has playableAmount > 0 but 0 activated abilities");
                }
                for (ActivatedAbility ability : abs.values()) {
                    // Skip mana abilities — auto-handled by payMana during spell/ability casting
                    if (ability instanceof ActivatedManaAbilityImpl) continue;
                    // Skip equip abilities where the equipment is already attached
                    // to the only valid target — re-equipping to the same creature
                    // wastes mana and causes duplicate equip prompts.
                    // If attached but multiple targets exist (move to another creature),
                    // keep the action but annotate it so the LLM knows the current state.
                    if (ability instanceof EquipAbility && perm.getAttachedTo() != null) {
                        Permanent attachedTo = game.getPermanent(perm.getAttachedTo());
                        if (attachedTo != null) {
                            // Check how many valid targets the equip ability has
                            Set<UUID> possibleTargets = ability.getTargets().get(0)
                                    .possibleTargets(ability.getControllerId(), ability, game);
                            // If the only valid target is the creature it's already attached to,
                            // skip this action — it's a no-op that wastes mana.
                            if (possibleTargets.size() == 1
                                    && possibleTargets.contains(perm.getAttachedTo())) {
                                if (config.isVerboseLogging()) {
                                    logger.info("buildActionList: Skipping equip " + perm.getName()
                                            + " — already attached to " + attachedTo.getName()
                                            + " (only valid target)");
                                }
                                continue;
                            }
                            // Multiple targets — keep the action but annotate it
                            actions.add(new ActionEntry(ActionType.ACTIVATE_ABILITY,
                                    "Activate: " + perm.getName() + " — " + shortRule(ability.getRule())
                                    + " (currently attached to " + attachedTo.getName() + ")",
                                    ability.getId(), perm.getId(), false));
                            continue;
                        }
                    }
                    actions.add(new ActionEntry(ActionType.ACTIVATE_ABILITY,
                            "Activate: " + perm.getName() + " — " + shortRule(ability.getRule()),
                            ability.getId(), perm.getId(), false));
                }
            } else if (perm.isToken() && config.isVerboseLogging()) {
                // Debug: token permanent with 0 playableAmount — may indicate
                // Clue sacrifice not appearing because {2} mana isn't available
                logger.info("buildActionList: Token " + perm.getName() + " (ID:" + perm.getId()
                        + ") has playableAmount 0 — abilities not offered to LLM");
            }
        }
        return actions;
    }

    private static String shortRule(String rule) {
        return rule != null && rule.length() > 80 ? rule.substring(0, 77) + "..." : rule;
    }

    // formatMana removed — mana-tap actions are no longer generated (auto-handled by payMana)

    // --- Parsing ---

    private int parseActionNumber(String response) {
        return parseFirstNumber(response);
    }

    /**
     * Static core of {@link #parseActionNumber}: prefer an "action:N" prefix
     * anywhere in the response, else extract the first number from the FIRST
     * LINE only. Package-private so the static mode-choice helpers (and unit
     * tests) reuse the exact same extraction the action parsers use.
     */
    public static int parseFirstNumber(String response) {
        if (response == null) return -1;
        // Prefer "action:N" format if present
        String lower = response.toLowerCase();
        int actionIdx = lower.indexOf("action:");
        if (actionIdx >= 0) {
            String after = response.substring(actionIdx + 7).trim();
            // Take the first number after "action:"
            String[] words = after.replaceAll("[^0-9\\s]", " ").trim().split("\\s+");
            for (String w : words) {
                if (w.matches("-?\\d+")) return Integer.parseInt(w);
            }
        }
        // Fallback: extract first number from the FIRST LINE only.
        // Looking at the last number in the full response is wrong because
        // LLM reasoning text often contains numbers (e.g., "T16", "turn 3")
        // that get misinterpreted as the action index.
        String firstLine = response.trim().split("\\n", 2)[0];
        String[] words = firstLine.replaceAll("[^0-9\\s]", " ").trim().split("\\s+");
        for (String w : words) {
            if (w.matches("-?\\d+")) return Integer.parseInt(w);
        }
        return -1;
    }

    /**
     * Parse an optional mana plan from the LLM response.
     * Format: "<number> mana:<land_id>,<land_id>..."
     * Land IDs are short labels (L1, L2...) from the prompt's Untapped Lands section,
     * resolved to permanent UUIDs via landIdMap.
     * Returns null if no mana plan was specified.
     */
    private List<UUID> parseManaPlan(String response, Map<String, UUID> landIdMap) {
        if (response == null || landIdMap.isEmpty()) return null;
        // Only look at the first line — trailing text is reasoning/explanation
        String firstLine = response.trim().split("\\n", 2)[0];
        String lower = firstLine.toLowerCase();
        int idx = lower.indexOf("mana:");
        if (idx < 0) return null;
        // Extract everything after "mana:" on this line only
        String rest = firstLine.substring(idx + 5).trim();
        // Split on comma/whitespace
        String[] parts = rest.split("[,\\s]+");
        List<UUID> plan = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            String trimmed = part.trim();
            UUID id = landIdMap.get(trimmed);
            if (id == null) {
                // Try case-insensitive lookup
                for (Map.Entry<String, UUID> e : landIdMap.entrySet()) {
                    if (e.getKey().equalsIgnoreCase(trimmed)) {
                        id = e.getValue();
                        break;
                    }
                }
            }
            if (id != null) {
                plan.add(id);
            } else {
                if (config.isVerboseLogging())
                    logger.info("LLM mana plan: could not resolve '" + trimmed + "', stopping");
                break;
            }
        }
        return plan.isEmpty() ? null : plan;
    }

    // --- Execution ---

    private boolean executeAction(ActionEntry action, Game game) {
        switch (action.actionType) {
            case PLAY_LAND: return doPlayLand(action, game);
            case CAST_SPELL: return doCastSpell(action, game);
            case ACTIVATE_ABILITY:
                if (action.sourceZone == Zone.HAND) {
                    return activateHandAbility(action, game);
                }
                return activatePermanentAbility(action, game);
            case SKIP_TO_NEXT_TURN:
                skipMode = SkipMode.SKIP_NEXT_TURN;
                skipSetAtTurn = game.getTurnNum();
                pass(game); return true;
            case SKIP_TO_END_STEP:
                skipMode = SkipMode.SKIP_END_STEP;
                skipSetAtTurn = game.getTurnNum();
                skipSetAtPhaseStep = game.getTurnStepType().ordinal();
                pass(game); return true;
            case SKIP_STACK:
                skipMode = SkipMode.SKIP_STACK;
                skipSetAtTurn = game.getTurnNum();
                skipSetAtPhaseStep = game.getTurnStepType().ordinal();
                pass(game); return true;
            case CANCEL_SKIP:
                skipMode = SkipMode.NONE;
                // Also cancel any lingering PlayerImpl skip flags
                sendPlayerAction(mage.constants.PlayerAction.PASS_PRIORITY_CANCEL_ALL_ACTIONS, game, null);
                pass(game); return true;
            case CONCEDE:
                concede(game);
                return true;
            default: pass(game); return false;
        }
    }

    private boolean doPlayLand(ActionEntry a, Game g) {
        Card c = g.getCard(a.cardOrAbilityId);
        if (c == null) return false;
        return playLand(c, g, false);
    }

    private boolean doCastSpell(ActionEntry a, Game g) {
        Card c = g.getCard(a.cardOrAbilityId);
        if (c == null) return false;
        pendingAltCost = a.useAltCost;
        pendingManaPlan = a.manaPlan;
        try {
            boolean result = playCard(c, g, a.noMana, null);
            return result;
        } finally {
            pendingAltCost = false;
            pendingManaPlan = null;
        }
    }

    private boolean activatePermanentAbility(ActionEntry a, Game g) {
        Permanent p = g.getPermanent(a.sourceId);
        if (p == null) return false;
        Map<UUID, ActivatedAbility> abs = getPlayableActivatedAbilities(p, Zone.BATTLEFIELD, g);
        ActivatedAbility ab = abs.get(a.cardOrAbilityId);
        if (ab == null) return false;
        pendingManaPlan = a.manaPlan;
        try {
            return activateAbility(ab, g);
        } finally {
            pendingManaPlan = null;
        }
    }

    /**
     * Activate a hand-zone activated ability (cycling, channel, etc.).
     * The source is a card in hand, not a battlefield permanent, so
     * activatePermanentAbility's getPermanent() lookup does not apply.
     * Mirrors PlayerImpl.activateAbility's non-permanent path: re-check
     * canActivate, then activate. The ability is looked up by id from the
     * card's current abilities because getPlayable() returns copies.
     */
    private boolean activateHandAbility(ActionEntry a, Game g) {
        Card card = g.getCard(a.sourceId);
        if (card == null) return false;
        ActivatedAbility ab = null;
        for (Ability ability : CardUtil.getAbilities(card, g)) {
            if (ability instanceof ActivatedAbility && a.cardOrAbilityId.equals(ability.getId())) {
                ab = (ActivatedAbility) ability;
                break;
            }
        }
        if (ab == null) return false;
        if (!ab.canActivate(getId(), g).canActivate()) return false;
        pendingManaPlan = a.manaPlan;
        try {
            return activateAbility(ab, g);
        } finally {
            pendingManaPlan = null;
        }
    }

    // ==================================================================
    //  MANA PAYMENT — Use LLM-specified mana plan, fall back to auto-tap
    // ==================================================================

    /**
     * Override ComputerPlayer.playMana to use the LLM's mana plan when available.
     * The mana plan is a list of permanent IDs to tap, in order, for each unpaid mana pip.
     * If the plan is exhausted or null, falls back to ComputerPlayer's heuristic auto-tapper.
     */
    @Override
    public boolean playMana(Ability ability, ManaCost unpaid, String promptText, Game game) {
        if (pendingManaPlan != null && !pendingManaPlan.isEmpty()) {
            // Try to tap a permanent from the plan for this mana pip.
            // The plan is a SET of lands to tap (the prompt documents it as
            // "action:3 mana:L1,L3" with no ordering contract), but ManaCostsImpl.pay
            // asks for pips in cost-string order ({2}{W}{W} asks generic first). A
            // plan listed in a different order than pip order — e.g.
            // Island,Fortress,Island,Fortress for {2}{W}{W} — pays the cost as a set
            // but fails pip-by-pip if consumed blindly. So: scan remaining plan
            // entries for the first one whose mana can pay THIS pip; only if none can
            // does the entry fail. (Observed 4× as auto-restore loops in
            // mirror matches.)
            UUID permId = null;
            int permIdx = -1;
            for (int i = 0; i < pendingManaPlan.size(); i++) {
                UUID candidateId = pendingManaPlan.get(i);
                Permanent candidate = game.getPermanent(candidateId);
                if (candidate == null || !candidate.isControlledBy(getId())) {
                    continue;
                }
                if (canPermanentPayPip(candidate, unpaid, game)) {
                    permId = candidateId;
                    permIdx = i;
                    break;
                }
            }
            if (permId != null) {
                pendingManaPlan.remove(permIdx);
            } else {
                // No plan entry pays this pip — fall through to the failure/circuit-
                // breaker path below with the head entry, preserving the original
                // throw-on-bad-plan behavior.
                permId = pendingManaPlan.remove(0);
            }
            Permanent perm = game.getPermanent(permId);
            if (perm != null && perm.isControlledBy(getId())) {
                // Find the best mana ability on this permanent — the one that produces
                // the most mana usable toward the unpaid cost. Lands like Eldrazi Temple
                // have multiple tap abilities ({T}: Add {C} vs {T}: Add {C}{C}); the LLM
                // specifies the land, but we must pick the ability that produces the most
                // mana so the cast doesn't fail on higher-cost spells.
                Map<UUID, ActivatedManaAbilityImpl> manaAbs = getUseableManaAbilities(perm, Zone.BATTLEFIELD, game);
                ActivatedManaAbilityImpl bestAb = null;
                int bestManaCount = 0;
                for (ActivatedManaAbilityImpl manaAb : manaAbs.values()) {
                    for (Mana netMana : manaAb.getNetMana(game)) {
                        // Only consider abilities whose mana can contribute to the unpaid cost.
                        // Use testPay (not includesMana) — includesMana is color-sensitive and
                        // rejects colored mana for generic pips, causing multi-netMana lands
                        // (e.g. Ancient Tomb {C}{C}) to be skipped when paying {2}. testPay
                        // delegates to each cost component (GenericManaCost accepts any color,
                        // ColoredManaCost checks exact color), matching ComputerPlayer's
                        // playManaHandling pattern (ComputerPlayer.java:445).
                        if (unpaid.testPay(netMana)) {
                            int count = netMana.count();
                            if (count > bestManaCount) {
                                bestManaCount = count;
                                bestAb = manaAb;
                            }
                        }
                    }
                }
                if (bestAb != null && activateAbility(bestAb, game)) {
                    // Success — reset the circuit breaker
                    lastManaFailPermId = null;
                    lastManaFailSignature = null;
                    manaFailCount = 0;
                    return true;
                }
            }
            // Fail-soft on a bad plan (first failure) — do NOT throw.
            //
            // Rationale (a Ninjutsu plan at T6 killed the
            // game): the previous behavior threw on the FIRST plan
            // entry failure. That throw escapes into GameImpl.playPriority's
            // error path → auto-restore re-plays the identical decision → a
            // deterministic model repeats the identical bad plan → FATAL loop
            // until "Too many errors, game will be end" (the gemma4 18×FATAL
            // death spiral). One bad
            // mana plan ending a whole game is disproportionate: refusing the
            // plan IS the honest outcome, and the engine already has a clean
            // decline contract for it.
            //
            // return false from playMana → ManaCostsImpl.pay returns false →
            // AbilityImpl.activate returns false → PlayerImpl.cast/playAbility
            // restoreState(bookmark) — un-doing any partial mana taps — and the
            // action is rejected. promptAndExecute then feeds
            // "YOUR LAST ACTION FAILED … choose a DIFFERENT action" back to the
            // LLM on its retry loop. The plan itself is discarded so the
            // re-prompt starts clean; the model decides again (Fail Fast's
            // "no silent fallback" is preserved — we substitute no decision).
            //
            // Circuit breaker (unchanged escalation): if the SAME permanent
            // fails the SAME unpaid cost 3 times in a row (deterministic
            // re-prompt loop), throw so the game error path surfaces it loudly
            // instead of spinning prompt retries.
            String unpaidSig = unpaid.toString();
            if (permId.equals(lastManaFailPermId) && unpaidSig.equals(lastManaFailSignature)) {
                manaFailCount++;
            } else {
                lastManaFailPermId = permId;
                lastManaFailSignature = unpaidSig;
                manaFailCount = 1;
            }
            if (manaFailCount >= 3) {
                int failCount = manaFailCount;
                addSelfLog(gameTurnStep(game) + ": Bad mana plan repeated " + failCount
                        + "x: permanent " + permanentLabel(game, permId) + " cannot pay " + unpaidSig
                        + " — giving up on this decision (game error)");
                pendingManaPlan.clear();
                lastManaFailPermId = null;
                lastManaFailSignature = null;
                manaFailCount = 0;
                throw new RuntimeException("LLM mana plan entry failed for permanent "
                        + permanentLabel(game, permId)
                        + " — could not produce required mana (bad plan repeated "
                        + failCount + "x after declines). Falling back would mask the LLM's bad plan.");
            }
            addSelfLog(gameTurnStep(game) + ": Engine rejected the mana plan (permanent "
                    + permanentLabel(game, permId) + " cannot pay " + unpaidSig + ") — action declined, reconsidering");
            if (config.isVerboseLogging())
                logger.info("LLM bad mana plan declined (permanent " + permanentLabel(game, permId)
                        + " cannot pay " + unpaidSig + "), count=" + manaFailCount);
            pendingManaPlan.clear();
            lastManaFailPermId = permId;      // keep the breaker armed across the re-prompt
            lastManaFailSignature = unpaidSig;
            return false;
        }
        // No mana plan — use heuristic auto-tapper
        return super.playMana(ability, unpaid, promptText, game);
    }

    /**
     * Whether a permanent has a usable mana ability whose net mana can contribute
     * to the given unpaid cost pip. Mirrors the testPay-based selection in
     * {@link #playMana(Ability, ManaCost, String, Game)} — testPay, not includesMana,
     * so generic pips accept any color.
     */
    private boolean canPermanentPayPip(Permanent perm, ManaCost unpaid, Game game) {
        Map<UUID, ActivatedManaAbilityImpl> manaAbs = getUseableManaAbilities(perm, Zone.BATTLEFIELD, game);
        for (ActivatedManaAbilityImpl manaAb : manaAbs.values()) {
            for (Mana netMana : manaAb.getNetMana(game)) {
                if (unpaid.testPay(netMana)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ==================================================================
    //  YES/NO DECISIONS — LLM-driven (miracle, Delver, "may" abilities, etc.)
    // ==================================================================

    @Override
    public boolean chooseUse(Outcome outcome, String message, Ability source, Game game) {
        return chooseUse(outcome, message, null, null, null, source, game);
    }

    @Override
    public boolean chooseUse(Outcome outcome, String message, String secondMessage,
                             String trueText, String falseText, Ability source, Game game) {
        if (config == null || message == null) return super.chooseUse(outcome, message, secondMessage, trueText, falseText, source, game);

        StringBuilder prompt = new StringBuilder();
        prompt.append("=== YES/NO DECISION ===\n");
        prompt.append("Question: ").append(message).append("\n");
        if (secondMessage != null && !secondMessage.isEmpty()) {
            prompt.append("Hint: ").append(secondMessage).append("\n");
        }

        // Source card context
        if (source != null) {
            MageObject srcObj = game.getObject(source.getSourceId());
            if (srcObj != null) {
                prompt.append("Source: ").append(srcObj.getName()).append("\n");
                String rule = source.getRule();
                if (rule != null && !rule.isEmpty()) {
                    prompt.append("Rules: ").append(rule).append("\n");
                }
            }
        }

        // Compact game state
        prompt.append("\n=== GAME STATE ===\n");
        // Phase suppressed when identical to Step (see LLMGameSerializer.formatTurnPhaseStep).
        prompt.append(LLMGameSerializer.formatTurnPhaseStep(game)).append("\n");
        for (Player p : game.getPlayers().values()) {
            prompt.append("  ").append(p.getName());
            if (p.getId().equals(getId())) prompt.append(" (YOU)");
            prompt.append(": Life ").append(p.getLife())
                  .append(", Hand ").append(p.getHand().size()).append("\n");
        }

        // Floating mana — critical for Ward/payment decisions
        prompt.append("\nFloating mana: ");
        if (getManaPool().isEmpty()) {
            prompt.append("None\n");
        } else {
            prompt.append(getManaPool().getMana().toString()).append("\n");
        }

        // Hand for context
        prompt.append("\nYour hand:\n");
        for (Card card : getHand().getCards(game)) {
            prompt.append("  ").append(LLMGameSerializer.formatCardForChoiceOnce(game, getId(), card)).append("\n");
        }

        if (trueText != null && falseText != null) {
            prompt.append("\nOptions:\n");
            prompt.append("  YES: ").append(trueText).append("\n");
            prompt.append("  NO: ").append(falseText).append("\n");
        } else {
            prompt.append("\nReply YES to accept, or NO to decline.\n");
        }
        prompt.append("\nReply with ONLY the word 'yes' or the word 'no' — nothing else.");

        if (config.isVerboseLogging())
            logger.info("=== LLM YES/NO ===\n" + prompt + "\n=== END ===");

        LLMResponse resp = chatAndRecord(game, prompt.toString(),
                "YN: " + message.substring(0, Math.min(message.length(), 80)),
                "yesno", YES_NO_VALIDATOR);

        if (config.isVerboseLogging()) {
            logger.info("LLM yes/no response: '" + resp.answer + "'");
            if (resp.thinking != null && !resp.thinking.isEmpty())
                logger.info("=== LLM THINKING ===\n" + resp.thinking + "\n=== END ===");
        }

        // First line only — chatAndRecord accepts first-line-valid answers
        // (passesValidation), so trailing reasoning must not influence the
        // yes/no interpretation (e.g. reasoning containing "not" or "pass"
        // must not flip the decision).
        String decision = firstLineOf(resp.answer).toLowerCase();
        // Narrow exact-match parse — the prompt promises 'yes' or 'no' and
        // nothing else, so anything else crashes Fail-Fast.
        // Parser, validator (YES_NO_VALIDATOR), and prompt all agree on
        // exactly 'yes'/'no' — no synonym table ("not" contains "no",
        // "pass" is a combat/legal term, etc.).
        boolean answer;
        if (decision.equals("yes")) {
            answer = true;
        } else if (decision.equals("no")) {
            answer = false;
        } else {
            // Unrecognized — throw rather than silently defaulting
            throw new RuntimeException("LLM yes/no: unrecognized response '" + resp.answer
                    + "' for question: " + message);
        }
        addSelfLog(gameTurnStep(game) + ": " + (answer ? "Yes" : "No") + " — " + message);
        return answer;
    }

    // ==================================================================
    //  CHOICE-BASED SELECTIONS — LLM-driven (Cabal Therapy, named choices, etc.)
    //  Small lists get numbered options. Huge lists (all card names) use free-text
    //  with validation and retry.
    // ==================================================================

    @Override
    public boolean choose(Outcome outcome, Choice choice, Game game) {
        if (config == null || choice == null) return super.choose(outcome, choice, game);

        // Auto-accept alt cost prompt when LLM explicitly chose an alt-cost action
        // The engine's alt cost dialog uses keyChoices ("1", "2", etc.),
        // message is "You may choose an alternative cost"
        if (pendingAltCost && choice.getMessage() != null
                && choice.getMessage().toLowerCase().contains("alternative cost")) {
            pendingAltCost = false;
            // Accept the first alt cost option (key "1")
            if (choice.getKeyChoices().containsKey("1")) {
                choice.setChoiceByKey("1", false);
                addSelfLog(gameTurnStep(game) + ": Auto-accepted alt cost option 1 ("
                        + choice.getMessage() + ")");
                if (config.isVerboseLogging())
                    logger.info("LLM auto-accepted alt cost option 1 for pendingAltCost");
                return true;
            }
        }

        // DETERMINISTIC ANSWER for the engine's alternative-cost dialog on a
        // NORMAL cast.
        //
        // AbilityImpl.activateAlternateOrAdditionalCosts opens this keyChoices-only
        // dialog for EVERY cast that has any alternative cost available (e.g. Dash):
        // keys "1".."n" are the alternatives and the LAST item is always
        // "Cast with no alternative cost: <cost>". The dialog carries no string
        // choices, so without this branch it fell through to super.choose() ->
        // ComputerPlayer.choose -> ChoiceImpl.setRandomChoice, whose KEY-MODE branch
        // picked a RANDOM key — a 50% coin flip that executed the Dash cast (game-
        // losing: the creature returns to hand at end step) after the LLM had
        // deliberately chosen the normal cast. Contract: the LLM's normal cast is
        // honored exactly — pick the no-alternative-cost key deterministically.
        //
        // The no-alt item is recognized by its VALUE PREFIX, not by key index
        // (alternatives are unbound in count). Fail fast if a mandatory dialog
        // ("Choose an alternative cost", no no-alt item) ever arrives — never guess.
        if (choice.getMessage() != null
                && choice.getMessage().toLowerCase().contains("alternative cost")
                && choice.isKeyChoice()) {
            String noAltKey = null;
            for (Map.Entry<String, String> e : choice.getKeyChoices().entrySet()) {
                if (e.getValue() != null && e.getValue().startsWith("Cast with no alternative cost")) {
                    noAltKey = e.getKey();
                    break;
                }
            }
            if (noAltKey != null) {
                choice.setChoiceByKey(noAltKey, false);
                addSelfLog(gameTurnStep(game) + ": Cast with no alternative cost (answered the "
                        + "engine's alt-cost dialog for the normal cast)");
                if (config.isVerboseLogging())
                    logger.info("LLM answered alt-cost dialog with the no-alternative-cost key (normal cast)");
                return true;
            }
            // Mandatory dialog: LLMPlayer's action list never offers mandatory-alt
            // casts, so this is an engine path we don't model — crash instead of
            // letting the random ComputerPlayer fallback answer it.
            throw new RuntimeException("LLMPlayer: engine presented a MANDATORY alternative-cost dialog "
                    + "with no \"Cast with no alternative cost\" item during a normal cast: "
                    + choice.getMessage() + " / " + choice.getSubMessage()
                    + " keys=" + choice.getKeyChoices().keySet());
        }

        Set<String> allChoices = choice.getChoices();
        if (allChoices == null || allChoices.isEmpty()) return super.choose(outcome, choice, game);

        int size = allChoices.size();
        if (size <= 100) {
            // Small enough to enumerate
            return chooseSmallSet(outcome, choice, game);
        } else {
            // Huge set (e.g., all card names) — free-text with validation
            return chooseLargeSet(outcome, choice, game);
        }
    }

    /**
     * Enumerate a small choice set (≤100) as numbered options.
     */
    private boolean chooseSmallSet(Outcome outcome, Choice choice, Game game) {
        List<String> options = new ArrayList<>(choice.getChoices());
        Collections.sort(options);

        StringBuilder prompt = new StringBuilder();
        prompt.append("=== CHOOSE ONE ===\n");
        prompt.append(choice.getMessage()).append("\n");
        if (choice.getSubMessage() != null && !choice.getSubMessage().isEmpty()) {
            prompt.append(choice.getSubMessage()).append("\n");
        }
        prompt.append("\n");
        for (int i = 0; i < options.size(); i++) {
            prompt.append(i + 1).append(". ").append(options.get(i)).append("\n");
        }
        prompt.append("\nReply with ONLY the number of your choice (e.g., '3').");

        if (config.isVerboseLogging())
            logger.info("=== LLM CHOICE (small) ===\n" + prompt + "\n=== END ===");

        LLMResponse resp = chatAndRecord(game, prompt.toString(),
                "Choice: " + choice.getMessage(),
                "choose", SIMPLE_NUMBER_VALIDATOR);

        int idx = parseActionNumber(resp.answer);
        if (idx >= 1 && idx <= options.size()) {
            choice.setChoice(options.get(idx - 1));
            addSelfLog(gameTurnStep(game) + ": Chose \"" + options.get(idx - 1) + "\" (" + choice.getMessage() + ")");
            return true;
        }
        throw new RuntimeException("LLM choice: invalid index " + idx + " (valid: 1-" + options.size()
                + "). Answer: '" + resp.answer + "' for: " + choice.getMessage());
    }

    /**
     * Handle a huge choice set (e.g., all Magic card names for Cabal Therapy).
     * Asks the LLM for a card name as free text, validates against the choice set,
     * and retries once with an error message if the name is invalid.
     */
    private boolean chooseLargeSet(Outcome outcome, Choice choice, Game game) {
        String message = choice.getMessage();
        if (message == null) message = "Select a card";

        // Build prompt: include source context + ask for a card name
        StringBuilder prompt = new StringBuilder();
        prompt.append("=== CARD NAME CHOICE ===\n");
        prompt.append("Reason: ").append(message).append("\n");
        if (choice.getSubMessage() != null && !choice.getSubMessage().isEmpty()) {
            prompt.append("Hint: ").append(choice.getSubMessage()).append("\n");
        }

        // Compact game state
        prompt.append("\n=== GAME STATE ===\n");
        // Phase suppressed when identical to Step (see LLMGameSerializer.formatTurnPhaseStep).
        prompt.append(LLMGameSerializer.formatTurnPhaseStep(game)).append("\n");
        for (Player p : game.getPlayers().values()) {
            prompt.append("  ").append(p.getName());
            if (p.getId().equals(getId())) prompt.append(" (YOU)");
            prompt.append(": Life ").append(p.getLife())
                  .append(", Hand ").append(p.getHand().size())
                  .append(", Graveyard ").append(p.getGraveyard().size()).append("\n");
            // Show graveyard contents briefly
            List<UUID> gy = p.getGraveyard().stream().collect(Collectors.toList());
            if (!gy.isEmpty()) {
                prompt.append("    Graveyard: ");
                prompt.append(gy.stream().limit(5).map(id -> {
                    Card c = game.getCard(id);
                    return c != null ? c.getName() : "?";
                }).collect(Collectors.joining(", ")));
                if (gy.size() > 5) prompt.append(", ...");
                prompt.append("\n");
            }
        }

        // Show hand for context
        prompt.append("\nYour hand:\n");
        for (Card card : getHand().getCards(game)) {
            prompt.append("  ").append(LLMGameSerializer.formatCardForChoiceOnce(game, getId(), card)).append("\n");
        }

        prompt.append("\nReply with ONLY the exact text of your chosen option.\n");
        prompt.append("It must exactly match one of the legal options. Do not include quotes or extra text.");

        // Validate against valid choices but do NOT include the full card list
        // in the prompt — the LLM has its own card knowledge.
        Set<String> validChoices = choice.getChoices();

        int maxRetries = 2;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            if (config.isVerboseLogging())
                logger.info("=== LLM CHOICE (large, attempt " + (attempt + 1) + ") ===\n" + prompt + "\n=== END ===");

            LLMResponse resp = chatAndRecord(game, prompt.toString(),
                    "Name a card: " + message,
                    "select");

            // Strip the mimicry reasoning fence before matching a card name.
            String cardName = stripReasoningFence(resp.answer).trim()
                    .replaceAll("^\"|\"$|^'|'$", "")  // strip quotes
                    .replaceAll("^I choose |^Choose |^Select |^Name: ", "");

            if (config.isVerboseLogging())
                logger.info("LLM named card: '" + cardName + "'");

            // Check exact match first
            if (validChoices.contains(cardName)) {
                choice.setChoice(cardName);
                addSelfLog(gameTurnStep(game) + ": Named \"" + cardName + "\" (" + message + ")");
                return true;
            }

            // Try case-insensitive
            for (String valid : validChoices) {
                if (valid.equalsIgnoreCase(cardName)) {
                    choice.setChoice(valid);
                    addSelfLog(gameTurnStep(game) + ": Named \"" + valid + "\" (" + message + ")");
                    return true;
                }
            }

            // Try prefix/substring match (for common abbreviations)
            String lower = cardName.toLowerCase();
            List<String> candidates = validChoices.stream()
                    .filter(c -> c.toLowerCase().startsWith(lower))
                    .limit(5)
                    .collect(Collectors.toList());

            if (candidates.size() == 1) {
                // Unique prefix match
                choice.setChoice(candidates.get(0));
                addSelfLog(gameTurnStep(game) + ": Named \"" + candidates.get(0) + "\" (" + message + ")");
                return true;
            }

            if (attempt < maxRetries - 1) {
                // Retry with error
                prompt = new StringBuilder();
                prompt.append("ERROR: '").append(cardName).append("' is not a valid choice for '").append(message).append("'.\n");
                if (!candidates.isEmpty()) {
                    prompt.append("Did you mean one of these? ");
                    prompt.append(candidates.stream().collect(Collectors.joining(", ")));
                    prompt.append("\n");
                }
                prompt.append("\nPlease reply with ONLY the exact card name.");

                if (config.isVerboseLogging())
                    logger.info("LLM invalid name '" + cardName + "', retrying...");
            }
        }

        // All retries exhausted — throw rather than picking a random card
        throw new RuntimeException("LLM failed to name a valid card after " + maxRetries
                + " attempts for: " + message);
    }

    // ==================================================================
    //  MISCELLANEOUS DECISIONS — LLM-driven
    //  (piles, modes, X values, trigger ordering, replacement effects, etc.)
    // ==================================================================

    // --- Pile choice (Fact or Fiction, Liliana of the Veil, Do or Die, etc.) ---

    @Override
    public boolean choosePile(Outcome outcome, String message, List<? extends Card> pile1,
                              List<? extends Card> pile2, Game game) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("=== CHOOSE PILE ===\n");
        prompt.append("Question: ").append(message).append("\n");

        String situation = LLMGameSerializer.buildSituationSummary(game, getId());
        prompt.append("Board: ").append(situation).append("\n\n");

        prompt.append("Pile 1:\n");
        if (pile1.isEmpty()) {
            prompt.append("  (empty)\n");
        } else {
            for (Card c : pile1)
                prompt.append("  - ").append(LLMGameSerializer.formatCardForChoiceOnce(game, getId(), c)).append("\n");
        }

        prompt.append("\nPile 2:\n");
        if (pile2.isEmpty()) {
            prompt.append("  (empty)\n");
        } else {
            for (Card c : pile2)
                prompt.append("  - ").append(LLMGameSerializer.formatCardForChoiceOnce(game, getId(), c)).append("\n");
        }

        prompt.append("\nReply with ONLY: 1 or 2");

        if (config.isVerboseLogging())
            logger.info("=== LLM PILE ===\n" + prompt + "\n=== END ===");

        LLMResponse resp = chatAndRecord(game, prompt.toString(), "Pile: " + message, "pile", PILE_VALIDATOR);

        if (config.isVerboseLogging() && resp.thinking != null && !resp.thinking.isEmpty())
            logger.info("=== LLM THINKING ===\n" + resp.thinking + "\n=== END ===");

        int idx = parseActionNumber(resp.answer);
        if (idx == 1) {
            addSelfLog(gameTurnStep(game) + ": Chose pile 1 (" + message + ")");
            return true;
        }
        if (idx == 2) {
            addSelfLog(gameTurnStep(game) + ": Chose pile 2 (" + message + ")");
            return false;
        }
        throw new RuntimeException("choosePile: invalid pile number " + idx
                + " (expected 1 or 2). Answer: '" + resp.answer + "'");
    }

    // --- Modal spells/abilities (Kolaghan's Command, Prismatic Ending, etc.) ---

    /** Max total LLM calls chooseMode() may make: 1 initial + 2 corrections. */
    public static final int MODE_CHOICE_MAX_ATTEMPTS = 3;

    /**
     * One selectable mode: its canonical number (1-based position in the
     * card's full printed mode list, matching the game log's "(mode N)")
     * plus the Mode object itself.
     */
    public static final class CanonicallyNumberedMode {
        public final int canonicalNumber;
        public final Mode mode;
        CanonicallyNumberedMode(int canonicalNumber, Mode mode) {
            this.canonicalNumber = canonicalNumber;
            this.mode = mode;
        }
    }

    /**
     * Build the canonical-mode mapping for chooseMode(). Canonical numbers are
     * STABLE per card: 1-based position in the full printed mode list
     * ({@code modes.values()}, LinkedHashMap insertion order) — the same
     * numbering the game log prints as "(mode N)" (AbilityImpl.getTargetDescription),
     * NOT the position in the per-call filtered list. Filtering already-selected
     * or currently-unchoosable modes out of the display list therefore does not
     * renumber the rest.
     *
     * Public static (no Game/instance state) so unit tests can exercise the
     * mapping and answer resolution without a full Game fixture.
     *
     * @param modes          the ability's full mode set
     * @param choosableModes modes that pass modes.getAvailableModes + the
     *                       canChoose target filter (the displayed list)
     * @return canonical-numbered entries for the choosable modes
     */
    public static List<CanonicallyNumberedMode> canonicalModeChoices(Modes modes, List<Mode> choosableModes) {
        List<CanonicallyNumberedMode> res = new ArrayList<>();
        int canonical = 0;
        for (Mode mode : modes.values()) {
            canonical++;
            if (choosableModes.contains(mode)) {
                res.add(new CanonicallyNumberedMode(canonical, mode));
            }
        }
        return res;
    }

    /**
     * Resolve the LLM's mode answer against the canonical numbering: valid iff
     * the parsed number is one of the displayed canonical numbers. Out-of-range
     * or unparsable answers return null (caller re-prompts or throws); there is
     * deliberately no positional fallback.
     */
    public static Mode resolveCanonicalModeAnswer(List<CanonicallyNumberedMode> choices, String answer) {
        int num = parseFirstNumber(answer);
        if (num < 1) return null;
        for (CanonicallyNumberedMode c : choices) {
            if (c.canonicalNumber == num) return c.mode;
        }
        return null;
    }

    /**
     * Build the "modes already chosen" line for the prompt, using canonical
     * numbers so it stays consistent across successive chooseMode calls.
     * A mode chosen more than once (mayChooseSameModeMoreThanOnce) is listed
     * once per canonical number. Empty string when nothing is chosen yet.
     */
    public static String alreadyChosenModesDisplay(Modes modes) {
        List<Integer> nums = new ArrayList<>();
        int canonical = 0;
        for (Mode mode : modes.values()) {
            canonical++;
            // getSelectedStats > 0 covers both the original id and duplicate
            // selections (2x selects register as a duplicate of the original).
            if (modes.getSelectedStats(mode.getId()) > 0) {
                nums.add(canonical);
            }
        }
        if (nums.isEmpty()) return "";
        return "You already chose mode(s): " + joinNumbers(nums) + "\n";
    }

    @Override
    public Mode chooseMode(Modes modes, Ability source, Game game) {
        // Note: Do NOT early-return when modes.getMode() is pre-set — that
        // causes the LLM to miss ETB keyword choices (e.g. Sire of Seven Deaths)
        // where core XMage pre-assigns a mode before chooseMode is called.
        // The available.size() == 1 check below handles the single-mode case.

        List<Mode> available = modes.getAvailableModes(source, game).stream()
                .filter(mode -> !modes.getSelectedModes().contains(mode.getId()))
                .filter(mode -> mode.getTargets().canChoose(source.getControllerId(), source, game))
                .collect(Collectors.toList());

        if (available.isEmpty()) {
            // Null is the correct return here: the stream above filters out
            // modes that are already in modes.getSelectedModes(), so an empty
            // list means every mode has already been selected (the normal
            // completion case for "choose N" spells where all N are chosen).
            // It can also mean no mode can currently target — but that would
            // be a core XMage issue, not an LLM player decision. Returning null
            // signals "no further mode choice needed" to the caller.
            return null;
        }
        if (available.size() == 1) return available.get(0);

        // Canonical numbering: position in the card's FULL printed mode list
        // (modes.values()), so numbers match the card and the game log's
        // "(mode N)" across successive calls — the second prompt for a
        // "choose two" spell does not renumber the remaining modes.
        List<CanonicallyNumberedMode> choices =
                canonicalModeChoices(modes, available);
        List<Integer> validNumbers = choices.stream()
                .map(c -> c.canonicalNumber).collect(Collectors.toList());

        MageObject srcObj = game.getObject(source.getSourceId());
        String cardName = srcObj != null ? srcObj.getName() : "unknown";

        StringBuilder prompt = new StringBuilder();
        prompt.append("=== CHOOSE MODE ===\n");
        prompt.append("Card: ").append(cardName).append("\n");

        String situation = LLMGameSerializer.buildSituationSummary(game, getId());
        prompt.append("Board: ").append(situation).append("\n\n");

        String alreadyChosen = alreadyChosenModesDisplay(modes);
        if (!alreadyChosen.isEmpty()) {
            prompt.append(alreadyChosen);
            prompt.append("\n");
        }

        prompt.append("Available modes (choose by their printed mode number):\n");
        for (CanonicallyNumberedMode c : choices) {
            String text = c.mode.getEffects().getText(c.mode);
            prompt.append(c.canonicalNumber).append(". ").append(text).append("\n");
        }
        prompt.append("\nReply with ONLY the number of your choice"
                + " (valid numbers: " + joinNumbers(validNumbers) + ").");

        if (config.isVerboseLogging())
            logger.info("=== LLM MODE ===\n" + prompt + "\n=== END ===");

        LLMResponse resp = chatAndRecord(game, prompt.toString(), "Mode: " + cardName, "mode", SIMPLE_NUMBER_VALIDATOR);

        if (config.isVerboseLogging() && resp.thinking != null && !resp.thinking.isEmpty())
            logger.info("=== LLM THINKING ===\n" + resp.thinking + "\n=== END ===");

        // Resolve against canonical numbers only. Up to MODE_CHOICE_MAX_ATTEMPTS
        // total LLM calls: on an out-of-range/unparsable answer re-prompt with
        // a correction message mirroring chatAndRecord's format-correction
        // pattern (quote the bad answer, list the valid numbers, lower the
        // effort one rung). No positional guess-fallback. After the last
        // attempt, throw — Fail Fast (the engine restores the turn on a
        // RuntimeException, which is the accepted failure mode).
        int turn = game.getTurnNum();
        String phaseStep = gameStepLabel(game);
        for (int attempt = 1; attempt <= MODE_CHOICE_MAX_ATTEMPTS; attempt++) {
            Mode chosen = resolveCanonicalModeAnswer(choices, resp.answer);
            if (chosen != null) {
                int canonicalNumber = canonicalNumberOf(choices, chosen);
                addSelfLog(gameTurnStep(game) + ": Chose mode " + canonicalNumber + " for " + cardName);
                return chosen;
            }
            if (attempt == MODE_CHOICE_MAX_ATTEMPTS) {
                break;
            }
            logger.warn("LLM mode answer out of range (attempt " + attempt + "): '" + resp.answer
                    + "' for " + cardName + " — re-prompting with correction (valid: "
                    + joinNumbers(validNumbers) + ")");
            String correctionPrompt = "Your previous answer was: \"" + resp.answer + "\"\n"
                    + "That is not a valid mode number. Valid mode numbers for " + cardName
                    + " are: " + joinNumbers(validNumbers) + ".\n"
                    + "Reply with ONLY one of those numbers — no reasoning, no explanation, "
                    + "no preamble, no \"Decision:\" prefix. Just the number itself.";
            // Same lowered-effort treatment as chatAndRecord's format
            // correction (attempt index 3 = one rung below configured effort).
            String correctionEffort = effortForAttempt(3);
            resp = chatAndRecordWithRetryWithEffort(correctionPrompt, "Mode correction: " + cardName,
                    "mode-corrected", turn, phaseStep, correctionEffort);
            if (resp.answer != null) {
                String cleaned = stripDecisionPrefix(resp.answer);
                if (!cleaned.equals(resp.answer)) {
                    resp = new LLMResponse(resp.thinking, cleaned, resp.usage);
                }
            }
            // Record the corrected exchange for replay, mirroring the
            // format-correction record in chatAndRecord (same per-game scoping).
            if (!game.isSimulation()) {
                recordReplayExchange(game, game.getGameStates().getSize() - 1, "mode-corrected",
                        "Mode correction: " + cardName, resp.thinking, resp.answer,
                        config.getModel().replaceAll("[:/].*", ""), turn, phaseStep);
            }
        }
        throw new RuntimeException("LLM mode: invalid answer '" + resp.answer + "' (valid mode numbers: "
                + joinNumbers(validNumbers) + "). Card: " + cardName);
    }

    /** Phase/step label mirroring chatAndRecord's replay-record formatting. */
    private static String gameStepLabel(Game game) {
        String phaseStep = game.getPhase() != null ? game.getPhase().toString() : "?";
        if (game.getStep() != null) {
            phaseStep += " / " + game.getStep().toString();
        }
        return phaseStep;
    }

    /** Canonical number of a resolved choice (canonicalNumber of the entry
     *  whose Mode instance equals the given one). */
    private static int canonicalNumberOf(List<CanonicallyNumberedMode> choices, Mode chosen) {
        for (CanonicallyNumberedMode c : choices) {
            if (c.mode == chosen) return c.canonicalNumber;
        }
        throw new IllegalStateException("chosen mode not in canonical choices list");
    }

    /** "1, 3, 4" style join used in prompts and error messages. */
    private static String joinNumbers(List<Integer> nums) {
        return nums.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    // --- Trigger ability ordering (storm, simultaneous triggers on stack) ---

    @Override
    public TriggeredAbility chooseTriggeredAbility(List<TriggeredAbility> abilities, Game game) {
        if (abilities.isEmpty()) return null;
        if (abilities.size() == 1) return abilities.get(0);

        // Trivial-decision fast path: identical triggers from the same source
        // (e.g. two Fleshraker triggers) have no meaningful ordering — the LLM
        // looped on "the order doesn't matter functionally" for minutes at a time

        boolean allIdentical = true;
        String firstRule = abilities.get(0).getRule();
        UUID firstSource = abilities.get(0).getSourceId();
        for (int i = 1; i < abilities.size(); i++) {
            TriggeredAbility ab = abilities.get(i);
            if (!firstRule.equals(ab.getRule())
                    || !firstSource.equals(ab.getSourceId())) {
                allIdentical = false;
                break;
            }
        }
        if (allIdentical) {
            if (config.isVerboseLogging())
                logger.info("Trivial fast path: " + abilities.size()
                        + " identical triggers from same source — auto-ordering first");
            return abilities.get(0);
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("=== ORDER TRIGGERS ===\n");
        prompt.append("Multiple triggered abilities are waiting to go on the stack.\n");
        prompt.append("Choose which one to put on the stack first. Note: the first one placed\n");
        prompt.append("on the stack will be the LAST to resolve (stacks resolve top-to-bottom).\n\n");

        String situation = LLMGameSerializer.buildSituationSummary(game, getId());
        prompt.append("Board: ").append(situation).append("\n\n");

        for (int i = 0; i < abilities.size(); i++) {
            TriggeredAbility ab = abilities.get(i);
            MageObject src = game.getObject(ab.getSourceId());
            String srcName = src != null ? src.getName() : "unknown";
            prompt.append(i + 1).append(". ").append(srcName).append(" — ").append(ab.getRule()).append("\n");
        }
        prompt.append("\nReply with ONLY the number of which trigger to put on the stack FIRST.");

        if (config.isVerboseLogging())
            logger.info("=== LLM TRIGGER ORDER ===\n" + prompt + "\n=== END ===");

        LLMResponse resp = chatAndRecord(game, prompt.toString(), "Order triggers on stack", "order", SIMPLE_NUMBER_VALIDATOR);

        if (config.isVerboseLogging() && resp.thinking != null && !resp.thinking.isEmpty())
            logger.info("=== LLM THINKING ===\n" + resp.thinking + "\n=== END ===");

        int idx = parseActionNumber(resp.answer);
        if (idx >= 1 && idx <= abilities.size())
            return abilities.get(idx - 1);
        throw new RuntimeException("LLM trigger order: invalid index " + idx + " (valid: 1-"
                + abilities.size() + "). Answer: '" + resp.answer + "'");
    }

    // --- Replacement effect choice ---

    @Override
    public int chooseReplacementEffect(Map<String, String> effectsMap,
                                       Map<String, MageObject> objectsMap, Game game) {
        if (effectsMap.size() <= 1) return 0;

        StringBuilder prompt = new StringBuilder();
        prompt.append("=== CHOOSE REPLACEMENT EFFECT ===\n");
        prompt.append("Multiple replacement effects could apply. Choose which to use.\n\n");

        int i = 1;
        for (Map.Entry<String, String> entry : effectsMap.entrySet()) {
            String text = entry.getValue();
            prompt.append(i).append(". ").append(text).append("\n");
            i++;
        }
        prompt.append("\nReply with ONLY the number of the effect to apply.");

        if (config.isVerboseLogging())
            logger.info("=== LLM REPLACEMENT ===\n" + prompt + "\n=== END ===");

        LLMResponse resp = chatAndRecord(game, prompt.toString(), "Choose replacement effect", "replacement", SIMPLE_NUMBER_VALIDATOR);

        if (config.isVerboseLogging() && resp.thinking != null && !resp.thinking.isEmpty())
            logger.info("=== LLM THINKING ===\n" + resp.thinking + "\n=== END ===");

        int idx = parseActionNumber(resp.answer);
        // Return 0-based index into the map
        if (idx >= 1 && idx <= effectsMap.size())
            return idx - 1;
        throw new RuntimeException("LLM replacement effect: invalid index " + idx + " (valid: 1-"
                + effectsMap.size() + "). Answer: '" + resp.answer + "'");
    }

    // --- Numeric choice (how many counters, how many cards drawn, etc.) ---

    @Override
    public int getAmount(int min, int max, String message, Ability source, Game game) {
        if (min >= max) return min;

        StringBuilder prompt = new StringBuilder();
        prompt.append("=== CHOOSE AMOUNT ===\n");
        prompt.append("Question: ").append(message).append("\n");
        prompt.append("Range: ").append(min).append(" to ").append(max).append("\n");

        if (source != null) {
            MageObject srcObj = game.getObject(source.getSourceId());
            if (srcObj != null)
                prompt.append("Source: ").append(srcObj.getName()).append("\n");
        }

        String situation = LLMGameSerializer.buildSituationSummary(game, getId());
        prompt.append("Board: ").append(situation).append("\n");
        prompt.append("\nReply with ONLY a number between ").append(min).append(" and ").append(max).append(".");

        if (config.isVerboseLogging())
            logger.info("=== LLM AMOUNT ===\n" + prompt + "\n=== END ===");

        String amtMsg = (message != null && message.length() > 80) ? message.substring(0, 80) + "..." : message;
        LLMResponse resp = chatAndRecord(game, prompt.toString(), "Amount: " + amtMsg, "amount", SIMPLE_NUMBER_VALIDATOR);

        if (config.isVerboseLogging() && resp.thinking != null && !resp.thinking.isEmpty())
            logger.info("=== LLM THINKING ===\n" + resp.thinking + "\n=== END ===");

        try {
            // First line only — chatAndRecord accepts first-line-valid answers
            // (passesValidation), so trailing reasoning must not break the parse.
            int val = Integer.parseInt(firstLineOf(resp.answer));
            if (val < min || val > max) {
                throw new RuntimeException("LLM amount " + val + " out of range [" + min + "," + max
                        + "] for question: " + message);
            }
            addSelfLog(gameTurnStep(game) + ": Amount " + val + " (" + message + ")");
            return val;
        } catch (NumberFormatException e) {
            throw new RuntimeException("Failed to parse LLM amount response: '" + resp.answer + "'", e);
        }
    }

    // --- X cost announcement (Walking Ballista, Finale of Devastation, etc.) ---

    @Override
    public int announceX(int min, int max, String message, Game game, Ability source, boolean isManaPay) {
        if (min >= max) return min;

        StringBuilder prompt = new StringBuilder();
        prompt.append("=== ANNOUNCE X ===\n");
        prompt.append("Question: ").append(message).append("\n");
        prompt.append("Range: ").append(min).append(" to ");
        if (max == Integer.MAX_VALUE) {
            prompt.append("any (engine cap 100 for this decision)\n");
        } else {
            prompt.append(max).append("\n");
        }

        if (source != null) {
            MageObject srcObj = game.getObject(source.getSourceId());
            if (srcObj != null)
                prompt.append("Source: ").append(srcObj.getName()).append("\n");
        }

        String situation = LLMGameSerializer.buildSituationSummary(game, getId());
        prompt.append("Board: ").append(situation).append("\n");

        if (isManaPay) {
            prompt.append("\nThis X determines how much mana to pay. Reply with ONLY a number.");
        } else {
            String srcName = "the spell";
            if (source != null) {
                MageObject srcObj = game.getObject(source.getSourceId());
                if (srcObj != null) srcName = srcObj.getName();
            }
            prompt.append("\nThis X determines the value for ").append(srcName)
                  .append("'s effect. Reply with ONLY a number.");
        }

        if (config.isVerboseLogging())
            logger.info("=== LLM ANNOUNCE X ===\n" + prompt + "\n=== END ===");

        LLMResponse resp = chatAndRecord(game, prompt.toString(), "Announce X: " + message, "announcex", SIMPLE_NUMBER_VALIDATOR);

        if (config.isVerboseLogging() && resp.thinking != null && !resp.thinking.isEmpty())
            logger.info("=== LLM THINKING ===\n" + resp.thinking + "\n=== END ===");

        try {
            // First line only — chatAndRecord accepts first-line-valid answers
            // (passesValidation), so trailing reasoning must not break the parse.
            int val = Integer.parseInt(firstLineOf(resp.answer));
            int effectiveMax = (max == Integer.MAX_VALUE) ? 100 : max;
            int result = Math.max(min, Math.min(effectiveMax, val));
            addSelfLog(gameTurnStep(game) + ": Announced X=" + result + " (" + message + ")");
            return result;
        } catch (NumberFormatException e) {
            throw new RuntimeException("Failed to parse LLM announce-X response: '" + resp.answer + "'", e);
        }
    }

    // --- Target with amount (multi-target variable-cost spells like Arc Lightning, Rolling Thunder) ---

    @Override
    public boolean chooseTargetAmount(Outcome outcome, TargetAmount target, Ability source, Game game) {
        // Initialize and check if there's anything to do
        target.prepareAmount(source, game);
        if (target.getAmountRemaining() <= 0)
            return false;
        if (target.getMaxNumberOfTargets() == 0 && target.getMinNumberOfTargets() == 0)
            return false;

        // Derive candidates for the CHOOSING player, not the ability controller:
        // getAffectedAbilityControllerId returns the explicit ability controller when
        // set, else the choosing player (matches HumanPlayer/ComputerPlayer). For an
        // attack/annihilator sacrifice trigger the abilityController is null, so the
        // defender must be scoped to its OWN permanents — using source.getControllerId()
        // here offered the attacker's permanents to the defender.
        UUID controllerId = target.getAffectedAbilityControllerId(getId());
        if (target.isChoiceCompleted(controllerId, source, game, null))
            return false;

        Set<UUID> possible = target.possibleTargets(controllerId, source, game);
        if (possible.isEmpty())
            return false;

        // Build target list (cards + players, same pattern as chooseTarget)
        List<Card> cards = new ArrayList<>();
        List<Player> players = new ArrayList<>();
        for (UUID id : possible) {
            Card c = game.getCard(id);
            if (c != null) {
                cards.add(c);
            } else {
                Player p = game.getPlayer(id);
                if (p != null) {
                    players.add(p);
                } else {
                    Permanent perm = game.getPermanent(id);
                    if (perm != null) cards.add(perm);
                }
            }
        }
        if (cards.isEmpty() && players.isEmpty())
            return false;

        int remaining = target.getAmountRemaining();
        int minTargets = target.getMinNumberOfTargets();
        int maxTargets = target.getMaxNumberOfTargets();
        int alreadyChosen = target.getTargets().size();

        StringBuilder prompt = new StringBuilder();
        prompt.append("=== TARGET WITH AMOUNT ===\n");
        // Source card
        if (source != null) {
            MageObject srcObj = game.getObject(source.getSourceId());
            if (srcObj != null) {
                prompt.append("Source: ").append(srcObj.getName()).append("\n");
                String rule = source.getRule();
                if (rule != null && !rule.isEmpty())
                    prompt.append("Rules: ").append(rule).append("\n");
            }
        }
        prompt.append("Amount remaining to distribute: ").append(remaining).append("\n");
        prompt.append("Targets needed: ").append(minTargets).append(" to ").append(maxTargets)
              .append(" (already chosen: ").append(alreadyChosen).append(")\n");

        String situation = LLMGameSerializer.buildSituationSummary(game, getId());
        prompt.append("Board: ").append(situation).append("\n\n");

        prompt.append("Available targets:\n");
        for (int i = 0; i < cards.size(); i++) {
            Card c = cards.get(i);
            prompt.append(i + 1).append(". ").append(LLMGameSerializer.formatCardForChoiceOnce(game, getId(), c)).append("\n");
        }
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            int num = cards.size() + i + 1;
            prompt.append(num).append(". PLAYER: ").append(p.getName())
                  .append(" (life: ").append(p.getLife()).append(")");
            if (p.getId().equals(getId())) prompt.append(" [YOU]");
            prompt.append("\n");
        }

        // Cancel option for optional targets
        boolean optional = (minTargets == 0);
        if (optional) {
            prompt.append("0. Cancel — stop assigning (no more targets)\n");
        }

        prompt.append("\nReply with: TARGET_NUMBER AMOUNT (e.g., '3 5' to assign 5 damage to target #3)");
        prompt.append(" If you give only the target number, ALL remaining amount is assigned to it.");
        if (optional) {
            prompt.append(" Or reply '0' to stop assigning.");
        } else {
            prompt.append(".");
        }

        if (config.isVerboseLogging())
            logger.info("=== LLM TARGET AMOUNT ===\n" + prompt + "\n=== END ===");

        String srcName = (source != null) ? game.getObject(source.getSourceId()).getName() : "spell";
        LLMResponse resp = chatAndRecord(game, prompt.toString(), "Target+amount: " + srcName, "targetamount");

        if (config.isVerboseLogging() && resp.thinking != null && !resp.thinking.isEmpty())
            logger.info("=== LLM THINKING ===\n" + resp.thinking + "\n=== END ===");

        // Strict parse of the FIRST LINE only: require exactly two adjacent
        // integers "TARGET AMOUNT" (e.g., "3 5"). Reject ambiguous answers like
        // "deal to target 3, 5 damage" where the order could be misinterpreted.
        // Both regexes run on the first line so reasoning digits (a second
        // "N N" pair etc.) cannot leak into the parse.
        String targetAmountAnswer = firstLineOf(resp.answer);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s+(\\d+)").matcher(targetAmountAnswer);
        int targetIdx = -1;
        int amount = remaining; // default: assign all remaining
        if (m.find()) {
            targetIdx = Integer.parseInt(m.group(1));
            amount = Integer.parseInt(m.group(2));
        } else {
            // Try single number (target only, amount defaults to remaining)
            java.util.regex.Matcher m1 = java.util.regex.Pattern.compile("(\\d+)").matcher(targetAmountAnswer);
            if (m1.find()) {
                targetIdx = Integer.parseInt(m1.group(1));
            } else {
                throw new RuntimeException("chooseTargetAmount: could not parse target and amount from '"
                        + resp.answer + "' — expected 'TARGET AMOUNT' (e.g., '3 5')");
            }
        }

        // Check for cancel (0) — only offered when the prompt listed it
        // (optional targets, minTargets == 0). A mandatory distribution has no
        // cancel option; '0' there falls through to the invalid-index error.
        if (optional && targetIdx == 0) {
            addSelfLog(gameTurnStep(game) + ": Chose to stop assigning (cancel)");
            return false; // signal done
        }

        // Clamp amount
        amount = Math.max(1, Math.min(remaining, amount));

        // Resolve target index
        UUID chosenId = null;
        if (targetIdx >= 1 && targetIdx <= cards.size()) {
            chosenId = cards.get(targetIdx - 1).getId();
        } else if (targetIdx > cards.size() && targetIdx <= cards.size() + players.size()) {
            chosenId = players.get(targetIdx - cards.size() - 1).getId();
        }

        if (chosenId == null) {
            throw new RuntimeException("LLM target-amount: invalid index " + targetIdx
                    + " (valid: 1-" + (cards.size() + players.size()) + "). Answer: '" + resp.answer + "'");
        }

        target.addTarget(chosenId, amount, source, game);
        addSelfLog(gameTurnStep(game) + ": Assigned " + amount + " to target #" + targetIdx
                + " (" + srcName + ")");
        return true;
    }

    // --- Multi-amount with individual constraints (combat damage division, mana distribution, etc.) ---

    @Override
    public List<Integer> getMultiAmountWithIndividualConstraints(Outcome outcome,
            List<MultiAmountMessage> messages, int totalMin, int totalMax,
            MultiAmountType type, Game game) {
        if (messages == null || messages.isEmpty())
            return super.getMultiAmountWithIndividualConstraints(outcome, messages, totalMin, totalMax, type, game);

        // Single option — no choice needed
        if (messages.size() == 1) {
            return Collections.singletonList(totalMax);
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("=== DISTRIBUTE AMOUNT ===\n");
        prompt.append("Task: ").append(type.getHeader()).append("\n");
        prompt.append("Total to distribute: ").append(totalMin).append(" to ").append(totalMax).append("\n\n");

        String situation = LLMGameSerializer.buildSituationSummary(game, getId());
        prompt.append("Board: ").append(situation).append("\n\n");

        prompt.append("Options (distribute the total among these):\n");
        for (int i = 0; i < messages.size(); i++) {
            MultiAmountMessage m = messages.get(i);
            prompt.append(i + 1).append(". ").append(m.message);
            prompt.append(" [min ").append(m.min).append(", max ").append(m.max).append("]");
            if (m.defaultValue > 0) {
                prompt.append(" — default: ").append(m.defaultValue);
            }
            prompt.append("\n");
        }

        prompt.append("\nReply with ").append(messages.size())
              .append(" comma-separated numbers, one per option above (e.g., '3,0,2' for 3 options). No reasoning text — the answer line must contain exactly ")
              .append(messages.size()).append(" numbers.\n");
        prompt.append("The sum must be between ").append(totalMin).append(" and ").append(totalMax).append(".\n");
        prompt.append("Each value must respect its min/max constraint. Missing values are an error — provide exactly one number per option.\n");

        if (config.isVerboseLogging())
            logger.info("=== LLM MULTI-AMOUNT ===\n" + prompt + "\n=== END ===");

        // Parse comma-separated numbers, clamp per-option, and retry if the
        // clamped sum exceeds totalMax. Clamping happens BEFORE the sum check,
        // so individually-valid values can collectively exceed totalMax. Rather
        // than silently submitting an over-limit sum, re-prompt the LLM with
        // a correction message (Fail Fast: no silent fallback).
        List<Integer> result = new ArrayList<>();
        LLMResponse resp = null;
        int lastSum = 0;
        int maxRetries = 3;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            String currentPrompt;
            if (attempt == 0) {
                currentPrompt = prompt.toString();
            } else {
                currentPrompt = prompt.toString() + "\n\n"
                        + "ERROR: Your previous answer summed to " + lastSum
                        + ", but the total must be between " + totalMin + " and " + totalMax
                        + ". Each value was clamped to its [min,max] range.\n"
                        + "Please redistribute so the sum does not exceed " + totalMax + ".";
            }

            if (config.isVerboseLogging())
                logger.info("=== LLM MULTI-AMOUNT (attempt " + (attempt + 1) + ") ===\n" + currentPrompt + "\n=== END ===");

            resp = chatAndRecord(game, currentPrompt, "Distribute: " + type.getTitle(), "distribute");

            if (config.isVerboseLogging() && resp.thinking != null && !resp.thinking.isEmpty())
                logger.info("=== LLM THINKING ===\n" + resp.thinking + "\n=== END ===");

            // Parse comma-separated numbers from the FIRST LINE only —
            // reasoning digits in later lines must not leak into the parse
            // Fail Fast on a count mismatch instead of
            // back-filling defaults: part i maps to option i positionally,
            // so guessing would silently misassign the distribution.
            String[] parts = firstLineOf(resp.answer).replaceAll("[^0-9,\\-]", "").split(",");
            if (parts.length != messages.size()) {
                throw new RuntimeException("Multi-amount parse: expected " + messages.size()
                        + " comma-separated values on the answer line but got " + parts.length
                        + " ('" + firstLineOf(resp.answer) + "')");
            }
            result = new ArrayList<>();
            for (int i = 0; i < messages.size(); i++) {
                int val;
                try {
                    val = Integer.parseInt(parts[i].trim());
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Failed to parse LLM multi-amount response: '"
                            + resp.answer + "' (part " + i + ": '" + parts[i].trim() + "')", e);
                }
                // Clamp to per-option constraints
                val = Math.max(messages.get(i).min, Math.min(messages.get(i).max, val));
                result.add(val);
            }

            // Ensure total is within bounds
            int sum = result.stream().mapToInt(Integer::intValue).sum();
            if (sum >= totalMin && sum <= totalMax) {
                // Sum is valid — accept the result
                addSelfLog(gameTurnStep(game) + ": Distributed " + sum + " across " + messages.size()
                        + " options (" + type.getTitle() + ")");
                return result;
            }

            // Sum is out of bounds — retry if attempts remain, else throw
            lastSum = sum;
            if (config.isVerboseLogging())
                logger.info("Multi-amount sum " + sum + " outside [" + totalMin + "," + totalMax
                        + "] on attempt " + (attempt + 1) + ", will retry");
            addSelfLog(gameTurnStep(game) + ": Multi-amount sum " + sum + " outside ["
                    + totalMin + "," + totalMax + "] on attempt " + (attempt + 1) + ", retrying");
        }

        // All retries exhausted — Fail Fast
        throw new RuntimeException("Multi-amount sum " + lastSum + " outside [" + totalMin + "," + totalMax
                + "] after " + maxRetries + " attempts. LLM answer: " + resp.answer);
    }

    // --- Choose which spell ability to cast (Adventure, MDFC, split cards) ---

    @Override
    public SpellAbility chooseAbilityForCast(Card card, Game game, boolean noMana) {
        // If the card is face-down, temporarily turn it face-up so we can
        // find its spell abilities. This happens when a card is exiled
        // face-down (e.g. Beseech the Mirror bargain) and then cast for free.
        // We MUST restore the face-down state after the lookup — leaking a
        // face-up card into the game is a state corruption bug.
        boolean wasFaceDown = card.isFaceDown(game);
        if (wasFaceDown) {
            card.setFaceDown(false, game);
        }
        List<SpellAbility> abilities;
        try {
            Map<UUID, SpellAbility> usable = PlayerImpl.getCastableSpellAbilities(game, this.getId(), card,
                    game.getState().getZone(card.getId()), noMana);
            abilities = usable.values().stream()
                    .filter(a -> a.getTargets().canChoose(getId(), a, game))
                    .collect(Collectors.toList());
        } finally {
            if (wasFaceDown) {
                card.setFaceDown(true, game);
            }
        }

        if (abilities.isEmpty()) return null;
        if (abilities.size() == 1) return abilities.get(0);

        StringBuilder prompt = new StringBuilder();
        prompt.append("=== CHOOSE SPELL SIDE ===\n");
        prompt.append("Card: ").append(card.getName()).append("\n");
        prompt.append("This card can be cast in multiple ways.\n\n");

        String situation = LLMGameSerializer.buildSituationSummary(game, getId());
        prompt.append("Board: ").append(situation).append("\n\n");

        for (int i = 0; i < abilities.size(); i++) {
            SpellAbility sa = abilities.get(i);
            prompt.append(i + 1).append(". ");
            prompt.append(sa.getName());
            if (!sa.getManaCosts().getText().isEmpty())
                prompt.append(" — ").append(sa.getManaCosts().getText());
            if (sa.getRule() != null && !sa.getRule().isEmpty())
                prompt.append(" — ").append(sa.getRule());
            prompt.append("\n");
        }
        prompt.append("\nReply with ONLY the number of your choice.");

        if (config.isVerboseLogging())
            logger.info("=== LLM CAST SIDE ===\n" + prompt + "\n=== END ===");

        LLMResponse resp = chatAndRecord(game, prompt.toString(), "Cast side: " + card.getName(), "castside", SIMPLE_NUMBER_VALIDATOR);

        if (config.isVerboseLogging() && resp.thinking != null && !resp.thinking.isEmpty())
            logger.info("=== LLM THINKING ===\n" + resp.thinking + "\n=== END ===");

        int idx = parseActionNumber(resp.answer);
        if (idx >= 1 && idx <= abilities.size()) {
            addSelfLog(gameTurnStep(game) + ": Cast " + card.getName() + " as side " + idx);
            return abilities.get(idx - 1);
        }
        // Fail Fast: if the LLM gave an invalid side index, crash so the bug
        // is visible. Silent fallbacks mask LLM errors.
        throw new RuntimeException("LLM cast side: invalid index " + idx + " (valid: 1-"
                + abilities.size() + "). Answer: '" + resp.answer + "' for: " + card.getName());
    }

    // ==================================================================
    //  CARD SELECTIONS — LLM-driven (tutors, Brainstorm, discards, etc.)
    // ==================================================================

    // choose(Outcome, Target, Ability, Game) — catch-all target routing.
    // Cards like Chrome Mox call this instead of chooseTarget(); route to LLM.
    @Override
    public boolean choose(Outcome outcome, Target target, Ability source, Game game) {
        return chooseTarget(outcome, target, source, game);
    }

    @Override
    public boolean choose(Outcome outcome, Target target, Ability source, Game game, Map<String, Serializable> options) {
        return chooseTarget(outcome, target, source, game);
    }

    // Called by Target (hand, graveyard, library search, etc.)
    @Override
    public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game) {
        // Derive candidates for the CHOOSING player, not the ability controller:
        // getAffectedAbilityControllerId returns the explicit ability controller when
        // set, else the choosing player (matches HumanPlayer/ComputerPlayer). Using
        // source.getControllerId() here (which is null-safe against the mulligan phase
        // via the ternary) offered the ATTACKER's permanents to the defending chooser
        // on annihilator/sacrifice triggers where the ability controller is unset.
        UUID controllerId = target.getAffectedAbilityControllerId(getId());
        Set<UUID> possible = target.possibleTargets(controllerId, source, game);

        // Restore-state staleness guard: after a failed mana
        // plan triggers restoreState(bookmarkAtPriorityStart), the Target object
        // can carry possibleTargets computed against the pre-rollback state —
        // e.g. a permanent destroyed between bookmark and error (LED at T12)
        // is offered as legal because the restored state
        // re-evaluates the trigger against the rolled-back battlefield. The Card
        // still resolves via getCard() — it's present in the library/hand zone
        // index — only its ZONE is wrong. Filter by the target's expected zone:
        // an id "on the battlefield" that resolves to a card the live state
        // thinks is in the GRAVEYARD is stale and must not be offered.
        // Fail Fast when everything vanished (not a silent fall-through).
        possible.removeIf(id -> isStaleForZone(id, target.getZone(), game));

        if (possible.isEmpty())
            return super.chooseTarget(outcome, target, source, game);

        List<Card> cards = new ArrayList<>();
        List<Player> players = new ArrayList<>();
        for (UUID id : possible) {
            Card c = game.getCard(id);
            if (c != null) {
                cards.add(c);
            } else {
                Player p = game.getPlayer(id);
                if (p != null) {
                    players.add(p);
                } else {
                    // Token permanents (Construct, Marit Lage, etc.) have no backing
                    // Card object but are Permanent instances on the battlefield.
                    // Permanent extends Card, so they work with formatCardForChoice.
                    Permanent perm = game.getPermanent(id);
                    if (perm != null) {
                        cards.add(perm);
                    }
                }
            }
        }
        if (cards.isEmpty() && players.isEmpty())
            return super.chooseTarget(outcome, target, source, game);

        return presentTargetChoice(target.getMessage(game),
                target.getMinNumberOfTargets(), target.getMaxNumberOfTargets(),
                cards, players, target, source, game, false);
    }

    /**
     * Restore-state staleness check: an id is stale when the
     * live game state's zone for that id doesn't match the zone the Target is
     * asking for. Player targets (target.getZone() == null, usually for
     * damage-to-any-target) always pass — players persist across restores.
     * Battlefield-target checks allow EITHER a resolvable Permanent or a Card
     * whose live zone is BATTLEFIELD (transformed copies / tokens resolve as
     * Permanent without a backing Card).
     */
    private boolean isStaleForZone(UUID id, Zone targetZone, Game game) {
        if (targetZone == null) return false;  // player targets
        Card c = game.getCard(id);
        if (c != null && game.getState().getZone(id) != targetZone) {
            return true;  // the card exists but is in a different zone than the target expects
        }
        if (targetZone == Zone.BATTLEFIELD && game.getPermanent(id) == null) {
            return true;  // should be a permanent; the live state has nothing on the battlefield here
        }
        return false;
    }

    // Called by TargetCard / TargetCardInLibrary (chooseTarget variant)
    @Override
    public boolean chooseTarget(Outcome outcome, Cards fromCards, TargetCard target,
                                Ability source, Game game) {
        if (fromCards == null || fromCards.isEmpty())
            return super.chooseTarget(outcome, fromCards, target, source, game);

        List<Card> cards = fromCards.getCards(game).stream().collect(Collectors.toList());
        int max = target.getMaxNumberOfTargets();
        String reason = target.getMessage(game);

        // Multi-step single-card selection (Brainstorm put-back from hand):
        // The engine calls chooseTarget() once per card, but we present all remaining cards.
        // Only add this clarification for HAND zone AND single-card-per-step selections.
        // Multi-card-in-one-call selections (e.g. Doomsday min=5 max=5) must NOT get this
        // message — the engine expects all selections in a single comma-separated response.
        if (cards.size() > max && max == 1 && target.getZone() == Zone.HAND) {
            reason = reason + " [STEP-BY-STEP: Pick exactly ONE card now. "
                    + "You will be asked again for the next card. "
                    + "The last card you pick will end up on top.]";
        }

        return presentCardChoice(reason,
                target.getMinNumberOfTargets(), max,
                cards, target, source, game, true);
    }

    // Called by putCardsOnTopOfLibrary (Ponder, scry, etc.) — choose() without "target"
    @Override
    public boolean choose(Outcome outcome, Cards fromCards, TargetCard target,
                          Ability source, Game game) {
        if (fromCards == null || fromCards.isEmpty())
            return super.choose(outcome, fromCards, target, source, game);

        List<Card> cards = fromCards.getCards(game).stream().collect(Collectors.toList());
        String reason = target.getMessage(game);
        int max = target.getMaxNumberOfTargets();

        // Multi-step single-card selection (Ponder, scry, etc.):
        // The engine calls choose() once per card, but we present all remaining cards.
        // Only add this clarification for single-card-per-step selections (max == 1).
        // Multi-card-in-one-call selections (e.g. Doomsday min=5 max=5) must NOT get this
        // message — the engine expects all selections in a single comma-separated response.
        if (cards.size() > max && max == 1) {
            reason = reason + " [STEP-BY-STEP: Pick exactly ONE card now. "
                    + "You will be asked again for the next card. "
                    + "The last card you pick will end up on top.]";
        }

        return presentCardChoice(reason,
                target.getMinNumberOfTargets(), max,
                cards, target, source, game, true);
    }

    private boolean presentCardChoice(String reason, int min, int max,
                                       List<Card> cards, Target target,
                                       Ability source, Game game,
                                       boolean useCardAdd) {
        return presentTargetChoice(reason, min, max, cards, Collections.emptyList(),
                                    target, source, game, useCardAdd);
    }

    /**
     * Present a list of card + player targets to the LLM.
     * Cards come first in the numbering, then players (for any-target abilities).
     */
    private boolean presentTargetChoice(String reason, int min, int max,
                                         List<Card> cards, List<Player> players,
                                         Target target, Ability source, Game game,
                                         boolean useCardAdd) {

        // For large library/graveyard searches (Doomsday, Tinker, Survival, etc.),
        // switch to free-text card names — a 50+ item numbered list is unreadable.
        boolean isLibraryOrGraveyard = target != null
                && (target.getZone() == Zone.LIBRARY || target.getZone() == Zone.GRAVEYARD);
        if (cards.size() > 50 && isLibraryOrGraveyard) {
            return presentLargeCardTargetChoice(reason, min, max, cards, target, source, game, useCardAdd);
        }
        StringBuilder prompt = new StringBuilder();
        // Detect library searches (fetchlands, tutors) — these should not be
        // declined because the cost was already paid (life + sacrifice).
        boolean isLibrarySearch = target != null && target.getZone() == Zone.LIBRARY;
        // Detect the legend rule prompt — the engine sets targetName to
        // "CardName to keep (Legendary Rule)?" and passes source=null. Without
        // this detection, the LLM sees "Select Atraxa to keep (Legendary Rule)?"
        // but doesn't know it's a state-based action, not a spell/ability.
        boolean isLegendRule = reason != null && reason.contains("(Legendary Rule)");
        // Decision-kind distinction: surveil keep-or-mill and the
        // London-mulligan bottom-card pick route through this same
        // card-selection path but are NOT targeting decisions. The old
        // hardcoded wording mislabelled them ("Declined targeting for …" on a
        // surveil; "Chose <name>" on a mulligan bottom). Name the real kind.
        boolean isMulliganBottom = reason != null
                && reason.contains("to put on the bottom of your library");
        boolean isSurveil = reason != null && reason.contains("(Surveil)");
        prompt.append(isLegendRule ? "=== LEGENDARY RULE ===\n"
                : isLibrarySearch ? "=== LIBRARY SEARCH ===\n" : "=== TARGET SELECTION ===\n");
        if (isLegendRule) {
            prompt.append("Two or more legendary permanents with the same name are on your battlefield.\n");
            prompt.append("You MUST choose ONE to keep — all others go to the graveyard.\n\n");
        }
        // Identify which card is asking
        String sourceName = "card";
        if (source != null) {
            MageObject srcObj = game.getObject(source.getSourceId());
            if (srcObj != null) {
                sourceName = srcObj.getName();
                prompt.append("Source: ").append(sourceName).append("\n");
                String rule = source.getRule();
                if (rule != null && !rule.isEmpty()) {
                    prompt.append("Rules: ").append(rule).append("\n");
                }
            }
        }
        if (reason != null && !reason.isEmpty())
            prompt.append("Reason: ").append(reason).append("\n");

        if (isLibrarySearch) {
            prompt.append("WARNING: You MUST choose a card — you already paid the cost to search.\n");
        }

        int totalTargets = cards.size() + players.size();
        int alreadyChosen = target != null ? target.getTargets().size() : 0;
        // Integer.MAX_VALUE is the engine's "unbounded" cap, not a playable
        // number — render it as "any number" (matching announceX's handling
        // of MAX_VALUE) instead of printing 2147483647.
        boolean maxUnbounded = (max == Integer.MAX_VALUE);
        prompt.append("Choose ").append(min).append(" to ");
        if (maxUnbounded) {
            prompt.append("any number of");
        } else {
            prompt.append(max);
        }
        prompt.append(" target(s) from ").append(totalTargets).append(" option(s).");
        // Follow-up prompt with the minimum already met: further targets are
        // optional, but the prompt reads as if another is required. Make the
        // decline path explicit.
        // alreadyChosen > 0 keeps the FIRST prompt of a min=0 selection clean
        // ("You already chose 0" is nonsense, and the "0. Cancel" line already
        // documents declining there).
        if (maxUnbounded && alreadyChosen > 0 && alreadyChosen >= min) {
            prompt.append(" You already chose ").append(alreadyChosen)
                  .append(" — further targets are optional and declining is legal (reply 0 to stop).");
        }
        prompt.append("\n");

        // Compact board context so the LLM knows the game state
        String situation = LLMGameSerializer.buildSituationSummary(game, getId());
        prompt.append("Board: ").append(situation).append("\n");

        prompt.append("Available targets:\n");

        // Zone-aware formatting for cards
        boolean onBattlefield = target != null && target.getZone() == Zone.BATTLEFIELD;
        boolean inHand = target != null && target.getZone() == Zone.HAND;
        boolean canValidate = (target != null && target.getZone() == Zone.LIBRARY && target instanceof TargetCard);
        Set<Integer> validIndices = canValidate ? new HashSet<>() : null;

        for (int i = 0; i < cards.size(); i++) {
            Card c = cards.get(i);
            String line;
            if (onBattlefield) {
                line = (i + 1) + ". " + LLMGameSerializer.formatCardForChoiceOnce(game, getId(), c);
            } else if (inHand) {
                line = (i + 1) + ". " + LLMGameSerializer.formatCardForChoiceOnce(game, getId(), c);
            } else {
                line = (i + 1) + ". " + LLMGameSerializer.formatCardNameOnly(c);
            }

            if (canValidate) {
                boolean match = true;
                try {
                    if (source != null) {
                        match = ((TargetCard) target).canTarget(getId(), c.getId(), source, game);
                    }
                } catch (Throwable t) {
                    throw new RuntimeException("canTarget check failed for card " + c.getName(), t);
                }
                if (!match) {
                    line += " [INVALID — does not match filter]";
                } else {
                    validIndices.add(i);
                }
            }
            prompt.append(line).append("\n");
        }

        // Players as targets (e.g. any-target spells like Orcish Bowmasters)
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            int num = cards.size() + i + 1;
            prompt.append(num).append(". PLAYER: ").append(p.getName())
                  .append(" (life: ").append(p.getLife()).append(")");
            if (p.getId().equals(getId())) prompt.append(" [YOU]");
            prompt.append("\n");
        }

        // Validation summary
        if (validIndices != null && validIndices.size() < cards.size()) {
            int v = validIndices.size();
            prompt.append("\n").append(v).append(" valid card choice(s) marked above — pick ONLY from these.\n");
        }

        // Zone annotation
        if (!onBattlefield && target != null && cards.size() > 0) {
            Zone zone = target.getZone();
            if (zone != null) {
                prompt.append("(Source zone: ").append(zone).append(")\n");
            }
        }

        // Cancel option
        boolean optional = (min == 0);
        if (optional) {
            prompt.append("0. Cancel — decline to choose any targets (skip this ability)\n");
        }

        // Exact-count constraint: when min == max, the only valid answer is that many
        // numbers. Saying "comma-separated (e.g., '1,4')" here invited models to
        // over-select on Brainstorm-style "put 2 back" prompts, which XMage presents
        // as sequential maxTargets=1 prompts — the extra selection then got dropped
        // with a WARN and the model's intended second card never chosen (observed
        // 9× in aggro-vs-control games).
        if (min == max && min > 0) {
            prompt.append("\nReply with ONLY ")
                  .append(min == 1 ? "the number" : "exactly " + min + " numbers");
            if (min > 1) {
                prompt.append(", comma-separated (e.g., '1,4')");
            } else {
                prompt.append(" (e.g., '3') — do NOT list multiple numbers");
            }
        } else {
            prompt.append("\nReply with ONLY the number(s), comma-separated (e.g., '3' or '1,4')");
        }
        if (optional) {
            prompt.append(" or '0' to cancel.");
        } else {
            prompt.append(".");
        }

        if (config.isVerboseLogging())
            logger.info("=== LLM TARGET SELECTION ===\n" + prompt + "\n=== END ===");

        LLMResponse resp = chatAndRecord(game, prompt.toString(),
                "Target: " + sourceName,
                "target", COMMA_NUMBERS_VALIDATOR);
        if (config.isVerboseLogging()) {
            logger.info("LLM choice: '" + resp.answer + "'");
            if (resp.thinking != null && !resp.thinking.isEmpty())
                logger.info("=== LLM THINKING ===\n" + resp.thinking + "\n=== END ===");
        }

        // Check for cancel (0)
        // Parse only the FIRST LINE of the answer — reasoning blocks contain
        // numbers that would be misparsed as additional target selections.
        String firstLine = resp.answer.split("\\n")[0].trim();
        String cleaned = firstLine.replaceAll("[^0-9,\\s]", "").trim();
        if (optional && (cleaned.equals("0") || cleaned.startsWith("0,") || cleaned.equals("0 "))) {
            if (config.isVerboseLogging())
                logger.info("LLM chose cancel/decline for optional target selection");
            // Label the actual choice kind: surveil keep-on-top and
            // mulligan-bottom declines are not "targeting" decisions.
            String declineLabel;
            if (isMulliganBottom) {
                declineLabel = "Declined mulligan bottom-card";
            } else if (isSurveil) {
                declineLabel = "Declined surveil for " + sourceName;
            } else {
                declineLabel = "Declined targeting for " + sourceName;
            }
            addSelfLog(gameTurnStep(game) + ": " + declineLabel);
            return false;
        }

        // Parse selections: numbers 1..cards.size() are cards, cards.size()+1..total are players
        boolean foundAny = false;
        int addedCount = 0;
        int maxTargets = max;
        for (String part : cleaned.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            try {
                int idx = Integer.parseInt(trimmed) - 1;
                // Guard: when maxTargets == 1, only the first valid selection counts.
                // Extra entries in a comma answer (e.g. "2,4" on a single-select
                // Brainstorm) would otherwise fire duplicate addSelfLog entries.
                if (maxTargets == 1 && addedCount >= 1) {
                    logger.warn("presentTargetChoice: maxTargets=1 but LLM answered '"
                            + resp.answer + "' — ignoring extra selection '" + trimmed + "'");
                    break;
                }
                if (idx >= 0 && idx < cards.size()) {
                    if (useCardAdd) {
                        ((TargetCard) target).add(cards.get(idx).getId(), game);
                    } else {
                        target.addTarget(cards.get(idx).getId(), source, game);
                    }
                    foundAny = true;
                    addedCount++;
                    if (isLegendRule) {
                        addSelfLog(gameTurnStep(game) + ": Legend rule — kept " + cards.get(idx).getName()
                                + " (" + LLMGameSerializer.formatCardForChoiceOnce(game, getId(), cards.get(idx)) + ")");
                    } else if (isMulliganBottom) {
                        // F8b: this is the London-mulligan "put one on the bottom"
                        // pick, not a "chose this card" targeting choice.
                        addSelfLog(gameTurnStep(game) + ": Bottomed " + cards.get(idx).getName()
                                + " (mulligan)");
                    } else if (isSurveil) {
                        addSelfLog(gameTurnStep(game) + ": Surveil — put " + cards.get(idx).getName()
                                + " into graveyard");
                    } else {
                        addSelfLog(gameTurnStep(game) + ": Chose " + cards.get(idx).getName());
                    }
                } else if (idx >= cards.size() && idx - cards.size() < players.size()) {
                    Player chosen = players.get(idx - cards.size());
                    target.addTarget(chosen.getId(), source, game);
                    foundAny = true;
                    addedCount++;
                    addSelfLog(gameTurnStep(game) + ": Chose player " + chosen.getName());
                }
            } catch (NumberFormatException e) {
                throw new RuntimeException("Failed to parse target selection number: '" + trimmed
                        + "' from answer: '" + resp.answer + "'", e);
            }
        }

        // If required selection got no parsable number, or got fewer than the
        // minimum required targets, retry up to 2 times. A partially-successful
        // first parse (e.g. "1,99" → picks 1, ignores 99) sets foundAny=true but
        // may still be below min — retry regardless of foundAny in that case.
        for (int retry = 0; !optional && (target.getTargets().size() < min) && retry < 2; retry++) {
            String retryPrompt = "ERROR: Your previous answer ('" + resp.answer
                    + "') contained no valid target numbers. "
                    + "You MUST reply with ONLY the number(s) of your choice(s), comma-separated "
                    + "(e.g., '17' or '3,5'). "
                    + "Pick a number from the valid choices listed above.";
            if (config.isVerboseLogging())
                logger.info("LLM target parse failure (retry " + (retry+1) + "), re-prompting");
            addSelfLog(gameTurnStep(game) + ": " + retryPrompt);

            // Re-prompt with the original prompt text plus the error
            String retryFull = prompt.toString() + "\n\n" + retryPrompt;
            resp = chatAndRecord(game, retryFull, "Target: " + sourceName, "target", COMMA_NUMBERS_VALIDATOR);

            if (config.isVerboseLogging()) {
                logger.info("LLM retry choice: '" + resp.answer + "'");
            }

            for (String part : firstLineOf(resp.answer).replaceAll("[^0-9,\\s]", "").split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    int idx = Integer.parseInt(trimmed) - 1;
                    if (idx >= 0 && idx < cards.size()) {
                        if (useCardAdd) {
                            ((TargetCard) target).add(cards.get(idx).getId(), game);
                        } else {
                            target.addTarget(cards.get(idx).getId(), source, game);
                        }
                        foundAny = true;
                        if (isLegendRule) {
                            addSelfLog(gameTurnStep(game) + ": Legend rule — kept " + cards.get(idx).getName()
                                    + " (" + LLMGameSerializer.formatCardForChoiceOnce(game, getId(), cards.get(idx)) + ")");
                        } else if (isMulliganBottom) {
                            addSelfLog(gameTurnStep(game) + ": Bottomed " + cards.get(idx).getName()
                                    + " (mulligan)");
                        } else if (isSurveil) {
                            addSelfLog(gameTurnStep(game) + ": Surveil — put " + cards.get(idx).getName()
                                    + " into graveyard");
                        } else {
                            addSelfLog(gameTurnStep(game) + ": Chose " + cards.get(idx).getName());
                        }
                    } else if (idx >= cards.size() && idx - cards.size() < players.size()) {
                        Player chosen = players.get(idx - cards.size());
                        target.addTarget(chosen.getId(), source, game);
                        foundAny = true;
                        addSelfLog(gameTurnStep(game) + ": Chose player " + chosen.getName());
                    }
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Failed to parse target selection number: '" + trimmed
                            + "' from retry answer: '" + resp.answer + "'", e);
                }
            }
        }

        return target.isChosen(game) && !target.getTargets().isEmpty();
    }

    /**
     * Target selection for large library/graveyard searches (Doomsday, Tinker, etc.).
     * Instead of a 55+ item numbered list, presents card names as free-text choices
     * with fuzzy matching and retry — same pattern as chooseLargeSet().
     */
    private boolean presentLargeCardTargetChoice(String reason, int min, int max,
                                                  List<Card> cards, Target target,
                                                  Ability source, Game game,
                                                  boolean useCardAdd) {
        // Build a compact map: lowercased name -> list of card UUIDs matching it
        Map<String, List<UUID>> nameMap = new LinkedHashMap<>();
        List<String> uniqueNames = new ArrayList<>();
        for (Card c : cards) {
            String key = c.getName().toLowerCase();
            if (!nameMap.containsKey(key)) {
                nameMap.put(key, new ArrayList<>());
                uniqueNames.add(c.getName());
            }
            nameMap.get(key).add(c.getId());
        }

        String sourceName = "card";
        String sourceRule = "";
        if (source != null) {
            MageObject srcObj = game.getObject(source.getSourceId());
            if (srcObj != null) {
                sourceName = srcObj.getName();
                String rule = source.getRule();
                if (rule != null && !rule.isEmpty()) sourceRule = rule;
            }
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("=== CARD SEARCH ===\n");
        prompt.append("Source: ").append(sourceName);
        if (!sourceRule.isEmpty()) prompt.append(" — ").append(sourceRule);
        prompt.append("\n");
        if (reason != null && !reason.isEmpty())
            prompt.append("Reason: ").append(reason).append("\n");
        prompt.append("You must choose ");
        if (min == max) {
            prompt.append("exactly ").append(min);
        } else {
            prompt.append(min).append(" to ").append(max);
        }
        prompt.append(" card(s) from your ")
              .append(target.getZone().toString().toLowerCase()).append(".\n");

        // Compact game state
        String situation = LLMGameSerializer.buildSituationSummary(game, getId());
        prompt.append("Board: ").append(situation).append("\n\n");

        // Show available cards — compact list for large sets
        prompt.append("=== AVAILABLE CARDS (").append(cards.size()).append(" total, ")
              .append(uniqueNames.size()).append(" unique names) ===\n");
        if (uniqueNames.size() <= 60) {
            for (String name : uniqueNames) {
                prompt.append(name).append("\n");
            }
        } else {
            // Very large set — just tell them they can name any card from their deck
            prompt.append("(Your entire deck is searchable — name any card from it)\n");
        }
        boolean listShown = uniqueNames.size() <= 60;

        prompt.append("\nReply with EXACT card names, one per line or comma-separated.\n");
        prompt.append("Example").append(min == 1 ? "" : "s").append(": '")
              .append(uniqueNames.stream().limit(min).collect(Collectors.joining(", "))).append("'\n");
        prompt.append("Do NOT include numbers, quotes, or extra text.");

        if (config.isVerboseLogging())
            logger.info("=== LLM LARGE TARGET SELECTION ===\n" + prompt + "\n=== END ===");

        LLMResponse resp = chatAndRecord(game, prompt.toString(),
                "Search: " + sourceName,
                "cardsearch");

        if (config.isVerboseLogging()) {
            logger.info("LLM large-target answer: '" + resp.answer + "'");
        }

        // Parse: split on commas or newlines, strip junk.
        // Models mimic the stored [model_reasoning] fence in their own
        // answers; its tokens must never reach the card-name splitter and be
        // reported as "unrecognized card names". Strip the whole fence first.
        String parseSource = stripReasoningFence(resp.answer);
        String[] parts = parseSource.split("[,;\\n]+");
        List<String> chosenNames = new ArrayList<>();
        for (String part : parts) {
            String cleaned = part.trim()
                    .replaceAll("^[\"'\\s]+|[\"'\\s]+$", "")
                    .replaceAll("^(I choose |Choose |Select |Pick |Name: )", "");
            if (!cleaned.isEmpty() && !cleaned.equalsIgnoreCase("none")) {
                chosenNames.add(cleaned);
            }
        }

        // Retry loop for fuzzy matching
        for (int attempt = 0; attempt < 3 && !chosenNames.isEmpty(); attempt++) {
            boolean anyFailed = false;
            for (String name : new ArrayList<>(chosenNames)) {
                Card matched = findCard(name, nameMap, cards);
                if (matched != null) {
                    if (useCardAdd) {
                        ((TargetCard) target).add(matched.getId(), game);
                    } else {
                        target.addTarget(matched.getId(), source, game);
                    }
                    addSelfLog(gameTurnStep(game) + ": Chose " + matched.getName());
                    chosenNames.remove(name);
                } else {
                    anyFailed = true;
                }
            }

            if (chosenNames.isEmpty()) return true;
            if (attempt >= 2) break;

            // Build retry prompt
            StringBuilder retryPrompt = new StringBuilder();
            retryPrompt.append("ERROR: The following card names were not recognized:\n");
            for (String name : chosenNames) {
                retryPrompt.append("  - \"").append(name).append("\"\n");
            }
            retryPrompt.append("\nReply with ONLY the corrected card names, one per line.\n");
            if (listShown) {
                retryPrompt.append("Choose from the available cards listed above.");
            } else {
                retryPrompt.append("Your entire deck is searchable — name any card from it.");
            }

            if (config.isVerboseLogging())
                logger.info("LLM large-target retry " + (attempt + 1));

            addSelfLog(gameTurnStep(game) + ": " + retryPrompt);
            resp = chatAndRecord(game, retryPrompt.toString(), "Search retry: " + sourceName, "cardsearch");

            // Re-parse (strip the mimicry reasoning fence first too).
            chosenNames.clear();
            for (String part : stripReasoningFence(resp.answer).split("[,;\\n]+")) {
                String cleaned = part.trim()
                        .replaceAll("^[\"'\\s]+|[\"'\\s]+$", "")
                        .replaceAll("^(I choose |Choose |Select |Pick |Name: )", "");
                if (!cleaned.isEmpty() && !cleaned.equalsIgnoreCase("none")) {
                    chosenNames.add(cleaned);
                }
            }
        }

        return target.isChosen(game) && !target.getTargets().isEmpty();
    }

    /**
     * Find a card by fuzzy name match against the name-to-UUID map.
     */
    private Card findCard(String name, Map<String, List<UUID>> nameMap, List<Card> allCards) {
        // Exact match
        String lower = name.toLowerCase().trim();
        if (nameMap.containsKey(lower)) {
            UUID id = nameMap.get(lower).get(0);
            for (Card c : allCards) {
                if (c.getId().equals(id)) return c;
            }
        }

        // Prefix/substring match
        List<String> candidates = nameMap.keySet().stream()
                .filter(k -> k.startsWith(lower))
                .limit(3)
                .collect(Collectors.toList());
        if (candidates.size() == 1) {
            UUID id = nameMap.get(candidates.get(0)).get(0);
            for (Card c : allCards) {
                if (c.getId().equals(id)) return c;
            }
        }

        return null;
    }

    // ==================================================================
    //  COMBAT — LLM-driven
    // ==================================================================

    // Pattern for parsing "1->player" or "3->pw:Jace, the Mind Sculptor"
    private static final Pattern ATTACK_PATTERN =
            Pattern.compile("(\\d+)\\s*->\\s*(player|pw:(.+?))(?:,|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOCK_PATTERN =
            Pattern.compile("(\\d+)\\s*->\\s*(\\d+)(?:,|$)");

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        // Only the active (attacking) player chooses attackers
        if (!getId().equals(attackingPlayerId)) return;

        long startMs = config.isVerboseLogging() ? System.currentTimeMillis() : 0;

        // Get available attackers
        List<Permanent> availableAttackers = getAvailableAttackers(game);
        if (availableAttackers.isEmpty()) {
            if (config.isVerboseLogging())
                logger.info("LLM combat: no attackers available");
            return;
        }

        // Get our opponents still in the game
        List<UUID> opponents = new ArrayList<>();
        game.getOpponents(playerId).stream()
                .filter(id -> {
                    Player p = game.getPlayer(id);
                    return p != null && p.isInGame();
                })
                .forEach(opponents::add);

        if (opponents.isEmpty()) return;

        // Build prompt
        String prompt = LLMGameSerializer.buildAttackersPrompt(game, this.getId(), availableAttackers);

        if (config.isVerboseLogging())
            logger.info("=== LLM ATTACKERS PROMPT ===\n" + prompt + "\n=== END ===");

        LLMResponse resp = chatAndRecord(game, prompt, "Declare attackers", "attackers", ATTACKERS_VALIDATOR, true);

        // First line only — chatAndRecord accepts first-line-valid answers
        // (passesValidation), so trailing reasoning must not leak attack
        // entries (e.g. a reasoning line "attacker 2 -> player" would
        // otherwise declare an unintended attack).
        String answer = firstLineOf(resp.answer);
        // Prefix already stripped centrally in chatAndRecord, but call
        // stripDecisionPrefix again as a safety net.
        answer = stripDecisionPrefix(answer);
        if (config.isVerboseLogging()) {
            logger.info("LLM attack decision: '" + answer + "'");
            if (resp.thinking != null && !resp.thinking.isEmpty())
                logger.info("=== LLM ATTACK THINKING ===\n" + resp.thinking + "\n=== END ===");
        }

        // Parse "none" or empty
        if (answer.equalsIgnoreCase("none") || answer.isEmpty()) {
            addSelfLog(gameTurnStep(game) + ": No attacks");
            return;
        }

        // Parse "all" — attack with all available creatures at the primary opponent
        if (answer.equalsIgnoreCase("all")) {
            int declared = 0;
            UUID primaryOpponent = opponents.get(0);
            for (Permanent attacker : availableAttackers) {
                if (attacker.isTapped() || (attacker.hasSummoningSickness() && attacker.isCreature(game)))
                    continue;
                if (attacker.canAttack(primaryOpponent, game)) {
                    declareAttacker(attacker.getId(), primaryOpponent, game, false);
                    declared++;
                }
            }
            addSelfLog(gameTurnStep(game) + ": Attacked with " + declared + " creature" + (declared != 1 ? "s" : "") +
                (declared > 0 ? " (" + availableAttackers.stream().filter(a -> a.canAttack(primaryOpponent, game))
                        .map(Permanent::getName).limit(5).collect(Collectors.joining(" | ")) + ")" : ""));
            if (config.isVerboseLogging()) {
                long elapsed = System.currentTimeMillis() - startMs;
                logger.info("LLM combat (attackers): declared " + declared + " (all) in " + elapsed + "ms");
            }
            return;
        }

        // Parse attack declarations
        Map<Integer, String> attacks = new LinkedHashMap<>(); // attacker idx -> target

        // First, try to match the explicit pattern
        Matcher m = ATTACK_PATTERN.matcher(answer.toLowerCase());
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            String target = m.group(2).trim();
            if (target.startsWith("pw:")) {
                // Include the planeswalker name from group(3) in the target string
                String pwName = m.group(3) != null ? m.group(3).trim() : "";
                attacks.put(idx, "pw:" + pwName);
            } else {
                attacks.put(idx, "player");
            }
        }

        if (attacks.isEmpty()) {
            // Fallback: simple comma-separated numbers = attack player
            String[] parts = answer.split("[,;\\s]+");
            for (String part : parts) {
                try {
                    int idx = Integer.parseInt(part.trim());
                    attacks.put(idx, "player");
                } catch (NumberFormatException e) {
                    // Skip unparseable parts instead of crashing — the LLM may
                    // have included labels or reasoning text mixed with numbers.
                    // If no valid indices are found, we throw below.
                    logger.warn("Skipping unparseable attacker index '" + part.trim() + "': " + e.getMessage());
                }
            }
        }

        if (attacks.isEmpty()) {
            // Fail Fast: the raw answer was not "none"/"all"/empty, so the LLM
            // produced something we cannot parse. Crash rather than silently
            // declaring no attacks and hiding the bug.
            throw new RuntimeException("selectAttackers: could not parse LLM answer as attack declaration: '" + answer + "'");
        }

        // Now declare attacks
        UUID primaryOpponent = opponents.get(0); // default target
        int declared = 0;

        // Build a list of declared attacker names for the self-log
        List<String> declaredNames = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : attacks.entrySet()) {
            int idx = entry.getKey() - 1; // 0-based
            if (idx < 0 || idx >= availableAttackers.size()) continue;

            Permanent attacker = availableAttackers.get(idx);
            if (attacker.isTapped() || attacker.hasSummoningSickness() && attacker.isCreature(game))
                continue;

            String targetType = entry.getValue();
            UUID defenderId;

            if (targetType.startsWith("pw:")) {
                // Find the planeswalker/battle by name (fuzzy match)
                String targetName = targetType.substring(3).trim();
                defenderId = findAttackablePermanent(game, targetName, attackers(opponents, game));
                if (defenderId == null) {
                    throw new RuntimeException("LLM specified attacking '" + targetName + "' but no attackable permanent with that name was found");
                }
            } else {
                defenderId = primaryOpponent;
            }

            if (attacker.canAttack(defenderId, game)) {
                declareAttacker(attacker.getId(), defenderId, game, false);
                declared++;
                declaredNames.add(attacker.getName());
            }
        }

        addSelfLog(gameTurnStep(game) + ": Attacked with " + declared + " creature" + (declared != 1 ? "s" : "")
                + (declared > 0 ? " (" + String.join(" | ", declaredNames) + ")" : ""));

        if (config.isVerboseLogging()) {
            long elapsed = System.currentTimeMillis() - startMs;
            logger.info("LLM combat (attackers): declared " + declared + " in " + elapsed + "ms");
        }
    }

    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        // Only the defending player chooses blockers
        if (!getId().equals(defendingPlayerId)) return;

        long startMs = config.isVerboseLogging() ? System.currentTimeMillis() : 0;

        // Get attackers targeting us
        Set<UUID> attackerIds = game.getCombat().getAttackers();
        if (attackerIds.isEmpty()) return;

        List<Permanent> attackers = new ArrayList<>();
        for (UUID id : attackerIds) {
            Permanent p = game.getPermanent(id);
            if (p != null) attackers.add(p);
        }

        // Get available blockers
        List<Permanent> availableBlockers = getAvailableBlockers(game);
        if (availableBlockers.isEmpty()) {
            if (config.isVerboseLogging())
                logger.info("LLM combat: no blockers available");
            return;
        }

        // Build prompt
        String prompt = LLMGameSerializer.buildBlockersPrompt(game, this.getId());

        if (config.isVerboseLogging())
            logger.info("=== LLM BLOCKERS PROMPT ===\n" + prompt + "\n=== END ===");

        LLMResponse resp = chatAndRecord(game, prompt, "Declare blockers", "blockers", BLOCKERS_VALIDATOR, true);

        // First line only — chatAndRecord accepts first-line-valid answers
        // (passesValidation), so trailing reasoning must not leak block
        // entries (a reasoning line "blocker 1 -> attacker 2" would
        // otherwise declare an unintended block).
        String answer = firstLineOf(resp.answer);
        // Prefix already stripped centrally in chatAndRecord, but call
        // stripDecisionPrefix again as a safety net.
        answer = stripDecisionPrefix(answer);
        if (config.isVerboseLogging()) {
            logger.info("LLM block decision: '" + answer + "'");
            if (resp.thinking != null && !resp.thinking.isEmpty())
                logger.info("=== LLM BLOCK THINKING ===\n" + resp.thinking + "\n=== END ===");
        }

        // Parse "none" or empty
        if (answer.equalsIgnoreCase("none") || answer.isEmpty()) {
            addSelfLog(gameTurnStep(game) + ": No blocks");
            return;
        }

        // Parse block declarations: blocker_idx->attacker_idx
        Map<Integer, List<Integer>> blocks = new LinkedHashMap<>();

        // Try the explicit pattern first
        Matcher m = BLOCK_PATTERN.matcher(answer);
        while (m.find()) {
            int blockerIdx = Integer.parseInt(m.group(1));
            int attackerIdx = Integer.parseInt(m.group(2));
            blocks.computeIfAbsent(attackerIdx, k -> new ArrayList<>()).add(blockerIdx);
        }

        if (blocks.isEmpty()) {
            // Fail Fast: the raw answer was not "none"/empty, so the LLM
            // produced something we cannot parse. Crash rather than silently
            // declaring no blocks and hiding the bug.
            throw new RuntimeException("selectBlockers: could not parse LLM answer as block declaration: '" + answer + "'");
        }

        // Resolve blocks
        int declared = 0;
        for (Map.Entry<Integer, List<Integer>> entry : blocks.entrySet()) {
            int attackerIdx = entry.getKey() - 1;
            if (attackerIdx < 0 || attackerIdx >= attackers.size()) continue;
            Permanent attacker = attackers.get(attackerIdx);

            for (int blockerNum : entry.getValue()) {
                int blockerIdx = blockerNum - 1;
                if (blockerIdx < 0 || blockerIdx >= availableBlockers.size()) continue;
                Permanent blocker = availableBlockers.get(blockerIdx);

                if (!blocker.isTapped() && blocker.canBlock(attacker.getId(), game)) {
                    declareBlocker(defendingPlayerId, blocker.getId(), attacker.getId(), game, false);
                    declared++;
                }
            }
        }

        addSelfLog(gameTurnStep(game) + ": Blocked with " + declared + " creature" + (declared != 1 ? "s" : ""));

        if (config.isVerboseLogging()) {
            long elapsed = System.currentTimeMillis() - startMs;
            logger.info("LLM combat (blockers): declared " + declared + " in " + elapsed + "ms");
        }
    }

    /**
     * Find a planeswalker or battle permanent by fuzzy name match.
     */
    private UUID findAttackablePermanent(Game game, String name, List<UUID> opponents) {
        String lowerName = name.toLowerCase().trim();
        for (UUID oppId : opponents) {
            // Check planeswalkers
            for (Permanent pw : game.getBattlefield().getActivePermanents(
                    mage.filter.StaticFilters.FILTER_PERMANENT_PLANESWALKER, playerId, game)) {
                if (pw.getName().toLowerCase().startsWith(lowerName)
                        || pw.getName().toLowerCase().contains(lowerName)) {
                    if (pw.canBeAttacked(null, oppId, game)) return pw.getId();
                }
            }
            // Check battles
            for (Permanent battle : game.getBattlefield().getActivePermanents(
                    mage.filter.StaticFilters.FILTER_PERMANENT_BATTLE, playerId, game)) {
                if (battle.getName().toLowerCase().startsWith(lowerName)
                        || battle.getName().toLowerCase().contains(lowerName)) {
                    if (battle.canBeAttacked(null, oppId, game)) return battle.getId();
                }
            }
        }
        return null;
    }

    /**
     * Collect all in-game opponent UUIDs (helper for planeswalker targeting).
     */
    private static List<UUID> attackers(List<UUID> opponents, Game game) {
        return opponents.stream()
                .filter(id -> {
                    Player p = game.getPlayer(id);
                    return p != null && p.isInGame();
                })
                .collect(Collectors.toList());
    }

    // ==================================================================
    //  COPY / UTILITY
    // ==================================================================

    @Override
    public LLMPlayer copy() { return new LLMPlayer(this); }

    @Override
    public void quit(Game game) {
        // Call super FIRST so hasLost()/hasQuit() is set before generatePostGameSummary reads it.
        // RESULT-COMMIT ORDERING: super.quit/super.won/super.lostForced
        // set the player's win/loss flags — the game result is committed HERE, on
        // the game thread, before any reflection work is snapshotted or submitted.
        // The reflection itself runs OFF this thread and can no longer throw into
        // the game-over machinery.
        super.quit(game);
        generatePostGameSummary(game);
    }

    @Override
    public void lostForced(Game game) {
        // Generate post-game reflection on natural game loss (deck-out, poison, etc.),
        // not just on manual quit. Won/lost are called from checkStateAndTriggered
        // when the game naturally ends.
        // Call super FIRST so hasLost() is set before generatePostGameSummary reads it.
        // RESULT-COMMIT ORDERING: the loss flag is committed on the
        // game thread before any reflection work; the reflection runs off-thread
        // and cannot throw into this path.
        super.lostForced(game);
        generatePostGameSummary(game);
    }

    @Override
    public void won(Game game) {
        // RESULT-COMMIT ORDERING: super.won() flips the win flag
        // and calls game.end() — the recorded result — BEFORE the reflection is
        // even snapshotted. A hung/slow provider can no longer flip the record
        // to "Game is a draw".
        super.won(game);
        generatePostGameSummary(game);
    }

    /**
     * Snapshot the game state the reflection needs, then submit the reflection
     * to the bounded post-game executor. Returns IMMEDIATELY — never throws,
     * never blocks on the LLM call, so the game-over machinery
     * (checkIfGameIsOver → endGameAndStartNextGame: sideboard, game 2) proceeds
     * without waiting for a slow/hung provider.
     *
     * <p>Reflection runs OFF the game thread (async): match + sideboard proceed
     * immediately; failure = no postgame file, loud log (+ optional UI surface).
     * This is the explicit, approved degradation that resolves the Fail-Fast
     * tension: reflection failure is
     * logged loudly (logger.error with the full cause) and yields no postgame
     * file, but is never match-fatal and never corrupts the recorded result.
     *
     * <p>If the snapshot/submit itself fails (or the bounded queue is saturated —
     * e.g. many games ended while one provider is hung), the degradation is the
     * same and equally loud: error log, no reflection. Nothing propagates.
     */
    private boolean generatePostGameSummary(Game game) {
        if (postGameSummaryGenerated) return false;
        postGameSummaryGenerated = true;
        boolean submitted = false;
        try {
            // Snapshot the game log ONCE on this (game) thread: the early-return
            // check and the reflection snapshot then read the same immutable copy.
            List<String> logSnapshot = snapshotGameLog(game);
            // Don't waste an API call on concessions before the game really started.
            // No async task is submitted here, so the game thread still owns the
            // transcript close (same as the pre-fix unconditional close).
            if (game.getTurnNum() <= 1 && logSnapshot.size() < 10) {
                releaseGameScopedRegistries(game);
                if (client != null) client.closeTranscript();
                return false;
            }
            // The transcript handoff token: the reflection task may close the
            // game-N transcript only while this generation is still current
            // (sideboarding/next game rotate it, and then the task owns nothing).
            final long transcriptToken = (client != null) ? client.detachTranscriptForAsyncClose() : -1L;
            // Client-key seats: pin the bridge route NOW, on the game thread,
            // while the game is still registered (
            // deciding-game-reflection-no-bridge.md). For the match-DECIDING
            // game the live gameId → tableId link is gone when the async
            // reflection fires 25-37 s later; the pinned token lets the
            // transport serve that call from the captured route instead.
            // Server-key seats never reach sendViaBridge and are unaffected.
            if (client != null && config.isUseClientKey()) {
                client.pinBridgeRouteForReflection();
            }
            final PostgameReflectionSnapshot snapshot =
                    PostgameReflectionSnapshot.capture(this, game, logSnapshot);
            submitted = submitPostGameReflection(() -> runPostGameReflection(snapshot, transcriptToken));
            // The reflection worker reads only the immutable snapshot, so the
            // game-scoped serializer registry can be released now (same timing
            // as before the async change: right after the game-end work).
            releaseGameScopedRegistries(game);
            if (!submitted && client != null) {
                // Queue saturation: no worker will ever run the handoff, so the
                // game thread keeps the close (and the writer cannot leak open).
                client.closeTranscript();
            }
            return submitted;
        } catch (Throwable t) {
            // Never propagate into checkIfGameIsOver: loud log, no postgame file.
            logger.error("Post-game reflection could not be scheduled for " + getName()
                    + " (game " + game.getId() + ") — match continues, no postgame file: " + t, t);
            postGameReflectionFailures++;
            releaseGameScopedRegistries(game);
            if (client != null) client.closeTranscript();
            return false;
        } finally {
            // No worker will run the reflection (queue saturation or a snapshot
            // failure), so the pin taken above has no consumer left to release it
            // — release it here or it leaks. On the success path the worker's
            // finally owns the release.
            if (!submitted && client != null) {
                try {
                    client.releaseReflectionRoute();
                } catch (Throwable releaseFailure) {
                    logger.warn("Post-game reflection: bridge pin release failed for " + getName() + ": "
                            + releaseFailure);
                }
            }
        }
    }

    /** Drop per-game serializer state (encounter-based full-text-once registry —
     * the whole game entry, all seats). Idempotent. */
    private void releaseGameScopedRegistries(Game game) {
        try {
            LLMGameSerializer.unregisterSeenCardNames(game.getId());
        } catch (Throwable t) {
            logger.warn("Could not release game-scoped LLM registries for game " + game.getId() + ": " + t);
        }
    }

    /** Submit to the bounded executor; queue saturation degrades loudly, never blocks. */
    private boolean submitPostGameReflection(Runnable task) {
        try {
            postGameReflectionFuture = POSTGAME_REFLECTION_EXECUTOR.submit(task);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            logger.error("Post-game reflection queue full (capacity "
                    + POSTGAME_REFLECTION_QUEUE_CAPACITY + ") for " + getName()
                    + " — dropping reflection, match continues: " + e, e);
            postGameReflectionFailures++;
            return false;
        }
    }

    /**
     * Executes on the post-game executor thread: rebuild the prompts from the
     * snapshot, make the (possibly slow/hung) LLM calls, write the postgame
     * file, close the transcript iff still owned. EVERY failure mode ends in a
     * loud error log + no postgame file; nothing here can reach the match
     * machinery.
     */
    private void runPostGameReflection(PostgameReflectionSnapshot snapshot, long transcriptToken) {
        if (client != null) {
            // Scope this thread's transcript writes to the handed-off generation:
            // if sideboarding/next game rotates the transcript while we are still
            // in the slow call, the late exchange record is skipped instead of
            // being appended into the wrong file.
            client.beginAsyncTranscriptScope(transcriptToken);
            // Client-key seats: expose the (game, seat) bridge pin to THIS thread
            // only — the reflection and its match-review call are the pin's sole
            // consumers. Every other thread (game decisions, sideboard) keeps the
            // live lookup, so it cannot steal the pin

            client.beginPinnedReflectionRoute();
        }
        try {
            generatePostGameSummaryFromSnapshot(snapshot);
        } catch (Throwable t) {
            logger.error("Failed to generate post-game reflection for " + getName()
                    + " (game " + snapshot.gameId + ") — no postgame file, match unaffected: "
                    + t, t);
            postGameReflectionFailures++;
        } finally {
            if (client != null) {
                client.endPinnedReflectionRoute();
                client.endAsyncTranscriptScope();
                // Release the bridge pin: it had to outlive table teardown, but it
                // must not outlive this task, which is its only user. Bounds the
                // transport's pin store (one pin per seat here, not per game).
                try {
                    client.releaseReflectionRoute();
                } catch (Throwable releaseFailure) {
                    logger.warn("Post-game reflection: bridge pin release failed for " + getName()
                            + ": " + releaseFailure);
                }
                // Close the game-N transcript iff we still own it. No-op when
                // sideboarding/next game already rotated it. Failures here are
                // logged, never thrown — this thread must not produce an
                // uncaught exception that would replace the worker.
                try {
                    client.closeTranscriptIfCurrent(transcriptToken);
                } catch (Throwable closeFailure) {
                    logger.error("Post-game reflection: transcript close failed for " + getName() + ": "
                            + closeFailure, closeFailure);
                }
            }
        }
    }

    // ==================================================================
    //  SIDEBOARDING
    // ==================================================================

    private static final String SIDEBOARD_SYSTEM_PROMPT =
            "You are an expert Magic: The Gathering player sideboarding for game 2/3 of a match.\n"
            + "\n"
            + "Analyze the previous game, what the opponent played, and your available\n"
            + "sideboard options. Then decide which cards to swap.\n"
            + "\n"
            + "Be strategic: side out dead cards, bring in answers to their threats,\n"
            + "and adjust your plan for the specific matchup.\n"
            + "\n"
            + "If no changes are justified, reply NONE.";

    private static final String REVISED_STRATEGY_SYSTEM_PROMPT =
            "You are an expert Magic: The Gathering player revising your strategy between games of a match.\n"
            + "\n"
            + "You just sideboarded for the next game. Now, write an updated gameplan that reflects\n"
            + "your new configuration and what you learned from the previous game.\n"
            + "\n"
            + "Cover:\n"
            + "- Any changes to your primary strategy or win condition\n"
            + "- New card synergies introduced by sideboard additions\n"
            + "- Updated mulligan strategy (what does the ideal opening hand look like now?)\n"
            + "- Tactical adjustments against this specific opponent's deck\n"
            + "\n"
            + "Write ONLY the revised gameplan. 100-250 words. No markdown, no preamble.";

    @Override
    public void sideboard(Match match, Deck deck) {
        if (config == null || deck.getSideboard().isEmpty()) {
            // No sideboard or no config — submit unchanged
            match.submitDeck(playerId, deck);
            return;
        }

        Game game = match.getGame();
        if (game == null) {
            match.submitDeck(playerId, deck);
            return;
        }

        // Work on a copy so the original Deck (which may be shared/reused by the
        // tournament layer) is not mutated in place.
        Deck workingDeck = deck.copy();

        // Reinitialize transcript for the sideboarding phase — won/lostForced/quit
        // closed the game-N transcript, but sideboard() calls client.chat() which
        // writes to the transcript. Without this, writeTranscript throws
        // IllegalStateException("writeTranscript called before initTranscript").
        client.initTranscript(game.getId().toString(), getName());
        // Client-key bridge routing context: gameId + seatId + display name.
        // Needed before any LLM call in browser-key mode.
        client.setBridgeContext(game.getId().toString(), this.getId().toString(), getName());

        // Sideboard summary accumulators — populated in the try block, written
        // to the sideboard summary file after the try/catch.
        String sideboardAnswer = null;
        String sideboardThinking = null;
        boolean sideboardNoChanges = false;

        try {
            String prompt = LLMGameSerializer.buildSideboardPrompt(
                    game, this.getId(), workingDeck, match, this.getName(), matchReview);

            if (config.isVerboseLogging()) {
                logger.info("=== LLM SIDEBOARDING ===");
                logger.info("Prompt length: " + prompt.length() + " chars");
                logger.info(prompt);
                logger.info("=== END SIDEBOARD PROMPT ===");
            }

            LLMResponse resp = client.chat(SIDEBOARD_SYSTEM_PROMPT, prompt);

            if (resp != null && resp.answer != null && !resp.answer.trim().isEmpty()) {
                String answer = resp.answer.trim();
                sideboardAnswer = answer;
                sideboardThinking = resp.thinking;
                if (config.isVerboseLogging()) {
                    logger.info("Sideboard response: " + answer);
                }

                // NONE gate: the prompt asks for "NONE on a
                // line by itself", but models write "NONE." or trail text
                // after it. Accept NONE on the FIRST line (stripped of
                // trailing punctuation) when there is no IN: section
                // anywhere; anything else goes to the IN:/OUT: parser,
                // which Fail-Fasts on missing sections.
                String noneStripped = firstLineOf(answer).toUpperCase()
                        .replaceAll("[.!\\s]+$", "");
                boolean noChanges = noneStripped.equals("NONE")
                        && !answer.toUpperCase().contains("IN:");
                sideboardNoChanges = noChanges;
                if (!noChanges) {
                    applySideboardChanges(workingDeck, answer);
                } else if (config.isVerboseLogging()) {
                    logger.info("LLM chose no sideboard changes");
                }
            }
        } catch (Throwable t) {
            // Sideboarding sits ON the match-end path (TableController.sideboard →
            // MatchImpl.sideboard). ANY failure of the sideboard LLM calls must END
            // the match, not escape: an escaping throw is not seen as GameException
            // by TableController.endGameAndStartNextGame (its catch is GameException),
            // so it reaches GameWorker's Throwable catch with endProcessed already
            // true — endGameWithError is skipped, closeTable()/matchEnd() never run,
            // and GamesRoomImpl keeps publishing the table in SIDEBOARDING forever
            // (six Bo3 tables once wedged for hours). The previous guard gated
            // quitMatch on thrownAfterFullLadder(t), but the sideboard path uses
            // LLMClient.chat — a SINGLE call with no retry ladder — so the "LLM call
            // failed after N attempts" signature never appears and every provider
            // failure (retryable or not; the browser reports HTTP 400/403/5xx as
            // retryable:true) fell through to the rethrow. On the match-end path there
            // is no "next ladder window" to defer to, so terminal-for-the-match is the
            // correct classification: quitMatch unconditionally.
            endMatchAfterSideboardFailure(match, deck, t);
            return;
        }

        // Write a human-readable sideboard summary alongside the postgame file.
        String modelShort = config.getModel().replaceAll("[:/].*", "");
        StringBuilder sideboardOutput = new StringBuilder();
        sideboardOutput.append("=== SIDEBOARDING ===\n");
        sideboardOutput.append("Player: ").append(getName()).append("\n");
        sideboardOutput.append("Model: ").append(config.getModel()).append("\n");
        sideboardOutput.append("Game: ").append(game.getId()).append("\n");
        sideboardOutput.append("Generated: ").append(java.time.LocalDateTime.now()).append("\n");
        sideboardOutput.append("\n");

        sideboardOutput.append("--- SIDEBOARD RESPONSE ---\n");
        if (sideboardAnswer != null) {
            sideboardOutput.append(sideboardAnswer).append("\n\n");
        } else {
            sideboardOutput.append("(no response)\n\n");
        }

        if (sideboardThinking != null && !sideboardThinking.isEmpty()) {
            sideboardOutput.append("--- THINKING ---\n");
            sideboardOutput.append(sideboardThinking).append("\n\n");
        }

        sideboardOutput.append("--- APPLIED CHANGES ---\n");
        if (sideboardAnswer == null) {
            sideboardOutput.append("No sideboard changes (no response).\n");
        } else if (sideboardNoChanges) {
            sideboardOutput.append("No sideboard changes (NONE).\n");
        } else {
            // Best-effort parse of the IN:/OUT: sections (applySideboardChanges
            // already validated them, so this normally succeeds).
            String upper = sideboardAnswer.toUpperCase();
            int inIdx = upper.indexOf("IN:");
            int outIdx = upper.indexOf("OUT:", inIdx >= 0 ? inIdx : 0);
            if (inIdx >= 0 && outIdx >= 0) {
                List<String> cardsIn = parseCardList(sideboardAnswer.substring(inIdx + 3, outIdx).trim());
                List<String> cardsOut = parseCardList(sideboardAnswer.substring(outIdx + 4).trim());
                sideboardOutput.append("IN:  ").append(cardsIn).append("\n");
                sideboardOutput.append("OUT: ").append(cardsOut).append("\n");
            } else {
                sideboardOutput.append("(could not parse IN:/OUT: sections)\n");
            }
        }
        sideboardOutput.append("maindeck: ").append(workingDeck.getCards().size()).append(" cards\n");
        sideboardOutput.append("sideboard: ").append(workingDeck.getSideboard().size()).append(" cards\n");

        client.writeSideboardSummary(getName(), modelShort, sideboardOutput.toString());

        // Generate revised strategy for game 2+ using the (possibly updated) deck.
        // This is the sideboard phase's SECOND LLM call (client.chat) and it runs on
        // the same match-end path: if it throws it would escape sideboard() exactly
        // like the main chat did pre-fix, wedging the table in SIDEBOARDING. Same
        // terminal handling — quit the match instead of escaping.
        try {
            generateRevisedStrategy(workingDeck, game);
        } catch (Throwable t) {
            endMatchAfterSideboardFailure(match, deck, t);
            return;
        }

        // Always submit, whether or not changes were made
        match.submitDeck(playerId, workingDeck);
    }

    /**
     * A failure anywhere in the sideboard phase must END the match, not escape.
     * Sideboarding runs ON the match-end path (TableController.sideboard →
     * MatchImpl.sideboard); an escaping throw is not caught as GameException by
     * TableController.endGameAndStartNextGame, so it reaches GameWorker's Throwable
     * catch with endProcessed already true — endGameWithError is skipped,
     * closeTable()/matchEnd() never run, and the table stays in SIDEBOARDING forever
     * This logs FATAL (Fail-Fast: the failure is loud, not
     * swallowed), releases the sideboard wait by submitting the unchanged deck so
     * isDoneSideboarding() unblocks MatchImpl.sideboard(), then
     * quitMatch → setQuit → checkIfMatchEnds → table close.
     */
    private void endMatchAfterSideboardFailure(Match match, Deck unchangedDeck, Throwable t) {
        logger.fatal("LLM sideboard failure — quitting match "
                + match.getId() + " so it ends cleanly (no stuck between-games "
                + "table). Root cause: " + t.getMessage(), t);
        try {
            match.submitDeck(playerId, unchangedDeck);  // release the sideboard wait
        } catch (Throwable submitFailure) {
            logger.error("Sideboard terminal path: deck submit failed too: "
                    + submitFailure, submitFailure);
        }
        match.quitMatch(playerId);
        client.writeSideboardSummary(getName(), modelShortOf(config),
                "=== SIDEBOARDING ===\nPlayer: " + getName()
                + "\nTERMINAL LLM FAILURE — match quit (no deck changes).\nRoot cause: "
                + t.getMessage() + "\n");
    }

    /**
     * After sideboarding, generate a revised gameplan that reflects the updated
     * deck configuration and lessons learned from the previous game.
     * This will be injected into the system prompt for game 2+ instead of a
     * fresh deck assessment.
     */
    private void generateRevisedStrategy(Deck deck, Game game) {
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("=== REVISED GAMEPLAN FOR NEXT GAME ===\n\n");

            prompt.append("You just finished a game and sideboarded. Here's what you know:\n\n");

            // Previous game review
            if (matchReview != null) {
                prompt.append("=== YOUR REVIEW OF THE LAST GAME ===\n");
                prompt.append(matchReview).append("\n\n");
            }

            // Updated decklist (post-board) — grouped like the other decklists
            prompt.append("=== YOUR UPDATED DECK (POST-SIDEBOARD, ")
                    .append(deck.getMaindeckCards().size()).append(" cards) ===\n");
            List<Card> mainCards = deck.getMaindeckCards().stream()
                    .sorted(Comparator.comparing(Card::getName))
                    .collect(Collectors.toList());
            prompt.append(LLMGameSerializer.formatDecklistGrouped(mainCards));
            prompt.append("\n");

            // Opponent reminder
            prompt.append("=== YOUR TASK ===\n");
            prompt.append("Based on your review of the last game and your updated deck configuration,\n");
            prompt.append("write a concise, actionable gameplan for the upcoming game.\n");
            prompt.append("Focus on: what to mulligan for, how to sequence early turns,\n");
            prompt.append("key synergies to assemble, and how to approach this specific matchup.\n\n");
            prompt.append("Write ONLY the revised gameplan. No markdown, no preamble.\n");

            LLMResponse resp = client.chat(REVISED_STRATEGY_SYSTEM_PROMPT, prompt.toString());

            if (resp != null && resp.answer != null && !resp.answer.trim().isEmpty()) {
                revisedStrategy = resp.answer.trim();
                if (config.isVerboseLogging()) {
                    logger.info("=== REVISED STRATEGY ===\n" + revisedStrategy + "\n=== END ===");
                }

                // Append to transcript
                String modelShort = config.getModel().replaceAll("[:/].*", "");
                StringBuilder strategyOutput = new StringBuilder();
                strategyOutput.append("\n=== REVISED STRATEGY (Post-Sideboard) ===\n");
                if (resp.thinking != null && !resp.thinking.isEmpty()) {
                    strategyOutput.append("--- THINKING ---\n");
                    strategyOutput.append(resp.thinking).append("\n\n");
                }
                strategyOutput.append(revisedStrategy);
                client.writePostGameSummary(getName(), modelShort, strategyOutput.toString());
            }
        } catch (Throwable t) {
            throw new RuntimeException("Failed to generate revised strategy: " + t.getMessage(), t);
        }
    }

    /**
     * Parse the LLM's sideboard response and apply card swaps.
     * Expected format:
     *   IN:
     *   Card Name A
     *   Card Name B
     *   OUT:
     *   Card Name X
     *   Card Name Y
     */
    private void applySideboardChanges(Deck deck, String response) {
        String upper = response.toUpperCase();
        int inIdx = upper.indexOf("IN:");
        int outIdx = upper.indexOf("OUT:", inIdx >= 0 ? inIdx : 0);

        if (inIdx < 0 || outIdx < 0) {
            // Fail Fast: the LLM sideboard response is missing required IN:/OUT:
            // sections. Crash rather than silently skipping sideboarding.
            throw new RuntimeException("applySideboardChanges: sideboard response missing IN:/OUT: sections: " + response);
        }

        // Extract card names from the IN and OUT sections
        String inSection = response.substring(inIdx + 3, outIdx).trim();
        String outSection = response.substring(outIdx + 4).trim();

        List<String> cardsIn = parseCardList(inSection);
        List<String> cardsOut = parseCardList(outSection);

        if (cardsIn.isEmpty() && cardsOut.isEmpty()) {
            if (config.isVerboseLogging())
                logger.info("No cards parsed from sideboard sections");
            return;
        }

        if (config.isVerboseLogging()) {
            logger.info("Sideboard IN:  " + cardsIn);
            logger.info("Sideboard OUT: " + cardsOut);
        }

        Set<Card> maindeck = deck.getCards();
        Set<Card> sideboard = deck.getSideboard();

        // Process OUT first: remove from maindeck, add to sideboard
        for (String cardName : cardsOut) {
            Card toRemove = findCardByName(maindeck, cardName);
            if (toRemove != null) {
                maindeck.remove(toRemove);
                sideboard.add(toRemove);
                if (config.isVerboseLogging())
                    logger.info("  Sideboarded OUT: " + cardName);
            } else {
                logger.warn("  Could not find '" + cardName + "' in maindeck");
            }
        }

        // Process IN: remove from sideboard, add to maindeck
        for (String cardName : cardsIn) {
            Card toAdd = findCardByName(sideboard, cardName);
            if (toAdd != null) {
                sideboard.remove(toAdd);
                maindeck.add(toAdd);
                if (config.isVerboseLogging())
                    logger.info("  Sideboarded IN:  " + cardName);
            } else {
                logger.warn("  Could not find '" + cardName + "' in sideboard");
            }
        }

        if (config.isVerboseLogging()) {
            logger.info("Post-sideboard maindeck: " + maindeck.size() + " cards");
            logger.info("Post-sideboard sideboard: " + sideboard.size() + " cards");
        }
    }

    /** Parse a block of card names (one per line) into a list. */
    private List<String> parseCardList(String section) {
        return Arrays.stream(section.split("[\\r\\n]+"))
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.equalsIgnoreCase("NONE"))
                .collect(Collectors.toList());
    }

    /**
     * Find a card in a set by name (case-insensitive).
     * Also handles partial matches where the LLM might include mana costs.
     */
    private Card findCardByName(Set<Card> cards, String name) {
        String search = name.trim().toLowerCase();
        // Direct match first
        for (Card c : cards) {
            if (c.getName().toLowerCase().equals(search)) return c;
        }
        // Try matching if the LLM appended cost text
        for (Card c : cards) {
            if (search.startsWith(c.getName().toLowerCase())) return c;
        }
        return null;
    }

    // ==================================================================
    //  POST-GAME REFLECTION
    // ==================================================================

    private static final String POSTGAME_SYSTEM_PROMPT =
            "You are an expert Magic: The Gathering player conducting a post-game analysis.\n"
            + "Be honest and specific. Identify mistakes. Credit opponent's good plays.\n"
            + "Distinguish between bad decisions, bad luck, and things you couldn't control.\n"
            + "Write in first person, as if you just finished playing the match.";

    private static final String MATCH_REVIEW_SYSTEM_PROMPT =
            "You are an expert Magic: The Gathering player writing a match review for yourself.\n"
            + "This review will be shown to you during sideboarding before the next game.\n"
            + "\n"
            + "Be concise and actionable. Focus on:\n"
            + "- What the opponent's deck is doing (archetype, key cards, speed)\n"
            + "- What worked well in your own deck and what underperformed\n"
            + "- Specific sideboard cards that would help in this matchup\n"
            + "- Any tactical adjustments needed (mulligan strategy, play patterns)\n"
            + "\n"
            + "Write in first person. 100-250 words. No markdown, just the review.";

    /**
     * Post-game reflection prompt game-log caps: the log section is bounded to
     * head + tail chars (whole entries only, never split), with an explicit
     * truncation marker when entries are dropped.
     */
    private static final int POSTGAME_GAME_LOG_HEAD_CHARS = 10_000;
    private static final int POSTGAME_GAME_LOG_TAIL_CHARS = 30_000;

    /**
     * Cap a cleaned game-log entry list for prompt embedding: keep whole head
     * and tail entries whose char totals (entry length + 1 for the appended
     * "\n") fit in headChars / tailChars, replacing the middle with one marker
     * entry when truncation happens. Never splits an entry; the last entry is
     * always present. When the total fits the budget the SAME list instance is
     * returned (caller output stays byte-identical to the uncapped path).
     */
    static java.util.List<String> capGameLogEntries(java.util.List<String> cleanedEntries, int headChars, int tailChars) {
        if (cleanedEntries == null || cleanedEntries.isEmpty()) return cleanedEntries;

        long total = 0;
        for (String s : cleanedEntries) total += (long) s.length() + 1;
        if (total <= (long) headChars + (long) tailChars) return cleanedEntries;

        int n = cleanedEntries.size();

        // Head: whole entries from the start while they fit in headChars.
        int headKept = 0;
        long headLen = 0;
        while (headKept < n) {
            long next = (long) cleanedEntries.get(headKept).length() + 1;
            if (headLen + next > headChars) break;
            headLen += next;
            headKept++;
        }

        // Tail: whole entries from the end while they fit in tailChars.
        int tailKept = 0;
        long tailLen = 0;
        while (tailKept < n - headKept) {
            long next = (long) cleanedEntries.get(n - 1 - tailKept).length() + 1;
            if (tailLen + next > tailChars) break;
            tailLen += next;
            tailKept++;
        }

        // Ranges overlap (head entries would collide with tail entries): the
        // tail takes precedence, drop head entries until disjoint.
        if (headKept + tailKept > n) {
            headKept = n - tailKept;
            if (headKept < 0) headKept = 0;
        }

        int omitted = n - headKept - tailKept;
        java.util.List<String> result = new java.util.ArrayList<>(headKept + 1 + tailKept);
        for (int i = 0; i < headKept; i++) result.add(cleanedEntries.get(i));
        result.add("... [game log truncated: kept first " + headKept + " and last " + tailKept
                + " of " + n + " entries — " + omitted + " entries omitted] ...");
        for (int i = n - tailKept; i < n; i++) result.add(cleanedEntries.get(i));
        return result;
    }

    /**
     * Test hook: wait for this player's async post-game reflection task (if one
     * was submitted) to finish. Returns true when the task ran to completion
     * (success OR handled failure), false on timeout.
     */
    public boolean awaitPostGameReflectionForTest(long timeoutMillis) {
        java.util.concurrent.Future<?> f = postGameReflectionFuture;
        if (f == null) return true;
        try {
            f.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            return false;
        } catch (Throwable t) {
            return true;  // task failed internally — runPostGameReflection caught and logged it
        }
    }

    /**
     * Test hook: how many reflection attempts failed or were dropped. The loud
     * degradation counter (failure = loud log, no postgame file).
     */
    public int postGameReflectionFailuresForTest() {
        return postGameReflectionFailures;
    }

    /**
     * Immutable snapshot of everything the post-game reflection needs, captured
     * on the GAME thread at game-end time.
     * The async worker then touches no live game object — it cannot race the
     * match lifecycle (sideboarding, game 2, table teardown) and cannot see a
     * mutated board state.
     */
    static final class PostgameReflectionSnapshot {
        final UUID gameId;
        final String playerName;
        final String model;
        final String modelShort;
        final String winnerDescription;   // getWinnerDescription() at game end
        final int turnNum;
        final int selfLife;
        final String gamePlan;            // nullable
        final List<String> selfLog;       // locked snapshot (see snapshotSelfLog)
        final List<String> gameLog;       // locked snapshot (see snapshotGameLog)
        final boolean multiGameMatch;
        final int matchWins;              // matchPlayer.getWins() at game end
        final int winsNeeded;
        final List<String> finalLifeLines;   // preformatted prompt lines
        final List<String> opponentLines;    // preformatted review lines
        final String opponentLives;          // preformatted life concat
        final java.nio.file.Path transcriptGameDir;  // pinned; nullable

        private PostgameReflectionSnapshot(UUID gameId, String playerName, String model,
                String winnerDescription, int turnNum, int selfLife, String gamePlan,
                List<String> selfLog, List<String> gameLog, boolean multiGameMatch,
                int matchWins, int winsNeeded, List<String> finalLifeLines,
                List<String> opponentLines, String opponentLives,
                java.nio.file.Path transcriptGameDir) {
            this.gameId = gameId;
            this.playerName = playerName;
            this.model = model;
            this.modelShort = model.replaceAll("[:/].*", "");
            this.winnerDescription = winnerDescription;
            this.turnNum = turnNum;
            this.selfLife = selfLife;
            this.gamePlan = gamePlan;
            this.selfLog = selfLog;
            this.gameLog = gameLog;
            this.multiGameMatch = multiGameMatch;
            this.matchWins = matchWins;
            this.winsNeeded = winsNeeded;
            this.finalLifeLines = finalLifeLines;
            this.opponentLines = opponentLines;
            this.opponentLives = opponentLives;
            this.transcriptGameDir = transcriptGameDir;
        }

        /** Capture on the game thread; touches no game state afterwards. */
        static PostgameReflectionSnapshot capture(LLMPlayer p, Game game, List<String> gameLogSnapshot) {
            List<String> finalLifeLines = new ArrayList<>();
            for (Player other : game.getPlayers().values()) {
                String marker = other.getId().equals(p.getId()) ? " (YOU)" : "";
                StringBuilder line = new StringBuilder();
                line.append("  ").append(other.getName()).append(marker)
                        .append(": ").append(other.getLife()).append(" life");
                if (other.hasLost()) line.append(" — LOST");
                if (other.hasWon()) line.append(" — WON");
                finalLifeLines.add(line.toString());
            }

            List<String> opponentLines = new ArrayList<>();
            StringBuilder opponentLives = new StringBuilder();
            for (Player other : game.getPlayers().values()) {
                if (other.getId().equals(p.getId())) continue;
                opponentLives.append(other.getLife());
                StringBuilder line = new StringBuilder();
                line.append("  ").append(other.getName()).append(" — ");
                List<String> oppCardNames = new ArrayList<>();
                for (UUID cardId : other.getGraveyard()) {
                    Card c = game.getCard(cardId);
                    if (c != null) oppCardNames.add(c.getName());
                }
                if (oppCardNames.isEmpty()) {
                    line.append("saw few cards — short game");
                } else {
                    line.append("saw ").append(oppCardNames.size())
                            .append(" cards: ").append(String.join(", ", oppCardNames));
                }
                opponentLines.add(line.toString());
            }

            boolean multiGameMatch = p.matchPlayer != null && p.matchPlayer.getWinsNeeded() > 1;
            return new PostgameReflectionSnapshot(
                    game.getId(),
                    p.getName(),
                    p.config.getModel(),
                    LLMGameSerializer.getWinnerDescription(game),
                    game.getTurnNum(),
                    p.getLife(),
                    p.gamePlan,
                    p.snapshotSelfLog(),
                    gameLogSnapshot,
                    multiGameMatch,
                    p.matchPlayer != null ? p.matchPlayer.getWins() : 0,
                    p.matchPlayer != null ? p.matchPlayer.getWinsNeeded() : 1,
                    finalLifeLines,
                    opponentLines,
                    opponentLives.toString(),
                    p.client != null ? p.client.getTranscriptGameDir() : null);
        }
    }

    /**
     * Ask the LLM to reflect on the completed game and write a summary.
     * Runs on the post-game executor thread — NEVER on the game
     * thread, and only ever touching the snapshot captured by
     * {@link PostgameReflectionSnapshot#capture}. Any failure it throws is
     * handled by {@link #runPostGameReflection}: loud error log, no postgame
     * file, match unaffected.
     */
    private void generatePostGameSummaryFromSnapshot(PostgameReflectionSnapshot s) {
        try {
            boolean isMultiGameMatch = s.multiGameMatch;

            // Build the shared output buffer — everything goes into one file
            StringBuilder output = new StringBuilder();
            output.append("=== POST-GAME REFLECTION ===\n");
            output.append("Player: ").append(s.playerName).append("\n");
            output.append("Model: ").append(s.model).append("\n");
            output.append("Game: ").append(s.gameId).append("\n");
            output.append("Result: ").append(s.winnerDescription).append("\n");
            output.append("Turn: ").append(s.turnNum).append("\n");
            output.append("Life: ").append(s.selfLife).append("\n");
            output.append("Generated: ").append(java.time.LocalDateTime.now()).append("\n");
            output.append("\n");

            // ================================================================
            // Reflection prompt
            // ================================================================
            StringBuilder prompt = new StringBuilder();
            prompt.append("=== POST-GAME REFLECTION ===\n\n");

            String winner = s.winnerDescription;
            prompt.append("Result: ").append(winner).append("\n");
            prompt.append("Final turn: ").append(s.turnNum).append("\n\n");

            prompt.append("=== FINAL LIFE TOTALS ===\n");
            for (String line : s.finalLifeLines) {
                prompt.append(line).append("\n");
            }
            prompt.append("\n");

            if (s.gamePlan != null) {
                prompt.append("=== YOUR PRE-GAME GAMEPLAN ===\n");
                prompt.append(s.gamePlan).append("\n\n");
                prompt.append("(Compare what you planned to what actually happened.)\n\n");
            }

            // Both logs were snapshotted (locked, retried) on the game thread at
            // capture time — see PostgameReflectionSnapshot.capture. The worker
            // only ever iterates those immutable copies.
            List<String> selfLogSnapshot = s.selfLog;
            if (!selfLogSnapshot.isEmpty()) {
                prompt.append("=== YOUR DECISIONS DURING THE GAME ===\n");
                for (String entry : selfLogSnapshot) {
                    prompt.append(entry).append("\n");
                }
                prompt.append("\n");
            }

            List<String> gameLog = s.gameLog;
            if (!gameLog.isEmpty()) {
                java.util.List<String> cleanedLog = new java.util.ArrayList<>(gameLog.size());
                for (String entry : gameLog) {
                    cleanedLog.add(LLMGameSerializer.cleanHtml(entry));
                }
                java.util.List<String> capped = capGameLogEntries(cleanedLog, POSTGAME_GAME_LOG_HEAD_CHARS, POSTGAME_GAME_LOG_TAIL_CHARS);
                if (capped.size() != cleanedLog.size()) {
                    logger.info("Post-game game log truncated for prompt: " + capped.size() + " entries (was " + cleanedLog.size() + ")");
                }
                prompt.append("=== COMPLETE GAME LOG ===\n");
                for (String entry : capped) {
                    prompt.append(entry).append("\n");
                }
                prompt.append("\n");
            }
            prompt.append("=== REFLECTION QUESTIONS ===\n");
            prompt.append("1. How did the game go? Summarize the key moments.\n");
            prompt.append("2. What did you do WELL? Where were your decisions correct?\n");
            prompt.append("3. What did you do POORLY? What decisions would you change?\n");
            prompt.append("4. What was out of your control? (mana screw/flood, opponent nut draw, bad matchups)\n");
            prompt.append("5. Did your gameplan hold up? What would you do differently next time?\n");
            prompt.append("6. Rate your overall play on a scale of 1-10 and explain why.\n");
            prompt.append("7. Did you experience any technical issues with the game engine?\n");
            prompt.append("   a) Were there moments where the game didn't ask you for a decision you expected\n");
            prompt.append("      (e.g. an imprint trigger on Chrome Mox, an optional ability you wanted to use,\n");
            prompt.append("      a choice of modes, a pile selection, a target you should have been able to pick)?\n");
            prompt.append("   b) Did any of your choices fail to execute as expected — did you pick something\n");
            prompt.append("      that the game ignored or rejected?\n");
            prompt.append("   c) Were you shown clearly invalid options (cards you shouldn't have been able\n");
            prompt.append("      to pick, targets out of range, etc.) that confused your decision-making?\n");
            prompt.append("   d) Did the game state information provided to you seem inaccurate or incomplete\n");
            prompt.append("      at any point (wrong life totals, missing permanents, stale board state)?\n");
            prompt.append("   Be specific — name the card, the situation, and what went wrong.\n\n");
            prompt.append("8. Rate your experience with the game engine on a scale of 1-10.\n");
            prompt.append("   Consider: how smooth was the interface, how accurate were the prompts,\n");
            prompt.append("   did actions resolve as expected, were you able to express your intentions clearly?\n\n");
            prompt.append("9. Did you have fun? Be honest — was this an enjoyable game?\n");
            prompt.append("   What made it fun (or not fun)? Length, interaction, interesting decisions?\n\n");
            prompt.append("Write a thorough, honest reflection addressing all nine questions above.\n");

            // ================================================================
            // Reflection LLM call — the slow/hung call, run off the game
            // thread by runPostGameReflection, which catches any failure here.
            // ================================================================
            LLMResponse reflection = client.chat(POSTGAME_SYSTEM_PROMPT, prompt.toString());

            if (reflection != null && reflection.answer != null && !reflection.answer.trim().isEmpty()) {
                postGameReflection = reflection.answer.trim();
                if (reflection.thinking != null && !reflection.thinking.isEmpty()) {
                    output.append("--- THINKING ---\n");
                    output.append(reflection.thinking).append("\n\n");
                }
                output.append("--- REFLECTION ---\n");
                output.append(reflection.answer).append("\n");

                if (config.isVerboseLogging()) {
                    logger.info("=== POST-GAME REFLECTION ===\n" + output);
                }
            } else {
                logger.warn("Post-game reflection returned null/empty for " + s.playerName);
                // Still write the header so at least a file exists for debugging
                output.append("--- REFLECTION ---\n");
                output.append("(No response from LLM — the model returned null or timed out)\n\n");
            }

            // ================================================================
            // Match review for sideboarding (only for multi-game matches)
            // ================================================================
            if (!isMultiGameMatch) {
                output.append("# Match review skipped — single-game match, no sideboarding needed.\n");
            } else {
                StringBuilder reviewPrompt = new StringBuilder();
                reviewPrompt.append("=== MATCH REVIEW FOR SIDEBOARDING ===\n\n");
                // matchPlayer.getWins() hasn't been incremented yet at game-end
                // time (the match system does that after the game ends). If this
                // player won this game, add 1 to reflect the current state. The
                // wins value was captured on the game thread at snapshot time.
                int displayWins = s.matchWins;
                if (winner != null && winner.contains(s.playerName) && winner.contains("WON")) {
                    displayWins++;
                }
                reviewPrompt.append("You just finished a game of a best-of-")
                        .append(s.winsNeeded * 2 - 1)
                        .append(" match. (Your record: ")
                        .append(displayWins).append(" wins).\n\n");

                reviewPrompt.append("Result: ").append(winner).append(".\n\n");

                reviewPrompt.append("Opponents you faced:\n");
                for (String line : s.opponentLines) {
                    reviewPrompt.append(line).append("\n");
                }
                reviewPrompt.append("\n");

                reviewPrompt.append("Final life total: ").append(s.selfLife).append("\n");
                reviewPrompt.append("Opponent final life: ").append(s.opponentLives).append("\n\n");

                reviewPrompt.append("=== YOUR KEY DECISIONS ===\n");
                if (!selfLogSnapshot.isEmpty()) {
                    int showFrom = Math.max(0, selfLogSnapshot.size() - 20);
                    for (int i = showFrom; i < selfLogSnapshot.size(); i++) {
                        reviewPrompt.append(selfLogSnapshot.get(i)).append("\n");
                    }
                    if (showFrom > 0) {
                        reviewPrompt.append("... (").append(showFrom)
                                .append(" earlier decisions omitted)\n");
                    }
                }
                reviewPrompt.append("\n");

                reviewPrompt.append("=== YOUR TASK ===\n");
                reviewPrompt.append("Write a concise match review (100-250 words) covering:\n");
                reviewPrompt.append("- What deck/archetype your opponent was on and what key cards you saw\n");
                reviewPrompt.append("- What cards in YOUR deck overperformed or underperformed this game\n");
                reviewPrompt.append("- What specific sideboard cards would help in this matchup\n");
                reviewPrompt.append("- Any adjustments to play patterns or mulligan strategy\n\n");
                reviewPrompt.append("This review will be shown to you during sideboarding. "
                        + "Write ONLY the review — no preamble.\n");

                LLMResponse reviewResp = client.chat(MATCH_REVIEW_SYSTEM_PROMPT, reviewPrompt.toString());

                // Replay viewer note (2026-09-30): the review is NOT recorded as
                // a replay preamble here. This code runs on the async post-game
                // executor AFTER endGameWithResult already wrote this game's
                // .game save — such a record could never reach its own replay.
                // Worse, recordReplayPreambleById's clear-on-game-mismatch
                // would fire against whichever game the player is in by the
                // time the slow call returns (in a Bo3: game 2), wiping the
                // fresh game-2 buckets. The review reaches the NEXT game's
                // replay legitimately instead: `matchReview` (volatile,
                // serialized) rides the player object into game 2+'s saves,
                // and LLMPlayerHelper.buildReplayViews attaches it to the
                // view — exactly the data the live tab shows mid-sideboarding.

                output.append("\n=== MATCH REVIEW (for sideboarding) ===\n");

                if (reviewResp != null && reviewResp.answer != null && !reviewResp.answer.trim().isEmpty()) {
                    matchReview = reviewResp.answer.trim();
                    if (reviewResp.thinking != null && !reviewResp.thinking.isEmpty()) {
                        output.append("--- THINKING ---\n");
                        output.append(reviewResp.thinking).append("\n\n");
                    }
                    output.append("--- REVIEW ---\n");
                    output.append(matchReview).append("\n");

                    if (config.isVerboseLogging()) {
                        logger.info("=== MATCH REVIEW (for sideboarding) ===\n"
                                + matchReview + "\n=== END ===");
                    }
                } else {
                    logger.warn("Match review returned null/empty for " + s.playerName);
                    output.append("--- REVIEW ---\n");
                    output.append("(No response from LLM — the model returned null or timed out)\n\n");
                }
            }

            // Write everything as a single file. The game directory is PINNED in
            // the snapshot: sideboarding/next-game re-points transcriptGameDir,
            // and a slow reflection must still file its summary under game N.
            client.writePostGameSummaryTo(s.transcriptGameDir, s.playerName, s.modelShort, output.toString());

        } catch (Throwable t) {
            throw new RuntimeException("Failed to generate post-game reflection: " + t.getMessage(), t);
        }
    }

    @Override
    public boolean isHuman() { return false; }
}
