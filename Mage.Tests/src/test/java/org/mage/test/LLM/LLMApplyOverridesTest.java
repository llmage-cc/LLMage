package org.mage.test.LLM;

import mage.player.llm.LLMConfig;
import mage.player.llm.LLMPlayer;
import mage.constants.RangeOfInfluence;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;


/**
 * Regression test for the GUI table-join failure: joining a table whose LLM
 * seat carries config overrides used to crash with
 * "LLMPlayer.applyOverrides() failed via reflection" when the overrides did
 * NOT change model/endpoint — applyOverrides() re-ran
 * fetchModelContextWindow() unconditionally, and on a non-Ollama endpoint
 * without an explicit context window that call throws (Fail Fast), killing
 * the join even though the player's constructor had already detected a valid
 * window for this exact model/endpoint pair.
 *
 * Contract:
 *  - applyOverrides with unchanged model/endpoint must NOT re-detect (no
 *    throw on a non-Ollama endpoint without explicit window, since the
 *    constructor's detection result stays valid).
 *  - applyOverrides with a CHANGED model/endpoint must re-detect (Fail Fast
 *    preserved for genuinely new, undetected configs).
 *  - The window from the constructor is retained in the unchanged case.
 */
public class LLMApplyOverridesTest {

    private static final String CUSTOM_ENDPOINT = "https://api.example.com/v1/chat/completions";
    private static final String OLLAMA_ENDPOINT = "http://localhost:11434/v1/chat/completions";

    @BeforeClass
    public static void checkLLMAvailable() {
        // Stub-only: no live model call happens. Construction no longer probes the
        // endpoint either — LLMPlayer.init() gates context-window detection on
        // isTestHarness() — so the old Ollama-reachability gate is gone.
        Assume.assumeFalse("LLM tests skipped (set -Dxmage.llm.skip=false to run)",
                Boolean.getBoolean("xmage.llm.skip"));
    }

    private TestLLMPlayer newPlayer() {
        TestLLMPlayer p = new TestLLMPlayer("OverrideBot", RangeOfInfluence.ONE, 0);
        // Constructor detects the context window from the default (Ollama) endpoint.
        p.getLLMConfig()
                .apiEndpoint(OLLAMA_ENDPOINT)
                .model("deepseek-v4-flash:cloud");
        p.reconnect();
        return p;
    }

    @Test
    public void unchangedEndpointDoesNotReDetectAndDoesNotThrow() {
        TestLLMPlayer p = newPlayer();
        int windowBefore = p.getLLMConfig().getModelContextWindow();

        mage.players.LLMPlayerConfig overrides = new mage.players.LLMPlayerConfig();
        // Only touches settings that don't affect detection — exactly the GUI
        // scenario that used to die in fetchModelContextWindow().
        overrides.setTemperatureEnabled(true);
        overrides.setTemperature(0.7);
        overrides.setVerboseLogging(true);

        // Must NOT throw (regression: unconditional re-detection threw on
        // non-Ollama endpoints without explicit window)
        p.applyOverrides(overrides);

        Assert.assertEquals("context window from constructor detection must be retained",
                windowBefore, p.getLLMConfig().getModelContextWindow());
    }

    @Test
    public void changedEndpointReDetects() {
        TestLLMPlayer p = newPlayer();

        mage.players.LLMPlayerConfig overrides = new mage.players.LLMPlayerConfig();
        overrides.setApiEndpoint(CUSTOM_ENDPOINT);
        overrides.setModelContextWindow(200000); // required: non-Ollama, explicit

        p.applyOverrides(overrides);

        Assert.assertEquals("endpoint override must be applied",
                CUSTOM_ENDPOINT, p.getLLMConfig().getApiEndpoint());
        Assert.assertEquals("explicit context window must survive re-detection skip logic",
                200000, p.getLLMConfig().getModelContextWindow());
    }

    @Test
    public void changedEndpointWithoutWindowFailsFast() {
        TestLLMPlayer p = newPlayer();

        mage.players.LLMPlayerConfig overrides = new mage.players.LLMPlayerConfig();
        overrides.setApiEndpoint(CUSTOM_ENDPOINT);
        // no modelContextWindow — Fail Fast expected

        try {
            p.applyOverrides(overrides);
            Assert.fail("expected Fail Fast RuntimeException for non-Ollama endpoint without explicit context window");
        } catch (RuntimeException e) {
            Assert.assertTrue("message should mention context window detection: " + e.getMessage(),
                    e.getMessage().contains("context window"));
        }
    }
}