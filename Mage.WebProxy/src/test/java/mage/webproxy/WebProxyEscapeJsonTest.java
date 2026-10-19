package mage.webproxy;

import com.google.gson.JsonParser;
import mage.interfaces.MageClient;
import mage.remote.SessionImpl;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;

/**
 * Regression tests for the literal backslash-u0027 sequence in error toasts
 * ("shown literally in an error toast").
 *
 * <p>Mechanism (parent-proven, re-derived here): {@code escapeJson(s)} returned
 * Gson's escaped form — Gson html-escapes an apostrophe as the six characters
 * backslash-u-0-0-2-7. When that result was placed into a Map serialized AGAIN
 * by {@code gsonToJson} ({@code sendError}, {@code sendTypedMessage},
 * {@code msg.put}), the backslash was re-escaped, the wire carried a
 * DOUBLE-backslash sequence, and the browser's {@code JSON.parse} left the
 * literal six characters in the toast instead of an apostrophe.
 *
 * <p>Contract pinned here:
 *  - the REAL deck-parse error at handleCreateTable (whose message carries two
 *    apostrophes) delivers real apostrophes after JSON.parse and no double-escape
 *    signature on the wire;
 *  - the same map round-trip through {@link WebProxySession#GSON} reproduces the
 *    OLD double-escaped wire + literal-text result, so this test would have failed
 *    on the old behavior;
 *  - the one legitimate pre-escaped site (a hand-concatenated JSON literal that no
 *    Gson pass touches) still produces valid JSON with apostrophes.
 *
 * The behavioural half drives the real handler on a real WebProxySession whose
 * xmageSession is an unconnected SessionImpl (same technique as
 * WebProxyRollbackTurnsTest): DeckLoader.parseDeckList rejects
 * {@code 4 [M10:150] Lightning Bolt} before any RPC, via the card DB at ./db.
 * That check needs the staged cards DB (scripts/stage-test-db.sh) — with no DB
 * the parser accepts the code and no error is emitted, which this test detects
 * and fails loudly rather than passing vacuously.
 */
public class WebProxyEscapeJsonTest {

    /** The literal six-character escape text a browser would show on the old bug. */
    private static final String LITERAL_ESCAPE = "\\u0027";
    /** The double-escaped wire signature the old structured path produced. */
    private static final String DOUBLE_ESCAPED = "\\\\u0027";

    private static com.google.gson.Gson gson() {
        return WebProxySession.GSON;
    }

    // ── the mechanism, pinned against the old double-serialization ──

    @Test
    public void doubleSerializationIsWhatProducedTheLiteralEscape() throws Exception {
        String error = "the line says 'Lightning Bolt'";

        // What the OLD structured path did: escapeJson first, then the map
        // serialized again by gsonToJson — the backslash got re-escaped.
        String preEscaped = invokeEscapeJson(error);
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("type", "error");
        map.put("message", preEscaped);
        String wireOld = gson().toJson(map);
        String parsedOld = JsonParser.parseString(wireOld).getAsJsonObject()
                .get("message").getAsString();

        Assert.assertTrue("the old wire carried the double-escape signature",
                wireOld.contains(DOUBLE_ESCAPED));
        Assert.assertEquals("and JSON.parse left the literal escape text in the toast",
                "the line says " + LITERAL_ESCAPE + "Lightning Bolt" + LITERAL_ESCAPE, parsedOld);

        // What the FIXED structured path does: the RAW string goes into the map and
        // Gson serializes exactly once. (Gson still html-escapes ' on the wire into
        // the six-character sequence — that is a valid JSON escape; the defect was
        // only ever what JSON.parse yielded on the browser side.)
        java.util.Map<String, Object> fixed = new java.util.HashMap<>();
        fixed.put("type", "error");
        fixed.put("message", error);
        String wireNew = gson().toJson(fixed);
        String parsedNew = JsonParser.parseString(wireNew).getAsJsonObject()
                .get("message").getAsString();

        Assert.assertEquals("one Gson pass round-trips the apostrophes", error, parsedNew);
        Assert.assertFalse("the fixed wire must NOT carry the double-escape signature",
                wireNew.contains(DOUBLE_ESCAPED));
        Assert.assertTrue("the parsed message must carry real apostrophes",
                parsedNew.contains("'Lightning Bolt'"));
    }

    // ── the real handler path: the deck-parse error for seat 0 ──

