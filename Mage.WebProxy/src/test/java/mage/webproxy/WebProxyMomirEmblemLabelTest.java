package mage.webproxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.abilities.ActivatedAbility;
import mage.cards.Card;
import mage.cards.CardSetInfo;
import mage.cards.decks.Deck;
import mage.cards.g.GrizzlyBears;
import mage.constants.RangeOfInfluence;
import mage.constants.Rarity;
import mage.game.FakeGame;
import mage.game.command.Commander;
import mage.game.command.Emblem;
import mage.game.command.emblems.MomirEmblem;
import mage.game.match.MatchImpl;
import mage.game.match.MatchOptions;
import mage.game.match.MatchPlayer;
import mage.players.PlayableObjectsList;
import mage.players.StubPlayer;
import mage.view.GameClientMessage;
import mage.view.GameView;
import mage.view.PlayerView;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Momir Basic emblem activation in the web client — the proxy half

 *
 * The emblem is a command-zone object that is NOT a CardView, so
 * `findCardViewById` could never resolve its playable ability's object key;
 * the GAME_SELECT labelling pass then took the fail-fast branch,
 * logged "… has no viewable card …" and DROPPED the choice. Momir Basic was
 * therefore unplayable in the web client: the browser never received the
 * emblem's activate-ability choice.
 *
 * The fix widens resolution for that path only:
 *  - a non-CardView command object WITH playable abilities is labelled from
 *    its own view (name + rules, action "activate"), generically (emblem /
 *    plane / dungeon alike — no Momir special case);
 * - genuinely unlabellable playable objects still hit the fail-fast
 *    (logged + surfaced to the browser + skipped);
 *  - a commander in the command zone (CommanderView extends CardView) keeps
 *    its "cast" labelling untouched.
 *
 * Technique: the REAL GAME_SELECT serialization path is driven end-to-end —
 * a real {@link GameView} built from a real {@link FakeGame} + real
 * {@code MomirEmblem}/{@code Commander} command objects, a real
 * {@link GameClientMessage}, the real private handler invoked by reflection,
 * and a recording {@link WebProxyClient.BrowserSender} as the browser. No
 * server and no mocks: the engine objects are genuine.
 */
public class WebProxyMomirEmblemLabelTest {

    /** Capture what the handler pushes to the browser. */
    private static final class RecordingSender implements WebProxyClient.BrowserSender {
        final List<String> sent = new ArrayList<String>();

        @Override
        public boolean send(String json) {
            sent.add(json);
            return true;
        }

        /** Every pushed message of the given JSON "type", parsed. */
        List<JsonObject> ofType(String type) {
            List<JsonObject> out = new ArrayList<JsonObject>();
            for (String s : sent) {
                JsonObject o = JsonParser.parseString(s).getAsJsonObject();
                if (o.has("type") && type.equals(o.get("type").getAsString())) {
                    out.add(o);
                }
            }
            return out;
        }
    }

    private WebProxyAuthCloseTest.RecordingAppender appender;
    private RecordingSender sender;
    private WebProxyClient client;

    @Before
    public void captureLogsAndBrowser() throws Exception {
        appender = new WebProxyAuthCloseTest.RecordingAppender();
        appender.setThreshold(Level.TRACE);
        Logger.getRootLogger().addAppender(appender);

        client = new WebProxyClient("momir-test");
        sender = new RecordingSender();
        client.setWsSender(sender);
    }

    @After
    public void detachLogs() {
        Logger.getRootLogger().removeAppender(appender);
    }

    // ── fixture helpers ──

    /** A two-player game with two real stub players and one match each. */
    private static FakeGame newGame(StubPlayer p1, StubPlayer p2) {
        FakeGame game = new FakeGame();
        MatchImpl match = new MatchImpl(new MatchOptions("t", "Momir Basic Two Player Duel", false)) {
            @Override
            public void startGame() {
            }
        };
        p1.setMatchPlayer(new MatchPlayer(p1, new Deck(), match));
        p2.setMatchPlayer(new MatchPlayer(p2, new Deck(), match));
        game.getState().addPlayer(p1);
        game.getState().addPlayer(p2);
        return game;
    }

