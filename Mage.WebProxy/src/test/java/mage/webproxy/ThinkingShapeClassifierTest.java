package mage.webproxy;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for the setup-time thinking-shape probe.

 *
 * {@link ThinkingShapeClassifier} must mirror the ENGINE's request-builder gate
 * — {@code LLMClient.emitsThinkingObject()} (commit 67af8b38f4): the
 * thinking{...} object goes ONLY to Ollama-classified endpoints ("ollama" in
 * the URL or port 11434) or when the explicit budget dialect is configured;
 * everywhere else the effort dialect sends bare reasoning_effort. A warning
 * that disagreed with the engine would either cry wolf on safe seats or stay
 * silent on fatal ones, so both sides of the split are pinned here.
 *
 * Measured ground truth: glm-5.3-flash on opencode zen/go
 * returns HTTP 400 "unknown field \"thinking\"" for the thinking object
 * (game-fatal at mulligan) and accepts bare reasoning_effort.
 */
public class ThinkingShapeClassifierTest {

    @After
    public void clearProps() {
        System.clearProperty("xmage.llm.thinking_dialect");
    }

    // ── isOllamaEndpoint: the literal heuristic the engine uses ──

    @Test
    public void ollamaBySubstring() {
        Assert.assertTrue(ThinkingShapeClassifier.isOllamaEndpoint("https://ollama.com/v1/chat/completions"));
        Assert.assertTrue(ThinkingShapeClassifier.isOllamaEndpoint("http://localhost:11434/v1/chat/completions"));
        Assert.assertTrue(ThinkingShapeClassifier.isOllamaEndpoint("https://my-ollama-mirror.example.com/v1"));
    }

    @Test
    public void ollamaByPort() {
        Assert.assertTrue(ThinkingShapeClassifier.isOllamaEndpoint("http://127.0.0.1:11434/api"));
        // the port number may appear anywhere in the URL string — same as the engine
        Assert.assertTrue(ThinkingShapeClassifier.isOllamaEndpoint("https://proxy.example.com/11434/v1/chat/completions"));
    }

    @Test
    public void nonOllamaEndpointsAreNotOllamaClassified() {
        Assert.assertFalse(ThinkingShapeClassifier.isOllamaEndpoint("https://opencode.ai/zen/go/v1/chat/completions"));
        Assert.assertFalse(ThinkingShapeClassifier.isOllamaEndpoint("https://api.openai.com/v1/chat/completions"));
        Assert.assertFalse(ThinkingShapeClassifier.isOllamaEndpoint(""));
        Assert.assertFalse(ThinkingShapeClassifier.isOllamaEndpoint(null));
    }

    // ── classify: the same truth table as LLMClient.emitsThinkingObject ──

    @Test
    public void thinkingDisabledIsAlwaysOff() {
        Assert.assertEquals(ThinkingShapeClassifier.Shape.OFF,
                ThinkingShapeClassifier.classify("https://api.openai.com/v1/chat/completions", "effort", false));
        Assert.assertEquals(ThinkingShapeClassifier.Shape.OFF,
                ThinkingShapeClassifier.classify("https://ollama.com/v1/chat/completions", "budget", false));
        // disabled wins over the dialect AND the endpoint classification
        Assert.assertEquals(ThinkingShapeClassifier.Shape.OFF,
                ThinkingShapeClassifier.classify("http://localhost:11434/v1/chat/completions", null, false));
    }

    @Test
    public void budgetDialectAlwaysEmitsTheObject() {
        Assert.assertEquals(ThinkingShapeClassifier.Shape.THINKING_OBJECT,
                ThinkingShapeClassifier.classify("https://opencode.ai/zen/go/v1/chat/completions", "budget", true));
        Assert.assertEquals(ThinkingShapeClassifier.Shape.THINKING_OBJECT,
                ThinkingShapeClassifier.classify("", "budget", true));
    }

