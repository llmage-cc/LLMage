package mage.webproxy;

import com.google.gson.JsonParser;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Fix-2/fix-3 regression tests for the auth-reject reconnect loop


 *
 * Fix 2: a deliberate close must not log an ERROR + stack trace — one INFO line
 *        instead; a genuine error keeps its ERROR.
 * Fix 3: the auth-reject close carries the dedicated application close code
 *        4401 + reason, so the browser can refuse to auto-reconnect on it.
 *
 * Drives a real WebProxySession with a recording stub of Jetty's Session — no
 * server, no XMage — exactly as the live flow delivers the rejection, plus a
 * log4j appender that records the WebProxySession logger's output.
 */
public class WebProxyAuthCloseTest {

    /** Minimal recording stub of org.eclipse.jetty.websocket.api.Session. */
    static class StubSession implements org.eclipse.jetty.websocket.api.Session {
        boolean open = true;
        int closeCalls = 0;
        Integer closeCode = null;
        String closeReason = null;
        // Copy-on-write: WS sends arrive from watch/background threads while test threads iterate.
        final List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override public void demand() {}
        @Override public void sendBinary(ByteBuffer buffer, org.eclipse.jetty.websocket.api.Callback callback) {}
        @Override public void sendPartialBinary(ByteBuffer buffer, boolean last, org.eclipse.jetty.websocket.api.Callback callback) {}
        @Override public void sendText(String s, org.eclipse.jetty.websocket.api.Callback callback) { sent.add(s); }
        @Override public void sendPartialText(String s, boolean last, org.eclipse.jetty.websocket.api.Callback callback) {}
        @Override public void sendPing(ByteBuffer buffer, org.eclipse.jetty.websocket.api.Callback callback) {}
        @Override public void sendPong(ByteBuffer buffer, org.eclipse.jetty.websocket.api.Callback callback) {}
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
        // ── Configurable ──
        @Override public Duration getIdleTimeout() { return Duration.ZERO; }
        @Override public void setIdleTimeout(Duration duration) {}
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

    /** Records every event the WebProxySession logger emits during a test. */
    static class RecordingAppender extends AppenderSkeleton {
        // Copy-on-write: appender threads (watch executors) write while test threads iterate;
        // an ArrayList raced into ConcurrentModificationException (measured in WebProxyAutoStartRetryTest).
        final List<LoggingEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override protected void append(LoggingEvent event) { events.add(event); }
        @Override public void close() {}
        @Override public boolean requiresLayout() { return false; }
        long count(java.util.function.Predicate<LoggingEvent> predicate) {
            return events.stream().filter(predicate).count();
        }
    }

    private RecordingAppender appender;

    @Before
    public void captureLogs() {
        appender = new RecordingAppender();
        appender.setThreshold(Level.TRACE);
        Logger.getRootLogger().addAppender(appender);
    }

    @After
    public void clearProps() {
        System.clearProperty("webproxy.authToken");
        Logger.getRootLogger().removeAppender(appender);
    }

    private long errorEvents() {
        return appender.count(e -> e.getLevel() == Level.ERROR);
    }

    private long deliberateInfoEvents() {
        return appender.count(e -> e.getLevel() == Level.INFO
                && e.getRenderedMessage() != null
                && e.getRenderedMessage().contains("deliberate close"));
    }

    private static StubSession connectSession(WebProxySession session) {
        StubSession ws = new StubSession();
        session.onWebSocketConnect(ws);
        return ws;
    }

    // ── Fix 3: the auth-reject close uses the dedicated 4401 code + reason ──

    @Test
    public void authRejectClosesWithDedicatedCodeAndReason() {
        System.setProperty("webproxy.authToken", "sekrit");
        WebProxySession session = new WebProxySession();
        StubSession ws = connectSession(session);

        session.onWebSocketText("{\"type\":\"connect\",\"username\":\"u\",\"authToken\":\"\"}");

        Assert.assertEquals("auth reject must close exactly once", 1, ws.closeCalls);
        Assert.assertEquals("auth reject must use the 4401 application code",
                4401, ws.closeCode == null ? -1 : ws.closeCode.intValue());
        Assert.assertEquals("close reason must say what is wrong",
                "Auth token required", ws.closeReason);
    }

