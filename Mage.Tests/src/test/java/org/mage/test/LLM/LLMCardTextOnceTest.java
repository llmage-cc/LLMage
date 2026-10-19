package org.mage.test.LLM;

import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.player.llm.LLMConfig;
import mage.player.llm.LLMGameSerializer;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mage.test.player.TestLLMPlayer;
import org.mage.test.player.TestPlayer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Verifies the encounter-based "card text full ONCE then shorthand" render
 * replacing the broken decklist-name
 * skip heuristic (LLMHandDedupTest): every decision-facing card render goes
 * through one shared check-and-emit keyed on (game, seat).
 *
 * <ul>
 *   <li>First sighting of a card NAME for that (game, seat) emits the card's
 *   FULL rules text with the explicit marker "— FULL TEXT: ".</li>
 *   <li>Every later sighting renders shorthand (name + cost + types + P/T)
 *   with no rules text and no "see earlier" note.</li>
 *   <li>Own-decklist cards still get full text on first in-game sighting —
 *   the motivating failure: a live seat had Spell Queller's full text in its
 *   system-prompt decklist on every call and still concluded "no flash".
 *   On the OLD decklist-skip code this test FAILS (the name was
 *   pre-registered by useDeck and the text was dropped).</li>
 *   <li>Seat isolation: seat A's sighting does not suppress seat B's full
 *   text for the same name. On the OLD seat-blind registry (keyed by gameId
 *   only) this test FAILS (the second seat's useDeck registration OVERWROTE
 *   the first's in LLM-vs-LLM games — same collision class as the
 *   postgame-pin bug fixed on master).</li>
 * </ul>
 *
 * No live model calls — buildPrompt is driven directly via reflection (the
 * proven LLMHandDedupTest pattern) and the assertions are on the rendered
 * prompt text.
 *
 * <p>Setup rules learned the hard way (first RED run):
 * <ul>
 *   <li>TestLLMPlayer pins chooseMulligan to "keep, no LLM call" — the
 *   mulligan path never marks anything.</li>
 *   <li>A card that is CASTABLE in the stop window (e.g. a flash creature at
 *   upkeep) generates a REAL priority prompt during execute(), which marks
 *   its name — the first reflection render is then a second sighting. Cards
 *   under test are kept UNCASTABLE (red mana only vs {1}{W}{U}) so the
 *   action list is empty and priority passes silently with no prompt.</li>
 *   <li>The stack is asserted inside runCode in the priority window right
 *   after the queued cast action — after execute() returns the spell has
 *   already resolved.</li>
 * </ul>
 */
public class LLMCardTextOnceTest extends CardTestPlayerBaseLLM {

    /** The exact marker the funnel emits (LLMGameSerializer.FULL_TEXT_MARKER). */
    private static final String FULL_TEXT_MARKER = "— FULL TEXT: ";

    /** Marker from the shorthand abilities summary (audit #23 fix). */
    private static final String TRUNCATED_ABILITIES = "more abilities, truncated";

    /** Two-seat mode for the seat-isolation test (see createPlayer). */
    private boolean twoSeatMode = false;

    @BeforeClass
    public static void checkLLMAvailable() {
        // Stub-only: no live model call happens (LLMPlayer.init() probes are
        // gated on isTestHarness), but honor the suite-wide skip switch.
        org.junit.Assume.assumeFalse("LLM tests skipped (set -Dxmage.llm.skip=false to run)",
                Boolean.getBoolean("xmage.llm.skip"));
    }

