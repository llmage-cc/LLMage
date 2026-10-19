package mage.webproxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for the deployment toggle that hides the connect form's
 * Server/Port fields:
 *
 *  - -Dwebproxy.hideServerTarget=true is a strict boolean (fail fast on any
 *    other value — no silent "false").
 *  - hideServerTarget=true requires -Dwebproxy.pinnedXmageTarget=host:port
 *    (WebProxyServer.validatePublicHostingConfig refuses a contradictory boot).
 *  - resolveConnectTarget(): with a pin, server/port in the connect message are
 *    OPTIONAL and ignored; without a pin they stay required (the local/LAN
 *    behavior the toggle must not change).
 *  - GET /config serves exactly {"hideServerTarget":<bool>}.
 *
 * resolveConnectTarget/hideServerTarget/proxyConfigJson are package-private
 * statics; these tests live in the same package to reach them.
 */
public class WebProxyConnectTargetTest {

    @After
    public void clearProps() {
        System.clearProperty("webproxy.hideServerTarget");
        System.clearProperty("webproxy.pinnedXmageTarget");
    }

    private static JsonObject connectMessage(String server, Integer port) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "connect");
        json.addProperty("username", "CityOfBronze");
        json.addProperty("password", "");
        if (server != null) {
            json.addProperty("server", server);
        }
        if (port != null) {
            json.addProperty("port", port);
        }
        return json;
    }

    private static JsonObject rawConnectMessage(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    // ── Pinned target: fields optional + ignored ──

    @Test
    public void pinnedTargetMakesClientFieldsOptional() {
        WebProxyServer.ConnectTarget target = WebProxyServer.resolveConnectTarget(
                connectMessage(null, null), "xmage-server:17171");
        Assert.assertNull(target.error);
        Assert.assertTrue(target.pinned);
        Assert.assertEquals("xmage-server", target.host);
        Assert.assertEquals(17171, target.port);
    }

    @Test
    public void pinnedTargetIgnoresClientSuppliedValues() {
        WebProxyServer.ConnectTarget target = WebProxyServer.resolveConnectTarget(
                connectMessage("evil.example.com", 9999), "xmage-server:17171");
        Assert.assertNull(target.error);
        Assert.assertTrue(target.pinned);
        Assert.assertEquals("xmage-server", target.host);
        Assert.assertEquals(17171, target.port);
    }

    @Test
    public void malformedPinnedTargetIsAnError() {
        for (String bad : new String[] {"no-colon", "host:", ":17171", "host:notaport"}) {
            WebProxyServer.ConnectTarget target =
                    WebProxyServer.resolveConnectTarget(connectMessage(null, null), bad);
            Assert.assertNotNull("expected an error for pinned target '" + bad + "'", target.error);
            Assert.assertTrue(target.error.contains("malformed"));
        }
    }

    @Test
    public void blankPinnedPropertyIsNotAPin() {
        // Whitespace-only property must behave exactly like "unset" — otherwise a
        // compose default of " " would silently disable the unpinned path.
        WebProxyServer.ConnectTarget target = WebProxyServer.resolveConnectTarget(
                connectMessage(null, null), "   ");
        Assert.assertNotNull(target.error);
        Assert.assertFalse(target.pinned);
        Assert.assertEquals("Missing server", target.error);
    }

    // ── Unpinned: unchanged local/LAN behavior ──

    @Test
    public void unpinnedUsesClientSuppliedValues() {
        WebProxyServer.ConnectTarget target = WebProxyServer.resolveConnectTarget(
                connectMessage("127.0.0.1", 17171), "");
        Assert.assertNull(target.error);
        Assert.assertFalse(target.pinned);
        Assert.assertEquals("127.0.0.1", target.host);
        Assert.assertEquals(17171, target.port);
    }

    @Test
    public void unpinnedRequiresServerAndPort() {
        Assert.assertEquals("Missing server",
                WebProxyServer.resolveConnectTarget(connectMessage(null, 17171), "").error);
        Assert.assertEquals("Missing port",
                WebProxyServer.resolveConnectTarget(connectMessage("127.0.0.1", null), "").error);
    }

    @Test
    public void unpinnedInvalidPortIsAnError() {
        JsonObject json = rawConnectMessage(
                "{\"type\":\"connect\",\"server\":\"127.0.0.1\",\"port\":\"abc\"}");
        WebProxyServer.ConnectTarget target = WebProxyServer.resolveConnectTarget(json, "");
        Assert.assertNotNull(target.error);
        Assert.assertTrue(target.error.contains("Invalid port"));
    }

    /**
     * The browser sends the port as a JSON number (ConnectOverlay: parseInt).
     * resolveConnectTarget must keep accepting that shape — it is the live wire
     * format of every existing deployment.
     */
    @Test
    public void numericJsonPortIsAccepted() {
        JsonObject json = rawConnectMessage("{\"type\":\"connect\",\"server\":\"127.0.0.1\",\"port\":17171}");
        WebProxyServer.ConnectTarget target = WebProxyServer.resolveConnectTarget(json, "");
        Assert.assertNull(target.error);
        Assert.assertEquals(17171, target.port);
    }

    // ── The toggle itself ──

    @Test
    public void hideServerTargetDefaultsToFalse() {
        Assert.assertFalse(WebProxyServer.hideServerTarget());
        System.setProperty("webproxy.hideServerTarget", "false");
        Assert.assertFalse(WebProxyServer.hideServerTarget());
        System.setProperty("webproxy.hideServerTarget", "true");
        Assert.assertTrue(WebProxyServer.hideServerTarget());
    }

    @Test
    public void hideServerTargetRejectsNonBooleanValues() {
        System.setProperty("webproxy.hideServerTarget", "yes");
        try {
            WebProxyServer.hideServerTarget();
            Assert.fail("expected IllegalStateException for a non-boolean value");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("hideServerTarget"));
        }
    }

    @Test
    public void hideServerTargetRequiresAPinnedTarget() {
        System.setProperty("webproxy.hideServerTarget", "true");
        try {
            WebProxyServer.validatePublicHostingConfig();
            Assert.fail("expected IllegalStateException without a pinned target");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("pinnedXmageTarget"));
        }
        System.setProperty("webproxy.pinnedXmageTarget", "xmage-server:17171");
        WebProxyServer.validatePublicHostingConfig(); // must not throw
    }

    @Test
    public void validatePassesForLocalRunsWithoutPin() {
        WebProxyServer.validatePublicHostingConfig(); // hide=false, no pin
        System.setProperty("webproxy.pinnedXmageTarget", "xmage-server:17171");
        WebProxyServer.validatePublicHostingConfig(); // pin alone is fine too
    }

    // ── GET /config body ──

    @Test
    public void configJsonReportsTheToggle() {
        // /config also carries the artifact flavor
        // (llmage) so the frontend knows which bundle flavor the proxy serves.
        // The minimal test build resolves LLMageBuild to its default (true).
        Assert.assertEquals("{\"hideServerTarget\":false,\"llmage\":true}",
                WebProxyServer.proxyConfigJson());
        System.setProperty("webproxy.hideServerTarget", "true");
        Assert.assertEquals("{\"hideServerTarget\":true,\"llmage\":true}",
                WebProxyServer.proxyConfigJson());
        System.clearProperty("webproxy.hideServerTarget");
    }
}
