import mage.cards.CardSetInfo;
import mage.cards.basiclands.Forest;
import mage.cards.decks.Deck;
import mage.collectors.DataCollectorServices;
import mage.constants.MultiplayerAttackOption;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Rarity;
import mage.game.Game;
import mage.game.GameOptions;
import mage.game.GameState;
import mage.game.GameStates;
import mage.game.TwoPlayerDuel;
import mage.game.TwoPlayerMatch;
import mage.game.match.MatchOptions;
import mage.game.mulligan.MulliganType;
import mage.player.llm.LLMClient;
import mage.player.llm.LLMConfig;
import mage.player.llm.LLMExchangeRecord;
import mage.player.llm.LLMPlayer;
import mage.player.llm.LLMResponse;
import mage.players.Player;
import mage.players.PlayerType;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectOutputStream;
import java.io.ObjectOutput;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Regression harness for cross-game replayLLMData scoping.
 *
 * <pre>
 *   bash scripts/verify-replay-llm-crossgame.sh [--corpus DIR]...
 * </pre>
 *
 * What it proves (all offline — no server, no network, no live model):
 *
 *  [1] manufacture — runs a REAL two-game (then three-game) match through the
 *      engine's own reuse path: MatchImpl.initGame hands the SAME player objects
 *      to game 2+, and each game is saved in the production replay format
 *      (mirror of GameController.saveGame). Each saved artifact is reloaded
 *      through the production load path (mirror of GameReplay.loadGame).
 *      Assertions:
 *        (a) the replay map is empty at every game start (useDeck per-game
 *            reset — GameImpl.addPlayer calls it for reused players too);
 *        (b) every LLM record binds to a frame OF ITS OWN GAME: the record's
 *            stateIndex is inside the game's frame range (+-5, the staleness
 *            definition) AND frame[stateIndex] carries the record's own
 *            (turnNum, phaseStep);
 *        (c) an earlier game's records do NOT appear in a later game's artifact
 *            (records are tagged per game by the stub's thinking text, so the
 *            comparison has no false positives from legitimately identical
 *            decisions like "keep" at index 0 of both games).
 *
 *  [2] corpus — walks every *.game in the given directories (or ./saved),
 *      skipping artifacts with no serialized LLM records, and asserts the same
 *      binding property for each LLM-bearing file. This is the regression sweep
 *      over the real replay corpus.
 *
 *  [3] selftest — injects four records into a clean artifact: one out-of-range
 *      ("carried over from an earlier game": index beyond this game's frames,
 *      the carried-over signature), one in-range-but-misbound, one pre-save-shaped
 *      record with the WRONG shape (must fire `pre_save_unexpected`), and one
 *      late-bound record matching only frame[idx-1] (must stay a hard misbound —
 *      the direction flip is never family). Asserts the walker DETECTS all of
 *      them. Proves the harness discriminates (non-vacuity).
 *
 * Known adjacent family, now ASSERTED: prompts issued from
 * Step.beginStep() BEFORE the engine's next saveState are recorded one frame
 * early. The measured family on this tree is exactly the cleanup-step discard
 * target prompt (CleanupStep.beginStep -> discardToMax -> chooseTarget ->
 * chatAndRecord non-combat overload -> stateIdx = getSize()-1): frame[idx] is
 * "End / end turn step" of turn T and frame[idx+1] is "End / cleanup step" of
 * the same turn T, while the record carries (T, "End / cleanup step"). Combat
 * declarations have their own +1 index adjustment (combatDeclaration=true) and
 * are NOT part of this family. The walker now asserts the family is limited to
 * that shape: any pre-save-bound record that does not match it fails as
 * `pre_save_unexpected` — so a new beginStep-origin prompt path (or a
 * direction flip / cross-turn bind) cannot silently join the family.
 *
 * Why this does not change displayed frame content: the family record's index
 * is in-range (idx+1 < frames is the classifier's precondition), so the viewer
 * can attach it; it is an ADDITIVE conversation entry at frame[idx] labelled
 * with its own accurate (turn, step) text — no frame's GameState is touched and
 * no record is hidden. The assertion pins attachability (idx in range) and the
 * same-turn binding (never spanning a game/turn boundary).
 *
 * Exit codes: 0 = all checks pass; 1 = a check failed; 2 = usage error.
 */
public final class VerifyReplayLlmCrossgame {

    /** Staleness tolerance ("no frame within +-5"). */
    private static final int RANGE_TOL = 5;

    /** frameStep() text of the frame immediately before a cleanup-step save. */
    private static final String END_TURN_FRAME = "End / end turn step";
    /** frameStep() text of the cleanup-step frame the family record describes. */
    private static final String CLEANUP_FRAME = "End / cleanup step";

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: VerifyReplayLlmCrossgame manufacture <workDir> [games=3] [turns=12,2,2]");
            System.err.println("       VerifyReplayLlmCrossgame walk <dirOrFile>...");
            System.err.println("       VerifyReplayLlmCrossgame selftest <workDir>");
            System.exit(2);
        }
        switch (args[0]) {
            case "manufacture": {
                Path work = Paths.get(args[1]);
                int games = args.length > 2 ? Integer.parseInt(args[2]) : 3;
                int[] turns = args.length > 3 ? parseIntList(args[3])
                        : defaultTurns(games);
                manufacture(work, games, turns);
                break;
            }
            case "walk": {
                int[] tally = new int[6]; // files, llmFiles, records, stale, misboundTrue, loadFail
                int[] family = new int[3]; // misboundPreSave, tail, preSaveUnexpected
                boolean[] anyFail = {false};
                for (int i = 1; i < args.length; i++) {
                    walkPath(Paths.get(args[i]), tally, family, anyFail);
                }
                System.out.println();
                System.out.printf("WALK TOTAL: files=%d llmFiles=%d records=%d stale(+-%d)=%d"
                                + " misbound=%d misbound_pre_save=%d pre_save_unexpected=%d tail=%d loadFail=%d%n",
                        tally[0], tally[1], tally[2], RANGE_TOL, tally[3],
                        tally[4], family[0], family[2], family[1], tally[5]);
                if (tally[5] > 0 || tally[3] > 0 || tally[4] > 0 || family[2] > 0) anyFail[0] = true;
                if (tally[1] == 0) {
                    System.out.println("FAIL: no LLM-bearing artifacts found to check");
                    anyFail[0] = true;
                }
                if (anyFail[0]) failures++;
                break;
            }
            case "selftest": {
                selftest(Paths.get(args[1]));
                break;
            }
            default:
                System.err.println("unknown mode: " + args[0]);
                System.exit(2);
        }
        if (failures > 0) {
            System.out.println("RESULT: FAIL (" + failures + " failing phase/check group(s))");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    private static int[] defaultTurns(int games) {
        int[] t = new int[games];
        t[0] = 12; // long game 1: fills the map so a game-2 leak would be visible
        for (int i = 1; i < games; i++) t[i] = 2;
        return t;
    }

    private static int[] parseIntList(String csv) {
        String[] parts = csv.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim());
        return out;
    }

    // ==================================================================
    //  [1] manufacture
    // ==================================================================

    private static void manufacture(Path work, int games, int[] turns) throws Exception {
        Path savedDir = work.resolve("saved");
        Files.createDirectories(savedDir);
        DataCollectorServices.init(false, false);

        // One match, one pair of player objects REUSED across games — the exact
        // reuse that produced the stale cross-game indices (MatchImpl.initGame
        // hands the same player object to game 2+).
        MatchOptions opts = new MatchOptions("u14-crossgame-verify", "Two Player Duel", false);
        opts.setWinsNeeded(2); // Bo3 shape: the match continues past game 1
        opts.getPlayerTypes().add(PlayerType.COMPUTER_LLM);
        opts.getPlayerTypes().add(PlayerType.COMPUTER_LLM);
        TwoPlayerMatch match = new TwoPlayerMatch(opts);
        match.setTableId(UUID.randomUUID());
        TaggedPlayer a = new TaggedPlayer("VerifyA");
        TaggedPlayer b = new TaggedPlayer("VerifyB");
        Deck dA = forestDeck("A");
        Deck dB = forestDeck("B");
        match.addPlayer(a, dA);
        match.addPlayer(b, dB);
        match.startMatch();

        List<UUID> gameIds = new ArrayList<>();
        for (int g = 1; g <= games; g++) {
            match.startGame(); // fresh TwoPlayerDuel; initGame() re-adds the SAME players
            Game game = match.getGame();
            game.setSaveGame(true); // replay mode (production: configSettings.isSaveGameActivated())
            GameOptions go = new GameOptions(); // mirror TableController.startGame's GameOptions wiring
            go.stopOnTurn = turns[g - 1];       // deterministic end (same option the test framework uses)
            go.stopAtStep = PhaseStep.UNTAP;
            game.setGameOptions(go);

            // BEFORE the game plays: the map must be empty for a fresh game —
            // useDeck() is the engine's per-game reset point (called from
            // GameImpl.addPlayer for every game, including reused players).
            Map<Integer, List<String>> before = summarize(a.getReplayLLMData());
            check("G" + g + ": replay map empty at game start (useDeck per-game reset)",
                    before.isEmpty(), "leftover buckets: " + before);

            TaggedPlayer.gameTag = "g" + g; // stub tags every record with the current game
            game.start(a.getId());
            TaggedPlayer.gameTag = null;
            gameIds.add(game.getId());

            // Mirror of GameController.saveGame (its serialization block).
            saveArtifact(game, savedDir);

            int records = countRecords(a.getReplayLLMData()) + countRecords(b.getReplayLLMData());
            System.out.printf("G%d saved: gameId=%s frames=%d records=%d  %s%n",
                    g, shortId(game.getId()), game.getGameStates().getSize(), records,
                    savedDir.resolve(game.getId() + ".game"));
            check("G" + g + ": game produced at least one LLM record", records > 0,
                    "records=0 — harness needs records to check scoping");

            match.endGame();
        }

        // ---- reload every artifact through the production load path ----
        System.out.println();
        System.out.println("--- reloaded artifacts (production load path) ---");
        int[] tally = new int[6];
        int[] family = new int[3];
        boolean[] anyFail = {false};
        for (UUID id : gameIds) {
            walkFile(savedDir.resolve(id + ".game"), tally, family, anyFail);
        }
        check("artifact walk: 0 stale(+-" + RANGE_TOL + ")", tally[3] == 0, tally[3] + " stale");
        check("artifact walk: 0 misbound (outside the pre-save timing family)",
                tally[4] == 0, tally[4] + " misbound");
        check("artifact walk: 0 pre_save_unexpected (family limited to the"
                        + " characterized cleanup-step shape)",
                family[2] == 0, family[2] + " unexpected");
        // Non-vacuity: the committed 12-turn scenario must actually exercise the
        // characterized family (the assertion above is only meaningful if the
        // engine still produces family-shaped records). Smaller custom turn
        // budgets may legitimately produce none, so the check applies to the
        // >=12-turn first game the committed script always uses.
        check("artifact walk: characterized family is exercised in the 12-turn game"
                        + " (assertion non-vacuous)",
                turns[0] < 12 || family[0] >= 1, "family=" + family[0]);
        if (family[0] > 0) {
            System.out.println("  note: " + family[0] + " record(s) are the characterized"
                    + " beginStep-origin cleanup-step family (frame[idx]=end-turn,"
                    + " frame[idx+1]=cleanup, asserted shape)");
        }

        // ---- cross-game leak check on the reloaded artifacts ----
        // Records carry the stub's per-game thinking tag, so a tuple that
        // legitimately repeats across games ("keep" at index 0) cannot
        // masquerade as a leak.
        for (int g = 2; g <= games; g++) {
            Game earlier = loadGame(savedDir.resolve(gameIds.get(g - 2) + ".game"));
            Game later = loadGame(savedDir.resolve(gameIds.get(g - 1) + ".game"));
            String earlierTag = "verify-stub-thinking-g" + (g - 1);
            List<String> leaked = new ArrayList<>();
            for (Player p : later.getPlayers().values()) {
                if (!(p instanceof LLMPlayer)) continue;
                for (Map.Entry<Integer, List<LLMExchangeRecord>> e
                        : ((LLMPlayer) p).getReplayLLMData().entrySet()) {
                    for (LLMExchangeRecord r : e.getValue()) {
                        if (earlierTag.equals(r.thinking)) {
                            leaked.add("idx=" + e.getKey() + " " + r.promptType + " " + r.answer);
                        }
                    }
                }
            }
            check("G" + g + " artifact contains no G" + (g - 1) + " record", leaked.isEmpty(),
                    leaked.size() + " leaked: " + head(leaked));

            // And the same metric, computed on the later artifact directly.
            int stale = staleCount(later);
            System.out.printf("headline metric: G%d artifact stale(+-%d)=%d/%d%n",
                    g, RANGE_TOL, stale, countRecords(firstLlmPlayer(later).getReplayLLMData()));
            check("G" + g + " artifact stale(+-" + RANGE_TOL + ") == 0", stale == 0, stale + " stale");
        }

        // ---- guard isolation: the recordGameId backstop ----
        // Simulate the one path the engine never takes (a record for a new game
        // id WITHOUT a useDeck reset in between): the first write of the new
        // game must drop the previous game's buckets.
        {
            LLMPlayer lp = a;
            Game current = loadGame(savedDir.resolve(gameIds.get(gameIds.size() - 1) + ".game"));
            int loadedBuckets = lp.getReplayLLMData().size();
            // The live player object still carries the last game's records (the
            // artifact is a snapshot; the live map is the production state at
            // save time). Record into a brand-new game id without useDeck:
            TwoPlayerDuel fresh = new TwoPlayerDuel(MultiplayerAttackOption.LEFT,
                    RangeOfInfluence.ONE, MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);
            lp.recordReplayExchangeForTest(fresh, 7, "priority", "guard isolation", "0");
            check("guard backstop: first write of a new game id drops prior buckets",
                    lp.getReplayLLMData().size() == 1
                            && lp.getReplayLLMData().get(7) != null
                            && lp.getReplayLLMData().get(7).size() == 1,
                    "map=" + summarize(lp.getReplayLLMData()));
            check("guard backstop: scope is the new game id",
                    fresh.getId().equals(lp.replayRecordGameIdForTest()),
                    "getReplayLLMData scope=" + lp.replayRecordGameIdForTest()
                            + " fresh=" + fresh.getId());
            // (loadedBuckets is informational: a live reused player's map is
            // cleared by useDeck at the next game start — that reset is the one
            // asserted in the per-game checks above.)
            System.out.println("  (guard isolation ran on the live reused player;"
                    + " last artifact had " + loadedBuckets + " buckets for reference)");
        }
    }

    private static class TaggedPlayer extends LLMPlayer {
        /** Tag the stub stamps into `thinking` so per-game record provenance is exact. */
        static volatile String gameTag = null;

        TaggedPlayer(String name) {
            super(name, RangeOfInfluence.ONE, 0);
            this.client = new TaggedStubClient(getLLMConfig());
            getLLMConfig().modelContextWindow(131072); // explicit: no network detection, ever
            getLLMConfig().apiEndpoint("http://127.0.0.1:9/v1/chat/completions")
                    .model("verify-stub-model").maxTokens(4096).timeoutSeconds(5)
                    .deckAssessmentEnabled(false).verboseLogging(false);
        }

        @Override
        protected boolean isTestHarness() {
            return true; // no startup probes in the harness
        }
    }

    /** Offline stub: valid answers for every prompt type the engine asks. */
    private static class TaggedStubClient extends LLMClient {
        TaggedStubClient(LLMConfig c) {
            super(c);
        }

        private LLMResponse answer(String promptType) {
            String answer;
            if ("mulligan".equals(promptType)) answer = "keep";
            else if ("attackers".equals(promptType)) answer = "none";
            else if ("blockers".equals(promptType)) answer = "none";
            else if ("target".equals(promptType)) answer = "1";
            else if ("yesno".equals(promptType)) answer = "no";
            else answer = "0";
            String tag = TaggedPlayer.gameTag;
            return new LLMResponse(tag == null ? "verify-stub-thinking" : "verify-stub-thinking-" + tag, answer);
        }

        @Override
        public LLMResponse chatConversation(String fullPrompt, String compactSummary,
                                            String promptType, Integer turn, String phaseStep,
                                            String effortOverride) {
            return answer(promptType);
        }

        @Override
        public LLMResponse chatConversationWithStop(String fullPrompt, String compactSummary,
                                                    String promptType, Integer turn, String phaseStep,
                                                    String effortOverride, String stopSequence) {
            return answer(promptType);
        }

        @Override
        public LLMResponse chat(String systemPrompt, String userPrompt) {
            return new LLMResponse("verify-stub", "stub");
        }
    }

    private static Deck forestDeck(String tag) {
        Deck d = new Deck();
        d.setName("verify-forests-" + tag);
        for (int i = 0; i < 40; i++) {
            d.getCards().add(new Forest(null, new CardSetInfo("Forest", "M10", "246", Rarity.LAND)));
        }
        return d;
    }

    /** Mirror of GameController.saveGame's serialization block (zstd replay container). */
    private static void saveArtifact(Game game, Path savedDir) throws Exception {
        Path file = savedDir.resolve(game.getId().toString() + ".game");
        try (OutputStream fileStream = new FileOutputStream(file.toFile());
             OutputStream buffer = new BufferedOutputStream(fileStream);
             ObjectOutput output = new ObjectOutputStream(mage.server.game.ReplayCodec.wrapForWrite(buffer))) {
            output.writeObject(game);
            output.writeObject(game.getGameStates());
            output.flush();
        }
    }

    /** Mirror of GameReplay.loadGame (same 4-step path). */
    private static Game loadGame(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file);
             InputStream buffer = new BufferedInputStream(in);
             InputStream codec = mage.server.game.ReplayCodec.wrapForRead(buffer);
             ObjectInput input = new mage.util.CopierObjectInputStream(
                     mage.server.util.PluginUtil.classLoader, codec)) {
            Game loadGame = (Game) input.readObject();
            GameStates states = (GameStates) input.readObject();
            loadGame.loadGameStates(states);
            loadGame.rebuildGameCardsFromStates();
            return loadGame;
        }
    }

    private static LLMPlayer firstLlmPlayer(Game game) {
        for (Player p : game.getPlayers().values()) {
            if (p instanceof LLMPlayer) return (LLMPlayer) p;
        }
        throw new IllegalStateException("no LLM player in loaded game");
    }

    // ==================================================================
    //  [2] walk — the corpus sweep
    // ==================================================================

    private static void walkPath(Path path, int[] tally, int[] family, boolean[] anyFail) throws Exception {
        if (Files.isDirectory(path)) {
            List<Path> files = new ArrayList<>();
            try (java.util.stream.Stream<Path> s = Files.list(path)) {
                s.filter(p -> p.toString().endsWith(".game")).sorted().forEach(files::add);
            }
            for (Path f : files) walkFile(f, tally, family, anyFail);
        } else {
            walkFile(path, tally, family, anyFail);
        }
    }

    private static void walkFile(Path file, int[] tally, int[] family, boolean[] anyFail) throws Exception {
        tally[0]++;
        String label = file.getFileName().toString();
        boolean hasLlm;
        try {
            hasLlm = hasLlmRecords(file);
        } catch (Throwable t) {
            System.out.println("SKIP " + label + " (unreadable container: " + t.getMessage() + ")");
            return;
        }
        if (!hasLlm) {
            System.out.println("SKIP " + label + " (no serialized LLM records)");
            return;
        }
        tally[1]++;
        Game game;
        try {
            game = loadGame(file);
        } catch (Throwable t) {
            tally[5]++;
            anyFail[0] = true;
            System.out.println("FAIL " + label + " — load failed: " + t);
            return;
        }
        if (game == null) {
            tally[5]++;
            anyFail[0] = true;
            System.out.println("FAIL " + label + " — load returned null");
            return;
        }
        int frames = game.getGameStates().getSize();
        int records = 0, stale = 0, misbound = 0;
        int familyRecords = 0, familyUnexpected = 0;
        List<String> samples = new ArrayList<>();
        for (Player p : game.getPlayers().values()) {
            if (!(p instanceof LLMPlayer)) continue;
            LLMPlayer lp = (LLMPlayer) p;
            for (Map.Entry<Integer, List<LLMExchangeRecord>> e : lp.getReplayLLMData().entrySet()) {
                int idx = e.getKey();
                for (LLMExchangeRecord r : e.getValue()) {
                    records++;
                    if (idx < 0 || idx >= frames + RANGE_TOL) {
                        // The staleness definition: no frame within +-5.
                        stale++;
                        if (samples.size() < 6) samples.add("stale idx=" + idx + " frames=" + frames
                                + " turn=" + r.turnNum + " " + r.phaseStep + " " + r.promptType);
                        continue;
                    }
                    if (idx >= frames) {
                        family[1]++; // inside the +-5 slack past the last frame (end-of-game write)
                        continue;
                    }
                    GameState frame = game.getGameStates().get(idx);
                    if (frame == null) {
                        stale++;
                        if (samples.size() < 6) samples.add("null frame at idx=" + idx);
                        continue;
                    }
                    int fTurn = frame.getTurnNum();
                    String fStep = frameStep(frame);
                    if (fTurn != r.turnNum || !fStep.equals(r.phaseStep)) {
                        // Distinguish the characterized beginStep-origin timing family
                        // (binds to idx+1) from a genuine misbinding.
                        GameState next = (idx + 1 < frames) ? game.getGameStates().get(idx + 1) : null;
                        boolean preSave = next != null && next.getTurnNum() == r.turnNum
                                && frameStep(next).equals(r.phaseStep);
                        if (preSave) {
                            // Family membership is ASSERTED, not just counted: the
                            // record must be the characterized cleanup-step shape —
                            // frame[idx] = the same turn's end-turn frame, frame[idx+1]
                            // = that turn's cleanup frame. Anything else (a new
                            // beginStep-origin prompt path, a direction flip, a
                            // cross-turn bind) fails loudly instead of joining the
                            // family silently. Counts are disjoint: a record is either
                            // characterized family or pre_save_unexpected.
                            boolean expectedShape = END_TURN_FRAME.equals(fStep)
                                    && CLEANUP_FRAME.equals(frameStep(next))
                                    && frameStep(next).equals(r.phaseStep)
                                    && fTurn == r.turnNum
                                    && ("target".equals(r.promptType)
                                        || "target-corrected".equals(r.promptType));
                            if (expectedShape) {
                                familyRecords++;
                            } else {
                                familyUnexpected++;
                                if (samples.size() < 6) samples.add("pre_save_unexpected idx=" + idx
                                        + " record=" + r.turnNum + " " + r.phaseStep
                                        + " frame=" + fTurn + " " + fStep
                                        + " nextFrame=" + next.getTurnNum() + " " + frameStep(next)
                                        + " " + r.promptType);
                            }
                        } else {
                            misbound++;
                            tally[4]++;
                            anyFail[0] = true;
                            if (samples.size() < 6) samples.add("misbound idx=" + idx
                                    + " record=" + r.turnNum + " " + r.phaseStep
                                    + " frame=" + fTurn + " " + fStep);
                        }
                    }
                }
            }
        }
        family[0] += familyRecords;
        family[2] += familyUnexpected;
        tally[2] += records;
        tally[3] += stale;
        boolean ok = stale == 0 && misbound == 0 && familyUnexpected == 0;
        if (!ok) anyFail[0] = true;
        System.out.printf("%s %s frames=%d records=%d stale(+-%d)=%d misbound=%d"
                        + " misbound_pre_save=%d pre_save_unexpected=%d%n",
                ok ? "PASS" : "FAIL", label, frames, records, RANGE_TOL, stale, misbound,
                familyRecords, familyUnexpected);
        for (String s : samples) System.out.println("      " + s);
    }

    /** Frame's phase/step text — built the same way LLMPlayer builds record phaseStep. */
    private static String frameStep(GameState state) {
        mage.game.turn.Turn turn = state.getTurn();
        mage.game.turn.Phase phase = turn != null ? turn.getPhase() : null;
        String phaseStep = phase != null ? phase.toString() : "?";
        mage.game.turn.Step step = turn != null ? turn.getStep() : null;
        if (step != null) phaseStep += " / " + step.toString();
        return phaseStep;
    }

    /** Streaming pre-scan for the serialized class name, so non-LLM saves are not fully loaded. */
    private static boolean hasLlmRecords(Path file) throws Exception {
        byte[] needle = "LLMExchangeRecord".getBytes("US-ASCII");
        try (InputStream in = Files.newInputStream(file);
             InputStream buffer = new BufferedInputStream(in);
             InputStream codec = mage.server.game.ReplayCodec.wrapForRead(buffer)) {
            byte[] buf = new byte[1 << 16];
            int carry = 0;
            int n;
            while ((n = codec.read(buf, carry, buf.length - carry)) != -1) {
                int total = carry + n;
                outer:
                for (int i = 0; i + needle.length <= total; i++) {
                    for (int j = 0; j < needle.length; j++) {
                        if (buf[i + j] != needle[j]) continue outer;
                    }
                    return true;
                }
                carry = Math.min(needle.length - 1, total);
                System.arraycopy(buf, total - carry, buf, 0, carry);
            }
        }
        return false;
    }

    private static int staleCount(Game game) {
        int stale = 0;
        int frames = game.getGameStates().getSize();
        for (Player p : game.getPlayers().values()) {
            if (!(p instanceof LLMPlayer)) continue;
            for (Integer idx : ((LLMPlayer) p).getReplayLLMData().keySet()) {
                if (idx < 0 || idx >= frames + RANGE_TOL) stale++;
            }
        }
        return stale;
    }

    private static int countRecords(Map<Integer, List<LLMExchangeRecord>> m) {
        int n = 0;
        for (List<LLMExchangeRecord> l : m.values()) n += l.size();
        return n;
    }

    /** (idx -> list of "turnNum|phaseStep|promptType|answer") — content signatures. */
    private static Map<Integer, List<String>> summarize(Map<Integer, List<LLMExchangeRecord>> m) {
        Map<Integer, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<LLMExchangeRecord>> e : m.entrySet()) {
            List<String> l = new ArrayList<>();
            for (LLMExchangeRecord r : e.getValue()) {
                l.add(r.turnNum + "|" + r.phaseStep + "|" + r.promptType + "|" + r.answer);
            }
            out.put(e.getKey(), l);
        }
        return out;
    }

    // ==================================================================
    //  [3] selftest — prove the walker detects stale + misbound records
    // ==================================================================

    private static void selftest(Path work) throws Exception {
        // Re-manufacture a clean 2-game artifact set in a scratch subdir.
        Path sub = work.resolve("selftest-corpus");
        Files.createDirectories(sub.resolve("saved"));
        manufacture(sub, 2, defaultTurns(2));
        Path clean = firstArtifact(sub.resolve("saved"));

        Path dirtyDir = work.resolve("selftest-injected");
        Files.createDirectories(dirtyDir);
        Game game = loadGame(clean);
        int frames = game.getGameStates().getSize();
        LLMPlayer lp = firstLlmPlayer(game);

        // (a) The carried-over signature: a record carried over from an earlier game,
        //     index beyond this game's frame range + tolerance.
        int staleIdx = frames + 40;
        List<LLMExchangeRecord> staleBucket = new ArrayList<>();
        staleBucket.add(new LLMExchangeRecord(staleIdx, "priority", "injected stale record",
                "inject", "0", lp.getName(), "inject", 99, "injected / step"));
        lp.getReplayLLMData().put(staleIdx, staleBucket);

        // (b) In-range but on the wrong frame: index 5 re-pointed at a record
        //     whose (turn, phase) belongs to a different frame. The doc calls
        //     this out ("stale +-5 undercounts this") — the walker's binding
        //     check must catch it.
        List<LLMExchangeRecord> misboundBucket = new ArrayList<>();
        misboundBucket.add(new LLMExchangeRecord(5, "priority", "injected misbound record",
                "inject", "0", lp.getName(), "inject",
                game.getGameStates().get(5).getTurnNum() + 7,
                "injected / wrong-phase-" + frames));
        lp.getReplayLLMData().put(5, misboundBucket);

        // (c) Pre-save-shaped bind with the WRONG shape: a record whose
        //     (turn, step) matches frame[idx+1] but is NOT the characterized
        //     cleanup-step family (frame[idx] != end-turn / frame[idx+1] !=
        //     cleanup). The family assertion must fire (pre_save_unexpected) —
        //     a new beginStep-origin prompt path cannot silently join the
        //     family. (d) The direction FLIP: a record matching only
        //     frame[idx-1] must still be a hard misbound, never family.
        int famIdx = -1, lateIdx = -1;
        for (int i = 2; i < frames - 2; i++) {
            GameState s0 = game.getGameStates().get(i);
            GameState s1 = game.getGameStates().get(i + 1);
            GameState s2 = game.getGameStates().get(i + 2);
            if (s0 == null || s1 == null || s2 == null) continue;
            String t0 = frameStep(s0), t1 = frameStep(s1), t2 = frameStep(s2);
            if (t1.equals(t0)) continue;                              // (c) needs a distinct next frame
            if (END_TURN_FRAME.equals(t0) && CLEANUP_FRAME.equals(t1)) continue; // that IS the family
            if (s2.getTurnNum() == s0.getTurnNum() && t2.equals(t0)) continue;   // (d) must not be pre-save
            if (lp.getReplayLLMData().containsKey(i) || lp.getReplayLLMData().containsKey(i + 1)) continue;
            famIdx = i;
            lateIdx = i + 1;
            break;
        }
        if (famIdx < 0) {
            failures++;
            System.out.println("SELFTEST FAIL: no free frame pair found for the pre-save injections");
            return;
        }
        GameState nextFrame = game.getGameStates().get(famIdx + 1);
        List<LLMExchangeRecord> notFamilyBucket = new ArrayList<>();
        notFamilyBucket.add(new LLMExchangeRecord(famIdx, "priority", "injected pre-save wrong shape",
                "inject", "0", lp.getName(), "inject",
                nextFrame.getTurnNum(), frameStep(nextFrame)));
        lp.getReplayLLMData().put(famIdx, notFamilyBucket);

        GameState prevFrame = game.getGameStates().get(lateIdx - 1);
        List<LLMExchangeRecord> lateBucket = new ArrayList<>();
        lateBucket.add(new LLMExchangeRecord(lateIdx, "priority", "injected late-bound record",
                "inject", "0", lp.getName(), "inject",
                prevFrame.getTurnNum(), frameStep(prevFrame)));
        lp.getReplayLLMData().put(lateIdx, lateBucket);

        Path dirty = dirtyDir.resolve("injected-stale.game");
        try (OutputStream fileStream = new FileOutputStream(dirty.toFile());
             OutputStream buffer = new BufferedOutputStream(fileStream);
             ObjectOutput output = new ObjectOutputStream(mage.server.game.ReplayCodec.wrapForWrite(buffer))) {
            output.writeObject(game);
            output.writeObject(game.getGameStates());
            output.flush();
        }

        System.out.println("selftest: injected into a " + frames + "-frame artifact:"
                + " stale(idx=" + staleIdx + "), misbound(idx=5),"
                + " wrong-shape pre-save(idx=" + famIdx + " -> next frame '"
                + frameStep(nextFrame) + "'), late-bound(idx=" + lateIdx + " -> prev frame '"
                + frameStep(prevFrame) + "')");
        int[] tally = new int[6];
        int[] family = new int[3];
        boolean[] anyFail = {false};
        walkFile(dirty, tally, family, anyFail);
        boolean detected = tally[3] >= 1 && tally[4] >= 2 && family[2] >= 1;
        if (detected) {
            System.out.println("SELFTEST PASS: walker detected all injected defects"
                    + " (stale=" + tally[3] + ", misbound=" + tally[4]
                    + " [includes the late-bound flip], pre_save_unexpected=" + family[2] + ")");
        } else {
            failures++;
            System.out.println("SELFTEST FAIL: walker missed an injected defect"
                    + " (stale=" + tally[3] + ", misbound=" + tally[4]
                    + ", pre_save_unexpected=" + family[2] + ")");
        }
    }

    private static Path firstArtifact(Path dir) throws Exception {
        List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.toString().endsWith(".game")).sorted().forEach(files::add);
        }
        if (files.isEmpty()) throw new IllegalStateException("no artifact in " + dir);
        return files.get(0);
    }

    // ==================================================================

    private static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name
                + (!ok && detail != null && !detail.isEmpty() ? "  — " + detail : ""));
        if (!ok) failures++;
    }

    private static List<String> head(List<String> l) {
        return l.size() <= 5 ? l : l.subList(0, 5);
    }

    private static String shortId(UUID u) {
        return u.toString().substring(0, 8);
    }
}
