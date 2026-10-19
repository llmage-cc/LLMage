package org.mage.test.LLM;

import mage.choices.Choice;
import mage.choices.ChoiceHintType;
import mage.choices.ChoiceImpl;
import mage.constants.MultiplayerAttackOption;
import mage.constants.Outcome;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.TwoPlayerDuel;
import mage.game.mulligan.MulliganType;
import mage.player.llm.LLMPlayer;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;
import org.mage.test.player.TestPlayer;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.List;

/**
 * Regression tests for the Dash alt-cost cast inversion

 *
 * <p>Engine chain (re-derived from source, 2026-09-29 — every link verified):
 * <ol>
 * <li>{@code AbilityImpl.activateAlternateOrAdditionalCosts} (Mage AbilityImpl.java:605-654)
 *     opens a keyChoices-ONLY choice for EVERY cast that has any alternative cost
 *     available — key "1" = the alternative (e.g. Dash), last key =
 *     "Cast with no alternative cost: {1}{R}"; {@code getChoices()} stays empty —
 *     and then calls {@code player.choose(Outcome.Benefit, choice, game)}.</li>
 * <li>{@code LLMPlayer.doCastSpell} (2506, cleared in finally :2512) sets
 *     {@code pendingAltCost = a.useAltCost} — FALSE for the normal-cast entry
 *     (the ActionEntry shape at :2285, not the :2292 alt-cost one).</li>
 * <li>{@code LLMPlayer.choose} (2799-2820) skips its auto-accept branch
 *     (pendingAltCost false), sees the EMPTY {@code getChoices()} of the key-only
 *     dialog, and falls through to {@code super.choose(...)}.</li>
 * <li>{@code ComputerPlayer.choose} (Mage.Player.AI ComputerPlayer.java:829-832)
 *     then calls {@code choice.setRandomChoice()}, and
 *     {@code ChoiceImpl.setRandomChoice} (ChoiceImpl.java:275-292) HAS a
 *     key-mode branch that picks a RANDOM key — a uniform coin flip between
 *     executing the Dash cast and the cast the LLM actually chose.</li>
 * </ol>
 *
 * <p>That is the observed inversion: the LLM chose the normal Ragavan cast
 * (creature stays on board) and the engine executed the Dash cast (returns to
 * hand at end step) with no LLM exchange logged. Pre-fix, the random fallback
 * reproduces the bug ~50% per normal cast of a dashable creature.
 *
 * <p>Contract after the fix: {@link LLMPlayer#choose} must answer the engine's
 * alternative-cost dialog deterministically — auto-accept option "1" ONLY when
 * the LLM chose an explicit alt-cost action (pendingAltCost=true), and choose
 * the "Cast with no alternative cost" key for every normal cast. Never drop an
 * alternative-cost dialog to the random ComputerPlayer fallback (Fail Fast).
 */
public class LLMAltCostChoiceTest extends CardTestPlayerBaseLLM {

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if (getFullSimulatedPlayers().contains(name)) {
            TestLLMPlayer llmPlayer = new TestLLMPlayer(name, RangeOfInfluence.ONE, 0);
            llmPlayer.getLLMConfig()
                    .apiEndpoint("http://localhost:11434/v1/chat/completions")
                    .model("deepseek-v4-flash:cloud")
                    .maxTokens(65536)
                    .timeoutSeconds(120)
                    .deckAssessmentEnabled(false)
                    .verboseLogging(false);
            llmPlayer.reconnect();
            // Stub after reconnect (reconnect replaces the client wholesale).
            llmPlayer.injectTestClient(new PassLLMClient(llmPlayer.getLLMConfig()));
            TestPlayer testPlayer = new TestPlayer(llmPlayer);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    /** Answers "0" (pass priority) to everything — no network. */
    static class PassLLMClient extends mage.player.llm.LLMClient {
        PassLLMClient(mage.player.llm.LLMConfig config) { super(config); }

        @Override
        public mage.player.llm.LLMResponse chatConversation(String fullPrompt, String compactSummary,
                                                            String promptType, Integer turn, String phaseStep,
                                                            String effortOverride) {
            return new mage.player.llm.LLMResponse("stub", "0");
        }
    }

    private static LLMPlayer llm(TestPlayer p) {
        return (LLMPlayer) p.getComputerPlayer();
    }

    private static Game game() {
        return new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE,
                MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);
    }

