package mage.webproxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.cards.Card;
import mage.cards.decks.Deck;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.interfaces.MageClient;
import mage.interfaces.callback.ClientCallback;
import mage.interfaces.callback.ClientCallbackMethod;
import mage.remote.SessionImpl;
import mage.view.TableClientMessage;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Unit tests for the "view deck (limited pool)" wire path

 *
 * Before this change the VIEW_LIMITED_DECK callback sent an EMPTY payload
 * ({@code jsonMessage("view_limited_deck", "")}) and the browser handler was a
 * console.log stub ("not yet implemented"). Desktop parity: PlayAreaPanel.java:436-448
 * offers "View deck (me|playerName)" for self or computer seats and opens a read-only
 * deck editor on the callback.
 *
 * Contract pinned here:
 *  - the VIEW_LIMITED_DECK callback serializes the TableClientMessage's DeckView, with
 *    entries in the SAME wire shape as sideboard_prompt (name/setCode/cardNumber/count),
 *    collapsed per print run — one card-list convention for both paths;
 *  - deckName carries the seat name (the server's MatchPlayer.getDeckForViewer replaces
 *    the real deck name), and tableId/time ride along like the sideboard precedent;
 *  - an unexpected payload is refused loudly (callback_error), never forwarded as an
 *    empty "successful" view;
 *  - only VIEW_LIMITED_DECK produces that message type;
 *  - the browser verb {@code view_limited_deck} dispatches
 *    PlayerAction.VIEW_LIMITED_DECK with the TARGET player UUID, and is registered
 *    player-only (a spectator has no playerId server-side; the request would die
 *    silently in GameController.viewDeckOrSideboard);
 *  - an unconnected session surfaces a browser error instead of a silent no-op
 *    (Fail Fast).
 *
 * Cards come from the REAL staged DB (Mage.WebProxy/db/cards.h2 — the same DB
 * DeckLoaderTest/CardsDbGuardTest use; stage with scripts/stage-test-db.sh after a
 * rebuild). The callback is driven through the REAL {@code WebProxyClient.onCallback}
 * dispatcher on a client whose BrowserSender is a recording stub — no server, no XMage,
 * same technique as WebProxyChooseChoiceSerializerTest.
 */
public class WebProxyViewLimitedDeckTest {

    /** Capture what the handler pushes to the browser. */
    private static final class RecordingSender implements WebProxyClient.BrowserSender {
        final List<String> sent = new ArrayList<>();

        @Override
        public boolean send(String json) {
            sent.add(json);
            return true;
        }
    }

    /** A real MockCard for a set/number the staged DB knows. */
    private static Card mockCard(String setCode, String cardNumber) {
        CardInfo info = CardRepository.instance.findCard(setCode, cardNumber);
        Assert.assertNotNull("staged card DB must resolve " + setCode + ":" + cardNumber
                + " (run Mage.WebProxy/scripts/stage-test-db.sh after a rebuild)", info);
        return info.createMockCard();
    }

    /**
     * A deck shaped like MatchPlayer.getDeckForViewer's output: the deck name is the
     * SEAT name (upstream replaces it so Tiny Leaders cannot leak the real one), the
     * maindeck has one duplicate print run, the sideboard one card.
     */
    private static Deck viewerDeck(String seatName) {
        Deck deck = new Deck();
        deck.setName(seatName);
        deck.getCards().add(mockCard("lci", "400"));  // Mountain
        deck.getCards().add(mockCard("lci", "400"));
        deck.getCards().add(mockCard("lci", "400"));
        deck.getCards().add(mockCard("lci", "401"));  // Forest
        deck.getSideboard().add(mockCard("lci", "401"));
        return deck;
    }

    private static TableClientMessage deckPayload(String seatName) {
        return new TableClientMessage()
                .withDeck(viewerDeck(seatName))
                .withTable(UUID.randomUUID(), null)
                .withTime(120)
                .withFlag(true);
    }

    /** Drive the real dispatcher and return the single emitted message. */
    private static JsonObject emitCallback(Object payload, ClientCallbackMethod method) {
        WebProxyClient client = new WebProxyClient("tester");
        RecordingSender sender = new RecordingSender();
        client.setWsSender(sender);

        client.onCallback(new ClientCallback(method, UUID.randomUUID(), payload));

        Assert.assertEquals("exactly one message pushed for " + method, 1, sender.sent.size());
        return JsonParser.parseString(sender.sent.get(0)).getAsJsonObject();
    }

    private static JsonObject findEntry(JsonArray list, String name, String cardNumber) {
        for (int i = 0; i < list.size(); i++) {
            JsonObject e = list.get(i).getAsJsonObject();
            if (name.equals(e.get("name").getAsString())
                    && cardNumber.equals(e.get("cardNumber").getAsString())) {
                return e;
            }
        }
        return null;
    }

