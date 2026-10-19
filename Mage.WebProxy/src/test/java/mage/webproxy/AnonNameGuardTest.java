package mage.webproxy;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.UUID;

/**
 * Anon same-name collision guard

 *
 * Two browser clients connected under the same anon name through a hosted
 * proxy silently kill each other server-side (ANON_IDENTIFY_BY_HOST=true; the
 * proxy's host is shared by every webui user) and then fight in
 * ping-fail/reconnect bursts. The guard registers (host, port, name) at
 * connect time, refuses a second DIFFERENT-browser holder of the same name
 * with a connect_error + 4401 close, and lets a SAME-browserId reconnect take
 * the name over (the page-refresh path — restoreSessionId is not forwarded
 * through the proxy).
 *
 * Level 1 drives AnonNameGuard directly; level 2 drives real WebProxySessions
 * through onWebSocketText with a recording Jetty stub, exactly as the live
 * flow delivers a connect. No server, no XMage: a refused connect never dials.
 * The single instance under test is fresh per test (never AnonNameGuard
 * .shared()) so a leftover registration can never leak across tests — the
 * singleton's own state is exercised only via the session-level close path.
 */
public class AnonNameGuardTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 17171;

    private AnonNameGuard guard;

    @Before
    public void freshGuard() {
        guard = new AnonNameGuard();
    }

    @After
    public void clearProps() {
        // handleConnect reads the pin property; a value set by another test
        // class would change which host:port the session-level tests register
        // under (they assert on guard contents, not the pin, but keep the
        // property surface clean anyway).
        System.clearProperty("webproxy.pinnedXmageTarget");
    }

    // ── Level 1: the guard itself ──

    @Test
    public void secondDifferentBrowserSameNameIsRefused() {
        AnonNameGuard.Decision first = guard.checkAndRegister(null, HOST, PORT, "Bronze", "web:Bronze:a");
        Assert.assertTrue(first.allowed);
        Assert.assertNotNull("sanity: an allowed decision carries the entry to release", first.active);
        AnonNameGuard.Decision second = guard.checkAndRegister(null, HOST, PORT, "Bronze", "web:Bronze:b");
        Assert.assertFalse("first=" + first.allowed + " second should refuse", second.allowed);
        Assert.assertNull("a refused connect must not hand back an entry to release", second.active);
        Assert.assertEquals("the refused connect must leave the original registration intact",
                1, guard.registeredCount());
    }

    @Test
    public void sameBrowserIdReconnectTakesOver() {
        AnonNameGuard.Decision first = guard.checkAndRegister(null, HOST, PORT, "Bronze", "web:Bronze:a");
        Assert.assertTrue(first.allowed);
        AnonNameGuard.Decision takeover = guard.checkAndRegister(null, HOST, PORT, "Bronze", "web:Bronze:a");
        Assert.assertTrue("same-browserId reconnect must pass (the page-refresh path)", takeover.allowed);
        Assert.assertNotSame("takeover installs a NEW entry the new session must release",
                first.active, takeover.active);
        // The old owner's cleanup runs later; identity-checked release must NOT
        // drop the takeover entry.
        guard.release(first.active);
        Assert.assertEquals("old owner's release must not drop the takeover entry",
                1, guard.registeredCount());
        guard.release(takeover.active);
        Assert.assertEquals(0, guard.registeredCount());
    }

    @Test
    public void collisionIsScopedPerTargetServer() {
        guard.checkAndRegister(null, HOST, PORT, "Bronze", "web:Bronze:a");
        AnonNameGuard.Decision otherServer = guard.checkAndRegister(null, HOST, 17172, "Bronze", "web:Bronze:b");
        Assert.assertTrue("the same name on a DIFFERENT server must not collide", otherServer.allowed);
    }

    @Test
    public void nameMatchIsCaseInsensitive() {
        // Mirrors UserManagerImpl's toLowerCase(Locale.ROOT) user map: the
        // server would kick a "bronze" connect for holding "Bronze", so the
        // guard must refuse it too.
        guard.checkAndRegister(null, HOST, PORT, "Bronze", "web:Bronze:a");
        AnonNameGuard.Decision lower = guard.checkAndRegister(null, HOST, PORT, "bronze", "web:Bronze:b");
        Assert.assertFalse("case-variant of a taken name must be refused", lower.allowed);
    }

    @Test
    public void absentBrowserIdIsADistinctBrowser() {
        // Old clients / direct WS probes send no browserId. A no-id holder must
        // not be takeover-able by another no-id connect: "" == "" would
        // otherwise read as "same browser" and reopen the kick loop for probes.
        guard.checkAndRegister(null, HOST, PORT, "Bronze", "");
        AnonNameGuard.Decision second = guard.checkAndRegister(null, HOST, PORT, "Bronze", "");
        Assert.assertFalse("two browserId-less sessions must NOT be treated as the same browser",
                second.allowed);
    }

    @Test
    public void releaseIsIdempotent() {
        AnonNameGuard.Decision first = guard.checkAndRegister(null, HOST, PORT, "Bronze", "web:Bronze:a");
        guard.release(first.active);
        guard.release(first.active);
        Assert.assertEquals(0, guard.registeredCount());
    }

    // ── Level 2: session path (connect frame → guard → refusal shape) ──

    /** Minimal recording stub of Jetty's Session — same shape as WebProxyAuthCloseTest's. */
    static class StubSession implements org.eclipse.jetty.websocket.api.Session {
        boolean open = true;
        int closeCalls = 0;
        Integer closeCode = null;
        String closeReason = null;
        final java.util.List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override public void demand() {}
        @Override public void sendBinary(java.nio.ByteBuffer buffer, org.eclipse.jetty.websocket.api.Callback callback) {}
        @Override public void sendPartialBinary(java.nio.ByteBuffer buffer, boolean last, org.eclipse.jetty.websocket.api.Callback callback) {}
        @Override public void sendText(String s, org.eclipse.jetty.websocket.api.Callback callback) { sent.add(s); }
        @Override public void sendPartialText(String s, boolean last, org.eclipse.jetty.websocket.api.Callback callback) {}
        @Override public void sendPing(java.nio.ByteBuffer buffer, org.eclipse.jetty.websocket.api.Callback callback) {}
        @Override public void sendPong(java.nio.ByteBuffer buffer, org.eclipse.jetty.websocket.api.Callback callback) {}
        @Override public void close() { closeCalls++; open = false; }
        @Override public void close(int code, String reason, org.eclipse.jetty.websocket.api.Callback callback) {
            closeCalls++; closeCode = code; closeReason = reason; open = false;
        }
        @Override public void disconnect() {}
        @Override public java.net.SocketAddress getLocalSocketAddress() { return null; }
        @Override public java.net.SocketAddress getRemoteSocketAddress() { return null; }
        @Override public String getProtocolVersion() { return "13"; }
        @Override public org.eclipse.jetty.websocket.api.UpgradeRequest getUpgradeRequest() { return null; }
        @Override public org.eclipse.jetty.websocket.api.UpgradeResponse getUpgradeResponse() { return null; }
        @Override public boolean isOpen() { return open; }
        @Override public boolean isSecure() { return false; }
        @Override public java.time.Duration getIdleTimeout() { return java.time.Duration.ZERO; }
        @Override public void setIdleTimeout(java.time.Duration duration) {}
        @Override public int getInputBufferSize() { return 0; }
        @Override public void setInputBufferSize(int i) {}
        @Override public int getOutputBufferSize() { return 0; }
        @Override public void setOutputBufferSize(int i) {}
        @Override public long getMaxBinaryMessageSize() { return 0; }
        @Override public void setMaxBinaryMessageSize(long l) {}
        @Override public long getMaxTextMessageSize() { return 0; }
        @Override public void setMaxTextMessageSize(long l) {}
        @Override public long getMaxFrameSize() { return 0; }
        @Override public void setMaxFrameSize(long l) {}
        @Override public boolean isAutoFragment() { return false; }
        @Override public void setAutoFragment(boolean b) {}
        @Override public int getMaxOutgoingFrames() { return 0; }
        @Override public void setMaxOutgoingFrames(int i) {}
    }

    private static String connectJson(String username, String browserId) {
        String id = browserId == null ? "" : ",\"browserId\":\"" + browserId + "\"";
        return "{\"type\":\"connect\",\"username\":\"" + username
                + "\",\"password\":\"\",\"server\":\"" + HOST + "\",\"port\":" + PORT + id + "}";
    }

    @Test
    public void refusedConnectSendsErrorThenCloses4401() {
        WebProxySession first = new WebProxySession();
        StubSession ws1 = new StubSession();
        first.onWebSocketConnect(ws1);
        first.onWebSocketText(connectJson("Bronze", "web:Bronze:" + UUID.randomUUID()));
        // The first session's dial fails (nothing listens on 127.0.0.1:17171 in
        // the test JVM) but its guard registration stands — cleanup() only runs
        // when its WS closes.
        Assert.assertEquals("first session must hold the name", 1, AnonNameGuard.shared().registeredCount());

        WebProxySession second = new WebProxySession();
        StubSession ws2 = new StubSession();
        second.onWebSocketConnect(ws2);
        second.onWebSocketText(connectJson("Bronze", "web:Bronze:" + UUID.randomUUID()));

        Assert.assertTrue("the refusal must send a connect_error",
                ws2.sent.stream().anyMatch(json -> json.contains("connect_error")
                        && json.contains("Name in use by another session")));
        Assert.assertEquals("the refusal must close exactly once", 1, ws2.closeCalls);
        Assert.assertEquals("the refusal must use the 4401 user-fixable close code",
                4401, ws2.closeCode == null ? -1 : ws2.closeCode.intValue());
        // The first session's own dial already failed (nothing listens on
        // 127.0.0.1:17171 in the test JVM) so its login-failure close is
        // expected; the guard's contract is that the refusal adds NO further
        // kick — the first session's close count stays at its own one and the
        // name stays registered to it.
        Assert.assertEquals("the refusal adds no kick to the holder", 1, ws1.closeCalls);
        Assert.assertEquals("the refusal must not consume the name",
                1, AnonNameGuard.shared().registeredCount());

        // Teardown of the first session frees the name — cleanup() runs from
        // onWebSocketClose, and the refusal left no registration on the second.
        first.onWebSocketClose(1000, "test over");
        Assert.assertEquals(0, AnonNameGuard.shared().registeredCount());
    }

    @Test
    public void sameBrowserReconnectThroughSessionsIsAllowed() {
        String browserId = "web:Bronze:" + UUID.randomUUID();
        WebProxySession first = new WebProxySession();
        first.onWebSocketConnect(new StubSession());
        first.onWebSocketText(connectJson("Bronze", browserId));
        Assert.assertEquals(1, AnonNameGuard.shared().registeredCount());

        WebProxySession second = new WebProxySession();
        StubSession ws2 = new StubSession();
        second.onWebSocketConnect(ws2);
        second.onWebSocketText(connectJson("Bronze", browserId));

        Assert.assertFalse("same-browser reconnect must not be refused by the guard",
                ws2.sent.stream().anyMatch(json -> json.contains("Name in use by another session")));
        Assert.assertNotEquals("same-browser reconnect must not get the guard's 4401 close",
                "Name in use by another session", ws2.closeReason);
        // The takeover replaced the registration; the dying first session's
        // cleanup must not remove it.
        first.onWebSocketClose(1001, "page refresh");
        Assert.assertEquals("the takeover entry must survive the old session's cleanup",
                1, AnonNameGuard.shared().registeredCount());
        second.onWebSocketClose(1000, "test over");
        Assert.assertEquals(0, AnonNameGuard.shared().registeredCount());
    }
}
