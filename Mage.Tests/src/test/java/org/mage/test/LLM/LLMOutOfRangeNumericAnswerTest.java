package org.mage.test.LLM;

import mage.constants.MultiplayerAttackOption;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.TwoPlayerDuel;
import mage.game.mulligan.MulliganType;
import mage.player.llm.LLMClient;
import mage.player.llm.LLMPlayer;
import mage.player.llm.LLMResponse;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Out-of-range numeric answers from the LLM seat.
 *
 * <p>Live evidence (deployed 8-bot tournament): five FATAL "Game error" events,
 * each a RuntimeException from a numeric answer the prompt did not offer —
 * "LLM returned invalid action index 10 (valid range: 0-2)", "… 15 (valid
 * range: 0-4)", "LLM cast side: invalid index 17 (valid: 1-2)", "LLM trigger
 * order: invalid index 0 (valid: 1-2)". Each rolled the turn back and burned
 * one of the game's 15 priority-error budget.
 *
 * <p>Three behaviours are pinned here, all OFFLINE (the model call is stubbed; no
 * network, no game loop):
 * <ol>
 *   <li>the cast-side and trigger-order prompts validate their number against
 *       the option list they offered, take the existing correction prompt once,
 *       and then fall back to the first offered option instead of throwing;</li>
 *   <li>the priority action menu keeps its list, its numbering, its validator
 *       and its dispatch lookup in ONE object, so a range can never be computed
 *       from one list and checked against another;</li>
 *   <li>validation and dispatch read the same NUMBER: the "action:" scan is
 *       first-line-only, so reasoning text can no longer supply a number the
 *       validator never saw.</li>
 * </ol>
 *
 * <p>Compile-only; named for the parent gate. Run with:
 * {@code mvn test -pl Mage.Tests -Dtest=LLMOutOfRangeNumericAnswerTest}
 */
public class LLMOutOfRangeNumericAnswerTest {

    /** The offered sides / triggers used by the fallback assertions. */
    private static final List<String> SIDES = Arrays.asList("Side A", "Side B");
    private static final List<String> TRIGGERS = Arrays.asList("Trigger A", "Trigger B");

    /**
     * Stub LLM seat: returns the queued answers in order (the last one repeats)
     * and records every prompt, so the correction round-trip is visible.
     */
    private static TestLLMPlayer stubPlayer(final List<String> promptsSeen, final String... answers) {
        TestLLMPlayer player = new TestLLMPlayer("RangeBot", RangeOfInfluence.ONE, 0);
        player.getLLMConfig()
                .model("stub-model")
                .apiEndpoint("http://127.0.0.1:9/v1/chat/completions")
                .timeoutSeconds(5);
        player.reconnect();
        player.injectTestClient(new LLMClient(player.getLLMConfig()) {
            @Override
            public LLMResponse chatConversation(String fullPrompt, String compactSummary,
                                                String promptType, Integer turn,
                                                String phaseStep, String effortOverride) {
                promptsSeen.add(fullPrompt);
                int i = Math.min(promptsSeen.size() - 1, answers.length - 1);
                return new LLMResponse("thinking", answers[i]);
            }
        });
        return player;
    }

