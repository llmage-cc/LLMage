package org.mage.test.LLM;

import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.game.Game;
import mage.player.llm.LLMPlayer;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;
import org.mage.test.player.TestPlayer;

import java.util.List;

/**
 * Regression tests for the action list the LLM is offered.
 *
 * <p>"play land from graveyard" was never offered: land plays were
 * enumerated from the HAND only, so Crucible of Worlds / Icetill Explorer /
 * Ramunap Excavator produced no action at all even when a graveyard land was
 * engine-legal.
 *
 * <p>Per-game replay hygiene:
 * {@code replayLLMData} is keyed by GameState index and the player object is
 * reused across games of a match, so an exchange recorded in game 1 could be
 * attributed to a DIFFERENT frame in game 2 (46/88 records matched no frame
 * within ±5).
 *
 * <p>No live model calls: the stub answers "0" (pass), and the tests assert on
 * the action list / replay map instead of on choices.
 */
public class LLMGraveyardLandActionTest extends CardTestPlayerBaseLLM {

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if (getFullSimulatedPlayers().contains(name)) {
            TestLLMPlayer llmPlayer = new TestLLMPlayer(name, RangeOfInfluence.ONE, 0);
            llmPlayer.getLLMConfig()
                    .apiEndpoint("http://localhost:11434/v1/chat/completions")
                    .model("deepseek-v4-flash:cloud")
                    .maxTokens(65536)
                    .timeoutSeconds(120)
                    .deckAssessmentEnabled(false)
                    .verboseLogging(false);
            llmPlayer.reconnect();
            // Stub after reconnect (reconnect replaces the client wholesale).
            llmPlayer.injectTestClient(new PassLLMClient(llmPlayer.getLLMConfig()));
            TestPlayer testPlayer = new TestPlayer(llmPlayer);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    /** Answers "0" (pass priority) to everything — no network. */
    static class PassLLMClient extends mage.player.llm.LLMClient {
        PassLLMClient(mage.player.llm.LLMConfig config) { super(config); }

        @Override
        public mage.player.llm.LLMResponse chatConversation(String fullPrompt, String compactSummary,
                                                            String promptType, Integer turn, String phaseStep,
                                                            String effortOverride) {
            return new mage.player.llm.LLMResponse("stub", "0");
        }
    }

    private static LLMPlayer llm(TestPlayer p) {
        return (LLMPlayer) p.getComputerPlayer();
    }

    // ── Graveyard land play is offered when the engine allows it ──────

    /**
     * Crucible of Worlds ("You may play lands from your graveyard") + a land in
     * the graveyard → the action list must contain that land.
     */
    @Test
    public void graveyardLandOfferedWithCrucibleOfWorlds() {
        addCard(Zone.BATTLEFIELD, playerA, "Crucible of Worlds", 1);
        addCard(Zone.GRAVEYARD, playerA, "Forest", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 2);

        runCode("snapshot the offered actions", 1, PhaseStep.PRECOMBAT_MAIN, playerA,
                (info, player, game) -> {
                    List<String> actions = llm(playerA).actionDescriptionsForTest(game);
                    Assert.assertTrue("Crucible + graveyard Forest must offer a graveyard land play,"
                                    + " got: " + actions,
                            actions.stream().anyMatch(a -> a.contains("Play land from GRAVEYARD: Forest")));
                });

        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();
    }

    /**
     * The engine must gate it: without Crucible the graveyard Forest is NOT
     * playable, so advertising it would be the opposite bug.
     */
    @Test
    public void graveyardLandNotOfferedWithoutPermission() {
        addCard(Zone.GRAVEYARD, playerA, "Forest", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 2);

        runCode("snapshot the offered actions", 1, PhaseStep.PRECOMBAT_MAIN, playerA,
                (info, player, game) -> {
                    List<String> actions = llm(playerA).actionDescriptionsForTest(game);
                    Assert.assertFalse("no permission effect → no graveyard land play, got: " + actions,
                            actions.stream().anyMatch(a -> a.contains("Play land from GRAVEYARD")));
                });

        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();
    }

    /** Hand land plays still work exactly as before (regression guard). */
    @Test
    public void handLandStillOffered() {
        addCard(Zone.HAND, playerA, "Forest", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 2);

        runCode("snapshot the offered actions", 1, PhaseStep.PRECOMBAT_MAIN, playerA,
                (info, player, game) -> {
                    List<String> actions = llm(playerA).actionDescriptionsForTest(game);
                    Assert.assertTrue("hand land play must keep its (unchanged) entry, got: " + actions,
                            actions.stream().anyMatch(a -> a.equals("Play land: Forest")));
                });

        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();
    }

    // ── Replay records are scoped to the current game ─────────────────

    /**
     * Two games in one process, same player object: records from game 1 must not
     * survive into game 2's map. This mirrors the reuse that produced the stale
     * indices (MatchImpl.initGame hands the same player to game 2+).
     */
    @Test
    public void replayRecordsAreScopedPerGame() {
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 2);

        runCode("record for this game", 1, PhaseStep.PRECOMBAT_MAIN, playerA,
                (info, player, game) -> {
                    LLMPlayer p = llm(playerA);
                    Assert.assertNull("no records before the first exchange",
                            p.replayRecordGameIdForTest());
                    p.recordReplayExchangeForTest(game, 5, "priority", "summary", "0");
                    Assert.assertEquals("scope should be this game",
                            game.getId(), p.replayRecordGameIdForTest());
                    Assert.assertEquals(1, p.getReplayLLMData().get(5).size());
                });

        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();

        Game game1 = currentGame;
        // A second game-1 exchange, same frame index (two decisions at one frame).
        llm(playerA).recordReplayExchangeForTest(game1, 5, "priority", "game-1 leftover", "0");
        Assert.assertEquals("both game-1 exchanges share the frame-5 bucket", 2,
                llm(playerA).getReplayLLMData().get(5).size());

        Game game2 = new mage.game.TwoPlayerDuel(mage.constants.MultiplayerAttackOption.LEFT,
                RangeOfInfluence.ONE, mage.game.mulligan.MulliganType.GAME_DEFAULT.getMulligan(0),
                60, 20, 7);
        game2.addPlayer(playerA, new mage.cards.decks.Deck());
        llm(playerA).recordReplayExchangeForTest(game2, 5, "priority", "game-2 exchange", "0");

        Assert.assertEquals("game-2 id should now scope the map",
                game2.getId(), llm(playerA).replayRecordGameIdForTest());
        List<?> bucket = llm(playerA).getReplayLLMData().get(5);
        Assert.assertEquals("game 1's exchange must be dropped, only game 2's remains", 1, bucket.size());
        String summary = ((mage.player.llm.LLMExchangeRecord) bucket.get(0)).compactSummary;
        Assert.assertEquals("game-2 exchange", summary);
    }

    /** useDeck resets the scope so a fresh game starts with an empty map. */
    @Test
    public void useDeckResetsReplayScope() {
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 2);

        runCode("record for this game", 1, PhaseStep.PRECOMBAT_MAIN, playerA,
                (info, player, game) -> {
                    llm(playerA).recordReplayExchangeForTest(game, 3, "priority", "before reset", "0");
                    Assert.assertEquals(1, llm(playerA).getReplayLLMData().size());
                });

        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();

        Assert.assertTrue("test setup: records exist before the reset",
                !llm(playerA).getReplayLLMData().isEmpty());
        llm(playerA).useDeck(new mage.cards.decks.Deck(), currentGame);
        Assert.assertTrue("useDeck must empty the replay map",
                llm(playerA).getReplayLLMData().isEmpty());
        Assert.assertNull("useDeck must clear the game scope",
                llm(playerA).replayRecordGameIdForTest());
    }
}
