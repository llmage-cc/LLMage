package mage.webproxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.cards.Card;
import mage.cards.decks.Deck;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.constants.PlayerAction;
import mage.constants.RangeOfInfluence;
import mage.game.FakeGame;
import mage.game.match.MatchImpl;
import mage.game.match.MatchOptions;
import mage.game.match.MatchPlayer;
import mage.interfaces.MageClient;
import mage.interfaces.callback.ClientCallback;
import mage.players.Player;
import mage.players.StubPlayer;
import mage.players.net.UserData;
import mage.remote.SessionImpl;
import mage.view.GameView;
import mage.view.SimpleCardsView;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Unit tests for the hand-request INITIATE half

 *
 * What existed before this change: the RESPONSE half only — the granter clicks
 * Accept in the browser's UserRequestDialog and `user_request_response` sends
 * ADD_PERMISSION_TO_SEE_HAND_CARDS. Nothing in the browser could ASK (the
 * desktop client's "Cards on hand (name)" submenu — PlayAreaPanel.java:403-434
 * for a seat:479-481 for a watcher — had no web counterpart) and nothing in
 * the state payload carried a hand the viewer had been GRANTED, so even a
 * permission obtained out of band was invisible in the browser.
 *
 * Contract pinned here:
 *  - the three browser verbs dispatch the right PlayerAction with the right data
 *    (request → REQUEST_PERMISSION_TO_SEE_HAND_CARDS + the GRANTER's UUID;
 *     revoke → REVOKE_PERMISSIONS_TO_SEE_HAND_CARDS + null;
 *     allow → PERMISSION_REQUESTS_ALLOWED_ON/_OFF + null);
 *  - malformed input fails fast — a browser error AND nothing on the XMage wire
 *    (missing/invalid playerId, missing `enabled`, no active game), and a dead
 *    session SURFACES the failure instead of silently no-op'ing;
 *  - request_hand_permission is WATCHER-ALLOWED (the desktop offers it to
 *    watchers and GameController.requestPermissionToSeeHandCards tolerates a
 *    null requestingPlayerId), while revoke_hand_permission and
 *    allow_hand_requests are PLAYER-ONLY (both act on the sender's own seat via
 *    userPlayerMap — a silent no-op for a spectator);
 *  - the STATE half: GameStateSerializer emits `watchedHands` as [{name, cards}]
 *    built from GameView.getWatchedHands(), each card in the SAME wire shape as a
 *    player's own `hand` entry (one row convention for the frontend);
 *  - an ungranted session gets an EMPTY ARRAY (never null/absent — the browser
 *    reads the key directly);
 *  - per-player `allowHandRequests` mirrors UserData.isAllowRequestHandToAll().
 *
 * Cards come from the REAL staged DB (Mage.WebProxy/db/cards.h2 — the same DB
 * DeckLoaderTest/WebProxyViewLimitedDeckTest use; stage with
 * scripts/stage-test-db.sh after a rebuild). Games are REAL FakeGame +
 * StubPlayer instances, the GameView is the REAL one, and the verbs run through
 * the REAL private handlers by reflection. Same technique as
 * WebProxyViewLimitedDeckTest / WebProxyMomirEmblemLabelTest.
 */
public class WebProxyHandPermissionTest {

