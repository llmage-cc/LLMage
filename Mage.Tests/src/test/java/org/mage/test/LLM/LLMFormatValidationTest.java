package org.mage.test.LLM;

import mage.player.llm.LLMPlayer;
import org.junit.Assert;
import org.junit.Test;

import java.util.function.Predicate;

/**
 * Unit tests for chatAndRecord's validation layer:
 *
 * 1. First-line acceptance:
 *    validators reject whole answers with trailing reasoning, but every
 *    downstream parser reads only the first line. When the first line alone is
 *    valid, the answer must be ACCEPTED without a correction call (202 wasted
 *    re-prompts per 688 calls ≈ 44% of prompt spend; ~94% of rejected answers
 *    had an already-valid first line).
 *
 * 2. Colon mana-plan variant: answers like "1:mana:L5" (colonified spelling of
 *    the documented "N mana:L1,L2") must validate AND carry the plan — never
 *    silently degrade to no-plan. Unrecognized mana-plan syntax must FAIL
 *    validation (correction path).
 */
public class LLMFormatValidationTest {

    private static final Predicate<String> ACTION = LLMPlayer.validatorByName("action-number");
    private static final Predicate<String> SIMPLE = LLMPlayer.validatorByName("simple-number");
    private static final Predicate<String> COMMA = LLMPlayer.validatorByName("comma-numbers");
    private static final Predicate<String> YES_NO = LLMPlayer.validatorByName("yes-no");

    // ── Item 2: first-line acceptance ─────────────────────────────────────

    /** The measured 94% case: "<valid number>\nReasoning: ..." must ACCEPT. */
    @Test
    public void testTrailingReasoningAfterValidNumberAccepts() {
        Assert.assertTrue(LLMPlayer.passesValidation(ACTION, "5\nReasoning: This is the mulligan to 5 hand..."));
        Assert.assertTrue(LLMPlayer.passesValidation(ACTION, "2\nReasoning: Turn 2, play a land..."));
        Assert.assertTrue(LLMPlayer.passesValidation(SIMPLE, "3\nReasoning: Turn 3, I..."));
        Assert.assertTrue(LLMPlayer.passesValidation(COMMA, "1,4\nReasoning: I want both of these cards."));
        Assert.assertTrue(LLMPlayer.passesValidation(YES_NO, "no\nI decline because the cost is too high."));
    }

    /** The answer that must still be rejected: prose on the FIRST line. */
    @Test
    public void testProseFirstLineStillRejected() {
        Assert.assertFalse("prose-only answer must still trigger correction",
                LLMPlayer.passesValidation(ACTION, "I would like to cast the creature, please."));
        Assert.assertFalse("reasoning before the number must still be rejected",
                LLMPlayer.passesValidation(ACTION, "Reasoning: the best play is...\n5"));
        Assert.assertFalse(LLMPlayer.passesValidation(SIMPLE, "cast it"));
        Assert.assertFalse(LLMPlayer.passesValidation(YES_NO, "probably fine, sure"));
    }

    /** Leading-whitespace answers are consistent: the validators trim the
     *  whole answer first (so "\n5" sees "5"), and parseActionNumber trims
     *  before splitting on newline — same result. No smuggling: a prose
     *  first line followed by a number still fails. */
    @Test
    public void testLeadingWhitespaceIsConsistentWithParsers() {
        Assert.assertTrue("validator trims whole answer; parser trims too",
                LLMPlayer.passesValidation(ACTION, "\n5"));
        // But prose-first + number-second must still be rejected: the whole
        // string isn't valid, and the first line ("Reasoning: the best play is...")
        // isn't either.
        Assert.assertFalse(LLMPlayer.passesValidation(ACTION, "Reasoning: the best play is...\n5"));
    }

    /** firstLineOf: split before trim, per its contract. */
    @Test
    public void testFirstLineOf() {
        Assert.assertEquals("5", LLMPlayer.firstLineOf("5\nReasoning: ..."));
        Assert.assertEquals("", LLMPlayer.firstLineOf("\n5"));
        Assert.assertEquals("", LLMPlayer.firstLineOf(null));
        Assert.assertEquals("3", LLMPlayer.firstLineOf("  3  \nrest"));
        Assert.assertEquals("3", LLMPlayer.firstLineOf("3"));
    }

    // ── Item 3: colon mana-plan variant ───────────────────────────────────

    /** Both spellings validate and therefore carry the plan (parsed by
     *  parseManaPlan, which reads "mana:" wherever it appears on line 1). */
    @Test
    public void testManaPlanSpaceAndColonFormsBothValidate() {
        Assert.assertTrue("documented space form", ACTION.test("3 mana:L1,L3"));
        Assert.assertTrue("colonified form from the report", ACTION.test("1:mana:L5"));
        Assert.assertTrue("colonified form with multiple lands", ACTION.test("4:mana:L1,L2,L3"));
        Assert.assertTrue("space form with action prefix", ACTION.test("action:3 mana:L1,L3"));
        Assert.assertTrue("colon form with action prefix", ACTION.test("action:3:mana:L1,L3"));
    }

    /** The full acceptance path also accepts both forms (first-line rule). */
    @Test
    public void testManaPlanFormsAcceptedThroughPassesValidation() {
        Assert.assertTrue(LLMPlayer.passesValidation(ACTION, "1:mana:L5"));
        Assert.assertTrue(LLMPlayer.passesValidation(ACTION, "1:mana:L5\nReasoning: tap the Island."));
        Assert.assertTrue(LLMPlayer.passesValidation(ACTION, "2 mana:L1,L2\nReasoning: ..."));
    }

    /** Unrecognized mana-plan syntax FAILS validation — correction path, not
     *  a silent degrade to no-plan. */
    @Test
    public void testUnrecognizedManaPlanSyntaxFailsValidation() {
        Assert.assertFalse("garbage separator", ACTION.test("3-mana:L1,L2"));
        Assert.assertFalse("missing separator", ACTION.test("3mana:L1,L2"));
        Assert.assertFalse("mana plan with no action number", ACTION.test("mana:L1,L2"));
        Assert.assertFalse("non-numeric action with plan", ACTION.test("three mana:L1"));
    }

    /** Plain numbers still validate (no mana plan present). */
    @Test
    public void testPlainNumbersStillValidate() {
        Assert.assertTrue(ACTION.test("3"));
        Assert.assertTrue(ACTION.test("0"));
        Assert.assertTrue(ACTION.test("  7 "));
        Assert.assertTrue(ACTION.test("action:12"));
        Assert.assertFalse(ACTION.test("three"));
        Assert.assertFalse(ACTION.test(""));
        Assert.assertFalse(ACTION.test(null));
    }
}