    @Override
    public List<String> getFullSimulatedPlayers() {
        return twoSeatMode
                ? java.util.Arrays.asList("PlayerA", "PlayerB")
                : java.util.Arrays.asList("PlayerA");
    }

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if (getFullSimulatedPlayers().contains(name)) {
            TestLLMPlayer llmPlayer = new TestLLMPlayer(name, RangeOfInfluence.ONE, 0);
            llmPlayer.getLLMConfig()
                    .apiEndpoint("http://localhost:11434/v1/chat/completions")
                    .model("deepseek-v4-flash:cloud")  // fixture policy: flash, never pro
                    .maxTokens(65536)
                    .timeoutSeconds(120)
                    .deckAssessmentEnabled(false)
                    .verboseLogging(false);
            llmPlayer.reconnect();
            // Inject AFTER reconnect — reconnect() replaces the client wholesale.
            llmPlayer.injectTestClient(new PassClient(llmPlayer.getLLMConfig()));
            TestPlayer testPlayer = new TestPlayer(llmPlayer);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    /** Answers "0" (pass priority) to everything — no network. */
    static class PassClient extends mage.player.llm.LLMClient {
        PassClient(LLMConfig config) { super(config); }

        @Override
        public mage.player.llm.LLMResponse chatConversation(String fullPrompt, String compactSummary,
                                                            String promptType, Integer turn, String phaseStep,
                                                            String effortOverride) {
            return new mage.player.llm.LLMResponse("stub", "0");
        }

        @Override
        public mage.player.llm.LLMResponse chatConversationWithStop(String fullPrompt, String compactSummary,
                                                                     String promptType, Integer turn, String phaseStep,
                                                                     String effortOverride, String stopSequence) {
            return new mage.player.llm.LLMResponse("stub", "0");
        }
    }

    // ── reflection helpers (proven pattern from LLMHandDedupTest) ──────────

    private static String buildPriorityPrompt(TestPlayer viewingPlayer, List<String> actions) throws Exception {
        Method m = LLMGameSerializer.class.getDeclaredMethod(
                "buildPrompt",
                mage.game.Game.class, java.util.UUID.class, LLMConfig.class,
                List.class, int.class, List.class, long.class, Map.class);
        m.setAccessible(true);
        return (String) m.invoke(null,
                currentGame, viewingPlayer.getId(),
                new LLMConfig(),
                new ArrayList<String>(),   // selfLog
                0,                          // lastShownLogSize
                actions,
                0L,                         // boardCursor (0 = force full render)
                new HashMap<String, UUID>()); // landIdMap
    }

    /** Unchecked variant for use inside runCode lambdas. */
    private static String buildPriorityPromptUnchecked(TestPlayer viewingPlayer, List<String> actions) {
        try {
            return buildPriorityPrompt(viewingPlayer, actions);
        } catch (Exception e) {
            throw new RuntimeException("buildPrompt reflection call failed", e);
        }
    }

    /** Extract a named section from the prompt (from header to the next "---" header). */
    private static String section(String prompt, String header) {
        int start = prompt.indexOf(header);
        Assert.assertTrue("prompt should contain section " + header + ", got:\n" + prompt, start >= 0);
        int end = prompt.indexOf("---", start + header.length());
        return end > start ? prompt.substring(start, end) : prompt.substring(start);
    }

    // ── 1. first sighting full text, second render shorthand ──────────────

    @Test
    public void handCardFirstSightingFullTextThenShorthand() throws Exception {
        addCard(Zone.HAND, playerA, "Spell Queller", 1);
        // UNCASTABLE mana (red only vs {1}{W}{U}): a flash creature castable
        // in the upkeep window would be rendered by a REAL priority prompt
        // during execute() and marked seen before this test's first render.
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 4);

        setStrictChooseMode(false);
        setStopAt(1, PhaseStep.UPKEEP);
        execute();

        String first = section(buildPriorityPrompt(playerA, java.util.Arrays.asList("Cast: Spell Queller")), "--- Your Hand ---");
        Assert.assertTrue("first sighting of a hand card must emit the FULL TEXT marker, got:\n" + first,
                first.contains(FULL_TEXT_MARKER));
        Assert.assertTrue("first sighting must carry the full rules text (flash), got:\n" + first,
                first.contains("Flash"));
        Assert.assertTrue(first.contains("Spell Queller"));

        // Second render: shorthand only — name, cost, types, P/T — no marker,
        // no rules text.
        String second = section(buildPriorityPrompt(playerA, java.util.Arrays.asList("Cast: Spell Queller")), "--- Your Hand ---");
        Assert.assertFalse("second sighting must NOT repeat the FULL TEXT marker, got:\n" + second,
                second.contains(FULL_TEXT_MARKER));
        Assert.assertFalse("second sighting must not repeat the rules text, got:\n" + second,
                second.contains("Flash"));
        // Shorthand still identifies the card and carries P/T.
        Assert.assertTrue("shorthand must keep the card name, got:\n" + second, second.contains("Spell Queller"));
        Assert.assertTrue("shorthand must carry power/toughness, got:\n" + second, second.contains("2/3"));
    }

