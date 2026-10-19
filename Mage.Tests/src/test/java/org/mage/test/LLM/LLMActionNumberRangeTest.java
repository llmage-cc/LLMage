package org.mage.test.LLM;

import mage.constants.RangeOfInfluence;
import mage.player.llm.LLMPlayer;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;

import java.util.function.Predicate;

/**
 * Range validation of the priority action number.
 *
 * <p>Before this fix {@code ACTION_NUMBER_VALIDATOR} accepted ANY digit string
 * ({@code trimmed.matches("\\d+")}), so an out-of-range answer such as "7" with
 * 3 actions offered passed validation, reached the action dispatcher, and threw
 * — a FATAL that rolled the game turn back. The ranged validator knows the
 * action list the LLM was actually offered, so out-of-range answers now fail
 * validation and take chatAndRecord's correction path instead.
 *
 * <p>The number is extracted by the same {@code parseFirstNumber} the dispatcher
 * uses, so "what validates" and "what executes" cannot disagree.
 */
public class LLMActionNumberRangeTest {

    /** Validator used before the fix: format only, no range. */
    private static final Predicate<String> FORMAT_ONLY = LLMPlayer.validatorByName("action-number");

    /** 3 real actions, concede enabled → 0..8 selectable (3 + 4 skip + concede). */
    private static Predicate<String> threeActions() {
        return LLMPlayer.actionNumberInRange(3, true);
    }

    /** 3 real actions, concede disabled → 0..7 selectable. */
    private static Predicate<String> threeActionsNoConcede() {
        return LLMPlayer.actionNumberInRange(3, false);
    }

    // ── The bug: format-only validator accepted the out-of-range answer ────

    @Test
    public void formatOnlyValidatorAcceptedOutOfRangeAnswer() {
        Assert.assertTrue("pre-fix behavior: \"7\" passed format validation with 3 actions",
                FORMAT_ONLY.test("7"));
    }

    // ── The fix: ranged validator rejects it, correction path engages ──────

    @Test
    public void outOfRangeAnswerFailsValidation() {
        // 3 actions → selectable 0..8 (0 = pass, 1-3 = actions, 4-7 = SKIPs, 8 = CONCEDE).
        Assert.assertFalse("\"9\" is one past the last selectable index",
                threeActions().test("9"));
        Assert.assertFalse(threeActions().test("100"));
        // The archived occurrence: "invalid action index 7 (valid range: 0-2)" — a
        // small action list where 7 has no meaning at all.
        Predicate<String> twoActions = LLMPlayer.actionNumberInRange(2, false);
        Assert.assertFalse("7 with 2 actions and no concede (max 6) must fail",
                twoActions.test("7"));
        Assert.assertTrue("6 = CANCEL ALL SKIPS is still selectable", twoActions.test("6"));
    }

    @Test
    public void inRangeAnswersStillValidate() {
        Predicate<String> v = threeActions();
        for (int i = 0; i <= 8; i++) {
            Assert.assertTrue("action " + i + " is in range (0-8)",
                    v.test(Integer.toString(i)));
        }
    }

    @Test
    public void concedeIndexExcludedWhenConcedeDisabled() {
        Assert.assertTrue("8 = CONCEDE is selectable when enabled",
                threeActions().test("8"));
        Assert.assertFalse("8 is out of range when concede is disabled",
                threeActionsNoConcede().test("8"));
        Assert.assertTrue("7 = CANCEL ALL SKIPS still selectable",
                threeActionsNoConcede().test("7"));
    }

    @Test
    public void manaPlanFormsRangeCheckedToo() {
        Assert.assertTrue("in-range plan validates",
                threeActions().test("3 mana:L1,L2"));
        Assert.assertFalse("out-of-range plan fails — no silent degrade",
                threeActions().test("9 mana:L1,L2"));
        Assert.assertFalse("out-of-range colon form fails",
                threeActions().test("99:mana:L1"));
    }

    @Test
    public void wrongFormatStillFailsThroughTheFormatPath() {
        Predicate<String> v = threeActions();
        Assert.assertFalse(v.test("pass"));
        Assert.assertFalse(v.test("three"));
        Assert.assertFalse(v.test(""));
        Assert.assertFalse(v.test(null));
        Assert.assertFalse("mana plan with no action number", v.test("mana:L1"));
    }

    @Test
    public void trailingReasoningAcceptedWhenFirstLineInRange() {
        // passesValidation falls back to the first line — same as every parser.
        Assert.assertTrue(LLMPlayer.passesValidation(threeActions(),
                "2\nReasoning: play the land first."));
        Assert.assertFalse("out-of-range first line still rejected",
                LLMPlayer.passesValidation(threeActions(),
                        "99\nReasoning: I want the last option."));
    }

    // ── The range mirrors the prompt's numbering exactly ───────────────────

