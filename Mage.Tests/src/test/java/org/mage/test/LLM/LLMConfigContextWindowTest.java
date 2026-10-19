package org.mage.test.LLM;

import mage.player.llm.LLMConfig;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for fetchModelContextWindow() behavior on non-Ollama
 * (custom provider) endpoints — LLMConfig.java.
 *
 * Contract:
 *  - Non-Ollama endpoint + explicitly-set context window: honored, no throw,
 *    no network call (detection is skipped).
 *  - Non-Ollama endpoint + no explicit window: Fail Fast — RuntimeException
 *    telling the user to set modelContextWindow.
 *  - The explicit flag survives the copy constructor (tournament sub-tables
 *    copy configs via LLMConfig(LLMConfig)).
 *
 * No network access required: both non-Ollama branches return before any
 * HTTP call, so these tests run on machines without Ollama.
 */
public class LLMConfigContextWindowTest {

    private static final String CUSTOM_ENDPOINT = "https://api.example.com/v1/chat/completions";

    @Test
    public void nonOllamaEndpointWithoutExplicitWindowFailsFast() {
        LLMConfig config = new LLMConfig()
                .apiEndpoint(CUSTOM_ENDPOINT)
                .model("some-remote-model");
        // modelContextWindow left at default — explicit flag unset
        try {
            config.fetchModelContextWindow();
            Assert.fail("expected RuntimeException for non-Ollama endpoint without explicit context window");
        } catch (RuntimeException e) {
            Assert.assertTrue("message should mention the endpoint: " + e.getMessage(),
                    e.getMessage().contains(CUSTOM_ENDPOINT));
            Assert.assertTrue("message should tell the user how to fix it: " + e.getMessage(),
                    e.getMessage().contains("xmage.llm.model_context_window"));
        }
    }

    @Test
    public void nonOllamaEndpointWithExplicitWindowIsHonored() {
        LLMConfig config = new LLMConfig()
                .apiEndpoint(CUSTOM_ENDPOINT)
                .model("some-remote-model")
                .modelContextWindow(200000);
        // Must not throw and must not attempt detection (no Ollama to probe)
        config.fetchModelContextWindow();
        Assert.assertEquals("explicit context window must be retained", 200000, config.getModelContextWindow());
    }

    @Test
    public void explicitFlagSurvivesCopyConstructor() {
        LLMConfig original = new LLMConfig()
                .apiEndpoint(CUSTOM_ENDPOINT)
                .model("some-remote-model")
                .modelContextWindow(128000);
        LLMConfig copy = new LLMConfig(original);
        // The copy must behave identically on a non-Ollama endpoint: honored,
        // no Fail Fast crash — otherwise tournament sub-table players die.
        copy.fetchModelContextWindow();
        Assert.assertEquals(128000, copy.getModelContextWindow());
    }

    @Test
    public void copiedDefaultConfigStillFailsFastOnNonOllama() {
        // A config that never had an explicit window must keep failing after a
        // copy — the copy constructor must not accidentally mark it explicit.
        LLMConfig original = new LLMConfig()
                .apiEndpoint(CUSTOM_ENDPOINT)
                .model("some-remote-model");
        LLMConfig copy = new LLMConfig(original);
        try {
            copy.fetchModelContextWindow();
            Assert.fail("expected RuntimeException: copy of default config must still Fail Fast");
        } catch (RuntimeException e) {
            Assert.assertTrue(e.getMessage().contains("modelContextWindow"));
        }
    }
}