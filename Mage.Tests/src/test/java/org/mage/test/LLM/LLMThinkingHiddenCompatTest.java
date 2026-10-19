package org.mage.test.LLM;

import mage.player.llm.LLMClient;
import mage.player.llm.LLMConfig;
import mage.player.llm.LLMResponse;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Regression pin for hidden-thinking frontier models

 *
 * OpenAI and Anthropic never return raw chain of thought: the reasoning text is
 * either absent or a provider-written summary, while `usage.reasoning_tokens`
 * still reports (and bills) the reasoning that happened. Every thinking consumer
 * in the engine must therefore be null-safe AND correctly distinguish three
 * states:
 *
 *   1. real thinking text present           -> THINKING section with the text
 *   2. no thinking text, reasoning tokens>0 -> THINKING section marked
 *                                              "(reasoning hidden by provider: N tokens billed)"
 *   3. neither                              -> no THINKING section at all
 *
 * Case 2 is the new behaviour; cases 1 and 3 pin the pre-existing shapes so the
 * change cannot silently alter transcripts for our current (raw-CoT) models.
 *
 * No network access required.
 */
public class LLMThinkingHiddenCompatTest {

    private static final Method WRITE_TRANSCRIPT;

    static {
        try {
            WRITE_TRANSCRIPT = LLMClient.class.getDeclaredMethod(
                    "writeTranscript", String.class, String.class, LLMResponse.class);
            WRITE_TRANSCRIPT.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static LLMResponse resp(String thinking, String answer, Integer reasoningTokens) {
        LLMResponse.Usage usage = (reasoningTokens == null)
                ? null
                : new LLMResponse.Usage(100, 200, 300, reasoningTokens);
        return new LLMResponse(thinking, answer, usage);
    }

    /** Run writeTranscript against a temp file and return its text. */
    private static String transcriptOf(LLMResponse response) throws Exception {
        Path dir = Files.createTempDirectory("llm-transcript-pin");
        LLMConfig config = new LLMConfig()
                .apiEndpoint("http://localhost:11434/v1/chat/completions")
                .model("probe")
                .transcriptDir(dir.toString());
        LLMClient client = new LLMClient(config);
        client.resetConversationForTest("sys");
        // initTranscript is public and is exactly what production calls from
        // LLMPlayer.useDeck() — so the real formatting path is exercised.
        client.initTranscript("00000000-0000-0000-0000-00000000abcd", "pin");

        WRITE_TRANSCRIPT.invoke(client, "SYSPROMPT", "PROMPT", response);
        client.closeTranscript();

        Path gameDir = dir.resolve("game_0000abcd");
        Path file = null;
        java.io.File[] files = gameDir.toFile().listFiles();
        if (files != null && files.length > 0) {
            file = files[0].toPath();
        }
        Assert.assertNotNull("transcript file must have been created", file);
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(file.toFile()), java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        reader.close();
        String text = sb.toString();
        // best-effort cleanup
        if (files != null) {
            for (java.io.File f : files) { f.delete(); }
        }
        gameDir.toFile().delete();
        dir.toFile().delete();
        return text;
    }

    @Test
    public void realThinkingRendersUnmarked() throws Exception {
        String t = transcriptOf(resp("I should play the land first.", "3", 40));
        Assert.assertTrue("thinking text must appear", t.contains("I should play the land first."));
        Assert.assertFalse("a real thinking block must NOT carry the hidden marker",
                t.contains("reasoning hidden by provider"));
        Assert.assertTrue(t.contains("--- LLM ANSWER ---"));
    }

    @Test
    public void hiddenThinkingIsMarkedWithBilledTokens() throws Exception {
        String t = transcriptOf(resp(null, "3", 2048));
        Assert.assertTrue("hidden reasoning must still produce the section header",
                t.contains("--- LLM THINKING / REASONING ---"));
        Assert.assertTrue("the marker must carry the billed token count",
                t.contains("reasoning hidden by provider: 2048 tokens billed"));
        Assert.assertTrue(t.contains("--- LLM ANSWER ---"));
        Assert.assertTrue(t.contains("3"));
    }

    @Test
    public void emptyThinkingStringWithTokensIsAlsoMarked() throws Exception {
        // Some providers return "" rather than null for an absent reasoning field.
        String t = transcriptOf(resp("", "2", 512));
        Assert.assertTrue(t.contains("reasoning hidden by provider: 512 tokens billed"));
    }

    @Test
    public void noThinkingAndNoTokensOmitsSectionEntirely() throws Exception {
        String t = transcriptOf(resp(null, "1", null));
        Assert.assertFalse("absent thinking with no billed tokens must not create a section",
                t.contains("--- LLM THINKING / REASONING ---"));
        Assert.assertFalse(t.contains("reasoning hidden by provider"));
        Assert.assertTrue(t.contains("--- LLM ANSWER ---"));
    }

    @Test
    public void zeroReasoningTokensIsNotTreatedAsHidden() throws Exception {
        String t = transcriptOf(resp(null, "0", 0));
        Assert.assertFalse("0 billed tokens means nothing to report",
                t.contains("--- LLM THINKING / REASONING ---"));
    }

    /** Null thinking, null usage, empty answer — the full null case must not throw. */
    @Test
    public void fullyNullResponseDoesNotThrow() throws Exception {
        String t = transcriptOf(new LLMResponse(null, "", null));
        Assert.assertTrue(t.contains("--- LLM ANSWER ---"));
    }
}