    // ── 2. own-decklist card STILL gets full text on first sighting ────────

    /**
     * The KEY behavior change vs LLMHandDedupTest: the fixture deck (RB
     * Aggro) IS all Mountains, so a Mountain in hand is an own-decklist card.
     * useDeck() no longer pre-registers decklist names — the seen-set starts
     * empty and the first in-game sighting emits full text.
     *
     * <p>On the OLD decklist-skip code this test FAILS: useDeck registered
     * "Mountain" and formatCardBrief dropped the rules text for it (the
     * old LLMHandDedupTest.decklistCardsLoseRulesTextInHandRender asserted
     * exactly that dropping).
     */
    @Test
    public void ownDecklistCardStillGetsFullTextOnFirstSighting() throws Exception {
        addCard(Zone.HAND, playerA, "Mountain", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 3);

        setStrictChooseMode(false);
        setStopAt(1, PhaseStep.UPKEEP);
        execute();

        String first = section(buildPriorityPrompt(playerA, java.util.Arrays.asList("Play land: Mountain")), "--- Your Hand ---");
        Assert.assertTrue("own-decklist card must STILL emit full text on first in-game sighting"
                        + " (the decklist primer alone is not enough for in-context recall), got:\n" + first,
                first.contains(FULL_TEXT_MARKER));
        Assert.assertTrue("Mountain's tap-for-{R} rule must be in the full text, got:\n" + first,
                first.contains("Add {R}") || first.contains("add {R}"));

        // And the shorthand afterwards drops it again.
        String second = section(buildPriorityPrompt(playerA, java.util.Arrays.asList("Play land: Mountain")), "--- Your Hand ---");
        Assert.assertFalse("second sighting of the decklist card must be shorthand, got:\n" + second,
                second.contains(FULL_TEXT_MARKER));
    }

    // ── 3. two-seat isolation ──────────────────────────────────────────────

    /**
     * Seat A's sighting must not suppress seat B's full text for the same
     * name. On the OLD seat-blind registry (gameId-only key) this FAILS:
     * the second useDeck() OVERWROTE the first seat's registration in
     * LLM-vs-LLM games, and any dedup decision was shared across seats.
     */
    @Test
    public void seatSightingDoesNotLeakAcrossSeats() throws Exception {
        twoSeatMode = true;
        try {
            addCard(Zone.HAND, playerA, "Spell Queller", 1);
            addCard(Zone.HAND, playerB, "Spell Queller", 1);
            // Islands/Plains split keeps the {1}{W}{U} Queller uncastable for
            // BOTH seats (see class javadoc: castable-in-window cards get
            // marked by real priority prompts during execute()).
            addCard(Zone.BATTLEFIELD, playerA, "Island", 3);
            addCard(Zone.BATTLEFIELD, playerB, "Plains", 3);

            setStrictChooseMode(false);
            setStopAt(1, PhaseStep.UPKEEP);
            execute();

            // Seat A renders first: full text.
            String aFirst = section(buildPriorityPrompt(playerA, new ArrayList<>()), "--- Your Hand ---");
            Assert.assertTrue("seat A's first sighting must emit FULL TEXT, got:\n" + aFirst,
                    aFirst.contains(FULL_TEXT_MARKER));

            // Seat B renders the SAME card name: still full text for seat B —
            // A's sighting is not B's sighting.
            String bFirst = section(buildPriorityPrompt(playerB, new ArrayList<>()), "--- Your Hand ---");
            Assert.assertTrue("seat B's first sighting of the same card must STILL emit FULL TEXT"
                            + " (seat isolation broken — registry is seat-blind?), got:\n" + bFirst,
                    bFirst.contains(FULL_TEXT_MARKER));

            // And a second A render is now shorthand (A's own set persists).
            String aSecond = section(buildPriorityPrompt(playerA, new ArrayList<>()), "--- Your Hand ---");
            Assert.assertFalse("seat A's second sighting must be shorthand, got:\n" + aSecond,
                    aSecond.contains(FULL_TEXT_MARKER));
        } finally {
            twoSeatMode = false;
        }
    }