    private static StubPlayer newPlayer(String name) {
        return new StubPlayer(name, RangeOfInfluence.ALL);
    }

    /** A playable-objects list built from ONE activated ability registered under the given key. */
    private static PlayableObjectsList playable(ActivatedAbility ability, UUID objectKey) {
        Map<UUID, List<ActivatedAbility>> objects = new HashMap<UUID, List<ActivatedAbility>>();
        objects.put(objectKey, Collections.singletonList(ability));
        return new PlayableObjectsList(objects);
    }

    /** Drive the REAL private GAME_SELECT handler over the real GameView. */
    private void runGameSelect(GameView gameView) throws Exception {
        GameClientMessage gcm = new GameClientMessage(gameView, null, "Select a card to play or pass priority");
        Method m = WebProxyClient.class.getDeclaredMethod(
                "handleGameSelect", GameClientMessage.class, GameView.class);
        m.setAccessible(true);
        m.invoke(client, gcm, gameView);
    }

    private long errorLogLines(String needle) {
        return appender.count(e -> e.getLevel() == Level.ERROR
                && e.getMessage() != null
                && e.getMessage().toString().contains(needle));
    }

    // ── 1. the emblem's choice is emitted, labelled from its own view ──

    @Test
    public void emblemActivateChoiceIsLabelledFromItsOwnView() throws Exception {
        StubPlayer p1 = newPlayer("P1");
        StubPlayer p2 = newPlayer("P2");
        FakeGame game = newGame(p1, p2);

        Emblem emblem = new MomirEmblem();
        emblem.setControllerId(p1.getId());
        game.getState().getCommand().add(emblem);

        GameView gameView = new GameView(game.getState(), game, p1.getId(), null);
        ActivatedAbility ability = (ActivatedAbility) emblem.getAbilities().iterator().next();
        gameView.setCanPlayObjects(playable(ability, emblem.getId()));

        runGameSelect(gameView);

        Assert.assertEquals("no 'no viewable card' ERROR for a playable emblem", 0,
                errorLogLines("has no viewable card"));

        List<JsonObject> prompts = sender.ofType("action_prompt");
        Assert.assertEquals("exactly one select prompt pushed", 1, prompts.size());
        JsonObject prompt = prompts.get(0);
        Assert.assertEquals("select prompt", "select", prompt.get("action_type").getAsString());

        JsonArray choices = prompt.getAsJsonArray("choices");
        Assert.assertEquals("the emblem choice was not dropped", 1, choices.size());
        JsonObject choice = choices.get(0).getAsJsonObject();
        Assert.assertEquals("labelled from the emblem's own view (name)",
                "Emblem Momir", choice.get("name").getAsString());
        Assert.assertEquals("labelled from the emblem's own view (id)",
                emblem.getId().toString(), choice.get("id").getAsString());
        Assert.assertEquals("an emblem is activate-only, never cast/land",
                "activate", choice.get("action").getAsString());
        Assert.assertTrue("the emblem's rules travel with the choice",
                choice.has("rules") && choice.getAsJsonArray("rules").size() > 0);

        // The click path: select_choice sends backings[index] via sendPlayerUUID,
        // so the backing must be the emblem's UUID for the activation to work.
        List<Object> backings = client.getPendingChoiceBackings();
        Assert.assertNotNull("pending backings set by the handler", backings);
        Assert.assertEquals("one backing", 1, backings.size());
        Assert.assertEquals("backing is the emblem's UUID", emblem.getId(), backings.get(0));

        Assert.assertTrue("no error pushed to the browser", sender.ofType("error").isEmpty());

        // Fixture sanity: the emblem is NOT a CardView, so before the fix this
        // exact object took the fail-fast branch.
        PlayerView view = gameView.getPlayers().get(0).getPlayerId().equals(p1.getId())
                ? gameView.getPlayers().get(0) : gameView.getPlayers().get(1);
        Assert.assertFalse("fixture sanity: the emblem is not a CardView",
                view.getCommandObjectList().get(0) instanceof mage.view.CardView);
    }

