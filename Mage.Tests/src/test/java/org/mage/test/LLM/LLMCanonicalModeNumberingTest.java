package org.mage.test.LLM;

import mage.abilities.Mode;
import mage.abilities.Modes;
import mage.abilities.effects.Effect;
import mage.abilities.effects.common.DestroyAllEffect;
import mage.filter.common.FilterArtifactPermanent;
import mage.player.llm.LLMPlayer;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Unit tests for chooseMode()'s canonical mode numbering and answer
 * resolution.
 *
 * The FATAL scenario: a "choose two" spell's second chooseMode call saw a
 * re-numbered, shorter list (mode 4 filtered out), the model repeated the
 * card's printed mode number, and the old positional parse threw
 * RuntimeException → engine auto-restored the whole turn.
 *
 * The fix numbers modes CANONICALLY (position in the card's full printed
 * mode list, modes.values() order — the same numbering the game log prints
 * as "(mode N)"), so numbers stay stable across successive calls. These
 * tests exercise the extracted static helpers; no Game/LLM fixture needed.
 */
public class LLMCanonicalModeNumberingTest {

    /**
     * Build a Modes set shaped like Austere Command (4 modes, choose two),
     * mirroring production construction: the Modes() default mode receives
     * the first effect (as AbilityImpl.addEffect does), then three addMode
     * calls. clearSelectedModes() matches what Modes.choose() does before
     * its choice loop, so getSelectedStats reflects only THIS selection.
     */
    private Modes austereCommandModes() {
        Modes modes = new Modes();
        modes.setMinModes(2);
        modes.setMaxModes(2);
        // Default mode (canonical 1): destroy all artifacts
        modes.getMode().getEffects().add(new DestroyAllEffect(new FilterArtifactPermanent("artifacts")));
        modes.addMode(new Mode(effectWithText("destroy all enchantments")));
        modes.addMode(new Mode(effectWithText("destroy creatures with mana value 3 or less")));
        modes.addMode(new Mode(effectWithText("destroy creatures with mana value 4 or greater")));
        modes.clearSelectedModes();  // Modes.choose() clears before its choice loop
        return modes;
    }

    private static Effect effectWithText(String text) {
        return new DestroyAllEffect(new FilterArtifactPermanent(text)) {
            @Override
            public String getText(Mode mode) {
                return text;
            }
        };
    }

    private List<Mode> modesByIds(Modes modes, int... canonicalPositions) {
        List<Mode> all = new ArrayList<>(modes.values());
        List<Mode> res = new ArrayList<>();
        for (int pos : canonicalPositions) {
            res.add(all.get(pos - 1));
        }
        return res;
    }

    /**
     * Scenario from the FATAL: mode 4 was chosen first; the second call's
     * choosable list is [1, 2, 3]. Canonical numbering must show 1, 2, 3 —
     * NOT renumber to 1, 2, 3-as-4.
     */
    @Test
    public void testNumberingStableWithChosenModeFilteredOut() {
        Modes modes = austereCommandModes();
        // Simulate the engine having already selected canonical mode 4
        modes.addSelectedMode(modesByIds(modes, 4).get(0).getId());

        List<Mode> choosable = modesByIds(modes, 1, 2, 3);
        List<LLMPlayer.CanonicallyNumberedMode> choices = LLMPlayer.canonicalModeChoices(modes, choosable);

        List<Integer> numbers = choices.stream()
                .map(c -> c.canonicalNumber).collect(Collectors.toList());
        Assert.assertEquals("filtered list must keep printed numbers, not renumber",
                Arrays.asList(Integer.valueOf(1), Integer.valueOf(2), Integer.valueOf(3)), numbers);
        // And the mode objects behind those numbers are the right ones
        List<Mode> all = new ArrayList<>(modes.values());
        Assert.assertSame(all.get(0), choices.get(0).mode);
        Assert.assertSame(all.get(1), choices.get(1).mode);
        Assert.assertSame(all.get(2), choices.get(2).mode);
    }

    /** Middle mode missing: choosable [1, 3, 4] must number as 1, 3, 4. */
    @Test
    public void testNumberingSkipsUnchoosableMiddleMode() {
        Modes modes = austereCommandModes();
        List<Mode> choosable = modesByIds(modes, 1, 3, 4);
        List<LLMPlayer.CanonicallyNumberedMode> choices = LLMPlayer.canonicalModeChoices(modes, choosable);

        List<Integer> numbers = choices.stream()
                .map(c -> c.canonicalNumber).collect(Collectors.toList());
        Assert.assertEquals(Arrays.asList(1, 3, 4), numbers);
    }

