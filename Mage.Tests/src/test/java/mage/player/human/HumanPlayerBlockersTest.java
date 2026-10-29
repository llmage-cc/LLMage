package mage.player.human;

import mage.constants.Constants;
import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.filter.common.FilterBlockingCreature;
import mage.filter.common.FilterCreatureForCombatBlock;
import mage.filter.predicate.permanent.ControllerIdPredicate;
import mage.game.Game;
import mage.game.combat.CombatGroup;
import mage.game.events.PlayerQueryEvent;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.players.PlayerImpl;
import mage.players.net.UserData;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The blocker list published by HumanPlayer.selectBlockers must only contain
 * creatures that can legally block at least one attacking group, otherwise the
 * client offers a ground creature against a flyer and the follow-up
 * "select attacker to block" target has no legal options. When no creature can
 * block anything the prompt must be skipped completely instead of being
 * published with an empty list.
 */
public class HumanPlayerBlockersTest extends CardTestPlayerBase {

    private Permanent getPermanent(String name, Game game) {
        return game.getBattlefield().getAllActivePermanents()
                .stream()
                .filter(p -> p.getName().equals(name))
                .findFirst()
                .get();
    }

    private FilterCreatureForCombatBlock blockFilter(UUID defendingPlayerId) {
        FilterCreatureForCombatBlock filter = new FilterCreatureForCombatBlock();
        filter.add(new ControllerIdPredicate(defendingPlayerId));
        return filter;
    }

    private List<UUID> narrowBlockers(FilterCreatureForCombatBlock filter, UUID defendingPlayerId, Game game) {
        return HumanPlayer.getPossibleBlockers(filter, new FilterBlockingCreature(),
                game.getCombat().getGroups(), defendingPlayerId, null, game);
    }

    /**
     * The player handed to a runCode payload is the test player's AI delegate (a
     * PlayerImpl with the same id and the same game seat), which is what the human
     * player stub is built from.
     */
    private StubClientHumanPlayer newHumanPlayerStub(Player player) {
        return new StubClientHumanPlayer((PlayerImpl) player);
    }

    /**
     * Records every select prompt the game publishes (the same event a real
     * client receives).
     */
    private List<Map<String, Serializable>> captureSelectPrompts(Game game) {
        List<Map<String, Serializable>> prompts = new ArrayList<>();
        game.addPlayerQueryEventListener(event -> {
            if (event.getQueryType() == PlayerQueryEvent.QueryType.SELECT) {
                prompts.add(event.getOptions());
            }
        });
        return prompts;
    }

    @SuppressWarnings("unchecked")
    private List<UUID> publishedBlockers(Map<String, Serializable> prompt) {
        Serializable value = prompt.get(Constants.Option.POSSIBLE_BLOCKERS);
        Assert.assertNotNull("select prompt must carry " + Constants.Option.POSSIBLE_BLOCKERS, value);
        return (List<UUID>) value;
    }

    @Test
    public void testGroundCreatureCannotBlockFlyer() {
        addCard(Zone.BATTLEFIELD, playerA, "Serra Angel");     // 4/4 flying
        addCard(Zone.BATTLEFIELD, playerB, "Silvercoat Lion"); // 2/2 without flying or reach
        addCard(Zone.BATTLEFIELD, playerB, "Giant Spider");    // 2/4 reach

        attack(1, playerA, "Serra Angel");
        runCode("check published blockers", 1, PhaseStep.DECLARE_BLOCKERS, playerB, (info, player, game) -> {
            List<CombatGroup> groups = game.getCombat().getGroups();
            Assert.assertEquals("one attacking group expected", 1, groups.size());

            Permanent lion = getPermanent("Silvercoat Lion", game);
            Permanent spider = getPermanent("Giant Spider", game);

            FilterCreatureForCombatBlock preFixFilter = blockFilter(playerB.getId());
            Assert.assertTrue("the ground creature passed the pre-fix availability filter, so it used to be published",
                    preFixFilter.match(lion, playerB.getId(), null, game));

            Assert.assertFalse("a creature without flying or reach cannot block a flyer and must not be published",
                    HumanPlayer.canBlockAnyGroup(lion, groups, game));
            Assert.assertTrue("a creature with reach can block a flyer and must stay published",
                    HumanPlayer.canBlockAnyGroup(spider, groups, game));

            List<UUID> narrowed = narrowBlockers(preFixFilter, playerB.getId(), game);
            Assert.assertEquals("only the reach creature is a legal blocker", 1, narrowed.size());
            Assert.assertEquals(spider.getId(), narrowed.get(0));
        });

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();
    }

