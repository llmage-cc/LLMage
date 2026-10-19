package mage.server.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Pins the replay container format contract (ReplayCodec).
 *
 * <p>These are the tests that make the zstd switch safe to deploy: the exact
 * object-write/read shape saveGame/loadGame use, an explicit failure for the
 * legacy gzip container, and a failure for arbitrary junk. The byte-level
 * round trip through ObjectOutputStream is what breaks silently if the codec
 * is ever swapped without this test.</p>
 */
class ReplayCodecTest {

    @Test
    @DisplayName("zstd container round-trips a serialized object graph byte-for-byte")
    void roundTripObjectGraph() throws IOException, ClassNotFoundException {
        List<String> payload = new ArrayList<>();
        StringBuilder filler = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            filler.append("state data ");
        }
        for (int i = 0; i < 500; i++) {
            payload.add("frame-" + i + ": " + filler);
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream codec = ReplayCodec.wrapForWrite(bytes);
             ObjectOutputStream out = new ObjectOutputStream(codec)) {
            out.writeObject(payload);
        }

        // The container is zstd (magic FD2FB528 little-endian = 28 B5 2F FD),
        // not gzip (1F 8B) — the thing the old writer produced.
        byte[] onDisk = bytes.toByteArray();
        assertThat(onDisk[0]).isEqualTo((byte) 0x28);
        assertThat(onDisk[1]).isEqualTo((byte) 0xB5);
        assertThat(onDisk[2]).isEqualTo((byte) 0x2F);
        assertThat(onDisk[3]).isEqualTo((byte) 0xFD);

        // Same write/read shape as GameController.saveGame / GameReplay.loadGame.
        try (InputStream codec = ReplayCodec.wrapForRead(new ByteArrayInputStream(onDisk));
             ObjectInputStream in = new ObjectInputStream(codec)) {
            Object read = in.readObject();
            assertThat(read).isEqualTo(payload);
        }

        // And the compressed size is genuinely smaller than raw gzip of the same
        // payload on a self-similar stream (the whole point of the switch).
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz);
             ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(payload);
        }
        assertThat(onDisk.length).isLessThan(gz.size());
    }

    @Test
    @DisplayName("legacy gzip replays fail fast with a message naming the conversion script")
    void legacyGzipFailsFast() throws IOException {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz);
             ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject("old replay written before the zstd switch");
        }
        byte[] legacy = gz.toByteArray();
        assertThat(legacy[0]).isEqualTo((byte) 0x1F);
        assertThat(legacy[1]).isEqualTo((byte) 0x8B);

        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> ReplayCodec.wrapForRead(new ByteArrayInputStream(legacy)))
                .withMessageContaining("legacy gzip")
                .withMessageContaining("replay-codec-migrate.sh");
    }

    @Test
    @DisplayName("non-replay bytes fail fast with the actual magic, not a deserialization error")
    void junkFailsFast() {
        byte[] junk = {'P', 'K', 0x03, 0x04, 0x00, 0x00}; // a zip header, say
        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> ReplayCodec.wrapForRead(new ByteArrayInputStream(junk)))
                .withMessageContaining("not a zstd replay")
                .withMessageContaining("50 4b 03 04");
    }

    @Test
    @DisplayName("truncated files (shorter than the magic) fail fast, not with an EOF inside readObject")
    void truncatedFailsFast() {
        byte[] tiny = {0x28, (byte) 0xB5};
        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> ReplayCodec.wrapForRead(new ByteArrayInputStream(tiny)))
                .withMessageContaining("2 bytes");
    }

    @Test
    @DisplayName("wrapForRead leaves the stream positioned for the zstd reader (magic re-consumed)")
    void readerSeesFullStream(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("sample.game");
        try (OutputStream out = Files.newOutputStream(file);
             OutputStream codec = ReplayCodec.wrapForWrite(out)) {
            codec.write("hello replay".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        try (InputStream in = Files.newInputStream(file);
             InputStream codec = ReplayCodec.wrapForRead(in)) {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            byte[] buf = new byte[64];
            int n;
            while ((n = codec.read(buf)) > 0) {
                sink.write(buf, 0, n);
            }
            assertThat(new String(sink.toByteArray(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("hello replay");
        }
    }
}
