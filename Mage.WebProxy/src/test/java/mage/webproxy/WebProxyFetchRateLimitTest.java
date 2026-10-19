package mage.webproxy;

import mage.players.PlayerType;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Regression tests for the fetch-bucket rate limiter's PER-TYPE windows
 * the test change:
 * {@code the earlier test evidence}).
 *
 * <p>Before this change ONE shared {@code lastFetchAt} window covered both
 * {@code fetch_models} and {@code fetch_personalities}, while the frontend
 * primes BOTH verbs in the SAME tick when the New Game / Tournament dialog
 * opens (lobbySlice {@code showGameSetup} / {@code showTournamentSetupDialog}).
 * The second of the pair was answered with a user-facing
 * {@code error} frame ("Rate limit — wait before fetching models again"), which
 * the browser renders as a console error + red toast on a perfectly normal
 * path.
 *
 * <p>Tracing (the fix's premise, done in the handler, not assumed):
 * <ul>
 *   <li>{@link WebProxySession#handleFetchModels} DIALS the LLM endpoint
 *       (fetchModelsFromEndpoint on the bounded background pool) — the verb the
 *       bucket exists for;</li>
 *   <li>{@code handleFetchPersonalities} is a LOCAL registry read
 *       ({@code Personality.allNames()}) that serializes a list back — cheap,
 *       but still a full frame per request.</li>
 * </ul>
 * Both verbs therefore keep a rate limit; each gets its OWN window, so the
 * dialog-open pair passes while a burst of EITHER verb is still rejected loudly
 * (a dropped request must stay surfaced — Fail Fast).
 *
 * <p>Same technique as {@link WebProxyViewLimitedDeckTest}: the REAL
 * {@code onWebSocketText} dispatcher runs on a real WebProxySession whose
 * {@code sendToBrowser} is a recording override and whose capabilities support
 * LLM (the gate would otherwise reject both verbs). No server, no XMage.
 */
public class WebProxyFetchRateLimitTest {

    /** Capture what the session pushes to the browser. */
    private static final class RecordingSession extends WebProxySession {
        final java.util.List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        boolean sendToBrowser(String json) {
            sent.add(json);
            return true;
        }

        /** Frames whose {@code type} matches — the assertion surface. */
        java.util.List<com.google.gson.JsonObject> frames(String type) {
            java.util.List<com.google.gson.JsonObject> out = new ArrayList<>();
            for (String s : sent) {
                com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(s).getAsJsonObject();
                if (type.equals(o.get("type").getAsString())) {
                    out.add(o);
                }
            }
            return out;
        }

        /** Rate-limit rejections seen on the wire. */
        java.util.List<String> rateLimitMessages() {
            java.util.List<String> out = new ArrayList<>();
            for (com.google.gson.JsonObject o : frames("error")) {
                String message = o.get("message").getAsString();
                if (message.startsWith("Rate limit")) {
                    out.add(message);
                }
            }
            return out;
        }
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

    /** An endpoint that passes the allowlist and fails fast on connect (port 1, refused). */
    private static final String REFUSED_ENDPOINT = "http://localhost:1/v1/chat/completions";

    private static void sendText(WebProxySession session, String json) {
        session.onWebSocketText(json);
    }

    private static void sendFetchModels(WebProxySession session) {
        sendText(session, "{\"type\":\"fetch_models\",\"endpoint\":\"" + REFUSED_ENDPOINT + "\"}");
    }

    // ── the defect: the dialog-open pair must both get through ──

    /**
     * The exact failing sequence: the frontend primes both verbs in one tick on
     * dialog open. BOTH must be served — no rate-limit error for either.
     */
    @Test
    public void dialogOpenPairIsNotRateLimited() throws Exception {
        RecordingSession session = newLlmCapableSession();

        sendFetchModels(session);   // first of the pair (dials in the background)
        sendFetchPersonalities(session); // second of the pair, same tick

        Assert.assertEquals("the pair must produce no rate-limit rejection",
                0, session.rateLimitMessages().size());
        Assert.assertEquals("personalities must arrive", 1, session.frames("personalities_list").size());
    }

    /** The pair in the other order must pass too (order-independence). */
    @Test
    public void dialogOpenPairInReverseOrderIsNotRateLimited() throws Exception {
        RecordingSession session = newLlmCapableSession();

        sendFetchPersonalities(session);
        sendFetchModels(session);

        Assert.assertEquals("the reversed pair must produce no rate-limit rejection",
                0, session.rateLimitMessages().size());
        Assert.assertEquals("personalities must arrive", 1, session.frames("personalities_list").size());
    }

    // ── the protection must remain: a storm of ONE verb is still rejected ──

    /**
     * Positive control: spamming {@code fetch_models} (the verb that dials the
     * endpoint) inside the window still gets the loud rejection — the bucket is
     * not "removed", only per-typed.
     */
    @Test
    public void modelsStormIsStillRateLimited() throws Exception {
        RecordingSession session = newLlmCapableSession();

        sendFetchModels(session);
        sendFetchModels(session); // within FETCH_MIN_INTERVAL_MS

        java.util.List<String> limited = session.rateLimitMessages();
        Assert.assertEquals("the second models fetch in the window must be rejected", 1, limited.size());
        Assert.assertTrue("the error names the verb so the surface is diagnosable",
                limited.get(0).contains("fetch_models"));
    }

    /** The personalities verb keeps its own storm protection as well. */
    @Test
    public void personalitiesStormIsStillRateLimited() throws Exception {
        RecordingSession session = newLlmCapableSession();

        sendFetchPersonalities(session);
        sendFetchPersonalities(session);

        java.util.List<String> limited = session.rateLimitMessages();
        Assert.assertEquals("the second personalities fetch in the window must be rejected", 1, limited.size());
        Assert.assertTrue("the error names the verb",
                limited.get(0).contains("fetch_personalities"));
        Assert.assertEquals("the first one was served", 1, session.frames("personalities_list").size());
    }

    /**
     * The windows are INDEPENDENT: one accepted fetch of each verb consumes only
     * its own bucket, so a following fetch of a verb whose window was already
     * used is the one rejected — proving the pair no longer shares a window.
     */
    @Test
    public void bucketsAreIndependentPerVerb() throws Exception {
        RecordingSession session = newLlmCapableSession();

        sendFetchPersonalities(session); // consumes the personalities window
        sendFetchModels(session);        // consumes the models window (different bucket)
        Assert.assertEquals("neither verb may be rejected for the other's use",
                0, session.rateLimitMessages().size());

        // A THIRD send of either verb is rejected by ITS OWN bucket alone.
        sendFetchPersonalities(session);
        java.util.List<String> limited = session.rateLimitMessages();
        Assert.assertEquals("the repeat personalities fetch must be rejected", 1, limited.size());
        Assert.assertTrue("and it must be the personalities window that fired",
                limited.get(0).contains("fetch_personalities"));
    }

    /** Both verbs stay on the limited list — the protection is not removed. */
    @Test
    public void bothFetchVerbsRemainRateLimited() throws Exception {
        Field f = WebProxySession.class.getDeclaredField("FETCH_RATE_LIMITED");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<String> limited = (java.util.Set<String>) f.get(null);
        Assert.assertTrue("fetch_models must stay limited", limited.contains("fetch_models"));
        Assert.assertTrue("fetch_personalities must stay limited", limited.contains("fetch_personalities"));
    }

    private static void sendFetchPersonalities(WebProxySession session) {
        sendText(session, "{\"type\":\"fetch_personalities\"}");
    }
}
