package mage.player.llm;

import com.google.gson.*;
import org.apache.log4j.Logger;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/**
 * Client for communicating with LLM APIs (OpenAI-compatible).
 * Returns both reasoning/thinking content and final answer for verbose logging.
 * Designed with extension points for future vision/image support.
 */
public class LLMClient {

    private static final Logger logger = Logger.getLogger(LLMClient.class);
    private static final Object STREAM_LOCK = new Object();

    // Floor for a descended reasoning cap (thinking budget_tokens). Anthropic's
    // native API requires budget_tokens >= 1024; the same floor keeps a descended
    // cap sane wherever the thinking object is accepted.
    private static final int MIN_REASONING_BUDGET_TOKENS = 1024;

    // When the total output cap (max_tokens) is not configured but a reasoning
    // cap rides on the request, the client emits max_tokens = cap * this factor so
    // the answer is reserved at least as much output room as the reasoning stream
    // is allowed. 620 empty answers came from request bodies that
    // carried a 32768 reasoning budget with max_tokens omitted — nothing reserved
    // room for the answer, so a reasoning blowout returned empty content. See
    // LLMConfig.maxTokens / thinkingMaxTokens.
    private static final int ANSWER_RESERVE_MULTIPLIER = 2;

    private final LLMConfig config;
    private PrintWriter transcriptWriter;
    private int exchangeCount = 0;
    private volatile Path transcriptGameDir = null;  // stored for post-game summary placement

    // Transcript ownership handshake for the ASYNC post-game reflection task
    // The game thread hands the game-N
    // transcript to the reflection task, which closes it when done — but ONLY
    // if no initTranscript()/closeTranscript() (sideboarding, next game) rotated
    // it meanwhile. transcriptToken is bumped on every rotation; a stale token
    // makes closeTranscriptIfCurrent a no-op and suppresses late transcript
    // writes, so a hung reflection can never garble the sideboard/next-game
    // transcript. All token checks and writer swaps happen under transcriptLock
    // (atomic vs. rotation).
    private final Object transcriptLock = new Object();
    private volatile long transcriptToken = 0L;

    // Thread-scoped transcript generation, declared by the async post-game
    // reflection task for the duration of its chat calls (see writeTranscript).
    private final ThreadLocal<Long> expectedTranscriptGeneration = new ThreadLocal<>();

    // Transcript system-prompt dedup (minor consistency item): the system
    // prompt is a byte-identical long-lived prefix across exchanges; keep the
    // last one written so repeats collapse to a marker line. Reset per game.
    private static final java.util.regex.Pattern TRANSCRIPT_HISTORY_MARKER =
            java.util.regex.Pattern.compile("\\n\\n\\(conversation history, \\d+ msgs\\)$");
    private String lastTranscriptSystemPrompt = null;
    private int lastTranscriptSystemPromptExchange = 0;

    // ── Client-key bridge context ──
    // Set from LLMPlayer.useDeck() — the live game/player ids the payload
    // needs for server-side routing (gameId → table owner's browser).
    private volatile String bridgeGameId;
    private volatile String bridgeSeatId;
    private volatile String bridgePlayerName;
    // Opaque (game, seat) route token pinned server-side while the game was
    // still registered, held from pin time until the post-game task releases it

    private volatile String pinnedReflectionRouteToken;
    private volatile String pinnedReflectionRouteGameId;   // the (game, seat) the pin was taken for
    private volatile String pinnedReflectionRouteSeatId;
    // The pinned token, scoped to the POST-GAME REFLECTION THREAD ONLY. The
    // reflection task runs on the post-game executor and sets this for the
    // duration of its calls; every other thread (game decisions, sideboard,
    // revised strategy) sees null and uses the live lookup. ThreadLocal — not a
    // field — precisely because those in-game calls run on the SAME client for
    // the SAME game while the pin is still live: a field would put the token on
    // the sideboard call, which then consumes the pin the reflection needs

    private final ThreadLocal<String> reflectionRouteToken = new ThreadLocal<>();

    public void setBridgeContext(String gameId, String seatId, String playerName) {
        this.bridgeGameId = gameId;
        this.bridgeSeatId = seatId;
        this.bridgePlayerName = playerName;
        // A pin belongs to ONE (game, seat). A DIFFERENT game here means any pin
        // still held is stale — drop it. The sideboard / revised-strategy calls
        // re-set the SAME (game, seat) for the game whose reflection is still
        // queued, and must NOT drop that reflection's pin.
        if (pinnedReflectionRouteToken != null
                && !(String.valueOf(gameId).equals(pinnedReflectionRouteGameId)
                        && String.valueOf(seatId).equals(pinnedReflectionRouteSeatId))) {
            releaseReflectionRoute();
        }
    }

    /**
     * Pin this (game, seat)'s bridge route server-side for the post-game
     * reflection. Must be called on the game thread while the game is still
     * registered (the pin resolves gameId → tableId → owner → session live).
     * Null result = no pin possible; the reflection then fails exactly as
     * pre-fix. Keyed by (game, seat) because both seats of one game pin the same
     * gameId and must not overwrite each other.
     */
    public void pinBridgeRouteForReflection() {
        if (bridgeGameId == null || bridgeSeatId == null) {
            return;
        }
        try {
            this.pinnedReflectionRouteToken = mage.interfaces.LLMBridgeRegistry.pinRoute(
                    java.util.UUID.fromString(bridgeGameId),
                    java.util.UUID.fromString(bridgeSeatId));
            if (pinnedReflectionRouteToken != null) {
                this.pinnedReflectionRouteGameId = bridgeGameId;
                this.pinnedReflectionRouteSeatId = bridgeSeatId;
            }
        } catch (RuntimeException e) {
            // Never propagate into the game-end path — loud log, no pin.
            logger.warn("LLM bridge route pin failed for " + bridgePlayerName
                    + " (game " + bridgeGameId + ", seat " + bridgeSeatId + ") — post-game"
                    + " reflection will use the live lookup and fail if the table is gone: " + e);
            this.pinnedReflectionRouteToken = null;
        }
    }

    /**
     * Scope the pinned route token to THIS thread — the post-game reflection
     * worker and nothing else. Called by LLMPlayer.runPostGameReflection before
     * the reflection calls; paired with {@link #endPinnedReflectionRoute()}.
     */
    public void beginPinnedReflectionRoute() {
        reflectionRouteToken.set(pinnedReflectionRouteToken);
    }

    /** Drop the thread-scoped token (the pin itself is released separately). */
    public void endPinnedReflectionRoute() {
        reflectionRouteToken.remove();
    }

    /**
     * Test hook: the (game, seat) bridge token THIS thread would put on a
     * payload. Null on every thread except the post-game reflection worker
     * between beginPinnedReflectionRoute() and endPinnedReflectionRoute().
     */
    public String reflectionRouteTokenForTest() {
        return reflectionRouteToken.get();
    }

    /**
     * Release the (game, seat) pin and forget its token — called from the
     * post-game task's finally. The pin must outlive teardown but must NOT
     * outlive the reflection task that is its only user; this is what bounds the
     * transport's pin store. Uses the ids captured at pin time, never the live
     * bridge context (sideboarding/next game re-point that at another game).
     */
    public void releaseReflectionRoute() {
        reflectionRouteToken.remove();
        String gameId = pinnedReflectionRouteGameId;
        String seatId = pinnedReflectionRouteSeatId;
        this.pinnedReflectionRouteToken = null;
        this.pinnedReflectionRouteGameId = null;
        this.pinnedReflectionRouteSeatId = null;
        if (gameId == null || seatId == null) {
            return;
        }
        try {
            mage.interfaces.LLMBridgeRegistry.releaseRoute(
                    java.util.UUID.fromString(gameId), java.util.UUID.fromString(seatId));
        } catch (RuntimeException e) {
            logger.warn("LLM bridge route release failed for " + bridgePlayerName
                    + " (game " + gameId + ", seat " + seatId + "): " + e);
        }
    }

    private final List<ConversationMessage> conversationHistory = new ArrayList<>();
    private String conversationSystemPrompt = null;
    private String summarizedHistory = null;  // compact narrative of summarized game history

    // Streaming state — volatile for safe cross-thread reads by watcher poll
    private volatile boolean streamingInProgress = false;
    private volatile String streamingReasoning = null;
    private volatile String streamingContent = null;
    private volatile long streamingStartedAt = 0;

    /** Copy debug state from another client (used after game.copy()).
     *  Propagates conversation history AND streaming state so watcher polls
     *  and player update calls always see the full LLM conversation. */
    public void copyDebugState(LLMClient other) {
        // Conversation history — defensive copy to avoid concurrent modification
        for (ConversationMessage msg : other.getConversationHistory()) {
            this.conversationHistory.add(new ConversationMessage(msg.role, msg.content, msg.condensed,
                    msg.promptType, msg.turn, msg.phaseStep));
        }
        this.conversationSystemPrompt = other.conversationSystemPrompt;

        // Streaming state
        this.streamingInProgress = other.streamingInProgress;
        this.streamingReasoning = other.streamingReasoning;
        this.streamingContent = other.streamingContent;
        this.streamingStartedAt = other.streamingStartedAt;
    }

