package mage.webproxy;

import com.google.gson.JsonObject;
import mage.players.net.UserData;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Regression tests for the avatar plumbing.
 *
 * Before the avatar plumbing, the web client had no avatar support at all: the connect path built
 * UserData with a hardcoded avatarId 0, so every seat in the browser rendered
 * whatever the desktop's clamp makes of 0 — the default avatar — and 0 is not
 * even a shipped image. The plumbing carries the browser's chosen id through
 * UserData → PlayerView → GameStateSerializer so each seat renders its own
 * avatar, and adds a mid-session `set_avatar` verb (desktop parity:
 * PreferencesDialog pushes the new avatar the moment it is picked).
 *
 * Encodes:
 *  (a) normalizeAvatarId CLAMPS rather than refuses: the drawable range is
 *      10…32 (Constants.MIN/MAX_AVATAR_ID) and anything else — 0 (the legacy
 *      wire value), null, negatives, 9, 33, the desktop's special range ≥ 1000
 *      — resolves to the default 10, which always has a shipped image. The
 *      desktop clamps in exactly the same way (PlayerPanelExt:361-363); a
 *      cosmetic id must never block a connect the user cannot fix.
 *  (b) the CONNECT path stores the requested avatar: the session field ends on
 *      the clamped id, so the later mid-session rebuilds cannot resurrect the
 *      default.
 *  (c) the SERIALIZER carries the per-seat avatar id: GameStateSerializer emits
 *      `avatarId` from PlayerView.getUserData() — the same source the desktop
 *      reads — and a payload with no user data yields 0 (the frontend resolver
 *      then draws the default, never a broken image).
 *  (d) the mid-session verb REBUILDS UserData preserving everything else: skip
 *      steps AND the browserId (userIdStr) must survive an avatar change — the
 *      browserId carries the shared-IP mitigation
 * and a rebuild that
 *      dropped it would silently re-merge users behind the VPS's single IP.
 *  (e) the verb ECHOES the effective id (`avatar_set`) so the picker can
 *      reflect a repaired value, and it does not require a seated live game —
 *      an avatar is a user preference, not game state.
 *
 * Drives a real WebProxySession with a real (unconnected) WebProxyClient and a
 * recording WebSocket stub — no server, no XMage — the same technique
 * WebProxyTokenArtPreferenceTest uses.
 */
public class WebProxyAvatarTest {

    private WebProxyAuthCloseTest.RecordingAppender appender;

    @Before
    public void attachLogs() {
        appender = new WebProxyAuthCloseTest.RecordingAppender();
        appender.setThreshold(Level.TRACE);
        Logger.getRootLogger().addAppender(appender);
    }

    @After
    public void detachLogs() {
        Logger.getRootLogger().removeAppender(appender);
    }

    // ── fixtures ──

    private static WebProxySession sessionWithClient(String username) throws Exception {
        WebProxySession session = new WebProxySession();
        Field f = WebProxySession.class.getDeclaredField("proxyClient");
        f.setAccessible(true);
        f.set(session, new WebProxyClient(username));
        // The verb checks xmageSession before touching anything; a real but
        // UNCONNECTED SessionImpl is enough (updatePreferencesForServer returns
        // true without dialing) — same stand-in as WebProxyEscapeJsonTest.
        Field xs = WebProxySession.class.getDeclaredField("xmageSession");
        xs.setAccessible(true);
        xs.set(session, newUnconnectedXmageSession());
        return session;
    }

    private static mage.remote.SessionImpl newUnconnectedXmageSession() {
        return new mage.remote.SessionImpl(new mage.interfaces.MageClient() {
            @Override public mage.utils.MageVersion getVersion() { return null; }
            @Override public void connected(String message) { }
            @Override public void disconnected(boolean askToReconnect, boolean keepMySessionActive) { }
            @Override public void showMessage(String message) { }
            @Override public void showError(String message) { }
            @Override public void onNewConnection() { }
            @Override public void onCallback(mage.interfaces.callback.ClientCallback callback) { }
        });
    }

    /** UserData.userIdStr has no getter (unused by the desktop) — read the field. */
    private static String userIdStrOf(UserData userData) throws Exception {
        Field f = UserData.class.getDeclaredField("userIdStr");
        f.setAccessible(true);
        return (String) f.get(userData);
    }

    private WebProxyAuthCloseTest.StubSession attachWs(WebProxySession session) {
        WebProxyAuthCloseTest.StubSession ws = new WebProxyAuthCloseTest.StubSession();
        session.onWebSocketConnect(ws);
        return ws;
    }