    // ── 4. battlefield permanent: full text first, marked-truncated summary ─

    /**
     * First render of a battlefield permanent carries the FULL TEXT marker;
     * the second render shows the abilities summary with VISIBLE truncation
     * markers when the caps fire. Zacama, Primal Calamity has 7 abilities
     * (Vigilance, Reach, Trample, ETB untap, three activated) — past the
     * 4-ability cap, so the dropped-abilities marker must appear and name
     * the count.
     */
    @Test
    public void permanentFullTextFirstThenMarkedTruncatedSummary() throws Exception {
        addCard(Zone.BATTLEFIELD, playerA, "Zacama, Primal Calamity", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 3);

        setStrictChooseMode(false);
        setStopAt(1, PhaseStep.UPKEEP);
        execute();

        String first = section(buildPriorityPrompt(playerA, new ArrayList<>()), "--- Your Battlefield ---");
        Assert.assertTrue("first sighting of a battlefield permanent must emit FULL TEXT, got:\n" + first,
                first.contains(FULL_TEXT_MARKER));
        Assert.assertTrue("Zacama's full text must include its vigilance keyword, got:\n" + first,
                first.contains("Vigilance"));
        // First-sighting line = stats then FULL TEXT; the abilities summary
        // must NOT also appear on the same (first) line for the SAME
        // permanent — a first sighting renders either full text or summary,
        // never both.
        String zacamaLine = java.util.Arrays.stream(first.split("\n"))
                .filter(l -> l.contains("Zacama")).findFirst().orElse("");
        Assert.assertTrue("Zacama's first-sighting line must carry the marker, got:\n" + zacamaLine,
                zacamaLine.contains(FULL_TEXT_MARKER));
        Assert.assertFalse("Zacama's first-sighting line must not ALSO carry the shorthand summary marker, got:\n"
                + zacamaLine, zacamaLine.contains(TRUNCATED_ABILITIES));

        // Second render: shorthand + abilities summary, with the audit-#23
        // markers when the caps fire.
        String second = section(buildPriorityPrompt(playerA, new ArrayList<>()), "--- Your Battlefield ---");
        Assert.assertFalse("second sighting of the permanent must be shorthand, got:\n" + second,
                second.contains(FULL_TEXT_MARKER));
        Assert.assertTrue("the 7-ability permanent must show the dropped-abilities marker, got:\n" + second,
                second.contains(TRUNCATED_ABILITIES));
        Assert.assertTrue("the dropped-abilities marker must say HOW MANY were dropped, got:\n" + second,
                second.matches("(?s).*\\+\\d+ more abilities, truncated.*"));
    }

    // ── 5. stack object: full text first, shorthand repeat ─────────────────

    /**
     * The stack render goes through the funnel: a spell's first sighting
     * carries its full text (marked), repeat appearances shorthand. The
     * renders happen inside runCode in the priority window right after the
     * queued cast — after execute() the spell has already resolved.
     */
    @Test
    public void stackObjectFullTextFirstThenShorthand() throws Exception {
        addCard(Zone.HAND, playerA, "Day of Judgment", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 5);

        setStrictChooseMode(false);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Day of Judgment");
        runCode("render while on stack", 1, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> {
            Assert.assertTrue("test setup: Day of Judgment should be on the stack",
                    !currentGame.getStack().isEmpty());

            String first = buildPriorityPromptUnchecked(playerA, new ArrayList<>());
            String stackFirst = section(first, "--- Stack ---");
            Assert.assertTrue("first sighting of a spell on the stack must emit FULL TEXT, got:\n" + stackFirst,
                    stackFirst.contains(FULL_TEXT_MARKER));
            Assert.assertTrue("the spell's full text must be present (Day of Judgment), got:\n" + stackFirst,
                    stackFirst.contains("Destroy all creatures"));

            String second = buildPriorityPromptUnchecked(playerA, new ArrayList<>());
            String stackSecond = section(second, "--- Stack ---");
            Assert.assertFalse("second sighting of the stack spell must not repeat the FULL TEXT marker, got:\n"
                    + stackSecond, stackSecond.contains(FULL_TEXT_MARKER));
        });
        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();
    }

