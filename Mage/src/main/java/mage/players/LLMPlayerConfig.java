package mage.players;

import java.io.Serializable;

/**
 * Per-player LLM configuration overrides sent from the client's New Table dialog
 * to the server. Applied to LLMPlayer instances before they start playing.
 * <p>
 * null-valued fields mean "use the server default" — they won't override
 * whatever the LLMConfig was already set to from start-server.sh / config.xml.
 */
public class LLMPlayerConfig implements Serializable {

    // Pinned to the pre-2026-09-04 shape (apiKey non-transient in this class at
    // the time old replays were written) so existing .game saves stay loadable.
    // History: apiKey was briefly marked transient to keep keys
    // out of .game replay files — but this class ALSO travels client→server via
    // the same Java serialization on the JBoss remoting RPC, so the GUI dialog's
    // key was silently dropped and every authenticated cloud endpoint returned
    // 401. The replay-file leak is now blocked one level down instead: the
    // runtime LLMConfig.apiKey (what GameState→LLMPlayer actually holds) is
    // transient, so keys never serialize into .game files from either route.
    private static final long serialVersionUID = 6112746117441871205L;

    // Core LLM settings
    private String model = null;            // null = use server default
    private String apiEndpoint = null;
    // NOT transient — must survive client→server serialization (see suid note).
    // Excluded from .game replay files via the runtime LLMConfig being transient.
    private String apiKey = null;
    // Client-key bridge: true = the browser owning
    // the table makes the provider calls with the user's key (never sent to
    // the server); false = server-side key path unchanged. null = server default.
    private Boolean useClientKey = null;
    // Browser-generated opaque tag naming which locally-stored key this seat
    // uses (the browser knows seat UUIDs only at prompt time, so it tags keys
    // at setup time and the tag rides to the prompt payload). Not a secret.
    private String clientKeyTag = null;
    private String reasoningEffort = null;  // null = use default; "low"/"medium"/"high"/"max"
    // Reasoning-effort descent ladder for empty-answer retries. Null/empty = use
    // server default (LLMConfig: max,high,medium,low). Order matters, highest first.
    private java.util.List<String> reasoningEffortLevels = null;
    private Boolean thinkingEnabled = null; // null = use default
    private Double temperature = null;
    private Boolean temperatureEnabled = null; // null = use server default; when false, omit temperature from API request
    private Integer maxTokens = null;       // max output tokens (NOT context window)
    private Integer timeoutSeconds = null;

    // Streaming / verbosity
    private Boolean streamingEnabled = null;
    private Boolean verboseLogging = null;

    // Deck assessment
    private Boolean deckAssessmentEnabled = null;

    // Transcript logging
    private String transcriptDir = null;

    // Concede
    private Boolean concedeEnabled = null;

    // Ask-reason — per-seat override; null = inherit server default (off unless
    // xmage.llm[2].ask_reason is set). When on, every decision prompt asks for a
    // "REASON: <one sentence>" line after the answer line. For hidden-reasoning
    // models this is the only intent signal in the transcript.
    private Boolean askReason = null;

    // Personality
    private Boolean personalityEnabled = null;  // null = use server default
    private String personalityName = null;      // null = auto-detect from bot name

    // Bot name override
    private String botName = null;              // null = random/auto-assign

    // Conversation memory
    private Integer conversationMaxExchanges = null;
    private Integer conversationMaxHistoryChars = null;
    private Integer conversationThinkCharsPerMsg = null;

    // Summarization
    private Integer summarizeTriggerChars = null;
    private Double summarizeContextRatio = null;
    private Integer modelContextWindow = null;  // context window in tokens (NOT max output tokens)
    private Integer summarizeTargetChars = null;
    private Double summarizeTargetRatio = null;
    private Integer summarizeKeepExchanges = null;

    // ---- Pending seat-config delivery (creation-time overrides) ----
    //
    // The web/desktop New-Table dialogs send a per-seat LLMPlayerConfig with the
    // seat. TableController.joinTable used to apply it via LLMPlayer.applyOverrides()
    // AFTER PlayerFactory.createPlayer() — but LLMPlayer.init() runs at CONSTRUCTION
    // time inside createPlayer() and dials the configured endpoint there (context-
    // window auto-detection, plus the thinking probe). With a dead/unreachable
    // SERVER-DEFAULT endpoint, construction failed before the seat's own endpoint
    // was ever applied: "Could not create player ... of type Computer - LLM"

    //
    // The factory now delivers the seat config through this holder and LLMPlayer
    // consumes it inside init() BEFORE the detection/probe, so those dial the
    // seat's endpoint/model. ThreadLocal because createPlayer() runs on the
    // calling thread's stack (set → construct → clear in finally). Static state
    // on this class does not affect its serialization shape (instance fields only).
    private static final ThreadLocal<LLMPlayerConfig> PENDING_PLAYER_CONFIG = new ThreadLocal<>();

    /** Publish the seat config the NEXT PlayerFactory.createPlayer() on this thread must apply at construction time. */
    public static void setPendingPlayerConfig(LLMPlayerConfig config) {
        PENDING_PLAYER_CONFIG.set(config);
    }

    /** Consume (read-and-clear) the pending seat config; null when none was published. */
    public static LLMPlayerConfig consumePendingPlayerConfig() {
        LLMPlayerConfig c = PENDING_PLAYER_CONFIG.get();
        if (c != null) {
            PENDING_PLAYER_CONFIG.remove();
        }
        return c;
    }

    public LLMPlayerConfig() {}

