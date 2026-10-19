package mage.server.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Pins the replay load-size guard contract ({@link ReplaySizeLimitInputStream}).
 *
 * <p>This guards the replay-load OOM fix: {@code GameReplay.loadGame}
 * deserializes the whole save, so a 1.9 MB save that decodes to ~308 MiB died with
 * {@code OutOfMemoryError} on a {@code -Xmx2g} server (an {@link Error}, so the CALL
 * thread died silently). The guard must refuse an over-budget save with a typed,
 * catchable {@link ReplaySizeLimitException} — without ever materialising the
 * graph — while leaving every in-budget save loadable.</p>
 *
 * <p>The two calibration cases use the exact byte counts measured on a real
 * {@code saved/} corpus, so the budget rule cannot silently drift:</p>
 * <ul>
 *   <li>reject at {@code -Xmx2g}: 323,088,708 B (an over-budget save, ~308 MiB);</li>
 *   <li>still load: 90,225,702 B (the largest normal save, ~86 MiB).</li>
 * </ul>
 */
class ReplaySizeLimitInputStreamTest {

    /** {@code -Xmx2g} in bytes. */
    private static final long TWO_GIG_HEAP = 2L * 1024 * 1024 * 1024;
    /** The budget the guard derives for that heap: maxMemory / 8 = 268,435,456 B. */
    private static final long TWO_GIG_BUDGET = TWO_GIG_HEAP / 8;
    /** Decoded size of an over-budget save that OOMed a 2 GB heap. */
    private static final long INCIDENT_DECODED_BYTES = 323_088_708L;
    /** Measured largest normal save. */
    private static final long LARGEST_NORMAL_DECODED_BYTES = 90_225_702L;

    @Test
    @DisplayName("an in-budget save loads (largest normal save, ~86 MiB)")
    void normalSaveLoads() throws IOException {
        try (InputStream guard = new ReplaySizeLimitInputStream(
                new CountingSource(LARGEST_NORMAL_DECODED_BYTES), TWO_GIG_BUDGET)) {
            long total = drain(guard, 8192);
            assertThat(total).isEqualTo(LARGEST_NORMAL_DECODED_BYTES);
            assertThat(((ReplaySizeLimitInputStream) guard).getDecodedBytes())
                    .isEqualTo(LARGEST_NORMAL_DECODED_BYTES);
        }
    }

    @Test
    @DisplayName("an over-budget 308 MiB save is refused at the -Xmx2g budget with a typed failure")
    void incidentSaveRejectedAtTwoGig() {
        try (InputStream guard = new ReplaySizeLimitInputStream(
                new CountingSource(INCIDENT_DECODED_BYTES), TWO_GIG_BUDGET)) {
            assertThatExceptionOfType(ReplaySizeLimitException.class)
                    .isThrownBy(() -> drain(guard, 8192))
                    .withMessageContaining("too large to open")
                    .withMessageContaining("256 MiB");
        } catch (IOException e) {
            throw new AssertionError("unexpected close failure", e);
        }
    }

    @Test
    @DisplayName("the guard stops pulling from the source at the budget (fail fast, no OOM)")
    void guardStopsReadingSourceAtBudget() throws IOException {
        long budget = 1000;
        CountingSource source = new CountingSource(10_000_000L);
        try (InputStream guard = new ReplaySizeLimitInputStream(source, budget)) {
            assertThatExceptionOfType(ReplaySizeLimitException.class)
                    // single-byte reads: overshoot is at most one byte
                    .isThrownBy(() -> drain(guard, 1));
        }
        assertThat(source.getServed())
                .as("the guard must not read the whole stream — that is what OOMs")
                .isLessThanOrEqualTo(budget + 1);
    }

    @Test
    @DisplayName("single-byte reads are counted as well as bulk reads")
    void singleByteReadsAreBounded() throws IOException {
        try (InputStream guard = new ReplaySizeLimitInputStream(
                new CountingSource(100L), 10)) {
            assertThatExceptionOfType(ReplaySizeLimitException.class)
                    .isThrownBy(() -> drain(guard, 1));
        }
    }

    @Test
    @DisplayName("a small serialized object graph round-trips through the guard unchanged")
    void smallObjectGraphRoundTrips() throws Exception {
        List<String> payload = Arrays.asList("frame-0", "frame-1", "frame-2");
        byte[] serialized = serialize(payload);
        try (InputStream guard = new ReplaySizeLimitInputStream(
                new ByteArrayInputStream(serialized), 1 << 20);
             ObjectInputStream in = new ObjectInputStream(guard)) {
            assertThat(in.readObject()).isEqualTo(payload);
        }
    }

    @Test
    @DisplayName("an over-budget object graph surfaces the typed failure through ObjectInputStream")
    void objectInputStreamSurfacesTypedFailure() throws Exception {
        // A serialized graph comfortably larger than the budget, so the guard fires
        // mid-deserialization — exactly the GameReplay.loadGame read path.
        List<String> big = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            big.add("state-" + i + "-payload-payload-payload");
        }
        byte[] serialized = serialize(big);
        assertThat(serialized.length).isGreaterThan(4096);

        try (InputStream guard = new ReplaySizeLimitInputStream(
                new ByteArrayInputStream(serialized), 4096);
             ObjectInputStream in = new ObjectInputStream(guard)) {
            Throwable thrown = catchThrowable(in::readObject);
            assertThat(thrown).isInstanceOf(IOException.class);
            assertThat(ReplaySizeLimitException.unwrap(thrown))
                    .as("the typed failure must reach the loadGame IOException catch")
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("the default budget is one eighth of the JVM max heap")
    void defaultBudgetIsOneEighthOfMaxHeap() {
        assertThat(ReplaySizeLimitInputStream.defaultBudgetBytes())
                .isEqualTo(Runtime.getRuntime().maxMemory() / 8)
                .isPositive();
    }

    @Test
    @DisplayName("a non-positive budget is rejected up front (no silent default)")
    void nonPositiveBudgetIsRejected() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ReplaySizeLimitInputStream(new CountingSource(1), 0));
    }

    // ---- helpers ----

    private static byte[] serialize(Object o) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(o);
        }
        return bos.toByteArray();
    }

    private static long drain(InputStream in, int bufferSize) throws IOException {
        byte[] buf = new byte[bufferSize];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
        }
        return total;
    }

    /**
     * A lazily-generated byte source of a given logical size. It allocates nothing
     * proportional to its size and records how many bytes it actually served, so a
     * test can prove the guard stops reading instead of pulling the whole stream.
     */
    private static final class CountingSource extends InputStream {

        private final long totalBytes;
        private long served;

        CountingSource(long totalBytes) {
            this.totalBytes = totalBytes;
        }

        long getServed() {
            return served;
        }

        @Override
        public int read() {
            if (served >= totalBytes) {
                return -1;
            }
            served++;
            return (int) (served & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (served >= totalBytes) {
                return -1;
            }
            int n = (int) Math.min(len, totalBytes - served);
            for (int i = 0; i < n; i++) {
                b[off + i] = (byte) ((served + i) & 0xFF);
            }
            served += n;
            return n;
        }
    }
}