    public boolean isStreamingInProgress() { return streamingInProgress; }
    public String getStreamingReasoning() { return streamingReasoning; }
    public String getStreamingContent() { return streamingContent; }
    public long getStreamingStartedAt() { return streamingStartedAt; }

    public static class ConversationMessage {
        public final String role;
        public final String content;
        public final String condensed;

        // Per-exchange metadata (null on system messages and format-correction exchanges).
        // Set on user messages that represent game-state snapshots.
        public String promptType;     // "priority", "attackers", "blockers", "target", etc.
        public Integer turn;          // game turn number at time of prompt
        public String phaseStep;      // phase/step description at time of prompt

        public ConversationMessage(String role, String content) {
            this.role = role;
            this.content = content;
            this.condensed = null;
        }

        public ConversationMessage(String role, String content, String condensed) {
            this.role = role;
            this.content = content;
            this.condensed = condensed;
        }

        public ConversationMessage(String role, String content, String condensed,
                                   String promptType, Integer turn, String phaseStep) {
            this.role = role;
            this.content = content;
            this.condensed = condensed;
            this.promptType = promptType;
            this.turn = turn;
            this.phaseStep = phaseStep;
        }

        public String effectiveContent() {
            return condensed != null ? condensed : content;
        }

        public int estimatedChars() {
            return effectiveContent().length();
        }
    }

    /** Test-only: seed the conversation system prompt without a running game. */
    public void resetConversationForTest(String systemPrompt) {
        this.conversationSystemPrompt = systemPrompt;
    }

    public LLMClient(LLMConfig config) {
        this.config = config;
        // Transcript init deferred to initTranscript() — called from LLMPlayer.useDeck()
        // when game ID and player name are available.
    }

