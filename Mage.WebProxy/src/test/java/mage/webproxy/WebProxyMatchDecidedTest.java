package mage.webproxy;

import mage.cards.decks.DeckValidator;
import mage.game.FakeMatch;
import mage.game.Table;
import mage.game.match.Match;
import mage.game.match.MatchOptions;
import mage.players.Player;
import mage.players.PlayerType;
import mage.players.StubPlayer;
import mage.constants.RangeOfInfluence;
import mage.view.MatchPlayerView;
import mage.view.MatchView;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Unit tests for the match-level match-decided decision
 * ({@link WebProxyClient#matchDecidedFrom}) that gates the end-of-game
 * {@code currentGameId} clear — and, through the partial-payload branch, the
 * watcher's stale-id clear.
 *
 * Root cause it pins
 * the clear
 * used to be judged from {@code GameEndView.getWins()/getLoses()} — but
 * GameEndView fills {@code wins} ONLY for the viewer's own MatchPlayer and never
 * assigns {@code loses} at all (a getter with no writer). A session that is not
 * the match winner therefore never cleared its game id: it kept a finished game
 * for the rest of the page life, and WebProxySession.handleReplayLoad's live-game
 * guard refused every subsequent {@code replay_load} in it. The fix judges the
 * MATCH instead ({@link MatchView#getPlayerList()}: one row per player with match
 * wins + quit).
 *
 * Fixtures are built through the REAL production path — {@link Table} +
 * {@link FakeMatch} + {@link MatchView} — not by injecting fields, so the test
 * exercises the same MatchPlayer bookkeeping the server's END_GAME_INFO payload
 * is built from (MatchImpl.addWin / setQuit → MatchView.initMatchTable).
 * {@link FakeMatch} is XMage's own test double (its constructor throws if anyone
 * tries to actually play it), so no game engine, card DB or live server is needed.
 *
 * The Bo3 between-game case is the one that must NOT clear: game 1 of a Bo3 is not
 * a decided match, and that session must keep its id + buffered history so a
 * reload can rejoin during sideboarding.
 */
public class WebProxyMatchDecidedTest {

    // ── fixtures through the real Table/Match/MatchView path ──

    /**
     * A match table (non-tournament) with the given winsNeeded, one match player
     * per name, and the given (wins, quit) applied the same way the server does:
     * {@code MatchPlayer.addWin()} / {@code setQuit(true)}.
     */
    private static MatchView matchView(int winsNeeded, int[][] results, String[] names) {
        MatchOptions options = new MatchOptions("test match", "Two Player Duel", false);
        options.setWinsNeeded(winsNeeded);
        Match match = new FakeMatch();
        // FakeMatch builds its own MatchOptions; replace it with ours so winsNeeded
        // reaches MatchPlayer (MatchPlayer ctor reads match.getWinsNeeded()).
        setOptions(match, options);

        for (int i = 0; i < names.length; i++) {
            Player player = new StubPlayer(names[i], RangeOfInfluence.ALL);
            match.addPlayer(player, null);
            for (int w = 0; w < results[i][0]; w++) {
                match.getPlayer(player.getId()).addWin();
            }
            if (results[i][1] != 0) {
                match.getPlayer(player.getId()).setQuit(true);
            }
        }

        Table table = new Table(UUID.randomUUID(), "Two Player Duel", "test table",
                "tester", (DeckValidator) null, Arrays.asList(PlayerType.HUMAN, PlayerType.HUMAN),
                null, match, Collections.emptySet(), false);
        return new MatchView(table);
    }

    private static void setOptions(Match match, MatchOptions options) {
        try {
            java.lang.reflect.Field f = match.getClass().getSuperclass().getDeclaredField("options");
            f.setAccessible(true);
            f.set(match, options);
        } catch (Exception e) {
            throw new IllegalStateException("could not set MatchOptions on FakeMatch", e);
        }
    }

    /** A BO3 summary at the given score (game 1 of a Bo3, say). */
    private static MatchView bo3(int wins0, int wins1) {
        return matchView(2, new int[][] { { wins0, 0 }, { wins1, 0 } }, new String[] { "A", "B" });
    }

    /** Sanity: the fixture really produces match-level rows (guards the tests below). */
    @Test
    public void fixtureCarriesMatchRows() {
        MatchView view = bo3(2, 0);
        List<MatchPlayerView> rows = view.getPlayerList();
        Assert.assertNotNull("MatchView.playerList must be filled by the fixture", rows);
        Assert.assertEquals("one row per match player", 2, rows.size());
        Assert.assertEquals("row carries the match win count", 2, rows.get(0).getWins());
        Assert.assertFalse("a winner row is not a quit row", rows.get(0).hasQuit());
    }

    // ── the prod shape: a decided match, from the match's own rows ──

    @Test
    public void spectatorWithDecidedMatchIsDecided() {
        // Viewer numbers are meaningless for a watcher (GameEndView leaves wins at 0
        // when `you` is null), so the match rows alone must decide it.
        MatchView match = bo3(2, 0);
        Assert.assertTrue("2-0 in a Bo3 is a decided match, viewer wins=0 notwithstanding",
                WebProxyClient.matchDecidedFrom(match, 2, 0, 0));
    }

    @Test
    public void losingViewerOfADecidedMatchIsDecided() {
        MatchView match = bo3(2, 0);
        Assert.assertTrue("the winner's 2 wins decide the match for the loser's session too",
                WebProxyClient.matchDecidedFrom(match, 2, 0, 0));
    }

    // ── THE REGRESSION CASE: Bo3 game 1 must keep the id (sideboard rejoin window) ──

    @Test
    public void bo3GameOneIsNotDecided() {
        MatchView match = bo3(1, 0);
        Assert.assertFalse("game 1 of a Bo3 (1-0, winsNeeded 2) is NOT a decided match"
                        + " — the between-game sideboard window must keep the id",
                WebProxyClient.matchDecidedFrom(match, 2, 1, 0));
    }

    @Test
    public void bo3GameOneWithSpectatorViewerIsNotDecided() {
        MatchView match = bo3(1, 0);
        Assert.assertFalse("same for a watcher (viewer wins=0 must not invent a verdict)",
                WebProxyClient.matchDecidedFrom(match, 2, 0, 0));
    }

    @Test
    public void bo3GameTwoDecidesWhenSomeoneReachesTwo() {
        MatchView match = bo3(1, 1);
        Assert.assertTrue("1-1 in a Bo3: one more game was played and won (2-1)",
                WebProxyClient.matchDecidedFrom(matchView(2, new int[][] { { 2, 0 }, { 1, 0 } },
                        new String[] { "A", "B" }), 2, 0, 0));
        Assert.assertFalse("...but an undecided 1-1 is not", WebProxyClient.matchDecidedFrom(match, 2, 0, 0));
    }

    // ── quit decides a match even without the score ──

    @Test
    public void quitDecidesMatch() {
        MatchView match = matchView(2, new int[][] { { 0, 1 }, { 1, 0 } }, new String[] { "A", "B" });
        Assert.assertTrue("a quit ends the match (MatchImpl.checkIfMatchEnds: activePlayers < 2)",
                WebProxyClient.matchDecidedFrom(match, 2, 0, 0));
    }

    @Test
    public void quitDecidesMatchEvenWithUnknownWinsNeeded() {
        MatchView match = matchView(2, new int[][] { { 0, 1 }, { 1, 0 } }, new String[] { "A", "B" });
        Assert.assertTrue("an explicit quit decides even when the payload carries no target",
                WebProxyClient.matchDecidedFrom(match, 0, 0, 0));
    }

    // ── missing data must not fabricate a verdict ──

    @Test
    public void unknownWinsNeededNeverDecidesOnScoreAlone() {
        MatchView match = bo3(2, 2);
        Assert.assertFalse("winsNeeded=0 must not be read as '0 wins needed'",
                WebProxyClient.matchDecidedFrom(match, 0, 0, 0));
    }

    @Test
    public void missingMatchViewFallsBackToViewerRows() {
        Assert.assertTrue("the single-game (winsNeeded 1) winner still clears without a match view",
                WebProxyClient.matchDecidedFrom(null, 1, 1, 0));
        Assert.assertFalse("...and an undecided single game still keeps its id",
                WebProxyClient.matchDecidedFrom(null, 1, 0, 0));
    }

    @Test
    public void tournamentSummaryFallsBackToViewerRows() {
        // Tournament rows carry POINTS, not match wins — judging a tournament table by
        // them would be a guess, so the helper must use the pre-existing viewer rule.
        // (Every Tournament impl in Mage is abstract; a no-op runTournament is enough
        //  for a MatchView that only reads name/points/deck off the players.)
        MatchOptions options = new MatchOptions("t", "Two Player Duel", false);
        options.setWinsNeeded(2);
        Match match = new FakeMatch();
        setOptions(match, options);
        Player p = new StubPlayer("A", RangeOfInfluence.ALL);
        match.addPlayer(p, null);
        mage.game.tournament.TournamentOptions tOptions =
                new mage.game.tournament.TournamentOptions("t", "Two Player Duel", false);
        tOptions.getMatchOptions().setWinsNeeded(2);
        mage.game.tournament.Tournament tournament =
                new mage.game.tournament.TournamentSingleElimination(tOptions) {
                    @Override
                    protected void runTournament() {
                        // never played in this test — MatchView reads the player list only
                    }

                    @Override
                    public void nextStep() {
                        // never stepped in this test
                    }
                };
        // MatchView.initTournamentTable calls getBoosterInfo() and appends it; the
        // field is only set for limited tournaments.
        tournament.setBoosterInfo("");
        Table table = new Table(UUID.randomUUID(), "Two Player Duel", "t", "tester",
                (DeckValidator) null, Arrays.asList(PlayerType.HUMAN, PlayerType.HUMAN),
                null, tournament, Collections.emptySet(), false);
        MatchView matchView = new MatchView(table);
        Assert.assertTrue("a tournament MatchView is recognized as such", matchView.isTournament());
        Assert.assertFalse("tournament points are not match wins (no clear from a bogus row)",
                WebProxyClient.matchDecidedFrom(matchView, 2, 0, 0));
        Assert.assertTrue("a seated winner still clears via the pre-existing fallback",
                WebProxyClient.matchDecidedFrom(matchView, 2, 2, 0));
    }

    @Test
    public void singleGameDecidedOnFirstWin() {
        MatchView match = matchView(1, new int[][] { { 1, 0 }, { 0, 0 } }, new String[] { "A", "B" });
        Assert.assertTrue("a single game is decided the moment it has a winner",
                WebProxyClient.matchDecidedFrom(match, 1, 0, 0));
    }

    @Test
    public void singleGameDrawIsNotDecided() {
        MatchView match = matchView(1, new int[][] { { 0, 0 }, { 0, 0 } }, new String[] { "A", "B" });
        Assert.assertFalse("a drawn single game decides nothing (no winner, no quit)",
                WebProxyClient.matchDecidedFrom(match, 1, 0, 0));
    }
}