    @Test
    public void deckParseErrorReachesTheBrowserWithRealApostrophes() throws Exception {
        WebProxySession session = sessionWithUnconnectedXmage();
        WebProxyAuthCloseTest.StubSession ws = new WebProxyAuthCloseTest.StubSession();
        session.onWebSocketConnect(ws);

        // M10:150 is Panic Attack, so DeckLoader's Fail-Fast name-vs-code check
        // rejects this line with a message containing two apostrophes.
        com.google.gson.JsonObject seat = new com.google.gson.JsonObject();
        seat.addProperty("type", "HUMAN");
        seat.addProperty("deck", "4 [M10:150] Lightning Bolt");
        seat.addProperty("isYou", true);
        com.google.gson.JsonArray seats = new com.google.gson.JsonArray();
        seats.add(seat);

        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("type", "create_table");
        json.addProperty("name", "escape-json test");
        json.addProperty("gameType", "Two Player Duel");
        json.addProperty("deckType", "Constructed - Freeform");
        json.addProperty("winsNeeded", 1);
        json.add("playerConfigs", seats);
        json.addProperty("deck", "");

        invokeCreateTable(session, json);

        Assert.assertEquals("exactly one frame may be sent for a rejected create_table (the error)",
                1, ws.sent.size());
        String raw = ws.sent.get(0);
        Assert.assertTrue("the frame must be the error frame",
                raw.contains("\"type\":\"error\"") || raw.contains("\"type\": \"error\""));

        com.google.gson.JsonObject parsed = JsonParser.parseString(raw).getAsJsonObject();
        String message = parsed.get("message").getAsString();

        Assert.assertTrue("fixture sanity: the parser must actually have rejected the line"
                        + " (stage the test DB with scripts/stage-test-db.sh)",
                message.contains("Invalid decklist for seat 0"));
        Assert.assertFalse("the literal escape must not survive JSON.parse",
                message.contains(LITERAL_ESCAPE));
        Assert.assertFalse("the wire must not carry the double-escape signature either",
                raw.contains(DOUBLE_ESCAPED));
        Assert.assertTrue("the apostrophes around the card names must be REAL apostrophes",
                message.contains("the line says 'Lightning Bolt'"));
        Assert.assertTrue("...and the second pair too",
                message.contains("is 'Panic Attack'"));
    }

    /** The legacy-deck branch keeps its single escaped literal (the one legit use). */
    @Test
    public void legacyDeckLiteralStaysEscapedExactlyOnce() throws Exception {
        WebProxySession session = sessionWithUnconnectedXmage();
        WebProxyAuthCloseTest.StubSession ws = new WebProxyAuthCloseTest.StubSession();
        session.onWebSocketConnect(ws);

        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("type", "create_table");
        json.addProperty("name", "escape-json legacy");
        json.addProperty("gameType", "Two Player Duel");
        json.addProperty("deckType", "Constructed - Freeform");
        json.addProperty("winsNeeded", 1);
        json.addProperty("deck", "4 [M10:150] Lightning Bolt"); // legacy path, no playerConfigs

        invokeCreateTable(session, json);

        Assert.assertEquals("one frame for a rejected legacy deck", 1, ws.sent.size());
        String raw = ws.sent.get(0);
        // The hand-built literal must be VALID JSON: parsing proves single escaping.
        com.google.gson.JsonObject parsed = JsonParser.parseString(raw).getAsJsonObject();
        String message = parsed.get("message").getAsString();
        Assert.assertTrue("the manually built frame must parse and keep the apostrophes",
                message.contains("Invalid decklist:") && message.contains("'Lightning Bolt'"));
    }

    // ── source guard: no structured path may pre-escape again ──

    /**
     * Grep guard: every {@code escapeJson(...)} call EXCEPT the legacy hand-built
     * literal must be gone from WebProxySession — a future re-introduction on a
     * structured path would double-escape again. The file must keep exactly one
     * such call site, at the sendToBrowser string-literal site.
     */
    @Test
    public void onlyTheManualJsonLiteralStillPreEscapes() throws Exception {
        String src = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/mage/webproxy/WebProxySession.java")));
        int count = 0;
        int idx = 0;
        StringBuilder offenders = new StringBuilder();
        while ((idx = src.indexOf("escapeJson(", idx)) >= 0) {
            int lineStart = src.lastIndexOf('\n', idx) + 1;
            String line = src.substring(lineStart, src.indexOf('\n', idx));
            idx += 10;
            if (line.contains("private static String escapeJson")) {
                continue; // the definition itself
            }
            if (line.contains("escapeJson(x)")) {
                continue; // the javadoc's illustrative snippet
            }
            count++;
            if (!line.contains("sendToBrowser(")) {
                offenders.append(line.trim()).append(" | ");
            }
        }
        Assert.assertEquals("exactly ONE escapeJson call site may survive (the manual JSON literal),"
                + " but found offenders: " + offenders, 1, count);
    }

    // ── helpers ──

    private static void invokeCreateTable(WebProxySession session, com.google.gson.JsonObject json)
            throws Exception {
        Method m = WebProxySession.class.getDeclaredMethod(
                "handleCreateTable", com.google.gson.JsonObject.class);
        m.setAccessible(true);
        try {
            m.invoke(session, json);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException("handleCreateTable threw: " + e.getCause(), e.getCause());
        }
    }

    private static WebProxySession sessionWithUnconnectedXmage() throws Exception {
        WebProxySession session = new WebProxySession();
        java.lang.reflect.Field xs = WebProxySession.class.getDeclaredField("xmageSession");
        xs.setAccessible(true);
        xs.set(session, newUnconnectedXmageSession());
        java.lang.reflect.Field pc = WebProxySession.class.getDeclaredField("proxyClient");
        pc.setAccessible(true);
        pc.set(session, new WebProxyClient("tester"));
        return session;
    }

    private static String invokeEscapeJson(String s) throws Exception {
        Method m = WebProxySession.class.getDeclaredMethod("escapeJson", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s);
    }

    private static SessionImpl newUnconnectedXmageSession() {
        return new SessionImpl(new MageClient() {
            @Override public mage.utils.MageVersion getVersion() { return null; }
            @Override public void connected(String message) { }
            @Override public void disconnected(boolean askToReconnect, boolean keepMySessionActive) { }
            @Override public void showMessage(String message) { }
            @Override public void showError(String message) { }
            @Override public void onNewConnection() { }
            @Override public void onCallback(mage.interfaces.callback.ClientCallback callback) { }
        });
    }
}
