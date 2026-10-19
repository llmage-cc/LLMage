package org.mage.test.LLM;

import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.player.llm.LLMClient;
import mage.player.llm.LLMConfig;
import mage.player.llm.LLMExchangeRecord;
import mage.player.llm.LLMPlayer;
import mage.player.llm.LLMResponse;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;
import org.mage.test.player.TestPlayer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pins the format-correction replay record:

 *
 * <p>When a decision answer fails format validation, {@code chatAndRecord}
 * issues a correction call and then records the corrected exchange into
 * {@code replayLLMData}. Until the fix, that record's compactSummary was the
 * hardcoded literal {@code "format correction"} — while the
 * conversation-history path (what the live webui tab and transcripts show)
 * carried a descriptive summary ("Retry of the &lt;type&gt; decision — …").
 * The replay viewer renders compactSummary as the user message body, so every
 * corrected exchange in a replay showed the placeholder.
 *
 * <p>This test drives the REAL priority decision path (fixture game is not a
 * simulation, so chatAndRecord computes a valid stateIdx and records), with a
 * stub that answers off-format first ("Decision: 0 — reasoning here") and
 * cleanly on the correction call ("0"). Assertions:
 * 1. A {@code -corrected} replay record exists.
 * 2. Its compactSummary is NOT the literal "format correction".
 * 3. It starts with "Retry of the " (the descriptive correction summary).
 * 4. No record anywhere in the map carries the literal.
 *
 * <p>Stub client: no live calls.
 */
public class LLMFormatCorrectionReplaySummaryTest extends CardTestPlayerBaseLLM {

    /** callCount: 1 = original (off-format answer), 2+ = correction (clean answer). */
    private static final AtomicInteger callCount = new AtomicInteger();

    static class OffFormatStubClient extends LLMClient {
        OffFormatStubClient(LLMConfig config) {
            super(config);
        }

        @Override
        public LLMResponse chatConversation(String fullPrompt, String compactSummary,
                                            String promptType, Integer turn, String phaseStep,
                                            String effortOverride) {
            return answer();
        }

        @Override
        public LLMResponse chatConversationWithStop(String fullPrompt, String compactSummary,
                                                    String promptType, Integer turn, String phaseStep,
                                                    String effortOverride, String stopSequence) {
            return answer();
        }

        private LLMResponse answer() {
            if (callCount.incrementAndGet() == 1) {
                // Off-format on purpose: validator rejects, correction path fires.
                // (No "Decision:" prefix — that would be stripped before validation
                // and never reach the correction path.)
                return new LLMResponse("stub thinking", "The best course is to do nothing, so I choose to pass.\nReasoning: passing is safe.");
            }
            return new LLMResponse("stub thinking", "0");
        }
    }

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if (getFullSimulatedPlayers().contains(name)) {
            TestLLMPlayer llmPlayer = new TestLLMPlayer(name, RangeOfInfluence.ONE, 0);
            llmPlayer.getLLMConfig()
                    .apiEndpoint("http://localhost:11434/v1/chat/completions")
                    .model("deepseek-v4-flash:cloud")
                    .maxTokens(65536)
                    .timeoutSeconds(120)
                    .deckAssessmentEnabled(false)
                    .verboseLogging(false);
            llmPlayer.reconnect();
            // Stub AFTER reconnect (reconnect replaces the client wholesale).
            llmPlayer.injectTestClient(new OffFormatStubClient(llmPlayer.getLLMConfig()));
            TestPlayer testPlayer = new TestPlayer(llmPlayer);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    @Test
    public void correctedReplayRecordCarriesDescriptiveSummary() {
        // A castable spell in hand → buildActionList is non-empty → the priority
        // path actually consults the LLM (with only lands out, every window is
        // auto-pass and no exchange is ever recorded).
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 2);
        addCard(Zone.HAND, playerA, "Silvercoat Lion", 1);
        addCard(Zone.BATTLEFIELD, playerB, "Plains", 2);

        // chatAndRecord's stateIdx = gameStates.getSize()-1, and saveState only
        // appends when saveGame (replay-save mode) is on — without this the
        // fixture game records NO exchanges and the test asserts nothing.
        ((mage.game.GameImpl) currentGame).setSaveGame(true);

        // Stop at T2 upkeep: all of turn 1's windows have fired, including
        // PRECOMBAT_MAIN where the Lion is castable (stop at T1.UPKEEP and
        // every window is pass-only → auto-pass → no LLM call → no records).
        setStopAt(2, PhaseStep.UPKEEP);
        execute();

        LLMPlayer llm = (LLMPlayer) playerA.getComputerPlayer();
        Map<Integer, List<LLMExchangeRecord>> replayData = llm.getReplayLLMData();
        Assert.assertFalse("priority decision must have recorded replay exchanges", replayData.isEmpty());

        LLMExchangeRecord correctedRec = null;
        String literal = "format correction";
        for (List<LLMExchangeRecord> bucket : replayData.values()) {
            for (LLMExchangeRecord rec : bucket) {
                Assert.assertNotEquals("no replay record may carry the placeholder literal",
                        literal, rec.compactSummary);
                if (rec.promptType.endsWith("-corrected")) {
                    correctedRec = rec;
                }
            }
        }
        Assert.assertNotNull("a format-correction replay record must exist (stub answered off-format once)",
                correctedRec);
        Assert.assertTrue("corrected record must start with the descriptive summary, got: '"
                        + correctedRec.compactSummary + "'",
                correctedRec.compactSummary.startsWith("Retry of the "));
    }
}