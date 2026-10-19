package mage.player.llm;

import java.io.Serializable;

/**
 * Record of a single LLM exchange (prompt + response) keyed to a GameState index.
 * Stored in LLMPlayer.replayLLMData during gameplay, serialized into the .game
 * replay file, and used by ReplaySession to show LLM reasoning during replays.
 */
public class LLMExchangeRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    public final int stateIndex;      // GameStates index at time of prompt
    public final String promptType;   // "priority", "attackers", "blockers", "target", etc.
    public final String compactSummary;  // Short situation summary (stored in conversation)
    public final String thinking;     // LLM's reasoning (if any, null if none)
    public final String answer;       // LLM's response text
    public final String playerName;   // Which LLM player made this decision
    public final String modelName;    // Which model was used
    public final int turnNum;         // Game turn number at time of prompt
    public final String phaseStep;    // Phase/step description at time of prompt

    // Pre-game sections (2026-09-30, replay-viewer pre-game gap): the FULL
    // prompt text the LLM saw for pre-game / post-game exchanges (deck
    // assessment, sideboard review) — the compactSummary is too lossy for the
    // replay viewer, which must render the same pre-game blocks the live LLM
    // tab shows. Null on in-game exchanges, whose full prompt is the game
    // state itself (reconstructible) — only pre/post-game prompts, which are
    // NOT derivable from any frame, are stored.
    public String fullPrompt;
    // True when this record is a pre/post-game section marker rather than an
    // in-game decision: the replay builder places these under a fixed
    // stateIndex (0) so the frontend can render them ahead of the turn-1
    // exchanges regardless of cursor position.
    public boolean preGame;

    public LLMExchangeRecord(int stateIndex, String promptType, String compactSummary,
                             String thinking, String answer,
                             String playerName, String modelName,
                             int turnNum, String phaseStep) {
        this.stateIndex = stateIndex;
        this.promptType = promptType;
        this.compactSummary = compactSummary;
        this.thinking = thinking;
        this.answer = answer;
        this.playerName = playerName;
        this.modelName = modelName;
        this.turnNum = turnNum;
        this.phaseStep = phaseStep;
    }
}