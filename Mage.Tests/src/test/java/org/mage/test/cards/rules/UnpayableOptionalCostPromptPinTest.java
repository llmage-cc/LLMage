package org.mage.test.cards.rules;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

/**
 * Fork-authored pin for the optional-cost prompt-shape contract:
 * a real payability model must not silently swallow an optional-cost question.
 * <p>
 * Upstream's {@code ManaCostImpl.canPay} returned {@code true} unconditionally, so every
 * {@code canPay(...) && player.chooseUse("Pay ...?")} site asked the question. The fork
 * made {@code canPay} real, which turned those ask-gates into silent skips whenever the
 * cost was unpayable — and the scripted answer staged by a test was never consumed.
 * The engine restores the upstream prompt shape by asking unconditionally; paying an
 * unpayable cost then fails at payment time, exactly as it did upstream.
 * <p>
 * Not a duplicate of the upstream kicker tests: this isolates the unpayable case
 * ("Inscription of Abundance" {1}{G}, kicker {2}{G}, only two Forests — the kicker
 * needs three mana at the moment the question is asked).
 *
 */
public class UnpayableOptionalCostPromptPinTest extends CardTestPlayerBase {

    @Test
    public void testUnpayableKickerIsStillAsked() {
        // {1}{G}, Kicker {2}{G}: three mana needed for the kicker, two available.
        addCard(Zone.HAND, playerA, "Inscription of Abundance", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 2);
        addCard(Zone.BATTLEFIELD, playerA, "Balduvian Bears", 1); // 2/2

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Inscription of Abundance");
        setChoice(playerA, false); // answer the kicker question with "no"
        setModeChoice(playerA, "1"); // +1/+1 counters mode (single mode when unkicked)
        addTarget(playerA, "Balduvian Bears");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute(); // fails in assertAllCommandsUsed() if the question was skipped

        assertPowerToughness(playerA, "Balduvian Bears", 2 + 2, 2 + 2); // one counter pair, not kicked
        assertTappedCount("Forest", true, 2);
    }
}
