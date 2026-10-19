package org.mage.test.game;

import java.util.List;
import java.util.UUID;
import mage.abilities.Ability;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.effects.common.InfoEffect;
import mage.cards.Card;
import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.permanent.Permanent;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

/**
 * Honest exile log message for a stale battlefield target

 * <p>
 * {@code PlayerImpl.moveCardToExileWithInfo} takes a caller-supplied {@code fromZone}
 * used only by its game-log line. When that claim is BATTLEFIELD but the live state has
 * no permanent with the card's id - the stale-target shape the issue describes (a target
 * carried across an auto-restore/rollback), or a battlefield entry removed without a zone
 * update - the old "moves X from battlefield to the exile zone" line reported a transition
 * that did not happen. The fix reports the attempt instead.
 * <p>
 * The test drives the real method directly through the test player's own delegation to
 * {@link mage.players.PlayerImpl} (TestPlayer delegates every move*WithInfo call to its
 * wrapped ComputerPlayer), with the game log turned on, and asserts BOTH legs:
 * <ul>
 *   <li>the dishonest leg - a card in the graveyard claimed as BATTLEFIELD emits the
 *       "attempted to move ... (already gone; actually in graveyard)" line, never the
 *       plain "moves ... from battlefield to the exile zone" one;</li>
 *   <li>the positive control - a card genuinely on the battlefield still emits the
 *       unchanged normal line.</li>
 * </ul>
 */
public class ExileLogMessageHonestyTest extends CardTestPlayerBase {

    /**
     * Normalize a raw game-log line the way the clients do: strip the HTML name
     * markup and XMage's 3-hex object shard markers ("... Silvercoat Lion [3d2] from
     * battlefield ..."), which sit BETWEEN the card name and the " from " clause and
     * would otherwise defeat a substring match. Same normalization the LLM plugin's
     * {@code LLMGameSerializer.cleanHtml} applies.
     */
    private static String plain(String line) {
        return line.replaceAll("<[^>]+>", "")
                .replaceAll(" \\[[0-9a-fA-F]{3}\\]", "")
                .replaceAll("  +", " ");
    }

    private static boolean logContains(List<String> log, String needle) {
        return log.stream().anyMatch(line -> plain(line).contains(needle));
    }

    private static int countLogLines(List<String> log, String needle) {
        return (int) log.stream().filter(line -> plain(line).contains(needle)).count();
    }

    @Test
    public void staleBattlefieldTargetGetsHonestMessageAndNormalMoveKeepsTheOldOne() {
        // gameLog accumulation is opt-in on GameState (LLM path enables it; the capture
        // collector is always registered, see LLMGameLogCollector).
        currentGame.getState().setGameLogEnabled(true);

        // A body that will be on the battlefield (normal-move control) and a second one
        // that will be in the graveyard (the stale-claim case).
        addCard(Zone.BATTLEFIELD, playerA, "Silvercoat Lion", 1);
        addCard(Zone.GRAVEYARD, playerA, "Grizzly Bears", 1);

        runCode("exile log honesty", 1, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> {
            Ability source = new SimpleStaticAbility(Zone.BATTLEFIELD, new InfoEffect("test"));
            source.setSourceId(player.getId());

            // (1) Dishonest leg: the card is in the graveyard, the caller claims BATTLEFIELD.
            Card inGraveyard = player.getGraveyard().getCards(game).stream().findFirst().orElse(null);
            Assert.assertNotNull("setup: a card must be in the graveyard", inGraveyard);
            Assert.assertNull("setup: the graveyard card must not be a live permanent",
                    game.getPermanent(inGraveyard.getId()));
            Assert.assertEquals("setup: the lived zone must be the graveyard",
                    Zone.GRAVEYARD, game.getState().getZone(inGraveyard.getId()));

            boolean moved = player.moveCardToExileWithInfo(
                    inGraveyard, null, "", source, game, Zone.BATTLEFIELD, true);
            Assert.assertTrue("the stale-claim exile must still move the card", moved);
            Assert.assertEquals("the move itself must land in exile regardless of the claim",
                    Zone.EXILED, game.getState().getZone(inGraveyard.getId()));

            List<String> log = game.getState().getGameLog();
            Assert.assertTrue("dishonest leg must use the honest line",
                    logContains(log, "attempted to move"));
            Assert.assertTrue("dishonest leg must say it was already gone",
                    logContains(log, "already gone"));
            Assert.assertTrue("dishonest leg must name the real zone",
                    logContains(log, "actually in graveyard"));
            Assert.assertEquals("dishonest leg must never claim a battlefield move",
                    0, countLogLines(log, "moves Grizzly Bears from battlefield to the exile zone"));

            // (2) Positive control: a genuine battlefield permanent still logs the normal line.
            Permanent onBattlefield = game.getBattlefield().getAllActivePermanents().stream()
                    .filter(p -> p.isOwnedBy(playerA.getId()))
                    .findFirst().orElse(null);
            Assert.assertNotNull("setup: a permanent must be on the battlefield", onBattlefield);

            boolean moved2 = player.moveCardToExileWithInfo(
                    onBattlefield, null, "", source, game, Zone.BATTLEFIELD, true);
            Assert.assertTrue("the normal exile must still move the card", moved2);
            Assert.assertTrue("positive control must keep the unchanged normal line",
                    logContains(log, "moves Silvercoat Lion from battlefield to the exile zone"));
            Assert.assertEquals("a legit move must not be logged as an attempt",
                    0, countLogLines(log, "attempted to move Silvercoat Lion"));
        });

        setStopAt(1, PhaseStep.END_TURN);
        execute();
    }
}
