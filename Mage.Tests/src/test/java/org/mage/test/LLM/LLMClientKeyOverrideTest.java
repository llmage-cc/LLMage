package org.mage.test.LLM;

import mage.constants.RangeOfInfluence;
import mage.player.llm.LLMPlayer;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;

/**
 * Client-key bridge seat-config guards.
 *
 * Contract (LLMPlayer.applyOverrides + init gating):
 *  - useClientKey=true clears any lingering server apiKey (leak channel).
 *  - useClientKey=true without a clientKeyTag throws Fail Fast (the browser
 *    could not look up which key to use at prompt time).
 *  - useClientKey=true requires an explicit modelContextWindow (the server
 *    cannot auto-detect without a key).
 *  - useClientKey=false leaves the server-key path untouched.
 */
public class LLMClientKeyOverrideTest {

    private static final String CUSTOM_ENDPOINT = "https://api.example.com/v1/chat/completions";

    @BeforeClass
    public static void checkLLMAvailable() {
        Assume.assumeFalse("LLM tests skipped (set -Dxmage.llm.skip=false to run)",
                Boolean.getBoolean("xmage.llm.skip"));
    }

    private TestLLMPlayer newPlayer() {
        TestLLMPlayer p = new TestLLMPlayer("ClientKeyBot", RangeOfInfluence.ONE, 0);
        p.getLLMConfig()
                .apiEndpoint(CUSTOM_ENDPOINT)
                .model("glm-5.3-flash");
        p.reconnect();
        return p;
    }

    @Test
    public void clientKeyClearsServerKeyAndAppliesTag() {
        TestLLMPlayer p = newPlayer();
        p.getLLMConfig().apiKey("server-secret-should-vanish");

        mage.players.LLMPlayerConfig overrides = new mage.players.LLMPlayerConfig();
        overrides.setUseClientKey(true);
        overrides.setClientKeyTag("k-test-tag");
        overrides.setModelContextWindow(131072);
        p.applyOverrides(overrides);

        Assert.assertTrue("useClientKey must be applied", p.getLLMConfig().isUseClientKey());
        Assert.assertEquals("clientKeyTag must be applied", "k-test-tag",
                p.getLLMConfig().getClientKeyTag());
        Assert.assertEquals("server key must be cleared on client-key seats",
                "", p.getLLMConfig().getApiKey());
    }

    @Test
    public void clientKeyWithoutTagFailsFast() {
        TestLLMPlayer p = newPlayer();
        mage.players.LLMPlayerConfig overrides = new mage.players.LLMPlayerConfig();
        overrides.setUseClientKey(true);
        overrides.setModelContextWindow(131072);
        try {
            p.applyOverrides(overrides);
            Assert.fail("client-key seat without clientKeyTag must throw");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("clientKeyTag"));
        }
    }

    @Test
    public void serverKeySeatUnchanged() {
        TestLLMPlayer p = newPlayer();
        p.getLLMConfig().apiKey("server-key-stays");
        mage.players.LLMPlayerConfig overrides = new mage.players.LLMPlayerConfig();
        overrides.setUseClientKey(false);
        overrides.setClientKeyTag("k-should-not-apply");
        p.applyOverrides(overrides);

        Assert.assertFalse("useClientKey=false must not enable browser mode",
                p.getLLMConfig().isUseClientKey());
        Assert.assertEquals("server key must survive when client-key is off",
                "server-key-stays", p.getLLMConfig().getApiKey());
        Assert.assertNull("tag must not be applied on server-key seats",
                p.getLLMConfig().getClientKeyTag());
    }
}