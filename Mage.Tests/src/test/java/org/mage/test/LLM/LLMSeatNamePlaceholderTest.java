package org.mage.test.LLM;

import mage.player.llm.LLMPlayer;
import org.junit.Assert;
import org.junit.Test;

/**
 * Pins {@link LLMPlayer#isPlaceholderBotName(String)} — the classifier that
 * decides which seat names get replaced by a generated model-personality name
 * in useDeck() (web-UI AI seat names).
 *
 * Placeholders are ONLY the client-derived defaults: the desktop
 * "Computer"/"Computer <n>" and the web proxy's "AI-<index>". Everything else
 * (botgame names, user-typed seat names, harness names) must be preserved —
 * re-randomizing those reopens the name-collision issue.
 */
public class LLMSeatNamePlaceholderTest {

    @Test
    public void clientDerivedDefaultsArePlaceholders() {
        Assert.assertTrue("missing name", LLMPlayer.isPlaceholderBotName(null));
        Assert.assertTrue("empty name", LLMPlayer.isPlaceholderBotName(""));
        Assert.assertTrue("desktop 2-player default", LLMPlayer.isPlaceholderBotName("Computer"));
        Assert.assertTrue("desktop seat 2", LLMPlayer.isPlaceholderBotName("Computer 2"));
        Assert.assertTrue("desktop seat 10", LLMPlayer.isPlaceholderBotName("Computer 10"));
        Assert.assertTrue("web seat 0", LLMPlayer.isPlaceholderBotName("AI-0"));
        Assert.assertTrue("web seat 1", LLMPlayer.isPlaceholderBotName("AI-1"));
        Assert.assertTrue("web seat 12", LLMPlayer.isPlaceholderBotName("AI-12"));
    }

    @Test
    public void assignedNamesArePreserved() {
        // Test-harness / GUI-typed / botgame names carry identity.
        Assert.assertFalse("harness name", LLMPlayer.isPlaceholderBotName("PlayerA"));
        Assert.assertFalse("botgame generated name", LLMPlayer.isPlaceholderBotName("deepseek-v4-flash-Kestrel"));
        Assert.assertFalse("stock-AI botgame name", LLMPlayer.isPlaceholderBotName("AI-Bot-Thorn"));
        Assert.assertFalse("user-typed seat name", LLMPlayer.isPlaceholderBotName("Sir Bob's Bot"));
    }

    @Test
    public void nearMissesAreNotPlaceholders() {
        // Not the exact derived shapes — must not be renamed.
        Assert.assertFalse("lowercase", LLMPlayer.isPlaceholderBotName("computer"));
        Assert.assertFalse("suffix without space", LLMPlayer.isPlaceholderBotName("Computer2"));
        Assert.assertFalse("bare AI-", LLMPlayer.isPlaceholderBotName("AI-"));
        Assert.assertFalse("AI- with non-digit", LLMPlayer.isPlaceholderBotName("AI-x"));
        Assert.assertFalse("AI digit elsewhere", LLMPlayer.isPlaceholderBotName("AI-0-X"));
    }
}