    // ── 2. the fail-fast still fires for a genuinely unlabellable object ──

    @Test
    public void unlabellablePlayableObjectStillSkipsAndSurfaces() throws Exception {
        StubPlayer p1 = newPlayer("P1");
        StubPlayer p2 = newPlayer("P2");
        FakeGame game = newGame(p1, p2);

        Emblem emblem = new MomirEmblem();
        emblem.setControllerId(p1.getId());
        game.getState().getCommand().add(emblem);

        GameView gameView = new GameView(game.getState(), game, p1.getId(), null);
        ActivatedAbility ability = (ActivatedAbility) emblem.getAbilities().iterator().next();
        // Mirrors PlayerImpl.getPlayableObjects: the SAME ability is also keyed by
        // a card container id that no view renders (the main-card key). Nothing in
        // any zone carries this id, so no view can label it.
        UUID unlabellableKey = UUID.randomUUID();
        Map<UUID, List<ActivatedAbility>> objects = new HashMap<UUID, List<ActivatedAbility>>();
        objects.put(unlabellableKey, Collections.singletonList(ability));
        gameView.setCanPlayObjects(new PlayableObjectsList(objects));

        runGameSelect(gameView);

        Assert.assertEquals("Fail-fast must still fire once", 1,
                errorLogLines("has no viewable card"));
        Assert.assertEquals("the unlabelled choice is still skipped", 0,
                sender.ofType("action_prompt").get(0)
                        .getAsJsonArray("choices").size());
        List<JsonObject> errors = sender.ofType("error");
        Assert.assertEquals("the gap is surfaced to the browser", 1, errors.size());
        Assert.assertTrue("error names the unlabellable object",
                errors.get(0).get("message").getAsString().contains(unlabellableKey.toString()));
    }

    // ── 3. commander regression: command-zone casts keep the 'cast' labelling ──

    @Test
    public void commanderInCommandZoneKeepsCastLabelling() throws Exception {
        StubPlayer p1 = newPlayer("P1");
        StubPlayer p2 = newPlayer("P2");
        FakeGame game = newGame(p1, p2);

        Card bears = new GrizzlyBears(p1.getId(), new CardSetInfo("Grizzly Bears", "M10", "180", Rarity.COMMON));
        game.loadCards(new HashSet<Card>(Collections.singletonList(bears)), p1.getId());
        Commander commander = new Commander(bears);
        game.getState().getCommand().add(commander);

        GameView gameView = new GameView(game.getState(), game, p1.getId(), null);
        // The commander's castability is registered under its source card's id,
        // which is the command object's id (Commander.getSourceId()).
        ActivatedAbility castAbility = commander.getAbilities().stream()
                .filter(a -> a instanceof ActivatedAbility)
                .map(a -> (ActivatedAbility) a)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("commander has no activated/cast ability"));
        gameView.setCanPlayObjects(playable(castAbility, commander.getId()));

        runGameSelect(gameView);

        Assert.assertEquals("no error for a resolvable commander", 0,
                errorLogLines("has no viewable card"));
        JsonArray choices = sender.ofType("action_prompt").get(0).getAsJsonArray("choices");
        Assert.assertEquals("commander choice emitted", 1, choices.size());
        JsonObject choice = choices.get(0).getAsJsonObject();
        Assert.assertEquals("commander name", "Grizzly Bears", choice.get("name").getAsString());
        Assert.assertEquals("command-zone CAST keeps the 'cast' action",
                "cast", choice.get("action").getAsString());
        Assert.assertTrue("cast keeps the mana cost",
                choice.has("manaCost") && choice.get("manaCost").getAsString().contains("1"));
        Assert.assertFalse("cast labelling must not carry emblem rules", choice.has("rules"));
    }
}