    private static Game newGame() {
        return new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE,
                MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);
    }

    // ── The validator itself ───────────────────────────────────────────────

    @Test
    public void rangeValidatorRejectsOutOfRangeNumbers() {
        Predicate<String> sides = LLMPlayer.numberInRange(1, 2);
        Assert.assertTrue("side 1 is offered", sides.test("1"));
        Assert.assertTrue("side 2 is offered", sides.test("2"));
        Assert.assertFalse("17 is not offered (the live cast-side event)", sides.test("17"));
        Assert.assertFalse("0 is not a side (the live trigger-order event)", sides.test("0"));
        Assert.assertFalse("garbage stays on the format path", sides.test("two"));
        Assert.assertFalse("null", sides.test(null));
        String hint = LLMPlayer.formatHintFor(sides);
        Assert.assertTrue("the correction prompt must name the range, got: " + hint,
                hint.contains("between 1 and 2"));
    }

    @Test
    public void inRangeAnswerNeedsNoCorrectionCall() {
        List<String> prompts = new ArrayList<>();
        TestLLMPlayer player = stubPlayer(prompts, "2");
        LLMResponse resp = player.chatAndRecordForTest(newGame(), "pick a side", "summary", "castside",
                LLMPlayer.numberInRange(1, SIDES.size()));
        Assert.assertEquals("2", resp.answer);
        Assert.assertEquals("a valid side takes no correction round-trip", 1, prompts.size());
        Assert.assertEquals("Side B", LLMPlayer.pickOrFirst(SIDES, resp.answer));
    }

    // ── Cast side: correction, then a legal fallback ────────────────────────

    @Test
    public void castSideOutOfRangeIsCorrectedThenFallsBackToTheFirstSide() {
        List<String> prompts = new ArrayList<>();
        TestLLMPlayer player = stubPlayer(prompts, "17", "17");
        LLMResponse resp = player.chatAndRecordForTest(newGame(), "pick a side", "summary", "castside",
                LLMPlayer.numberInRange(1, SIDES.size()));

        Assert.assertEquals("the repeated out-of-range answer comes back unchanged — no throw", "17", resp.answer);
        Assert.assertEquals("the model was asked once and corrected once", 2, prompts.size());
        Assert.assertTrue("the correction prompt names the range, got: " + prompts.get(1),
                prompts.get(1).contains("between 1 and 2"));
        Assert.assertEquals("17 selects no side", 0, LLMPlayer.pickNumbered(resp.answer, SIDES.size()));
        Assert.assertEquals("the legal fallback is the FIRST offered side", "Side A",
                LLMPlayer.pickOrFirst(SIDES, resp.answer));
    }

    @Test
    public void castSideRecoversOnTheCorrectionCall() {
        List<String> prompts = new ArrayList<>();
        TestLLMPlayer player = stubPlayer(prompts, "17", "2");
        LLMResponse resp = player.chatAndRecordForTest(newGame(), "pick a side", "summary", "castside",
                LLMPlayer.numberInRange(1, SIDES.size()));

        Assert.assertEquals("the corrected answer is what the caller gets", "2", resp.answer);
        Assert.assertEquals("exactly one correction call", 2, prompts.size());
        Assert.assertEquals("2 is an offered side", 2, LLMPlayer.pickNumbered(resp.answer, SIDES.size()));
        Assert.assertEquals("Side B", LLMPlayer.pickOrFirst(SIDES, resp.answer));
    }

    // ── Trigger order: correction, then a legal fallback ────────────────────

    @Test
    public void triggerOrderOutOfRangeIsCorrectedThenFallsBackToTheFirstTrigger() {
        List<String> prompts = new ArrayList<>();
        TestLLMPlayer player = stubPlayer(prompts, "0", "0");
        LLMResponse resp = player.chatAndRecordForTest(newGame(), "order the triggers", "summary", "order",
                LLMPlayer.numberInRange(1, TRIGGERS.size()));

        Assert.assertEquals("0 comes back unchanged — no throw", "0", resp.answer);
        Assert.assertEquals("asked once, corrected once", 2, prompts.size());
        Assert.assertTrue("the correction prompt names the range, got: " + prompts.get(1),
                prompts.get(1).contains("between 1 and 2"));
        Assert.assertEquals("0 selects no trigger", 0, LLMPlayer.pickNumbered(resp.answer, TRIGGERS.size()));
        Assert.assertEquals("the fallback keeps the order the prompt listed", "Trigger A",
                LLMPlayer.pickOrFirst(TRIGGERS, resp.answer));
    }

    // ── Action menu: one list for validation AND dispatch ───────────────────

    @Test
    public void actionMenuValidatesAndDispatchesFromOneList() {
        List<String> described = new ArrayList<>(Arrays.asList("play land", "cast Bear", "activate Relic"));
        LLMPlayer.ActionMenu menu = LLMPlayer.actionMenuForTest(described, true);

        Assert.assertEquals("3 offered actions", 3, menu.actionCount());
        Assert.assertEquals("0..8 selectable (3 actions + 4 skips + concede)", 8, menu.maxNumber());
        for (int n = 0; n <= 8; n++) {
            Assert.assertTrue("number " + n + " is both selectable and valid",
                    menu.isSelectable(n) && menu.test(Integer.toString(n)));
        }
        Assert.assertFalse("9 is one past the last printed number", menu.isSelectable(9));
        Assert.assertFalse("and the validator agrees", menu.test("9"));
        Assert.assertEquals("number 1 is the first offered action", "play land", menu.descriptionAt(1));
        Assert.assertEquals("number 3 is the third", "activate Relic", menu.descriptionAt(3));
        Assert.assertNull("number 4 is a SKIP, not an action", menu.descriptionAt(4));

        // The menu derives its numbering from the LIST, not a count snapshot:
        // clearing that very list must move the numbers with it.
        Object ref = menu.offeredListReferenceForTest();
        Assert.assertNotNull("the menu keeps the list it was built from", ref);
        Assert.assertEquals("and it is the offered list", 3, ((List<?>) ref).size());
        ((List<?>) ref).clear();
        Assert.assertEquals("numbering follows the list", 0, menu.actionCount());
        Assert.assertEquals("0..5 with no actions and concede on (0 = pass, 1-4 skips, 5 concede)", 5, menu.maxNumber());
        Assert.assertNull("no action 1 any more", menu.descriptionAt(1));
    }

    @Test
    public void validatorAndDispatcherReadTheSameNumber() {
        LLMPlayer.ActionMenu menu = LLMPlayer.actionMenuForTest(Arrays.asList("A", "B", "C"), true);

        String inRangeFirstLine = "2\nREASON: action:10 is the stronger line";
        Assert.assertTrue("the offered first-line number 2 validates", menu.test(inRangeFirstLine));
        Assert.assertEquals("and 2 is the number the dispatcher parses — not the reasoning's 10",
                2, menu.numberFor(inRangeFirstLine));
        Assert.assertEquals("parseFirstNumber reads the first line only",
                2, LLMPlayer.parseFirstNumber(inRangeFirstLine));

        String outOfRangeFirstLine = "10\nREASON: I meant action:2";
        Assert.assertFalse("a first-line 10 is rejected, whatever the reasoning says",
                menu.test(outOfRangeFirstLine));
        Assert.assertEquals("and the dispatcher would see the same 10",
                10, menu.numberFor(outOfRangeFirstLine));
        Assert.assertEquals("validator and dispatcher share one parser",
                LLMPlayer.parseFirstNumber(outOfRangeFirstLine), menu.numberFor(outOfRangeFirstLine));

        Assert.assertEquals("an in-range answer dispatches to its own entry",
                "B", menu.descriptionAt(menu.numberFor(inRangeFirstLine)));
    }

    @Test
    public void hugeNumberIsOutOfRangeNotAnException() {
        String huge = "99999999999999999999";
        Assert.assertFalse("a 20-digit answer is simply out of range",
                LLMPlayer.numberInRange(1, 2).test(huge));
        Assert.assertEquals("it saturates above every range instead of throwing",
                Integer.MAX_VALUE, LLMPlayer.parseFirstNumber(huge));
        Assert.assertEquals("no pick", 0, LLMPlayer.pickNumbered(huge, SIDES.size()));
        Assert.assertEquals("Side A", LLMPlayer.pickOrFirst(SIDES, huge));
    }
}
