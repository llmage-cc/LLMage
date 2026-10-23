package mage.abilities.costs;

import mage.abilities.Ability;
import mage.abilities.costs.mana.ManaCost;
import mage.game.Game;

import java.util.UUID;

/**
 * Payability answer for OPTIONAL-COST QUESTIONS ("do you want to pay ...?").
 * <p>
 * Fork history: upstream's {@code ManaCostImpl.canPay} and {@code ManaCostsImpl.canPay} were
 * permissive (they answered {@code true} for every symbol, i.e. for any cost made of mana).
 * Fork commit {@code 03cee7a3515} made them answer real payability. That is the right answer
 * for PLAYABILITY gates ({@code PlayerImpl.canPlay*}, cast/activate checks), but it silently
 * changed the prompt shape at every {@code canPay(...) && player.chooseUse("Pay ...?")} site:
 * an unpayable optional cost stopped being offered, and a scripted answer staged by a test was
 * never consumed (engine-CI family M1, 30 cases / 23 classes).
 * <p>
 * This helper restores the UPSTREAM ANSWER at ask sites instead of blindly asking:
 * <ul>
 *   <li>a mana cost (single {@link ManaCost} or a {@code ManaCosts}, both are {@link ManaCost})
 *       answers {@code true}, exactly like upstream;</li>
 *   <li>a composite {@link Costs} answers by the same rule for its children — upstream's
 *       {@code ManaCostsImpl.canPay} was an AND over children, each a mana cost answering
 *       {@code true};</li>
 *   <li>any other cost (life, sacrifice, discard, counters, energy, exile, ...) keeps its REAL
 *       {@code canPay} answer — those were never permissive upstream, so their ask-gate was and
 *       remains meaningful.</li>
 * </ul>
 * Paying still fails at payment time when the player answers yes to an unpayable mana cost,
 * exactly as it did upstream — this only decides whether the question is asked.
 *
 */
public final class OptionalCostPrompt {

    private OptionalCostPrompt() {
    }

    public static boolean canPay(Cost cost, Ability ability, Ability source, UUID controllerId, Game game) {
        if (cost instanceof ManaCost) {
            return true;
        }
        if (cost instanceof Costs) {
            for (Object child : (Costs<?>) cost) {
                if (!canPay((Cost) child, ability, source, controllerId, game)) {
                    return false;
                }
            }
            return true;
        }
        return cost.canPay(ability, source, controllerId, game);
    }
}
