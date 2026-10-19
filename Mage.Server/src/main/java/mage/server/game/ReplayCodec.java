package mage.server.game;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;

/**
 * Codec for the replay {@code .game} container: zstd, level 9, frame checksum on.
 *
 * <p>WHY ZSTD: every saved frame is a full {@code GameState.copy()},
 * so a 25-turn game is ~340 MB of near-identical snapshots. gzip's 32 KB deflate
 * window cannot see redundancy spanning megabytes; the same bytes measured with
 * zstd level 9 compress to 2.1 MB (16x smaller than the 34.7 MB on-disk gzip of
 * the largest save), write in ~0.7 s (vs 7.6 s for gzip -6) and decode in ~40 ms
 * (vs 1.3 s gunzip). Level 9 is the measured sweet spot: levels 12+ cost 3-30x
 * more write time for under 1% size (re-measure with
 * {@code zcat file | zstd -N -o out}; the full ladder is in
 * </p>
 *
 * <p>The on-disk name stays {@code <uuid>.game}; the format is identified by the
 * 4-byte magic. This reader is zstd-only BY DESIGN (no dual-format fallback):
 * a legacy gzip file fails fast with a message naming the conversion script
 * {@code scripts/replay-codec-migrate.sh}.</p>
 */
public final class ReplayCodec {

    /** Compression level for the replay container. Keep scripts/replay-codec-migrate.sh in sync. */
    public static final int LEVEL = 9;

    private static final int MAGIC_LEN = 4;
    /** zstd frame magic 0xFD2FB528, little-endian on disk. */
    private static final byte[] MAGIC_ZSTD = {0x28, (byte) 0xB5, 0x2F, (byte) 0xFD};
    /** gzip magic 1F 8B (legacy replay container). */
    private static final byte[] MAGIC_GZIP = {0x1F, (byte) 0x8B};

    private ReplayCodec() {
    }

    /**
     * Wrap the destination stream for writing a replay. The returned stream must
     * be closed (or have close() reached through the ObjectOutputStream) before
     * the underlying file stream, so the zstd frame epilogue is written.
     *
     * <p>Frame checksum is enabled: it is 4 bytes per frame and gives the same
     * end-to-end integrity check gzip's CRC used to provide — a corrupted save
     * fails on load instead of deserializing garbage.</p>
     */
    public static OutputStream wrapForWrite(OutputStream out) throws IOException {
        ZstdOutputStream zstd = new ZstdOutputStream(out);
        zstd.setLevel(LEVEL);
        zstd.setChecksum(true);
        return zstd;
    }

    /**
     * Wrap a replay file stream for reading.
     *
     * <p>Fails fast with an explicit message when the file is not a zstd replay:
     * a legacy gzip container (files written before 2026-09-24) names the
     * conversion script; anything else reports the actual magic bytes.</p>
     */
    public static InputStream wrapForRead(InputStream in) throws IOException {
        PushbackInputStream probe = new PushbackInputStream(in, MAGIC_LEN);
        byte[] magic = new byte[MAGIC_LEN];
        int n = probe.read(magic);
        if (n < MAGIC_LEN) {
            throw new IOException("replay file is only " + Math.max(n, 0)
                    + " bytes — not a zstd replay");
        }
        probe.unread(magic);
        if (startsWith(magic, MAGIC_GZIP)) {
            throw new IOException("legacy gzip replay container — recompress this file with"
                    + " scripts/replay-codec-migrate.sh (replay saves are zstd-only since 2026-09-24)");
        }
        if (!startsWith(magic, MAGIC_ZSTD)) {
            throw new IOException(String.format(
                    "not a zstd replay (magic %02x %02x %02x %02x)",
                    magic[0], magic[1], magic[2], magic[3]));
        }
        return new ZstdInputStream(probe);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
