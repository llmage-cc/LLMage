package org.mage.test.LLM;

import com.sun.net.httpserver.HttpServer;
import mage.constants.RangeOfInfluence;
import mage.player.llm.LLMClient;
import mage.player.llm.LLMResponse;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pins the reasoning-bleed RECOVERY on the REAL captured zen wire shape

 *
 * <p>Why this replaces the first, VACUOUS fixture: the first version of the
 * recovery gated on {@code finish_reason == "stop"} and served hand-rolled
 * non-streaming JSON through a stub client. It passed 6/6 yet the branch was
 * DEAD CODE live — a recording reverse-proxy in front of glm-5.3-flash @
 * opencode zen/go captured the long stream with EVERY chunk
 * {@code "finish_reason":null} and NO terminal {@code stop} chunk
 * ({@code rec/zen-raw-frames/s0008.raw}, 119/119 chunks null), while the real
 * game still terminal-failed at turn 4 (5 empty answers with non-blank
 * reasoning → concede) and the promotion WARN never fired. A hand-built
 * {@code finish_reason="stop"} body cannot reproduce a gateway that omits the
 * field, so the old test proved nothing about production.
 *
 * <p>This test drives the PRODUCTION path end to end over the captured bytes:
 * an in-process HTTP server replays the verbatim SSE frames from that capture,
 * the REAL {@link LLMClient#sendStreamingRequest} reads them (the actual SSE
 * reader / finish_reason capture), and the REAL retry ladder
 * ({@code chatAndRecordWithRetry}) is exercised through a {@code TestLLMPlayer}.
 * The null-finish_reason case is RED against the pre-fix {@code "stop"} gate and
 * GREEN with the fix; the short-stream {@code "stop"} case, the {@code length}
 * truncation case and the genuine-empty case pin the conservative paths.
 *
 * <p>No network beyond loopback; no live model. The endpoint is a local
 * {@code HttpServer} on an ephemeral port. {@code TestLLMPlayer} construction is
 * harness-gated (no Ollama probe) — the same skip gate as every LLM test.
 */
public class LLMReasoningBleedRecoveryTest {

    @BeforeClass
    public static void checkLLMRunEnabled() {
        Assume.assumeFalse("LLM tests skipped (set -Dxmage.llm.skip=false to run)",
                Boolean.getBoolean("xmage.llm.skip"));
    }

    // ── Captured wire frames (verbatim, from rec/zen-raw-frames/s0008.raw) ──

    /**
     * The 15 {@code delta.reasoning_content} chunks of s0008, concatenated by the
     * REAL reader to 103 chars. This is the exact reasoning the gateway routed to
     * the reasoning field while leaving content empty.
     */
    private static final String CAPTURED_REASONING =
            "Write reflection — game ended turn 1 due to opponent quadruple mull to 4."
            + " Be honest: no real decisions.";

    /** The captured reasoning frames, in order (each is one SSE data line body). */
    private static final String[] CAPTURED_REASONING_FRAMES = {
        frame("{\"reasoning_content\":\"Write\"}"),
        frame("{\"reasoning_content\":\" reflection\"}"),
        frame("{\"reasoning_content\":\" —\"}"),
        frame("{\"reasoning_content\":\" game ended\"}"),
        frame("{\"reasoning_content\":\" turn\"}"),
        frame("{\"reasoning_content\":\" 1 due\"}"),
        frame("{\"reasoning_content\":\" to opponent quadr\"}"),
        frame("{\"reasoning_content\":\"uple mull\"}"),
        frame("{\"reasoning_content\":\" to\"}"),
        frame("{\"reasoning_content\":\" 4.\"}"),
        frame("{\"reasoning_content\":\" Be\"}"),
        frame("{\"reasoning_content\":\" honest:\"}"),
        frame("{\"reasoning_content\":\" no\"}"),
        frame("{\"reasoning_content\":\" real decisions\"}"),
        frame("{\"reasoning_content\":\".\"}"),
    };

    /** The opening role frame captured on every stream (content ""). */
    private static final String ROLE_FRAME =
            frame("{\"role\":\"assistant\",\"content\":\"\",\"refusal\":null}");

    /**
     * The captured TERMINAL chunk shape from the short streams that DID carry
     * {@code stop} (s0002/s0007) — empty delta, no content:
     * {@code ...{"choices":[{...,"finish_reason":"stop"...,"delta":{}}]...}}.
     */
    private static final String STOP_TERMINAL_FRAME =
            "data: {\"id\":\"chatcmpl-zbO5LwPt8gmKxWplSeYsJEQJ7fWQM\",\"object\":\"chat.completion.chunk\","
            + "\"created\":1791186234,\"model\":\"glm-5.3-flash\",\"choices\":[{\"index\":0,"
            + "\"finish_reason\":\"stop\",\"logprobs\":null,\"delta\":{}}],\"usage\":null}";