    /** Returns a config pre-populated with the same defaults as LLMConfig.java
     *  so the dialog shows current server values on first open.
     *  modelContextWindow is left null — the server auto-detects from Ollama. */
    public static LLMPlayerConfig defaults() {
        LLMPlayerConfig c = new LLMPlayerConfig();
        c.model = "deepseek-v4-flash:cloud";
        c.apiEndpoint = "http://localhost:11434/v1/chat/completions";
        c.reasoningEffort = "max";
        c.thinkingEnabled = true;
        c.temperature = 0.7;
        c.temperatureEnabled = false; // default: use model's temperature, don't override
        c.maxTokens = 0; // 0 = omit max_tokens from API request (let API/host decide)
        c.timeoutSeconds = 600;
        c.streamingEnabled = true;
        c.verboseLogging = false;
        c.deckAssessmentEnabled = true;
        c.concedeEnabled = true;
        c.personalityEnabled = true;
        // personalityName: null — auto-detect from bot name
        // Conversation limits — 0 = unlimited (infinity), mirrors LLMConfig
        // defaults. 0 is a value the dialog spinner can
        // represent, so opening the GUI no longer flips the server value.
        c.conversationMaxExchanges = 0;
        c.conversationMaxHistoryChars = 0;
        c.conversationThinkCharsPerMsg = 0;
        c.summarizeTriggerChars = 0;
        c.summarizeContextRatio = 0.65;
        // modelContextWindow: null — let server auto-detect from Ollama
        c.summarizeTargetChars = 0;
        c.summarizeTargetRatio = 0.25;
        c.summarizeKeepExchanges = 5;
        return c;
    }

    // --- Getters / Setters ---

    public String getModel() { return model; }
    public void setModel(String v) { this.model = v; }

    public String getApiEndpoint() { return apiEndpoint; }
    public void setApiEndpoint(String v) { this.apiEndpoint = v; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { this.apiKey = v; }
    public Boolean getUseClientKey() { return useClientKey; }
    public void setUseClientKey(Boolean v) { this.useClientKey = v; }
    public String getClientKeyTag() { return clientKeyTag; }
    public void setClientKeyTag(String v) { this.clientKeyTag = v; }

    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String v) { this.reasoningEffort = v; }

    public java.util.List<String> getReasoningEffortLevels() { return reasoningEffortLevels; }
    public void setReasoningEffortLevels(java.util.List<String> v) { this.reasoningEffortLevels = v; }

    public Boolean isThinkingEnabled() { return thinkingEnabled; }
    public void setThinkingEnabled(Boolean v) { this.thinkingEnabled = v; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double v) { this.temperature = v; }

    public Boolean getTemperatureEnabled() { return temperatureEnabled; }
    public void setTemperatureEnabled(Boolean v) { this.temperatureEnabled = v; }

    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer v) { this.maxTokens = v; }

    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(Integer v) { this.timeoutSeconds = v; }

    public Boolean isStreamingEnabled() { return streamingEnabled; }
    public void setStreamingEnabled(Boolean v) { this.streamingEnabled = v; }

    public Boolean isVerboseLogging() { return verboseLogging; }
    public void setVerboseLogging(Boolean v) { this.verboseLogging = v; }

    public Boolean isDeckAssessmentEnabled() { return deckAssessmentEnabled; }
    public void setDeckAssessmentEnabled(Boolean v) { this.deckAssessmentEnabled = v; }

    public String getTranscriptDir() { return transcriptDir; }
    public void setTranscriptDir(String v) { this.transcriptDir = v; }

    public Boolean isConcedeEnabled() { return concedeEnabled; }
    public void setConcedeEnabled(Boolean v) { this.concedeEnabled = v; }

    public Boolean isAskReason() { return askReason; }
    public void setAskReason(Boolean v) { this.askReason = v; }

    public Boolean isPersonalityEnabled() { return personalityEnabled; }
    public void setPersonalityEnabled(Boolean v) { this.personalityEnabled = v; }

    public String getPersonalityName() { return personalityName; }
    public void setPersonalityName(String v) { this.personalityName = v; }

    public String getBotName() { return botName; }
    public void setBotName(String v) { this.botName = v; }

    public Integer getConversationMaxExchanges() { return conversationMaxExchanges; }
    public void setConversationMaxExchanges(Integer v) { this.conversationMaxExchanges = v; }

    public Integer getConversationMaxHistoryChars() { return conversationMaxHistoryChars; }
    public void setConversationMaxHistoryChars(Integer v) { this.conversationMaxHistoryChars = v; }

    public Integer getConversationThinkCharsPerMsg() { return conversationThinkCharsPerMsg; }
    public void setConversationThinkCharsPerMsg(Integer v) { this.conversationThinkCharsPerMsg = v; }

    public Integer getSummarizeTriggerChars() { return summarizeTriggerChars; }
    public void setSummarizeTriggerChars(Integer v) { this.summarizeTriggerChars = v; }

    public Double getSummarizeContextRatio() { return summarizeContextRatio; }
    public void setSummarizeContextRatio(Double v) { this.summarizeContextRatio = v; }

    public Integer getModelContextWindow() { return modelContextWindow; }
    public void setModelContextWindow(Integer v) { this.modelContextWindow = v; }

    public Integer getSummarizeTargetChars() { return summarizeTargetChars; }
    public void setSummarizeTargetChars(Integer v) { this.summarizeTargetChars = v; }

    public Double getSummarizeTargetRatio() { return summarizeTargetRatio; }
    public void setSummarizeTargetRatio(Double v) { this.summarizeTargetRatio = v; }

    public Integer getSummarizeKeepExchanges() { return summarizeKeepExchanges; }
    public void setSummarizeKeepExchanges(Integer v) { this.summarizeKeepExchanges = v; }
}
