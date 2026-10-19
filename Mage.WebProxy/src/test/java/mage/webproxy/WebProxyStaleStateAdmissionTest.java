package mage.webproxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.constants.MultiplayerAttackOption;
import mage.constants.RangeOfInfluence;
import mage.game.FakeGame;
import mage.game.match.MatchImpl;
import mage.game.match.MatchOptions;
import mage.game.match.MatchPlayer;
import mage.cards.decks.Deck;
import mage.game.mulligan.MulliganType;
import mage.interfaces.callback.ClientCallback;
import mage.interfaces.callback.ClientCallbackMethod;
import mage.players.StubPlayer;
import mage.view.GameView;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Stale / out-of-order GameView admission at the proxy relay
 * (the "header briefly read an older turn/phase" symptom).
 *
 * MEASURED ROOT CAUSE (the earlier test evidence): the XMage server
 * stamps callbacks with a per-session messageId in emission order
 * (Session.fireCallback), but the proxy RECEIVES them inverted — 96 inverted
 * adjacent messageId pairs in one live spectator session, producing 43 content
 * regressions in 2,567 state frames (43/43 explained by arrival inversion;
 * stateSeq was strictly monotonic on the wire, so the frontend's forward-gap
 * check could not see the staleness). This relay therefore drops a GameView
 * whose messageId is below the newest one applied, mirroring the desktop
 * client's "ignore un-synced message" rule (CallbackClientImpl.onCallback).
 *
 * Contract pinned here (technique as WebProxyMomirEmblemLabelTest: a REAL
 * WebProxyClient, a REAL FakeGame/StubPlayer GameView, a recording
 * BrowserSender as the browser — no server, no mocks):
 *
 *  1. in-order updates all reach the browser (no over-drop);
 *  2. an inverted (older-id) GameView is dropped — the browser never sees the
 *     older turn/phase under a newer stateSeq (the defect mechanics);
 *  3. GAME_INIT is never dropped (full rebuild / rejoin) and raises the
 *     watermark so a following update at a higher id still applies;
 *  4. a versionless callback (messageId 0 — a stand-in for any sender that did
 *     not stamp ids) still reaches the browser (guard can't misfire on it);
 *  5. the drop is logged loudly (one INFO line naming both ids).
 */
public class WebProxyStaleStateAdmissionTest {

    /** Capture what the proxy pushes to the browser. */
    private static final class RecordingSender implements WebProxyClient.BrowserSender {
        final List<String> sent = new ArrayList<String>();

        @Override
        public boolean send(String json) {
            sent.add(json);
            return true;
        }

        /**
         * The browser's view of turn+phase after each state-bearing frame, in
         * send order — reduced exactly like the frontend's deltaApplicator
         * (game_delta merges into the previous state; game_update/game_init
         * replace it). The proxy sends small changes as deltas, so counting only
         * game_update would undercount what the browser actually renders.
         */
        List<String> stateSummary() {
            List<String> out = new ArrayList<String>();
            int turn = -1;
            String phase = "null";
            for (String s : sent) {
                JsonObject o = JsonParser.parseString(s).getAsJsonObject();
                if (!o.has("type")) continue;
                String type = o.get("type").getAsString();
                JsonObject payload = null;
                if ("game_init".equals(type) || "game_update".equals(type)) {
                    payload = o.getAsJsonObject("state");
                    turn = -1;
                    phase = "null";
                } else if ("game_delta".equals(type)) {
                    payload = o.getAsJsonObject("delta");
                } else {
                    continue;
                }
                if (payload != null && payload.has("turn") && !payload.get("turn").isJsonNull()) {
                    turn = payload.get("turn").getAsInt();
                }
                if (payload != null && payload.has("phase") && !payload.get("phase").isJsonNull()) {
                    phase = payload.get("phase").getAsString();
                }
                out.add(turn + "/" + phase);
            }
            return out;
        }
    }

    private WebProxyAuthCloseTest.RecordingAppender appender;
    private RecordingSender sender;
    private WebProxyClient client;

    @Before
    public void setUp() throws Exception {
        appender = new WebProxyAuthCloseTest.RecordingAppender();
        appender.setThreshold(Level.TRACE);
        Logger.getRootLogger().addAppender(appender);

        client = new WebProxyClient("stale-admission-test");
        sender = new RecordingSender();
        client.setWsSender(sender);
    }

    @After
    public void tearDown() {
        Logger.getRootLogger().removeAppender(appender);
    }

    // ── fixtures ──

    /** A two-player game; turn number settable per view (turn+phase are derived from GameState). */
    private static FakeGame newGame(StubPlayer p1, StubPlayer p2) {
        FakeGame game = new FakeGame();
        MatchImpl match = new MatchImpl(new MatchOptions("t", "Two Player Duel", false)) {
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

    /** Deliver a state callback exactly like SessionImpl does: method + data + messageId. */
    private void deliver(ClientCallbackMethod method, GameView view, UUID gameId, int messageId) {
        ClientCallback cb = new ClientCallback(method, gameId,
                new mage.view.GameClientMessage(view, (java.util.Map<String, java.io.Serializable>) null, (String) null));
        cb.setMessageId(messageId);
        client.onCallback(cb);
    }

    private static UUID GAME = UUID.randomUUID();

    private long skipLogLines() {
        return appender.count(e -> e.getMessage() != null
                && e.getMessage().toString().contains("Skipping stale game state to browser"));
    }

    // ── 1. in-order: nothing dropped ──

    @Test
    public void inOrderUpdatesAllReachTheBrowser() {
        StubPlayer p1 = new StubPlayer("P1", RangeOfInfluence.ALL);
        StubPlayer p2 = new StubPlayer("P2", RangeOfInfluence.ALL);
        FakeGame game = newGame(p1, p2);

        game.getState().setTurnNum(2);
        deliver(ClientCallbackMethod.GAME_UPDATE, new GameView(game.getState(), game, p1.getId(), null), GAME, 10);
        game.getState().setTurnNum(3);
        deliver(ClientCallbackMethod.GAME_UPDATE_AND_INFORM, new GameView(game.getState(), game, p1.getId(), null), GAME, 11);
        game.getState().setTurnNum(4);
        deliver(ClientCallbackMethod.GAME_UPDATE, new GameView(game.getState(), game, p1.getId(), null), GAME, 12);

        Assert.assertEquals("all three in-order states reach the browser: " + sender.stateSummary(),
                3, sender.stateSummary().size());
        Assert.assertEquals("2/null", sender.stateSummary().get(0));
        Assert.assertEquals("4/null", sender.stateSummary().get(2));
        Assert.assertEquals("no drops for in-order callbacks", 0, skipLogLines());
    }

    // ── 2. inverted: the older-id frame is dropped ──

    @Test
    public void invertedOlderStateIsDroppedAndNeverShown() {
        StubPlayer p1 = new StubPlayer("P1", RangeOfInfluence.ALL);
        StubPlayer p2 = new StubPlayer("P2", RangeOfInfluence.ALL);
        FakeGame game = newGame(p1, p2);

        // newest first (id 30, turn 5), then the late-delivered older frame (id 25, turn 4)
        game.getState().setTurnNum(5);
        deliver(ClientCallbackMethod.GAME_UPDATE, new GameView(game.getState(), game, p1.getId(), null), GAME, 30);
        game.getState().setTurnNum(4);
        deliver(ClientCallbackMethod.GAME_UPDATE_AND_INFORM, new GameView(game.getState(), game, p1.getId(), null), GAME, 25);

        List<String> shown = sender.stateSummary();
        Assert.assertEquals("only the newer state reaches the browser: " + shown, 1, shown.size());
        Assert.assertEquals("the browser keeps turn 5 (the stale turn 4 was dropped)", "5/null", shown.get(0));
        Assert.assertEquals("the drop is logged exactly once", 1, skipLogLines());
    }

    // ── 3. game_init: never dropped, raises the watermark ──

    @Test
    public void gameInitIsNeverDroppedAndRaisesTheWatermark() {
        StubPlayer p1 = new StubPlayer("P1", RangeOfInfluence.ALL);
        StubPlayer p2 = new StubPlayer("P2", RangeOfInfluence.ALL);
        FakeGame game = newGame(p1, p2);

        // an update at id 40, then a LATE-arriving GAME_INIT (id 35, inverted)
        game.getState().setTurnNum(6);
        deliver(ClientCallbackMethod.GAME_UPDATE, new GameView(game.getState(), game, p1.getId(), null), GAME, 40);
        game.getState().setTurnNum(1);
        deliver(ClientCallbackMethod.GAME_INIT, new GameView(game.getState(), game, p1.getId(), null), GAME, 35);

        List<String> shown = sender.stateSummary();
        Assert.assertEquals("a GAME_INIT always reaches the browser (full rebuild): " + shown, 2, shown.size());
        Assert.assertEquals("no drop log for the init", 0, skipLogLines());

        // The watermark is a global MAX that never lowers (the desktop client's
        // lastMessages max rule): an update BELOW the newest applied id stays
        // stale even after the intervening init was applied.
        game.getState().setTurnNum(2);
        deliver(ClientCallbackMethod.GAME_UPDATE, new GameView(game.getState(), game, p1.getId(), null), GAME, 38);
        Assert.assertEquals("an update below the newest applied id stays dropped", 2, sender.stateSummary().size());
        Assert.assertEquals("exactly one stale drop logged", 1, skipLogLines());

        // a genuinely newer update still applies after the init/rebuild
        game.getState().setTurnNum(3);
        deliver(ClientCallbackMethod.GAME_UPDATE, new GameView(game.getState(), game, p1.getId(), null), GAME, 41);
        Assert.assertEquals("a newer update after the rebuild applies", 3, sender.stateSummary().size());
        Assert.assertEquals("3/null", sender.stateSummary().get(2));
    }

    // ── 4. versionless callback still delivered ──

    @Test
    public void callbackWithoutMessageIdStillReachesTheBrowser() {
        StubPlayer p1 = new StubPlayer("P1", RangeOfInfluence.ALL);
        StubPlayer p2 = new StubPlayer("P2", RangeOfInfluence.ALL);
        FakeGame game = newGame(p1, p2);

        game.getState().setTurnNum(3);
        deliver(ClientCallbackMethod.GAME_UPDATE, new GameView(game.getState(), game, p1.getId(), null), GAME, 50);
        // messageId 0 = unstamped sender; the guard must not treat it as stale
        game.getState().setTurnNum(4);
        deliver(ClientCallbackMethod.GAME_UPDATE, new GameView(game.getState(), game, p1.getId(), null), GAME, 0);

        Assert.assertEquals("both reach the browser: " + sender.stateSummary(), 2, sender.stateSummary().size());
        Assert.assertEquals("4/null", sender.stateSummary().get(1));
        Assert.assertEquals("no stale drop for an unstamped callback", 0, skipLogLines());
    }

    // ── 4b. the resync reply is still served (and is never self-dropped) ──

    @Test
    public void resyncReplyStillAppliesTheCachedNewestState() {
        StubPlayer p1 = new StubPlayer("P1", RangeOfInfluence.ALL);
        StubPlayer p2 = new StubPlayer("P2", RangeOfInfluence.ALL);
        FakeGame game = newGame(p1, p2);

        // normal updates build the cache
        game.getState().setTurnNum(7);
        deliver(ClientCallbackMethod.GAME_UPDATE, new GameView(game.getState(), game, p1.getId(), null), GAME, 60);
        int before = sender.stateSummary().size();

        // the browser's request_resync answers with the cached GameView
        // (WebProxyClient.resendLatestGameState — forceFullState, no callback
        // messageId). The guard must not interfere: the reply is delivered. The
        // browser's stateSeq advances (new send => new version), which is exactly
        // how the frontend's gap detection is satisfied.
        client.resendLatestGameState();

        List<String> shown = sender.stateSummary();
        Assert.assertEquals("the resync reply reached the browser: " + shown, before + 1, shown.size());
        Assert.assertEquals("the reply carries the newest cached state", "7/null", shown.get(shown.size() - 1));
        Assert.assertEquals("no stale drop logged for a resync", 0, skipLogLines());

        // a stale frame AFTER a resync is still dropped (watermark intact)
        game.getState().setTurnNum(6);
        deliver(ClientCallbackMethod.GAME_UPDATE, new GameView(game.getState(), game, p1.getId(), null), GAME, 55);
        Assert.assertEquals("stale frame after a resync still dropped", before + 1, sender.stateSummary().size());
        Assert.assertEquals("exactly one stale drop logged", 1, skipLogLines());
    }

    // ── 5. game_init re-bases a fresh channel (rejoin after a new game) ──

    @Test
    public void initRebasesSoTheNewGamesUpdatesApply() {
        StubPlayer p1 = new StubPlayer("P1", RangeOfInfluence.ALL);
        StubPlayer p2 = new StubPlayer("P2", RangeOfInfluence.ALL);
        FakeGame game = newGame(p1, p2);

        // game 1: a high-water mark is built up
        game.getState().setTurnNum(9);
        deliver(ClientCallbackMethod.GAME_UPDATE, new GameView(game.getState(), game, p1.getId(), null), GAME, 900);

        // a NEW game arrives on the same session: GAME_INIT carries the next
        // emission id (the server's counter is per-session and only grows), and
        // the per-game state restarts at turn 1
        UUID game2 = UUID.randomUUID();
        game.getState().setTurnNum(1);
        deliver(ClientCallbackMethod.GAME_INIT, new GameView(game.getState(), game, p1.getId(), null), game2, 901);
        deliver(ClientCallbackMethod.GAME_UPDATE, new GameView(game.getState(), game, p1.getId(), null), game2, 902);

        List<String> shown = sender.stateSummary();
        Assert.assertEquals("both new-game frames reach the browser: " + shown, 3, shown.size());
        Assert.assertEquals("init turn 1", "1/null", shown.get(1));
        Assert.assertEquals("post-init update turn 1", "1/null", shown.get(2));
        Assert.assertEquals("no stale drop across the game boundary", 0, skipLogLines());
    }
}