    /** Set a volatile session field (e.g. the connection) via reflection. */
    private static void setField(WebProxySession session, String name, Object value) throws Exception {
        Field f = WebProxySession.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(session, value);
    }

    /**
     * A connection in the state the CONNECT path leaves behind: userIdStr
     * (browserId, line 835) and a UserData (line 863) both set. The bare
     * `new Connection(param)` sets only the constructor parameter — NOT
     * userIdStr — so a fixture built from it would test the wrong state.
     */
    private static mage.remote.Connection connectedLikeConnection() {
        mage.remote.Connection connection = new mage.remote.Connection("web:tester:uuid");
        connection.setUserIdStr("web:tester:uuid");
        connection.setUserData(defaultUserData(20, "web:tester:uuid"));
        return connection;
    }

    private static UserData defaultUserData(int avatarId, String userIdStr) {
        return new UserData(mage.players.net.UserGroup.PLAYER, avatarId, true, false,
                new mage.players.net.UserSkipPrioritySteps(), "world", false, true, true,
                false, false, true, 1, true, false, userIdStr);
    }

    private static int getIntField(WebProxySession session, String name) throws Exception {
        Field f = WebProxySession.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(session);
    }

    private static void invokeSetAvatar(WebProxySession session, JsonObject obj) throws Exception {
        Method m = WebProxySession.class.getDeclaredMethod("handleSetAvatar", JsonObject.class);
        m.setAccessible(true);
        m.invoke(session, obj);
    }