    // ── the callback → browser message ──

    @Test
    public void viewLimitedDeckCallbackSendsTheDeckLists() {
        JsonObject msg = emitCallback(deckPayload("AI Mad"), ClientCallbackMethod.VIEW_LIMITED_DECK);

        Assert.assertEquals("view_limited_deck message", "view_limited_deck", msg.get("type").getAsString());
        Assert.assertEquals("deck name on this path is the seat name", "AI Mad", msg.get("deckName").getAsString());
        Assert.assertEquals("server timeout rides the message", 120, msg.get("time").getAsInt());
        Assert.assertFalse("a tableId is present", msg.get("tableId").isJsonNull());

        JsonArray cards = msg.getAsJsonArray("cards");
        Assert.assertEquals("two print runs in the maindeck", 2, cards.size());
        JsonObject mountain = findEntry(cards, "Mountain", "400");
        Assert.assertNotNull("Mountain must be present", mountain);
        Assert.assertEquals("copies collapse into one row", 3, mountain.get("count").getAsInt());
        // CardInfo normalizes set codes to upper case; the serializer forwards what the
        // DB holds. Assert case-insensitively so a DB normalization change is not read
        // as a wire-shape change.
        Assert.assertEquals("lci", mountain.get("setCode").getAsString().toLowerCase(java.util.Locale.ROOT));
        Assert.assertNotNull("Forest must be present", findEntry(cards, "Forest", "401"));

        JsonArray sideboard = msg.getAsJsonArray("sideboard");
        Assert.assertEquals(1, sideboard.size());
        Assert.assertEquals(1, findEntry(sideboard, "Forest", "401").get("count").getAsInt());
    }

    /**
     * The entry shape must be the SIDEBOARD PROMPT's shape — one card-list wire
     * convention for both callers (that is what lets the frontend reuse DeckEntryLite
     * and share the row renderer).
     */
    @Test
    public void entriesMatchTheSideboardPromptWireShape() {
        JsonObject limited = emitCallback(deckPayload("You"), ClientCallbackMethod.VIEW_LIMITED_DECK);
        JsonObject prompt = emitCallback(deckPayload("You"), ClientCallbackMethod.SIDEBOARD);

        Assert.assertEquals("sideboard_prompt type", "sideboard_prompt", prompt.get("type").getAsString());
        JsonArray a = limited.getAsJsonArray("cards");
        JsonArray b = prompt.getAsJsonArray("cards");
        Assert.assertEquals("same card rows on both paths", b.size(), a.size());
        for (int i = 0; i < a.size(); i++) {
            JsonObject ea = a.get(i).getAsJsonObject();
            JsonObject eb = b.get(i).getAsJsonObject();
            Assert.assertEquals("field set matches", eb.keySet(), ea.keySet());
            Assert.assertEquals(eb.get("name").getAsString(), ea.get("name").getAsString());
            Assert.assertEquals(eb.get("count").getAsInt(), ea.get("count").getAsInt());
        }
    }

    /**
     * Fail Fast: a payload that is not the expected TableClientMessage must produce a
     * loud callback_error, not an empty "successful" view.
     */
    @Test
    public void unexpectedPayloadIsRefusedNotEmptied() {
        JsonObject msg = emitCallback("not a table message", ClientCallbackMethod.VIEW_LIMITED_DECK);
        Assert.assertEquals("callback_error", msg.get("type").getAsString());
        Assert.assertTrue("the error names the method",
                msg.get("message").getAsString().contains("VIEW_LIMITED_DECK"));
    }

    /** An unrelated callback must not emit a view_limited_deck message. */
    @Test
    public void otherCallbacksDoNotEmitViewDeck() {
        JsonObject msg = emitCallback(deckPayload("Seat"), ClientCallbackMethod.SIDEBOARD);
        Assert.assertEquals("sideboard_prompt", msg.get("type").getAsString());
        Assert.assertNotEquals("nothing may claim view_limited_deck here", "view_limited_deck",
                msg.get("type").getAsString());
    }

    // ── the browser verb (WebProxySession.handleViewLimitedDeck) ──

    /**
     * A WebProxySession whose browser pushes are captured instead of written to a
     * WebSocket. sendToBrowser is package-private, so a same-package subclass can
     * override it — the whole handler then runs for real (validation, dispatch,
     * sendPlayerAction) and we assert on the frames it would have sent.
     */
    private static final class RecordingSession extends WebProxySession {
        final List<String> sent = new ArrayList<>();

        @Override
        boolean sendToBrowser(String json) {
            sent.add(json);
            return true;
        }
    }

