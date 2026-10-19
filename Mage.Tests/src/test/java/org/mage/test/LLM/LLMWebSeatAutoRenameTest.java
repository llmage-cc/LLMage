package org.mage.test.LLM;

import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.player.llm.LLMClient;
import mage.player.llm.LLMConfig;
import mage.players.Personality;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;
import org.mage.test.player.TestPlayer;

import java.util.Arrays;
import java.util.List;

/**
 * Regression test for the web-UI AI seat names.
 *
 * WebProxySession.handleCreateTable / handleCreateTournament name AI seats
 * that carry no explicit name "AI-<index>". LLMPlayer.useDeck()'s name
 * auto-generation used to fire only for the exact desktop placeholder
 * "Computer" — so a web-created seat ("AI-0"/"AI-1") was treated as a
 * caller-assigned name and preserved: no model-personality name was generated
 * AND (because the personality section resolves through the seat's name) no
 * personality was injected. Every web-UI-launched bot played as
 * "AI-0"/"AI-1" (transcripts: "You are playing as: AI-0").
 *
 * Both seats run as LLM players with stubbed clients (no live model calls):
 * the assertions are that the harness's AI-0/AI-1 placeholders are REPLACED
 * by generated model-personality names and that the stubbed conversation
 * receives the personality section — the exact chain the web UI depends on.
 */
public class LLMWebSeatAutoRenameTest extends CardTestPlayerBaseLLM {

    private NoOpLLMClient stubA;
    private NoOpLLMClient stubB;
    private TestLLMPlayer llmA;
    private TestLLMPlayer llmB;

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
            // The web proxy's derived seat names for an unnamed AI seat pair
            // (WebProxySession: pt.isAI() ? "AI-" + i : username).
            String seatName = name.equals("PlayerA") ? "AI-0" : "AI-1";
            TestLLMPlayer llmPlayer = new TestLLMPlayer(seatName, RangeOfInfluence.ONE, 0);
            llmPlayer.getLLMConfig()
                    .apiEndpoint("http://localhost:11434/v1/chat/completions")
                    .model("deepseek-v4-flash:cloud")  // fixture policy: flash, not pro (expensive)
                    .maxTokens(65536)
                    .timeoutSeconds(120)
                    // keep the assessment prompt cached (TestLLMPlayer.chooseMulligan
                    // never calls ensureGamePlan, so it is never consumed) — this test
                    // asserts on the cached text built by useDeck().
                    .deckAssessmentEnabled(true);
            llmPlayer.reconnect();
            // Inject AFTER reconnect — reconnect() replaces the client wholesale.
            NoOpLLMClient stub = new NoOpLLMClient(llmPlayer.getLLMConfig());
            llmPlayer.injectTestClient(stub);
            if (name.equals("PlayerA")) {
                stubA = stub;
                llmA = llmPlayer;
            } else {
                stubB = stub;
                llmB = llmPlayer;
            }
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

    private static String modelShortFor(NoOpLLMClient stub) {
        return stub.getConfig().getModel().replaceAll("[:/].*", "");
    }

    private static void assertGeneratedBotName(String actual, String modelShort, String label) {
        Assert.assertNotEquals(label + " still has the web placeholder ("
                + actual + ") — no bot name was generated", "AI-0", actual);
        Assert.assertNotEquals(label + " still has the web placeholder ("
                + actual + ") — no bot name was generated", "AI-1", actual);
        Assert.assertTrue(label + " name '" + actual + "' is not model-prefixed ("
                        + modelShort + "-<Personality>)",
                actual.startsWith(modelShort + "-"));
        String suffix = actual.substring(actual.lastIndexOf('-') + 1);
        Assert.assertTrue(label + " name suffix '" + suffix
                + "' is not a registered personality name", Personality.isRegistered(suffix));
    }

    @Test
    public void webSeatPlaceholdersGetGeneratedNamesAndPersonality() {
        addCard(Zone.HAND, playerA, "Plains", 1);
        addCard(Zone.HAND, playerB, "Plains", 1);
        setStopAt(2, PhaseStep.END_TURN);
        execute();

        // useDeck() runs during player creation (GameImpl.addPlayer) — by now
        // both seats must carry generated names instead of the web placeholders.
        String nameA = playerA.getName();
        String nameB = playerB.getName();

        assertGeneratedBotName(nameA, modelShortFor(stubA), "seat A");
        assertGeneratedBotName(nameB, modelShortFor(stubB), "seat B");
        Assert.assertNotEquals("Both LLM seats ended up with the same name (" + nameA
                        + ") — game log would attribute all actions to one indistinguishable player",
                nameA, nameB);

        // The personality section resolves through the (renamed) seat name and
        // must have reached each seat's system prompt.
        String sysA = stubA.getConversationSystemPrompt();
        String sysB = stubB.getConversationSystemPrompt();
        Assert.assertNotNull("seat A conversation was never reset", sysA);
        Assert.assertNotNull("seat B conversation was never reset", sysB);
        Assert.assertTrue("no personality section injected for seat A (" + nameA + ")",
                sysA.contains("=== YOUR PERSONALITY ==="));
        Assert.assertTrue("no personality section injected for seat B (" + nameB + ")",
                sysB.contains("=== YOUR PERSONALITY ==="));
        // Sanity: the prompt is the real seeded deck prompt, not a stub artifact.
        Assert.assertTrue("seat A system prompt is missing the decklist section",
                sysA.contains("=== YOUR DECKLIST ==="));
    }

    /**
     * The cached pre-game deck-assessment prompt embeds
     * "You are playing as: &lt;name&gt;" and is built inside useDeck(). It must
     * be built AFTER the rename — building it before (the original code) had
     * the LLM's first call introduce itself under the web placeholder:
     * transcripts carried "You are playing as: AI-0".
     */
    @Test
    public void webSeatAssessmentPromptUsesGeneratedName() {
        addCard(Zone.HAND, playerA, "Plains", 1);
        addCard(Zone.HAND, playerB, "Plains", 1);
        setStopAt(2, PhaseStep.END_TURN);
        execute();

        for (int i = 0; i < 2; i++) {
            TestLLMPlayer llm = (i == 0) ? llmA : llmB;
            String label = (i == 0) ? "seat A" : "seat B";
            String prompt = llm.cachedAssessmentPromptForTest();
            Assert.assertNotNull(label + " assessment prompt was never built", prompt);
            int at = prompt.indexOf("You are playing as: ");
            Assert.assertTrue(label + " assessment prompt has no 'You are playing as:' line",
                    at >= 0);
            String announced = prompt.substring(at + "You are playing as: ".length())
                    .split("\\R", 2)[0].trim();
            Assert.assertNotEquals(label + " assessment prompt still announces the web placeholder ("
                    + announced + ")", "AI-0", announced);
            Assert.assertNotEquals(label + " assessment prompt still announces the web placeholder ("
                    + announced + ")", "AI-1", announced);
            Assert.assertTrue(label + " assessment prompt announces non-generated name '"
                            + announced + "' (expected " + llm.getName() + ")",
                    announced.equals(llm.getName()));
        }
    }
}
