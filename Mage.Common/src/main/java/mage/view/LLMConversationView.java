package mage.view;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializable view of an LLM player's conversation history for the debug window.
 * Lightweight POJO — role + content pairs, plus optional per-exchange metadata
 * (promptType, turn, phase) and pre/post-game analysis sections.
 */
public class LLMConversationView implements Serializable {
    private static final long serialVersionUID = 2L;

    public final String playerName;
    public final String modelName;
    public final int exchangeCount;
    public final int totalContextChars;
    public final int modelContextWindow;   // total model context window in tokens (0 if unknown)
    public final List<LLMMessageView> messages;

    // Streaming state — non-null means a request is in progress
    public boolean streaming;
    public String streamingReasoning;
    public String streamingContent;
    public long streamingStartedAt;

    // Pre-game deck assessment / gameplan (null if not generated or disabled)
    public String gamePlan;

    // Post-game reflection (null until the game ends and reflection is generated)
    public String postGameReflection;

    // Match review for sideboarding (null for single-game matches or not yet generated)
    public String matchReview;

    public LLMConversationView(String playerName, String modelName, int exchangeCount, int totalContextChars, int modelContextWindow) {
        this.playerName = playerName;
        this.modelName = modelName;
        this.exchangeCount = exchangeCount;
        this.totalContextChars = totalContextChars;
        this.modelContextWindow = modelContextWindow;
        this.messages = new ArrayList<>();
    }

    public static class LLMMessageView implements Serializable {
        private static final long serialVersionUID = 2L;
        public final String role;
        public final String content;

        // Per-exchange metadata (null on system messages and format-correction exchanges)
        public String promptType;     // "priority", "attackers", "blockers", "target", etc.
        public Integer turn;          // game turn number at time of prompt
        public String phaseStep;      // phase/step description at time of prompt
        public String compactSummary; // short situation summary stored in conversation

        public LLMMessageView(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}