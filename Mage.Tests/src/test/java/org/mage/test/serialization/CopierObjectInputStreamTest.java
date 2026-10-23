package org.mage.test.serialization;

import mage.util.CopierObjectInputStream;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * A replay save that references a class this build cannot load must fail with a
 * {@link ClassNotFoundException} that names the missing class and keeps the original
 * failure as its cause. Before the fix {@code resolveClass} swallowed the exception and
 * returned null, so the JDK raised {@code ClassNotFoundException: null class} and the real
 * class name only reached stderr — which made a missing player-type class look like an
 * incompatible build during the replay campaign.
 *
 * @author wave1021
 */
public class CopierObjectInputStreamTest {

    private static final String MISSING_CLASS = "mage.player.MissingReplayPlayer";

    private static class Payload implements Serializable {

        private static final long serialVersionUID = 1L;
        private final int value;

        private Payload(int value) {
            this.value = value;
        }
    }

    private static CopierObjectInputStream stream(byte[] bytes) throws IOException {
        return new CopierObjectInputStream(CopierObjectInputStreamTest.class.getClassLoader(),
                new ByteArrayInputStream(bytes));
    }

    /**
     * Hand-built stream: TC_OBJECT of a class whose name is not on the classpath. Only the
     * descriptor is written, so the read fails exactly where a replay of a save from a build
     * with an extra player type fails.
     */
    private static byte[] bytesOfMissingClass(String className) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(0xACED);
        out.writeShort(5);
        out.writeByte(0x73);
        out.writeByte(0x72);
        out.writeUTF(className);
        out.writeLong(1L);
        out.writeByte(0x02);
        out.writeShort(0);
        out.writeByte(0x78);
        out.writeByte(0x70);
        out.flush();
        return bytes.toByteArray();
    }

    private static byte[] bytesOfPayload(int value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(new Payload(value));
        }
        return bytes.toByteArray();
    }

    @Test
    public void unresolvableClassFailsWithClassNotFoundExceptionNamingIt() throws IOException {
        try (CopierObjectInputStream in = stream(bytesOfMissingClass(MISSING_CLASS))) {
            ClassNotFoundException error = assertThrows(ClassNotFoundException.class, in::readObject);

            assertNotNull("message must name the missing class", error.getMessage());
            assertTrue("message was: " + error.getMessage(),
                    error.getMessage().contains(MISSING_CLASS));
        }
    }

    @Test
    public void unresolvableClassKeepsTheOriginalCause() throws IOException {
        try (CopierObjectInputStream in = stream(bytesOfMissingClass(MISSING_CLASS))) {
            ClassNotFoundException error = assertThrows(ClassNotFoundException.class, in::readObject);

            Throwable cause = error.getCause();
            assertNotNull("the original resolution failure must be kept as the cause", cause);
            assertTrue("cause was: " + cause.getClass().getName(),
                    cause instanceof ClassNotFoundException);
            assertTrue("cause message was: " + cause.getMessage(),
                    cause.getMessage() != null && cause.getMessage().contains(MISSING_CLASS));
        }
    }

    @Test
    public void resolvableClassStillReads() throws IOException, ClassNotFoundException {
        try (CopierObjectInputStream in = stream(bytesOfPayload(42))) {
            Payload restored = (Payload) in.readObject();

            assertNotNull(restored);
            assertEquals(42, restored.value);
        }
    }
}
