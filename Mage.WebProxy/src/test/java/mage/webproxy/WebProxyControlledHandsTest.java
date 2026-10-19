package mage.webproxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.cards.Card;
import mage.cards.decks.Deck;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.constants.RangeOfInfluence;
import mage.game.FakeGame;
import mage.game.match.MatchImpl;
import mage.game.match.MatchOptions;
import mage.game.match.MatchPlayer;
import mage.players.StubPlayer;
import mage.view.GameClientMessage;
import mage.view.GameView;
import mage.view.SimpleCardView;
import mage.view.SimpleCardsView;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Unit tests for the "switch hands" wire contract

 *
 * Desktop parity: GamePanel.updateGame collects `handCards` from
 * gameView.getMyHand() ("Your hand"), getOpponentHands() (players whose TURN
 * this session controls — GameSessionPlayer.processControlledPlayers) and
 * getWatchedHands() (players who granted this user permission to see their hand
 * — GameSessionWatcher.processWatchedHands); btnSwitchHands is visible only when
 * handCards.size() > 1. Before this change the web wire carried only MY hand, so
 * the browser had nothing to switch to.
 *
 * Contract pinned here:
 *  - the top-level `controlledHands` list carries {name, kind, cards[]} for every
 *    opponent/watched hand the GameView holds, opponent hands first;
 *  - each card is the FULL hand-card shape (name/imageUrl/typeLine/manaCost/rules)
 *    resolved through the card DB, and its `id` is the SimpleCardView's own game
 *    object UUID (what the board/choice click paths key on);
 *  - the list is ALWAYS present (empty when nothing is visible) so the frontend's
 *    "more than one hand" gate is a plain length check;
 *  - the existing `hand` contract is untouched: still only the session player's
 *    hand, everyone else empty;
 *  - a set+number the DB cannot resolve is a Fail-Fast IllegalStateException, not
 *    a silent nameless card;
 *  - the play path resolves a CONTROLLED hand's card through
 *    WebProxyClient.findCardViewById (via the real GAME_SELECT handler): the
 *    choice is labelled cast/land — not 'activate' — so control-the-turn is
 * playable from the web client (without this the fail-fast dropped every
 *    such choice and logged "has no viewable card").
 *
 * Technique: a real {@link GameView} built from a real {@link FakeGame} + real
 * {@link StubPlayer}s (same fixture as WebProxyMomirEmblemLabelTest), real
 * {@link mage.cards.Card}s from the staged DB (Mage.WebProxy/db/cards.h2 — stage
 * with scripts/stage-test-db.sh after a rebuild), the REAL serializer and the REAL
 * private handler invoked by reflection over a recording
 * {@link WebProxyClient.BrowserSender}. No server, no mocks of the code under test.
 */
public class WebProxyControlledHandsTest {

    /** Capture what the handler pushes to the browser. */
    private static final class RecordingSender implements WebProxyClient.BrowserSender {
        final List<String> sent = new ArrayList<String>();

        @Override
        public boolean send(String json) {
            sent.add(json);
            return true;
        }
    }

    private static StubPlayer newPlayer(String name) {
        return new StubPlayer(name, RangeOfInfluence.ALL);
    }

    /** A game with a real stub player and a real match each (PlayerView reads it). */
    private static FakeGame newGame(StubPlayer... players) {
        FakeGame game = new FakeGame();
        MatchImpl match = new MatchImpl(new MatchOptions("t", "Two Player Duel", false)) {
            @Override
            public void startGame() {
            }
        };
        for (StubPlayer p : players) {
            p.setMatchPlayer(new MatchPlayer(p, new Deck(), match));
            game.getState().addPlayer(p);
        }
        return game;
    }

    /** A real MockCard for a set/number the staged DB knows. */
    private static Card mockCard(String setCode, String cardNumber) {
        CardInfo info = CardRepository.instance.findCard(setCode, cardNumber);
        Assert.assertNotNull("staged card DB must resolve " + setCode + ":" + cardNumber
                + " (run Mage.WebProxy/scripts/stage-test-db.sh after a rebuild)", info);
        return info.createMockCard();
    }

    /** The engine's own foreign-hand shape: SimpleCardsView over real cards. */
    private static SimpleCardsView foreignHand(Card... cards) {
        SimpleCardsView view = new SimpleCardsView();
        for (Card card : cards) {
            view.put(card.getId(), new SimpleCardView(card.getId(),
                    card.getExpansionSetCode(), card.getCardNumber(), card.getUsesVariousArt(), true));
        }
        return view;
    }

