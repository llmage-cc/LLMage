package org.mage.test.LLM;

import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.player.llm.LLMClient;
import mage.player.llm.LLMConfig;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;
import org.mage.test.player.TestPlayer;

import java.util.Arrays;
import java.util.List;

/**
 * Verifies the bot-name collision fix (game-log name attribution problem): when
 * two LLM bots in one game would auto-generate names from the personality
 * pool, the second pick must exclude names already in use by other seats.
 * Before the fix, both bots could randomly draw the same personality suffix
 * (two "deepseek-v4-flash-thorn"), making the game log attribute every
 * action to one indistinguishable name.
 *
 * Both seats run as LLM players with stubbed clients (answering "0"), so no
 * live model calls happen. Asserts the two seats end up with DIFFERENT names.
 */
public class LLMBotNameCollisionTest extends CardTestPlayerBaseLLM {

    @BeforeClass
    public static void checkLLMAvailable() {
        // Stub-only: no live model call happens. Construction no longer probes the
        // endpoint either — LLMPlayer.init() gates context-window detection on
        // isTestHarness() — so the old Ollama-reachability gate is gone.
        Assume.assumeFalse("LLM tests skipped (set -Dxmage.llm.skip=false to run)",
                Boolean.getBoolean("xmage.llm.skip"));
    }

    @Override
    public List<String> getFullSimulatedPlayers() {
        return Arrays.asList("PlayerA", "PlayerB");
    }

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if (getFullSimulatedPlayers().contains(name)) {
            TestLLMPlayer llmPlayer = new TestLLMPlayer(name, RangeOfInfluence.ONE, 0);
            llmPlayer.getLLMConfig()
                    .apiEndpoint("http://localhost:11434/v1/chat/completions")
                    .model("deepseek-v4-flash:cloud")  // fixture policy: flash, not pro (expensive)
                    .maxTokens(65536)
                    .timeoutSeconds(120);
            llmPlayer.reconnect();
            // Inject AFTER reconnect — reconnect() replaces the client wholesale.
            LLMClient stub = new NoOpLLMClient(llmPlayer.getLLMConfig());
            llmPlayer.injectTestClient(stub);
            TestPlayer testPlayer = new TestPlayer(llmPlayer);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    /** Answers "0" to everything. */
    static class NoOpLLMClient extends LLMClient {
        NoOpLLMClient(LLMConfig config) { super(config); }

        @Override
        public mage.player.llm.LLMResponse chatConversation(String fullPrompt, String compactSummary,
                                                            String promptType, Integer turn, String phaseStep,
                                                            String effortOverride) {
            return new mage.player.llm.LLMResponse("stub", "0");
        }
    }

    @Test
    public void testTwoLLMBotsGetDistinctNames() {
        addCard(Zone.HAND, playerA, "Plains", 1);
        addCard(Zone.HAND, playerB, "Plains", 1);
        setStopAt(2, PhaseStep.END_TURN);
        execute();

        String nameA = playerA.getName();
        String nameB = playerB.getName();
        Assert.assertNotEquals("Both LLM seats ended up with the same name (" + nameA
                        + ") — game log would attribute all actions to one indistinguishable player",
                nameA, nameB);
    }
}