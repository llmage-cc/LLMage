package org.mage.test.serverside.performance;

import mage.abilities.condition.common.ScryOrSurveilCondition;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Guards the replay-orphaning class of bug behind
 * replay UID drift: an engine class whose computed UID changes between
 * builds orphans every previously-saved game.
 *
 * <p>Every engine class that reaches a {@code .game} replay stream must carry a DELIBERATE
 * {@code serialVersionUID}. Without one the JVM derives it from the class's fields, methods and
 * superclass chain, so any rebuild that touches the class silently changes the value and every
 * previously-saved game carrying that class fails to load with {@code InvalidClassException}. A
 * pinned UID is the only thing that breaks that coupling.
 *
 * <p>These tests fail loudly if a pin is removed or if the value changes, so breaking replay
 * compatibility can never happen as a refactor side effect. The values asserted here are the ones
 * the pin commits froze; they are also pinned in the classes' own source.
 */
public class SerialVersionUidPinsTest {

    /** Asserts the class still carries the exact pin, and reports the drift clearly if not. */
    private static void assertPin(String fqn, Class<?> c, long expected) {
        long actual = ObjectStreamClass.lookup(c).getSerialVersionUID();
        assertEquals(fqn + " serialVersionUID changed to " + actual + " (expected " + expected
                        + ") — this orphans every .game save that carries the old value. If the break is"
                        + " deliberate, update the pin AND scripts/serial-uid-manifest.txt together"
                        + "",
                expected, actual);
    }

    @Test
    public void test_newEnginePinsFrom2026_10_02() {
        assertPin("mage.abilities.common.AttacksPlayerAloneControlledTriggeredAbility",
                mage.abilities.common.AttacksPlayerAloneControlledTriggeredAbility.class,
                -3570144507765990434L);
        assertPin("mage.abilities.effects.common.AttachToSourceTargetEffect",
                mage.abilities.effects.common.AttachToSourceTargetEffect.class,
                -4505143709879460827L);
        assertPin("mage.abilities.effects.common.ruleModifying.CastOnlyOneCommanderPerGameEffect",
                mage.abilities.effects.common.ruleModifying.CastOnlyOneCommanderPerGameEffect.class,
                -5724925742847283570L);
        assertPin("mage.filter.predicate.mageobject.SpellOrTargetsPermanentPredicate",
                mage.filter.predicate.mageobject.SpellOrTargetsPermanentPredicate.class,
                2821881409499991948L);
        assertPin("mage.watchers.common.ScryOrSurveilWatcher",
                mage.watchers.common.ScryOrSurveilWatcher.class,
                6977335614126595227L);
    }

    @Test
    public void test_newTokenPinsFrom2026_10_02() {
        assertPin("mage.game.permanent.token.BlueAngelToken",
                mage.game.permanent.token.BlueAngelToken.class, -9069992225952058273L);
        assertPin("mage.game.permanent.token.ForestTentacleToken",
                mage.game.permanent.token.ForestTentacleToken.class, 6781361087889443144L);
        assertPin("mage.game.permanent.token.GingerbruteToken",
                mage.game.permanent.token.GingerbruteToken.class, -2596303535555184025L);
        assertPin("mage.game.permanent.token.LotusToken",
                mage.game.permanent.token.LotusToken.class, -6603041440864492621L);
        assertPin("mage.game.permanent.token.SanctumToken",
                mage.game.permanent.token.SanctumToken.class, -1426367231911116642L);
        assertPin("mage.game.permanent.token.SculptureTreasureToken",
                mage.game.permanent.token.SculptureTreasureToken.class, -4048894276367478684L);
    }

    /**
     * DungeonRoom reaches .game saves via the command zone (Dungeon.dungeonRooms/currentRoom and
     * Dungeon.abilites -> RoomTriggeredAbility.room); it was not Serializable and aborted every
     * dungeon game's save.
     */
    @Test
    public void test_dungeonRoomPinFrom2026_10_04() {
        assertPin("mage.game.command.DungeonRoom",
                mage.game.command.DungeonRoom.class, -4590315335003225207L);
    }

