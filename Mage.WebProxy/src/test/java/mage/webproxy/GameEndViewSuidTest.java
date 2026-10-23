package mage.webproxy;

import java.io.ObjectStreamClass;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import mage.view.GameEndView;
import org.junit.Assert;
import org.junit.Test;

/**
 * GameEndView crosses the RPC wire in both directions: the server sends it on
 * END_GAME_INFO, and this proxy deserializes it from an official server as well
 * as from our own. It has no upstream explicit pin, so its UID follows the
 * class's field shape — which means the additive {@code endReason} field would
 * silently move it and every older peer would fail with InvalidClassException.
 *
 * The pin was MEASURED on the unmodified 1.4.62 tree (2026-10-20): serialver and
 * ObjectStreamClass.lookup both report 6881988264274364339, and the official
 * 1.4.61 client jar's GameEndView computes the same value. That equality is
 * what lets an old desktop client skip the new field by name. If this test
 * fails, do NOT re-pin to the new number: re-derive both sides and treat it as
 * a deliberate wire break.
 */
public class GameEndViewSuidTest {

    private static long suid(Class<?> c) {
        ObjectStreamClass osc = ObjectStreamClass.lookup(c);
        Assert.assertNotNull("not serializable: " + c, osc);
        return osc.getSerialVersionUID();
    }

    @Test
    public void gameEndViewKeepsTheMeasuredWireUid() {
        Assert.assertEquals(6881988264274364339L, suid(GameEndView.class));
    }

    /**
     * The value above must come from an EXPLICIT pin, not from a computed shape:
     * a class without the field reports the same number today and a different
     * one after the next field change, which is exactly the drift this test
     * exists to catch.
     */
    @Test
    public void uidIsDeclaredOnTheClass() throws Exception {
        Field f = GameEndView.class.getDeclaredField("serialVersionUID");
        Assert.assertTrue("serialVersionUID must be static", Modifier.isStatic(f.getModifiers()));
        Assert.assertTrue("serialVersionUID must be final", Modifier.isFinal(f.getModifiers()));
        Assert.assertEquals(long.class, f.getType());
    }
}
