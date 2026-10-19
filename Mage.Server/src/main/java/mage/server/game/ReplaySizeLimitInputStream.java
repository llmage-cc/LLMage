package mage.server.game;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Bounds the total number of DECODED bytes a replay load may pull out of the
 * container before the object graph is deserialized.
 *
 * <p>WHY: {@code GameReplay.loadGame}
 * deserializes the whole {@code Game} plus the full {@code GameStates} list in a
 * single {@code readObject()}, so the heap cost is proportional to the
 * decompressed size of the save — not its size on disk. A 1.9 MB save
 * decodes to 323,088,708 B (~308 MiB); a {@code -Xmx2g} server dies with
 * {@code OutOfMemoryError} inside {@code ObjectInputStream} (server log:
 * "Catch unhandled error in CALL thread: Java heap space" —
 * {@code ObjectInputStream$HandleTable.grow}). An OOM is an {@link Error}, so it
 * bypasses every {@code catch (Exception)} on the RPC path: {@code REPLAY_GAME}
 * is never emitted, the viewer never mounts, and the failure is silent.</p>
 *
 * <p>This stream streams the decode and refuses the load up front: once the
 * running total of decoded bytes exceeds the budget it throws
 * {@link ReplaySizeLimitException} from {@code read()}. Because the ceiling is
 * crossed while streaming, the full object graph is never materialised, so the
 * process cannot OOM on an over-budget save. The failure is a normal catchable
 * exception the caller can surface.</p>
 *
 * @see ReplaySizeLimitException
 * @see GameReplay#loadGame
 */
public final class ReplaySizeLimitInputStream extends FilterInputStream {

    /**
     * Fraction of the JVM max heap a replay load may decode: {@code maxMemory/8}
     * (12.5%). Calibrated against the live corpus:
     * <ul>
     *   <li>a 1.9 MB save decodes to 323,088,708 B (~308 MiB) and OOMs
     *       at {@code -Xmx2g}; the 353,808,871 B outlier is worse;</li>
     *   <li>every normal save decodes to at most 90,225,702 B (~86 MiB).</li>
     * </ul>
     * At {@code -Xmx2g} this budget is 268,435,456 B (256 MiB): the over-budget
     * saves are rejected, all normal saves load. At {@code -Xmx7g} the
     * budget is 939,524,096 B (~896 MiB), so even the 1.9 MB save loads there —
     * the guard only fires where the load genuinely would not fit. The decoded
     * byte count is a lower bound on the heap the object graph needs, so an 1/8
     * cap leaves the remaining ~7/8 of the heap for object overhead.</p>
     */
    public static final int MAX_DECODED_BYTES_PER_MAX_HEAP = 8;

    private final long maxDecodedBytes;
    private long decodedBytes;

    /** Wraps {@code in} with the budget derived from the current JVM max heap. */
    public ReplaySizeLimitInputStream(InputStream in) {
        this(in, defaultBudgetBytes());
    }

    /** Wraps {@code in} with an explicit decoded-byte budget (tests, tuning). */
    public ReplaySizeLimitInputStream(InputStream in, long maxDecodedBytes) {
        super(in);
        if (maxDecodedBytes <= 0) {
            throw new IllegalArgumentException(
                    "maxDecodedBytes must be > 0, got " + maxDecodedBytes);
        }
        this.maxDecodedBytes = maxDecodedBytes;
    }

    /** Decoded-byte budget for the current JVM: {@code Runtime.maxMemory() / 8}. */
    public static long defaultBudgetBytes() {
        return Runtime.getRuntime().maxMemory() / MAX_DECODED_BYTES_PER_MAX_HEAP;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            account(1);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            account(n);
        }
        return n;
    }

    private void account(long n) throws ReplaySizeLimitException {
        decodedBytes += n;
        if (decodedBytes > maxDecodedBytes) {
            throw new ReplaySizeLimitException(decodedBytes, maxDecodedBytes);
        }
    }

    /** Total decoded bytes read so far (logging/tests). */
    public long getDecodedBytes() {
        return decodedBytes;
    }

    /** The configured budget in decoded bytes. */
    public long getMaxDecodedBytes() {
        return maxDecodedBytes;
    }
}
