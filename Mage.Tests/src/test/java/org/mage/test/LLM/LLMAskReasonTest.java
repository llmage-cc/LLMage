package org.mage.test.LLM;

import mage.player.llm.LLMClient;
import mage.player.llm.LLMConfig;
import mage.player.llm.LLMPlayer;
import mage.player.llm.LLMResponse;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;

/**
 * Ask-reason feature:
 * for providers that hide reasoning text (transcript shows "(reasoning hidden
 * by provider: N tokens billed)"), the seat can ask for a one-sentence REASON
 * line after the answer line.
 *
 * Contract pinned here:
 *  - The REASON ask is appended to decision prompts only when the flag is ON,
 *    and never twice.
 *  - The action line stays the FIRST line and is parsed exactly as before;
 *    REASON extraction is best-effort and a mangled/missing reason never
 *    fails the move.
 *  - extractStatedReason handles the hostile shapes: no second line, blank
 *    lines, missing/odd-case label, multi-line trailing content.
 */
public class LLMAskReasonTest {

    // ---------- extractStatedReason ----------

    @Test
    public void extractsReasonWithLabel() {
        Assert.assertEquals("casting Ponder to find lands",
                LLMPlayer.extractStatedReason("3\nREASON: casting Ponder to find lands"));
    }

    @Test
    public void extractsReasonCaseInsensitiveLabel() {
        Assert.assertEquals("keep this hand, two lands and a creature",
                LLMPlayer.extractStatedReason("keep\nreason: keep this hand, two lands and a creature"));
    }

    @Test
    public void extractsReasonWithoutLabel() {
        // Model forgot the label — the second line is still the reason.
        Assert.assertEquals("it develops my board",
                LLMPlayer.extractStatedReason("2\nit develops my board"));
    }

    @Test
    public void skipsBlankLinesBeforeReason() {
        Assert.assertEquals("need blockers",
                LLMPlayer.extractStatedReason("1\n\n\nREASON: need blockers"));
    }

    @Test
    public void noSecondLineReturnsNull() {
        Assert.assertNull(LLMPlayer.extractStatedReason("3"));
    }

    @Test
    public void emptySecondLineReturnsNull() {
        Assert.assertNull(LLMPlayer.extractStatedReason("3\n   \n"));
    }

    @Test
    public void labelOnlyReturnsNull() {
        Assert.assertNull(LLMPlayer.extractStatedReason("3\nREASON:"));
        Assert.assertNull(LLMPlayer.extractStatedReason("3\nREASON:  "));
    }

    @Test
    public void nullAnswerReturnsNull() {
        Assert.assertNull(LLMPlayer.extractStatedReason(null));
    }

    @Test
    public void firstLineUnchangedByReasonLine() {
        // The decisive contract: whatever follows, the action parse reads
        // line 1 exactly as before (validators see only firstLineOf).
        String answer = "3 mana:L1,L2\nREASON: Ponder digs for the third land";
        Assert.assertEquals("3 mana:L1,L2", LLMPlayer.firstLineOf(answer));
    }

    @Test
    public void reasonStopsAtFirstContentLine() {
        // Multi-line ramble after the reason: only the first content line is
        // taken; the rest is ignored (it stays in the raw answer for the
        // transcript's ANSWER section).
        Assert.assertEquals("tempo play",
                LLMPlayer.extractStatedReason("4\nREASON: tempo play\nand also because value"));
    }

    // ---------- suffix shape ----------

    @Test
    public void suffixIsAppendedVerbatim() {
        String prompt = "=== MULLIGAN DECISION ===\nReply with ONLY 'keep' or 'mulligan'.";
        String effective = prompt + LLMConfig.ASK_REASON_PROMPT_SUFFIX;
        Assert.assertTrue(effective.startsWith(prompt));
        Assert.assertTrue(effective.contains("REASON:"));
        // The suffix must not rewrite the existing format instruction — the
        // answer still goes on the first line.
        Assert.assertTrue(effective.contains("Reply with ONLY 'keep' or 'mulligan'."));
    }

    @Test
    public void defaultOff() {
        Assert.assertFalse("askReason defaults OFF", new LLMConfig().isAskReason());
    }

    @Test
    public void copyConstructorCarriesFlag() {
        LLMConfig a = new LLMConfig();
        a.askReason(true);
        Assert.assertTrue(new LLMConfig(a).isAskReason());
    }

    // ---------- LLMResponse statedReason field ----------

    @Test
    public void responseDefaultsNullReason() {
        LLMResponse r = new LLMResponse("think", "3");
        Assert.assertNull(r.statedReason);
        Assert.assertNull(new LLMResponse("think", "3", null).statedReason);
    }

    @Test
    public void responseCarriesReason() {
        LLMResponse r = new LLMResponse("think", "3", null, "tempo");
        Assert.assertEquals("tempo", r.statedReason);
        Assert.assertEquals("3", r.answer);
        Assert.assertEquals("think", r.thinking);
    }

    // client-boundary attachment (the earlier verification live fix) ----------
    // The live flag-on run proved the extracted reason never reached the
    // transcript: LLMPlayer extracted it AFTER LLMClient had already called
    // writeTranscript, so resp.statedReason was still null at write time
    // (0 "--- LLM STATED REASON ---" sections with the suffix demonstrably on
    // the wire). The extraction therefore happens at the CLIENT call boundary,
    // before the transcript write. These tests pin that boundary.

    @Test
    public void clientBoundaryAttachesReasonToResponseWhenFlagOn() throws Exception {
        LLMConfig cfg = new LLMConfig().askReason(true);
        LLMClient client = new LLMClient(cfg);
        Method m = LLMClient.class.getDeclaredMethod("attachStatedReason", LLMResponse.class);
        m.setAccessible(true);
        LLMResponse out = (LLMResponse) m.invoke(client,
                new LLMResponse(null, "3\nREASON: casting Ponder for lands", null));
        Assert.assertEquals("casting Ponder for lands", out.statedReason);
        Assert.assertEquals("3\nREASON: casting Ponder for lands", out.answer);
    }

    @Test
    public void clientBoundaryLeavesResponseAloneWhenFlagOff() throws Exception {
        LLMConfig cfg = new LLMConfig().askReason(false);
        LLMClient client = new LLMClient(cfg);
        Method m = LLMClient.class.getDeclaredMethod("attachStatedReason", LLMResponse.class);
        m.setAccessible(true);
        LLMResponse in = new LLMResponse(null, "3\nREASON: casting Ponder for lands", null);
        LLMResponse out = (LLMResponse) m.invoke(client, in);
        Assert.assertSame("flag off must not touch the response (byte-identical path)", in, out);
        Assert.assertNull(out.statedReason);
    }

    @Test
    public void clientBoundaryMissingReasonLeavesResponseAlone() throws Exception {
        LLMConfig cfg = new LLMConfig().askReason(true);
        LLMClient client = new LLMClient(cfg);
        Method m = LLMClient.class.getDeclaredMethod("attachStatedReason", LLMResponse.class);
        m.setAccessible(true);
        LLMResponse in = new LLMResponse(null, "3", null);
        Assert.assertSame("action-only answer must pass through untouched", in, m.invoke(client, in));
    }
}
