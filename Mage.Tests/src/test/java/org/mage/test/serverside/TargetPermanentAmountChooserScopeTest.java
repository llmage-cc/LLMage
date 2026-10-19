package org.mage.test.serverside;

import mage.abilities.Ability;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.effects.common.SacrificeEffect;
import mage.constants.PhaseStep;
import mage.constants.Outcome;
import mage.constants.Zone;
import mage.filter.FilterPermanent;
import mage.filter.StaticFilters;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.target.common.TargetPermanentAmount;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

import java.util.UUID;

/**
 * Core regression: {@code TargetPermanentAmount.canTarget(playerId, objectId, source, game)}
 * must evaluate as the CHOOSING player (its {@code playerId} argument), matching its own
 * parent {@code TargetPermanent.canTarget(playerId...)} — not silently fall back to
 * {@code source.getControllerId()} (the ability controller).
 *
 * <p>Context:
 * found by source reading while fixing the P1 LLM target-controller defect: same
 * controller-vs-chooser family, per-target validation leg. In the ordinary case
 * (controller == chooser) both readings are identical, which is why this never surfaced
 * in play. It matters for defender-chooses triggers (annihilator-style sacrifice,
 * "each player chooses", Show-and-Tell-style off-controller picks).</p>
 *
 * <p><b>Discriminator.</b> A {@code FILTER_CONTROLLED_ANOTHER_PERMANENT}-style filter is
 * player-relative: read as player X it matches permanents X controls other than the
 * ability's source object. So with the ability hosted on the ATTACKER's creature and a
 * second creature on each side, the two readings disagree on every object on the board.</p>
 *
 * <p><b>RED-at-base proof.</b> Before the fix, the 4-arg overload forwards to the 3-arg
 * one, which scopes to {@code source.getControllerId()}: the chooser-scoped assertions
 * below fail. With the fix (both {@code canBeTargetedBy} and {@code filter.match} keyed
 * on the passed {@code playerId}), they pass. The 3-arg overload keeps its controller-
 * scoped semantics (it has no playerId to honor) — asserted as a control leg so the RED
 * in the main leg is attributable to the 4-arg overload alone.</p>
 */
public class TargetPermanentAmountChooserScopeTest extends CardTestPlayerBase {

    /** Host creature for the attacker's ability (controller = attacker A). */
    private static final String HOST_A = "Grizzly Bears";
    /** The attacker's other creature — "another permanent" only for the ATTACKER reading. */
    private static final String OTHER_A = "Hill Giant";
    /** The defender/chooser's creature — "another permanent" for the CHOOSER reading only. */
    private static final String CHOOSER_B = "Silvercoat Lion";

    private Permanent permanent(Game game, String name, UUID controllerId) {
        return game.getBattlefield().getAllActivePermanents(controllerId).stream()
                .filter(p -> name.equals(p.getName()))
                .findFirst().orElse(null);
    }

    /**
     * An attacker-controlled trigger hosted on one of the attacker's creatures — the
     * annihilator/SacrificeEffect shape: ability controller = ATTACKER, chooser = DEFENDER.
     */
    private Ability attackerTrigger(Game game, UUID attackerId) {
        Permanent host = permanent(game, HOST_A, attackerId);
        Assert.assertNotNull("setup: attacker must control the host creature", host);
        Ability trigger = new SimpleStaticAbility(Zone.BATTLEFIELD,
                new SacrificeEffect(StaticFilters.FILTER_PERMANENT, 1, ""));
        trigger.setSourceId(host.getId());
        trigger.setControllerId(attackerId);
        return trigger;
    }

    @Test
    public void fourArgCanTargetHonorsTheChoosingPlayer() {
        addCard(Zone.BATTLEFIELD, playerA, HOST_A);
        addCard(Zone.BATTLEFIELD, playerA, OTHER_A);
        addCard(Zone.BATTLEFIELD, playerB, CHOOSER_B);

        runCode("read canTarget as the defender for an attacker-controlled trigger", 1,
                PhaseStep.PRECOMBAT_MAIN, playerA, (info, p, game) -> {
            UUID chooserId = playerB.getId();
            UUID attackerId = playerA.getId();
            Ability trigger = attackerTrigger(game, attackerId);

            // Player-relative filter: read as X, matches permanents X controls other
            // than the ability's source object.
            FilterPermanent other = StaticFilters.FILTER_CONTROLLED_ANOTHER_PERMANENT.copy();
            TargetPermanentAmount target = new TargetPermanentAmount(1, 1, 1, other);

            Permanent chooserCreature = permanent(game, CHOOSER_B, chooserId);
            Assert.assertNotNull(chooserCreature);
            Permanent attackerCreature = permanent(game, OTHER_A, attackerId);
            Assert.assertNotNull(attackerCreature);

            // The core assertion: the 4-arg overload must answer for the CHOOSER.
            Assert.assertTrue("the chooser's own creature must be targetable for the chooser",
                    target.canTarget(chooserId, chooserCreature.getId(), trigger, game));
            Assert.assertFalse("the attacker's creature must NOT be targetable for the chooser",
                    target.canTarget(chooserId, attackerCreature.getId(), trigger, game));
        });

        setStopAt(1, PhaseStep.END_TURN);
        execute();
    }

    /**
     * Control: the 3-arg overload has no playerId argument — it keeps its (inherited)
     * controller-scoped semantics. This leg must stay GREEN at base so the RED in the
     * main leg is attributable to the 4-arg overload alone.
     */
    @Test
    public void threeArgCanTargetControlLegStaysControllerScoped() {
        addCard(Zone.BATTLEFIELD, playerA, HOST_A);
        addCard(Zone.BATTLEFIELD, playerA, OTHER_A);
        addCard(Zone.BATTLEFIELD, playerB, CHOOSER_B);

        runCode("read canTarget 3-arg as the controller-scoped control", 1,
                PhaseStep.PRECOMBAT_MAIN, playerA, (info, p, game) -> {
            UUID attackerId = playerA.getId();
            Ability trigger = attackerTrigger(game, attackerId);

            FilterPermanent other = StaticFilters.FILTER_CONTROLLED_ANOTHER_PERMANENT.copy();
            TargetPermanentAmount target = new TargetPermanentAmount(1, 1, 1, other);

            Permanent attackerCreature = permanent(game, OTHER_A, attackerId);
            Permanent chooserCreature = permanent(game, CHOOSER_B, playerB.getId());

            // Controller reading (source.getControllerId() = attacker): the attacker's
            // other creature is "another permanent" for the attacker; the defender's is
            // not controlled by the attacker at all.
            Assert.assertTrue("control: attacker's other creature is targetable in the controller reading",
                    target.canTarget(attackerCreature.getId(), trigger, game));
            Assert.assertFalse("control: chooser's creature is not targetable in the controller reading",
                    target.canTarget(chooserCreature.getId(), trigger, game));
        });

        setStopAt(1, PhaseStep.END_TURN);
        execute();
    }
}