    // ── the verb half ──

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
            super(stubClient());
        }

        @Override
        public boolean sendPlayerAction(PlayerAction playerAction, UUID gameId, Object data) {
            sentActions.add(new SentAction(playerAction, gameId, data));
            return true;
        }
    }

    /** A WebProxySession whose browser pushes are captured, not written to a WebSocket. */
    static class RecordingSession extends WebProxySession {
        final List<String> sent = new ArrayList<>();

        @Override
        boolean sendToBrowser(String json) {
            sent.add(json);
            return true;
        }

        List<JsonObject> ofType(String type) {
            List<JsonObject> out = new ArrayList<>();
            for (String s : sent) {
                JsonObject o = JsonParser.parseString(s).getAsJsonObject();
                if (o.has("type") && type.equals(o.get("type").getAsString())) {
                    out.add(o);
                }
            }
            return out;
        }
    }

    private static MageClient stubClient() {
        return new MageClient() {
            @Override public mage.utils.MageVersion getVersion() { return null; }
            @Override public void connected(String message) { }
            @Override public void disconnected(boolean askToReconnect, boolean keepMySessionActive) { }
            @Override public void showMessage(String message) { }
            @Override public void showError(String message) { }
            @Override public void onNewConnection() { }
            @Override public void onCallback(ClientCallback callback) { }
        };
    }

    private static RecordingSession newSession(SessionImpl xmage, UUID gameId) throws Exception {
        RecordingSession session = new RecordingSession();
        setField(session, WebProxySession.class, "xmageSession", xmage);
        WebProxyClient client = new WebProxyClient("tester");
        setField(session, WebProxySession.class, "proxyClient", client);
        if (gameId != null) {
            setField(client, WebProxyClient.class, "currentGameId", gameId);
        }
        return session;
    }

    /**
     * A session whose XMage link is a never-connected SessionImpl:
     * isConnected() is false, so sendPlayerAction returns false — the Fail-Fast branch.
     */
    private static RecordingSession deadXmageSession(UUID gameId) throws Exception {
        return newSession(new SessionImpl(stubClient()), gameId);
    }

    private static void setField(Object target, Class<?> c, String name, Object value) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void invoke(WebProxySession session, String method, JsonObject json) throws Exception {
        Method m = WebProxySession.class.getDeclaredMethod(method, JsonObject.class);
        m.setAccessible(true);
        m.invoke(session, json);
    }

    /** For the no-payload handlers (revoke_hand_permission takes no fields). */
    private static void invokeNoArg(WebProxySession session, String method) throws Exception {
        Method m = WebProxySession.class.getDeclaredMethod(method);
        m.setAccessible(true);
        m.invoke(session);
    }

    private static JsonObject requestJson(String playerId) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "request_hand_permission");
        if (playerId != null) o.addProperty("playerId", playerId);
        return o;
    }

    private static JsonObject allowJson(Boolean enabled) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "allow_hand_requests");
        if (enabled != null) o.addProperty("enabled", enabled);
        return o;
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<String> playerOnlyActions() throws Exception {
        Field f = WebProxySession.class.getDeclaredField("PLAYER_ONLY_ACTIONS");
        f.setAccessible(true);
        return (java.util.Set<String>) f.get(null);
    }

    // ── request_hand_permission ──

    /** The happy path: REQUEST for the GRANTER's player UUID, that game, that data. */
    @Test
    public void requestSendsRequestPermissionWithGranterUuid() throws Exception {
        RecordingXmageSession xmage = new RecordingXmageSession();
        UUID gameId = UUID.randomUUID();
        UUID granter = UUID.randomUUID();

        invoke(newSession(xmage, gameId), "handleRequestHandPermission", requestJson(granter.toString()));

        Assert.assertEquals("exactly one action on the wire", 1, xmage.sentActions.size());
        SentAction sent = xmage.sentActions.get(0);
        Assert.assertEquals(PlayerAction.REQUEST_PERMISSION_TO_SEE_HAND_CARDS, sent.action);
        Assert.assertEquals(gameId, sent.gameId);
        Assert.assertEquals("data is the GRANTER's player UUID"
                        + " (GameController.requestPermissionToSeeHandCards)",
                granter, sent.data);
    }

    /**
     * The WATCHER call: request_hand_permission is deliberately NOT player-only.
     * The desktop offers the item in its watcher menu and the engine tolerates a
     * null requestingPlayerId, so a spectator session must be able to send it.
     */
    @Test
    public void requestIsWatcherAllowed() throws Exception {
        Assert.assertFalse("request_hand_permission must be watcher-allowed"
                        + " (desktop watcher menu + null-safe engine path)",
                playerOnlyActions().contains("request_hand_permission"));
    }

    /** A missing playerId must be refused: browser error, nothing on the wire. */
    @Test
    public void requestWithoutPlayerIdIsRefused() throws Exception {
        RecordingXmageSession xmage = new RecordingXmageSession();
        RecordingSession session = newSession(xmage, UUID.randomUUID());

        invoke(session, "handleRequestHandPermission", requestJson(null));

        Assert.assertEquals("nothing on the XMage wire", 0, xmage.sentActions.size());
        Assert.assertEquals("the browser is told why", 1, session.ofType("error").size());
    }

    /** A playerId that is not a UUID must be refused before touching XMage. */
    @Test
    public void requestWithMalformedPlayerIdIsRefused() throws Exception {
        RecordingXmageSession xmage = new RecordingXmageSession();
        RecordingSession session = newSession(xmage, UUID.randomUUID());

        invoke(session, "handleRequestHandPermission", requestJson("not-a-uuid"));

        Assert.assertEquals("nothing on the XMage wire", 0, xmage.sentActions.size());
        Assert.assertTrue("the error names the bad field",
                session.ofType("error").get(0).get("message").getAsString().contains("playerId"));
    }

    /** No seated game yet — refused, nothing sent. */
    @Test
    public void requestWithNoGameIsRefused() throws Exception {
        RecordingXmageSession xmage = new RecordingXmageSession();
        RecordingSession session = newSession(xmage, null);

        invoke(session, "handleRequestHandPermission", requestJson(UUID.randomUUID().toString()));

        Assert.assertEquals("nothing on the XMage wire", 0, xmage.sentActions.size());
        Assert.assertTrue("the error says there is no active game",
                session.ofType("error").get(0).get("message").getAsString().contains("No active game"));
    }

    /** A dead XMage link surfaces the failure to the browser, never a silent no-op. */
    @Test
    public void deadXmageSessionSurfacesTheFailure() throws Exception {
        RecordingSession session = deadXmageSession(UUID.randomUUID());
        invoke(session, "handleRequestHandPermission", requestJson(UUID.randomUUID().toString()));

        Assert.assertEquals("the browser is told the send failed", 1, session.ofType("error").size());
        Assert.assertTrue("the error says the request failed",
                session.ofType("error").get(0).get("message").getAsString().contains("Request permission"));
    }

    // ── revoke_hand_permission ──

    @Test
    public void revokeSendsRevokeWithNullData() throws Exception {
        RecordingXmageSession xmage = new RecordingXmageSession();
        UUID gameId = UUID.randomUUID();

        invokeNoArg(newSession(xmage, gameId), "handleRevokeHandPermission");

        Assert.assertEquals(1, xmage.sentActions.size());
        SentAction sent = xmage.sentActions.get(0);
        Assert.assertEquals(PlayerAction.REVOKE_PERMISSIONS_TO_SEE_HAND_CARDS, sent.action);
        Assert.assertEquals(gameId, sent.gameId);
        Assert.assertNull("revoke acts on the sender's own hand — no data payload", sent.data);
    }

    /** revoke acts on the sender's OWN seat → player-only (a spectator has none). */
    @Test
    public void revokeIsPlayerOnly() throws Exception {
        Assert.assertTrue("revoke_hand_permission must be player-only (own seat)",
                playerOnlyActions().contains("revoke_hand_permission"));
    }

    // ── allow_hand_requests ──

    @Test
    public void allowOnSendsPermissionRequestsAllowedOn() throws Exception {
        RecordingXmageSession xmage = new RecordingXmageSession();
        UUID gameId = UUID.randomUUID();

        invoke(newSession(xmage, gameId), "handleAllowHandRequests", allowJson(true));

        Assert.assertEquals(1, xmage.sentActions.size());
        SentAction sent = xmage.sentActions.get(0);
        Assert.assertEquals(PlayerAction.PERMISSION_REQUESTS_ALLOWED_ON, sent.action);
        Assert.assertEquals(gameId, sent.gameId);
        Assert.assertNull("own-seat UserData flip — no data payload", sent.data);
    }

    @Test
    public void allowOffSendsPermissionRequestsAllowedOff() throws Exception {
        RecordingXmageSession xmage = new RecordingXmageSession();
        invoke(newSession(xmage, UUID.randomUUID()), "handleAllowHandRequests", allowJson(false));

        Assert.assertEquals(1, xmage.sentActions.size());
        Assert.assertEquals(PlayerAction.PERMISSION_REQUESTS_ALLOWED_OFF, xmage.sentActions.get(0).action);
        Assert.assertNull(xmage.sentActions.get(0).data);
    }

    /**
     * Fail Fast: a payload without `enabled` must NOT silently pick a direction
     * (flipping the wrong way would either refuse every request or re-open them).
     */
    @Test
    public void allowWithoutEnabledIsRefused() throws Exception {
        RecordingXmageSession xmage = new RecordingXmageSession();
        RecordingSession session = newSession(xmage, UUID.randomUUID());

        invoke(session, "handleAllowHandRequests", allowJson(null));

        Assert.assertEquals("nothing on the XMage wire", 0, xmage.sentActions.size());
        Assert.assertEquals("the browser is told why", 1, session.ofType("error").size());
    }

    @Test
    public void allowIsPlayerOnly() throws Exception {
        Assert.assertTrue("allow_hand_requests must be player-only (own UserData)",
                playerOnlyActions().contains("allow_hand_requests"));
    }

    // ── the state half: GameStateSerializer.watchedHands ──

    /** Two real stub players with a match each and one real staged card in P2's hand. */
    private static StubPlayer[] newGameWithHandCard(FakeGame[] out) {
        StubPlayer p1 = new StubPlayer("P1", RangeOfInfluence.ALL);
        StubPlayer p2 = new StubPlayer("P2", RangeOfInfluence.ALL);
        FakeGame game = new FakeGame();
        MatchImpl match = new MatchImpl(new MatchOptions("t", "Test Two Player Duel", false)) {
            @Override
            public void startGame() {
            }
        };
        p1.setMatchPlayer(new MatchPlayer(p1, new Deck(), match));
        p2.setMatchPlayer(new MatchPlayer(p2, new Deck(), match));
        game.getState().addPlayer(p1);
        game.getState().addPlayer(p2);

        // Real card from the staged DB (fails loudly if the DB is unstaged).
        CardInfo info = CardRepository.instance.findCard("lci", "401");
        Assert.assertNotNull("staged card DB must resolve lci:401 (run"
                + " Mage.WebProxy/scripts/stage-test-db.sh after a rebuild)", info);
        Card card = info.createMockCard();
        game.loadCards(Collections.singleton(card), p2.getId());
        p2.getHand().add(card);

        out[0] = game;
        return new StubPlayer[]{p1, p2};
    }

    /**
     * The GRANTED hand: processWatchedHands' exact shape, then the serializer's
     * [{name, cards}] — and each card row must carry the SAME fields as the
     * viewer's own hand row (one card-row convention for the frontend).
     *
     * NOTE: GameSessionWatcher lives in Mage.Server, which is not on this
     * module's classpath, so the three-line population is mirrored here — it is
     * verbatim what processWatchedHands does (grant check per USER id, keyed by
     * player name). The SERIALIZER under test is the real one.
     */
    @Test
    public void grantedHandIsSerializedInThePlayersHandShape() throws Exception {
        FakeGame[] holder = new FakeGame[1];
        StubPlayer[] players = newGameWithHandCard(holder);
        StubPlayer p1 = players[0];
        StubPlayer p2 = players[1];
        FakeGame game = holder[0];

        // P1's own hand gets the same printing, so both rows are comparable.
        Card own = CardRepository.instance.findCard("lci", "401").createMockCard();
        game.loadCards(Collections.singleton(own), p1.getId());
        p1.getHand().add(own);

        UUID viewerUserId = UUID.randomUUID();
        p2.addPermissionToShowHandCards(viewerUserId);   // the accepted grant
        Assert.assertTrue("the grant must be visible to the engine",
                p2.hasUserPermissionToSeeHand(viewerUserId));

        GameView view = new GameView(game.getState(), game, p1.getId(), null);
        view.getWatchedHands().clear();
        for (Player player : game.getPlayers().values()) {
            if (player.hasUserPermissionToSeeHand(viewerUserId)) {
                view.getWatchedHands().put(player.getName(),
                        new SimpleCardsView(player.getHand().getCards(game), true));
            }
        }
        Assert.assertEquals("exactly the granted seat", 1, view.getWatchedHands().size());

        JsonObject state = JsonParser.parseString(
                GameStateSerializer.serialize(view, p1.getId(), 7)).getAsJsonObject();

        JsonArray watched = state.getAsJsonArray("watchedHands");
        Assert.assertNotNull("watchedHands must be present (never null — the browser reads it directly)", watched);
        Assert.assertEquals(1, watched.size());
        JsonObject hand = watched.get(0).getAsJsonObject();
        Assert.assertEquals("keyed by player NAME (GameSessionWatcher.processWatchedHands)", "P2",
                hand.get("name").getAsString());
        JsonArray cards = hand.getAsJsonArray("cards");
        Assert.assertEquals("the granted hand's cards", 1, cards.size());
        JsonObject watchedCard = cards.get(0).getAsJsonObject();
        Assert.assertEquals("the card's real name (resolved via the proxy CardRepository)",
                "Forest", watchedCard.get("name").getAsString());
        Assert.assertFalse("a real id, not null", watchedCard.get("id").isJsonNull());

        // Same row shape as the viewer's OWN hand.
        JsonArray myHand = null;
        for (JsonElement el : state.getAsJsonArray("players")) {
            JsonObject p = el.getAsJsonObject();
            if (p.get("isMe").getAsBoolean()) {
                myHand = p.getAsJsonArray("hand");
            }
        }
        Assert.assertNotNull(myHand);
        Assert.assertEquals("own hand has its card", 1, myHand.size());
        JsonObject ownCard = myHand.get(0).getAsJsonObject();
        Assert.assertEquals("one card-row convention: same field set on both paths",
                ownCard.keySet(), watchedCard.keySet());
    }

    /** No grants → an EMPTY ARRAY (not null, not absent), so the frontend reads []. */
    @Test
    public void ungrantedSessionGetsAnEmptyArray() throws Exception {
        FakeGame[] holder = new FakeGame[1];
        StubPlayer[] players = newGameWithHandCard(holder);
        GameView view = new GameView(holder[0].getState(), holder[0], players[0].getId(), null);
        Assert.assertTrue("no grant → no watched hands", view.getWatchedHands().isEmpty());

        JsonObject state = JsonParser.parseString(
                GameStateSerializer.serialize(view, players[0].getId(), 1)).getAsJsonObject();
        JsonArray watched = state.getAsJsonArray("watchedHands");
        Assert.assertNotNull("the key must exist even when empty", watched);
        Assert.assertEquals(0, watched.size());
    }

    /** A watched card missing from the proxy DB still renders (minimal fallback, no throw). */
    @Test
    public void unknownPrintingFallsBackToAMinimalCard() throws Exception {
        FakeGame[] holder = new FakeGame[1];
        StubPlayer[] players = newGameWithHandCard(holder);
        StubPlayer p2 = players[1];
        GameView view = new GameView(holder[0].getState(), holder[0], players[0].getId(), null);
        // A printing no DB has: the serializer must not throw inside the state channel.
        mage.view.SimpleCardsView hand = new mage.view.SimpleCardsView();
        hand.put(UUID.randomUUID(), new mage.view.SimpleCardView(
                UUID.randomUUID(), "zzz", "99999", false, true));
        view.getWatchedHands().put("Ghost", hand);

        JsonObject state = JsonParser.parseString(
                GameStateSerializer.serialize(view, players[0].getId(), 1)).getAsJsonObject();
        JsonArray watched = state.getAsJsonArray("watchedHands");
        Assert.assertEquals(1, watched.size());
        JsonObject card = watched.get(0).getAsJsonObject().getAsJsonArray("cards").get(0).getAsJsonObject();
        Assert.assertEquals("the fallback keeps set/number so Scryfall art still resolves",
                "zzz", card.get("setCode").getAsString());
        Assert.assertEquals("99999", card.get("cardNumber").getAsString());
    }

    /**
     * Per-player allowHandRequests mirrors UserData.isAllowRequestHandToAll() — the
     * value the desktop client's own-header checkbox reads. Both directions round-trip.
     */
    @Test
    public void allowHandRequestsMirrorsTheUserDataFlag() throws Exception {
        FakeGame[] holder = new FakeGame[1];
        StubPlayer[] players = newGameWithHandCard(holder);
        StubPlayer p1 = players[0];
        StubPlayer p2 = players[1];

        p1.setUserData(UserData.getDefaultUserDataView());
        p1.getUserData().setAllowRequestShowHandCards(true);
        p2.setUserData(UserData.getDefaultUserDataView());
        p2.getUserData().setAllowRequestShowHandCards(false);

        GameView view = new GameView(holder[0].getState(), holder[0], p1.getId(), null);
        JsonObject state = JsonParser.parseString(
                GameStateSerializer.serialize(view, p1.getId(), 1)).getAsJsonObject();

        boolean sawP1 = false;
        boolean sawP2 = false;
        for (JsonElement el : state.getAsJsonArray("players")) {
            JsonObject p = el.getAsJsonObject();
            String name = p.get("name").getAsString();
            boolean allow = p.get("allowHandRequests").getAsBoolean();
            if ("P1".equals(name)) {
                sawP1 = true;
                Assert.assertTrue("P1 accepts hand requests", allow);
            } else if ("P2".equals(name)) {
                sawP2 = true;
                Assert.assertFalse("P2 refuses hand requests", allow);
            }
        }
        Assert.assertTrue("both seats were serialized", sawP1 && sawP2);
    }
}
