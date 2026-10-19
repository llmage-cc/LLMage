package org.mage.test.LLM;

import mage.abilities.ActivatedAbility;
import mage.cards.Card;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.permanent.Permanent;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;
import org.mage.test.player.TestPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Fail-soft bad-mana-plan pins.
 *
 * Pre-fix behavior: {@code LLMPlayer.playMana} threw RuntimeException on the
 * FIRST mana-plan entry that could not pay its pip. That throw escaped the
 * engine callback into GameImpl.playPriority's error catch → auto-restore
 * replayed the identical decision → a deterministic model repeated the
 * identical bad plan → FATAL loop until "Too many errors, game will be end"
 * (an 18×FATAL spiral). A Ninjutsu plan at T6
 * killed the game.
 *
 * Post-fix behavior pinned here:
 *  - the FIRST bad-plan failure DECLINES the action (playMana returns false)
 *    — no throw: ManaCostsImpl.pay returns false → AbilityImpl.activate
 *    returns false → PlayerImpl.playAbility rolls back its bookmark → the
 *    cast aborts with the spell still in hand (TestPlayer observes the same
 *    decline via its own command-skip path).
 *  - the breaker escalation (3x identical) is code-inspected, not game-tested:
 *    driving 3 identical bad plans needs 3 full LLM re-prompts, which the
 *    scripted player cannot answer.
 */
public class LLMManaPlanFailSoftTest extends CardTestPlayerBaseLLM {

    private static final String TEST_SPELL = "Day of Judgment";

    /** Signals between the test body and the player subclass below. */
    static boolean armInjector = false;
    static int playManaFalseReturns = 0;
    static Throwable thrownFromCast = null;
    static int planInjects = 0;

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence range) {
        if (name.equals("PlayerA")) {
            TestLLMPlayer llmPlayer = new TestLLMPlayer(name, range, 0) {
                @Override
                public boolean activateAbility(ActivatedAbility ability, Game game) {
                    Card source = game.getCard(ability == null ? null : ability.getSourceId());
                    if (armInjector && source != null && source.getName().equals(TEST_SPELL)) {
                        // A plan that CANNOT pay: basic Mountains only, for
                        // {2}{W}{W}. No plan entry can ever produce {W}, so
                        // the plan fails at the first colored pip (generic
                        // pips consume Mountains first) — deterministically.
                        List<UUID> plan = game.getBattlefield()
                                .getAllActivePermanents(getId())
                                .stream()
                                .filter(p -> p.isLand(game) && p.getName().equals("Mountain"))
                                .map(Permanent::getId)
                                .collect(Collectors.toList());
                        Assert.assertEquals("test setup: expected 4 Mountains",
                                4, plan.size());
                        pendingManaPlan = new ArrayList<>(plan);
                        planInjects++;
                        armInjector = false;
                        try {
                            return super.activateAbility(ability, game);
                        } catch (Throwable t) {
                            thrownFromCast = t;
                            throw t;
                        } finally {
                            // mirror doCastSpell's finally — clear after the cast
                            pendingManaPlan = null;
                        }
                    }
                    return super.activateAbility(ability, game);
                }

                @Override
                public boolean playMana(mage.abilities.Ability ability,
                                        mage.abilities.costs.mana.ManaCost unpaid,
                                        String promptText, Game game) {
                    boolean ok = super.playMana(ability, unpaid, promptText, game);
                    if (!ok) {
                        playManaFalseReturns++;
                    }
                    return ok;
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
            // (Same pattern as LLMManaPlanOrderTest, whose cast also runs the
            // LLM seat's scripted activateAbility path with a live plan.)
            return testPlayer;
        }
        return super.createPlayer(name, range);
    }

    @After
    public void resetSignals() {
        armInjector = false;
        playManaFalseReturns = 0;
        thrownFromCast = null;
        planInjects = 0;
    }

    /**
     * The first bad-plan failure must DECLINE the cast (no throw, no game
     * error): playMana returns false, ManaCostsImpl.pay returns false,
     * AbilityImpl.activate returns false, PlayerImpl.playAbility rolls back
     * its bookmark, and the cast aborts with the spell still in hand. The
     * scripted player then skips the failed command (choices:
     * SKIP_FAILED_COMMAND at the exact activateAbility-false moment, like
     * PrepareTest's cancel-cast flow) and the game continues.
     */
    @Test
    public void testFirstBadPlanDeclinesWithoutThrow() {
        // Fixture: 4 Mountains + 2 Plains make the cast appear in getPlayable
        // (all untapped: {R}{R}{R}{R}{W}{W} covers {2}{W}{W}); the injected
        // plan uses ONLY the 4 Mountains, so after the two generic pips
        // consume Mountains 1-2, the {W} pip finds no plan entry that can pay
        // it → plan-entry failure (the Ninjutsu shape: colors absent
        // from the chosen plan though present on the battlefield).
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 4);
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 2);
        addCard(Zone.HAND, playerA, TEST_SPELL, 1);
        addCard(Zone.BATTLEFIELD, playerB, "Hill Giant", 1);

        setStrictChooseMode(false);
        armInjector = true;

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, TEST_SPELL);
        // The plan fails at the first {W} pip → playMana declines → the cast
        // aborts. choices.get(0) must be SKIP_FAILED_COMMAND exactly when
        // activateAbility returns false (TestPlayer's failed-command branch);
        // MANA_CANCEL is not usable here (it's only consumed by the manual
        // auto-payment-off branch in TestPlayer.playMana).
        setChoice(playerA, TestPlayer.SKIP_FAILED_COMMAND);
        setStopAt(1, PhaseStep.END_TURN);

        execute();

        Assert.assertNull("a bad mana plan must NOT throw (fail-soft decline): "
                + (thrownFromCast == null ? "ok" : thrownFromCast.toString()), thrownFromCast);
        Assert.assertTrue("playMana decline path never ran — bad plan may not have been injected",
                playManaFalseReturns >= 1);
        Assert.assertTrue("bad plan was never injected onto the cast",
                planInjects >= 1);
        // The cast was declined, not resolved: the spell is still in hand and
        // Hill Giant survives (Day of Judgment never resolved).
        long djInHand = playerA.getHand().getCards(currentGame).stream()
                .filter(c -> c.getName().equals(TEST_SPELL)).count();
        Assert.assertEquals("the unpayable cast must be declined — spell stays in hand",
                1, djInHand);
        Assert.assertTrue("Hill Giant must survive (Day of Judgment never resolved)",
                currentGame.getBattlefield().getAllActivePermanents().stream()
                        .anyMatch(p -> p.getName().equals("Hill Giant")));
    }
}