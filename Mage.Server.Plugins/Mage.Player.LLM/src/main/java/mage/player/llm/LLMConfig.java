package mage.player.llm;

import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Configuration for the LLM player — endpoint, model, prompt template, etc.
 * Designed to be easily serializable/configurable for future GUI/server config integration.
 * <p>
 * <b>Setters are builder-style, not JavaBeans.</b> Mutators return {@code this} and
 * are named after the field without a {@code set} prefix: {@code model(String)}, not
 * {@code setModel(String)}. {@code mage.server.LLMPlayerHelper#setConfigField} reaches
 * these via {@code getMethod(configField, valueClass)} reflection — the lookup keys on
 * that builder name, so renaming a setter here silently breaks that call site
 * (a {@code NoSuchMethodException} at runtime, nothing at compile time). If you rename
 * any setter, audit every caller of {@code setConfigField}.
 */
public class LLMConfig implements java.io.Serializable {

    // Pinned so the transient apiKey change (the apiKey leak fix, moved here from
    // LLMPlayerConfig) doesn't invalidate existing .game replay saves.
    private static final long serialVersionUID = -134329132618624147L;

    private static final Logger logger = Logger.getLogger(LLMConfig.class);

    private String apiEndpoint = "http://localhost:11434/v1/chat/completions";
    // Transient: LLMPlayer→LLMConfig reaches the serialized
    // .game replay files via GameState→players. The API key must never be
    // persisted to disk. The key is re-attached in-memory at construction
    // time from system properties / LLMPlayerConfig overrides — never read
    // back from a replay.
    private transient String apiKey = "";
    private String model = "deepseek-v4-flash:cloud";
    private double temperature = 0.7;
    private boolean temperatureEnabled = false; // when false, omit temperature from API request so the LLM host uses its own default
    private int maxTokens = 0;  // 0 = no explicit total-output cap: when the request also carries a
                                // reasoning cap (thinking.budget_tokens), LLMClient emits
                                // max_tokens = reasoning cap × 2 to reserve output room for the
                                // ANSWER (the old omitted-max_tokens body let
                                // reasoning consume the whole window and return empty content); on
                                // a request with no reasoning cap the field stays omitted and the
                                // host decides. A positive value is authoritative and always wins.
    private int timeoutSeconds = 600; // 10 min — LLM API calls can run long with large models + deck assessment
    private String reasoningEffort = "max"; // deepseek-v4: low/medium/high/max, only when thinking enabled
    // Thinking request dialect — which wire format the endpoint speaks:
    //   "effort"  → reasoning_effort: <level>            (DeepSeek / ollama-cloud, default)
    //   "budget"  → thinking: {type:"enabled", budget_tokens:N}  (Anthropic-style)
    // The empty-answer retry ladder descends effort levels in "effort" mode —
    // and, with them, the emitted thinking.budget_tokens cap —
    // and budget sizes in "budget" mode. There is no silent translation between the
    // two — a provider that rejects the configured dialect fails fast on the
    // API error, per project policy.
    // In "effort" mode the non-standard thinking{} object is emitted ONLY for
    // Ollama-classified endpoints (see isOllamaEndpoint): strict OpenAI-compatible
    // gateways reject it as an unknown field (HTTP 400 — measured on opencode
    // zen/go 2026-09-26, glm-5.3-flash). "budget" mode always emits the object —
    // that dialect is object-based by definition.
    private String thinkingDialect = "effort";
    // Budget ladder (tokens) for dialect="budget". Highest first, same ladder
    // semantics as reasoningEffortLevels: attempt 1 uses the first entry,
    // empty-answer retries descend. Configurable via xmage.llm.thinking_budget_tokens.
    private List<Integer> thinkingBudgetTokens = new ArrayList<>(Arrays.asList(16000, 8000, 4000, 1000));
    // Hard reasoning cap for dialect="effort". The endpoint accepts
    // {type:"enabled", reasoning_effort: X, budget_tokens: N} together (verified
    // on ollama-cloud): budget_tokens bounds the reasoning stream, and LLMClient
    // now also emits max_tokens to reserve output room for the answer (the old
    // body carried the cap with max_tokens omitted, so a
    // reasoning loop burned the whole window and returned empty content: 620
    // occurrences in one run). The cap DESCENDS with the empty-answer effort
    // ladder: a retry one rung below the configured effort
    // emits a proportionally smaller budget_tokens, floored at 1024, so descent
    // genuinely reduces the reasoning allowance instead of only changing the
    // advisory reasoning_effort string. 0 = no explicit cap (the answer reserve
    // above then does not engage either — rely on max_tokens, the pre-batch-2
    // behavior). Configurable via xmage.llm.thinking_max_tokens.
    private int thinkingMaxTokens = 32768;
    // Reasoning-effort descent ladder for empty-answer loops: after the nudge
    // fails, retry at successively lower thinking levels before giving up.
    // Order matters (highest first). Effort levels not supported by the model's
    // API are the provider's problem — Fail Fast on the API error if so.
    private List<String> reasoningEffortLevels = new ArrayList<>(Arrays.asList("max", "high", "medium", "low"));
    private boolean thinkingEnabled = true;  // toggle deepseek thinking mode on/off
    private boolean verboseLogging = false;

    // Deck assessment — one-shot pre-game analysis to formulate a gameplan
    private boolean deckAssessmentEnabled = true;

    // Future: vision/image capture support
    private boolean visionEnabled = false;

    // Transcript logging — dump full LLM conversation to disk for debugging
    private String transcriptDir = null;  // null = disabled, path = dump every exchange to a .txt file

    // Streaming output — print LLM tokens to stdout as they arrive
    private boolean streamingEnabled = true;

    // Concede — allow the LLM to voluntarily concede the game
    private boolean concedeEnabled = true;

    // Ask-reason — when ON, every prompt asks for a second line "REASON: <one
    // sentence>" after the answer line. For models whose provider hides reasoning
    // text (transcript shows "(reasoning hidden by provider)"), this is the only
    // way to see anything about intent. Default OFF: the extra output line is a
    // format-compliance cost that visible-reasoning models shouldn't pay.
    // The action line parse is unchanged and decisive; the REASON line is
    // best-effort and never fails a move.
    private boolean askReason = false;

    // Personality — inject a character personality + playstyle into the system prompt
    private boolean personalityEnabled = true;
    private String personalityName = null;  // null = auto-detect from bot name; set to override

    // Bot name — override the auto-generated name (null = random/auto-assign)
    private String botName = null;  // null = random from pool; set to a registered name to force it

    // --- Conversation memory controls ---
    // All three default to infinity, expressed as 0.
    // 0 = unlimited; any positive value is a hard cap. Negative values are
    // rejected Fail-Fast by the setters below.
    private int conversationMaxExchanges = 0;     // 0 = unlimited (infinity); positive = cap on user/assistant pairs (summarization handles overflow)
    private int conversationMaxHistoryChars = 0;  // 0 = unlimited (infinity); positive = cap on total chars (summarization handles overflow)
    private int conversationThinkCharsPerMsg = 0; // 0 = unlimited (infinity) — keep full reasoning; positive = truncate thinking to N chars per message (only low-context models should truncate)

    // Summarization — when history exceeds trigger, LLM condenses it to a compact narrative
    private int summarizeTriggerChars = 0;      // trigger summarization when total history exceeds this (0 = use context ratio below)
    private double summarizeContextRatio = 0.65; // fraction of model context window at which to trigger summarization
    // Default context window (tokens). Actual value auto-detected from Ollama /api/show
    // at startup via fetchModelContextWindow(). If detection fails, an exception is thrown.
    // Override manually via xmage.llm.model_context_window.
    // Typical: deepseek-v4=1M, kimi-k2.6=131K.
    private int modelContextWindow = 131072;     // total context window in tokens (used with ratio when trigger/target are 0)
    // True when modelContextWindow was set explicitly (system property or GUI
    // override) rather than left at the 131072 default. Gates
    // fetchModelContextWindow(): non-Ollama endpoints have no detection path,
    // so they REQUIRE an explicit value — Fail Fast if it's missing.
    private boolean modelContextWindowExplicit = false;
    private int summarizeTargetChars = 0;        // target size for the condensed summary (0 = use ratio below)
    private double summarizeTargetRatio = 0.25;  // fraction of model context window for summary target (chars ≈ tokens × 2 × ratio)
    private int summarizeKeepExchanges = 5;      // number of recent user/assistant exchanges to retain post-summary

    // Prompt templates
    private String systemPromptTemplate =
            "You are an expert Magic: The Gathering player controlling a deck in an XMage game.\n"
            + "\n"
            + "Your goal is to WIN. Every decision should advance that goal.\n"
            + "\n"
            + "Each turn you will receive a text description of the game containing:\n"
            + "  NEW GAME EVENTS — only events since your last decision (incremental)\n"
            + "    (Your prior decisions and the game context are carried in conversation history.)\n"
            + "  CURRENT BOARD STATE — all permanents, hands, life totals, stack, graveyards, exile\n"
            + "  ACTIVE SKIP STATUS — whether any skip/auto-pass is currently active\n"
            + "  AVAILABLE ACTIONS — numbered list of things you can do right now\n"
            + "\n"
            + "CARD TEXT FORMAT (how card rules text appears in prompts):\n"
            + "  Each card's full rules text appears exactly ONCE per game — on its first appearance\n"
            + "  in any prompt, marked with '— FULL TEXT:'. Thereafter the card appears as a shorthand\n"
            + "  (name, cost, types, power/toughness) and the full text is NOT repeated. Read and\n"
            + "  remember the FULL TEXT when it first appears; later prompts will not show it again.\n"
            + "\n"
            + "HOW TO RESPOND:\n"
            + "  The prompt header tells you which mode you are in. Decision prompts use headers like these (among others):\n"
            + "    '=== AVAILABLE ACTIONS ===' — Priority menu: Reply with ONLY the action number (e.g., '3').\n"
            + "    - Action 0 always means 'pass this priority window'\n"
            + "    - Higher-numbered actions include playing lands, casting spells, and activating abilities\n"
            + "    - The last numbered options are skip controls, followed by CONCEDE when enabled\n"
            + "    '=== TARGET SELECTION ===' or '=== CARD SEARCH ===' — Card selection:\n"
            + "    Reply with ONLY the card number(s), comma-separated (e.g., '3' or '1,4').\n"
            + "    Other decision headers — e.g. 'MULLIGAN DECISION', 'YES/NO DECISION', 'CHOOSE ONE',\n"
            + "    'CHOOSE MODE', 'CHOOSE PILE', 'CHOOSE AMOUNT', 'CHOOSE REPLACEMENT EFFECT',\n"
            + "    'CHOOSE SPELL SIDE', 'ANNOUNCE X', 'TARGET WITH AMOUNT', 'DISTRIBUTE AMOUNT',\n"
            + "    'ORDER TRIGGERS', 'DECLARE ATTACKERS', 'DECLARE BLOCKERS', 'LIBRARY SEARCH',\n"
            + "    'LEGENDARY RULE', 'CARD NAME CHOICE' — instruct you explicitly.\n"
            + "    Whatever the prompt asks for, reply with ONLY that: the requested number(s), card\n"
            + "    name(s), declaration, or 'yes'/'no' — never reasoning or explanation.\n"
            + "SKIP CONTROLS:\n"
            + "  Skip controls are efficient ways to advance the game when you have no decisions to make.\n"
            + "  Using them saves time and lets you focus analysis on turns where you actually have plays.\n"
            + "\n"
            + "  SKIP MECHANISMS:\n"
            + "    SKIP TO YOUR NEXT TURN — Mechanism: passes priority until your next turn begins or something\n"
            + "      goes on the stack.\n"
            + "    SKIP TO END STEP BEFORE YOUR TURN — Mechanism: passes priority through step changes as well;\n"
            + "      wakes you only when something goes on the stack or the turn changes.\n"
            + "    SKIP UNTIL STACK RESOLVES — Mechanism: passes priority while spells/abilities are on the stack;\n"
            + "      cancels when the stack is empty.\n"
            + "    CANCEL ALL SKIPS — Mechanism: clears the active skip and resumes normal priority prompts.\n"
            + "\n"
            + "  IMPORTANT: Skips auto-cancel when a spell/ability goes on the stack or when a new turn begins\n"
            + "  — you'll get priority back to respond.\n"
            + "\n"
            + "  Do NOT manually pass priority (option 0) repeatedly through multiple phases.\n"
            + "  If you pass and are immediately prompted again with nothing changed, use a SKIP instead.\n"
            + "  Treat skip options as your default when you have nothing to do.\n"
            + "\n"
            + "\n"
            + "MANA MANAGEMENT — VERY IMPORTANT:\n"
            + "  Every untapped mana source is listed in the 'Untapped Mana Sources' section with an\n"
            + "  id (L1, L2, …). To cast or activate something that needs mana of specific colors, add\n"
            + "  'mana:L3,L4' to your answer — e.g. '3 mana:L1,L3' — listing the ids of the sources you\n"
            + "  want tapped, and the engine taps exactly those. (Sources is shorthand; only lands\n"
            + "  spend a land drop.) If you omit the mana plan, sources are auto-tapped; this may\n"
            + "  tap a dual land for the wrong color.\n"
            + "  Activated abilities of lands (and of other permanents) may also appear as separate\n"
            + "  'Activate:' entries in the action list — use those when you want the ability's OTHER\n"
            + "  effect (fetch, looting, crew, etc.), not to pay for another spell; mana for casting\n"
            + "  is specified with the mana: plan above.\n"
            + "  Check the Floating Mana section — if it says \"None floating\" and the spell costs mana,\n"
            + "  you need to tap lands before casting. If a cast fails, try tapping lands first and casting again.";

    public LLMConfig() {}

    /**
     * Copy constructor — deep-copies all config fields.
     * Used by LLMPlayer copy constructor to prevent tournament sub-tables
     * from sharing mutable config with the original player.
     */
    public LLMConfig(LLMConfig other) {
        this.apiEndpoint = other.apiEndpoint;
        this.apiKey = other.apiKey;
        this.useClientKey = other.useClientKey;
        this.clientKeyTag = other.clientKeyTag;
        this.model = other.model;
        this.temperature = other.temperature;
        this.temperatureEnabled = other.temperatureEnabled;
        this.maxTokens = other.maxTokens;
        this.timeoutSeconds = other.timeoutSeconds;
        this.reasoningEffort = other.reasoningEffort;
        this.thinkingDialect = other.thinkingDialect;
        this.thinkingBudgetTokens = other.thinkingBudgetTokens == null
                ? null : new ArrayList<>(other.thinkingBudgetTokens);
        this.thinkingMaxTokens = other.thinkingMaxTokens;
        this.reasoningEffortLevels = other.reasoningEffortLevels == null
                ? null : new ArrayList<>(other.reasoningEffortLevels);
        this.thinkingEnabled = other.thinkingEnabled;
        this.verboseLogging = other.verboseLogging;
        this.deckAssessmentEnabled = other.deckAssessmentEnabled;
        this.visionEnabled = other.visionEnabled;
        this.transcriptDir = other.transcriptDir;
        this.streamingEnabled = other.streamingEnabled;
        this.concedeEnabled = other.concedeEnabled;
        this.askReason = other.askReason;
        this.personalityEnabled = other.personalityEnabled;
        this.personalityName = other.personalityName;
        this.botName = other.botName;
        this.conversationMaxExchanges = other.conversationMaxExchanges;
        this.conversationMaxHistoryChars = other.conversationMaxHistoryChars;
        this.conversationThinkCharsPerMsg = other.conversationThinkCharsPerMsg;
        this.summarizeTriggerChars = other.summarizeTriggerChars;
        this.summarizeContextRatio = other.summarizeContextRatio;
        this.modelContextWindow = other.modelContextWindow;
        this.modelContextWindowExplicit = other.modelContextWindowExplicit;
        this.summarizeTargetChars = other.summarizeTargetChars;
        this.summarizeTargetRatio = other.summarizeTargetRatio;
        this.summarizeKeepExchanges = other.summarizeKeepExchanges;
        this.systemPromptTemplate = other.systemPromptTemplate;
    }

    // --- Builder-style setters ---

    public LLMConfig apiEndpoint(String v) { this.apiEndpoint = v; return this; }
    public String getApiEndpoint() { return apiEndpoint; }

    public LLMConfig apiKey(String v) { this.apiKey = v; return this; }
    public String getApiKey() { return apiKey; }

    // ── Client-key bridge ──
    // When true, LLMClient does NOT call the provider itself: it hands the
    // exact request body to the seat-owner's browser (LLMBridgeRegistry),
    // which attaches the user's API key locally and POSTs to the provider.
    // The server JVM never holds a key for this seat.
    private boolean useClientKey = false;

    public LLMConfig useClientKey(boolean v) { this.useClientKey = v; return this; }
    public boolean isUseClientKey() { return useClientKey; }

    // Browser-side key tag (client-key bridge): names which locally-stored
    // browser key this seat uses. Opaque routing metadata, not a secret.
    private String clientKeyTag = null;
    public LLMConfig clientKeyTag(String v) { this.clientKeyTag = v; return this; }
    public String getClientKeyTag() { return clientKeyTag; }

    public LLMConfig model(String v) { this.model = v; return this; }
    public String getModel() { return model; }

    public LLMConfig temperature(double v) { this.temperature = v; return this; }
    public double getTemperature() { return temperature; }

    public LLMConfig temperatureEnabled(boolean v) { this.temperatureEnabled = v; return this; }
    public boolean isTemperatureEnabled() { return temperatureEnabled; }

    public LLMConfig maxTokens(int v) { this.maxTokens = v; return this; }
    public int getMaxTokens() { return maxTokens; }

    public LLMConfig timeoutSeconds(int v) { this.timeoutSeconds = v; return this; }
    public int getTimeoutSeconds() { return timeoutSeconds; }

    public String getReasoningEffort() { return reasoningEffort; }
    public LLMConfig reasoningEffort(String v) { this.reasoningEffort = v; return this; }

    public String getThinkingDialect() { return thinkingDialect; }
    public LLMConfig thinkingDialect(String v) {
        if (!"effort".equals(v) && !"budget".equals(v)) {
            throw new IllegalArgumentException("thinkingDialect must be 'effort' or 'budget', got: '" + v + "'");
        }
        this.thinkingDialect = v; return this;
    }

    public List<Integer> getThinkingBudgetTokens() { return thinkingBudgetTokens; }
    public int getThinkingMaxTokens() { return thinkingMaxTokens; }
    public LLMConfig thinkingMaxTokens(int v) {
        if (v < 0) throw new IllegalArgumentException("thinkingMaxTokens must be >= 0 (0 = no explicit cap), got: " + v);
        this.thinkingMaxTokens = v; return this;
    }
    /**
     * Parse a comma-separated budget ladder (e.g. "16000,8000,4000,1000").
     * Highest first; positive ints only; fails fast on blank/invalid result.
     */
    public LLMConfig thinkingBudgetTokens(String csv) {
        List<Integer> budgets = new ArrayList<>();
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            int v;
            try {
                v = Integer.parseInt(t);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("thinkingBudgetTokens entry not an integer: '" + t + "'", e);
            }
            if (v <= 0) throw new IllegalArgumentException("thinkingBudgetTokens must be positive, got: " + v);
            budgets.add(v);
        }
        if (budgets.isEmpty()) {
            throw new IllegalArgumentException("thinkingBudgetTokens must contain at least one value, got: '" + csv + "'");
        }
        this.thinkingBudgetTokens = budgets;
        return this;
    }

    public List<String> getReasoningEffortLevels() { return reasoningEffortLevels; }
    public LLMConfig reasoningEffortLevels(List<String> v) { this.reasoningEffortLevels = v; return this; }

    /**
     * Parse a comma-separated effort-level list (e.g. "max,high,medium,low").
     * Trims entries, drops empties, fails fast on blank result.
     */
    public LLMConfig reasoningEffortLevels(String csv) {
        List<String> levels = new ArrayList<>();
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) levels.add(t);
        }
        if (levels.isEmpty()) {
            throw new IllegalArgumentException("reasoningEffortLevels must contain at least one level, got: '" + csv + "'");
        }
        this.reasoningEffortLevels = levels;
        return this;
    }

    public boolean isThinkingEnabled() { return thinkingEnabled; }
    public LLMConfig thinkingEnabled(boolean v) { this.thinkingEnabled = v; return this; }

    public boolean isDeckAssessmentEnabled() { return deckAssessmentEnabled; }
    public LLMConfig deckAssessmentEnabled(boolean v) { this.deckAssessmentEnabled = v; return this; }

    public LLMConfig verboseLogging(boolean v) { this.verboseLogging = v; return this; }
    public boolean isVerboseLogging() { return verboseLogging; }

    public LLMConfig visionEnabled(boolean v) { this.visionEnabled = v; return this; }
    public boolean isVisionEnabled() { return visionEnabled; }

    public LLMConfig transcriptDir(String v) { this.transcriptDir = v; return this; }
    public String getTranscriptDir() { return transcriptDir; }

    public LLMConfig streamingEnabled(boolean v) { this.streamingEnabled = v; return this; }
    public boolean isStreamingEnabled() { return streamingEnabled; }

    public LLMConfig concedeEnabled(boolean v) { this.concedeEnabled = v; return this; }
    public boolean isConcedeEnabled() { return concedeEnabled; }

    public LLMConfig askReason(boolean v) { this.askReason = v; return this; }
    public boolean isAskReason() { return askReason; }

    /**
     * The instruction appended to every decision prompt when askReason is ON.
     * The answer stays on the FIRST line (every downstream parser reads only
     * the first line — {@code LLMPlayer.firstLineOf}); the REASON line is
     * optional and a mangled/missing one never fails the move.
     */
    public static final String ASK_REASON_PROMPT_SUFFIX =
            "\nAfter your answer line, add one more line: REASON: <one sentence explaining your choice>.";

    public LLMConfig personalityEnabled(boolean v) { this.personalityEnabled = v; return this; }
    public boolean isPersonalityEnabled() { return personalityEnabled; }

    public LLMConfig personalityName(String v) { this.personalityName = v; return this; }
    public String getPersonalityName() { return personalityName; }

    public LLMConfig botName(String v) { this.botName = v; return this; }
    public String getBotName() { return botName; }

    // --- Conversation memory ---
    // 0 = unlimited (infinity) for all three limits. Negative = caller bug, rejected Fail-Fast.
    public int getConversationMaxExchanges() { return conversationMaxExchanges; }
    public LLMConfig conversationMaxExchanges(int v) {
        if (v < 0) throw new IllegalArgumentException("conversationMaxExchanges must be >= 0 (0 = unlimited), got: " + v);
        this.conversationMaxExchanges = v; return this;
    }

    public int getConversationMaxHistoryChars() { return conversationMaxHistoryChars; }
    public LLMConfig conversationMaxHistoryChars(int v) {
        if (v < 0) throw new IllegalArgumentException("conversationMaxHistoryChars must be >= 0 (0 = unlimited), got: " + v);
        this.conversationMaxHistoryChars = v; return this;
    }

    public int getConversationThinkCharsPerMsg() { return conversationThinkCharsPerMsg; }
    public LLMConfig conversationThinkCharsPerMsg(int v) {
        if (v < 0) throw new IllegalArgumentException("conversationThinkCharsPerMsg must be >= 0 (0 = unlimited), got: " + v);
        this.conversationThinkCharsPerMsg = v; return this;
    }

    public int getSummarizeTriggerChars() { return summarizeTriggerChars; }
    public LLMConfig summarizeTriggerChars(int v) { this.summarizeTriggerChars = v; return this; }

    public double getSummarizeContextRatio() { return summarizeContextRatio; }
    public LLMConfig summarizeContextRatio(double v) { this.summarizeContextRatio = v; return this; }

    public int getModelContextWindow() { return modelContextWindow; }
    public LLMConfig modelContextWindow(int v) { this.modelContextWindow = v; this.modelContextWindowExplicit = true; return this; }

    public int getSummarizeTargetChars() { return summarizeTargetChars; }
    public LLMConfig summarizeTargetChars(int v) { this.summarizeTargetChars = v; return this; }

    public double getSummarizeTargetRatio() { return summarizeTargetRatio; }
    public LLMConfig summarizeTargetRatio(double v) { this.summarizeTargetRatio = v; return this; }

    public int getSummarizeKeepExchanges() { return summarizeKeepExchanges; }
    public LLMConfig summarizeKeepExchanges(int v) {
        if (v < 0) throw new IllegalArgumentException("summarizeKeepExchanges must be >= 0, got: " + v);
        this.summarizeKeepExchanges = v; return this;
    }

    public LLMConfig systemPromptTemplate(String v) { this.systemPromptTemplate = v; return this; }
    public String getSystemPromptTemplate() { return systemPromptTemplate; }

    /**
     * Attempt to detect the model's context window.
     *
     * Ollama endpoints: POST /api/show, read model_info[<key containing
     * context_length>] — exact, per-model value.
     *
     * Other OpenAI-compatible endpoints: GET /v1/models, find the entry whose
     * id matches the configured model, read its context_length (OpenRouter,
     * Groq, Cerebras, and LM Studio all expose it there; responses without the
     * field are skipped). The chat endpoint's Bearer key is sent.
     *
     * Safe to call from any module.
     */
    public void fetchModelContextWindow() {
        String endpoint = this.apiEndpoint;
        String modelName = this.model;
        if (endpoint == null || modelName == null) {
            throw new IllegalArgumentException("apiEndpoint and model must not be null for context window detection");
        }
        String baseUrl;
        if (endpoint.endsWith("/v1/chat/completions")) {
            baseUrl = endpoint.substring(0, endpoint.length() - "/v1/chat/completions".length());
        } else if (endpoint.endsWith("/v1")) {
            baseUrl = endpoint.substring(0, endpoint.length() - "/v1".length());
        } else {
            baseUrl = endpoint.replaceAll("/v1/chat/completions$", "").replaceAll("/v1$", "");
            // Fail Fast: endpoint did not match any known chat API pattern.
            if (baseUrl.equals(endpoint)) {
                throw new IllegalArgumentException("fetchModelContextWindow: endpoint URL did not match any known chat API pattern (expected '/v1/chat/completions' or '/v1' suffix): " + endpoint);
            }
        }
        if (isOllamaEndpoint(endpoint)) {
            detectFromOllamaShow(baseUrl, modelName);
        } else {
            detectFromOpenAIModels(baseUrl, modelName);
        }
    }

    /**
     * Ollama detection heuristic, shared with the model-list fetchers:
     * "ollama" in the URL or the default port 11434. Everything else is
     * treated as an OpenAI-compatible endpoint. A remote Ollama whose URL
     * does not match (e.g. https://llm.example.com) is misclassified — set
     * the context window manually there, or point the chat endpoint at the
     * Ollama default port.
     */
    static boolean isOllamaEndpoint(String endpoint) {
        return endpoint.contains("ollama") || endpoint.contains("11434");
    }

    private void detectFromOllamaShow(String baseUrl, String modelName) {
        // Explicit-window contract (mirrors detectFromOpenAIModels): an explicitly
        // set context window SKIPS detection entirely — no network call. Without
        // this, keyless boots (no server-level endpoint; config defaults to
        // localhost:11434 with no Ollama running) threw "Connection refused"
        // at LLMPlayer.init() BEFORE per-seat overrides could apply.
        if (modelContextWindowExplicit) {
            logger.info("Context window detection skipped (Ollama endpoint '" + apiEndpoint
                    + "') — using manually set context window: " + modelContextWindow
                    + " tokens for " + modelName);
            return;
        }
        try {
            java.net.URI uri = java.net.URI.create(baseUrl + "/api/show");
            java.net.URL url = uri.toURL();
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                String requestBody = "{\"name\":\"" + modelName + "\"}";
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(requestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }

                int status = conn.getResponseCode();
                if (status >= 200 && status < 300) {
                    try (java.io.InputStream is = conn.getInputStream()) {
                        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            bos.write(buf, 0, n);
                        }
                        String responseBody = bos.toString("UTF-8");

                        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(responseBody).getAsJsonObject();
                        if (root.has("model_info")) {
                            com.google.gson.JsonObject modelInfo = root.getAsJsonObject("model_info");
                            for (String key : modelInfo.keySet()) {
                                if (key.contains("context_length")) {
                                    int contextLen = modelInfo.get(key).getAsInt();
                                    this.modelContextWindow = contextLen;
                                    logger.info("Detected context window: " + contextLen + " tokens for " + modelName);
                                    return;
                                }
                            }
                        }
                    }
                }
                throw new RuntimeException("Could not find context_length in Ollama /api/show response for model: " + modelName);
            } finally {
                conn.disconnect();
            }
        } catch (Throwable t) {
            throw new RuntimeException("Failed to detect context window from Ollama: " + t.getMessage(), t);
        }
    }

    /**
     * OpenAI-compatible context window detection: GET {base}/v1/models, match
     * the entry whose "id" equals the configured model, read its
     * "context_length". OpenRouter, Groq, Cerebras, and LM Studio expose the
     * field there; OpenAI's own /v1/models does not.
     *
     * An explicitly-set window skips detection entirely (no network call) —
     * same contract as the old non-Ollama path, pinned by
     * LLMConfigContextWindowTest. Without an explicit window, a provider that
     * omits context_length is a detection failure — Fail Fast.
     */
    private void detectFromOpenAIModels(String baseUrl, String modelName) {
        if (modelContextWindowExplicit) {
            logger.info("Context window detection skipped (non-Ollama endpoint '" + apiEndpoint
                    + "') — using manually set context window: " + modelContextWindow
                    + " tokens for " + modelName);
            return;
        }
        try {
            java.net.URI uri = java.net.URI.create(baseUrl + "/v1/models");
            java.net.URL url = uri.toURL();
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            try {
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                if (apiKey != null && !apiKey.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                }

                int status = conn.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw new RuntimeException("OpenAI-compatible /v1/models request returned HTTP " + status
                            + " for context window detection of model '" + modelName + "' at " + baseUrl
                            + " — check the endpoint URL and API key");
                }
                String responseBody;
                try (java.io.InputStream is = conn.getInputStream()) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        bos.write(buf, 0, n);
                    }
                    responseBody = bos.toString("UTF-8");
                }

                com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(responseBody).getAsJsonObject();
                com.google.gson.JsonArray data = root.has("data") ? root.getAsJsonArray("data") : null;
                if (data == null) {
                    throw new RuntimeException("/v1/models response missing 'data' array for context window detection at " + baseUrl);
                }
                for (com.google.gson.JsonElement el : data) {
                    if (!el.isJsonObject()) continue;
                    com.google.gson.JsonObject m = el.getAsJsonObject();
                    String id = m.has("id") ? m.get("id").getAsString() : null;
                    if (id == null || !id.equals(modelName)) continue;
                    // Field name varies by provider: OpenRouter/Groq/LM Studio use
                    // "context_length"; hyper.charm.land uses "context_window".
                    // Both mean the total context size in tokens.
                    Integer contextLen = null;
                    if (m.has("context_length")) contextLen = m.get("context_length").getAsInt();
                    else if (m.has("context_window")) contextLen = m.get("context_window").getAsInt();
                    if (contextLen == null) continue;
                    this.modelContextWindow = contextLen;
                    logger.info("Detected context window: " + contextLen + " tokens for " + modelName
                            + " (via /v1/models at " + baseUrl + ")");
                    return;
                }
                throw new RuntimeException("No /v1/models entry with a context size field ('context_length' "
                        + "or 'context_window') matching model '" + modelName + "' (providers like OpenAI "
                        + "omit the field — detection is only possible on providers that expose it, "
                        + "e.g. OpenRouter/Groq/LM Studio)");
            } finally {
                conn.disconnect();
            }
        } catch (Throwable t) {
            throw new RuntimeException("Failed to detect context window from OpenAI-compatible endpoint '"
                    + this.apiEndpoint + "' (tried GET " + baseUrl + "/v1/models): " + t.getMessage()
                    + ". Set modelContextWindow manually for this model (xmage.llm.model_context_window "
                    + "or the GUI LLM config).", t);
        }
    }
}