    /**
     * The FATAL case verbatim: second call, choosable [1, 2, 3], model
     * answers the printed number "4" it wants (which is already chosen).
     * That must NOT resolve — but "3" (a displayed number) must.
     */
    @Test
    public void testAnswerResolutionOnlyAcceptsDisplayedCanonicalNumbers() {
        Modes modes = austereCommandModes();
        List<Mode> choosable = modesByIds(modes, 1, 2, 3);
        List<LLMPlayer.CanonicallyNumberedMode> choices = LLMPlayer.canonicalModeChoices(modes, choosable);

        // Valid displayed numbers resolve to the right Mode
        List<Mode> all = new ArrayList<>(modes.values());
        Assert.assertSame("answer 1", all.get(0), LLMPlayer.resolveCanonicalModeAnswer(choices, "1"));
        Assert.assertSame("answer 2", all.get(1), LLMPlayer.resolveCanonicalModeAnswer(choices, "2"));
        Assert.assertSame("answer 3", all.get(2), LLMPlayer.resolveCanonicalModeAnswer(choices, "3"));

        // Out-of-range (mode 4 already chosen / not displayed) → null, no
        // positional fallback onto canonical mode 3
        Assert.assertNull("answer 4 must not fall back positionally",
                LLMPlayer.resolveCanonicalModeAnswer(choices, "4"));
        Assert.assertNull("answer 5", LLMPlayer.resolveCanonicalModeAnswer(choices, "5"));
        Assert.assertNull("answer 0", LLMPlayer.resolveCanonicalModeAnswer(choices, "0"));
        Assert.assertNull("prose", LLMPlayer.resolveCanonicalModeAnswer(choices, "destroy artifacts please"));
        Assert.assertNull("empty", LLMPlayer.resolveCanonicalModeAnswer(choices, ""));
        Assert.assertNull("null answer", LLMPlayer.resolveCanonicalModeAnswer(choices, null));
    }

    /** "3\nReasoning: ..." resolves — first-line parsing, as in production. */
    @Test
    public void testAnswerWithTrailingReasoningStillResolves() {
        Modes modes = austereCommandModes();
        List<Mode> choosable = modesByIds(modes, 1, 2, 3);
        List<LLMPlayer.CanonicallyNumberedMode> choices = LLMPlayer.canonicalModeChoices(modes, choosable);
        List<Mode> all = new ArrayList<>(modes.values());

        Assert.assertSame(all.get(2),
                LLMPlayer.resolveCanonicalModeAnswer(choices, "3\nReasoning: the board has no artifacts"));
    }

    /** The prompt's already-chosen line uses canonical numbers. */
    @Test
    public void testAlreadyChosenDisplayUsesCanonicalNumbers() {
        Modes modes = austereCommandModes();
        Assert.assertEquals("nothing chosen yet → no line",
                "", LLMPlayer.alreadyChosenModesDisplay(modes));

        // Chose canonical mode 4 (creatures MV>=4) first
        modes.addSelectedMode(modesByIds(modes, 4).get(0).getId());
        String display = LLMPlayer.alreadyChosenModesDisplay(modes);
        Assert.assertEquals("You already chose mode(s): 4\n", display);

        // Choose two: mode 1 also selected now
        modes.addSelectedMode(modesByIds(modes, 1).get(0).getId());
        display = LLMPlayer.alreadyChosenModesDisplay(modes);
        Assert.assertEquals("You already chose mode(s): 1, 4\n", display);
    }

    /** Same mode chosen twice (mayChooseSameModeMoreThanOnce) is listed once. */
    @Test
    public void testDuplicateSelectionListedOncePerCanonicalNumber() {
        Modes modes = austereCommandModes();
        modes.setMayChooseSameModeMoreThanOnce(true);
        Mode mode2 = modesByIds(modes, 2).get(0);
        modes.addSelectedMode(mode2.getId());
        modes.addSelectedMode(mode2.getId());  // duplicate selection
        String display = LLMPlayer.alreadyChosenModesDisplay(modes);
        Assert.assertEquals("You already chose mode(s): 2\n", display);
    }

    /** The correction loop is bounded: 3 total attempts, then throw. */
    @Test
    public void testAttemptBoundIsThree() {
        Assert.assertEquals(3, LLMPlayer.MODE_CHOICE_MAX_ATTEMPTS);
    }
}