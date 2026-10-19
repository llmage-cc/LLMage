package org.mage.test.game;

import mage.abilities.Ability;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.effects.common.InfoEffect;
import mage.cards.Card;
import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.filter.FilterCard;
import mage.filter.StaticFilters;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.target.Target;
import mage.target.TargetPermanent;
import mage.target.common.TargetCardInGraveyardBattlefieldOrStack;
import mage.target.common.TargetCardInYourGraveyardOrExile;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Core restore-state candidate re-validation

 * <p>
 * The documented shape: after GameImpl.playPriority restores the priority-start snapshot, an id
 * captured against the pre-restore world can still be offered by a Target whose declared area no
 * longer matches the live state ("The Card still resolves via getCard() - it's present in the
 * library/hand zone index - only its ZONE is wrong"; at T12, LED offered as a
 * battlefield target while the live state has it in the graveyard). The core fix re-validates
 * every candidate at the one funnel all possibleTargets implementations pass through
 * ({@code Target.keepValidPossibleTargets}), so no caller - human dialogs, MAD/MCTS bots, AI sims -
 * can offer it, not just the LLM plugin that has its own guard.
 * <p>
 * Legs:
 * <ul>
 *   <li>{@link #staleCandidateWhoseLiveZoneContradictsTheAreaIsDropped} - a candidate id the live
 *       state does not have on the battlefield is dropped by a Zone.BATTLEFIELD target (the
 *       documented stale-card shape, injected through the real funnel).</li>
 *   <li>{@link #liveCandidateIsKept} - positive control: a genuinely live permanent survives the
 *       funnel (no over-filtering).</li>
 *   <li>{@link #permanentEnteringIsKept} - a permanent mid-entry (the zone map is written when
 *       placement finishes) is kept.</li>
 *   <li>{@link #permanentWithContradictingZoneMapEntryIsDropped} - the other divergence shape:
 *       the zone map contradicts the battlefield entry, so the id is not trustworthy.</li>
 *   <li>{@link #multiZoneTargetKeepsItsOffZoneCandidates} / {@link #graveyardTargetKeepsItsCards}
 *       - non-regression for classes that declare one zone while legitimately offering ids from
 *       other zones (a blanket "declared zone must equal live zone" rule would drop these).</li>
 * </ul>
 */
public class TargetLiveStateRevalidationTest extends CardTestPlayerBase {

    /**
     * Minimal source ability for the target checks: the source id must resolve, otherwise
     * keepValidPossibleTargets skips its protection branch, which is not what these tests cover.
     */
    private static Ability sourceAbility(Player controller) {
        Ability source = new SimpleStaticAbility(Zone.BATTLEFIELD, new InfoEffect("test"));
        source.setSourceId(controller.getId());
        return source;
    }

    private static Permanent creatureOf(Player owner, Game game) {
        return game.getBattlefield().getAllActivePermanents(StaticFilters.FILTER_PERMANENT_CREATURE, game)
                .stream().filter(p -> p.isOwnedBy(owner.getId())).findFirst().orElse(null);
    }

    @Test
    public void staleCandidateWhoseLiveZoneContradictsTheAreaIsDropped() {
        addCard(Zone.GRAVEYARD, playerA, "Grizzly Bears", 1);

        runCode("stale candidate", 1, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> {
            Card inGraveyard = player.getGraveyard().getCards(game).stream().findFirst().orElse(null);
            Assert.assertNotNull("setup: a card must be in the graveyard", inGraveyard);
            Assert.assertEquals("setup: the live zone must be the graveyard",
                    Zone.GRAVEYARD, game.getState().getZone(inGraveyard.getId()));
            Assert.assertNull("setup: the graveyard card must not be a live permanent",
                    game.getPermanent(inGraveyard.getId()));

            // The stale candidate set a caller holds across the restore boundary: an id that the
            // pre-restore world had on the battlefield but the live state does not (LED shape).
            Set<UUID> staleCandidates = new HashSet<>();
            staleCandidates.add(inGraveyard.getId());

            TargetPermanent target = new TargetPermanent(StaticFilters.FILTER_PERMANENT_CREATURE);
            Set<UUID> validated = target.keepValidPossibleTargets(
                    staleCandidates, player.getId(), sourceAbility(player), game);

            Assert.assertFalse("a candidate the live state does not have on the battlefield must be dropped",
                    validated.contains(inGraveyard.getId()));
            Assert.assertTrue("the stale candidate set must not survive as a whole", validated.isEmpty());
        });

        setStopAt(1, PhaseStep.END_TURN);
        execute();
    }

    @Test
    public void liveCandidateIsKept() {
        addCard(Zone.BATTLEFIELD, playerA, "Silvercoat Lion", 1);

        runCode("live candidate", 1, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> {
            Permanent lion = creatureOf(player, game);
            Assert.assertNotNull("setup: a permanent must be on the battlefield", lion);
            Assert.assertEquals("setup: the live zone map must agree it is on the battlefield",
                    Zone.BATTLEFIELD, game.getState().getZone(lion.getId()));

            Set<UUID> candidates = new HashSet<>();
            candidates.add(lion.getId());

            TargetPermanent target = new TargetPermanent(StaticFilters.FILTER_PERMANENT_CREATURE);
            Set<UUID> validated = target.keepValidPossibleTargets(
                    candidates, player.getId(), sourceAbility(player), game);

            Assert.assertTrue("a genuine battlefield permanent must survive the funnel",
                    validated.contains(lion.getId()));

            // ... and it is also what a real possibleTargets call returns.
            Assert.assertTrue("possibleTargets must still offer the live permanent",
                    target.possibleTargets(player.getId(), sourceAbility(player), game).contains(lion.getId()));
        });

        setStopAt(1, PhaseStep.END_TURN);
        execute();
    }

    @Test
    public void permanentEnteringIsKept() {
        addCard(Zone.BATTLEFIELD, playerA, "Silvercoat Lion", 1);

        runCode("permanent entering", 1, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> {
            Permanent lion = creatureOf(player, game);
            Assert.assertNotNull("setup: a permanent must be on the battlefield", lion);

            // Simulate the mid-entry window: the battlefield map has no permanent yet, but the
            // permanent is entering (the zone map is written when placement finishes). Without
            // the getPermanentEntering() leg this legitimately targetable permanent - one the
            // engine itself keeps in getPermanentsEntering - would be dropped.
            game.getBattlefield().removePermanent(lion.getId());
            game.getPermanentsEntering().put(lion.getId(), lion);
            try {
                Assert.assertNull("setup: mid-entry means no battlefield entry yet",
                        game.getPermanent(lion.getId()));

                Set<UUID> candidates = new HashSet<>();
                candidates.add(lion.getId());

                TargetPermanent target = new TargetPermanent(StaticFilters.FILTER_PERMANENT_CREATURE);
                Set<UUID> validated = target.keepValidPossibleTargets(
                        candidates, player.getId(), sourceAbility(player), game);

                Assert.assertTrue("a permanent mid-entry must survive the funnel",
                        validated.contains(lion.getId()));
            } finally {
                game.getPermanentsEntering().remove(lion.getId());
                game.getBattlefield().addPermanent(lion);
            }
        });

        setStopAt(1, PhaseStep.END_TURN);
        execute();
    }

    @Test
    public void permanentWithContradictingZoneMapEntryIsDropped() {
        addCard(Zone.BATTLEFIELD, playerA, "Silvercoat Lion", 1);

        runCode("contradicting zone entry", 1, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> {
            Permanent lion = creatureOf(player, game);
            Assert.assertNotNull("setup: a permanent must be on the battlefield", lion);

            // The other divergence shape: the battlefield map still holds the permanent while
            // the zone map says it is somewhere else (the two indexes contradict each other, e.g.
            // a battlefield entry removed without a zone-map update - see the OUTSIDE branch of
            // PlayerImpl.moveCards). The id is not trustworthy, so it must not be offered.
            game.getState().setZone(lion.getId(), Zone.OUTSIDE);
            try {
                Set<UUID> candidates = new HashSet<>();
                candidates.add(lion.getId());

                TargetPermanent target = new TargetPermanent(StaticFilters.FILTER_PERMANENT_CREATURE);
                Set<UUID> validated = target.keepValidPossibleTargets(
                        candidates, player.getId(), sourceAbility(player), game);

                Assert.assertFalse("a permanent whose zone map contradicts the battlefield must be dropped",
                        validated.contains(lion.getId()));
            } finally {
                game.getState().setZone(lion.getId(), Zone.BATTLEFIELD);
            }
        });

        setStopAt(1, PhaseStep.END_TURN);
        execute();
    }

    @Test
    public void multiZoneTargetKeepsItsOffZoneCandidates() {
        addCard(Zone.GRAVEYARD, playerA, "Grizzly Bears", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Silvercoat Lion", 1);

        runCode("multi-zone target", 1, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> {
            Permanent lion = creatureOf(player, game);
            Assert.assertNotNull("setup: a permanent must be on the battlefield", lion);
            Assert.assertEquals("setup: the permanent's live zone must be the battlefield",
                    Zone.BATTLEFIELD, game.getState().getZone(lion.getId()));

            // This class declares Zone.GRAVEYARD (for its graveyard half) while legitimately
            // offering battlefield permanents and stack spells as well. A blanket
            // "declared zone must equal live zone" rule would drop the permanent here; the
            // narrow guard must not, or every multi-zone target in the game breaks.
            TargetCardInGraveyardBattlefieldOrStack target = new TargetCardInGraveyardBattlefieldOrStack(
                    0, 2, new FilterCard("card from a graveyard"), StaticFilters.FILTER_PERMANENT_CREATURE);
            Set<UUID> candidates = target.possibleTargets(player.getId(), sourceAbility(player), game);

            Assert.assertTrue("the battlefield half of a multi-zone target must keep working",
                    candidates.contains(lion.getId()));
        });

        setStopAt(1, PhaseStep.END_TURN);
        execute();
    }

    @Test
    public void graveyardTargetKeepsItsCards() {
        addCard(Zone.GRAVEYARD, playerA, "Grizzly Bears", 1);

        runCode("graveyard target", 1, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> {
            Card inGraveyard = player.getGraveyard().getCards(game).stream().findFirst().orElse(null);
            Assert.assertNotNull("setup: a card must be in the graveyard", inGraveyard);

            Target cardTarget = new TargetCardInYourGraveyardOrExile(
                    new FilterCard("card from your graveyard"), new FilterCard("card from exile"));
            Set<UUID> candidates = cardTarget.possibleTargets(player.getId(), sourceAbility(player), game);

            Assert.assertTrue("a graveyard card must still be offered by a graveyard-declared target",
                    candidates.contains(inGraveyard.getId()));
        });

        setStopAt(1, PhaseStep.END_TURN);
        execute();
    }
}
