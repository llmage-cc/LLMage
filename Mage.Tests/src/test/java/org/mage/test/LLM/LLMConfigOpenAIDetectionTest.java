package org.mage.test.LLM;

import com.sun.net.httpserver.HttpServer;
import mage.player.llm.LLMConfig;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Unit tests for fetchModelContextWindow() on OpenAI-compatible (non-Ollama)
 * endpoints — LLMConfig.java.
 *
 * Contract:
 *  - /v1/models exposes context_length on the matched model id: detected and
 *    stored, overriding the default.
 *  - /v1/models response without context_length on the matched model:
 *    Fail Fast — RuntimeException telling the user to set
 *    modelContextWindow manually.
 *  - API key (when set) is sent as a Bearer header on the /v1/models request.
 *
 * These tests run a local HTTP server so no external network access or
 * real cloud credentials are needed.
 */
public class LLMConfigOpenAIDetectionTest {

    private static HttpServer server;
    private static int port;

    /** Whether the last /v1/models request carried an Authorization header. */
    private static volatile String lastAuthHeader;

    @BeforeClass
    public static void startStubServer() throws Exception {
        // Port 0 = ephemeral — no fixed-port collisions with parallel test runs
        // or leftover stubs from an aborted run
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            lastAuthHeader = exchange.getRequestHeaders().getFirst("Authorization");
            String path = exchange.getRequestURI().getPath();
            String body;
            if (path.contains("/noctx")) {
                // OpenAI-style: model entries without context_length
                body = "{\"data\":[{\"id\":\"test-model\",\"object\":\"model\"}]}";
            } else if (path.contains("/wrongmodel")) {
                // context_length present, but on a different model id
                body = "{\"data\":[{\"id\":\"other-model\",\"context_length\":8192}]}";
            } else if (path.contains("/ctxwindow")) {
                // hyper.charm.land-style: field named "context_window" instead of "context_length"
                body = "{\"data\":[{\"id\":\"test-model\",\"context_window\":1000000}]}";
            } else {
                // OpenRouter/Groq-style: matched model id with context_length
                body = "{\"data\":[{\"id\":\"other-model\",\"context_length\":8192},"
                        + "{\"id\":\"test-model\",\"context_length\":1048576}]}";
            }
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

    @Test
    public void openAiModelsContextLengthIsDetected() {
        LLMConfig config = new LLMConfig()
                .apiEndpoint("http://localhost:" + port + "/v1/chat/completions")
                .model("test-model");
        config.fetchModelContextWindow();
        Assert.assertEquals("context_length from /v1/models must be detected",
                1048576, config.getModelContextWindow());
    }

    @Test
    public void apiKeyIsSentAsBearerHeader() {
        lastAuthHeader = null;
        LLMConfig config = new LLMConfig()
                .apiEndpoint("http://localhost:" + port + "/v1/chat/completions")
                .model("test-model")
                .apiKey("test-key-123");
        config.fetchModelContextWindow();
        Assert.assertEquals("Bearer header must be sent on /v1/models",
                "Bearer test-key-123", lastAuthHeader);
    }

    @Test
    public void noKeyMeansNoAuthHeader() {
        lastAuthHeader = null;
        LLMConfig config = new LLMConfig()
                .apiEndpoint("http://localhost:" + port + "/v1/chat/completions")
                .model("test-model");
        config.fetchModelContextWindow();
        Assert.assertNull("no API key configured — no Authorization header expected",
                lastAuthHeader);
    }

    @Test
    public void missingContextLengthFailsFast() {
        // Endpoint whose /v1/models omits context_length (like OpenAI's own API)
        LLMConfig config = new LLMConfig()
                .apiEndpoint("http://localhost:" + port + "/noctx/v1/chat/completions")
                .model("test-model");
        try {
            config.fetchModelContextWindow();
            Assert.fail("expected RuntimeException when provider omits context_length");
        } catch (RuntimeException e) {
            Assert.assertTrue("message should tell the user how to fix it: " + e.getMessage(),
                    e.getMessage().contains("xmage.llm.model_context_window"));
        }
    }

    @Test
    public void explicitWindowSurvivesFailedDetection() {
        // Explicit window + provider that omits context_length → honored, no throw
        LLMConfig config = new LLMConfig()
                .apiEndpoint("http://localhost:" + port + "/noctx/v1/chat/completions")
                .model("test-model")
                .modelContextWindow(200000);
        config.fetchModelContextWindow();
        Assert.assertEquals("explicit window must be retained on failed detection",
                200000, config.getModelContextWindow());
    }

    @Test
    public void wrongModelIdInModelsListFailsFast() {
        // context_length present but only for a different model id
        LLMConfig config = new LLMConfig()
                .apiEndpoint("http://localhost:" + port + "/wrongmodel/v1/chat/completions")
                .model("test-model");
        try {
            config.fetchModelContextWindow();
            Assert.fail("expected RuntimeException when matched model has no context_length");
        } catch (RuntimeException e) {
            Assert.assertTrue(e.getMessage().contains("xmage.llm.model_context_window"));
        }
    }

    @Test
    public void contextWindowFieldNameIsAlsoAccepted() {
        // Providers like hyper.charm.land name the field "context_window"
        LLMConfig config = new LLMConfig()
                .apiEndpoint("http://localhost:" + port + "/ctxwindow/v1/chat/completions")
                .model("test-model");
        config.fetchModelContextWindow();
        Assert.assertEquals("context_window field must be read as the context size",
                1000000, config.getModelContextWindow());
    }
}