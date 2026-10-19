package org.mage.test.LLM;

import com.sun.net.httpserver.HttpServer;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.game.Game;
import mage.player.llm.LLMClient;
import mage.player.llm.LLMResponse;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;
import org.mage.test.player.TestPlayer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Regression harness for the separate glm-5.3-flash @ opencode zen/go
 * defect: "BOTH channels empty → terminal concede", distinct from the
 * reasoning-bleed family pinned by
 * {@link LLMReasoningBleedRecoveryTest}.
 *
 * <p>CHARACTERIZED SHAPE (captured from a live gateway stream): the gateway
 * aborts a stream right after the role chunk, emitting
 * {@code delta={"role":"assistant","content":""}} (finish_reason null) and then a
 * top-level {@code {"error":{"type":"server_error","message":"Streaming response
 * failed: [server_error] upstream service timeout"}}} frame with NO {@code [DONE]}.
 * The reader ({@code LLMClient.sendStreamingRequest}) reads the role delta (no
 * content, no reasoning), ignores the error frame (no {@code choices} → {@code
 * continue}) and returns {@code LLMResponse(thinking="", answer="",
 * usage=null, finishReason=null)}. The reasoning-bleed recovery correctly does NOT
 * fire (nothing in either channel), so the empty-answer ladder exhausts and the
 * seat Fail-Fast concedes.
 *
 * <p>This class drives the PRODUCTION path over the captured bytes: an in-process
 * HTTP server replays verbatim SSE frames, the REAL {@code sendStreamingRequest}
 * reads them, and the REAL retry ladder runs through {@code TestLLMPlayer}.
 *
 * <p>TWO KINDS OF TEST here:
 * <ul>
 *   <li>PINS (green at base): fix the wire shape and the terminal-ladder behavior
 *       so a future change cannot silently alter them. They are the evidence that
 *       the fixture is not a stub — {@link #normalStreamPositiveControl} proves the
 *       reader/sever are wired, so the both-empty reds are for the real reason.</li>
 *   <li>A RED-FIRST probe
 *       ({@link #bothEmptyEscalationDoesNotConcedeTheSeat}) that calls the
 *       production concede path at the seat level. It is RED at base because base
 *       throws the ladder-exhausted RuntimeException out of {@code priority()}
 *       (which kills the game thread / concedes); it is GREEN only after the
 * proposed change (fall back to a legal pass instead of
 *       conceding). This is report-only, do not
 *       "fix" the probe by weakening it.</li>
 * </ul>
 *
 * <p>No network beyond loopback; no live model. Run:
 * {@code mvn test -pl Mage.Tests -Dtest=LLMZenBothChannelsEmptyTerminalTest}
 */
public class LLMZenBothChannelsEmptyTerminalTest {

    // ── Captured wire frames (VERBATIM, from rec/zen-raw-frames/s0003.raw) ──

    /**
     * s0003's single role chunk: content "" and finish_reason null — the opening
     * frame every zen stream emits.
     */
    private static final String CAPTURED_ROLE_FRAME =
            "data: {\"id\":\"chatcmpl-j6v2sr9rygrsOLzFWfidKzrl06EML\",\"object\":\"chat.completion.chunk\","
            + "\"created\":1791186235,\"model\":\"glm-5.3-flash\",\"choices\":[{\"index\":0,"
            + "\"finish_reason\":null,\"logprobs\":null,\"delta\":{\"role\":\"assistant\","
            + "\"content\":\"\",\"refusal\":null}}],\"usage\":null}";

    /**
     * s0003's terminal frame: a top-level {@code error} object, no {@code choices},
     * no finish_reason, no {@code [DONE]}. This is the exact bytes that produce the
     * both-empty LLMResponse.
     */
    private static final String CAPTURED_ERROR_FRAME =
            "data: {\"error\":{\"param\":null,\"type\":\"server_error\","
            + "\"message\":\"Streaming response failed: [server_error] upstream service timeout\"}}";

    /** Builds one chunk with the given delta body and null finish_reason (zen shape). */
    private static String frame(String deltaBody) {
        return "data: {\"id\":\"chatcmpl-bothempty\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1,\"model\":\"glm-5.3-flash\",\"choices\":[{\"index\":0,"
                + "\"finish_reason\":null,\"logprobs\":null,\"delta\":" + deltaBody + "}],"
                + "\"usage\":null}";
    }

    private static final String NORMAL_CONTENT_FRAME =
            frame("{\"content\":\"keep\"}");
    private static final String STOP_FRAME =
            "data: {\"id\":\"chatcmpl-bothempty\",\"object\":\"chat.completion.chunk\",\"created\":1,"
            + "\"model\":\"glm-5.3-flash\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
            + "\"logprobs\":null,\"delta\":{}}],\"usage\":null}";

    /**
     * A usage-only terminal chunk as the real gateway sends when
     * {@code stream_options.include_usage=true}: {@code choices:[]} plus a usage
     * object. Used by the completed-but-empty scenario (usage reports tokens, yet
     * both channels are blank).
     */
    private static final String USAGE_ONLY_FRAME =
            "data: {\"id\":\"chatcmpl-bothempty\",\"object\":\"chat.completion.chunk\",\"created\":1,"
            + "\"model\":\"glm-5.3-flash\",\"choices\":[],\"usage\":{\"prompt_tokens\":10,"
            + "\"completion_tokens\":0,\"total_tokens\":10,\"completion_tokens_details\":"
            + "{\"reasoning_tokens\":0}}}";

    // ── In-process SSE replay server ──

    private static HttpServer server;
    private static int port;

    @BeforeClass
    public static void checkLLMRunEnabled() {
        Assume.assumeFalse("LLM tests skipped (set -Dxmage.llm.skip=false to run)",
                Boolean.getBoolean("xmage.llm.skip"));
    }

    @BeforeClass
    public static void startSseServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                while (r.readLine() != null) { /* drain */ }
            }
            String body = sseBodyFor(exchange.getRequestURI().getPath());
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);  // chunked, like a real SSE endpoint
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

    private static String sseBodyFor(String path) {
        switch (path) {
            case "/zen-both-empty":
                // THE captured both-empty shape: role chunk then the error frame,
                // no [DONE]. s0003.raw, verbatim.
                return CAPTURED_ROLE_FRAME + "\n\n" + CAPTURED_ERROR_FRAME + "\n\n";
            case "/zen-both-empty-closed":
                // Same empty shape with NO error frame at all — only the bare role
                // chunk and then the connection closes (the reader cannot tell the
                // two apart: both yield an empty LLMResponse, usage=null).
                return frame("{\"role\":\"assistant\",\"content\":\"\",\"refusal\":null}") + "\n\n";
            case "/zen-both-empty-blank":
                // The 1/25 action-shape probe variant: a COMPLETELY EMPTY body (no
                // data: line at all) — the gateway aborted before the role frame.
                // The reader returns the same empty LLMResponse(usage=null).
                return "";
            case "/zen-empty-usage":
                // Provider COMPLETED (usage object present) but produced nothing in
                // either channel — the conservative genuine-empty case.
                return frame("{\"role\":\"assistant\",\"content\":\"\",\"refusal\":null}") + "\n\n"
                        + USAGE_ONLY_FRAME + "\n\n"
                        + "data: [DONE]\n\n";
            case "/zen-normal":
                // Positive control: a real content answer, so a both-empty red can
                // never be a fixture/reader error.
                return frame("{\"role\":\"assistant\",\"content\":\"\",\"refusal\":null}") + "\n\n"
                        + NORMAL_CONTENT_FRAME + "\n\n"
                        + STOP_FRAME + "\n\n"
                        + "data: [DONE]\n\n";
            default:
                throw new IllegalStateException("unexpected scenario path: " + path);
        }
    }

    // ── Driving the REAL reader + REAL ladder ──

    private static LLMResponse readThroughRealStreaming(TestLLMPlayer player) throws Exception {
        LLMClient client = player.clientForTest();
        Method build = LLMClient.class.getDeclaredMethod(
                "buildRequestBodyConv", boolean.class, String.class, String.class);
        build.setAccessible(true);
        String body = (String) build.invoke(client, true, "priority prompt", (String) null);
        Method send = LLMClient.class.getDeclaredMethod("sendStreamingRequest", String.class);
        send.setAccessible(true);
        return (LLMResponse) send.invoke(client, body);
    }

    private static TestLLMPlayer newPlayer(String scenario) {
        TestLLMPlayer player = new TestLLMPlayer("ZenBothemptyBot", RangeOfInfluence.ONE, 0);
        player.getLLMConfig()
                .apiEndpoint("http://127.0.0.1:" + port + scenario)
                .model("glm-5.3-flash")
                .streamingEnabled(true);
        player.clientForTest().resetConversation("You are a test seat.");
        return player;
    }

    private static String finishReasonOf(LLMResponse resp) throws Exception {
        Field f = LLMResponse.class.getField("finishReason");
        return (String) f.get(resp);
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ── Wire-shape pins ──

    /**
     * POSITIVE CONTROL — proves the SSE server + the REAL reader are wired: a normal
     * content stream yields the answer. If this ever fails, every "both empty"
     * assertion below is suspect (fixture error), so it guards the discriminating
     * reds.
     */
    @Test
    public void normalStreamPositiveControl() throws Exception {
        TestLLMPlayer player = newPlayer("/zen-normal");
        LLMResponse wire = readThroughRealStreaming(player);
        Assert.assertEquals("the reader must read a normal content stream", "keep", wire.answer);
        Assert.assertEquals("stop", finishReasonOf(wire));
    }

    /**
     * PINS the captured both-empty wire shape: the REAL reader returns BOTH channels
     * blank, finish_reason null, and usage null (the gateway aborted before any usage
     * frame). This is the discriminator that makes the seat report
     * "(no reasoning reported either)".
     */
    @Test
    public void capturedServerErrorShapeIsBothChannelsEmpty() throws Exception {
        TestLLMPlayer player = newPlayer("/zen-both-empty");
        LLMResponse wire = readThroughRealStreaming(player);
        Assert.assertTrue("captured shape: content channel is empty", blank(wire.answer));
        Assert.assertTrue("captured shape: reasoning channel is empty (nothing to promote)",
                blank(wire.thinking));
        Assert.assertNull("captured shape carries no finish_reason", finishReasonOf(wire));
        Assert.assertNull("captured shape carries no usage object", wire.usage);
    }

    /**
     * PINS the terminal path: the both-empty captured shape exhausts the FULL
     * reasoning-effort descent and throws the ladder-exhausted exception — the
     * message `thrownAfterFullLadder` reads as terminal, which is what triggers the
     * concede guard in-game. Asserts the "no reasoning reported either" discriminator
     * so a regression that starts reporting reasoning here is caught.
     */
    @Test
    public void capturedBothEmptyShapeExhaustsLadderAndThrows() {
        TestLLMPlayer player = newPlayer("/zen-both-empty");
        try {
            player.chatAndRecordWithRetryTest("priority prompt", "summary", "priority", 1, "T1.M1");
            Assert.fail("the both-empty shape must exhaust the ladder and throw, not return");
        } catch (RuntimeException e) {
            Assert.assertTrue("ladder-exhausted signature (the concede guard's key): "
                            + e.getMessage(),
                    e.getMessage().contains("survived the full reasoning-effort descent"));
            Assert.assertTrue("must report BOTH channels empty, got: " + e.getMessage(),
                    e.getMessage().contains("no reasoning reported either"));
        }
    }

    /**
     * PINS the bare-close both-empty shape (role chunk only, no [DONE]) and the
     * COMPLETELY-EMPTY body variant (1/25 action-shape probe) — both exhaust the
     * ladder identically to the captured error-frame shape.
     */
    @Test
    public void closedStreamBothEmptyAlsoExhaustsLadder() {
        for (String scenario : new String[]{"/zen-both-empty-closed", "/zen-both-empty-blank"}) {
            TestLLMPlayer player = newPlayer(scenario);
            try {
                player.chatAndRecordWithRetryTest("priority prompt", "summary", "priority", 1, "T1.M1");
                Assert.fail(scenario + ": the both-empty shape must exhaust the ladder and throw");
            } catch (RuntimeException e) {
                Assert.assertTrue(scenario + ": ladder-exhausted signature, got: " + e.getMessage(),
                        e.getMessage().contains("survived the full reasoning-effort descent"));
            }
        }
    }

    /**
     * PINS the conservative completed-but-empty case: the provider returns a usage
     * object but NO content and NO reasoning. This is a GENUINE empty answer and must
     * keep exhausting the ladder (the recovery must never fabricate an answer). It is
     * deliberately NOT treated as the both-empty infrastructure failure.
     */
    @Test
    public void completedButEmptyStillExhaustsLadder() {
        TestLLMPlayer player = newPlayer("/zen-empty-usage");
        try {
            LLMResponse resp = player.chatAndRecordWithRetryTest(
                    "priority prompt", "summary", "priority", 1, "T1.M1");
            Assert.fail("a completed empty answer must exhaust the ladder, got: " + resp.answer);
        } catch (RuntimeException e) {
            Assert.assertTrue("ladder-exhausted signature, got: " + e.getMessage(),
                    e.getMessage().contains("survived the full reasoning-effort descent"));
        }
    }

    // ── The seat-level RED-FIRST probe lives in LLMZenBothChannelsEmptyConcedeTest ──
    // (a separate top-level class so surefire picks it up; that probe covers
    // the semantic change it demands).
}
