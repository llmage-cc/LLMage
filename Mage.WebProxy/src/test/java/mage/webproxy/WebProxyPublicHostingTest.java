package mage.webproxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for the public-hosting security additions to the web proxy:
 *
 *  - WebProxyServer.isAllowedOrigin honors -Dwebproxy.allowedOrigins
 *    (exact normalized match, no wildcards; localhost default unaffected).
 *  - WebProxySession.parseLLMConfig applies the model-endpoint host allowlist
 *    to per-seat apiEndpoint overrides (SSRF + key-redirect guard at the
 *    browser→proxy boundary).
 *  - The parse rejects non-http(s) schemes and cloud metadata hosts.
 *
 * parseLLMConfig is package-private static; these tests live in the same
 * package to reach it.
 */
public class WebProxyPublicHostingTest {

    private static final String DEFAULT_ALLOWED_HOSTS = "localhost,127.0.0.1,::1";

    @After
    public void clearProps() {
        System.clearProperty("webproxy.allowedOrigins");
        System.clearProperty("webproxy.allowedModelHosts");
    }

    // ── Origin allowlist ──

    @Test
    public void configuredOriginIsAllowed() {
        System.setProperty("webproxy.allowedOrigins", "https://mage.example.com,https://other.example.org");
        Assert.assertTrue(WebProxyServer.isAllowedOrigin("https://mage.example.com"));
        Assert.assertTrue(WebProxyServer.isAllowedOrigin("https://other.example.org"));
        // trailing slash normalized
        Assert.assertTrue(WebProxyServer.isAllowedOrigin("https://mage.example.com/"));
    }

    @Test
    public void unlistedPublicOriginIsRejected() {
        System.setProperty("webproxy.allowedOrigins", "https://mage.example.com");
        Assert.assertFalse(WebProxyServer.isAllowedOrigin("https://evil.example.com"));
        // no wildcard / suffix match
        Assert.assertFalse(WebProxyServer.isAllowedOrigin("https://mage.example.com.evil.com"));
        Assert.assertFalse(WebProxyServer.isAllowedOrigin("http://mage.example.com"));
    }

    @Test
    public void localhostOriginsStillAllowedWithoutProperty() {
        Assert.assertTrue(WebProxyServer.isAllowedOrigin("http://localhost:8090"));
        Assert.assertTrue(WebProxyServer.isAllowedOrigin("https://127.0.0.1"));
        Assert.assertFalse(WebProxyServer.isAllowedOrigin("https://evil.example.com"));
    }

    // ── Seat-endpoint SSRF guard (parseLLMConfig) ──

    private static JsonObject seatConfigWithEndpoint(String endpoint) {
        JsonObject lcfg = new JsonObject();
        lcfg.addProperty("model", "deepseek-v4.1-flash");
        lcfg.addProperty("apiEndpoint", endpoint);
        return lcfg;
    }

    @Test
    public void seatEndpointOnAllowlistedHostPasses() {
        System.setProperty("webproxy.allowedModelHosts", "hyper.charm.land,localhost");
        mage.players.LLMPlayerConfig c = WebProxySession.parseLLMConfig(
                seatConfigWithEndpoint("https://hyper.charm.land/v1/chat/completions"));
        Assert.assertEquals("https://hyper.charm.land/v1/chat/completions", c.getApiEndpoint());
        Assert.assertEquals("deepseek-v4.1-flash", c.getModel());
    }

    @Test
    public void seatEndpointOnUnlistedHostIsRejected() {
        System.setProperty("webproxy.allowedModelHosts", DEFAULT_ALLOWED_HOSTS);
        JsonObject lcfg = seatConfigWithEndpoint("https://attacker.example.com/v1/chat/completions");
        try {
            WebProxySession.parseLLMConfig(lcfg);
            Assert.fail("expected IllegalArgumentException for unallowed host");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("allowlist"));
        }
    }

    @Test
    public void seatEndpointMetadataHostIsRejected() {
        System.setProperty("webproxy.allowedModelHosts", "169.254.169.254,hyper.charm.land");
        // Even if somehow allowlisted by property, the metadata block fires first
        JsonObject lcfg = seatConfigWithEndpoint("http://169.254.169.254/v1/chat/completions");
        try {
            WebProxySession.parseLLMConfig(lcfg);
            Assert.fail("expected IllegalArgumentException for metadata host");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("metadata"));
        }
    }

    @Test
    public void seatEndpointNonHttpSchemeIsRejected() {
        System.setProperty("webproxy.allowedModelHosts", "evil.example.com");
        JsonObject lcfg = seatConfigWithEndpoint("ftp://evil.example.com/v1/chat/completions");
        try {
            WebProxySession.parseLLMConfig(lcfg);
            Assert.fail("expected IllegalArgumentException for non-http scheme");
        } catch (IllegalArgumentException expected) {
            // message comes from either the scheme check or URL parsing
            Assert.assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void malformedSeatEndpointIsRejected() {
        System.setProperty("webproxy.allowedModelHosts", DEFAULT_ALLOWED_HOSTS);
        JsonObject lcfg = seatConfigWithEndpoint("not a url");
        try {
            WebProxySession.parseLLMConfig(lcfg);
            Assert.fail("expected IllegalArgumentException for malformed URL");
        } catch (IllegalArgumentException expected) {
            Assert.assertNotNull(expected.getMessage());
        }
    }

    // ── apiEndpoint field absent → no endpoint override (existing behavior) ──

    @Test
    public void absentEndpointLeavesOverrideNull() {
        System.setProperty("webproxy.allowedModelHosts", DEFAULT_ALLOWED_HOSTS);
        JsonObject lcfg = new JsonObject();
        lcfg.addProperty("model", "deepseek-v4.1-flash");
        mage.players.LLMPlayerConfig c = WebProxySession.parseLLMConfig(lcfg);
        Assert.assertNull(c.getApiEndpoint());
    }

    // ── parseLLMConfig must not be reachable with a null/absent object ──

    @Test
    public void jsonParseSanity() {
        // The handler only calls parseLLMConfig when pc.has("llmConfig"); this pins
        // the Gson contract used by the call sites.
        JsonObject parsed = JsonParser.parseString(
                "{\"type\":\"COMPUTER_LLM\",\"llmConfig\":{\"model\":\"m\",\"apiEndpoint\":\"https://hyper.charm.land/v1/chat/completions\"}}")
                .getAsJsonObject();
        Assert.assertTrue(parsed.has("llmConfig"));
        Assert.assertTrue(parsed.get("llmConfig").isJsonObject());
    }
}