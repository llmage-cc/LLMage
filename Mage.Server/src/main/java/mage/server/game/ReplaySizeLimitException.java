package mage.server.game;

import java.io.IOException;

/**
 * Thrown when a replay load attempts to decode more than the configured
 * decoded-byte budget (see {@link ReplaySizeLimitInputStream}).
 *
 * <p>This is an {@link IOException} on purpose. The replay read path
 * ({@code ObjectInputStream.readObject()}) already declares and catches
 * {@code IOException}, so an over-budget save travels as an ordinary, catchable
 * load failure. An {@link OutOfMemoryError} — the failure this replaces — is an
 * {@link Error}, sails past every {@code catch (Exception)} in the RPC path and
 * kills the CALL thread, which is exactly why the original failure was silent.</p>
 */
public class ReplaySizeLimitException extends IOException {

    private static final long serialVersionUID = 1L;

    private final long decodedBytes;
    private final long maxDecodedBytes;

    public ReplaySizeLimitException(long decodedBytes, long maxDecodedBytes) {
        super("replay is too large to open: it needs more than this server's replay budget of "
                + mib(maxDecodedBytes) + " MiB (1/"
                + ReplaySizeLimitInputStream.MAX_DECODED_BYTES_PER_MAX_HEAP
                + " of max heap) - refusing to load rather than risk an OutOfMemoryError");
        this.decodedBytes = decodedBytes;
        this.maxDecodedBytes = maxDecodedBytes;
    }

    /** Total decoded bytes seen when the budget was crossed. */
    public long getDecodedBytes() {
        return decodedBytes;
    }

    /** The configured budget in decoded bytes. */
    public long getMaxDecodedBytes() {
        return maxDecodedBytes;
    }

    /**
     * Returns the {@link ReplaySizeLimitException} in {@code t}'s cause chain, or
     * {@code null} if there is none. The deserializer can wrap an underlying
     * stream failure, so callers classify an {@link IOException} by walking the
     * chain rather than by the top-level type alone.
     */
    public static ReplaySizeLimitException unwrap(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof ReplaySizeLimitException) {
                return (ReplaySizeLimitException) c;
            }
            if (c.getCause() == c) {
                break; // self-referential cause guard
            }
        }
        return null;
    }

    private static long mib(long bytes) {
        return bytes / (1024L * 1024L);
    }
}