    @Test
    public void rangeMatchesPromptNumbering() {
        // LLMGameSerializer.buildPrompt numbers: 0 = pass priority,
        // 1..n = actions, n+1..n+4 = SKIPs, n+5 = CONCEDE (when enabled).
        for (int n = 1; n <= 5; n++) {
            Assert.assertTrue("n=1: the single action's index is valid",
                    n != 1 || LLMPlayer.actionNumberInRange(1, true).test("1"));
            Assert.assertTrue("n=" + n + ": pass is always valid",
                    LLMPlayer.actionNumberInRange(n, true).test("0"));
            Assert.assertTrue("n=" + n + ": skip-turn (n+1) valid",
                    LLMPlayer.actionNumberInRange(n, true).test(Integer.toString(n + 1)));
            Assert.assertTrue("n=" + n + ": concede (n+5) valid with concede on",
                    LLMPlayer.actionNumberInRange(n, true).test(Integer.toString(n + 5)));
            Assert.assertFalse("n=" + n + ": n+6 out of range",
                    LLMPlayer.actionNumberInRange(n, true).test(Integer.toString(n + 6)));
        }
    }

    @Test
    public void formatHintNamesTheValidRange() {
        // The correction prompt must tell the model the actual range, otherwise
        // it just re-emits another out-of-range guess.
        String hint = LLMPlayer.formatHintFor(threeActions());
        Assert.assertTrue("hint should name the max (" + hint + ")", hint.contains("8"));
        Assert.assertTrue("hint should name the action count (" + hint + ")",
                hint.contains("1-3"));
    }

    // ── End-to-end: the retry/correction path fires, not the dispatcher ────

    /**
     * The whole point: an out-of-range answer must be answered with a
     * CORRECTION prompt (a second model call naming the range), so the decision
     * never reaches the dispatcher's FATAL-and-rollback. Drives the real
     * chatAndRecord accept/reject logic with a stub that answers out-of-range
     * first and in-range on the correction call.
     */
    @Test
    public void outOfRangeAnswerTakesTheCorrectionPathNotTheDispatcher() throws Exception {
        final java.util.List<String> promptsSeen = new java.util.ArrayList<>();
        TestLLMPlayer player = new TestLLMPlayer("RangeTestBot", RangeOfInfluence.ONE, 0);
        player.getLLMConfig()
                .model("deepseek-v4-flash:cloud")
                .apiEndpoint("http://localhost:11434/v1/chat/completions")
                .maxTokens(65536)
                .timeoutSeconds(120);
        player.reconnect();
        player.injectTestClient(new mage.player.llm.LLMClient(player.getLLMConfig()) {
            @Override
            public mage.player.llm.LLMResponse chatConversation(String fullPrompt, String compactSummary,
                                                                String promptType, Integer turn,
                                                                String phaseStep, String effortOverride) {
                promptsSeen.add(fullPrompt);
                // First call: deliberately out of range for a 3-action list.
                // Correction call: a valid answer.
                return promptsSeen.size() == 1
                        ? new mage.player.llm.LLMResponse("thinking", "99")
                        : new mage.player.llm.LLMResponse("thinking", "2");
            }
        });

        mage.game.Game game = new mage.game.TwoPlayerDuel(
                mage.constants.MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE,
                mage.game.mulligan.MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);

        mage.player.llm.LLMResponse resp = player.chatAndRecordForTest(
                game, "pick an action", "summary", "priority", threeActions());

        Assert.assertEquals("the corrected in-range answer must be returned", "2", resp.answer);
        Assert.assertEquals("an out-of-range answer must trigger exactly one correction call",
                2, promptsSeen.size());
        Assert.assertTrue("the correction prompt must name the valid range, got: " + promptsSeen.get(1),
                promptsSeen.get(1).contains("between 0 and 8"));
        Assert.assertTrue("...and quote the rejected answer, got: " + promptsSeen.get(1),
                promptsSeen.get(1).contains("99"));
    }

    /** A valid answer must NOT trigger a correction call. */
    @Test
    public void inRangeAnswerSkipsTheCorrectionPath() throws Exception {
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        TestLLMPlayer player = new TestLLMPlayer("RangeTestBot2", RangeOfInfluence.ONE, 0);
        player.getLLMConfig()
                .model("deepseek-v4-flash:cloud")
                .apiEndpoint("http://localhost:11434/v1/chat/completions")
                .maxTokens(65536)
                .timeoutSeconds(120);
        player.reconnect();
        player.injectTestClient(new mage.player.llm.LLMClient(player.getLLMConfig()) {
            @Override
            public mage.player.llm.LLMResponse chatConversation(String fullPrompt, String compactSummary,
                                                                String promptType, Integer turn,
                                                                String phaseStep, String effortOverride) {
                calls.incrementAndGet();
                return new mage.player.llm.LLMResponse("thinking", "2");
            }
        });

        mage.game.Game game = new mage.game.TwoPlayerDuel(
                mage.constants.MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE,
                mage.game.mulligan.MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);

        mage.player.llm.LLMResponse resp = player.chatAndRecordForTest(
                game, "pick an action", "summary", "priority", threeActions());
        Assert.assertEquals("2", resp.answer);
        Assert.assertEquals("no correction call for a valid answer", 1, calls.get());
    }

    // ── Extraction parity with the dispatcher ──────────────────────────────

    @Test
    public void validationUsesTheDispatchersOwnNumberExtraction() {
        // "action:5" is parsed by parseFirstNumber as 5 for both paths, so a
        // prefixed out-of-range answer must be rejected by the validator, not
        // silently accepted here and then rejected in the dispatcher.
        Assert.assertTrue(threeActions().test("action:5"));
        Assert.assertFalse(threeActions().test("action:99"));
        Assert.assertEquals("validator and dispatcher agree on the number",
                5, LLMPlayer.parseFirstNumber("action:5"));
    }
}