    /**
     * Initialize the transcript writer now that we know the game and player.
     * Called from LLMPlayer.useDeck() after the game has started.
     * Creates a fresh file for each game in the match.
     */
    public void initTranscript(String gameId, String playerName) {
        // Close existing writer from previous game + rotate the generation so any
        // in-flight async writer (post-game reflection) is invalidated atomically
        // with the close.
        synchronized (transcriptLock) {
            transcriptToken++;
            closeTranscriptLocked();
        }
        exchangeCount = 0;
        lastTranscriptSystemPrompt = null;  // new game = new system prompt identity
        String dir = config.getTranscriptDir();
        if (dir == null || dir.isEmpty()) return;
        try {
            // Dir layout: {transcriptDir}/game_{gameId8}/{playerName}_{model}_transcript.txt
            String gameIdShort = gameId.length() > 8 ? gameId.substring(gameId.length() - 8) : gameId;
            String modelShort = config.getModel().replaceAll("[:/].*", "");
            Path gameDir = Paths.get(dir, "game_" + gameIdShort);
            Files.createDirectories(gameDir);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String filename = playerName + "_" + modelShort + "_transcript-" + ts + ".txt";
            Path file = gameDir.resolve(filename);
            // Disambiguate if a file with this exact name already exists (e.g.
            // two games started in the same second for the same player/model).
            // Append a counter suffix to avoid merging transcripts in append mode.
            int suffix = 1;
            while (Files.exists(file)) {
                filename = playerName + "_" + modelShort + "_transcript-" + ts + "-" + suffix + ".txt";
                file = gameDir.resolve(filename);
                suffix++;
            }
            transcriptWriter = new PrintWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(file.toFile(), true), StandardCharsets.UTF_8));
            transcriptWriter.println("=== LLM TRANSCRIPT ===");
            transcriptWriter.println("Game: " + gameId);
            transcriptWriter.println("Player: " + playerName);
            transcriptWriter.println("Model: " + config.getModel());
            transcriptWriter.println("Endpoint: " + config.getApiEndpoint());
            transcriptWriter.println("Started: " + LocalDateTime.now());
            transcriptWriter.println();
            transcriptWriter.flush();
            transcriptGameDir = gameDir;  // store for post-game summary
            logger.info("LLM transcript (" + playerName + ") writing to: " + file);
        } catch (Exception e) {
            // Fail Fast: transcript init failure must not continue with a null
            // writer — every subsequent writeTranscript call would silently fail.
            throw new RuntimeException("Failed to initialize transcript for gameId=" + gameId + " player=" + playerName + ": " + e.getMessage(), e);
        }
    }

    /**
     * Send a simple chat completion with NO side effects: no transcript write,
     * no conversation state. Used by the startup probe — writeTranscript before
     * initTranscript (useDeck) would throw, and the probe runs during init.
     */
    public LLMChatResult chatNoSideEffects(String systemPrompt, String userPrompt) {
        try {
            String requestBody = buildRequestBody(systemPrompt, userPrompt, false);
            String responseJson = sendRequest(requestBody);
            return new LLMChatResult(parseResponse(responseJson));
        } catch (Throwable t) {
            throw new RuntimeException("LLM API call failed: " + t.getMessage(), t);
        }
    }

    /** Holder for side-effect-free chat (probe) calls. */
    public static final class LLMChatResult {
        public final LLMResponse response;
        public LLMChatResult(LLMResponse response) { this.response = response; }
    }

    /**
     * Send a simple chat completion (no conversation history).
     */
    public LLMResponse chat(String systemPrompt, String userPrompt) {
        boolean useStream = shouldStream();
        try {
            String requestBody = buildRequestBody(systemPrompt, userPrompt, useStream);
            LLMResponse resp;
            if (useStream) {
                resp = sendStreamingRequest(requestBody);
            } else {
                String responseJson = sendRequest(requestBody);
                resp = parseResponse(responseJson);
            }
            writeTranscript(systemPrompt, userPrompt, resp);
            return resp;
        } catch (Throwable t) {
            logger.error("LLM API call failed: " + t.getMessage(), t);
            writeTranscript(systemPrompt, userPrompt, null);
            throw new RuntimeException("LLM API call failed: " + t.getMessage(), t);
        }
    }

    // ==================================================================
    //  CONVERSATION MEMORY
    // ==================================================================

    public void resetConversation(String systemPrompt) {
        conversationHistory.clear();
        // PROMPT-CACHE STABILITY CONTRACT: this system prompt is the long-lived
        // prefix that provider-side prompt caching (OpenRouter, Anthropic,
        // ollama-cloud) can key on. It must be byte-identical across calls until
        // an explicit mutation point (gameplan injection, revised strategy) —
        // anything dynamic (timestamps, live game state, attempt counters)
        // appended here silently breaks the cache and multiplies prompt cost.
        // Dynamic content belongs in the user message, never in this prefix.
        this.conversationSystemPrompt = systemPrompt;
        this.summarizedHistory = null;  // reset on new game
        if (config.isVerboseLogging())
            logger.info("LLM conversation reset (system prompt " + systemPrompt.length() + " chars)");
    }

    /**
     * Send a message in the ongoing conversation.
     * <p>
     * The current request contains: system prompt + summarized history (if any) +
     * recent conversation exchanges + the FULL current prompt.
     * The compact summary for THIS turn is stored AFTER the API call returns
     * — it's a historical record for future calls, not part of the current
     * decision. This avoids confusing the LLM with two consecutive user
     * messages describing the same situation.
     *
     * @param fullPrompt      full game state + actions (sent as current user message)
     * @param compactSummary  short ~200-400 char snapshot (stored for future calls)
     * @return LLMResponse with thinking and answer, or null on failure
     */
    public LLMResponse chatConversation(String fullPrompt, String compactSummary) {
        return chatConversation(fullPrompt, compactSummary, null, null, null);
    }

    /**
     * Conversation-mode chat with per-exchange metadata for the debug viewer.
     * The metadata (promptType, turn, phaseStep) is attached to the user message
     * stored in conversation history so the web UI can show what decision the LLM
     * was making for each exchange.
     */
    public LLMResponse chatConversation(String fullPrompt, String compactSummary,
                                         String promptType, Integer turn, String phaseStep) {
        return chatConversation(fullPrompt, compactSummary, promptType, turn, phaseStep, null);
    }

    /**
     * Conversation-mode chat with an optional per-call reasoning-effort override.
     * Used by the empty-answer retry ladder: lower the thinking effort on later
     * attempts without mutating config (which would silently persist the lower
     * effort to all future decisions).
     *
     * @param effortOverride  reasoning effort for THIS call only; null = use
     *                        config.getReasoningEffort(). Only meaningful when
     *                        thinking is enabled.
     */
    public LLMResponse chatConversation(String fullPrompt, String compactSummary,
                                         String promptType, Integer turn, String phaseStep,
                                         String effortOverride) {
        if (conversationSystemPrompt == null) {
            throw new IllegalStateException("chatConversation called before resetConversation");
        }

        // Summarize if needed before building request (shrinks ctx to avoid overflow)
        summarizeIfNeeded();

        boolean useStream = shouldStream();
        try {
            String requestBody = buildRequestBodyConv(useStream, fullPrompt, effortOverride);
            logger.info("LLM request body: " + requestBody.length() + " chars");
            LLMResponse resp;
            if (useStream) {
                resp = sendStreamingRequest(requestBody);
            } else {
                String responseJson = sendRequest(requestBody);
                resp = parseResponse(responseJson);
            }
            resp = attachStatedReason(resp);

            // NOW store the compact summary + assistant response
            // These become part of the conversation prefix for the next call.
            // Skip storage on empty answers — a failed exchange (reasoning loop,
            // token budget exhausted) must not pollute conversation history or
            // the retry will send the bloated reasoning back, causing a death spiral.
            if (resp != null && resp.answer != null && !resp.answer.trim().isEmpty()) {
                conversationHistory.add(new ConversationMessage("user", compactSummary, null,
                        promptType, turn, phaseStep));
                String assistantContent = buildAssistantMessage(resp);
                conversationHistory.add(new ConversationMessage("assistant", assistantContent));
            }

            writeTranscript(conversationSystemPrompt != null
                    ? conversationSystemPrompt + "\n\n(conversation history, " + conversationHistory.size() + " msgs)"
                    : "(conversation history, " + conversationHistory.size() + " msgs)",
                    fullPrompt, resp);
            return resp;
        } catch (Throwable t) {
            logger.error("LLM conversation API call failed: " + t.getMessage(), t);
            writeTranscript("(conversation)", fullPrompt, null);
            throw new RuntimeException("LLM conversation API call failed: " + t.getMessage(), t);
        }
    }

    /**
     * Conversation-mode chat with thinking DISABLED for this call only.
     * <p>
     * The empty-answer retry ladder's final rung: once every reasoning-effort
     * level has returned an empty answer (the model burned its whole output
     * budget on thinking), the only remaining lever is to stop asking for
     * reasoning altogether so the answer stream gets the full {@code max_tokens}
     * budget. The built body carries NO {@code thinking} object and NO
     * {@code reasoning_effort}; {@code max_tokens} is preserved (authoritative
     * when configured — 65536 in production).
     * <p>
     * Behaviourally identical to {@link #chatConversation} otherwise: the same
     * conversation history, transcript write and stated-reason attachment, and
     * the same store-on-non-empty-answer rule.
     *
     * @param effortOverride  accepted for signature symmetry with
     *                        {@link #chatConversation}; ignored while thinking is
     *                        disabled (no reasoning_effort is emitted).
     */
    public LLMResponse chatConversationNoThinking(String fullPrompt, String compactSummary,
                                                  String promptType, Integer turn, String phaseStep,
                                                  String effortOverride) {
        if (conversationSystemPrompt == null) {
            throw new IllegalStateException("chatConversation called before resetConversation");
        }

        // Summarize if needed before building request (shrinks ctx to avoid overflow)
        summarizeIfNeeded();

        boolean useStream = shouldStream();
        try {
            String requestBody = buildRequestBodyConv(useStream, fullPrompt, effortOverride, null, true);
            logger.info("LLM request body: " + requestBody.length() + " chars [thinking disabled — final attempt]");
            LLMResponse resp;
            if (useStream) {
                resp = sendStreamingRequest(requestBody);
            } else {
                String responseJson = sendRequest(requestBody);
                resp = parseResponse(responseJson);
            }
            resp = attachStatedReason(resp);

            // Same store rule as chatConversation: never record a failed
            // (empty-answer) exchange into history.
            if (resp != null && resp.answer != null && !resp.answer.trim().isEmpty()) {
                conversationHistory.add(new ConversationMessage("user", compactSummary, null,
                        promptType, turn, phaseStep));
                conversationHistory.add(new ConversationMessage("assistant", buildAssistantMessage(resp)));
            }

            writeTranscript(conversationSystemPrompt != null
                    ? conversationSystemPrompt + "\n\n(conversation history, " + conversationHistory.size() + " msgs)"
                    : "(conversation history, " + conversationHistory.size() + " msgs)",
                    fullPrompt, resp);
            return resp;
        } catch (Throwable t) {
            logger.error("LLM conversation API call failed: " + t.getMessage(), t);
            writeTranscript("(conversation)", fullPrompt, null);
            throw new RuntimeException("LLM conversation API call failed: " + t.getMessage(), t);
        }
    }

    /**
     * Resolve the effective trigger: if summarizeTriggerChars is explicitly set (>0), use it.
     * Otherwise compute from modelContextWindow × summarizeContextRatio.
     */
    private int getEffectiveTriggerChars() {
        int triggerChars = config.getSummarizeTriggerChars();
        if (triggerChars > 0) return triggerChars;
        return (int) (config.getModelContextWindow() * config.getSummarizeContextRatio());
    }

    /**
     * Resolve the effective target summary size: if summarizeTargetChars is
     * explicitly set (>0), use it. Otherwise compute from context window:
     * tokens × 2 ≈ chars, then × summarizeTargetRatio.
     */
    private int getEffectiveTargetChars() {
        int targetChars = config.getSummarizeTargetChars();
        if (targetChars > 0) return targetChars;
        // Rough: tokens × 2 for chars, then apply ratio
        return (int) (config.getModelContextWindow() * 2 * config.getSummarizeTargetRatio());
    }

    /**
     * If the conversation history exceeds the configured trigger size, ask the LLM
     * to condense it into a compact narrative. The summarized history replaces the
     * raw messages and future calls include it as a system-level context prefix.
     * This prevents context window overflows in long games.
     */
    private void summarizeIfNeeded() {
        int triggerChars = getEffectiveTriggerChars();
        if (triggerChars <= 0) return;  // disabled

        int totalChars = conversationHistory.stream().mapToInt(ConversationMessage::estimatedChars).sum();
        int totalWithCurrent = totalChars + (summarizedHistory != null ? summarizedHistory.length() : 0);
        // Size-based trigger is OFF when the exchange limit is unlimited:
        // 0 = infinity; near-MAX_VALUE = legacy
        // unlimited (the *2 would overflow). With a positive cap, summarize when
        // either the chars exceed the trigger or the count exceeds the cap.
        int maxEx = config.getConversationMaxExchanges();
        boolean sizeUnlimited = maxEx == 0 || maxEx > Integer.MAX_VALUE / 2;
        if (totalWithCurrent <= triggerChars && (sizeUnlimited || conversationHistory.size() <= maxEx * 2)) {
            return;  // not triggered yet
        }

        // Keep the last N exchanges (configurable), summarize the rest
        int keepCount = config.getSummarizeKeepExchanges() * 2;  // each exchange = user + assistant msg
        if (conversationHistory.size() <= keepCount + 2) return;  // too small to summarize

        List<ConversationMessage> toSummarize = new ArrayList<>(conversationHistory.subList(0, conversationHistory.size() - keepCount));
        List<ConversationMessage> recent = new ArrayList<>(conversationHistory.subList(conversationHistory.size() - keepCount, conversationHistory.size()));

        StringBuilder historyText = new StringBuilder();
        for (ConversationMessage msg : toSummarize) {
            historyText.append("[").append(msg.role).append("] ").append(msg.effectiveContent()).append("\n");
        }

        try {
            int targetChars = getEffectiveTargetChars();
            // System prompt = stable instruction; user prompt = history to compress.
            // Previously both received the full text — every summarization call
            // paid double prompt tokens for identical content.
            String summarySystem =
                "You are an assistant summarizing a Magic: The Gathering game history.\n"
                + "Below is the conversation history of a game so far (user = game state updates, assistant = your decisions).\n"
                + "Compress this into a concise third-person summary of key events and decisions, preserving the facts needed to reconstruct game context.\n"
                + "Preserve: life total changes, key cards played/destroyed, critical counters/discards, board wipes, combat outcomes.\n"
                + "Drop: routine priority passes, mana tap sequences, and repetitive actions.\n"
                + "Target length: roughly " + targetChars + " characters.\n"
                + "End the summary with a 'LAST KNOWN STATE:' line giving exact current life totals, "
                + "your exact hand card names, and the names of important permanents on each battlefield — "
                + "these facts must survive summarization exactly, not as prose approximations.";
            String summaryUser = "Conversation history to summarize:\n" + historyText.toString();

            // Use non-streaming, no-thinking call for speed
            String requestBody = buildRequestBodyNoThinking(summarySystem, summaryUser, false);
            String responseJson = sendRequest(requestBody);
            LLMResponse summaryResp = parseResponse(responseJson);

            if (summaryResp != null && summaryResp.answer != null && !summaryResp.answer.trim().isEmpty()) {
                this.summarizedHistory = summaryResp.answer.trim();
                // Replace summarized messages with just the recent ones
                conversationHistory.clear();
                conversationHistory.addAll(recent);
                if (config.isVerboseLogging()) {
                    logger.info("LLM conversation summarized: " + toSummarize.size() + " msgs -> "
                            + summarizedHistory.length() + " chars, keeping " + recent.size() + " recent msgs");
                }
            } else {
                // Summarization returned empty — the LLM produced no usable summary
                throw new RuntimeException("LLM summarization returned empty response");
            }
        } catch (Throwable t) {
            throw new RuntimeException("LLM summarization failed: " + t.getMessage(), t);
        }
    }

    /**
     * Assistant message stored in conversation history. Mimicry format
     * a bare answer followed by a
     * {@code [model_reasoning]} fence — replaces the old
     * {@code Decision: 3\nReasoning: ...} prefix lines, which models copied
     * into their answers and which duplicated the answer-format contract the
     * prompts already state. Parsers/validators read {@code resp.answer}, never
     * this stored text, so the format-correction path is unaffected.
     */
    // Fence tokens for condensed-reasoning mimicry in conversation history
    // Package-visible so the parser-side stripper
    // (LLMPlayer.stripReasoningFence) reuses the exact same tokens — the
    // emitter and the stripper can never drift apart.
    static final String REASONING_FENCE_OPEN = "[model_reasoning]";
    static final String REASONING_FENCE_CLOSE = "[/model_reasoning]";

    private String buildAssistantMessage(LLMResponse resp) {
        StringBuilder sb = new StringBuilder();
        sb.append(resp.answer.trim());

        // Thinking retention: 0 = unlimited (infinity) —
        // keep the full reasoning; positive = truncate to N chars per message.
        // (Before this change, 0 meant "omit reasoning from history entirely".)
        int thinkLimit = config.getConversationThinkCharsPerMsg();
        if (resp.thinking != null && !resp.thinking.isEmpty()) {
            String condensed;
            if (thinkLimit <= 0 || resp.thinking.length() <= thinkLimit) {
                condensed = resp.thinking;
            } else {
                condensed = resp.thinking.substring(0, thinkLimit) + "... [reasoning truncated]";
            }
            sb.append("\n").append(REASONING_FENCE_OPEN).append("\n").append(condensed)
                    .append("\n").append(REASONING_FENCE_CLOSE);
        }
        return sb.toString();
    }

    private void trimConversationHistory() {
        int maxEx = config.getConversationMaxExchanges();
        int maxChars = config.getConversationMaxHistoryChars();

        // 0 = unlimited/infinity. Values near
        // MAX_VALUE come from older saves / explicit configs — also effectively
        // unlimited (the *2 below would overflow); skip trimming for both.
        boolean unlimitedExchanges = maxEx == 0 || maxEx > Integer.MAX_VALUE / 2;
        boolean unlimitedChars = maxChars == 0 || maxChars > Integer.MAX_VALUE / 2;

        if (!unlimitedExchanges) {
            int maxMsgs = maxEx * 2;
            while (conversationHistory.size() > maxMsgs) {
                conversationHistory.remove(0);
            }
        }

        if (!unlimitedChars) {
            int totalChars = conversationHistory.stream().mapToInt(ConversationMessage::estimatedChars).sum();
            while (totalChars > maxChars && conversationHistory.size() > 2) {
                ConversationMessage removed = conversationHistory.remove(0);
                totalChars -= removed.estimatedChars();
                if (config.isVerboseLogging())
                    logger.info("LLM conversation trimmed: removed oldest msg ("
                            + removed.estimatedChars() + " chars), total now " + totalChars);
            }
        }

        if (config.isVerboseLogging() && conversationHistory.size() > 2) {
            int remaining = conversationHistory.stream().mapToInt(ConversationMessage::estimatedChars).sum();
            logger.info("LLM conversation: " + conversationHistory.size() + " msgs, " + remaining
                    + " chars (limit " + (unlimitedChars ? "unlimited" : String.valueOf(maxChars)) + ")");
        }
    }

    /**
     * Ask-reason: attach the model's stated REASON line (the first non-blank
     * line after the answer line; a leading "REASON:" label is optional) when
     * the seat's askReason flag is on. Best-effort by contract — a missing or
     * mangled reason NEVER fails the move; the decisive action parse still
     * reads the first line only.
     * <p>
     * Called BEFORE the conversation-history store and the transcript write,
     * because writeTranscript emits "--- LLM STATED REASON ---" from
     * {@code resp.statedReason} — extracting any later (e.g. in LLMPlayer
     * after this method returns) would miss the transcript.
     */
    private LLMResponse attachStatedReason(LLMResponse resp) {
        if (resp == null || resp.answer == null || !config.isAskReason()) return resp;
        String reason = LLMPlayer.extractStatedReason(resp.answer);
        if (reason == null || reason.isEmpty()) return resp;
        return new LLMResponse(resp.thinking, resp.answer, resp.usage, reason, resp.finishReason);
    }

    /**
     * Conversation-mode chat with a stop sequence — used for terse-answer
     * prompts (action number, card number, yes/no) where any continuation text
     * is by-definition not part of the answer. Verified on ollama-cloud:
     * stop ["\n"] cuts the answer at the first newline, preventing rambling
     * continuations that validators otherwise have to reject after the fact.
     */
    public LLMResponse chatConversationWithStop(String fullPrompt, String compactSummary,
                                                String promptType, Integer turn, String phaseStep,
                                                String effortOverride, String stopSequence) {
        if (conversationSystemPrompt == null) {
            throw new IllegalStateException("chatConversation called before resetConversation");
        }
        summarizeIfNeeded();
        boolean useStream = shouldStream();
        try {
            String requestBody = buildRequestBodyConv(useStream, fullPrompt, effortOverride, stopSequence);
            logger.info("LLM request body: " + requestBody.length() + " chars"
                    + (stopSequence != null ? " [stop=\\n]" : ""));
            LLMResponse resp;
            if (useStream) {
                resp = sendStreamingRequest(requestBody);
            } else {
                String responseJson = sendRequest(requestBody);
                resp = parseResponse(responseJson);
            }
            resp = attachStatedReason(resp);
            if (resp != null && resp.answer != null && !resp.answer.trim().isEmpty()) {
                conversationHistory.add(new ConversationMessage("user", compactSummary, null,
                        promptType, turn, phaseStep));
                conversationHistory.add(new ConversationMessage("assistant", buildAssistantMessage(resp)));
            }
            writeTranscript(conversationSystemPrompt != null
                    ? conversationSystemPrompt + "\n\n(conversation history, " + conversationHistory.size() + " msgs)"
                    : "(conversation history, " + conversationHistory.size() + " msgs)",
                    fullPrompt, resp);
            return resp;
        } catch (Throwable t) {
            logger.error("LLM conversation API call failed: " + t.getMessage(), t);
            writeTranscript("(conversation)", fullPrompt, null);
            throw new RuntimeException("LLM conversation API call failed: " + t.getMessage(), t);
        }
    }

    private String buildRequestBodyConv(boolean stream, String currentFullPrompt) {
        return buildRequestBodyConv(stream, currentFullPrompt, null);
    }

    /**
     * @param effortOverride  per-call reasoning effort (empty-answer retry
     *                        ladder), or null for config.getReasoningEffort()
     */
    private String buildRequestBodyConv(boolean stream, String currentFullPrompt, String effortOverride) {
        return buildRequestBodyConv(stream, currentFullPrompt, effortOverride, null);
    }

    /**
     * Whether the request builders emit the non-standard {@code thinking}
     * object. It is an Ollama-family dialect feature: ollama-cloud consumes it
     * (budget_tokens acts as a hard reasoning cap), while strict
     * OpenAI-compatible gateways reject it as an unknown field with HTTP 400 —
     * measured 2026-09-26 on opencode zen/go: {@code glm-5.3-flash} died with
     * {@code invalid request body: json: unknown field "thinking"} while
     * glm-5.3 / kimi-k2.6 / qwen3.8-max / space-bunny-free accepted the same
     * body. Emit it only for Ollama-classified endpoints (the same heuristic
     * context-window detection uses) or when the explicit budget dialect is
     * configured — that dialect is object-based by definition, so its endpoint
     * must accept the object or fail fast.
     */
    private boolean emitsThinkingObject() {
        return emitsThinkingObjectAt(config.getApiEndpoint());
    }

    /**
     * Endpoint-scoped form of {@link #emitsThinkingObject()} — used by
     * buildRequestBodyConv so the thinking-object gate is evaluated against the
     * endpoint the body will actually POST to.
     */
    private boolean emitsThinkingObjectAt(String endpoint) {
        // Fail Fast: a null endpoint cannot be classified — isOllamaEndpoint
        // would NPE. The gate must be decided explicitly, never guessed.
        if (endpoint == null) {
            throw new IllegalStateException("LLM apiEndpoint is null — cannot classify the"
                    + " thinking-object dialect (set xmage.llm.endpoint / the GUI LLM config)");
        }
        return "budget".equals(config.getThinkingDialect())
                || LLMConfig.isOllamaEndpoint(endpoint);
    }

    /**
     * Full request builder: effort override + optional stop sequence.
     * stopSequence, when non-null, is emitted as stop:["<seq>"] — the caller
     * uses "\\n" for terse-answer prompts (the answer must be a single line).
     */
    private String buildRequestBodyConv(boolean stream, String currentFullPrompt, String effortOverride,
                                        String stopSequence) {
        return buildRequestBodyConv(stream, currentFullPrompt, effortOverride, stopSequence, false);
    }

    /**
     * Full request builder with an explicit no-thinking switch.
     * <p>
     * noThinking=true emits NEITHER the {@code thinking} object NOR
     * {@code reasoning_effort} for THIS call, so the entire output budget
     * ({@code max_tokens}) is available to the answer stream. Used by the
     * empty-answer ladder's FINAL attempt: after every effort-descent rung has
     * returned an empty answer, disabling thinking is the last thing that can
     * change the outcome — a reasoning blowout can never again consume the
     * budget the answer needs.
     * <p>
     * The 4-arg overload above delegates here with noThinking=false, so the
     * reflection-based body-shape tests (which resolve the 4-arg signature)
     * keep seeing the thinking-enabled shape.
     */
    private String buildRequestBodyConv(boolean stream, String currentFullPrompt, String effortOverride,
                                        String stopSequence, boolean noThinking) {
        JsonObject root = new JsonObject();
        root.addProperty("model", config.getModel());
        if (config.isTemperatureEnabled()) {
            root.addProperty("temperature", config.getTemperature());
        }

        // Endpoint the body will be POSTed to — resolved ONCE so the
        // thinking-object gate and the max_tokens reserve below agree on where
        // this request goes (they must never disagree; a disagreement would put
        // the answer reserve on a request the thinking object never reaches).
        // Null is a misconfiguration — emitsThinkingObjectAt fails fast on it.
        String endpoint = config.getApiEndpoint();

        Integer reasoningBudget = null;   // the cap emitted as thinking.budget_tokens, or null
        JsonObject thinking = null;
        if (config.isThinkingEnabled() && !noThinking) {
            thinking = new JsonObject();
            thinking.addProperty("type", "enabled");
            if ("budget".equals(config.getThinkingDialect())) {
                // Budget dialect: the per-attempt override IS the ladder rung, so
                // descent lowers the emitted cap directly.
                reasoningBudget = (effortOverride != null)
                        ? Integer.parseInt(effortOverride)
                        : currentBudgetTokens();
            } else {
                String effort = (effortOverride != null) ? effortOverride : config.getReasoningEffort();
                root.addProperty("reasoning_effort", effort);
                // Hard reasoning cap in effort dialect (#7): verified accepted
                // alongside reasoning_effort on ollama-cloud. Ollama-only — the cap
                // rides INSIDE the thinking object, which strict OpenAI-compatible
                // endpoints reject; there the cap falls back to the effort ladder
                // (pre-batch-2 behavior).
                if (config.getThinkingMaxTokens() > 0 && emitsThinkingObjectAt(endpoint)) {
                    // The cap must track the per-attempt effort
                    // override, not sit pinned at thinkingMaxTokens. The descent
                    // ladder lowers the rung each empty-answer retry; scaling the
                    // cap by how far below the configured effort that rung is makes
                    // the descent actually shrink the reasoning allowance instead
                    // of only changing the advisory reasoning_effort string.
                    reasoningBudget = effortDescentScaledBudget(effort);
                }
            }
            if (reasoningBudget != null) {
                thinking.addProperty("budget_tokens", reasoningBudget);
            }
            // The thinking object is an Ollama-family extension (budget dialect
            // is object-based by definition; effort dialect emits it only for
            // Ollama-classified endpoints). See emitsThinkingObjectAt().
            if (emitsThinkingObjectAt(endpoint)) {
                root.add("thinking", thinking);
            }
        }

        // Total output cap. When max_tokens is configured it is authoritative;
        // otherwise, if this request also carries a reasoning cap, reserve output
        // room for the ANSWER with max_tokens = reasoning cap * factor. The old
        // body emitted {"budget_tokens":32768} with NO
        // max_tokens, so a reasoning blowout consumed the whole output window and
        // content came back empty (620 occurrences, ~+45% calls). The answer now
        // always gets at least as much output room as the reasoning stream is
        // allowed. (On strict OpenAI-compatible endpoints no reasoning cap is
        // emitted, so this branch is inert there and reasoning_effort stands alone.)
        if (config.getMaxTokens() > 0) {
            root.addProperty("max_tokens", config.getMaxTokens());
        } else if (reasoningBudget != null) {
            root.addProperty("max_tokens", reasoningBudget * ANSWER_RESERVE_MULTIPLIER);
        }

        root.addProperty("stream", stream);

        JsonArray messages = new JsonArray();

        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", conversationSystemPrompt);
        messages.add(sysMsg);

        // Inject summarized history as system context so the LLM sees compressed game history
        if (summarizedHistory != null && !summarizedHistory.isEmpty()) {
            JsonObject sumMsg = new JsonObject();
            sumMsg.addProperty("role", "system");
            // Minor consistency: plain label instead of the old
            // "=== SUMMARIZED GAME HISTORY ===" banner (the banner-style
            // ===...=== marker is reserved for prompt sections the system prompt
            // documents; this is injected context, not a prompt section).
            sumMsg.addProperty("content", "Prior game summary:\n" + summarizedHistory);
            messages.add(sumMsg);
        }

        for (ConversationMessage cm : conversationHistory) {
            JsonObject msg = new JsonObject();
            msg.addProperty("role", cm.role);
            msg.addProperty("content", cm.effectiveContent());
            messages.add(msg);
        }

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", currentFullPrompt);
        messages.add(userMsg);

        root.add("messages", messages);
        if (stopSequence != null) {
            JsonArray stop = new JsonArray();
            stop.add(stopSequence);
            root.add("stop", stop);
        }
        if (stream) {
            // Ask the provider to append a final usage-only chunk to the stream so
            // we get real token counts for streaming calls too (OpenAI-compatible:
            // stream_options.include_usage). Providers that don't support the field
            // ignore it; the parser tolerates a missing usage chunk (null usage).
            JsonObject streamOptions = new JsonObject();
            streamOptions.addProperty("include_usage", true);
            root.add("stream_options", streamOptions);
        }
        return new Gson().toJson(root);
    }

    protected String buildRequestBody(String systemPrompt, String userPrompt, boolean stream) {
        JsonObject root = new JsonObject();
        root.addProperty("model", config.getModel());
        if (config.isTemperatureEnabled()) {
            root.addProperty("temperature", config.getTemperature());
        }
        if (config.getMaxTokens() > 0) {
            root.addProperty("max_tokens", config.getMaxTokens());
        }
        root.addProperty("stream", stream);

        if (config.isThinkingEnabled()) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "enabled");
            if ("budget".equals(config.getThinkingDialect())) {
                thinking.addProperty("budget_tokens", currentBudgetTokens());
            } else {
                root.addProperty("reasoning_effort", config.getReasoningEffort());
                if (config.getThinkingMaxTokens() > 0 && emitsThinkingObject()) {
                    thinking.addProperty("budget_tokens", config.getThinkingMaxTokens());
                }
            }
            if (emitsThinkingObject()) {
                root.add("thinking", thinking);
            }
        }

        JsonArray messages = new JsonArray();

        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        messages.add(sysMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messages.add(userMsg);

        root.add("messages", messages);
        return new Gson().toJson(root);
    }

    /** Build a request body with thinking explicitly disabled.
     *  Used for summarization calls where we want a direct answer, not reasoning.
     *  The thinking object is Ollama-family-only (see emitsThinkingObject): on
     *  strict OpenAI-compatible endpoints an unknown field 400s, so there the
     *  field is omitted entirely — the call simply doesn't ask for reasoning
     *  (models may still emit reasoning_content by default; the parser keeps it).
     *  The budget dialect keeps the object — its endpoints accept it by
     *  definition, and it's the only way to express "disabled" there. */
    protected String buildRequestBodyNoThinking(String systemPrompt, String userPrompt, boolean stream) {
        JsonObject root = new JsonObject();
        root.addProperty("model", config.getModel());
        if (config.isTemperatureEnabled()) {
            root.addProperty("temperature", config.getTemperature());
        }
        if (config.getMaxTokens() > 0) {
            root.addProperty("max_tokens", config.getMaxTokens());
        }
        root.addProperty("stream", stream);

        // Explicitly disable thinking (Ollama-classified endpoints and the
        // budget dialect only — strict OpenAI-compatible endpoints reject the
        // unknown field; see emitsThinkingObject).
        if (emitsThinkingObject()) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "disabled");
            root.add("thinking", thinking);
        }

        JsonArray messages = new JsonArray();

        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        messages.add(sysMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messages.add(userMsg);

        root.add("messages", messages);
        return new Gson().toJson(root);
    }

    /** Current thinking budget for the "budget" dialect — first ladder entry. */
    private int currentBudgetTokens() {
        List<Integer> ladder = config.getThinkingBudgetTokens();
        if (ladder == null || ladder.isEmpty()) {
            throw new IllegalStateException("thinkingDialect=budget but thinkingBudgetTokens ladder is empty");
        }
        return ladder.get(0);
    }

    /**
     * The effort-dialect reasoning cap actually emitted as
     * {@code thinking.budget_tokens}, scaled by how far the per-attempt effort
     * rung has descended. The cap used to be pinned at
     * {@link LLMConfig#getThinkingMaxTokens()} on every attempt, so the
     * empty-answer effort descent lowered only the advisory {@code
     * reasoning_effort} string while the reasoning allowance stayed maximal — the
     * loop could only be broken by lexical nudging and one seat burned all five
     * attempts then conceded. Scaling the cap by the rung's position in the
     * configured effort ladder makes the descent genuinely shrink the reasoning
     * allowance.
     *
     * <p>Anchor = the CONFIGURED effort rung (the rung attempt 1 runs at), exactly
     * as {@code LLMPlayer.effortForAttempt} defines the descent: attempt 1 emits
     * the full cap, and each rung the retry ladder descends below the configured
     * effort emits proportionally less down to {@link #MIN_REASONING_BUDGET_TOKENS}
     * at the ladder's lowest rung (so it never reaches 0/negative and always stays a
     * valid reasoning cap). When the effort is not in the ladder (custom per-model
     * value) or no descent below the configured rung is possible, there is no
     * ordering to scale by, so the full configured cap is used — same "no guessing"
     * policy {@code LLMPlayer.effortForAttempt} documents for the descent itself.
     */
    private int effortDescentScaledBudget(String effort) {
        int cap = config.getThinkingMaxTokens();
        List<String> ladder = config.getReasoningEffortLevels();
        if (ladder == null) return cap;
        int rung = ladder.indexOf(effort);                          // 0 = highest effort
        int configuredRung = ladder.indexOf(config.getReasoningEffort());
        // No descent (attempt 1, or an effort the ladder can't order): the full cap.
        if (rung < 0 || configuredRung < 0 || rung <= configuredRung) return cap;
        int span = ladder.size() - 1 - configuredRung;             // rungs below configured
        if (span <= 0) return cap;                                 // configured effort is the lowest rung
        int descended = rung - configuredRung;
        int scaled = (int) ((long) cap * (span - descended) / span);
        return Math.max(scaled, MIN_REASONING_BUDGET_TOKENS);
    }

    /**
     * Stable per-seat identity for provider session headers (opencode zen/go
     * routes per-session and 400s without one). Server-key seats have no
     * bridge context, so derive from the endpoint+model — constant per seat,
     * distinct across seats on different models, never a secret.
     */
    private String sessionIdentity() {
        String ep = config.getApiEndpoint() != null ? config.getApiEndpoint() : "";
        String m = config.getModel() != null ? config.getModel() : "";
        return ep + "|" + m;
    }

    /**
     * Client-key mode forces non-streaming: the browser does one plain POST
     * and returns the full response JSON. SSE relay through the bridge would
     * duplicate the parser for no feature gain (the web debug panel streams
     * from GameView.llmDebug snapshots either way).
     */
    private boolean shouldStream() {
        return config.isStreamingEnabled() && !config.isUseClientKey();
    }

    /**
     * Client-key bridge send: hand the exact
     * request body to the seat-owner's browser and block for its decision.
     * The provider call itself happens in the browser with the user's key —
     * no key or provider traffic enters the server JVM.
     */
    private String sendViaBridge(String requestBody) throws IOException,
            java.util.concurrent.TimeoutException {
        if (bridgeGameId == null || bridgeSeatId == null) {
            throw new IllegalStateException("Client-key seat has no bridge context — "
                    + "initTranscript/setBridgeContext must run before the first LLM call "
                    + "(gameId=" + bridgeGameId + " seatId=" + bridgeSeatId + ")");
        }
        mage.interfaces.LLMPromptPayload payload = new mage.interfaces.LLMPromptPayload(
                java.util.UUID.randomUUID(),
                java.util.UUID.fromString(bridgeSeatId),
                java.util.UUID.fromString(bridgeGameId),
                bridgePlayerName,
                requestBody,
                config.getApiEndpoint(),
                false,  // stream — see shouldStream(): always false on this path
                config.getTimeoutSeconds(),
                config.getClientKeyTag(),
                // Pinned (game, seat) route — set ONLY on the post-game
                // reflection thread (beginPinnedReflectionRoute). Every in-game
                // call, including the sideboard and revised-strategy calls that
                // run for the SAME game while the reflection is still queued,
                // sees null here and uses the live lookup, so it can never steal
                // the reflection's pin

                reflectionRouteToken.get());
        try {
            mage.interfaces.LLMPromptPayload.Decision decision =
                    mage.interfaces.LLMBridgeRegistry.request(payload);
            if (decision.rawResponse == null || decision.rawResponse.isEmpty()) {
                throw new IOException("Browser returned empty provider response for request "
                        + payload.requestId);
            }
            return decision.rawResponse;
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("Browser LLM call timed out after "
                    + config.getTimeoutSeconds() + "s (requestId=" + payload.requestId + ")", e);
        } catch (mage.interfaces.LLMBridgeRegistry.BrowserCallException e) {
            // Browser-reported provider failure. Rethrow TYPED (terminal-failure
            // endgame): flattening to IOException here destroyed httpStatus/retryable,
            // so isRetryable() fell back to "retryable" and a dead/revoked key ground
            // through the full 5-attempt ladder (~30s) before the same fatal throw.
            // The caller's classifier unwraps the cause chain and consults
            // isRetryableWithBackoff()/retryable below. Message carries the provider
            // error text for logs + transcripts either way.
            throw e;
        } catch (IllegalStateException e) {
            throw new IOException("LLM bridge unavailable: " + e.getMessage(), e);
        } catch (java.util.concurrent.CompletionException e) {
            // Registry/transport internals — unwrap to keep BrowserCallException
            // reachable when it arrives wrapped by the CompletableFuture join.
            Throwable cause = e.getCause();
            if (cause instanceof mage.interfaces.LLMBridgeRegistry.BrowserCallException) {
                throw (mage.interfaces.LLMBridgeRegistry.BrowserCallException) cause;
            }
            if (cause instanceof TimeoutException) {
                throw (TimeoutException) cause;
            }
            throw new IOException("LLM bridge call failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IOException("LLM bridge call failed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IOException("LLM bridge call failed: " + e.getMessage(), e);
        }
    }

    protected String sendRequest(String requestBody) throws IOException,
            java.util.concurrent.TimeoutException {
        if (config.isUseClientKey()) {
            return sendViaBridge(requestBody);
        }
        URI uri = URI.create(config.getApiEndpoint());
        URL url = uri.toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");

        if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + config.getApiKey());
        }
        // opencode zen/go routes per-session and 400s without this header
        // ("MissingSessionID", measured live). Stable per-seat value
        // so one seat's calls share a session.
        if (config.getApiEndpoint() != null
                && config.getApiEndpoint().contains("opencode.ai")) {
            conn.setRequestProperty("x-opencode-session",
                    "llmage-" + Integer.toHexString(sessionIdentity().hashCode()));
        }

        conn.setDoOutput(true);
        conn.setConnectTimeout(config.getTimeoutSeconds() * 1000);
        conn.setReadTimeout(config.getTimeoutSeconds() * 1000);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int status = conn.getResponseCode();
        InputStream is = (status >= 200 && status < 300)
                ? conn.getInputStream()
                : conn.getErrorStream();

        String responseBody;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            responseBody = sb.toString();
        }
        // Capture Retry-After BEFORE disconnect — the retry loop honors it for
        // 429/5xx so we don't hammer a rate-limited endpoint with fixed 2s gaps.
        String retryAfterHeader = conn.getHeaderField("Retry-After");
        conn.disconnect();

        if (status >= 400) {
            logger.error("LLM API error (HTTP " + status + "): " + responseBody);
            throw new HttpStatusException(status, responseBody, retryAfterHeader);
        }

        return responseBody;
    }

    protected LLMResponse sendStreamingRequest(String requestBody) throws IOException {
        URI uri = URI.create(config.getApiEndpoint());
        URL url = uri.toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream");

        if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + config.getApiKey());
        }
        // opencode zen/go routes per-session and 400s without this header
        // ("MissingSessionID", measured live 2026-09-30). Same seat-stable value
        // as sendRequest.
        if (config.getApiEndpoint() != null
                && config.getApiEndpoint().contains("opencode.ai")) {
            conn.setRequestProperty("x-opencode-session",
                    "llmage-" + Integer.toHexString(sessionIdentity().hashCode()));
        }

        conn.setDoOutput(true);
        conn.setConnectTimeout(config.getTimeoutSeconds() * 1000);
        conn.setReadTimeout(config.getTimeoutSeconds() * 1000);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int status = conn.getResponseCode();
        if (status >= 400) {
            String errBody = readAll(conn.getErrorStream());
            String retryAfterHeader = conn.getHeaderField("Retry-After");
            conn.disconnect();
            logger.error("LLM API error (HTTP " + status + "): " + errBody);
            throw new HttpStatusException(status, errBody, retryAfterHeader);
        }

        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        String modelName = config.getModel().replaceAll("[:/].*", "");
        final LLMResponse.Usage[] usageHolder = new LLMResponse.Usage[1];
        final String[] finishReasonHolder = new String[1];

        // Signal streaming start — watcher thread will see these
        streamingStartedAt = System.currentTimeMillis();
        streamingInProgress = true;
        streamingReasoning = "";
        streamingContent = "";

        boolean reasoningPhase = true;
        synchronized (STREAM_LOCK) {
            System.out.println("\n=== LLM STREAM [" + modelName + "] ===");
        }
        try {
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    if (!line.startsWith("data: ")) continue;

                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) break;

                    try {
                        JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                        // Final usage-only chunk (stream_options.include_usage): has
                        // no choices. Capture it — real token counts per decision.
                        if (usageHolder[0] == null && (chunk.has("usage") && !chunk.get("usage").isJsonNull())) {
                            usageHolder[0] = parseUsage(chunk);
                            continue;
                        }
                        JsonArray choices = chunk.getAsJsonArray("choices");
                        if (choices == null || choices.isEmpty()) continue;

                        JsonObject choice = choices.get(0).getAsJsonObject();
                        // finish_reason arrives on (usually) the final chunk; keep
                        // the last non-null so an empty answer can be diagnosed.
                        String fr = getStringOrNull(choice, "finish_reason");
                        if (fr != null) finishReasonHolder[0] = fr;
                        JsonObject delta = choice.getAsJsonObject("delta");
                        if (delta == null) continue;

                        String rToken = getStringOrNull(delta, "reasoning_content");
                        if (rToken == null) rToken = getStringOrNull(delta, "reasoning");
                        String cToken = getStringOrNull(delta, "content");
                        if (rToken != null || cToken != null) {
                            synchronized (STREAM_LOCK) {
                                if (rToken != null && !rToken.isEmpty()) {
                                    reasoning.append(rToken);
                                    streamingReasoning = reasoning.toString();
                                    if (reasoningPhase) System.out.print(rToken);
                                }
                                if (cToken != null && !cToken.isEmpty()) {
                                    content.append(cToken);
                                    streamingContent = content.toString();
                                    if (reasoningPhase && reasoning.length() > 0) {
                                        System.out.println("\n--- ANSWER ---");
                                        reasoningPhase = false;
                                    }
                                    System.out.print(cToken);
                                }
                            }
                        }
                    } catch (JsonSyntaxException e) {
                        logger.debug("Skipped malformed SSE chunk: " + e.getMessage());
                    }
                }
            } finally {
                try { br.close(); } catch (IOException e) {
                    logger.debug("Error closing stream reader: " + e.getMessage());
                }
            }
        } finally {
            conn.disconnect();
            // ALWAYS clear streaming state — even if exception killed the stream
            streamingInProgress = false;
            streamingReasoning = null;
            streamingContent = null;
            streamingStartedAt = 0;
        }

        synchronized (STREAM_LOCK) {
            System.out.println();
            System.out.println("=== END STREAM [" + modelName + "] ===");
        }

        LLMResponse resp = new LLMResponse(reasoning.toString(), content.toString(),
                usageHolder[0], null, finishReasonHolder[0]);
        if (resp.usage != null && config.isVerboseLogging()) {
            logger.info("LLM (stream) " + resp.usage);
        }
        return resp;
    }

    protected LLMResponse parseResponse(String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            LLMResponse.Usage usage = parseUsage(root);
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices != null && choices.size() > 0) {
                JsonObject firstChoice = choices.get(0).getAsJsonObject();
                JsonObject message = firstChoice.getAsJsonObject("message");
                if (message != null) {
                    String content = getStringOrNull(message, "content");
                    // reasoning_content is the OpenAI/DeepSeek-compatible field
                    // (message.reasoning_content); `reasoning` and the
                    // choices[0].reasoning_content placement are provider variants
                    // (ollama-cloud). Check all three — the old code missed
                    // message.reasoning_content entirely, silently dropping the
                    // reasoning of every non-streaming DeepSeek/zen response.
                    String reasoning = getStringOrNull(message, "reasoning_content");
                    if (reasoning == null || reasoning.isEmpty()) {
                        reasoning = getStringOrNull(message, "reasoning");
                    }
                    if (reasoning == null || reasoning.isEmpty()) {
                        reasoning = getStringOrNull(firstChoice, "reasoning_content");
                    }
                    // finish_reason was never read before the glm-5.3-flash@zen
                    // terminal empty-answer investigation — without
                    // it, "truncated at max_tokens" (finish_reason=length) and
                    // "stopped with empty content" (finish_reason=stop) were
                    // indistinguishable from a provider hiccup.
                    String finishReason = getStringOrNull(firstChoice, "finish_reason");
                    LLMResponse resp = new LLMResponse(reasoning, content, usage, null, finishReason);
                    if (usage != null && config.isVerboseLogging()) {
                        logger.info("LLM " + usage);
                    }
                    return resp;
                }
            }
            throw new RuntimeException("Unexpected LLM response format: " + responseBody);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to parse LLM response: " + t.getMessage(), t);
        }
    }

    /**
     * Extract token usage from a chat-completion response. Returns null when the
     * provider omits the object entirely (acceptable — some streaming configs do).
     * A malformed usage block, however, must surface: we rely on these numbers for
     * cost accounting and future token-based context bookkeeping (Fail Fast).
     */
    static LLMResponse.Usage parseUsage(JsonObject root) {
        if (!root.has("usage") || root.get("usage").isJsonNull()) return null;
        JsonObject u = root.getAsJsonObject("usage");
        Integer prompt = getIntOrNull(u, "prompt_tokens");
        Integer completion = getIntOrNull(u, "completion_tokens");
        Integer total = getIntOrNull(u, "total_tokens");
        Integer reasoning = null;
        if (u.has("completion_tokens_details") && u.get("completion_tokens_details").isJsonObject()) {
            reasoning = getIntOrNull(u.getAsJsonObject("completion_tokens_details"), "reasoning_tokens");
        }
        // A usage object with no token fields at all is malformed — don't silently accept.
        if (prompt == null && completion == null && total == null) {
            throw new RuntimeException("LLM response contained a 'usage' object with no recognized token fields: " + u);
        }
        return new LLMResponse.Usage(prompt, completion, total, reasoning);
    }

    private static Integer getIntOrNull(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsInt();
        }
        return null;
    }

    private String getStringOrNull(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    /** Java 8-compatible read-all from an InputStream. Returns empty string on null input. */
    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /** List for serializing to client debug window. */
    public List<ConversationMessage> getConversationHistory() {
        return new ArrayList<>(conversationHistory);
    }

    /** Total estimated character count of the full conversation context. */
    public int getTotalContextChars() {
        int sys = conversationSystemPrompt != null ? conversationSystemPrompt.length() : 0;
        int hist = conversationHistory.stream().mapToInt(ConversationMessage::estimatedChars).sum();
        int sum = summarizedHistory != null ? summarizedHistory.length() : 0;
        return sys + hist + sum;
    }

    /**
     * Returns the model's total context window in tokens, as stored on the
     * config (auto-detected from Ollama /api/show or OpenAI-compatible
     * /v1/models at player construction; explicit config values are never
     * overwritten here).
     */
    public int getModelContextWindow() {
        return config.getModelContextWindow();
    }

    /**
     * Inject a failure message into the conversation history so the LLM
     * sees it on retry. The next chatConversation call includes this as
     * part of the conversation prefix.
     */
    public void addFailureMessage(String message) {
        conversationHistory.add(new ConversationMessage("user", message));
    }

    public String getConversationSystemPrompt() {
        return conversationSystemPrompt;
    }

    public LLMConfig getConfig() {
        return config;
    }

    // ==================================================================
    //  TRANSCRIPT LOGGING
    // ==================================================================

    private void writeTranscript(String systemPrompt, String userPrompt, LLMResponse resp) {
        String dir = config.getTranscriptDir();
        if (dir == null || dir.isEmpty()) return;

        synchronized (transcriptLock) {
            // Async post-game reflection handoff: when the calling
            // thread declared the transcript generation it owns and that
            // generation has since been rotated away (sideboarding
            // initTranscript / close / next game), the write is SKIPPED — a stale
            // async writer must never append into a transcript file it no longer
            // owns. Game-thread writers (no generation declared) are unaffected.
            // Held under transcriptLock: the check and the write are atomic vs.
            // transcript rotation.
            Long expectedGeneration = expectedTranscriptGeneration.get();
            if (expectedGeneration != null && expectedGeneration != transcriptToken) {
                return;
            }
            writeTranscriptLocked(systemPrompt, userPrompt, resp);
        }
    }

    private void writeTranscriptLocked(String systemPrompt, String userPrompt, LLMResponse resp) {
        try {
            if (transcriptWriter == null) {
                throw new IllegalStateException("writeTranscript called before initTranscript");
            }

            exchangeCount++;
            transcriptWriter.println("═══════════════════════════════════════════════════");
            transcriptWriter.println("EXCHANGE #" + exchangeCount);
            transcriptWriter.println("Time: " + LocalDateTime.now());
            transcriptWriter.println("═══════════════════════════════════════════════════");
            transcriptWriter.println();
            // Minor consistency: the system prompt is the long-lived prefix and
            // is byte-identical across exchanges (prompt-cache contract in
            // resetConversation). Re-printing it every exchange bloated the
            // transcript ~4KB per exchange, so unchanged repeats collapse to a
            // one-line marker naming the exchange that printed it in full.
            // The dynamic "(conversation history, N msgs)" suffix is not part
            // of the identity — it is printed alongside the marker when present.
            String baseSystem = systemPrompt;
            String dynamicSuffix = "";
            if (systemPrompt != null) {
                java.util.regex.Matcher hm = TRANSCRIPT_HISTORY_MARKER.matcher(systemPrompt);
                if (hm.find()) {
                    dynamicSuffix = hm.group();
                    baseSystem = systemPrompt.substring(0, hm.start());
                }
            }
            if (baseSystem != null && baseSystem.equals(lastTranscriptSystemPrompt)) {
                transcriptWriter.println("--- SYSTEM PROMPT (unchanged since exchange #"
                        + lastTranscriptSystemPromptExchange + "; " + baseSystem.length() + " chars) ---");
                if (!dynamicSuffix.isEmpty()) {
                    transcriptWriter.println(dynamicSuffix.trim());
                }
            } else {
                transcriptWriter.println("--- SYSTEM PROMPT ---");
                transcriptWriter.println(systemPrompt);
                lastTranscriptSystemPrompt = baseSystem;
                lastTranscriptSystemPromptExchange = exchangeCount;
            }
            transcriptWriter.println();
            transcriptWriter.println("--- USER PROMPT ---");
            transcriptWriter.println(userPrompt);
            transcriptWriter.println();

            if (resp != null) {
                Integer reasoningTokens = (resp.usage != null) ? resp.usage.reasoningTokens : null;
                if (resp.thinking != null && !resp.thinking.isEmpty()) {
                    transcriptWriter.println("--- LLM THINKING / REASONING ---");
                    transcriptWriter.println(resp.thinking);
                    transcriptWriter.println();
                } else if (reasoningTokens != null && reasoningTokens > 0) {
                    // Hidden-thinking provider (OpenAI Responses / Anthropic
                    // native): the model reasoned and was billed for it, but no
                    // reasoning text is returned. Record the fact, so postgame
                    // analysis can tell "model didn't think" from "provider hid
                    // the thinking".
                    transcriptWriter.println("--- LLM THINKING / REASONING ---");
                    transcriptWriter.println("(reasoning hidden by provider: " + reasoningTokens
                            + " tokens billed)");
                    transcriptWriter.println();
                }
                if (resp.statedReason != null && !resp.statedReason.isEmpty()) {
                    // Ask-reason: the model's stated one-sentence justification,
                    // asked for in the prompt (xmage.llm[2].ask_reason). A post-hoc
                    // rationalization in the answer content, NOT the hidden
                    // chain-of-thought — for hidden-reasoning providers this is
                    // the only intent signal available.
                    transcriptWriter.println("--- LLM STATED REASON ---");
                    transcriptWriter.println(resp.statedReason);
                    transcriptWriter.println();
                }
                transcriptWriter.println("--- LLM ANSWER ---");
                transcriptWriter.println(resp.answer);
            } else {
                transcriptWriter.println("--- LLM ANSWER ---");
                transcriptWriter.println("[ERROR: null response]");
            }
            transcriptWriter.println();
            transcriptWriter.flush();
        } catch (Exception e) {
            // Fail Fast: transcript write failures indicate a real problem
            // (e.g. disk full, bad path) and must not be swallowed.
            throw new RuntimeException("Failed to write transcript: " + e.getMessage(), e);
        }
    }

    /**
     * Write a post-game reflection summary to a file alongside the transcript.
     * Called once from LLMPlayer.quit() after the LLM generates its reflection.
     */
    public void writePostGameSummary(String playerName, String modelShort, String content) {
        writePostGameSummaryTo(null, playerName, modelShort, content);
    }

    /**
     * Pinned-directory variant used by the ASYNC post-game reflection task
     * the game directory is captured on the game thread when the
     * reflection is scheduled, because sideboarding/next-game re-points
     * transcriptGameDir. Without the pin, a slow reflection would file its
     * postgame summary under the NEXT game's directory (or nowhere useful).
     */
    public void writePostGameSummaryTo(Path pinnedDir, String playerName, String modelShort, String content) {
        String dir = config.getTranscriptDir();
        if (dir == null || dir.isEmpty()) return;
        try {
            Path outDir = (pinnedDir != null) ? pinnedDir
                    : (transcriptGameDir != null) ? transcriptGameDir : Paths.get(dir);
            Files.createDirectories(outDir);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String filename = playerName + "_" + modelShort + "_postgame-" + ts + ".txt";
            Path file = outDir.resolve(filename);
            // Same-second collision guard:
            // THIS path is written twice inside a Bo3 game-1 window — once by
            // generatePostGameSummary() at game end and once by generateRevisedStrategy()
            // during sideboarding. Measured 276-292 ms apart, i.e. the same second;
            // without this guard the second Files.write truncated the first file and
            // the game-1 reflection was silently lost. Same suffix loop as initTranscript.
            int suffix = 1;
            while (Files.exists(file)) {
                filename = playerName + "_" + modelShort + "_postgame-" + ts + "-" + suffix + ".txt";
                file = outDir.resolve(filename);
                suffix++;
            }
            Files.write(file, content.getBytes(StandardCharsets.UTF_8));
            logger.info("Post-game summary written to: " + file);
        } catch (Exception e) {
            // Fail Fast: post-game summary write failures must not be swallowed.
            throw new RuntimeException("Failed to write post-game summary: " + e.getMessage(), e);
        }
    }

    /**
     * Write a human-readable sideboard summary to a file alongside the
     * transcript. Called from LLMPlayer.sideboard() after the changes (if any)
     * were applied.
     */
    public void writeSideboardSummary(String playerName, String modelShort, String content) {
        String dir = config.getTranscriptDir();
        if (dir == null || dir.isEmpty()) return;
        try {
            Path outDir = (transcriptGameDir != null) ? transcriptGameDir : Paths.get(dir);
            Files.createDirectories(outDir);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String filename = playerName + "_" + modelShort + "_sideboard-" + ts + ".txt";
            Path file = outDir.resolve(filename);
            // Same-second collision guard — same exposure as writePostGameSummary
            // (the same-second clobber fix applies here: a re-queued
            // sideboarding (table re-submit) can write twice within one second,
            // and the second Files.write would truncate the first summary.
            int suffix = 1;
            while (Files.exists(file)) {
                filename = playerName + "_" + modelShort + "_sideboard-" + ts + "-" + suffix + ".txt";
                file = outDir.resolve(filename);
                suffix++;
            }
            Files.write(file, content.getBytes(StandardCharsets.UTF_8));
            logger.info("Sideboard summary written to: " + file);
        } catch (Exception e) {
            // Fail Fast: sideboard summary write failures must not be swallowed.
            throw new RuntimeException("Failed to write sideboard summary: " + e.getMessage(), e);
        }
    }

    public void closeTranscript() {
        synchronized (transcriptLock) {
            transcriptToken++;  // explicit close rotates too: stale async writers must not append
            closeTranscriptLocked();
        }
    }

    /** Rotate + close only when the caller still owns the current transcript. */
    private void closeTranscriptLocked() {
        if (transcriptWriter != null) {
            transcriptWriter.println("=== END OF TRANSCRIPT ===");
            transcriptWriter.println("Completed: " + LocalDateTime.now());
            transcriptWriter.flush();
            transcriptWriter.close();
            transcriptWriter = null;
            logger.info("LLM transcript closed (" + exchangeCount + " exchanges)");
        }
    }

    // ==================================================================
    // ASYNC POST-GAME REFLECTION HANDOFF
    // ==================================================================

    /**
     * Hand the current transcript to the async post-game reflection task and
     * return its ownership token. The token is valid until the next
     * initTranscript()/closeTranscript() rotation; closing the game is then the
     * task's job via {@link #closeTranscriptIfCurrent(long)} — NOT the game
     * thread's, which must not block on a hung reflection.
     */
    public long detachTranscriptForAsyncClose() {
        synchronized (transcriptLock) {
            return transcriptToken;
        }
    }

    /**
     * Close the transcript iff no rotation happened since
     * {@link #detachTranscriptForAsyncClose()} was called. A no-op when
     * sideboarding/next-game already rotated the writer (the reflection task
     * then owns nothing and must not touch the live writer).
     */
    public void closeTranscriptIfCurrent(long token) {
        synchronized (transcriptLock) {
            if (token != transcriptToken) {
                return;  // rotated by initTranscript()/closeTranscript(): not ours to close
            }
            closeTranscriptLocked();
        }
    }

    /** True when the given ownership token is still the current transcript generation. */
    public boolean isTranscriptCurrent(long token) {
        return token == transcriptToken;
    }

    /** The game directory the current transcript was opened in (null when transcripts are off). */
    public Path getTranscriptGameDir() {
        return transcriptGameDir;
    }

    // Thread-scoped declaration used by the async reflection task: from
    // beginAsyncTranscriptScope(token) to endAsyncTranscriptScope() every
    // transcript write on THIS thread verifies that the declared generation is
    // still current, and is skipped otherwise (see writeTranscript).
    public void beginAsyncTranscriptScope(long token) {
        expectedTranscriptGeneration.set(token);
    }

    public void endAsyncTranscriptScope() {
        expectedTranscriptGeneration.remove();
    }
}
