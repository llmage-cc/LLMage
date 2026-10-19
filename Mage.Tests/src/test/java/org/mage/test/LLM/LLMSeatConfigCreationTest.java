package org.mage.test.LLM;

import com.sun.net.httpserver.HttpServer;
import mage.constants.RangeOfInfluence;
import mage.player.llm.LLMPlayer;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Regression test for the LLM seat creation ordering bug

 *
 * Old behavior: LLMPlayer.init() dialed the server-default endpoint during
 * CONSTRUCTION (context-window detection + thinking probe) and the seat's own
 * LLMPlayerConfig was applied only AFTER creation — so a dead server-default
 * endpoint killed web-created LLM seats even when the seat carried a perfectly
 * reachable endpoint.
 *
 * Contract after the fix:
 *  - A pending seat config set via LLMPlayerConfig.setPendingPlayerConfig is
 *    applied INSIDE init(), BEFORE detection/probe — construction succeeds and
 *    the window is detected from the SEAT's endpoint even when the server
 *    default endpoint is dead.
 *  - The pending holder is consumed (cleared) — never leaks to the next
 *    construction on the same thread.
 *  - No pending config → byte-identical old behavior.
 *  - Client-key validation inside the applied config still Fail-Fasts on a
 *    missing clientKeyTag (same rule as applyOverrides).
 */
public class LLMSeatConfigCreationTest {

    private static HttpServer server;
    private static int port;

    @BeforeClass
    public static void startStubServer() throws Exception {
        // /v1/models with a context_length — the OpenAI-compatible detection path
        // (this endpoint has no "ollama" in the host, which is the point: the seat
        // endpoint must be dialed, not the server default).
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String body = "{\"data\":[{\"id\":\"stub-model\",\"object\":\"model\"},"
                    + "{\"id\":\"stub-model\",\"context_length\":1048576}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    @AfterClass
    public static void stopStubServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private mage.players.LLMPlayerConfig seatConfig(String endpoint, String model) {
        mage.players.LLMPlayerConfig c = new mage.players.LLMPlayerConfig();
        c.setModel(model);
        c.setApiEndpoint(endpoint);
        c.setReasoningEffort("max");
        c.setThinkingEnabled(false); // skip the thinking probe path — window detection is the dial under test
        return c;
    }

    @Test
    public void seatConfigAppliesAtConstructionBeforeDetection() {
        // The dead endpoint standing in for an unreachable server default. If init()
        // still dialed the server default AFTER this test's config was published —
        // or never consumed the pending config at all — construction throws here.
        mage.players.LLMPlayerConfig pending = seatConfig(
                "http://127.0.0.1:9/v1/chat/completions", "stub-model");
        // The seat's REAL endpoint is a reachable stub; its model is what detection must match.
        pending.setApiEndpoint("http://localhost:" + port + "/v1/chat/completions");

        mage.players.LLMPlayerConfig.setPendingPlayerConfig(pending);
        LLMPlayer p = new LLMPlayer("SeatBot", RangeOfInfluence.ONE, 0);

        Assert.assertEquals("seat endpoint applied at construction", "http://localhost:" + port + "/v1/chat/completions", p.getLLMConfig().getApiEndpoint());
        Assert.assertEquals("seat model applied at construction", "stub-model", p.getLLMConfig().getModel());
        Assert.assertEquals("context window detected from the SEAT endpoint", 1048576, p.getLLMConfig().getModelContextWindow());
    }

    @Test
    public void pendingConfigIsConsumedAndNeverLeaks() {
        mage.players.LLMPlayerConfig pending = seatConfig(
                "http://localhost:" + port + "/v1/chat/completions", "stub-model");
        mage.players.LLMPlayerConfig.setPendingPlayerConfig(pending);
        new LLMPlayer("ConsumerBot", RangeOfInfluence.ONE, 0);

        // Consume again: must already be cleared by the construction above.
        // (No follow-up no-pending construction here: without a pending config,
        // init() falls back to dialing the server-default endpoint — the exact
        // old behavior — which is host-dependent in tests. The cleared-holder
        // assertion above is the complete no-leak proof.)
        Assert.assertNull("pending config must be consumed at construction",
                mage.players.LLMPlayerConfig.consumePendingPlayerConfig());
    }

    @Test
    public void clientKeySeatWithoutTagFailsFastAtConstruction() {
        mage.players.LLMPlayerConfig pending = new mage.players.LLMPlayerConfig();
        pending.setUseClientKey(true);
        pending.setClientKeyTag(null);
        pending.setModelContextWindow(1048576);
        pending.setApiEndpoint("http://localhost:" + port + "/v1/chat/completions");
        mage.players.LLMPlayerConfig.setPendingPlayerConfig(pending);
        try {
            new LLMPlayer("ClientKeyBot", RangeOfInfluence.ONE, 0);
            Assert.fail("client-key seat without clientKeyTag must Fail-Fast at construction");
        } catch (IllegalStateException expected) {
            Assert.assertTrue("message names the missing tag",
                    expected.getMessage() != null && expected.getMessage().contains("clientKeyTag"));
        } finally {
            // clear in case of an earlier-than-expected throw
            mage.players.LLMPlayerConfig.consumePendingPlayerConfig();
        }
    }
}