package org.mage.test.serialization;

import mage.abilities.costs.common.BeholdCost;
import mage.abilities.keyword.BeholdAbility;
import mage.constants.BeholdType;
import mage.constants.SubType;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

/**
 * RED test for
 * <p>
 * The game-state serializer walks the live game graph on saveGame; any holder that stores a
 * {@link BeholdType} instance (BeholdCost / BeholdAndExileCost / BeholdAbility) makes the whole
 * graph unserializable and aborts the save with {@code NotSerializableException:
 * mage.constants.BeholdType}.
 * <p>
 * These cases round-trip the exact holder objects through {@link ObjectOutputStream} /
 * {@link ObjectInputStream} and assert (a) the round trip completes and (b) the deserialized
 * holder still references the CANONICAL {@code BeholdType} instance from
 * {@link BeholdType#getBeholdType(SubType)} — because {@code BeholdType.typeMap} is a canonical
 * instance cache and identity is what callers compare.
 *
 *
 */
public class BeholdTypeSerializationTest {

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }

    private static BeholdType readBeholdTypeField(Object holder) throws Exception {
        java.lang.reflect.Field field = holder.getClass().getDeclaredField("beholdType");
        field.setAccessible(true);
        return (BeholdType) field.get(holder);
    }

    @Test
    public void beholdCostRoundTripsAndKeepsCanonicalBeholdType() throws Exception {
        SubType subType = SubType.DRAGON;
        BeholdType canonical = BeholdType.getBeholdType(subType);
        BeholdCost original = new BeholdCost(canonical, 1);

        BeholdCost restored = roundTrip(original);

        assertNotNull(restored);
        assertSame("deserialized BeholdCost must reference the canonical cached BeholdType",
                canonical, readBeholdTypeField(restored));
    }

    @Test
    public void beholdAbilityRoundTripsAndKeepsCanonicalBeholdType() throws Exception {
        SubType subType = SubType.GOBLIN;
        BeholdType canonical = BeholdType.getBeholdType(subType);
        BeholdAbility original = new BeholdAbility(subType);

        BeholdAbility restored = roundTrip(original);

        assertNotNull(restored);
        assertSame("deserialized BeholdAbility must reference the canonical cached BeholdType",
                canonical, readBeholdTypeField(restored));
    }
}