    /**
     * Every potential blocker is illegal for the only attacking group (ground
     * creature against a lone flyer): the pre-fix code counted the lion as an
     * available blocker, so it did not skip the step and the client received a
     * declare-blockers prompt with an empty possible-blockers list. The prompt
     * must not be published and the player must not be waited on at all.
     */
    @Test
    public void testNoPromptWhenEveryBlockerIsIllegal() {
        addCard(Zone.BATTLEFIELD, playerA, "Serra Angel");     // 4/4 flying
        addCard(Zone.BATTLEFIELD, playerB, "Silvercoat Lion"); // 2/2 without flying or reach

        attack(1, playerA, "Serra Angel");
        runCode("no legal blocker", 1, PhaseStep.DECLARE_BLOCKERS, playerB, (info, player, game) -> {
            List<CombatGroup> groups = game.getCombat().getGroups();
            Assert.assertEquals("one attacking group expected", 1, groups.size());

            Permanent lion = getPermanent("Silvercoat Lion", game);

            // non-vacuity: the base availability filter still counts the lion, so the
            // pre-fix skip decision saw a "possible blocker" and opened the prompt
            FilterCreatureForCombatBlock baseFilter = blockFilter(playerB.getId());
            Assert.assertEquals("premise: the base filter counts the ground creature as available",
                    1, game.getBattlefield().count(baseFilter, playerB.getId(), null, game));
            Assert.assertTrue("premise: the ground creature passes the base filter",
                    baseFilter.match(lion, playerB.getId(), null, game));

            Assert.assertTrue("nobody can legally block the flyer", narrowBlockers(baseFilter, playerB.getId(), game).isEmpty());

            List<Map<String, Serializable>> prompts = captureSelectPrompts(game);
            StubClientHumanPlayer human = newHumanPlayerStub(player);
            human.selectBlockers(null, game, playerB.getId()); // must return on its own

            Assert.assertEquals("no declare-blockers prompt may be published", 0, prompts.size());
            Assert.assertFalse("the player must not be waited on for a blocker answer", human.waitedForClientAnswer);
        });

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();
    }

    /**
     * The legitimate path stays intact: with a creature that can really block, the
     * prompt is published once and carries exactly the legal blockers.
     */
    @Test
    public void testPromptPublishesOnlyLegalBlockers() {
        addCard(Zone.BATTLEFIELD, playerA, "Serra Angel");     // 4/4 flying
        addCard(Zone.BATTLEFIELD, playerB, "Silvercoat Lion"); // 2/2 without flying or reach
        addCard(Zone.BATTLEFIELD, playerB, "Giant Spider");    // 2/4 reach

        attack(1, playerA, "Serra Angel");
        runCode("published blockers", 1, PhaseStep.DECLARE_BLOCKERS, playerB, (info, player, game) -> {
            Permanent lion = getPermanent("Silvercoat Lion", game);
            Permanent spider = getPermanent("Giant Spider", game);
            Assert.assertEquals("premise: no block is declared yet", 0, spider.getBlocking());

            List<Map<String, Serializable>> prompts = captureSelectPrompts(game);
            StubClientHumanPlayer human = newHumanPlayerStub(player);
            human.selectBlockers(null, game, playerB.getId());

            Assert.assertTrue("the player must be asked for blockers", human.waitedForClientAnswer);
            Assert.assertEquals("exactly one declare-blockers prompt", 1, prompts.size());

            List<UUID> published = publishedBlockers(prompts.get(0));
            Assert.assertEquals("only the reach creature is a legal blocker, got " + published, 1, published.size());
            Assert.assertEquals(spider.getId(), published.get(0));
            Assert.assertFalse("the ground creature must not be offered against a flyer", published.contains(lion.getId()));
        });

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();
    }

    /**
     * selectBlockers talks to a real client: it opens a prompt and blocks until the
     * client answers. The stub keeps the production code path intact and only
     * replaces the client boundary - prepareForResponse is a no-op (priority/turn
     * bookkeeping does not apply outside the real turn loop), waitForResponse
     * records that the client was asked instead of blocking, and canRespond stops
     * the loop after that first answer cycle so the test never waits on a client.
     */
    private static final class StubClientHumanPlayer extends HumanPlayer {

        private boolean waitedForClientAnswer = false;
        private boolean clientAsked = false;

        private StubClientHumanPlayer(PlayerImpl sourcePlayer) {
            super(sourcePlayer, new PlayerResponse());
            setUserData(UserData.getDefaultUserDataView());
        }

        @Override
        protected void prepareForResponse(Game game) {
            // no client state to prepare for the test
        }

        @Override
        protected void waitForResponse(Game game) {
            waitedForClientAnswer = true;
            clientAsked = true;
        }

        @Override
        public boolean canRespond() {
            // one answer cycle is enough: the prompt publishing is what is under test
            return !clientAsked;
        }
    }
}
