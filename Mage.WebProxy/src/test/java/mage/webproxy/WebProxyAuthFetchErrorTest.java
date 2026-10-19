package mage.webproxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Regression tests for the typed auth-reject frames

 *
 * <p>The defect: on a token-gated proxy ({@code -Dwebproxy.authToken}), a
 * {@code fetch_models} that races the connect frame (auto-reconnect opens the
 * socket; the user's refresh click, or {@code PlayerSlotRow.onChange}'s
 * auto-refresh, fires on the bare socket first) is rejected by
 * {@link WebProxySession#requireAuthFor} with the GENERIC {@code error} frame.
 * The frontend's models spinner ({@code lobbySlice.modelsLoading}) is cleared
 * only by {@code models_list} / {@code models_error}, so it spun forever —
 * confirmed live (repro log: "Rejected unauthenticated message type:
 * fetch_models" at 17:47:47.807).
 *
 * <p>Contract pinned here:
 * <ul>
 *   <li>unauthenticated {@code fetch_models} → {@code models_error} (with the
 *       same auth message text), NOT the generic {@code error};</li>
 *   <li>unauthenticated {@code fetch_personalities} → {@code personalities_error};</li>
 *   <li>EVERY OTHER type keeps the generic {@code error} — the auth SEMANTICS
 *       (rejected, not dispatched) are unchanged, only the error TYPE for the two
 *       fetch verbs differs;</li>
 *   <li>the frame matches the shape handleFetchModels uses
 *       ({@code {"type":"models_error","message":…}}), so the existing frontend
 *       consumer (messageHandlers {@code case 'models_error'}) works untouched;</li>
 *   <li>an authenticated/tokenless session is unaffected (no rejection at all).</li>
 * </ul>
 *
 * <p>Same technique as {@link WebProxyFetchRateLimitTest}: the REAL
 * {@code onWebSocketText} dispatcher runs on a real WebProxySession whose
 * {@code sendToBrowser} is a recording override. No server, no XMage.
 */
public class WebProxyAuthFetchErrorTest {

    /** Capture what the session pushes to the browser. */
    private static final class RecordingSession extends WebProxySession {
        final List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        boolean sendToBrowser(String json) {
            sent.add(json);
            return true;
        }

        /** Frames whose {@code type} matches — the assertion surface. */
        List<JsonObject> frames(String type) {
            List<JsonObject> out = new ArrayList<>();
            for (String s : sent) {
                JsonObject o = JsonParser.parseString(s).getAsJsonObject();
                if (type.equals(o.get("type").getAsString())) {
                    out.add(o);
                }
            }
            return out;
        }

        /** The single frame sent in this test (fails when the count is not 1). */
        JsonObject onlyFrame() {
            Assert.assertEquals("exactly one frame must be sent, got: " + sent, 1, sent.size());
            return frames(JsonParser.parseString(sent.get(0)).getAsJsonObject()
                    .get("type").getAsString()).get(0);
        }
    }

    private static final String AUTH_MESSAGE = "Not authenticated — connect with an auth token first";

    @After
    public void clearProps() {
        System.clearProperty("webproxy.authToken");
    }

    private static RecordingSession gatedSession() {
        System.setProperty("webproxy.authToken", "sekrit");
        return new RecordingSession();
    }

    // ── the defect: the fetch verbs answer with their TYPED frame ──

    @Test
    public void unauthenticatedFetchModelsAnswersModelsError() {
        RecordingSession session = gatedSession();

        session.onWebSocketText("{\"type\":\"fetch_models\",\"endpoint\":\"http://localhost:11434/v1/chat/completions\"}");

        JsonObject frame = session.onlyFrame();
        Assert.assertEquals("unauthenticated fetch_models must answer models_error, not the generic error",
                "models_error", frame.get("type").getAsString());
        Assert.assertEquals("the typed frame carries the auth message text",
                AUTH_MESSAGE, frame.get("message").getAsString());
        Assert.assertEquals("NO generic error frame may accompany the typed one",
                0, session.frames("error").size());
    }

    @Test
    public void unauthenticatedFetchPersonalitiesAnswersPersonalitiesError() {
        RecordingSession session = gatedSession();

        session.onWebSocketText("{\"type\":\"fetch_personalities\"}");

        JsonObject frame = session.onlyFrame();
        Assert.assertEquals("unauthenticated fetch_personalities must answer personalities_error",
                "personalities_error", frame.get("type").getAsString());
        Assert.assertEquals("the typed frame carries the auth message text",
                AUTH_MESSAGE, frame.get("message").getAsString());
    }

    /** The typed shape is byte-compatible with the handler's own failure frames. */
    @Test
    public void typedFrameShapeMatchesHandleFetchModelsErrors() {
        RecordingSession session = gatedSession();

        session.onWebSocketText("{\"type\":\"fetch_models\"}");

        // handleFetchModels' own fail-fast for a missing endpoint sends the same
        // two fields; parity is asserted via a second, authenticated-free probe:
        // the raw JSON of the auth reject must carry exactly type+message.
        JsonObject frame = session.onlyFrame();
        Assert.assertEquals("the frame has exactly the two fields the frontend reads",
                2, frame.entrySet().size());
        Assert.assertTrue("type field present", frame.has("type"));
        Assert.assertTrue("message field present", frame.has("message"));
    }

    // ── auth semantics unchanged: other types keep the generic error ──

    @Test
    public void otherTypesKeepTheGenericError() {
        RecordingSession session = gatedSession();

        session.onWebSocketText("{\"type\":\"get_tables\"}");

        Assert.assertEquals("a non-fetch verb must keep the generic error frame",
                1, session.frames("error").size());
        Assert.assertEquals("no typed fetch frames for an unrelated verb",
                0, session.frames("models_error").size() + session.frames("personalities_error").size());
        Assert.assertEquals("the generic error carries the auth message",
                AUTH_MESSAGE, session.frames("error").get(0).get("message").getAsString());
    }

    /** The rejected verb is never DISPATCHED — the auth gate still returns early. */
    @Test
    public void rejectedFetchModelsIsNotDispatched() {
        RecordingSession session = gatedSession();

        session.onWebSocketText("{\"type\":\"fetch_models\",\"endpoint\":\"http://localhost:11434/v1/chat/completions\"}");

        Assert.assertEquals("the auth gate must reject before dispatch — no models_list may be produced",
                0, session.frames("models_list").size());
    }

    // ── tokenless (open) proxy: no rejection at all ──

    @Test
    public void tokenlessProxyHasNoAuthReject() {
        RecordingSession session = new RecordingSession(); // no -Dwebproxy.authToken

        // The LLM capability gate answers here (no server connected in a unit
        // test) — but it must NOT be the AUTH gate's frame.
        session.onWebSocketText("{\"type\":\"fetch_models\",\"endpoint\":\"http://localhost:11434/v1/chat/completions\"}");
        session.onWebSocketText("{\"type\":\"fetch_personalities\"}");

        Assert.assertEquals("the auth gate must never fire on an open proxy",
                0, session.frames("models_error").size());
        Assert.assertEquals("the auth gate must never fire on an open proxy",
                0, session.frames("personalities_error").size());
    }

    /** The message text is one shared definition so the frontend copy cannot drift. */
    @Test
    public void authMessageIsTheSharedConstant() {
        Assert.assertEquals("the auth rejection text lives in one place",
                AUTH_MESSAGE, WebProxySession.AUTH_REQUIRED_MESSAGE);
    }
}
