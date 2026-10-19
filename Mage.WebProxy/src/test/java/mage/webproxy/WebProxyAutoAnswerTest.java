package mage.webproxy;

import com.google.gson.JsonObject;
import mage.constants.PlayerAction;
import mage.interfaces.MageClient;
import mage.remote.SessionImpl;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Unit tests for the {@code auto_answer} browser verb (recurring
 * yes/no answers + reset surfaces, ported from the desktop client's
 * {@code HelperPanel} auto-answer submenu).
 *
 * Contract pinned here (the proxy handler's javadoc carries the wire form):
 *  - each action maps to its exact engine PlayerAction:
 *      id_yes → REQUEST_AUTO_ANSWER_ID_YES, id_no → …_ID_NO,
 *      text_yes → …_TEXT_YES, text_no → …_TEXT_NO,
 *      reset_all → REQUEST_AUTO_ANSWER_RESET_ALL,
 *      reset_replacement → RESET_AUTO_SELECT_REPLACEMENT_EFFECTS;
 *  - the DATA payload is the engine's own key material
 *      id variants:  originalId + '#' + message
 *      text variants: message
 *      resets:        null
 *    (HumanPlayer.chooseUse:455-465 looks the remembered answer up under exactly
 *    these keys — a different join makes the memory a silent no-op);
 *  - malformed input fails fast with a browser error and NO PlayerAction on the
 *    wire: unknown action, missing/empty message, missing originalId on an id
 *    variant, originalId that is not a UUID, missing action, no active game.
 *
 * Same technique as WebProxyRollbackTurnsTest / WebProxyTokenArtPreferenceTest:
 * a real WebProxySession whose xmageSession is a recording SessionImpl stand-in
 * (no server) and whose proxyClient carries a game id via reflection. The real
 * handler runs; the captures are the assertions.
 */
public class WebProxyAutoAnswerTest {

    /** The action calls the handler put on the wire. */
    static final class SentAction {
        final PlayerAction action;
        final UUID gameId;
        final Object data;

        SentAction(PlayerAction action, UUID gameId, Object data) {
            this.action = action;
            this.gameId = gameId;
            this.data = data;
        }
    }

    /** Stand-in for the XMage server session: records sendPlayerAction calls. */
    static class RecordingXmageSession extends SessionImpl {
        final List<SentAction> sentActions = new ArrayList<>();

        RecordingXmageSession() {
            super(new MageClient() {
                @Override public mage.utils.MageVersion getVersion() { return null; }
                @Override public void connected(String message) { }
                @Override public void disconnected(boolean askToReconnect, boolean keepMySessionActive) { }
                @Override public void showMessage(String message) { }
                @Override public void showError(String message) { }
                @Override public void onNewConnection() { }
                @Override public void onCallback(mage.interfaces.callback.ClientCallback callback) { }
            });
        }

        @Override
        public boolean sendPlayerAction(PlayerAction playerAction, UUID gameId, Object data) {
            sentActions.add(new SentAction(playerAction, gameId, data));
            return true;
        }
    }

    // ── fixtures ──

    private static WebProxySession newSession(RecordingXmageSession xmage, UUID gameId) throws Exception {
        WebProxySession session = new WebProxySession();
        setField(session, WebProxySession.class, "xmageSession", xmage);
        WebProxyClient client = new WebProxyClient("tester");
        setField(session, WebProxySession.class, "proxyClient", client);
        if (gameId != null) {
            setField(client, WebProxyClient.class, "currentGameId", gameId);
        }
        return session;
    }

    private static void setField(Object target, Class<?> cls, String field, Object value) throws Exception {
        Field f = cls.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void invokeAutoAnswer(WebProxySession session, JsonObject json) throws Exception {
        Method m = WebProxySession.class.getDeclaredMethod("handleAutoAnswer", JsonObject.class);
        m.setAccessible(true);
        m.invoke(session, json);
    }

    private static JsonObject json(String action, String originalId, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "auto_answer");
        if (action != null) o.addProperty("action", action);
        if (originalId != null) o.addProperty("originalId", originalId);
        if (message != null) o.addProperty("message", message);
        return o;
    }

    /** A boolean prompt's auto-answer key material. */
    static final class PromptPayload {
        String originalId;
        String autoAnswerMessage;
        String message;
        String context;
    }

    /**
     * Drive the REAL handleGameAsk with an options map shaped like the engine's
     * (HumanPlayer.chooseUse → PlayerQueryEvent.askEvent → GAME_ASK), and return
     * the JSON the browser received.
     */
    private static PromptPayload invokeHandleGameAsk(java.util.Map<String, java.io.Serializable> options,
                                                     String question) throws Exception {
        WebProxyClient client = new WebProxyClient("tester");
        RecordingSender sender = new RecordingSender();
        client.setWsSender(sender);

        mage.view.GameClientMessage gcm = new mage.view.GameClientMessage(null, options, question);
        Method m = WebProxyClient.class.getDeclaredMethod("handleGameAsk",
                mage.view.GameClientMessage.class, mage.view.GameView.class);
        m.setAccessible(true);
        m.invoke(client, gcm, null);

        Assert.assertEquals("exactly one prompt pushed", 1, sender.sent.size());
        JsonObject msg = com.google.gson.JsonParser.parseString(sender.sent.get(0)).getAsJsonObject();
        Assert.assertEquals("action_prompt", msg.get("type").getAsString());
        Assert.assertEquals("boolean", msg.get("action_type").getAsString());

        PromptPayload payload = new PromptPayload();
        payload.message = msg.get("message").getAsString();
        payload.context = msg.has("context") ? msg.get("context").getAsString() : null;
        payload.originalId = msg.has("original_id") ? msg.get("original_id").getAsString() : null;
        payload.autoAnswerMessage = msg.has("auto_answer_message") ? msg.get("auto_answer_message").getAsString() : null;
        return payload;
    }

    /** Browser-send recorder (same shape as WebProxyChooseChoiceSerializerTest). */
    static final class RecordingSender implements WebProxyClient.BrowserSender {
        final List<String> sent = new ArrayList<>();

        @Override
        public boolean send(String json) {
            sent.add(json);
            return true;
        }
    }

    private static java.util.Map<String, java.io.Serializable> askOptions(UUID originalId, String autoAnswerMessage) {
        java.util.Map<String, java.io.Serializable> options = new java.util.HashMap<>();
        options.put("UI.left.btn.text", "Yes");
        options.put("UI.right.btn.text", "No");
        if (originalId != null) {
            options.put(mage.constants.Constants.Option.ORIGINAL_ID, originalId);
        }
        if (autoAnswerMessage != null) {
            options.put(mage.constants.Constants.Option.AUTO_ANSWER_MESSAGE, autoAnswerMessage);
        }
        return options;
    }

    // ── the ORIGINAL-ID finding, pinned: the proxy now carries the key material ──

    /**
     * The proxy used to drop both options; without them no browser-built key could
     * ever match the engine's lookup. Both fields must now ride the boolean prompt.
     */
    @Test
    public void booleanPromptCarriesOriginalIdAndAutoAnswerMessage() throws Exception {
        UUID id = UUID.fromString(ORIGINAL_ID);
        PromptPayload p = invokeHandleGameAsk(askOptions(id, "Put {this} onto the battlefield?"),
                "Put Grizzly Bears onto the battlefield?");
        Assert.assertEquals("originalId must ride the prompt (the id-variant key needs it)",
                ORIGINAL_ID, p.originalId);
        Assert.assertEquals("the engine's own filtered autoAnswerMessage must ride the prompt",
                "Put {this} onto the battlefield?", p.autoAnswerMessage);
    }

    /**
     * A source-less ask (chooseMulligan) carries no ORIGINAL_ID and no
     * AUTO_ANSWER_MESSAGE; the desktop falls back to the displayed text
     * (FeedbackPanel.java:106-111), and the browser must then be offered only the
     * text variant. The fallback value must be exactly the message the engine keys on.
     */
    @Test
    public void sourceLessAskFallsBackToTheDisplayedQuestion() throws Exception {
        java.util.Map<String, java.io.Serializable> options = new java.util.HashMap<>();
        options.put("UI.left.btn.text", "Mulligan");
        options.put("UI.right.btn.text", "Keep");
        PromptPayload p = invokeHandleGameAsk(options, "Mulligan down to 6 cards?");
        Assert.assertNull("a source-less ask has no ability id", p.originalId);
        Assert.assertEquals("the displayed question is the text-variant key here",
                "Mulligan down to 6 cards?", p.autoAnswerMessage);
        Assert.assertEquals("mulligan context still detected", "mulligan", p.context);
    }

    /** An ask with an id but no filtered message keeps the id and keys text on the question. */
    @Test
    public void idOnlyAskCarriesIdAndQuestionAsTextKey() throws Exception {
        UUID id = UUID.fromString(ORIGINAL_ID);
        PromptPayload payload = invokeHandleGameAsk(askOptions(id, null), "Do the thing?");
        Assert.assertEquals(ORIGINAL_ID, payload.originalId);
        Assert.assertEquals("Do the thing?", payload.autoAnswerMessage);
    }

    private static final String ORIGINAL_ID = "11111111-2222-3333-4444-555555555555";
    private static final String MESSAGE = "Put {this} onto the battlefield?";

    // ── the happy paths: action → PlayerAction + exact data payload ──

    @Test
    public void idYesSendsIdKeyWithHashJoinAndIdPlayerAction() throws Exception {
        RecordingXmageSession xmage = new RecordingXmageSession();
        UUID gameId = UUID.randomUUID();
        invokeAutoAnswer(newSession(xmage, gameId), json("id_yes", ORIGINAL_ID, MESSAGE));

        Assert.assertEquals("exactly one action on the wire", 1, xmage.sentActions.size());
        SentAction sent = xmage.sentActions.get(0);
        Assert.assertEquals(PlayerAction.REQUEST_AUTO_ANSWER_ID_YES, sent.action);
        Assert.assertEquals(gameId, sent.gameId);
        Assert.assertEquals("id variants key on originalId + '#' + message (HumanPlayer.chooseUse:459-460)",
                ORIGINAL_ID + "#" + MESSAGE, sent.data);
    }

    @Test
    public void idNoSendsIdKeyWithIdNoPlayerAction() throws Exception {
        RecordingXmageSession xmage = new RecordingXmageSession();
        invokeAutoAnswer(newSession(xmage, UUID.randomUUID()), json("id_no", ORIGINAL_ID, MESSAGE));

        Assert.assertEquals(1, xmage.sentActions.size());
        SentAction sent = xmage.sentActions.get(0);
        Assert.assertEquals(PlayerAction.REQUEST_AUTO_ANSWER_ID_NO, sent.action);
        Assert.assertEquals(ORIGINAL_ID + "#" + MESSAGE, sent.data);
    }

    @Test
    public void textYesSendsBareMessageWithTextYesPlayerAction() throws Exception {
        RecordingXmageSession xmage = new RecordingXmageSession();
        invokeAutoAnswer(newSession(xmage, UUID.randomUUID()), json("text_yes", null, MESSAGE));

        Assert.assertEquals(1, xmage.sentActions.size());
        SentAction sent = xmage.sentActions.get(0);
        Assert.assertEquals(PlayerAction.REQUEST_AUTO_ANSWER_TEXT_YES, sent.action);
        Assert.assertEquals("text variants key on the bare message (HumanPlayer.chooseUse:464)",
                MESSAGE, sent.data);
    }

    @Test
    public void textNoSendsBareMessageWithTextNoPlayerAction() throws Exception {
        RecordingXmageSession xmage = new RecordingXmageSession();
        invokeAutoAnswer(newSession(xmage, UUID.randomUUID()), json("text_no", null, MESSAGE));

        Assert.assertEquals(1, xmage.sentActions.size());
        SentAction sent = xmage.sentActions.get(0);
        Assert.assertEquals(PlayerAction.REQUEST_AUTO_ANSWER_TEXT_NO, sent.action);
        Assert.assertEquals(MESSAGE, sent.data);
    }

    /** A text variant must IGNORE a supplied originalId — its key is the message alone. */
    @Test
    public void textVariantIgnoresOriginalId() throws Exception {
        RecordingXmageSession xmage = new RecordingXmageSession();
        invokeAutoAnswer(newSession(xmage, UUID.randomUUID()), json("text_yes", ORIGINAL_ID, MESSAGE));

        Assert.assertEquals(MESSAGE, xmage.sentActions.get(0).data);
    }

    /** The '#' join must not consume a message that itself contains '#' — both survive verbatim. */
    @Test
    public void hashJoinKeepsMessageBytesVerbatim() throws Exception {
        RecordingXmageSession xmage = new RecordingXmageSession();
        String tricky = "Mulligan # draw # another?";
        invokeAutoAnswer(newSession(xmage, UUID.randomUUID()), json("id_yes", ORIGINAL_ID, tricky));

        Assert.assertEquals(ORIGINAL_ID + "#" + tricky, xmage.sentActions.get(0).data);
    }

    // ── reset paths: no data ──

    @Test
    public void resetAllSendsResetAllWithNullData() throws Exception {
        RecordingXmageSession xmage = new RecordingXmageSession();
        invokeAutoAnswer(newSession(xmage, UUID.randomUUID()), json("reset_all", null, null));

        Assert.assertEquals(1, xmage.sentActions.size());
        SentAction sent = xmage.sentActions.get(0);
        Assert.assertEquals(PlayerAction.REQUEST_AUTO_ANSWER_RESET_ALL, sent.action);
        Assert.assertNull("resets carry null data (HumanPlayer.setRequestAutoAnswer clears both maps)", sent.data);
    }

    @Test
    public void resetReplacementSendsReplacementResetWithNullData() throws Exception {
        RecordingXmageSession xmage = new RecordingXmageSession();
        invokeAutoAnswer(newSession(xmage, UUID.randomUUID()), json("reset_replacement", null, null));

        Assert.assertEquals(1, xmage.sentActions.size());
        SentAction sent = xmage.sentActions.get(0);
        Assert.assertEquals(PlayerAction.RESET_AUTO_SELECT_REPLACEMENT_EFFECTS, sent.action);
        Assert.assertNull(sent.data);
    }

    /** Resets ignore stray key material instead of silently keying something. */
    @Test
    public void resetsIgnoreStrayKeyFields() throws Exception {
        RecordingXmageSession xmage = new RecordingXmageSession();
        invokeAutoAnswer(newSession(xmage, UUID.randomUUID()), json("reset_all", ORIGINAL_ID, MESSAGE));

        Assert.assertEquals(PlayerAction.REQUEST_AUTO_ANSWER_RESET_ALL, xmage.sentActions.get(0).action);
        Assert.assertNull(xmage.sentActions.get(0).data);
    }

    // ── fail fast: malformed input sends NOTHING ──

    @Test
    public void unknownActionIsRefused() throws Exception {
        RecordingXmageSession xmage = new RecordingXmageSession();
        invokeAutoAnswer(newSession(xmage, UUID.randomUUID()), json("id_maybe", ORIGINAL_ID, MESSAGE));
        Assert.assertTrue("an unknown action must not reach the engine", xmage.sentActions.isEmpty());
    }

    @Test
    public void missingActionIsRefused() throws Exception {
        RecordingXmageSession xmage = new RecordingXmageSession();
        invokeAutoAnswer(newSession(xmage, UUID.randomUUID()), json(null, ORIGINAL_ID, MESSAGE));
        Assert.assertTrue(xmage.sentActions.isEmpty());
    }

    @Test
    public void missingMessageIsRefusedForAllFourVariants() throws Exception {
        for (String action : new String[]{"id_yes", "id_no", "text_yes", "text_no"}) {
            RecordingXmageSession xmage = new RecordingXmageSession();
            invokeAutoAnswer(newSession(xmage, UUID.randomUUID()), json(action, ORIGINAL_ID, null));
            Assert.assertTrue("action " + action + " without a message must not reach the engine",
                    xmage.sentActions.isEmpty());
        }
    }

    @Test
    public void emptyMessageIsRefused() throws Exception {
        RecordingXmageSession xmage = new RecordingXmageSession();
        invokeAutoAnswer(newSession(xmage, UUID.randomUUID()), json("text_yes", null, ""));
        Assert.assertTrue("an empty message must not become a stored key", xmage.sentActions.isEmpty());
    }

    @Test
    public void idVariantWithoutOriginalIdIsRefused() throws Exception {
        for (String action : new String[]{"id_yes", "id_no"}) {
            RecordingXmageSession xmage = new RecordingXmageSession();
            invokeAutoAnswer(newSession(xmage, UUID.randomUUID()), json(action, null, MESSAGE));
            Assert.assertTrue("action " + action + " without originalId must not guess a key",
                    xmage.sentActions.isEmpty());
        }
    }

    @Test
    public void idVariantWithNonUuidOriginalIdIsRefused() throws Exception {
        RecordingXmageSession xmage = new RecordingXmageSession();
        invokeAutoAnswer(newSession(xmage, UUID.randomUUID()), json("id_yes", "not-a-uuid", MESSAGE));
        Assert.assertTrue("a malformed id must be refused, not stored as a dead key",
                xmage.sentActions.isEmpty());
    }

    // ── the no-game guards (same shape as every other player-only verb) ──

    @Test
    public void noActiveGameIsRefused() throws Exception {
        RecordingXmageSession xmage = new RecordingXmageSession();
        invokeAutoAnswer(newSession(xmage, null), json("id_yes", ORIGINAL_ID, MESSAGE));
        Assert.assertTrue("with no current game id nothing may be sent", xmage.sentActions.isEmpty());
    }

    @Test
    public void noXmageSessionIsRefused() throws Exception {
        WebProxySession session = new WebProxySession();
        // proxyClient deliberately absent too — the handler's first guard fires.
        invokeAutoAnswer(session, json("reset_all", null, null));
        // Reaching here without a throw is the assertion: the guard returned early.
        Assert.assertTrue(true);
    }

    // ── dispatch-level guard: the verb is registered and player-only ──

    /**
     * `auto_answer` must be in PLAYER_ONLY_ACTIONS and dispatched from the WS switch —
     * a verb that answers prompts is player-only, exactly like boolean_choice, and an
     * unregistered type would be refused as "Unknown message type".
     *
     * <p>The membership check reads the real set by reflection (like
     * WebProxyViewLimitedDeckTest / WebProxyHandPermissionTest do) instead of pinning
     * the set's SOURCE TEXT: a text pin that assumed auto_answer was the last entry
     * broke when a later change added sibling verbs after it, even though the
     * membership itself was intact — reflection checks what the class actually holds.
     */
    @Test
    public void verbIsRegisteredAndSpectatorGated() throws Exception {
        String src = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/mage/webproxy/WebProxySession.java")));
        Assert.assertTrue("the WS dispatch switch must route auto_answer",
                src.contains("case \"auto_answer\":"));
        Assert.assertTrue("auto_answer must be dispatched to handleAutoAnswer",
                src.contains("handleAutoAnswer(json)"));
        java.lang.reflect.Field f = WebProxySession.class.getDeclaredField("PLAYER_ONLY_ACTIONS");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<String> playerOnly = (java.util.Set<String>) f.get(null);
        Assert.assertTrue("a prompt answer is a player-only action (PLAYER_ONLY_ACTIONS)",
                playerOnly.contains("auto_answer"));
        Assert.assertTrue("the handler must exist",
                src.contains("private void handleAutoAnswer(com.google.gson.JsonObject json)"));
    }
}
