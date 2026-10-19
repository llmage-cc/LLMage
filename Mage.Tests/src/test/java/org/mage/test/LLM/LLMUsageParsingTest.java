package org.mage.test.LLM;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.player.llm.LLMClient;
import mage.player.llm.LLMResponse;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;

/**
 * Unit tests for LLMResponse.Usage parsing (LLMClient.parseUsage) and the
 * stream_options.include_usage request flag.
 *
 * Contract:
 *  - A well-formed `usage` object parses into prompt/completion/total tokens,
 *    with reasoning tokens read from completion_tokens_details when present.
 *  - A missing `usage` key yields null usage (some providers/stream configs
 *    omit it — that's tolerated).
 *  - A `usage` object with NO recognized token fields is malformed and must
 *    throw (Fail Fast — we rely on these numbers for cost accounting).
 *
 * No network access required.
 */
public class LLMUsageParsingTest {

    private static LLMResponse.Usage parseUsage(String json) throws Exception {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Method m = LLMClient.class.getDeclaredMethod("parseUsage", JsonObject.class);
        m.setAccessible(true);
        return (LLMResponse.Usage) m.invoke(null, root);
    }

    @Test
    public void wellFormedUsageParses() throws Exception {
        LLMResponse.Usage u = parseUsage(
                "{\"choices\":[], \"usage\":{\"prompt_tokens\":1234,\"completion_tokens\":56,\"total_tokens\":1290}}");
        Assert.assertNotNull(u);
        Assert.assertEquals(Integer.valueOf(1234), u.promptTokens);
        Assert.assertEquals(Integer.valueOf(56), u.completionTokens);
        Assert.assertEquals(Integer.valueOf(1290), u.totalTokens);
        Assert.assertNull(u.reasoningTokens);
    }

    @Test
    public void reasoningTokensParsedFromDetails() throws Exception {
        LLMResponse.Usage u = parseUsage(
                "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":200,\"total_tokens\":210,"
                        + "\"completion_tokens_details\":{\"reasoning_tokens\":180}}}");
        Assert.assertNotNull(u);
        Assert.assertEquals(Integer.valueOf(180), u.reasoningTokens);
    }

    @Test
    public void missingUsageObjectReturnsNull() throws Exception {
        LLMResponse.Usage u = parseUsage("{\"choices\":[]}");
        Assert.assertNull("absent usage object is tolerated (null)", u);
    }

    @Test
    public void nullUsageObjectReturnsNull() throws Exception {
        LLMResponse.Usage u = parseUsage("{\"usage\":null}");
        Assert.assertNull(u);
    }

    @Test
    public void emptyUsageObjectFailsFast() {
        try {
            parseUsage("{\"usage\":{}}");
            Assert.fail("expected RuntimeException for usage object with no token fields");
        } catch (Exception e) {
            Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException)
                    ? e.getCause() : e;
            Assert.assertTrue("expected RuntimeException, got: " + cause,
                    cause instanceof RuntimeException);
            Assert.assertTrue(cause.getMessage().contains("usage"));
        }
    }

    @Test
    public void usageToStringIncludesCounts() throws Exception {
        LLMResponse.Usage u = parseUsage(
                "{\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":8,\"total_tokens\":15}}");
        String s = u.toString();
        Assert.assertTrue(s.contains("prompt=7"));
        Assert.assertTrue(s.contains("completion=8"));
        Assert.assertTrue(s.contains("total=15"));
    }
}