    /**
     * Enum UIDs are 0 by the serialization specification — an enum is written by constant NAME, so
     * the pin can never drift. Asserted so the zero-pin is a documented decision, not an accident.
     */
    @Test
    public void test_enumPinsAreAlwaysZero() {
        assertPin("mage.abilities.condition.common.ScryOrSurveilCondition",
                ScryOrSurveilCondition.class, 0L);
    }

    /**
     * The pin is only load-bearing if it is the value written to the stream. Serialize a real
     * instance, read the descriptor back off the wire, and compare — a source-level constant that
     * never reaches the stream would pass a reflection-only check and still orphan the corpus.
     */
    @Test
    public void test_theDeclaredPinIsWhatTheStreamCarries() throws Exception {
        long streamUid = firstStreamUid(serialize(
                new mage.abilities.effects.common.AttachToSourceTargetEffect()),
                "mage.abilities.effects.common.AttachToSourceTargetEffect");
        assertEquals("the serialized stream UID of AttachToSourceTargetEffect differs from its "
                + "source pin", -4505143709879460827L, streamUid);
    }

    /**
     * NEGATIVE CONTROL at the unit level, using the JDK's own contract: a stream written under one
     * UID cannot be read once the local UID differs. This is the exact failure seen in the field against
     * all 35 of its own saves, reproduced in one test so the gate's premise never silently rots.
     */
    @Test
    public void test_aChangedUidMakesAnOldStreamUnreadable() throws Exception {
        // write as Uid1, read as Uid2 — same class name, different declared UID, i.e. what a
        // rebuild with a changed pin produces
        byte[] streamUnderUid1 = serialize(new Uid1());
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(streamUnderUid1)) {
            @Override
            protected Class<?> resolveClass(ObjectStreamClass desc) {
                return Uid2.class; // same shape, different UID — the drift
            }
        }) {
            in.readObject();
            fail("a stream whose class UID differs must NOT deserialize silently");
        } catch (InvalidClassException expected) {
            assertTrue("expected UID mismatch, got: " + expected.getMessage(),
                    expected.getMessage().contains("serialVersionUID"));
        }
    }

    /** Two instances of a pinned class must produce the same stream UID — the property the gate needs. */
    @Test
    public void test_pinnedUidIsStableAcrossInstances() throws Exception {
        String fqn = Uid1.class.getName();
        assertEquals(1L, ObjectStreamClass.lookup(Uid1.class).getSerialVersionUID());
        assertEquals(firstStreamUid(serialize(new Uid1()), fqn),
                firstStreamUid(serialize(new Uid1()), fqn));
    }

    // ------------------------------------------------------------------ helpers

    /** A Serializable with explicitly controlled UIDs, for the negative control. */
    private static class Uid1 implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    private static class Uid2 implements Serializable {
        private static final long serialVersionUID = 2L; // the "drift"
    }

    private static byte[] serialize(Serializable o) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(o);
        }
        return bos.toByteArray();
    }

    /**
     * The serialVersionUID the stream carries for {@code fqn}. Superclass class-descriptors are
     * written BEFORE the subclass's, so the helper must key by descriptor name, not take the first
     * resolveClass() call (that would return the topmost superclass's UID).
     */
    private static long firstStreamUid(byte[] stream, String fqn) throws Exception {
        final long[] seen = {-1L};
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(stream)) {
            @Override
            protected Class<?> resolveClass(ObjectStreamClass desc) throws java.io.IOException,
                    ClassNotFoundException {
                if (fqn.equals(desc.getName())) {
                    seen[0] = desc.getSerialVersionUID();
                }
                return super.resolveClass(desc); // let the graph read proceed normally
            }
        }) {
            in.readObject();
        }
        if (seen[0] == -1L) {
            throw new AssertionError("class descriptor for " + fqn + " was not found in the stream");
        }
        return seen[0];
    }
}
