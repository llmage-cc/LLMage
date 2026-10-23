package org.mage.test.game;

import mage.abilities.Ability;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.effects.common.DestroyTargetEffect;
import mage.abilities.effects.common.InfoEffect;
import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.filter.StaticFilters;
import mage.game.permanent.Permanent;
import mage.target.TargetPermanent;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

/**
 * Bot auto-target feel-bad guard

 * <p>
 * {@code TargetImpl.tryToAutoChoose} used to hardcode {@code playerAutoTargetLevel = 2}
 * (AUTO_TARGET_ALL) for every non-human seat, which skips the own-permanent feel-bad filter in
 * its selection loop. A bot's removal spell therefore silently auto-targeted its OWN permanent
 * when that was the only legal target (at T11: Dark Banishing auto-killed the caster's
 * own Ephara's Warden, no target prompt). The fix sets level 1 (AUTO_TARGET_NON_FEEL_BAD).
 * <p>
 * Legs:
 * <ul>
 *   <li>{@link #botDoesNotAutoChooseItsOwnPermanentForARemovalEffect} - a bot seat that must pick
 *       exactly one target, where the only legal candidate is a permanent it controls, gets
 *       {@code null} back (so the caller falls through to the player's own chooseTarget path and
 *       the seat is prompted).</li>
 *   <li>{@link #botStillAutoChoosesAnOpponentsPermanent} - positive control: the same target with
 *       only an opponent's permanent as a candidate still auto-chooses it, so the guard does not
 *       stop ordinary auto-targeting (no extra prompts / no latency regression).</li>
 * </ul>
 * The test seats are TestPlayers. A scripted TestPlayer does NOT take the bot branch any more
 * ({@code isComputer() == AIRealGameSimulation} defaults to false for it,
 * so it keeps upstream's level 2), so each leg turns the harness's bot emulation on with
 * {@code setAIPlayer(true)} — the same flag that makes {@code priority()} call
 * {@code changeAIControl(game, true)} — and asserts the seat really reports
 * {@code isComputer() == true} before measuring. A plain TestPlayer is covered by
 * {@code BotAutoTargetLevelPinTest}.
 */
public class BotAutoTargetFeelBadTest extends CardTestPlayerBase {

    private static Ability destroySource() {
        // rule text carrying "destroy" - the string the feel-bad filter matches on.
        // staticText is set explicitly so the match does not depend on how
        // DestroyTargetEffect renders a bare (target-less) rules text.
        Ability source = new SimpleStaticAbility(Zone.BATTLEFIELD, new DestroyTargetEffect("destroy target creature"));
        return source;
    }

    @Test
    public void botDoesNotAutoChooseItsOwnPermanentForARemovalEffect() {
        addCard(Zone.BATTLEFIELD, playerA, "Silvercoat Lion", 1);
        playerA.setAIPlayer(true); // take the genuine bot branch (isComputer() == true)

        runCode("own permanent only", 1, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> {
            Assert.assertFalse("setup: the seat must be a bot path (non-human)", player.isHuman());
            Assert.assertTrue("setup: the seat must take the bot branch", player.isComputer());

            Permanent own = game.getBattlefield().getAllActivePermanents(StaticFilters.FILTER_PERMANENT_CREATURE, game)
                    .stream().filter(p -> p.isControlledBy(player.getId())).findFirst().orElse(null);
            Assert.assertNotNull("setup: the bot must control a creature", own);

            TargetPermanent target = new TargetPermanent(StaticFilters.FILTER_PERMANENT_CREATURE);
            Ability source = destroySource();
            source.setControllerId(player.getId());

            // canAutoChoose preconditions: min == max, exactly as many candidates as required,
            // auto-choose enabled (level > 0 now that the bot level is 1), no "search" in the text
            Assert.assertEquals("setup: exactly one candidate must exist",
                    1, target.possibleTargets(player.getId(), source, game).size());

            java.util.UUID autoChosen = target.tryToAutoChoose(player.getId(), source, game);
            Assert.assertNull("a bot must not auto-target its OWN permanent with a destroy effect",
                    autoChosen);
        });

        setStopAt(1, PhaseStep.END_TURN);
        execute();
    }

    @Test
    public void botStillAutoChoosesAnOpponentsPermanent() {
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears", 1);
        playerA.setAIPlayer(true); // take the genuine bot branch (isComputer() == true)

        runCode("opponent permanent only", 1, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> {
            Assert.assertFalse("setup: the seat must be a bot path (non-human)", player.isHuman());
            Assert.assertTrue("setup: the seat must take the bot branch", player.isComputer());

            Permanent hostile = game.getBattlefield().getAllActivePermanents(StaticFilters.FILTER_PERMANENT_CREATURE, game)
                    .stream().filter(p -> p.isControlledBy(playerB.getId())).findFirst().orElse(null);
            Assert.assertNotNull("setup: the opponent must control a creature", hostile);

            TargetPermanent target = new TargetPermanent(StaticFilters.FILTER_PERMANENT_CREATURE);
            Ability source = destroySource();
            source.setControllerId(player.getId());

            Assert.assertEquals("setup: exactly one candidate must exist",
                    1, target.possibleTargets(player.getId(), source, game).size());

            java.util.UUID autoChosen = target.tryToAutoChoose(player.getId(), source, game);
            Assert.assertEquals("an opponent's permanent must still be auto-chosen (no behaviour loss)",
                    hostile.getId(), autoChosen);
        });

        setStopAt(1, PhaseStep.END_TURN);
        execute();
    }
}
