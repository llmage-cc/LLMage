package org.mage.test.player.p1;

import mage.constants.TurnPhase;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;

/**
 * Regression test for the phase-mapping bug found 2026-10-07: the P1 projection emitted
 * "BEGINNING" for the beginning phase, but the Argentum P1 checkpoint vocab has no BEGINNING
 * entry (only PRECOMBAT_MAIN/COMBAT/POSTCOMBAT_MAIN/ENDING), so features.py resolved it to the
 * UNK id and the net got an out-of-distribution phase token for every untap/upkeep/draw window.
 * The fix maps BEGINNING to PRECOMBAT_MAIN — the same squash stepName already applied to those
 * steps.
 * <p>
 * {@code P1Projection.phaseName} is private and the class package-private; reached by name via
 * reflection. RED property: pre-fix, the BEGINNING assertion fails ("BEGINNING" != "PRECOMBAT_MAIN").
 */
public class P1PhaseMappingTest {

    private static String phaseName(TurnPhase phase) throws Exception {
        Class<?> cls = Class.forName("mage.player.p1.P1Projection");
        Method m = cls.getDeclaredMethod("phaseName", TurnPhase.class);
        m.setAccessible(true);
        return (String) m.invoke(null, phase);
    }

    @Test
    public void beginningMapsToPrecombatMain_notUnkVocab() throws Exception {
        Assert.assertEquals("PRECOMBAT_MAIN", phaseName(TurnPhase.BEGINNING));
    }

    @Test
    public void otherPhasesUnchanged() throws Exception {
        Assert.assertEquals("PRECOMBAT_MAIN", phaseName(TurnPhase.PRECOMBAT_MAIN));
        Assert.assertEquals("COMBAT", phaseName(TurnPhase.COMBAT));
        Assert.assertEquals("POSTCOMBAT_MAIN", phaseName(TurnPhase.POSTCOMBAT_MAIN));
        Assert.assertEquals("ENDING", phaseName(TurnPhase.END));
    }
}
