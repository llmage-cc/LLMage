package org.mage.test.LLM;

import mage.constants.RangeOfInfluence;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;


/**
 * Regression test for the seat-endpoint key-exfiltration guard
 * (public-hosting security): a per-seat LLMPlayerConfig override that CHANGES
 * the endpoint while leaving apiKey blank must NOT carry the server's key to
 * the new endpoint. Before the fix, applyOverrides() only overwrote the key
 * when the override provided one — a blank key field silently kept the
 * server-configured secret and the seat then sent it as a Bearer header to the
 * (attacker-controlled) new endpoint.
 *
 * Contract:
 *  - override changes endpoint, apiKey blank  -> server key is CLEARED
 *  - override changes endpoint, apiKey set    -> override key wins
 *  - override does NOT change endpoint        -> server key is KEPT
 */
public class LLMEndpointKeyGuardTest {

    private static final String OLLAMA_ENDPOINT = "http://localhost:11434/v1/chat/completions";
    private static final String ATTACKER_ENDPOINT = "https://attacker.example.com/v1/chat/completions";
    private static final String SERVER_KEY = "server-secret-key";

    @BeforeClass
    public static void checkLLMAvailable() {
        // Stub-only: no live model call happens. Construction no longer probes the
        // endpoint either — LLMPlayer.init() gates context-window detection on
        // isTestHarness() — so the old Ollama-reachability gate is gone.
        Assume.assumeFalse("LLM tests skipped (set -Dxmage.llm.skip=false to run)",
                Boolean.getBoolean("xmage.llm.skip"));
    }

    private TestLLMPlayer newPlayerWithServerKey() {
        TestLLMPlayer p = new TestLLMPlayer("GuardBot", RangeOfInfluence.ONE, 0);
        p.getLLMConfig()
                .apiEndpoint(OLLAMA_ENDPOINT)
                .model("deepseek-v4-flash:cloud")
                .apiKey(SERVER_KEY)
                // Explicit window: applyOverrides() re-detects when the endpoint
                // changes, and detection against the fake ATTACKER_ENDPOINT would
                // fail the test for the wrong reason. An explicit window makes
                // detection a no-op (detectFromOpenAIModels early-returns).
                .modelContextWindow(1048576);
        p.reconnect();
        return p;
    }

    @Test
    public void endpointChangeWithBlankKeyClearsServerKey() {
        TestLLMPlayer p = newPlayerWithServerKey();
        Assert.assertEquals(SERVER_KEY, p.getLLMConfig().getApiKey());

        mage.players.LLMPlayerConfig overrides = new mage.players.LLMPlayerConfig();
        overrides.setApiEndpoint(ATTACKER_ENDPOINT);
        // apiKey deliberately NOT set — the exfiltration scenario

        p.applyOverrides(overrides);

        Assert.assertEquals(ATTACKER_ENDPOINT, p.getLLMConfig().getApiEndpoint());
        Assert.assertEquals("server key must NOT follow the seat to a changed endpoint",
                "", p.getLLMConfig().getApiKey());
    }

    @Test
    public void endpointChangeWithProvidedKeyUsesOverrideKey() {
        TestLLMPlayer p = newPlayerWithServerKey();

        mage.players.LLMPlayerConfig overrides = new mage.players.LLMPlayerConfig();
        overrides.setApiEndpoint(ATTACKER_ENDPOINT);
        overrides.setApiKey("user-provided-key");

        p.applyOverrides(overrides);

        Assert.assertEquals("user-provided-key", p.getLLMConfig().getApiKey());
        Assert.assertEquals(ATTACKER_ENDPOINT, p.getLLMConfig().getApiEndpoint());
    }

    @Test
    public void unchangedEndpointKeepsServerKey() {
        TestLLMPlayer p = newPlayerWithServerKey();

        mage.players.LLMPlayerConfig overrides = new mage.players.LLMPlayerConfig();
        // Same endpoint value — must NOT clear the key
        overrides.setApiEndpoint(OLLAMA_ENDPOINT);
        overrides.setTemperatureEnabled(true);

        p.applyOverrides(overrides);

        Assert.assertEquals("unchanged endpoint must keep the server key",
                SERVER_KEY, p.getLLMConfig().getApiKey());
        Assert.assertEquals(OLLAMA_ENDPOINT, p.getLLMConfig().getApiEndpoint());
    }
}