package org.mage.test.LLM;

import mage.player.llm.LLMClient;
import mage.player.llm.LLMConfig;
import mage.player.llm.LLMResponse;
import mage.players.LLMPlayerConfig;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Locks the  defaults change ("Sep 26:" block):
 *
 *  - conversationMaxExchanges / conversationMaxHistoryChars /
 *    conversationThinkCharsPerMsg default to 0 = infinity (unlimited);
 *  - 0 means unlimited in the consumption paths (trim + thinking retention +
 *    the summarize count gate). Previously 0 meant "trim everything",
 *    "omit reasoning from history", and "force summarize by count" — so every
 *    test here is written to FAIL against the pre-change code;
 *  - legacy near-Integer.MAX_VALUE values (old .game saves, explicit configs)
 *    still mean unlimited and never overflow the `* 2` in trim/summarize;
 *  - summarizeKeepExchanges defaults to 5 (was 2);
 *  - negative values Fail-Fast at the setters.
 *
 * No network: the summarize tests exercise only early-return paths (a
 * regression re-opens the gate and dials the deliberately dead endpoint,
 * turning the test red instead of hanging).
 */
public class LLMConversationDefaultsTest {

    private static final String DEAD_ENDPOINT = "http://127.0.0.1:9/v1/chat/completions";

    private static final Method TRIM;
    private static final Method BUILD_ASSISTANT;
    private static final Method SUMMARIZE;

    static {
        try {
            TRIM = LLMClient.class.getDeclaredMethod("trimConversationHistory");
            TRIM.setAccessible(true);
            BUILD_ASSISTANT = LLMClient.class.getDeclaredMethod("buildAssistantMessage", LLMResponse.class);
            BUILD_ASSISTANT.setAccessible(true);
            SUMMARIZE = LLMClient.class.getDeclaredMethod("summarizeIfNeeded");
            SUMMARIZE.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<LLMClient.ConversationMessage> historyOf(LLMClient client) throws Exception {
        Field f = LLMClient.class.getDeclaredField("conversationHistory");
        f.setAccessible(true);
        return (List<LLMClient.ConversationMessage>) f.get(client);
    }

    private static void seedHistory(LLMClient client, int count, int charsPerMsg) throws Exception {
        StringBuilder sb = new StringBuilder(charsPerMsg);
        for (int i = 0; i < charsPerMsg; i++) sb.append('x');
        String content = sb.toString();
        List<LLMClient.ConversationMessage> history = historyOf(client);
        for (int i = 0; i < count; i++) {
            history.add(new LLMClient.ConversationMessage(i % 2 == 0 ? "user" : "assistant", content));
        }
    }

    private static int totalChars(LLMClient client) throws Exception {
        int total = 0;
        for (LLMClient.ConversationMessage m : historyOf(client)) total += m.estimatedChars();
        return total;
    }

    // ── defaults ──────────────────────────────────────────────────────────────

    @Test
    public void conversationLimitsDefaultToInfinityZero() {
        LLMConfig c = new LLMConfig();
        Assert.assertEquals("max exchanges must default to 0 = infinity", 0, c.getConversationMaxExchanges());
        Assert.assertEquals("max history chars must default to 0 = infinity", 0, c.getConversationMaxHistoryChars());
        Assert.assertEquals("think chars per msg must default to 0 = infinity", 0, c.getConversationThinkCharsPerMsg());
    }

    @Test
    public void summarizeKeepExchangesDefaultsToFive() {
        Assert.assertEquals("summarization must keep 5 exchanges (was 2)", 5, new LLMConfig().getSummarizeKeepExchanges());
    }

    @Test
    public void desktopDefaultsMirrorEngineDefaults() {
        LLMPlayerConfig d = LLMPlayerConfig.defaults();
        Assert.assertEquals("dialog defaults: max exchanges 0 = infinity", Integer.valueOf(0), d.getConversationMaxExchanges());
        Assert.assertEquals("dialog defaults: max history chars 0 = infinity", Integer.valueOf(0), d.getConversationMaxHistoryChars());
        Assert.assertEquals("dialog defaults: think chars per msg 0 = infinity", Integer.valueOf(0), d.getConversationThinkCharsPerMsg());
        Assert.assertEquals("dialog defaults: keep exchanges 5", Integer.valueOf(5), d.getSummarizeKeepExchanges());
    }

    // ── Fail-Fast on negatives ────────────────────────────────────────────────

    @Test
    public void negativeLimitsFailFast() {
        for (String which : new String[]{"exchanges", "historyChars", "thinkChars", "keep"}) {
            try {
                LLMConfig c = new LLMConfig();
                if ("exchanges".equals(which)) c.conversationMaxExchanges(-1);
                else if ("historyChars".equals(which)) c.conversationMaxHistoryChars(-1);
                else if ("thinkChars".equals(which)) c.conversationThinkCharsPerMsg(-1);
                else c.summarizeKeepExchanges(-1);
                Assert.fail("expected IllegalArgumentException for negative " + which);
            } catch (IllegalArgumentException e) {
                Assert.assertTrue("message names the field ('" + which + "'): " + e.getMessage(),
                        e.getMessage().contains("-1"));
            }
        }
    }

    // ── trim: 0 = unlimited, positive = cap, MAX_VALUE = legacy unlimited ────

    @Test
    public void zeroMeansUnlimitedInTrim() throws Exception {
        LLMClient client = new LLMClient(new LLMConfig()); // 0 / 0 / 0
        seedHistory(client, 300, 50);
        TRIM.invoke(client);
        Assert.assertEquals("0 = infinity: trim must keep every message", 300, historyOf(client).size());
    }

    @Test
    public void positiveExchangeCapStillTrims() throws Exception {
        LLMConfig c = new LLMConfig().conversationMaxExchanges(3); // history chars stay 0 = unlimited
        LLMClient client = new LLMClient(c);
        seedHistory(client, 10, 50);
        TRIM.invoke(client);
        Assert.assertEquals("a positive exchange cap still trims to cap*2 messages", 6, historyOf(client).size());
    }

    @Test
    public void positiveCharsCapStillTrims() throws Exception {
        LLMConfig c = new LLMConfig().conversationMaxHistoryChars(250); // exchanges stay 0 = unlimited
        LLMClient client = new LLMClient(c);
        seedHistory(client, 10, 100); // 1000 chars total
        TRIM.invoke(client);
        Assert.assertEquals("chars cap trims to <= 250 chars (floor: keeps 2 msgs)", 2, historyOf(client).size());
        Assert.assertEquals(200, totalChars(client));
    }

    @Test
    public void legacyMaxValueStillUnlimitedInTrim() throws Exception {
        LLMConfig c = new LLMConfig()
                .conversationMaxExchanges(Integer.MAX_VALUE)
                .conversationMaxHistoryChars(Integer.MAX_VALUE);
        LLMClient client = new LLMClient(c);
        seedHistory(client, 100, 50);
        TRIM.invoke(client); // must not overflow cap*2 and must not trim
        Assert.assertEquals("near-MAX_VALUE stays effectively unlimited (old saves)", 100, historyOf(client).size());
    }

    // ── thinking retention: 0 = keep full reasoning ───────────────────────────

    @Test
    public void zeroKeepsFullReasoningInHistory() throws Exception {
        // Explicit 0 (= the new default): pre-change code treated 0 as "omit
        // reasoning from history" — this test fails against that behavior.
        LLMClient client = new LLMClient(new LLMConfig().conversationThinkCharsPerMsg(0));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) sb.append('t');
        String thinking = sb.toString();

        String msg = (String) BUILD_ASSISTANT.invoke(client, new LLMResponse(thinking, "answer"));
        Assert.assertTrue("0 = infinity must keep the FULL reasoning", msg.contains(thinking));
        Assert.assertFalse("no truncation marker when unlimited", msg.contains("[reasoning truncated]"));
    }

    @Test
    public void positiveThinkLimitTruncates() throws Exception {
        LLMConfig c = new LLMConfig().conversationThinkCharsPerMsg(100);
        LLMClient client = new LLMClient(c);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) sb.append('t');
        String thinking = sb.toString();

        String msg = (String) BUILD_ASSISTANT.invoke(client, new LLMResponse(thinking, "answer"));
        Assert.assertTrue("reasoning beyond the cap is cut with a marker",
                msg.contains(thinking.substring(0, 100) + "... [reasoning truncated]"));
        Assert.assertFalse("the full reasoning must not survive a positive cap", msg.contains(thinking));
    }