    /** Serialize the given view as the given session player and parse the JSON. */
    private static JsonObject stateOf(GameView gameView, UUID myPlayerId) {
        String json = GameStateSerializer.serialize(gameView, myPlayerId, 7);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonObject findHand(JsonArray list, String name) {
        for (int i = 0; i < list.size(); i++) {
            JsonObject e = list.get(i).getAsJsonObject();
            if (name.equals(e.get("name").getAsString())) {
                return e;
            }
        }
        return null;
    }

    // ── 1. the additive wire shape ──

    @Test
    public void controlledAndWatchedHandsAreSerializedWithTheirNamesAndKinds() {
        StubPlayer p1 = newPlayer("QA-A");
        StubPlayer p2 = newPlayer("QA-B");
        StubPlayer p3 = newPlayer("QA-C");
        FakeGame game = newGame(p1, p2, p3);

        GameView gameView = new GameView(game.getState(), game, p1.getId(), null);
        Card mountain = mockCard("lci", "400");
        Card forest = mockCard("lci", "401");
        gameView.getOpponentHands().put("QA-B", foreignHand(mountain));
        gameView.getWatchedHands().put("QA-C", foreignHand(forest));

        JsonObject state = stateOf(gameView, p1.getId());
        JsonArray hands = state.getAsJsonArray("controlledHands");
        Assert.assertNotNull("controlledHands is always present", hands);
        Assert.assertEquals("one controlled + one watched entry", 2, hands.size());

        JsonObject controlled = hands.get(0).getAsJsonObject();
        Assert.assertEquals("engine player name is the key", "QA-B", controlled.get("name").getAsString());
        Assert.assertEquals("turn control kind", "controlled", controlled.get("kind").getAsString());
        JsonArray controlledCards = controlled.getAsJsonArray("cards");
        Assert.assertEquals(1, controlledCards.size());
        JsonObject card = controlledCards.get(0).getAsJsonObject();
        Assert.assertEquals("card name resolved through the DB", "Mountain", card.get("name").getAsString());
        Assert.assertEquals("the SimpleCardView's own UUID rides along",
                mountain.getId().toString(), card.get("id").getAsString());
        // The tile must be shaped like a card in `hand` — the same serializer runs.
        for (String field : new String[]{"imageUrl", "typeLine", "manaCost", "rules"}) {
            Assert.assertTrue("foreign hand card carries " + field, card.has(field));
        }

        JsonObject watched = hands.get(1).getAsJsonObject();
        Assert.assertEquals("QA-C", watched.get("name").getAsString());
        Assert.assertEquals("granted-permission kind", "watched", watched.get("kind").getAsString());
        Assert.assertEquals("Forest", watched.getAsJsonArray("cards").get(0).getAsJsonObject()
                .get("name").getAsString());
    }

    /**
     * Additive means additive: the existing `hand` contract is untouched — only
     * the session player's own seat carries it; every other seat stays empty.
     */
    @Test
    public void theExistingHandContractIsUntouched() {
        StubPlayer p1 = newPlayer("QA-A");
        StubPlayer p2 = newPlayer("QA-B");
        FakeGame game = newGame(p1, p2);

        GameView gameView = new GameView(game.getState(), game, p1.getId(), null);
        gameView.getOpponentHands().put("QA-B", foreignHand(mockCard("lci", "400")));

        JsonObject state = stateOf(gameView, p1.getId());
        JsonArray players = state.getAsJsonArray("players");
        for (int i = 0; i < players.size(); i++) {
            JsonObject player = players.get(i).getAsJsonObject();
            JsonArray hand = player.getAsJsonArray("hand");
            Assert.assertNotNull("every seat still carries a hand array", hand);
            if (player.get("isMe").getAsBoolean()) {
                Assert.assertEquals("my own hand array is unchanged (empty hand in this fixture)",
                        0, hand.size());
            } else {
                Assert.assertEquals("another seat's hand stays empty", 0, hand.size());
            }
        }
        // The controlled hand is available ONLY through the new field.
        Assert.assertEquals("one foreign hand", 1, state.getAsJsonArray("controlledHands").size());
    }

    /** Nothing visible → an EMPTY list, never a missing key (plain length gate). */
    @Test
    public void noForeignHandsSerializesToAnEmptyList() {
        StubPlayer p1 = newPlayer("QA-A");
        StubPlayer p2 = newPlayer("QA-B");
        FakeGame game = newGame(p1, p2);

        GameView gameView = new GameView(game.getState(), game, p1.getId(), null);
        JsonObject state = stateOf(gameView, p1.getId());
        Assert.assertNotNull(state.getAsJsonArray("controlledHands"));
        Assert.assertEquals(0, state.getAsJsonArray("controlledHands").size());
    }

    /** A spectator (no playerId) sees whatever the view holds — or nothing. */
    @Test
    public void spectatorViewCarriesTheWatchedHandsItWasGiven() {
        StubPlayer p1 = newPlayer("QA-A");
        StubPlayer p2 = newPlayer("QA-B");
        FakeGame game = newGame(p1, p2);

        GameView gameView = new GameView(game.getState(), game, null, UUID.randomUUID());
        gameView.getWatchedHands().put("QA-A", foreignHand(mockCard("lci", "400")));

        JsonObject state = stateOf(gameView, null);
        JsonArray hands = state.getAsJsonArray("controlledHands");
        Assert.assertEquals(1, hands.size());
        Assert.assertEquals("QA-A", hands.get(0).getAsJsonObject().get("name").getAsString());
        Assert.assertTrue("a watcher view is flagged", state.get("isSpectator").getAsBoolean());
        // No session player → no `hand` anywhere.
        JsonArray players = state.getAsJsonArray("players");
        for (int i = 0; i < players.size(); i++) {
            Assert.assertEquals("no seat is 'me' for a spectator", 0,
                    players.get(i).getAsJsonObject().getAsJsonArray("hand").size());
        }
    }

    /** Fail Fast: an unknown set+number must throw, never render a nameless tile. */
    @Test
    public void unresolvableSetAndNumberIsRefusedLoudly() {
        StubPlayer p1 = newPlayer("QA-A");
        StubPlayer p2 = newPlayer("QA-B");
        FakeGame game = newGame(p1, p2);

        GameView gameView = new GameView(game.getState(), game, p1.getId(), null);
        SimpleCardsView bogus = new SimpleCardsView();
        bogus.put(UUID.randomUUID(), new SimpleCardView(UUID.randomUUID(), "zzz", "999999", false, true));
        gameView.getOpponentHands().put("QA-B", bogus);

        try {
            GameStateSerializer.serialize(gameView, p1.getId(), 1);
            Assert.fail("an unresolvable foreign-hand card must throw (Fail Fast)");
        } catch (IllegalStateException expected) {
            Assert.assertTrue("the error names the seat",
                    expected.getMessage().contains("QA-B"));
            Assert.assertTrue("the error names the printing",
                    expected.getMessage().contains("zzz") && expected.getMessage().contains("999999"));
        }
    }

    // ── 2. the play path: a controlled hand's card must be playable ──

    /**
     * The controlling session plays the controlled player's cards during their
     * turn. The engine offers them via getCanPlayObjects, but the object key is
     * NOT in myHand — before this change findCardViewById could not resolve it,
     * so the fail-fast dropped the choice ("has no viewable card") and
     * control-the-turn was unusable from the web client. The lookup now searches
     * the foreign hands, and the choice is labelled like a play from MY hand
     * (cast/land), because that is what it is.
     */
    @Test
    public void aCardPlayedFromAControlledHandIsLabelledCast() throws Exception {
        StubPlayer p1 = newPlayer("QA-A");
        StubPlayer p2 = newPlayer("QA-B");
        FakeGame game = newGame(p1, p2);

        GameView gameView = new GameView(game.getState(), game, p1.getId(), null);
        Card bolt = mockCard("2x2", "117"); // Lightning Bolt
        gameView.getOpponentHands().put("QA-B", foreignHand(bolt));

        // The engine offers the controlled player's card as a playable object.
        Map<UUID, List<mage.abilities.ActivatedAbility>> playable = new java.util.HashMap<>();
        playable.put(bolt.getId(), new ArrayList<mage.abilities.ActivatedAbility>());
        mage.players.PlayableObjectsList list = new mage.players.PlayableObjectsList();
        // Empty ability list still registers the object key (the stats shape the
        // handler reads); a real play carries abilities, but the labelling only
        // needs the key to resolve to a view.
        list.load(playable);
        gameView.setCanPlayObjects(list);

        WebProxyClient client = new WebProxyClient("controlled-hands-test");
        RecordingSender sender = new RecordingSender();
        client.setWsSender(sender);
        GameClientMessage gcm = new GameClientMessage(gameView, null, "Play spells and abilities");
        Method m = WebProxyClient.class.getDeclaredMethod("handleGameSelect", GameClientMessage.class, GameView.class);
        m.setAccessible(true);
        m.invoke(client, gcm, gameView);

        JsonObject message = JsonParser.parseString(sender.sent.get(sender.sent.size() - 1)).getAsJsonObject();
        Assert.assertEquals("a select prompt", "action_prompt", message.get("type").getAsString());
        JsonArray choices = message.getAsJsonArray("choices");
        Assert.assertEquals("the controlled hand's card is offered", 1, choices.size());
        JsonObject choice = choices.get(0).getAsJsonObject();
        Assert.assertEquals("named from the DB through the foreign-hand lookup",
                "Lightning Bolt", choice.get("name").getAsString());
        Assert.assertEquals("its own UUID is the choice id", bolt.getId().toString(),
                choice.get("id").getAsString());
        Assert.assertEquals("a play from a hand is a cast, not an activate",
                "cast", choice.get("action").getAsString());
    }
}
