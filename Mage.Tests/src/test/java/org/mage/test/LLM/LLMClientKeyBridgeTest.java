package org.mage.test.LLM;

import mage.interfaces.LLMBridgeRegistry;
import mage.interfaces.LLMPromptPayload;
import mage.players.LLMPlayerConfig;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client-key bridge serialization + registry tests

 *
 * 1. LLMPlayerConfig.useClientKey + clientKeyTag must survive client→server
 *    RPC serialization (same contract as apiKey — non-transient on the
 *    transport class).
 * 2. LLMPromptPayload round-trips with all routing fields intact.
 * 3. LLMBridgeRegistry request/complete/fail lifecycle: a blocking request
 *    completes when the browser's decision arrives; a failure propagates the
 *    provider message; duplicate requestIds are rejected.
 */
public class LLMClientKeyBridgeTest {

    @After
    public void clearTransport() {
        LLMBridgeRegistry.clearTransport();
    }

    private static byte[] serialize(Object o) throws Exception {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(o);
            return bos.toByteArray();
        }
    }

    private static Object deserialize(byte[] bytes) throws Exception {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return ois.readObject();
        }
    }

    @Test
    public void useClientKeyAndTagSurviveRpcTransport() throws Exception {
        LLMPlayerConfig c = new LLMPlayerConfig();
        c.setUseClientKey(true);
        c.setClientKeyTag("k-abc123");
        LLMPlayerConfig copy = (LLMPlayerConfig) deserialize(serialize(c));
        Assert.assertEquals("useClientKey must survive client→server serialization (RPC transport)",
                Boolean.TRUE, copy.getUseClientKey());
        Assert.assertEquals("clientKeyTag must survive client→server serialization (RPC transport)",
                "k-abc123", copy.getClientKeyTag());
    }

    @Test
    public void payloadRoundTripPreservesRoutingFields() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        LLMPromptPayload p = new LLMPromptPayload(requestId, seatId, gameId,
                "glm-5.3-flash-Kestrel", "{\"model\":\"x\",\"messages\":[]}",
                "https://openrouter.ai/api/v1/chat/completions",
                false, 600, "k-abc123");
        LLMPromptPayload copy = (LLMPromptPayload) deserialize(serialize(p));
        Assert.assertEquals(requestId, copy.requestId);
        Assert.assertEquals(seatId, copy.seatId);
        Assert.assertEquals(gameId, copy.gameId);
        Assert.assertEquals("glm-5.3-flash-Kestrel", copy.playerName);
        Assert.assertEquals("{\"model\":\"x\",\"messages\":[]}", copy.requestBody);
        Assert.assertEquals("https://openrouter.ai/api/v1/chat/completions", copy.apiEndpoint);
        Assert.assertFalse(copy.stream);
        Assert.assertEquals(600, copy.timeoutSeconds);
        Assert.assertEquals("k-abc123", copy.clientKeyTag);
    }

    @Test
    public void registryRequestCompletesOnDecision() throws Exception {
        UUID requestId = UUID.randomUUID();
        LLMPromptPayload payload = new LLMPromptPayload(requestId, UUID.randomUUID(), UUID.randomUUID(),
                "bot", "{}", "https://x.example/v1/chat/completions", false, 10, "k-1");

        // Transport that records the payload and completes it asynchronously.
        AtomicReference<LLMPromptPayload> delivered = new AtomicReference<>();
        LLMBridgeRegistry.setTransport(pl -> {
            delivered.set(pl);
            return true;
        });

        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<LLMPromptPayload.Decision> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                started.countDown();
                result.set(LLMBridgeRegistry.request(payload));
            } catch (Throwable t) {
                error.set(t);
            }
        });
        caller.start();
        Assert.assertTrue(started.await(2, TimeUnit.SECONDS));

        // Wait until the request is registered, then complete it.
        long deadline = System.currentTimeMillis() + 2000;
        while (delivered.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        Assert.assertNotNull("transport never saw the payload", delivered.get());
        LLMBridgeRegistry.complete(new LLMPromptPayload.Decision(requestId, "{\"ok\":true}", null, 200));
        caller.join(2000);

        Assert.assertNull("request should not have thrown", error.get());
        Assert.assertNotNull(result.get());
        Assert.assertEquals("{\"ok\":true}", result.get().rawResponse);
        Assert.assertEquals(0, LLMBridgeRegistry.pendingCount());
    }

    @Test
    public void registryFailurePropagatesMessage() throws Exception {
        UUID requestId = UUID.randomUUID();
        LLMPromptPayload payload = new LLMPromptPayload(requestId, UUID.randomUUID(), UUID.randomUUID(),
                "bot", "{}", "https://x.example/v1/chat/completions", false, 5, "k-1");
        LLMBridgeRegistry.setTransport(pl -> true);

        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                LLMBridgeRegistry.request(payload);
            } catch (Throwable t) {
                error.set(t);
            }
        });
        caller.start();
        long deadline = System.currentTimeMillis() + 2000;
        while (LLMBridgeRegistry.pendingCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        LLMBridgeRegistry.fail(new LLMPromptPayload.Failure(requestId, "HTTP 429: Rate limited", true, 429));
        caller.join(2000);

        Assert.assertNotNull("request should have thrown", error.get());
        // ExecutionException is unwrapped to the BrowserCallException message
        Assert.assertTrue("error message should carry the provider text: " + error.get(),
                error.get().getMessage().contains("429"));
    }

    @Test
    public void registryRejectsDuplicateRequestIds() throws Exception {
        UUID requestId = UUID.randomUUID();
        LLMPromptPayload payload = new LLMPromptPayload(requestId, UUID.randomUUID(), UUID.randomUUID(),
                "bot", "{}", "https://x.example/v1/chat/completions", false, 5, "k-1");
        LLMBridgeRegistry.setTransport(pl -> true);

        AtomicReference<Throwable> firstError = new AtomicReference<>();
        Thread blocker = new Thread(() -> {
            try {
                LLMBridgeRegistry.request(payload);  // blocks 5s
            } catch (Throwable t) {
                firstError.set(t);
            }
        });
        blocker.start();
        long deadline = System.currentTimeMillis() + 2000;
        while (LLMBridgeRegistry.pendingCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        try {
            LLMBridgeRegistry.request(payload);
            Assert.fail("duplicate requestId must be rejected");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("Duplicate"));
        } finally {
            LLMBridgeRegistry.fail(new LLMPromptPayload.Failure(requestId, "stop", false, 0));
            blocker.join(2000);
        }
    }

    @Test
    public void missingTransportFailsFast() throws Exception {
        LLMPromptPayload payload = new LLMPromptPayload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "bot", "{}", "https://x.example/v1/chat/completions", false, 5, "k-1");
        try {
            LLMBridgeRegistry.request(payload);
            Assert.fail("request without a registered transport must fail fast");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("transport not registered"));
        }
    }
}