    private static TestLLMPlayer player() {
        // TestPlayer's ctor sets the testPlayerLink that TestLLMPlayer.choose()
        // reads (its choose delegates → super.choose = LLMPlayer.choose while
        // canChooseByComputer() is true, the default non-strict mode).
        TestLLMPlayer llm = new TestLLMPlayer("AltCostBot", RangeOfInfluence.ONE, 0);
        new TestPlayer(llm);
        return llm;
    }

    private static void setPendingAltCost(LLMPlayer p, boolean value) {
        try {
            Field f = LLMPlayer.class.getDeclaredField("pendingAltCost");
            f.setAccessible(true);
            f.setBoolean(p, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("cannot set pendingAltCost on LLMPlayer", e);
        }
    }

    /** The exact dialog shape AbilityImpl builds for a normal Ragavan cast with Dash available. */
    private static ChoiceImpl altCostDialog() {
        ChoiceImpl choice = new ChoiceImpl(false);
        choice.setMessage("You may choose an alternative cost");
        choice.setSubMessage("for casting Ragavan, Nimble Pilferer");
        choice.withItem("1",
                "Cast Ragavan, Nimble Pilferer with Dash {1}{R} (return it to its owner's hand "
                        + "at the beginning of the next end step)",
                0, ChoiceHintType.GAME_OBJECT, "00000000-0000-0000-0000-000000000001");
        choice.withItem("2",
                "Cast with no alternative cost: {1}{R}",
                1, ChoiceHintType.GAME_OBJECT, "00000000-0000-0000-0000-000000000002");
        return choice;
    }

    /** Mandatory dialog variant (no no-alt item) — LLMPlayer never supports it; must fail fast. */
    private static ChoiceImpl mandatoryDialog() {
        ChoiceImpl choice = new ChoiceImpl(false);
        choice.setMessage("Choose an alternative cost");
        choice.setSubMessage("for casting Ragavan, Nimble Pilferer");
        choice.withItem("1",
                "Cast Ragavan, Nimble Pilferer with Dash {1}{R}",
                0, ChoiceHintType.GAME_OBJECT, "00000000-0000-0000-0000-000000000001");
        return choice;
    }

    /**
     * THE BUG: a NORMAL cast (pendingAltCost=false) with Dash available must NEVER
     * be executed with Dash. 200 trials because the pre-fix behavior is random:
     * ChoiceImpl.setRandomChoice coin-flips the keyChoices, so a single trial
     * passes ~50% of the time by luck; all 200 pass only deterministically.
     */
    @Test
    public void normalCastAlwaysAnswersNoAlternativeCost() {
        LLMPlayer p = player();
        setPendingAltCost(p, false); // the LLM chose the NORMAL cast (useAltCost=false)
        Game game = game();
        for (int i = 0; i < 200; i++) {
            ChoiceImpl choice = altCostDialog();
            boolean handled = p.choose(Outcome.Benefit, choice, game);
            Assert.assertTrue("trial " + i + ": choose() must handle the engine's alternative-cost dialog", handled);
            Assert.assertEquals("trial " + i + ": engine must NOT randomize a Dash cast the LLM cast normally",
                    "Cast with no alternative cost: {1}{R}", choice.getChoiceValue());
            Assert.assertEquals("trial " + i + ": wrong key chosen", "2", choice.getChoiceKey());
        }
    }

    /** Explicit alt-cost action (pendingAltCost=true) keeps the auto-accept-option-1 behavior. */
    @Test
    public void explicitAltCostStillAutoAcceptsOption1() {
        TestLLMPlayer p = player();
        setPendingAltCost(p, true);
        ChoiceImpl choice = altCostDialog();
        boolean handled = p.choose(Outcome.Benefit, choice, game());
        Assert.assertTrue(handled);
        Assert.assertEquals("1", choice.getChoiceKey());
        Assert.assertTrue(choice.getChoiceValue().contains("with Dash"));
    }

    /**
     * Fail Fast: if the engine prese
     * nts the MANDATORY alt-cost dialog ("Choose an alternative cost" — no
     * "no alternative cost" item) to a normal cast, LLMPlayer must crash loudly
     * instead of letting ComputerPlayer guess.
     */
    @Test
    public void mandatoryDialogWithoutNoAltItemFailsFast() {
        TestLLMPlayer p = player();
        setPendingAltCost(p, false);
        ChoiceImpl choice = mandatoryDialog();
        try {
            p.choose(Outcome.Benefit, choice, game());
            Assert.fail("normal cast hit a mandatory alternative-cost dialog with no "
                    + "\"Cast with no alternative cost\" item — must fail fast, not random-guess");
        } catch (RuntimeException expected) {
            // the ONLY acceptable exit: crash carrying the dialog in the message
            Assert.assertTrue("message must carry the dialog: " + expected.getMessage(),
                    expected.getMessage().contains("alternative cost"));
        }
    }

    /** Guard pins the dialog's wire shape: key-only choices, keys "1","2" in order. */
    @Test
    public void engineDialogShapeIsKeyOnly() {
        ChoiceImpl choice = altCostDialog();
        Assert.assertTrue("engine alt-cost dialog is key mode", choice.isKeyChoice());
        Assert.assertTrue("key-only dialog: getChoices() must be empty (the LLM fall-through trap)",
                choice.getChoices().isEmpty());
        Iterator<String> keys = choice.getKeyChoices().keySet().iterator();
        Assert.assertEquals("1", keys.next());
        Assert.assertEquals("2", keys.next());
    }

    // ── Controller-scoped alt costs ─────────────────────────────────────

    /**
     * Omniscience ("You may cast nonland cards from your hand without paying their
     * mana costs") registers its AlternativeCostSourceAbility on the CONTROLLER
     * (PlayerImpl.getAlternativeSourceCosts), not on the card — pre-fix the action
     * list scan only looked at card.getAbilities(game), so the free cast produced
     * no action entry and the LLM could only pick the unpayable normal cast

     *
     * <p>Board: Omniscience on the LLM seat's battlefield; hand has Acererak the
     * Archlich ({2}{B}) with NO black sources (only Islands) so the normal cast is
     * unpayable-by-color but the free cast is the only way to cast it.
     */
    @Test
    public void controllerAltCostFreeCastOffered() {
        addCard(Zone.BATTLEFIELD, playerA, "Omniscience", 1);
        addCard(Zone.HAND, playerA, "Acererak the Archlich", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 3);

        runCode("snapshot the offered actions", 1, PhaseStep.PRECOMBAT_MAIN, playerA,
                (info, player, game) -> {
                    List<String> actions = llm(playerA).actionDescriptionsForTest(game);
                    Assert.assertTrue("Omniscience must offer a free-cast entry for Acererak,"
                                    + " got: " + actions,
                            actions.stream().anyMatch(a -> a.startsWith("Cast: Acererak the Archlich — ")
                                    && a.contains("without paying")));
                    // Pin the wire shape (AlternativeCostSourceAbility.getAlternativeCostText
                    // + buildActionList's "Cast: <name> — <alttext>" template; the source
                    // suffix carries the engine's HTML log decoration with a per-game
                    // object_id, so assert the stable fragments).
                    Assert.assertTrue("exact free-cast text, got: " + actions,
                            actions.stream().anyMatch(a -> a.startsWith(
                                    "Cast: Acererak the Archlich — Cast without paying its mana cost (source: ")
                                    && a.contains("Omniscience")));
                    Assert.assertTrue("the normal-cast entry must remain alongside, got: " + actions,
                            actions.stream().anyMatch(a -> a.equals("Cast: Acererak the Archlich ({2}{B})")));
                });

        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();
    }

    /**
     * Gate guard: without Omniscience, no free-cast text may appear for Acererak
     * (advertising it would be the opposite bug — an entry the engine rejects).
     */
    @Test
    public void noControllerAltCostNoFreeCastEntry() {
        addCard(Zone.HAND, playerA, "Acererak the Archlich", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 3);

        runCode("snapshot the offered actions", 1, PhaseStep.PRECOMBAT_MAIN, playerA,
                (info, player, game) -> {
                    List<String> actions = llm(playerA).actionDescriptionsForTest(game);
                    Assert.assertFalse("no Omniscience → no free-cast entry, got: " + actions,
                            actions.stream().anyMatch(a -> a.contains("Acererak")
                                    && a.contains("without paying")));
                });

        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();
    }
}