    /**
     * Builds one captured-style chunk with a null finish_reason and the given delta
     * body — the exact shape s0008 emitted on every frame.
     */
    private static String frame(String deltaBody) {
        return "data: {\"id\":\"chatcmpl-4sYgjGwct7UlFkSmRvDloT25hy1OG\","
                + "\"object\":\"chat.completion.chunk\",\"created\":1791186844,"
                + "\"model\":\"glm-5.3-flash\",\"choices\":[{\"index\":0,"
                + "\"finish_reason\":null,\"logprobs\":null,\"delta\":" + deltaBody + "}],"
                + "\"usage\":null}";
    }

    // ── In-process SSE replay server ──

    private static HttpServer server;
    private static int port;

    @BeforeClass
    public static void startSseServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        // One context; the requested path selects the captured scenario.
        server.createContext("/", exchange -> {
            // Drain the request body so the connection is well-behaved.
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                while (r.readLine() != null) { /* discard */ }
            }
            String body = sseBodyFor(exchange.getRequestURI().getPath());
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            // length 0 => chunked stream, exactly how a real SSE endpoint responds.
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        });
        server.start();
    }

    @AfterClass
    public static void stopSseServer() {
        if (server != null) server.stop(0);
    }

    /** The SSE body for a scenario path — assembled from the captured frames. */
    private static String sseBodyFor(String path) {
        StringBuilder b = new StringBuilder();
        b.append(ROLE_FRAME).append("\n\n");
        switch (path) {
            case "/zen-bleed": {
                // THE live shape: 15 reasoning frames, EVERY chunk finish_reason
                // null, and NO terminal chunk at all (no stop, no [DONE]) — exactly
                // what s0008.raw captured on the streams that bled.
                for (String f : CAPTURED_REASONING_FRAMES) b.append(f).append("\n\n");
                break;
            }
            case "/zen-stop": {
                // A SHORT stream that DID terminate: reasoning then a real stop chunk
                // followed by [DONE] (s0002/s0007 shape).
                for (String f : CAPTURED_REASONING_FRAMES) b.append(f).append("\n\n");
                b.append(STOP_TERMINAL_FRAME).append("\n\n");
                b.append("data: [DONE]\n\n");
                break;
            }
            case "/zen-length": {
                // Classic truncation: non-blank reasoning, empty content,
                // finish_reason=length. Must NEVER be promoted.
                for (String f : CAPTURED_REASONING_FRAMES) b.append(f).append("\n\n");
                b.append("data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                        + "\"model\":\"glm-5.3-flash\",\"choices\":[{\"index\":0,"
                        + "\"finish_reason\":\"length\",\"logprobs\":null,\"delta\":{}}],"
                        + "\"usage\":null}\n\n");
                b.append("data: [DONE]\n\n");
                break;
            }
            case "/zen-empty": {
                // Genuine empty answer: BOTH channels blank, stopped normally.
                b.append(frame("{\"reasoning_content\":\"\"}")).append("\n\n");
                b.append(STOP_TERMINAL_FRAME).append("\n\n");
                b.append("data: [DONE]\n\n");
                break;
            }
            default:
                throw new IllegalStateException("unexpected scenario path: " + path);
        }
        return b.toString();
    }

    // ── Driving the REAL reader + REAL ladder ──

    /**
     * Reads the captured SSE through the REAL production reader
     * ({@code sendStreamingRequest}) — the same parser that captured
     * finish_reason in production — so the fixture exercises the wire shape, not
     * a hand-built response object.
     */
    private static LLMResponse readThroughRealStreaming(TestLLMPlayer player, String scenario)
            throws Exception {
        LLMClient client = player.clientForTest();
        StringBuilder body = new StringBuilder();
        // A minimal but real conversation request body (the mock ignores it).
        Method build = LLMClient.class.getDeclaredMethod(
                "buildRequestBodyConv", boolean.class, String.class, String.class);
        build.setAccessible(true);
        body.append((String) build.invoke(client, true, "mulligan prompt", (String) null));
        Method send = LLMClient.class.getDeclaredMethod("sendStreamingRequest", String.class);
        send.setAccessible(true);
        return (LLMResponse) send.invoke(client, body.toString());
    }

    private static TestLLMPlayer newPlayer(String scenario) {
        TestLLMPlayer player = new TestLLMPlayer("ZenBleedBot", RangeOfInfluence.ONE, 0);
        player.getLLMConfig()
                .apiEndpoint("http://127.0.0.1:" + port + scenario)
                .model("glm-5.3-flash")
                .streamingEnabled(true);
        // The REAL client requires a conversation system prompt before
        // chatConversation (the ladder's call path) — the old stub bypassed this.
        player.clientForTest().resetConversation("You are a test seat.");
        return player;
    }

    /** Reads the finishReason field reflectively so the fixture assert is explicit. */
    private static String finishReasonOf(LLMResponse resp) throws Exception {
        Field f = LLMResponse.class.getField("finishReason");
        return (String) f.get(resp);
    }

    // ── Tests ──

    /**
     * THE regression, on the REAL captured wire shape. The 15 reasoning frames of
     * s0008 (every chunk finish_reason null, no terminal chunk) are replayed
     * through the REAL streaming reader; content is empty and reasoning is
     * non-blank. The FULL ladder must recover the reasoning text as the answer on
     * the FIRST attempt.
     *
     * <p>RED with the pre-fix gate ({@code "stop".equalsIgnoreCase(finishReason)}
     * is false for null → treated as an empty answer → the 5-attempt ladder
     * exhausts and throws "survived the full reasoning-effort descent"); the
     * fixture FAILED for exactly that reason before the fix.
     */
    @Test
    public void capturedNullFinishReasonBleedIsRecoveredOnFirstAttempt() throws Exception {
        TestLLMPlayer player = newPlayer("/zen-bleed");

        // (a) the wire shape itself: the REAL reader must see null finish_reason
        //     and 103 chars of reasoning with empty content.
        LLMResponse wire = readThroughRealStreaming(player, "/zen-bleed");
        Assert.assertNull("captured s0008 chunk stream carries NO terminal finish_reason",
                finishReasonOf(wire));
        Assert.assertEquals("captured reasoning text (verbatim, 103 chars)",
                CAPTURED_REASONING, wire.thinking);
        Assert.assertTrue("captured content channel is empty",
                wire.answer == null || wire.answer.trim().isEmpty());

        // (b) the FULL production ladder must recover it, first attempt, no concede.
        LLMResponse resp = player.chatAndRecordWithRetryTest("mulligan prompt", "summary",
                "mulligan", 1, "T1.M1");
        Assert.assertNotNull(resp);
        Assert.assertFalse("the reasoning-channel decision must be recovered (not empty)",
                resp.answer == null || resp.answer.trim().isEmpty());
        Assert.assertEquals("the recovered answer is the reasoning text, verbatim",
                CAPTURED_REASONING, resp.answer);
    }

    /**
     * The short-stream {@code stop} shape the FIRST fixture used really does occur
     * (s0002/s0007) — it must STILL be recovered, so the fix is a superset of the
     * original (it never regresses the case that already worked).
     */
    @Test
    public void shortStreamWithStopFinishReasonIsStillRecovered() throws Exception {
        TestLLMPlayer player = newPlayer("/zen-stop");

        LLMResponse wire = readThroughRealStreaming(player, "/zen-stop");
        Assert.assertEquals("a short real stream DOES carry stop", "stop", finishReasonOf(wire));

        LLMResponse resp = player.chatAndRecordWithRetryTest("mulligan prompt", "summary",
                "mulligan", 1, "T1.M1");
        Assert.assertEquals(CAPTURED_REASONING, resp.answer);
    }

    /**
     * finish_reason=length is the classic reasoning-loop truncation (reasoning
     * non-blank, content empty). It must NOT be promoted — the ladder runs and
     * Fail-Fast throws, exactly as before the fix. {@code length} is the sole
     * conservative non-promote.
     */
    @Test
    public void lengthTruncationIsNotRecovered() {
        TestLLMPlayer player = newPlayer("/zen-length");
        try {
            player.chatAndRecordWithRetryTest("prompt", "summary", "priority", 1, "T1.M1");
            Assert.fail("expected the empty-answer ladder to exhaust for a length-truncated loop");
        } catch (RuntimeException e) {
            Assert.assertTrue("expected the descent-survived message, got: " + e.getMessage(),
                    e.getMessage().contains("survived the full reasoning-effort descent"));
        }
    }

    /**
     * A GENUINE empty answer (both channels blank) is NOT a bleed — nothing to
     * recover. The ladder still runs and the seat still Fail-Fast concedes, so the
     * recovery never masks a truly dead model.
     */
    @Test
    public void genuineEmptyAnswerStillExhaustsAndThrows() {
        TestLLMPlayer player = newPlayer("/zen-empty");
        try {
            player.chatAndRecordWithRetryTest("prompt", "summary", "priority", 1, "T1.M1");
            Assert.fail("expected the empty-answer ladder to exhaust for a genuine empty answer");
        } catch (RuntimeException e) {
            Assert.assertTrue("expected the descent-survived message, got: " + e.getMessage(),
                    e.getMessage().contains("survived the full reasoning-effort descent"));
        }
    }

    /**
     * The REAL streaming reader must expose the captured reasoning under
     * {@code delta.reasoning_content} (the provider field) — the very text the
     * recovery promotes. Pins that the fixture's reasoning really came off the
     * wire (and would break if the reader stopped reading the field).
     */
    @Test
    public void streamingReaderReadsReasoningContentAndNullFinishReason() throws Exception {
        TestLLMPlayer player = newPlayer("/zen-bleed");
        LLMResponse wire = readThroughRealStreaming(player, "/zen-bleed");
        Assert.assertEquals(103, wire.thinking.length());
        Assert.assertTrue("first line from the wire", wire.thinking.startsWith("Write reflection"));
        Assert.assertNull(finishReasonOf(wire));
    }
}
