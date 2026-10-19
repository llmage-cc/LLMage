package mage.webproxy;

import mage.players.PlayerType;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Regression tests for the model-list error text shown in the New Game dialog

 *
 * <p>The reported incident: clicking the ↻ reload next to the Model field popped a
 * red banner rendering raw OPERATOR jargon at a player —
 * {@code "Endpoint host 'localhost' is not in the model-endpoint allowlist.
 * Configure -Dwebproxy.allowedModelHosts=host1,host2 to add hosts."} — and naming the
 * WRONG host ('localhost' for a seat whose endpoint is the opencode.ai server
 * default). Two proxy defects are pinned here:
 * <ul>
 *   <li>the allowlist rejection passed the {@code -D} flag text straight through to
 *       the browser; it must be player-facing (host named, operator detail logged,
 *       no JVM flags, no "allowlist" jargon);</li>
 *   <li>an empty endpoint (the seat's "(server default)" field) answered with a bare
 *       {@code "No endpoint provided"} instead of telling the player the seat uses
 *       the server's configured default — text that invited the frontend to
 *       fabricate a localhost default and get the wrong-host banner.</li>
 * </ul>
 *
 * <p>Same technique as {@link WebProxyFetchRateLimitTest}: the REAL
 * {@code onWebSocketText} dispatcher runs on a real WebProxySession whose
 * {@code sendToBrowser} is a recording override and whose capabilities support LLM.
 * A FRESH session per call keeps each fetch out of the other's rate-limit window.
 */
public class WebProxyModelsErrorTextTest {

    /** Capture what the session pushes to the browser. */
    private static final class RecordingSession extends WebProxySession {
        final List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        boolean sendToBrowser(String json) {
            sent.add(json);
            return true;
        }

        List<com.google.gson.JsonObject> frames(String type) {
            List<com.google.gson.JsonObject> out = new ArrayList<>();
            for (String s : sent) {
                com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(s).getAsJsonObject();
                if (type.equals(o.get("type").getAsString())) {
                    out.add(o);
                }
            }
            return out;
        }

        /** The single models_error message this session produced. */
        String onlyModelsErrorMessage() {
            List<com.google.gson.JsonObject> errs = frames("models_error");
            Assert.assertEquals("exactly one models_error frame expected, sent=" + sent, 1, errs.size());
            return errs.get(0).get("message").getAsString();
        }
    }

    @After
    public void clearProps() {
        System.clearProperty("webproxy.allowedModelHosts");
    }

    private static RecordingSession newLlmCapableSession() throws Exception {
        RecordingSession session = new RecordingSession();
        Field caps = WebProxySession.class.getDeclaredField("serverCapabilities");
        caps.setAccessible(true);
        caps.set(session, ServerCapabilities.forTest(null,
                new ArrayList<>(Collections.singletonList(PlayerType.COMPUTER_LLM)),
                true, true, true));
        return session;
    }

    private static String fetchModelsError(String endpointJson) throws Exception {
        RecordingSession session = newLlmCapableSession();
        session.onWebSocketText("{\"type\":\"fetch_models\",\"endpoint\":\""
                + endpointJson.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
        return session.onlyModelsErrorMessage();
    }

    /** No frame in a batch may contain operator jargon. */
    private static void assertNoOperatorJargon(String message) {
        Assert.assertFalse("a player-facing model error must not name a JVM flag: " + message,
                message.contains("-D"));
        Assert.assertFalse("a player-facing model error must not name the allowlist property: " + message,
                message.contains("allowedModelHosts"));
        Assert.assertFalse("a player-facing model error must not use 'allowlist' jargon: " + message,
                message.toLowerCase().contains("allowlist"));
    }

    // ── the wrong-host allowlist banner ──

    @Test
    public void unallowedHostErrorNamesTheHostWithoutJvmJargon() throws Exception {
        String message = fetchModelsError("https://blocked-provider.example/v1/chat/completions");

        Assert.assertTrue("the blocked host IS the actionable part and must be named",
                message.contains("blocked-provider.example"));
        assertNoOperatorJargon(message);
    }

    // ── the empty seat endpoint ("(server default)") ──

    @Test
    public void missingSeatEndpointGivesPlainGuidanceNotNoEndpointProvided() throws Exception {
        String message = fetchModelsError("");

        Assert.assertNotEquals("the empty-endpoint reply must explain the server default, "
                + "not the bare 'No endpoint provided'", "No endpoint provided", message);
        Assert.assertTrue("the text must say the seat uses the server's configured default: " + message,
                message.toLowerCase().contains("default"));
        assertNoOperatorJargon(message);
    }

    // ── the invariant both defects violate: nothing operator-facing reaches a player ──

    @Test
    public void noPlayerFacingModelErrorLeaksJvmFlags() throws Exception {
        assertNoOperatorJargon(fetchModelsError("https://blocked-provider.example/v1/chat/completions"));
        assertNoOperatorJargon(fetchModelsError(""));
    }
}