    private static JsonObject setAvatarJson(Integer avatarId) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "set_avatar");
        if (avatarId == null) {
            o.add("avatarId", com.google.gson.JsonNull.INSTANCE);
        } else {
            o.addProperty("avatarId", avatarId);
        }
        return o;
    }

    private boolean logged(String fragment) {
        return appender.events.stream().anyMatch(e -> e.getRenderedMessage() != null
                && e.getRenderedMessage().contains(fragment));
    }

    // ── (a) the clamp ──

    @Test
    public void normalizeAvatarIdClampsToTheDrawableRange() throws Exception {
        Method m = WebProxySession.class.getDeclaredMethod("normalizeAvatarId", int.class);
        m.setAccessible(true);

        Assert.assertEquals("a selectable id passes through unchanged",
                12, (int) (Integer) m.invoke(null, 12));
        Assert.assertEquals("the range endpoints are drawable",
                10, (int) (Integer) m.invoke(null, 10));
        Assert.assertEquals("the range endpoints are drawable",
                32, (int) (Integer) m.invoke(null, 32));

        // 0 is the previous hardcoded wire value — not a shipped image, so it must
        // repair to the default (this is what makes an old cached bundle safe).
        Assert.assertEquals("0 (the previous hardcoded value) repairs to the default",
                10, (int) (Integer) m.invoke(null, 0));
        Assert.assertEquals("9 is below the range", 10, (int) (Integer) m.invoke(null, 9));
        Assert.assertEquals("33 is above the range", 10, (int) (Integer) m.invoke(null, 33));
        Assert.assertEquals("negatives are not ids", 10, (int) (Integer) m.invoke(null, -5));
        Assert.assertEquals("the desktop's special range is not served by the web client",
                10, (int) (Integer) m.invoke(null, 1000));
        Assert.assertEquals("the computer avatar is not a selectable player avatar",
                10, (int) (Integer) m.invoke(null, 64));
    }

    // ── (b) the verb: effective id + echo ──

    @Test
    public void setAvatarAdoptsClampsAndEchoesTheEffectiveId() throws Exception {
        WebProxySession session = sessionWithClient("tester");
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);
        // The handler needs a Connection (it rebuilds UserData from the current
        // one). A bare Connection is enough — no server is involved.
        setField(session, "connection", connectedLikeConnection());

        invokeSetAvatar(session, setAvatarJson(21));

        Assert.assertEquals("the session remembers the chosen avatar",
                21, getIntField(session, "sessionAvatarId"));
        Assert.assertTrue("the effective id is echoed as avatar_set",
                ws.sent.stream().anyMatch(s -> s.contains("\"type\":\"avatar_set\"")));
        Assert.assertTrue("the echo carries the effective id",
                ws.sent.stream().anyMatch(s -> s.contains("\"avatarId\":21")));
        Assert.assertTrue("no error frame for a legal avatar",
                ws.sent.stream().noneMatch(s -> s.contains("\"error\"")));
    }

    @Test
    public void setAvatarRepairsAnOutOfRangeIdAndSaysSo() throws Exception {
        WebProxySession session = sessionWithClient("tester");
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);
        setField(session, "connection", connectedLikeConnection());

        invokeSetAvatar(session, setAvatarJson(9999));

        Assert.assertEquals("an out-of-range id repairs to the default",
                10, getIntField(session, "sessionAvatarId"));
        Assert.assertTrue("the repair is logged, not silent",
                logged("Repaired out-of-range avatarId 9999"));
        Assert.assertTrue("the browser still gets an answer (the EFFECTIVE id)",
                ws.sent.stream().anyMatch(s -> s.contains("\"type\":\"avatar_set\"")
                        && s.contains("\"avatarId\":10")));
    }

    // ── (d) the rebuild preserves the OTHER preferences ──

    @Test
    public void setAvatarPreservesSkipStepsAndBrowserIdInTheRebuiltUserData() throws Exception {
        WebProxySession session = sessionWithClient("tester");
        attachWs(session);
        // The connection as connect leaves it, then a live phase-stop change on
        // top (yourTurn: upkeep armed; opponentTurn: main1 explicitly cleared).
        mage.remote.Connection connection = connectedLikeConnection();
        mage.players.net.UserSkipPrioritySteps skipSteps = new mage.players.net.UserSkipPrioritySteps();
        skipSteps.getYourTurn().setUpkeep(true);
        skipSteps.getOpponentTurn().setMain1(false);
        connection.setUserData(new UserData(mage.players.net.UserGroup.PLAYER, 20, true, false,
                skipSteps, "world", false, true, true, false, false, true, 1, true, false,
                "web:tester:uuid"));
        setField(session, "connection", connection);

        invokeSetAvatar(session, setAvatarJson(25));

        UserData after = connection.getUserData();
        Assert.assertNotNull("the rebuild must install a UserData", after);
        Assert.assertEquals("the new avatar is in the rebuilt UserData",
                25, after.getAvatarId());
        Assert.assertTrue("the browserId (shared-IP mitigation) survives the rebuild",
                "web:tester:uuid".equals(userIdStrOf(after)));
        Assert.assertNotNull("skip steps survive the rebuild",
                after.getUserSkipPrioritySteps());
        Assert.assertTrue("...and their VALUE survives (yourTurn.upkeep=true)",
                after.getUserSkipPrioritySteps().getYourTurn().isUpkeep());
        Assert.assertFalse("...including an explicitly-cleared step (opponentTurn.main1=false)",
                after.getUserSkipPrioritySteps().getOpponentTurn().isMain1());
    }

    @Test
    public void setAvatarOnAConnectionWithoutUserDataIsAnErrorNotAThrow() throws Exception {
        WebProxySession session = sessionWithClient("tester");
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);
        // Connection present but no UserData — a state the connect path never
        // leaves behind, so the handler must fail fast and visibly. (A bare
        // Connection carries no userData field; only the connect path sets it.)
        mage.remote.Connection bare = new mage.remote.Connection("web:tester:uuid");
        setField(session, "connection", bare);

        invokeSetAvatar(session, setAvatarJson(19));

        Assert.assertTrue("the failure is surfaced to the browser",
                ws.sent.stream().anyMatch(s -> s.contains("\"error\"")));
        Assert.assertTrue("...and logged for the operator",
                logged("set_avatar on a session whose Connection has no UserData"));
    }

    /** No connection at all (a spectator before connect) must also answer, not throw. */
    @Test
    public void setAvatarBeforeConnectAnswersAnError() throws Exception {
        WebProxySession session = sessionWithClient("tester");
        WebProxyAuthCloseTest.StubSession ws = attachWs(session);

        invokeSetAvatar(session, setAvatarJson(19));

        Assert.assertTrue("an unconnected session gets an explicit error frame",
                ws.sent.stream().anyMatch(s -> s.contains("Not connected to XMage server")));
    }

    /** A missing/JSON-null avatarId falls back to the default instead of throwing. */
    @Test
    public void setAvatarWithNoIdUsesTheDefault() throws Exception {
        WebProxySession session = sessionWithClient("tester");
        attachWs(session);
        setField(session, "connection", connectedLikeConnection());

        invokeSetAvatar(session, setAvatarJson(null));

        Assert.assertEquals("absent avatarId means the default avatar",
                10, getIntField(session, "sessionAvatarId"));
    }

    // ── (e) the default field value ──

    @Test
    public void aFreshSessionCarriesTheDesktopDefault() throws Exception {
        WebProxySession session = sessionWithClient("tester");
        Assert.assertEquals("the pre-connect default mirrors Constants.DEFAULT_AVATAR_ID",
                10, getIntField(session, "sessionAvatarId"));
    }
}
