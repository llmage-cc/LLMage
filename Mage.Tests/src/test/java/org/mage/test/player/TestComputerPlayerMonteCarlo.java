package org.mage.test.player;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.cards.Cards;
import mage.choices.Choice;
import mage.constants.Outcome;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.player.ai.ComputerPlayerMCTS;
import mage.player.ai.MCTSNode;
import mage.player.ai.MCTSPlayer.NextAction;
import mage.target.Target;
import mage.target.TargetCard;

/**
 * AI: helper class for tests
 * <p>
 * Copied-pasted methods from TestComputerPlayer, see docs in there
 *
 * @author JayDi85
 */

public final class TestComputerPlayerMonteCarlo extends ComputerPlayerMCTS {

    private TestPlayer testPlayerLink;

    public TestComputerPlayerMonteCarlo(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);
    }

    public void setTestPlayerLink(TestPlayer testPlayerLink) {
        this.testPlayerLink = testPlayerLink;
    }

    /**
     * Test seam for the production defect "AI (Monte Carlo / MCTS) seat stuck in infinite
     * null ability loop" (see
     * a missing computed
     * action must force a pass, never activateAbility(null).
     * <p>
     * Why a seam: the real MCTS search cannot run inside Mage.Tests —
     * {@code ComputerPlayerMCTS.createMCTSGame} does {@code MCTSPlayer.restore(originalPlayer)}
     * on every player of the live game, and that throws for the test framework's TestPlayer
     * wrappers ("Wrong code usage: can't restore from player class
     * org.mage.test.player.TestPlayer"), which is why CardTestPlayerBaseWithMonteCarloAIHelps
     * keeps the seat passive ({@code setAIPlayer(false)}). The seam injects the root state
     * getNextAction would have produced, so {@code ComputerPlayerMCTS.priority()} itself (the
     * code under test) runs for real without the search:
     * <ul>
     * <li>NONE (default) — no interception</li>
     * <li>NULL_ROOT — the root lookup/rebuild leaves root == null; priority() must not NPE on
     * root.getAction() and must pass the seat</li>
     * <li>NULL_ACTION — root exists but carries no action (the log's exact shape:
     * "choose action:null success ratio: -1.0" proves root != null with visits == 0)</li>
     * </ul>
     * {@link #setForcedPriorityActionForTest(Ability)} additionally pins a chosen action on the
     * injected root for negative controls (e.g. a PassAbility, or a real land-play ability).
     * Only the PRIORITY next-action is intercepted; attacker/blocker selection is untouched.
     */
    public enum NullActionMode {
        NONE, NULL_ROOT, NULL_ACTION
    }

    private NullActionMode nullActionMode = NullActionMode.NONE;
    private Ability forcedPriorityAction = null;
    private int interceptedPriorityCalls = 0;
    private int nullAbilityActivationCount = 0;

    public void setNullActionMode(NullActionMode nullActionMode) {
        this.nullActionMode = nullActionMode;
    }

    /**
     * Force the action the injected PRIORITY root will carry (null = none). Negative controls:
     * the injected root then behaves like a real computed root with that action.
     */
    public void setForcedPriorityActionForTest(Ability action) {
        this.forcedPriorityAction = action;
    }

    public int getInterceptedPriorityCalls() {
        return interceptedPriorityCalls;
    }

    public int getNullAbilityActivationCount() {
        return nullAbilityActivationCount;
    }

    @Override
    protected void getNextAction(Game game, NextAction nextAction) {
        if (nextAction == NextAction.PRIORITY
                && (nullActionMode != NullActionMode.NONE || forcedPriorityAction != null)) {
            interceptedPriorityCalls++;
            switch (nullActionMode) {
                case NULL_ROOT:
                    // no matching state and no rebuilt tree: priority() must survive root == null
                    root = null;
                    return;
                case NULL_ACTION:
                default:
                    // root exists but carries no action: only the child constructor sets an
                    // action, and calculateActions rebuilds an action-less root when the state
                    // lookup fails
                    root = new MCTSNode(playerId, game);
                    if (forcedPriorityAction != null) {
                        setRootActionForTest(root, forcedPriorityAction);
                    }
                    return;
            }
        }
        super.getNextAction(game, nextAction);
    }

    /**
     * Test-only helper for the seam above. MCTSNode.action has no setter: only the child
     * constructor sets it, and that needs a parent node. If MCTSNode's fields change, this seam
     * must be updated with them.
     */
    private static void setRootActionForTest(MCTSNode node, Ability action) {
        try {
            java.lang.reflect.Field field = MCTSNode.class.getDeclaredField("action");
            field.setAccessible(true);
            field.set(node, action);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("MCTS test seam can't set root action", e);
        }
    }

    @Override
    public boolean activateAbility(ActivatedAbility ability, Game game) {
        if (ability == null) {
            // regression probe: the fixed priority() must pass instead of calling this with null
            nullAbilityActivationCount++;
        }
        return super.activateAbility(ability, game);
    }

    @Override
    public boolean choose(Outcome outcome, Target target, Ability source, Game game) {
        if (testPlayerLink.canChooseByComputer()) {
            return super.choose(outcome, target, source, game);
        } else {
            return testPlayerLink.choose(outcome, target, source, game);
        }
    }

    @Override
    public boolean choose(Outcome outcome, Choice choice, Game game) {
        if (testPlayerLink.canChooseByComputer()) {
            return super.choose(outcome, choice, game);
        } else {
            return testPlayerLink.choose(outcome, choice, game);
        }
    }

    @Override
    public boolean choose(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        if (testPlayerLink.canChooseByComputer()) {
            return super.choose(outcome, cards, target, source, game);
        } else {
            return testPlayerLink.choose(outcome, cards, target, source, game);
        }
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game) {
        if (testPlayerLink.canChooseByComputer()) {
            return super.chooseTarget(outcome, target, source, game);
        } else {
            return testPlayerLink.chooseTarget(outcome, target, source, game);
        }
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        if (testPlayerLink.canChooseByComputer()) {
            return super.chooseTarget(outcome, cards, target, source, game);
        } else {
            return testPlayerLink.chooseTarget(outcome, cards, target, source, game);
        }
    }

    @Override
    public boolean flipCoinResult(Game game) {
        if (testPlayerLink.canChooseByComputer()) {
            return super.flipCoinResult(game);
        } else {
            return testPlayerLink.flipCoinResult(game);
        }
    }

    @Override
    public int rollDieResult(int sides, Game game) {
        if (testPlayerLink.canChooseByComputer()) {
            return super.rollDieResult(sides, game);
        } else {
            return testPlayerLink.rollDieResult(sides, game);
        }
    }

    @Override
    public boolean isComputer() {
        if (testPlayerLink.canChooseByComputer()) {
            return super.isComputer();
        } else {
            return testPlayerLink.isComputer();
        }
    }
}
