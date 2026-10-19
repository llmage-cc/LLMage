package org.mage.test.LLM;

import mage.abilities.Ability;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.effects.common.SacrificeEffect;
import mage.constants.Outcome;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.filter.FilterPermanent;
import mage.filter.StaticFilters;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.player.llm.LLMClient;
import mage.player.llm.LLMConfig;
import mage.player.llm.LLMResponse;
import mage.target.TargetAmount;
import mage.target.common.TargetPermanentAmount;
import mage.target.common.TargetSacrifice;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;
import org.mage.test.player.TestPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Regression for the P1 LLM target-controller defect.
 *
 * <p><b>Defect.</b> {@code LLMPlayer.chooseTarget} and
 * {@code LLMPlayer.chooseTargetAmount} derived their candidate set with
 * {@code source.getControllerId()} (the ABILITY controller) instead of
 * {@code target.getAffectedAbilityControllerId(getId())} (the CHOOSING player),
 * unlike {@code HumanPlayer} (5 sites) and {@code ComputerPlayer} (2 sites).
 * {@code TargetImpl.getAffectedAbilityControllerId(choosingPlayerId)} returns the
 * explicit ability controller when set, otherwise the choosing player.</p>
 *
 * <p>For an annihilator / attack-trigger sacrifice ({@code AnnihilatorAbility} →
 * {@code SacrificeEffect} → {@code TargetSacrifice}) the ability controller hint is
 * NULL, so the standard path scopes candidates to the CHOOSING player — the
 * DEFENDING player, who actually sacrifices. The trigger belongs to the ATTACKER,
 * so {@code source.getControllerId()} is the attacker: the buggy LLM path offered
 * the ATTACKER's permanents to the DEFENDER. The same defect surfaces for
 * off-controller picks (Show and Tell: the second chooser is offered the caster's hand).</p>
 *
 * <p><b>Fixture.</b> PlayerB is the LLM seat (override of
 * {@code getFullSimulatedPlayers}), so the LLM is the DEFENDER/chooser while the
 * plain scripted PlayerA is the ATTACKER/ability controller — exactly the
 * split that makes the two controller ids differ. A prompt-capturing stub client
 * (no network) records every prompt and answers the target prompt with two
 * indices.</p>
 *
 * <p><b>Legs.</b></p>
 * <ul>
 *   <li>{@link #chooseTargetOffersTheChoosingPlayersPermanents} — drives the real
 *       {@code TargetSacrifice.choose(...)} path on the LLM defender for an
 *       attacker-controlled sacrifice trigger and asserts (a) the offered prompt
 *       lists the DEFENDER's permanents and not the attacker's, and (b) the chosen
 *       ids are the defender's.</li>
 *   <li>{@link #chooseTargetAmountDerivesCandidatesForTheChoosingPlayer} — pins the
 *       second changed line directly. It uses a {@link ChooserScopedPermanentAmount}
 *       (see its javadoc for why the stock {@code TargetPermanentAmount} cannot
 *       discriminate here) and asserts the amount-target is offered the defender's
 *       permanent.</li>
 * </ul>
 *
 * <p><b>Negative control (stash-probe).</b> With the two-line LLMPlayer fix
 * reverted, BOTH legs fail: {@link #chooseTargetOffersTheChoosingPlayersPermanents}
 * fails on "Grizzly Bears" (the attacker's permanent) appearing in the defender's
 * target prompt, and the amount leg targets the attacker's permanent. With the fix
 * restored they pass.</p>
 */
public class LLMTargetControllerScopeTest extends CardTestPlayerBaseLLM {

    /** Prompt-capturing stub: records every prompt, answers target prompts with indices. */
    static class TargetCapturingClient extends LLMClient {
        final List<String> prompts = new ArrayList<>();

        TargetCapturingClient(LLMConfig config) {
            super(config);
        }

        @Override
        public LLMResponse chatConversation(String fullPrompt, String compactSummary,
                                            String promptType, Integer turn, String phaseStep,
                                            String effortOverride) {
            prompts.add(fullPrompt);
            return answerFor(fullPrompt);
        }

        @Override
        public LLMResponse chatConversationWithStop(String fullPrompt, String compactSummary,
                                                    String promptType, Integer turn, String phaseStep,
                                                    String effortOverride, String stopSequence) {
            prompts.add(fullPrompt);
            return answerFor(fullPrompt);
        }

        private LLMResponse answerFor(String fullPrompt) {
            if (fullPrompt == null) {
                return new LLMResponse("stub", "no");
            }
            if (fullPrompt.contains("=== TARGET WITH AMOUNT ===")) {
                // chooseTargetAmount contract: "TARGET AMOUNT" (e.g. "3 5").
                return new LLMResponse("stub", "1 5");
            }
            if (fullPrompt.contains("=== TARGET SELECTION ===")) {
                // Two indices: TargetSacrifice above has min == max == 2.
                return new LLMResponse("stub", "1,2");
            }
            return new LLMResponse("stub", "no");
        }

        String firstTargetPrompt() {
            for (String p : prompts) {
                if (p != null && p.contains("=== TARGET SELECTION ===")) {
                    return p;
                }
            }
            return null;
        }

        String firstAmountPrompt() {
            for (String p : prompts) {
                if (p != null && p.contains("=== TARGET WITH AMOUNT ===")) {
                    return p;
                }
            }
            return null;
        }
    }

    private TargetCapturingClient captureClient;

    @Override
    public List<String> getFullSimulatedPlayers() {
        // The LLM is the DEFENDER (chooser) so the ability controller (attacker)
        // differs from the choosing player — the exact split the defect needs.
        return Arrays.asList("PlayerB");
    }

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if (getFullSimulatedPlayers().contains(name)) {
            TestLLMPlayer llmPlayer = new TestLLMPlayer(name, RangeOfInfluence.ONE, 0);
            llmPlayer.getLLMConfig()
                    .apiEndpoint("http://localhost:11434/v1/chat/completions")
                    .model("deepseek-v4-flash:cloud")  // fixture policy: flash, never pro
                    .temperature(0.0)
                    .maxTokens(65536)
                    .timeoutSeconds(120)
                    .deckAssessmentEnabled(false)
                    .verboseLogging(false);
            llmPlayer.reconnect();
            // Inject AFTER reconnect — reconnect() replaces the client wholesale.
            captureClient = new TargetCapturingClient(llmPlayer.getLLMConfig());
            llmPlayer.injectTestClient(captureClient);
            TestPlayer testPlayer = new TestPlayer(llmPlayer);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    @BeforeClass
    public static void checkLLMAvailable() {
        // Stub-only: no live model call happens.
        Assume.assumeFalse("LLM tests skipped (set -Dxmage.llm.skip=false to run)",
                Boolean.getBoolean("xmage.llm.skip"));
    }

    /** The attacker's sacrifice trigger: ability controlled by the attacker, effect on the defender. */
    private static Ability attackerSacrificeTrigger(Game game, UUID attackerId, UUID attackerPermanentId,
                                                    FilterPermanent filter, int count) {
        Ability trigger = new SimpleStaticAbility(Zone.BATTLEFIELD,
                new SacrificeEffect(filter, count, ""));
        trigger.setSourceId(attackerPermanentId);
        trigger.setControllerId(attackerId);
        return trigger;
    }

    private TestLLMPlayer llmSeat() {
        return (TestLLMPlayer) ((TestPlayer) playerB).getComputerPlayer();
    }

    // ── leg 1: chooseTarget ─────────────────────────────────────────────────

    @Test
    public void chooseTargetOffersTheChoosingPlayersPermanents() {
        // Attacker (PlayerA): the annihilator source + permanents that must NOT be offered.
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears", 2);
        // Defender (PlayerB, the LLM): the permanents that MUST be offered.
        addCard(Zone.BATTLEFIELD, playerB, "Silvercoat Lion", 2);

        runCode("drive attacker-controlled sacrifice on the LLM defender", 1, PhaseStep.PRECOMBAT_MAIN,
                playerA, (info, p, game) -> {
            TestLLMPlayer llm = llmSeat();
            UUID llmId = llm.getId();                 // defender / chooser
            UUID attackerId = playerA.getId();        // ability controller

            Permanent attackerPerm = game.getBattlefield().getAllActivePermanents(
                    StaticFilters.FILTER_PERMANENT_CREATURE, game).stream()
                    .filter(x -> x.isControlledBy(attackerId)).findFirst().orElse(null);
            Assert.assertNotNull("setup: attacker must have a permanent to host the trigger", attackerPerm);

            Ability trigger = attackerSacrificeTrigger(game, attackerId, attackerPerm.getId(),
                    StaticFilters.FILTER_CONTROLLED_PERMANENTS, 2);

            // Annihilator shape: the defender chooses which of THEIR permanents to sacrifice.
            TargetSacrifice target = new TargetSacrifice(2, StaticFilters.FILTER_CONTROLLED_PERMANENTS.copy());

            boolean chosen = target.choose(Outcome.Sacrifice, llmId, trigger.getSourceId(), trigger, game);
            Assert.assertTrue("the defender must be able to complete the sacrifice selection", chosen);

            String prompt = captureClient.firstTargetPrompt();
            Assert.assertNotNull("the LLM seat must have been prompted for the sacrifice targets", prompt);
            // The Source: line names the ability's source (the ATTACKER's creature,
            // which hosts the trigger) — assert on the OFFERED LIST only, not the
            // whole prompt.
            String offered = prompt.substring(prompt.indexOf("Available targets:"));
            Assert.assertTrue("the defender's own permanent must be offered:\n" + offered,
                    offered.contains("Silvercoat Lion"));
            Assert.assertFalse("the ATTACKER's permanent must NEVER be offered to the defender:\n" + offered,
                    offered.contains("Grizzly Bears"));

            Assert.assertEquals("exactly two permanents sacrificed", 2, target.getTargets().size());
            for (UUID id : target.getTargets()) {
                Permanent perm = game.getPermanent(id);
                Assert.assertNotNull("targeted id must be a live permanent", perm);
                Assert.assertTrue("chosen permanent must be controlled by the CHOOSING player, got "
                                + perm.getName() + " controlled by " + perm.getControllerId(),
                        perm.isControlledBy(llmId));
                Assert.assertFalse("chosen permanent must not be the ability controller's",
                        perm.isControlledBy(attackerId));
            }
        });

        setStopAt(1, PhaseStep.END_TURN);
        execute();
    }

    // ── leg 2: chooseTargetAmount ───────────────────────────────────────────

    /**
     * Stock {@code TargetPermanentAmount} (and every {@code TargetAmount} subclass)
     * ignores the passed {@code playerId} inside {@code canTarget(...)}, so it cannot
     * discriminate the chooser here regardless of the LLM fix; that playerId-ignoring
     * behaviour is a separate, pre-existing core concern and out of scope. This
     * subclass restores the intended chooser-scoping so the LLMPlayer derivation
     * change is what the assertion actually exercises.
     */
    static class ChooserScopedPermanentAmount extends TargetPermanentAmount {
        ChooserScopedPermanentAmount(int amount, int min, int max, FilterPermanent filter) {
            super(amount, min, max, filter);
        }

        @Override
        public boolean canTarget(UUID playerId, UUID objectId, Ability source, Game game) {
            Permanent permanent = game.getPermanent(objectId);
            return permanent != null && filter.match(permanent, playerId, source, game);
        }
    }

    @Test
    public void chooseTargetAmountDerivesCandidatesForTheChoosingPlayer() {
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears", 2);
        addCard(Zone.BATTLEFIELD, playerB, "Silvercoat Lion", 2);

        runCode("drive an attacker-controlled amount target on the LLM defender", 1, PhaseStep.PRECOMBAT_MAIN,
                playerA, (info, p, game) -> {
            TestLLMPlayer llm = llmSeat();
            UUID llmId = llm.getId();
            UUID attackerId = playerA.getId();

            Permanent attackerPerm = game.getBattlefield().getAllActivePermanents(
                    StaticFilters.FILTER_PERMANENT_CREATURE, game).stream()
                    .filter(x -> x.isControlledBy(attackerId)).findFirst().orElse(null);
            Assert.assertNotNull("setup: attacker must have a permanent to host the trigger", attackerPerm);

            Ability trigger = attackerSacrificeTrigger(game, attackerId, attackerPerm.getId(),
                    StaticFilters.FILTER_CONTROLLED_PERMANENTS, 5);

            TargetAmount amountTarget = new ChooserScopedPermanentAmount(
                    5, 1, 3, StaticFilters.FILTER_CONTROLLED_PERMANENTS.copy());

            boolean chosen = llm.chooseTargetAmount(Outcome.Damage, amountTarget, trigger, game);
            Assert.assertTrue("the defender must be able to complete the amount selection", chosen);

            String prompt = captureClient.firstAmountPrompt();
            Assert.assertNotNull("the LLM seat must have been prompted for the amount target", prompt);
            String offered = prompt.substring(prompt.indexOf("Available targets:"));
            Assert.assertTrue("the defender's own permanent must be offered:\n" + offered,
                    offered.contains("Silvercoat Lion"));
            Assert.assertFalse("the ATTACKER's permanent must NEVER be offered to the defender:\n" + offered,
                    offered.contains("Grizzly Bears"));

            Assert.assertEquals("exactly one permanent selected", 1, amountTarget.getTargets().size());
            UUID chosenId = amountTarget.getTargets().iterator().next();
            Permanent perm = game.getPermanent(chosenId);
            Assert.assertNotNull("the chosen id must be a live permanent", perm);
            Assert.assertTrue("the amount target must be the CHOOSING player's permanent",
                    perm.isControlledBy(llmId));
        });

        setStopAt(1, PhaseStep.END_TURN);
        execute();
    }
}