    /**
     * A session whose XMage link is a never-connected SessionImpl: isConnected() is
     * false, so sendPlayerAction returns false — the Fail-Fast branch.
     */
    private static RecordingSession sessionWithDisconnectedXmage() throws Exception {
        RecordingSession session = new RecordingSession();
        Field f = WebProxySession.class.getDeclaredField("xmageSession");
        f.setAccessible(true);
        f.set(session, new SessionImpl(new MageClient() {
            @Override public mage.utils.MageVersion getVersion() { return null; }
            @Override public void connected(String message) { }
            @Override public void disconnected(boolean askToReconnect, boolean keepMySessionActive) { }
            @Override public void showMessage(String message) { }
            @Override public void showError(String message) { }
            @Override public void onNewConnection() { }
            @Override public void onCallback(ClientCallback callback) { }
        }));
        return session;
    }

    private static void setField(Object target, Class<?> c, String name, Object value) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void invokeViewLimitedDeck(WebProxySession session, JsonObject json) throws Exception {
        Method m = WebProxySession.class.getDeclaredMethod("handleViewLimitedDeck", JsonObject.class);
        m.setAccessible(true);
        m.invoke(session, json);
    }

    /**
     * On a dead session the handler must SURFACE the failure to the browser (error
     * frame), not complete as a silent no-op — and it must send
     * PlayerAction.VIEW_LIMITED_DECK for the TARGET player, never null (a null payload
     * is dropped silently by GameController's `data instanceof UUID` branch).
     */
    @Test
    public void unconnectedSessionSurfacesFailure() throws Exception {
        RecordingSession session = sessionWithDisconnectedXmage();
        WebProxyClient client = new WebProxyClient("tester");
        setField(client, WebProxyClient.class, "currentGameId", UUID.randomUUID());
        setField(session, WebProxySession.class, "proxyClient", client);

        JsonObject json = new JsonObject();
        json.addProperty("playerId", UUID.randomUUID().toString());
        invokeViewLimitedDeck(session, json); // must not throw

        Assert.assertEquals("a dead session is surfaced, not swallowed", 1, session.sent.size());
        JsonObject err = JsonParser.parseString(session.sent.get(0)).getAsJsonObject();
        Assert.assertEquals("error", err.get("type").getAsString());
        Assert.assertTrue("the error says the view-deck send failed",
                err.get("message").getAsString().contains("View deck"));
    }

    /** A malformed / missing playerId must be refused before touching XMage. */
    @Test
    public void malformedPlayerIdIsRefused() throws Exception {
        RecordingSession session = sessionWithDisconnectedXmage();
        WebProxyClient client = new WebProxyClient("tester");
        setField(client, WebProxyClient.class, "currentGameId", UUID.randomUUID());
        setField(session, WebProxySession.class, "proxyClient", client);

        JsonObject json = new JsonObject();
        json.addProperty("playerId", "not-a-uuid");
        invokeViewLimitedDeck(session, json);

        JsonObject err = JsonParser.parseString(session.sent.get(0)).getAsJsonObject();
        Assert.assertEquals("error", err.get("type").getAsString());
        Assert.assertTrue("the error names the bad field",
                err.get("message").getAsString().contains("playerId"));
    }

    /** No seated game yet — the request must be refused, not sent into the void. */
    @Test
    public void noActiveGameIsRefused() throws Exception {
        RecordingSession session = sessionWithDisconnectedXmage();
        WebProxyClient client = new WebProxyClient("tester"); // currentGameId stays null
        setField(session, WebProxySession.class, "proxyClient", client);

        JsonObject json = new JsonObject();
        json.addProperty("playerId", UUID.randomUUID().toString());
        invokeViewLimitedDeck(session, json);

        JsonObject err = JsonParser.parseString(session.sent.get(0)).getAsJsonObject();
        Assert.assertEquals("error", err.get("type").getAsString());
        Assert.assertTrue("the error says there is no active game",
                err.get("message").getAsString().contains("No active game"));
    }

    /**
     * view_limited_deck is a PLAYER-ONLY verb: a spectator has no playerId on the
     * server (userPlayerMap), so the request would die silently in
     * GameController.viewDeckOrSideboard. Rejecting at the proxy surfaces it.
     */
    @Test
    public void viewLimitedDeckIsPlayerOnly() throws Exception {
        Field f = WebProxySession.class.getDeclaredField("PLAYER_ONLY_ACTIONS");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<String> playerOnly = (java.util.Set<String>) f.get(null);
        Assert.assertTrue("view_limited_deck must be player-only (spectators have no playerId)",
                playerOnly.contains("view_limited_deck"));
    }
}