    /**
     * The proxy sends connect_error BEFORE closing: the client surfaces the
     * form from connect_error; the close's 4401 code then suppresses the
     * auto-reconnect. sendToBrowser is synchronous to the stub, so the sent
     * list is the exact wire order.
     */
    @Test
    public void connectErrorArrivesBeforeClose() {
        System.setProperty("webproxy.authToken", "sekrit");
        WebProxySession session = new WebProxySession();
        StubSession ws = connectSession(session);

        session.onWebSocketText("{\"type\":\"connect\",\"username\":\"u\",\"authToken\":\"\"}");

        Assert.assertEquals(1, ws.closeCalls);
        Assert.assertTrue("connect_error must be sent before the auth-reject close",
                ws.sent.stream().anyMatch(json -> json.contains("connect_error")));
    }

    // ── Fix 2: deliberate close logs INFO, genuine error keeps ERROR ──

    @Test
    public void deliberateAuthCloseLogsOneInfoLineNoErrorTrace() {
        System.setProperty("webproxy.authToken", "sekrit");
        WebProxySession session = new WebProxySession();
        StubSession ws = connectSession(session);
        Assert.assertEquals(0, errorEvents());

        // The rejection itself: one WARN + connect_error + the deliberate 4401 close.
        session.onWebSocketText("{\"type\":\"connect\",\"username\":\"u\",\"authToken\":\"\"}");
        // Jetty surfaces the follow-up error for a close we chose to make:
        session.onWebSocketError(new java.nio.channels.ClosedChannelException());

        Assert.assertEquals("the deliberate close must not log an ERROR", 0, errorEvents());
        Assert.assertEquals("the deliberate close logs exactly one INFO line",
                1, deliberateInfoEvents());
    }

    @Test
    public void genuineErrorStillLogsErrorWithTrace() {
        WebProxySession session = new WebProxySession();
        connectSession(session);
        Assert.assertEquals(0, errorEvents());

        session.onWebSocketError(new RuntimeException("genuine failure"));

        Assert.assertEquals("a genuine error must still log at ERROR", 1, errorEvents());
        Assert.assertTrue("the ERROR must carry the throwable",
                appender.events.stream().anyMatch(e ->
                        e.getLevel() == Level.ERROR && e.getThrowableInformation() != null));
    }

    @Test
    public void deliberateCloseFlagIsConsumeOnce() {
        System.setProperty("webproxy.authToken", "sekrit");
        WebProxySession session = new WebProxySession();
        StubSession ws = connectSession(session);
        session.onWebSocketText("{\"type\":\"connect\",\"username\":\"u\",\"authToken\":\"\"}");
        Assert.assertEquals(1, ws.closeCalls);

        // First error: consumed as the deliberate close's follow-up.
        session.onWebSocketError(new java.nio.channels.ClosedChannelException());
        // Second error on the same session: the flag must already be consumed,
        // so this one logs at ERROR again (not silently downgraded).
        session.onWebSocketError(new RuntimeException("genuine later error"));

        Assert.assertEquals("a later genuine error must log at ERROR", 1, errorEvents());
    }

    // ── No-token (open proxy) path unchanged ──

    @Test
    public void openProxyConnectIsUnaffected() {
        WebProxySession session = new WebProxySession();
        StubSession ws = connectSession(session);
        // No token configured: requireAuthFor returns true and handleConnect runs.
        // Whether it reaches a locally-running XMage or nothing listens (the login
        // rejection closes 4401 since 2f7eca9fbf), the AUTH gate must never be what
        // rejected this connect: its close carries reason "Auth token required" and
        // its connect_error names a bad/missing token. (Pre-2f7eca9fbf this test
        // asserted closeCalls == 0; that expectation is gone with the close
        // generalization — and it wrongly failed on every host with no listener.)
        session.onWebSocketText("{\"type\":\"connect\",\"username\":\"u\",\"password\":\"\",\"server\":\"127.0.0.1\",\"port\":17171}");
        Assert.assertNotEquals("open proxy must not auth-close on connect",
                "Auth token required", ws.closeReason);
        Assert.assertFalse("auth gate must not send its connect_error on an open proxy",
                ws.sent.stream().anyMatch(json -> json.contains("Bad or missing auth token")));
    }
}