package org.mage.test.LLM;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Integration tests for LLMPlayer — validates the LLM can make basic
 * game actions through the XMage test framework.
 *
 * Run with: mvn test -pl Mage.Tests -Dtest=LLMBasicTest -Dxmage.llm.verbose=true
 *
 * Endpoint/model/key are property-driven (see CardTestPlayerBaseLLM):
 *   -Dxmage.llm.endpoint=... -Dxmage.llm.model=... -Dxmage.llm.key=...
 *   (-Dxmage.llm.reasoningeffort=... for models whose ladder excludes the
 *    default "max", e.g. deepseek-v4.1-flash takes low/high)
 * Defaults to local Ollama with deepseek-v4-flash:cloud when unset.
 *   ollama pull deepseek-v4-flash:cloud && ollama serve
 *
 * @author nox
 */
public class LLMBasicTest extends CardTestPlayerBaseLLM {

    /**
     * Skip tests if no LLM endpoint is configured. These integration tests
     * require a live Ollama (or compatible) server. Set -Dxmage.llm.skip=true
     * to skip in CI environments without an LLM backend.
     */
    @BeforeClass
    public static void checkLLMAvailable() {
        Assume.assumeFalse("LLM tests skipped (set -Dxmage.llm.skip=false to run)",
                Boolean.getBoolean("xmage.llm.skip"));
        // A live endpoint configured via -Dxmage.llm.endpoint/model satisfies the
        // requirement directly; otherwise these tests are the ONLY ones that make
        // real LLM calls (every other LLM test stubs the client), so require a
        // reachable local Ollama instead of failing with "Connection refused"
        // after 5 retries on hosts that have none.
        boolean liveConfigured = !System.getProperty("xmage.llm.endpoint", "").isEmpty()
                && !System.getProperty("xmage.llm.model", "").isEmpty();
        if (liveConfigured) {
            return;
        }
        try {
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new java.net.URL("http://localhost:11434").openConnection();
            conn.setConnectTimeout(2000);
            conn.connect();
            conn.disconnect();
        } catch (java.io.IOException e) {
            Assume.assumeNoException(
                    "No live LLM endpoint configured and Ollama not reachable at localhost:11434 — skipping", e);
        }
    }

    /**
     * Simplest possible test: LLM player has two Plains and a Silvercoat Lion
     * in hand. It should cast the Lion.
     */
    @Test
    public void testCastCreatureWhenManaAvailable() {
        addCard(Zone.HAND, playerA, "Silvercoat Lion", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 2);

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Silvercoat Lion", 1);
        assertTappedCount("Plains", true, 2);
    }

    /**
     * LLM player should play a land when one is in hand.
     */
    @Test
    public void testPlayLand() {
        addCard(Zone.HAND, playerA, "Mountain", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 2);

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Mountain", 1);
        assertPermanentCount(playerA, "Plains", 2);
    }

    /**
     * LLM player has multiple options — should take at least one action.
     */
    @Test
    public void testTakesAnyAction() {
        addCard(Zone.HAND, playerA, "Silvercoat Lion", 1);
        addCard(Zone.HAND, playerA, "Forest", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 2);

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        // The LLM should have done something — played the land, cast the creature,
        // or both (both is correct play). Assert at least one action happened.
        int permanents = currentGame.getBattlefield().getAllActivePermanents(playerA.getId()).size();
        Assert.assertTrue("LLM took no action — expected at least a land or creature in play (found "
                + permanents + " permanents)", permanents >= 3);
    }
}
