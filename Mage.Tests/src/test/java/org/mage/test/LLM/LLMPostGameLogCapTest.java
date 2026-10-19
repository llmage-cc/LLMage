package org.mage.test.LLM;

import mage.player.llm.LLMPlayer;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for LLMPlayer.capGameLogEntries — the post-game reflection prompt
 * caps the COMPLETE GAME LOG section at head+tail chars (whole entries only,
 * never split, explicit truncation marker).
 *
 * The helper is package-private static, so it is invoked via reflection
 * (same pattern as LLMHandDedupTest / LLMManaSourcePromptTest).
 */
public class LLMPostGameLogCapTest {

    private static final int HEAD = 10_000;
    private static final int TAIL = 30_000;

    @SuppressWarnings("unchecked")
    private static List<String> cap(List<String> entries, int head, int tail) throws Exception {
        Method m = LLMPlayer.class.getDeclaredMethod("capGameLogEntries", List.class, int.class, int.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(null, entries, head, tail);
    }

    private static List<String> entriesOf(int count, int entryLen) {
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            StringBuilder sb = new StringBuilder(entryLen);
            sb.append("entry-").append(i).append(":");
            while (sb.length() < entryLen) sb.append('x');
            if (sb.length() > entryLen) sb.setLength(entryLen);
            out.add(sb.toString());
        }
        return out;
    }

    private static String repeat(char c, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(c);
        return sb.toString();
    }

    @Test
    public void underBudgetReturnsInputUnchanged() throws Exception {
        List<String> input = new ArrayList<>();
        input.add("Turn 1: mulligan to 6");
        input.add("Turn 2: Forest, play Silvercoat Lion");
        input.add("Turn 3: attack for 2");

        List<String> result = cap(input, HEAD, TAIL);

        Assert.assertEquals(input, result);
        Assert.assertEquals(3, result.size());
    }

    @Test
    public void overBudgetKeepsHeadAndTailWithMarker() throws Exception {
        int n = 60;
        List<String> input = entriesOf(n, 2000);
        long total = 0;
        for (String s : input) total += s.length() + 1;
        Assert.assertTrue("test setup: input must exceed the budget", total > HEAD + TAIL);

        List<String> result = cap(input, HEAD, TAIL);

        Assert.assertTrue("result must be smaller than input", result.size() < input.size());
        Assert.assertEquals("first entry must be kept", input.get(0), result.get(0));
        Assert.assertEquals("last entry must be kept", input.get(n - 1), result.get(result.size() - 1));

        String marker = null;
        for (String s : result) {
            if (s.startsWith("... [game log truncated:")) {
                Assert.assertNull("exactly one marker expected", marker);
                marker = s;
            } else {
                Assert.assertTrue("non-marker elements must be verbatim input entries (no splitting)",
                        input.contains(s));
            }
        }
        Assert.assertNotNull("truncation marker expected", marker);
        Assert.assertTrue("marker must mention omitted entries", marker.contains("entries omitted"));
        Assert.assertTrue("marker must sit between head and tail entries",
                result.indexOf(marker) > 0 && result.indexOf(marker) < result.size() - 1);
    }

    @Test
    public void exactBoundaryReturnsInputUnchanged() throws Exception {
        // entry length + 1 (appended "\n") sums exactly to HEAD + TAIL
        List<String> input = new ArrayList<>();
        input.add(repeat('a', HEAD - 1));
        input.add(repeat('b', TAIL - 1));
        long total = 0;
        for (String s : input) total += s.length() + 1;
        Assert.assertEquals("test setup: boundary must be exact", HEAD + TAIL, total);

        List<String> result = cap(input, HEAD, TAIL);

        Assert.assertEquals(input, result);
    }
}