    // ── 6. game-end cleanup: fresh render emits full text again ────────────

    @Test
    public void unregisterMakesNextRenderFullTextAgain() throws Exception {
        addCard(Zone.HAND, playerA, "Spell Queller", 1);
        // Red-only mana keeps the {1}{W}{U} Queller uncastable — see class javadoc.
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 4);

        setStrictChooseMode(false);
        setStopAt(1, PhaseStep.UPKEEP);
        execute();

        String first = section(buildPriorityPrompt(playerA, new ArrayList<>()), "--- Your Hand ---");
        Assert.assertTrue("first render must emit FULL TEXT, got:\n" + first, first.contains(FULL_TEXT_MARKER));

        String shorthand = section(buildPriorityPrompt(playerA, new ArrayList<>()), "--- Your Hand ---");
        Assert.assertFalse("second render must be shorthand, got:\n" + shorthand,
                shorthand.contains(FULL_TEXT_MARKER));

        // Game end drops the whole game entry (releaseGameScopedRegistries
        // calls this with the game id): a fresh render sees an empty set.
        LLMGameSerializer.unregisterSeenCardNames(currentGame.getId());
        String fresh = section(buildPriorityPrompt(playerA, new ArrayList<>()), "--- Your Hand ---");
        Assert.assertTrue("after game-end cleanup the card must be re-shown in full, got:\n" + fresh,
                fresh.contains(FULL_TEXT_MARKER));
    }

    // ── 7. system-prompt instruction + decklist block ──────────────────────

    @Test
    public void systemPromptContainsCardTextInstructionAndDecklist() {
        String sys = new LLMConfig().getSystemPromptTemplate();
        // The mechanic contract — FORMAT contract, not coaching.
        Assert.assertTrue("system prompt must document the full-text-once mechanic, got:\n" + sys,
                sys.contains("CARD TEXT FORMAT"));
        Assert.assertTrue("system prompt must name the marker, got:\n" + sys,
                sys.contains("— FULL TEXT:"));
        Assert.assertTrue("system prompt must say the text appears once per game, got:\n" + sys,
                sys.contains("ONCE per game"));
        Assert.assertTrue("system prompt must tell the model to read it when it first appears, got:\n" + sys,
                sys.contains("when it first appears"));
        // The instruction must sit with the prompt-format docs, before HOW TO RESPOND.
        Assert.assertTrue("instruction must precede HOW TO RESPOND, got:\n" + sys,
                sys.indexOf("CARD TEXT FORMAT") < sys.indexOf("HOW TO RESPOND"));

        // The decklist primer block must still be appended by useDeck — the
        // funnel complements it, it does not replace it. After execute(), the
        // stubbed client carries the seeded system prompt.
        String seeded = llmClientOf(playerA).getConversationSystemPrompt();
        Assert.assertNotNull("conversation was never reset by useDeck", seeded);
        Assert.assertTrue("the decklist block must still be seeded into the system prompt, got:\n" + seeded,
                seeded.contains("=== YOUR DECKLIST ==="));
        // Mountain is the fixture deck's only card — the decklist must name it.
        Assert.assertTrue("the seeded decklist must contain the deck's card (Mountain), got:\n" + seeded,
                seeded.contains("Mountain"));
        // And the instruction block is part of the seeded prompt too (it is
        // in the base template useDeck appends to).
        Assert.assertTrue("the seeded system prompt must carry the card-text instruction, got:\n" + seeded,
                seeded.contains("CARD TEXT FORMAT"));
    }

    private mage.player.llm.LLMClient llmClientOf(TestPlayer p) {
        return ((TestLLMPlayer) p.getComputerPlayer()).clientForTest();
    }
}