    @Test
    public void ollamaClassifiedEndpointGetsTheObject() {
        Assert.assertEquals(ThinkingShapeClassifier.Shape.THINKING_OBJECT,
                ThinkingShapeClassifier.classify("https://ollama.com/v1/chat/completions", "effort", true));
        Assert.assertEquals(ThinkingShapeClassifier.Shape.THINKING_OBJECT,
                ThinkingShapeClassifier.classify("http://localhost:11434/v1/chat/completions", null, true));
    }

    @Test
    public void nonOllamaEndpointWithEffortDialectSendsBareEffort() {
        // the incident endpoint: glm-5.3-flash via opencode zen/go
        Assert.assertEquals(ThinkingShapeClassifier.Shape.BARE_EFFORT,
                ThinkingShapeClassifier.classify("https://opencode.ai/zen/go/v1/chat/completions", "effort", true));
        // null dialect = the engine default "effort" — the web seat case
        Assert.assertEquals(ThinkingShapeClassifier.Shape.BARE_EFFORT,
                ThinkingShapeClassifier.classify("https://opencode.ai/zen/go/v1/chat/completions", null, true));
        // unknown dialect strings fall through to the endpoint classification,
        // exactly like the engine (only "budget" flips to object-based)
        Assert.assertEquals(ThinkingShapeClassifier.Shape.BARE_EFFORT,
                ThinkingShapeClassifier.classify("https://api.openai.com/v1/chat/completions", "weird", true));
    }

    // ── isStrictShape: the warning predicate ──

    @Test
    public void strictShapeIsBareEffortOnly() {
        Assert.assertTrue(ThinkingShapeClassifier.isStrictShape(
                "https://opencode.ai/zen/go/v1/chat/completions", "effort", true));
        Assert.assertFalse(ThinkingShapeClassifier.isStrictShape(
                "https://opencode.ai/zen/go/v1/chat/completions", "effort", false));
        Assert.assertFalse(ThinkingShapeClassifier.isStrictShape(
                "https://ollama.com/v1/chat/completions", "effort", true));
        Assert.assertFalse(ThinkingShapeClassifier.isStrictShape(
                "https://opencode.ai/zen/go/v1/chat/completions", "budget", true));
    }

    // ── strictWarning: the text the seat form shows ──

    @Test
    public void strictWarningNamesTheSeatAndTheFix() {
        String w = ThinkingShapeClassifier.strictWarning("AI-1", "glm-5.3-flash");
        Assert.assertTrue("seat name in warning", w.contains("AI-1"));
        Assert.assertTrue("model in warning", w.contains("(glm-5.3-flash)"));
        Assert.assertTrue("shape named", w.contains("reasoning_effort"));
        Assert.assertTrue("fix named", w.contains("Thinking Enabled"));
        Assert.assertTrue("failure mode named", w.contains("mulligan"));
    }

    @Test
    public void strictWarningWorksWithoutAModel() {
        String w = ThinkingShapeClassifier.strictWarning("AI-0", null);
        Assert.assertTrue("seat name in warning", w.contains("AI-0"));
        Assert.assertFalse("no null model placeholder", w.contains("(null)"));
        String w2 = ThinkingShapeClassifier.strictWarning("AI-0", "");
        Assert.assertTrue("seat name in warning (empty model)", w2.contains("AI-0"));
    }

    // ── serverDefaultThinkingDialect: the seat dialect the proxy classifies with ──

    @Test
    public void dialectPropertyIsHonored() {
        System.setProperty("xmage.llm.thinking_dialect", "budget");
        Assert.assertEquals("budget", WebProxySession.serverDefaultThinkingDialect());
    }

    @Test
    public void unsetDialectFallsBackToEffort() {
        // the engine default (LLMConfig.thinkingDialect = "effort") — no silent
        // empty-string passthrough: unset/empty both mean "effort"
        System.clearProperty("xmage.llm.thinking_dialect");
        Assert.assertEquals("effort", WebProxySession.serverDefaultThinkingDialect());
        System.setProperty("xmage.llm.thinking_dialect", "");
        Assert.assertEquals("effort", WebProxySession.serverDefaultThinkingDialect());
    }
}