package org.mage.test.LLM;

import mage.abilities.ActivatedAbility;
import mage.cards.Card;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.permanent.Permanent;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;
import org.mage.test.player.TestPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Regression test for the mana-plan order-sensitivity bug in
 * {@code LLMPlayer.playMana} (observed in aggro/control games).
 *
 * The mana plan is documented to the model as a SET of lands to tap
 * ("action:3 mana:L1,L3" — no ordering contract), but playMana consumed it
 * strictly pip-by-pip: {@code pendingManaPlan.remove(0)} for each unpaid pip
 * in cost-string order. A plan whose land order differs from pip order — e.g.
 * Island,Glacial Fortress,Island,Glacial Fortress for {2}{W}{W} — pays the
 * cost as a set but threw at pip 3 ({W} asked of an Island), causing
 * game-error auto-restores (4x in one live game).
 *
 * Fix: playMana scans remaining plan entries for the first one that can pay
 * THIS pip (testPay-based, same as the existing ability selection). This test
 * casts Day of Judgment ({2}{W}{W}) with a plan in the failing order and
 * asserts the cost fully pays and all four lands end up tapped.
 */
public class LLMManaPlanOrderTest extends CardTestPlayerBaseLLM {

    private static final String TEST_SPELL = "Day of Judgment";

    /** Signals between the test body and the player subclass below. */
    static boolean armInjector = false;
    static boolean injected = false;
    static int remainingAfter = -1;
    static String failDuringCast = null;

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence range) {
        if (name.equals("PlayerA")) {
            TestLLMPlayer llmPlayer = new TestLLMPlayer(name, range, 0) {
                @Override
                public boolean activateAbility(ActivatedAbility ability, Game game) {
                    Card source = game.getCard(ability == null ? null : ability.getSourceId());
                    if (armInjector && source != null && source.getName().equals(TEST_SPELL)) {
                        // Build the plan in the deliberately WRONG order: Islands first
                        // (matches the glm plan observed live). Pips for {2}{W}{W} ask
                        // generic, generic, {W}, {W} — blind remove(0) would offer the
                        // 3rd pip to lands.get(2) = Island and throw.
                        List<UUID> plan = new ArrayList<>();
                        List<Permanent> lands = game.getBattlefield()
                                .getAllActivePermanents(getId())
                                .stream()
                                .filter(p -> p.isLand(game))
                                .sorted((a, b) -> {
                                    boolean aI = a.getName().equals("Island");
                                    boolean bI = b.getName().equals("Island");
                                    if (aI != bI) return aI ? -1 : 1;
                                    return a.getId().compareTo(b.getId());
                                })
                                .collect(Collectors.toList());
                        Assert.assertEquals("test setup: expected 4 lands on PlayerA's battlefield",
                                4, lands.size());
                        lands.forEach(l -> plan.add(l.getId()));
                        pendingManaPlan = new ArrayList<>(plan);
                        injected = true;
                        armInjector = false;
                        try {
                            return super.activateAbility(ability, game);
                        } catch (Throwable t) {
                            failDuringCast = t.getMessage();
                            throw t;
                        } finally {
                            remainingAfter = pendingManaPlan == null ? -1 : pendingManaPlan.size();
                            pendingManaPlan = null;
                        }
                    }
                    return super.activateAbility(ability, game);
                }
            };
            llmPlayer.getLLMConfig()
                    .apiEndpoint("http://localhost:11434/v1/chat/completions")
                    .model("deepseek-v4-flash:cloud")
                    .temperature(0.0)
                    .maxTokens(65536)
                    .timeoutSeconds(120)
                    .deckAssessmentEnabled(false)
                    .verboseLogging(true);
            llmPlayer.reconnect();
            TestPlayer testPlayer = new TestPlayer(llmPlayer);
            // NOT setAIPlayer(true): we want scripted (TestPlayer) priority control
            // for PlayerA so the cast is driven deterministically — the LLM would
            // otherwise take priority itself and make live API calls.
            return testPlayer;
        }
        return super.createPlayer(name, range);
    }

    @Test
    public void testManaPlanOutOfPipOrderStillPays() {
        // PlayerA (LLM seat, scripted): 2x Island + 2x Glacial Fortress (enters
        // untapped, Island present), Day of Judgment ({2}{W}{W}) in hand.
        addCard(Zone.BATTLEFIELD, playerA, "Island", 2);
        addCard(Zone.BATTLEFIELD, playerA, "Glacial Fortress", 2);
        addCard(Zone.HAND, playerA, TEST_SPELL, 1);
        addCard(Zone.BATTLEFIELD, playerB, "Hill Giant", 1);

        setStrictChooseMode(false);
        armInjector = true;
        injected = false;
        remainingAfter = -1;
        failDuringCast = null;

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, TEST_SPELL);
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN, playerA);
        setStopAt(1, PhaseStep.END_TURN);

        execute();

        Assert.assertNull("cast threw with the out-of-order plan (order-sensitivity regression): "
                + failDuringCast, failDuringCast);
        Assert.assertTrue("mana plan was never injected — test did not exercise playMana", injected);
        Assert.assertEquals("mana plan should have fully consumed (paid every pip), entries left: "
                + remainingAfter, 0, remainingAfter);

        List<Permanent> untapped = currentGame.getBattlefield()
                .getAllActivePermanents(playerA.getId())
                .stream()
                .filter(p -> p.isLand(currentGame) && !p.isTapped())
                .collect(Collectors.toList());
        Assert.assertEquals("all 4 plan lands should be tapped after paying {2}{W}{W} (untapped: "
                        + untapped.stream().map(Permanent::getName).collect(Collectors.joining(",")) + ")",
                0, untapped.size());
    }
}