package org.mage.test.LLM;

import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.player.llm.LLMGameSerializer;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;
import org.mage.test.player.TestPlayer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Prompt-composition regressions driven directly from a real game state.
 *
 * <p>Non-land mana sources must be listable for mana planning: the old
 * section filtered {@code isLand}, so artifact mana could never appear in a
 * mana plan. The mana-vs-non-mana ability split and a size cap stay.
 *
 * <p>An alternative-cost action must not be offered when its cost cannot
 * actually be paid: a {1}{R} dash was offered with zero
 * red sources because {@code ManaCostImpl.canPay} returns true unconditionally.
 *
 * <p>No live model calls — the stub answers "0" and the assertions are on the
 * rendered prompt / action list.
 */
public class LLMManaSourcePromptTest extends CardTestPlayerBaseLLM {

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
            llmPlayer.injectTestClient(new LLMGraveyardLandActionTest.PassLLMClient(llmPlayer.getLLMConfig()));
            TestPlayer testPlayer = new TestPlayer(llmPlayer);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    private static String buildPriorityPrompt(TestPlayer viewingPlayer, List<String> actions) throws Exception {
        Method m = LLMGameSerializer.class.getDeclaredMethod(
                "buildPrompt",
                mage.game.Game.class, java.util.UUID.class, mage.player.llm.LLMConfig.class,
                List.class, int.class, List.class, long.class, java.util.Map.class);
        m.setAccessible(true);
        return (String) m.invoke(null,
                currentGame, viewingPlayer.getId(),
                new mage.player.llm.LLMConfig(),
                new ArrayList<String>(),   // selfLog
                0,                          // lastShownLogSize
                actions,
                0L,                         // boardCursor (0 = force full render)
                new HashMap<String, java.util.UUID>());
    }

    private static mage.player.llm.LLMPlayer llm(TestPlayer p) {
        return (mage.player.llm.LLMPlayer) p.getComputerPlayer();
    }

    // ── Non-land mana sources are listable ────────────────────────────

    @Test
    public void artifactManaSourceAppearsInManaPlanningSection() throws Exception {
        addCard(Zone.BATTLEFIELD, playerA, "Sol Ring", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 1);

        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();

        String prompt = buildPriorityPrompt(playerA, new ArrayList<>());
        String section = section(prompt);
        Assert.assertTrue("section should be renamed to cover non-lands, got:\n" + section,
                prompt.contains("--- Untapped Mana Sources (for mana planning) ---"));
        Assert.assertTrue("Sol Ring (a non-land mana source) must be listable, got:\n" + section,
                section.contains("Sol Ring"));
        Assert.assertTrue("Sol Ring must be marked as a non-land source, got:\n" + section,
                section.contains("[artifact, mana source]"));
        // The old section filtered isLand, so no non-land source could ever appear
        // here with mana options attached.
        boolean solRingHasMana = section.contains("Sol Ring [artifact, mana source] -> {C}");
        Assert.assertTrue("Sol Ring's mana output must be shown on its line, got:\n" + section,
                solRingHasMana);
        Assert.assertTrue("the land must still be listed, got:\n" + section,
                section.contains("Plains"));
    }

    /** A mana source that is not usable right now is not advertised. */
    @Test
    public void tappedArtifactManaSourceIsNotListed() throws Exception {
        addCard(Zone.BATTLEFIELD, playerA, "Sol Ring", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 1);

        runCode("tap the Sol Ring, then re-render", 1, PhaseStep.PRECOMBAT_MAIN, playerA,
                (info, player, game) -> {
                    mage.game.permanent.Permanent solRing = game.getBattlefield()
                            .getAllPermanents().stream()
                            .filter(x -> x.getName().equals("Sol Ring")).findFirst().orElse(null);
                    Assert.assertNotNull("test setup: Sol Ring must be on the battlefield", solRing);
                    // A real ability source keeps the tap legal (ability can be null).
                    mage.abilities.Ability src = new mage.abilities.common.SimpleStaticAbility(
                            Zone.BATTLEFIELD, new mage.abilities.effects.common.InfoEffect("test"));
                    src.setControllerId(playerA.getId());
                    src.setSourceId(solRing.getId());
                    solRing.tap(src, game);  // tap BEFORE rendering, as a used rock would be
                    String sec;
                    try {
                        sec = section(buildPriorityPrompt(playerA, new ArrayList<>()));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    Assert.assertFalse("a tapped Sol Ring cannot be planned, got:\n" + sec,
                            sec.contains("Sol Ring"));
                    Assert.assertTrue("the untapped Plains is still listable, got:\n" + sec,
                            sec.contains("Plains"));
                });

        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();
    }

    /** Section size is capped so a wide board can't flood the prompt. */
    @Test
    public void hugeBoardIsCappedWithAnOmittedNote() throws Exception {
        // 30 islands — over the 24-entry cap.
        addCard(Zone.BATTLEFIELD, playerA, "Island", 30);

        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();

        String prompt = buildPriorityPrompt(playerA, new ArrayList<>());
        String section = section(prompt);
        Assert.assertTrue("cap must be announced so the LLM knows entries exist, got:\n" + section,
                section.contains("more untapped mana sources not listed for prompt size"));
        int listed = 0;
        for (String line : section.split("\n")) {
            if (line.trim().matches("L\\d+:.*")) listed++;
        }
        Assert.assertTrue("at most the cap should be listed (got " + listed + ")", listed <= 24);
        Assert.assertEquals("the cap itself should be reached with 30 Islands", 24, listed);
    }

    private static String section(String prompt) {
        int start = prompt.indexOf("--- Untapped Mana Sources (for mana planning) ---");
        if (start < 0) return "";
        int end = prompt.indexOf("To choose an action", start);
        return end > start ? prompt.substring(start, end) : prompt.substring(start);
    }

    // ── Alternative costs are payability-checked ──────────────────────

    /** Dash {1}{R} with NO red source must not be offered. */
    @Test
    public void unpayableDashNotOffered() {
        addCard(Zone.HAND, playerA, "Ragavan, Nimble Pilferer", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 3);  // blue only, and {1} is payable

        runCode("assert dash is not offered", 1, PhaseStep.PRECOMBAT_MAIN, playerA,
                (info, player, game) -> {
                    List<String> actions = llm(playerA).actionDescriptionsForTest(game);
                    Assert.assertFalse("dash {1}{R} is unpayable with only Islands — must not be offered."
                                    + " Actions: " + actions,
                            actions.stream().anyMatch(a -> a.contains("Ragavan") && a.contains("Dash")));
                });

        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();
    }

    /** Same card, red mana available → dash IS offered (the gate is payability, not a blanket ban). */
    @Test
    public void payableDashIsOffered() {
        addCard(Zone.HAND, playerA, "Ragavan, Nimble Pilferer", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 2);

        runCode("assert dash is offered", 1, PhaseStep.PRECOMBAT_MAIN, playerA,
                (info, player, game) -> {
                    List<String> actions = llm(playerA).actionDescriptionsForTest(game);
                    Assert.assertTrue("dash {1}{R} is payable with 2 Mountains — must be offered."
                                    + " Actions: " + actions,
                            actions.stream().anyMatch(a -> a.contains("Ragavan") && a.contains("Dash")));
                });

        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();
    }
}
