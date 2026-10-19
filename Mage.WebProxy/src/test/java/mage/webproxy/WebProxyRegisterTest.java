package mage.webproxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.remote.Connection;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for the {@code register} browser verb.
 *
 * <p>TRACE THIS PINS (why the verb is a separate RPC and not a flag on `connect`):
 * the engine distinguishes login from register by METHOD. {@code connectStart}
 * calls {@code server.connectUser} (which under {@code authenticationActivated}
 * only accepts an ALREADY-registered account — {@code Session.connectUserHandling}
 * returns "Wrong username or password. You must register your account first." for
 * an unknown name), while {@code sendAuthRegister} calls {@code server.authRegister}
 * → {@code MageServerImpl.authRegister} → {@code SessionManagerImpl.registerUser} →
 * {@code Session.registerUser}. The desktop's RegisterUserDialog uses the latter
 * ({@code SessionHandler.register}). The proxy therefore needs a dedicated frame,
 * which is what these tests cover: parsing, the mapping onto the remote Connection
 * (host/port/username/password/EMAIL), every fail-fast rule, and the dispatch
 * wiring (the verb reaches the handler; a rejection answers `register_error`).
 *
 * <p>Contract pinned here (the handler's javadoc carries the wire form):
 *  - the frame's server/port follow the CONNECT rules verbatim via the same
 *    resolver ({@code WebProxyServer.resolveConnectTarget}): pinned target =
 *    optional+ignored, unpinned = required, invalid port refused;
 *  - the SSRF host allowlist applies to the register dial exactly as to connect;
 *  - the account-name rules mirror the server's own Session.validateUserName
 *    (length 3..14, allowlist a-z A-Z 0-9 _) and are refused BEFORE any dial;
 *  - the email is required and trimmed (the server mails the generated password
 *    there; its own validateEmail rejects a blank address — one wasted dial saved);
 *  - the parsed fields land on the Connection object exactly as the desktop
 *    RegisterUserDialog builds it (`toConnection`);
 *  - a rejected frame produces NO Connection / no dial;
 *  - dispatch: {"type":"register"} reaches handleRegister, and a refusal is
 *    answered with a `register_error` frame (not the generic `error`).
 *
 * Same technique as WebProxyAutoAnswerTest / WebProxyViewLimitedDeckTest: a real
 * WebProxySession whose browser pushes are captured (sendToBrowser is
 * package-private, so a recording subclass sees the exact frames), driven through
 * the REAL dispatch entry point {@code onWebSocketText} where the wiring matters.
 * No server, no dial: the parse seam is static and the dial is exercised live by
 * the unit's harness.
 */
public class WebProxyRegisterTest {

    @After
    public void clearProps() {
        System.clearProperty("webproxy.hiddenServerTarget");
        System.clearProperty("webproxy.pinnedXmageTarget");
        System.clearProperty("webproxy.allowedXmageHosts");
    }

    // ── fixtures ──

    /** A WebProxySession whose browser pushes are captured instead of written. */
    private static final class RecordingSession extends WebProxySession {
        final List<String> sent = new ArrayList<>();

        @Override
        boolean sendToBrowser(String json) {
            sent.add(json);
            return true;
        }

        /** The last frame sent, parsed (null when nothing was sent). */
        JsonObject lastFrame() {
            return sent.isEmpty() ? null : JsonParser.parseString(sent.get(sent.size() - 1)).getAsJsonObject();
        }
    }

    private static JsonObject frame(String username, String password, String email,
                                    String server, Integer port) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "register");
        if (username != null) o.addProperty("username", username);
        if (password != null) o.addProperty("password", password);
        if (email != null) o.addProperty("email", email);
        if (server != null) o.addProperty("server", server);
        if (port != null) o.addProperty("port", port);
        return o;
    }

    private static JsonObject raw(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    // ── target resolution: identical rules to `connect` ──

    @Test
    public void unpinnedRequiresServerAndPort() {
        Assert.assertEquals("Missing server",
                WebProxySession.parseRegisterRequest(frame("RegUser", "pw", "a@b.c", null, 17171), "").error);
        Assert.assertEquals("Missing port",
                WebProxySession.parseRegisterRequest(frame("RegUser", "pw", "a@b.c", "127.0.0.1", null), "").error);
    }

    @Test
    public void invalidPortIsRefused() {
        WebProxySession.RegisterRequest req = WebProxySession.parseRegisterRequest(
                raw("{\"type\":\"register\",\"username\":\"RegUser\",\"email\":\"a@b.c\","
                        + "\"server\":\"127.0.0.1\",\"port\":\"abc\"}"), "");
        Assert.assertNotNull(req.error);
        Assert.assertTrue(req.error.contains("Invalid port"));
    }

    @Test
    public void pinnedTargetMakesFieldsOptionalAndWins() {
        WebProxySession.RegisterRequest req = WebProxySession.parseRegisterRequest(
                frame("RegUser", "pw", "a@b.c", null, null), "127.0.0.1:17171");
        Assert.assertNull(req.error);
        Assert.assertEquals("127.0.0.1", req.host);
        Assert.assertEquals(17171, req.port);
        // A client-supplied target must not override the pin (same as connect).
        WebProxySession.RegisterRequest req2 = WebProxySession.parseRegisterRequest(
                frame("RegUser", "pw", "a@b.c", "localhost", 9999), "127.0.0.1:17171");
        Assert.assertNull(req2.error);
        Assert.assertEquals("127.0.0.1", req2.host);
        Assert.assertEquals(17171, req2.port);
    }

    /**
     * The pin does NOT bypass the SSRF allowlist — the same rule as `connect`
     * (WebProxySession.handleConnect's "The pinned target still passes the
     * allowlist check below"). A deployment that pins a non-localhost target must
     * also add it to -Dwebproxy.allowedXmageHosts.
     */
    @Test
    public void aPinnedTargetStillPassesTheAllowlist() {
        WebProxySession.RegisterRequest req = WebProxySession.parseRegisterRequest(
                frame("RegUser", "pw", "a@b.c", null, null), "xmage-server:17171");
        Assert.assertNotNull(req.error);
        Assert.assertTrue(req.error.contains("allowlist"));
    }

    @Test
    public void malformedPinnedTargetIsAnError() {
        WebProxySession.RegisterRequest req = WebProxySession.parseRegisterRequest(
                frame("RegUser", "pw", "a@b.c", null, null), "host:notaport");
        Assert.assertNotNull(req.error);
        Assert.assertTrue(req.error.contains("malformed"));
    }

    @Test
    public void nonAllowlistedHostIsRefused() {
        WebProxySession.RegisterRequest req = WebProxySession.parseRegisterRequest(
                frame("RegUser", "pw", "a@b.c", "evil.example.com", 17171), "");
        Assert.assertNotNull(req.error);
        Assert.assertTrue(req.error.contains("allowlist"));
    }

    // ── account-name rules (mirror of Session.validateUserName) ──

    @Test
    public void usernameLengthBoundsAreEnforced() {
        Assert.assertNotNull(WebProxySession.parseRegisterRequest(
                frame("ab", "pw", "a@b.c", "127.0.0.1", 17171), "").error);
        Assert.assertNotNull(WebProxySession.parseRegisterRequest(
                frame("abcdefghijklmno", "pw", "a@b.c", "127.0.0.1", 17171), "").error);
        Assert.assertNull(WebProxySession.parseRegisterRequest(
                frame("abc", "pw", "a@b.c", "127.0.0.1", 17171), "").error);
        Assert.assertNull(WebProxySession.parseRegisterRequest(
                frame("abcdefghijklmn", "pw", "a@b.c", "127.0.0.1", 17171), "").error);
    }

    @Test
    public void usernameCharacterRuleMirrorsTheServer() {
        // config.xml ships invalidUserNamePattern="[^a-z0-9_]" — the server refuses
        // anything outside a-z A-Z 0-9 _; the pre-flight must refuse the same set.
        for (String bad : new String[] { "has space", "has-dash", "has.dot", "héllo", "drop'quote", "<b>tag" }) {
            WebProxySession.RegisterRequest req = WebProxySession.parseRegisterRequest(
                    frame(bad, "pw", "a@b.c", "127.0.0.1", 17171), "");
            Assert.assertNotNull("expected a refusal for username '" + bad + "'", req.error);
        }
        for (String good : new String[] { "abc", "ABC123", "a_b_c", "Z9x" }) {
            Assert.assertNull("expected acceptance for username '" + good + "'",
                    WebProxySession.parseRegisterRequest(
                            frame(good, "pw", "a@b.c", "127.0.0.1", 17171), "").error);
        }
    }

    @Test
    public void usernameIsTrimmedAndBlankRefused() {
        WebProxySession.RegisterRequest req = WebProxySession.parseRegisterRequest(
                frame("  RegUser  ", "pw", "a@b.c", "127.0.0.1", 17171), "");
        Assert.assertNull(req.error);
        Assert.assertEquals("RegUser", req.username);
        Assert.assertNotNull(WebProxySession.parseRegisterRequest(
                frame("   ", "pw", "a@b.c", "127.0.0.1", 17171), "").error);
        Assert.assertNotNull(WebProxySession.parseRegisterRequest(
                frame(null, "pw", "a@b.c", "127.0.0.1", 17171), "").error);
    }

    // ── email ──

    @Test
    public void emailIsRequiredAndTrimmed() {
        Assert.assertNotNull("a missing email must be refused before any dial",
                WebProxySession.parseRegisterRequest(frame("RegUser", "pw", null, "127.0.0.1", 17171), "").error);
        Assert.assertNotNull("a blank email must be refused",
                WebProxySession.parseRegisterRequest(frame("RegUser", "pw", "  ", "127.0.0.1", 17171), "").error);
        WebProxySession.RegisterRequest req = WebProxySession.parseRegisterRequest(
                frame("RegUser", "pw", " reg@example.com ", "127.0.0.1", 17171), "");
        Assert.assertNull(req.error);
        Assert.assertEquals("reg@example.com", req.email);
    }

    // ── the mapping onto the remote Connection (desktop parity) ──

    @Test
    public void parsedFieldsLandOnTheConnectionExactlyLikeTheDesktopBuildsIt() {
        WebProxySession.RegisterRequest req = WebProxySession.parseRegisterRequest(
                frame("RegUser", "hunter2", "reg@example.com", "127.0.0.1", 17171), "");
        Assert.assertNull(req.error);
        Connection connection = req.toConnection();
        Assert.assertEquals("127.0.0.1", connection.getHost());
        Assert.assertEquals(17171, connection.getPort());
        Assert.assertEquals("RegUser", connection.getUsername());
        Assert.assertEquals("hunter2", connection.getPassword());
        Assert.assertEquals("reg@example.com", connection.getEmail());
        Assert.assertEquals(Connection.ProxyType.NONE, connection.getProxyType());
    }

    @Test
    public void aRejectedFrameProducesNoConnection() {
        WebProxySession.RegisterRequest req = WebProxySession.parseRegisterRequest(
                frame("ab", "pw", "a@b.c", "127.0.0.1", 17171), "");
        Assert.assertNotNull(req.error);
        Assert.assertNull("a rejected frame must not carry a dialable target", req.host);
    }

    // ── dispatch wiring + the browser frame ──

    /**
     * The real dispatch switch must route `register` to the handler and answer a
     * refusal with `register_error` — NOT the generic `error` (the dialog reads
     * register_error inline on the connect overlay).
     */
    @Test
    public void registerDispatchAnswersRefusalsWithRegisterError() {
        RecordingSession session = new RecordingSession();
        session.onWebSocketConnect(new WebProxyAuthCloseTest.StubSession());
        // Unpinned proxy + no target -> the resolver refuses.
        session.onWebSocketText("{\"type\":\"register\",\"username\":\"RegUser\",\"email\":\"a@b.c\"}");

        JsonObject frame = session.lastFrame();
        Assert.assertNotNull("the register verb must be answered with a frame", frame);
        Assert.assertEquals("register_error", frame.get("type").getAsString());
        Assert.assertEquals("Missing server", frame.get("message").getAsString());
    }

    /** The register verb must be reachable and must NOT go through handleConnect. */
    @Test
    public void registerIsItsOwnVerbNotTheConnectPath() {
        RecordingSession session = new RecordingSession();
        session.onWebSocketConnect(new WebProxyAuthCloseTest.StubSession());
        // A connect-only field set may not satisfy register: no email.
        session.onWebSocketText("{\"type\":\"register\",\"username\":\"RegUser\",\"password\":\"pw\","
                + "\"server\":\"127.0.0.1\",\"port\":17171}");
        JsonObject frame = session.lastFrame();
        Assert.assertNotNull(frame);
        Assert.assertEquals("register_error", frame.get("type").getAsString());
        Assert.assertTrue(frame.get("message").getAsString().toLowerCase().contains("email"));
    }

    /**
     * A duplicate register while one is in flight is refused — the handler's
     * in-flight guard (the dial holds callbacks for seconds; two dials would race
     * their answers into the same browser frame type).
     */
    @Test
    public void duplicateRegisterWhileInFlightIsRefused() throws Exception {
        RecordingSession session = new RecordingSession();
        session.onWebSocketConnect(new WebProxyAuthCloseTest.StubSession());
        setField(session, "registerInFlight", Boolean.TRUE);

        invokeHandleRegister(session, frame("RegUser", "pw", "a@b.c", "127.0.0.1", 17171));

        JsonObject last = session.lastFrame();
        Assert.assertNotNull(last);
        Assert.assertEquals("register_error", last.get("type").getAsString());
        Assert.assertTrue(last.get("message").getAsString().contains("already in progress"));
    }

    // ── RegisterAuthClient: the outcome-text capture (the only reason surface) ──

    @Test
    public void authClientCapturesTheServersShowUsermessageText() throws Exception {
        RegisterAuthClient client = new RegisterAuthClient();
        // The server's sendErrorMessageToClient / sendInfoMessageToClient shape:
        // SHOW_USERMESSAGE carrying [title, text].
        List<String> payload = new ArrayList<>();
        payload.add("Error while connecting to server");
        payload.add("User name 'RegUser' already in use");
        client.onCallback(new mage.interfaces.callback.ClientCallback(
                mage.interfaces.callback.ClientCallbackMethod.SHOW_USERMESSAGE, null, payload, false));

        Assert.assertTrue(client.lastMessage().contains("already in use"));
        Assert.assertFalse("the title must not leak into the user-facing text",
                client.lastMessage().contains("Error while connecting"));
    }

    @Test
    public void authClientCapturesShowErrorAndShowMessage() {
        RegisterAuthClient a = new RegisterAuthClient();
        a.showError("Registration is disabled on this server.");
        Assert.assertEquals("Registration is disabled on this server.", a.lastMessage());

        RegisterAuthClient b = new RegisterAuthClient();
        b.showMessage("You are successfully registered as RegUser.");
        Assert.assertEquals("You are successfully registered as RegUser.", b.lastMessage());
    }

    @Test
    public void authClientReportsNoMessageBeforeAnythingArrives() {
        Assert.assertEquals("", new RegisterAuthClient().lastMessage());
        // A short wait with nothing coming must return "" — never null, never invented text.
        Assert.assertEquals("", new RegisterAuthClient().awaitMessage(50));
    }

    // ── helpers ──

    private static void invokeHandleRegister(WebProxySession session, JsonObject json) throws Exception {
        Method m = WebProxySession.class.getDeclaredMethod("handleRegister", JsonObject.class);
        m.setAccessible(true);
        m.invoke(session, json);
    }

    private static void setField(Object target, String field, Object value) throws Exception {
        Field f = WebProxySession.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