    // ── summarize gates: 0 exchanges = no count-forced summarization ──────────

    @Test
    public void zeroExchangeLimitDoesNotForceSummarizeByCount() throws Exception {
        LLMConfig c = new LLMConfig().apiEndpoint(DEAD_ENDPOINT).model("probe")
                .summarizeTriggerChars(100000); // chars (100) stay far UNDER the trigger
        LLMClient client = new LLMClient(c); // max exchanges 0 = infinity
        client.resetConversationForTest("sys");
        seedHistory(client, 10, 10);

        SUMMARIZE.invoke(client); // must return early — old code ("<= 0*2" false) dialed the endpoint
        Assert.assertEquals("nothing summarized", 10, historyOf(client).size());
    }

    @Test
    public void keepFiveCoversTenMessagesAtTheSummarizeGate() throws Exception {
        LLMConfig c = new LLMConfig().apiEndpoint(DEAD_ENDPOINT).model("probe")
                .summarizeTriggerChars(1); // chars (1000) exceed the trigger -> keep gate is next
        LLMClient client = new LLMClient(c); // keep = 5 -> "too small to summarize" up to 12 msgs
        client.resetConversationForTest("sys");
        seedHistory(client, 10, 100);

        SUMMARIZE.invoke(client); // old keep=2: 10 > 6 -> proceeds -> dials the dead endpoint
        Assert.assertEquals("10 msgs inside a 5-exchange retention window: nothing summarized",
                10, historyOf(client).size());
    }

    @Test
    public void legacyMaxValueDoesNotForceSummarizeByCount() throws Exception {
        // Old .game saves / explicit configs carry Integer.MAX_VALUE. The count
        // clause (`size <= maxEx * 2`) overflowed for those (MAX_VALUE * 2 = -2),
        // making it unconditionally false: every call with > keepCount+2 messages
        // forced a summarization. MAX_VALUE must mean unlimited here too.
        LLMConfig c = new LLMConfig().apiEndpoint(DEAD_ENDPOINT).model("probe")
                .conversationMaxExchanges(Integer.MAX_VALUE)
                .summarizeTriggerChars(100000); // chars (200) stay UNDER the trigger
        LLMClient client = new LLMClient(c);
        client.resetConversationForTest("sys");
        seedHistory(client, 20, 10);

        SUMMARIZE.invoke(client); // pre-change: overflow -> forced summarize -> dead endpoint
        Assert.assertEquals("MAX_VALUE = unlimited: no count-forced summarization",
                20, historyOf(client).size());
    }
}
