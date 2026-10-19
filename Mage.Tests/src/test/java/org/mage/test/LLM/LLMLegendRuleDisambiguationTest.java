package org.mage.test.LLM;

import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.player.llm.LLMClient;
import mage.player.llm.LLMConfig;
import mage.player.llm.LLMResponse;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;
import org.mage.test.player.TestPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Verifies the legend-rule choice prompt actually disambiguates the copies
 * (TAPPED / SICK / counters / attachments) — the info the LLM needs to make
 * an informed keep decision. The disambiguation was added but had never
 * been exercised: zero legend-rule prompts in
 * ~40K archived transcripts. This test forces the scenario and asserts on
 * the PROMPT the LLM would see, captured via a stubbed client — no live
 * model call needed.
 *
 * Setup notes:
 * - The LLM seat is PlayerB (getFullSimulatedPlayers override), and both
 *   legendary copies are PlayerB's. Turn 1 is PlayerA's turn — PlayerA's
 *   untap step only untaps PlayerA's permanents, so PlayerB's pre-tapped
 *   copy STAYS tapped when the legend-rule SBA fires at upkeep. (With the
 *   copies on the active player, the untap step erases the TAPPED marker
 *   before the prompt is ever built — first draft of this test hit exactly
 *   that.)
 * - The stub answers "1" to every prompt, so the SBA resolves; assertions
 *   run against the captured prompt text.
 */
public class LLMLegendRuleDisambiguationTest extends CardTestPlayerBaseLLM {

    /** Captures every prompt received, answers "1". */
    public static class PromptCapturingClient extends LLMClient {
        public final List<String> prompts = new ArrayList<>();

        public PromptCapturingClient(LLMConfig config) {
            super(config);
        }

        @Override
        public LLMResponse chatConversation(String fullPrompt, String compactSummary,
                                             String promptType, Integer turn, String phaseStep,
                                             String effortOverride) {
            prompts.add(fullPrompt);
            return new LLMResponse("keep the untapped one", "1");
        }

        // Strict-format calls route through chatConversationWithStop with
        // stop:["\n"] — the stub must capture those too, else the prompt never
        // reaches `prompts` and the SBA assertion fails on an empty capture.
        @Override
        public LLMResponse chatConversationWithStop(String fullPrompt, String compactSummary,
                                                     String promptType, Integer turn, String phaseStep,
                                                     String effortOverride, String stopSequence) {
            prompts.add(fullPrompt);
            return new LLMResponse("keep the untapped one", "1");
        }
    }

    private PromptCapturingClient captureClient;

    @Override
    public List<String> getFullSimulatedPlayers() {
        // PlayerB is the LLM seat — see class javadoc for why (untap-step timing).
        return Arrays.asList("PlayerB");
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
            captureClient = new PromptCapturingClient(llmPlayer.getLLMConfig());
            llmPlayer.injectTestClient(captureClient);
            TestPlayer testPlayer = new TestPlayer(llmPlayer);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    @BeforeClass
    public static void checkLLMAvailable() {
        // Stub-only: no live model call happens. Construction no longer probes the
        // endpoint either — LLMPlayer.init() gates context-window detection on
        // isTestHarness() — so the old Ollama-reachability gate is gone.
        Assume.assumeFalse("LLM tests skipped (set -Dxmage.llm.skip=false to run)",
                Boolean.getBoolean("xmage.llm.skip"));
    }

    @Test
    public void testLegendRulePromptDisambiguatesCopies() {
        addCard(Zone.BATTLEFIELD, playerB, "Rhys the Exiled", 1);
        addCard(Zone.BATTLEFIELD, playerB, "Rhys the Exiled", 1, true);  // tapped copy

        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();

        Assert.assertNotNull("LLM player was never prompted", captureClient);
        String legendPrompt = findLegendPrompt();
        Assert.assertNotNull("No legend-rule prompt captured — SBA did not fire or prompt "
                + "took a different path. Captured prompts:\n" + String.join("\n---\n", captureClient.prompts),
                legendPrompt);

        // Both copies listed with the card name
        Assert.assertTrue("legend prompt missing card name:\n" + legendPrompt,
                legendPrompt.contains("Rhys the Exiled"));
        // The disambiguation marker must be present on exactly one copy
        Assert.assertTrue("legend prompt has no (TAPPED) marker — copies are "
                + "indistinguishable, the LLM cannot make an informed keep choice:\n" + legendPrompt,
                legendPrompt.contains("(TAPPED)"));
        // The keep-choice context must be present
        Assert.assertTrue("legend prompt missing LEGENDARY RULE header:\n" + legendPrompt,
                legendPrompt.contains("LEGENDARY RULE"));
        Assert.assertTrue("legend prompt missing 'ONE to keep' instruction:\n" + legendPrompt,
                legendPrompt.contains("ONE to keep"));
    }

    private String findLegendPrompt() {
        for (String p : captureClient.prompts) {
            if (p.contains("LEGENDARY RULE")) return p;
        }
        return null;
    }
}