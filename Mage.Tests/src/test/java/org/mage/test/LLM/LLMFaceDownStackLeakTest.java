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
import java.util.Set;
import java.util.UUID;

/**
 * A spell cast FACE DOWN (morph / megamorph / disguise) is hidden information:
 * the opponent sees "a face-down creature spell", never the card. The LLM
 * serializer must not leak the real name or the real rules text of a face-down
 * spell on the stack (that would make the LLM seat systematically
 * better-informed than a human, and would contaminate the postgame reflection —
 * "I knew it was a Sagu Mauler").
 *
 * <p>Covered surfaces (both render the stack for the seat):
 * <ul>
 *   <li>the {@code --- Stack ---} header line and rule line — the primary leak;</li>
 *   <li>the compact situation summary's {@code stack:[...]} (the historical record
 *   that survives conversation summarization, so a leak there persists all game).</li>
 * </ul>
 *
 * <p>Seen-set decision (see {@link LLMGameSerializer#appendStackObjectRule}):
 * a face-down sighting must NOT register the real name in the (game, seat)
 * seen-set. Otherwise a LATER face-UP appearance of the same card would render
 * shorthand with no full text — a differential that is itself an inference
 * channel back to the morph identity — and it would steal the one full-text slot
 * the face-up card is entitled to. {@code faceDownSpellOnStackHidesIdentity}
 * pins it directly on {@link LLMGameSerializer#seenCardNames}: pre-fix, rendering
 * the face-down spell registers its real name; post-fix the set stays clean.
 *
 * <p>No live model calls — buildPrompt is driven via reflection (the proven
 * LLMCardTextOnceTest pattern) and assertions are on the rendered prompt text.
 * Setup rules reused from there: the stack is asserted inside runCode in the
 * priority window right after the queued cast (after execute() the spell has
 * resolved), and the casting actor must be the ACTIVE player for that turn.
 * playerA (the LLM seat under test) starts turn 1, so the OPPONENT's face-down
 * spell is cast by playerB on turn 2 — which is also the realistic hidden-info
 * scenario (a seat never needs the name of its OWN face-down spell, only the
 * opponent's).
 */
public class LLMFaceDownStackLeakTest extends CardTestPlayerBaseLLM {

    /** The exact marker the funnel emits (LLMGameSerializer.FULL_TEXT_MARKER). */
    private static final String FULL_TEXT_MARKER = "— FULL TEXT: ";

    /** The face-down placeholder name (the render contract; see LLMGameSerializer.FACE_DOWN_SPELL_NAME). */
    private static final String FACE_DOWN_NAME = "face-down spell";

    /** The face-down placeholder rule line (see LLMGameSerializer.FACE_DOWN_SPELL_RULE). */
    private static final String FACE_DOWN_RULE = "(face-down spell — identity hidden)";

    /**
     * A rules-text fragment unique to Pine Walker's REAL text — the morph
     * reminder ("…Turn it face up any time for its morph cost."). None of it may
     * appear for a face-down spell.
     */
    private static final String REAL_RULES_FRAGMENT = "Turn it face up";

    @BeforeClass
    public static void checkLLMAvailable() {
        // Stub-only: no live model call happens, but honor the suite-wide switch.
        org.junit.Assume.assumeFalse("LLM tests skipped (set -Dxmage.llm.skip=false to run)",
                Boolean.getBoolean("xmage.llm.skip"));
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

    // ── reflection helpers (proven pattern from LLMCardTextOnceTest) ────────

    private static String buildPriorityPrompt(TestPlayer viewingPlayer) throws Exception {
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
                new ArrayList<String>(),    // actions
                0L,                         // boardCursor (0 = force full board render)
                new HashMap<String, UUID>()); // landIdMap
    }

    private static String buildPriorityPromptUnchecked(TestPlayer viewingPlayer) {
        try {
            return buildPriorityPrompt(viewingPlayer);
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

    // ── 1. the leak: face-down spell hides name + rules, registers no sighting ──

    /**
     * playerB (active on turn 2) casts Pine Walker face down ({3} morph, 3
     * Forests). The prompt rendered for the LLM seat (playerA) must show the
     * face-down placeholder, the controller (public info), and nothing of the
     * real card — and must NOT have registered the real name in the seat's
     * seen-set.
     *
     * <p>RED on pre-fix code: the stack line names Pine Walker and prints its
     * real morph reminder, so the placeholder/absence assertions fail AND the
     * name lands in the seen-set.
     */
    @Test
    public void faceDownSpellOnStackHidesIdentity() throws Exception {
        addCard(Zone.HAND, playerB, "Pine Walker", 1);
        addCard(Zone.BATTLEFIELD, playerB, "Forest", 3);
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 3);

        setStrictChooseMode(false);
        castSpell(2, PhaseStep.PRECOMBAT_MAIN, playerB, "Pine Walker using Morph");
        runCode("render while a face-down spell is on the stack", 2, PhaseStep.PRECOMBAT_MAIN, playerB,
                (info, player, game) -> {
            Assert.assertTrue("test setup: the face-down spell should be on the stack",
                    !currentGame.getStack().isEmpty());
            Assert.assertTrue("test setup: the stack spell should be face down",
                    ((mage.game.stack.Spell) currentGame.getStack().iterator().next()).isFaceDown(currentGame));

            String stack = section(buildPriorityPromptUnchecked(playerA), "--- Stack ---");

            // The header/rule render as the placeholder, with the controller kept.
            Assert.assertTrue("the stack header must show the face-down placeholder, got:\n" + stack,
                    stack.contains(FACE_DOWN_NAME));
            Assert.assertTrue("the controller name is public information and must be kept, got:\n" + stack,
                    stack.contains("(PlayerB)"));
            Assert.assertTrue("the rule line must be the hidden placeholder, got:\n" + stack,
                    stack.contains(FACE_DOWN_RULE));

            // The leak: neither the real name nor any real rules text.
            Assert.assertFalse("face-down spell's real NAME must not appear on the stack, got:\n" + stack,
                    stack.contains("Pine Walker"));
            Assert.assertFalse("face-down spell's real RULES TEXT must not appear, got:\n" + stack,
                    stack.contains(REAL_RULES_FRAGMENT));
            Assert.assertFalse("face-down spell must not get the FULL TEXT marker, got:\n" + stack,
                    stack.contains(FULL_TEXT_MARKER));

            // Seen-set decision: a face-down sighting must not consume the
            // name's full-text slot (see the class javadoc).
            Set<String> seen = LLMGameSerializer.seenCardNames(currentGame.getId(), playerA.getId());
            Assert.assertFalse("a face-down sighting must NOT register the real name in the seat's"
                            + " seen-set (it would make a later face-up render shorthand — an identity"
                            + " inference channel), seen=" + seen,
                    seen.contains("Pine Walker"));
        });
        setStopAt(2, PhaseStep.PRECOMBAT_MAIN);
        execute();
    }

    // ── 2. the compact situation summary must not leak either ───────────────

    /**
     * {@link LLMGameSerializer#buildSituationSummary} is the historical record
     * that survives conversation summarization; its {@code stack:[...]} entry
     * used raw stack names. It must go through the same hidden-information rule.
     */
    @Test
    public void situationSummaryStackHidesFaceDownName() throws Exception {
        addCard(Zone.HAND, playerB, "Pine Walker", 1);
        addCard(Zone.BATTLEFIELD, playerB, "Forest", 3);
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 3);

        setStrictChooseMode(false);
        castSpell(2, PhaseStep.PRECOMBAT_MAIN, playerB, "Pine Walker using Morph");
        runCode("render summary while a face-down spell is on the stack", 2, PhaseStep.PRECOMBAT_MAIN, playerB,
                (info, player, game) -> {
            Assert.assertTrue("test setup: the face-down spell should be on the stack",
                    !currentGame.getStack().isEmpty());

            String summary = LLMGameSerializer.buildSituationSummary(currentGame, playerA.getId());
            int stackStart = summary.indexOf("stack:[");
            Assert.assertTrue("summary should carry a stack entry, got:\n" + summary, stackStart >= 0);
            String stackEntry = summary.substring(stackStart, summary.indexOf("]", stackStart));
            Assert.assertTrue("the summary stack entry must use the face-down placeholder, got:\n" + stackEntry,
                    stackEntry.contains(FACE_DOWN_NAME));
            Assert.assertFalse("the summary stack entry must not name a face-down spell, got:\n" + stackEntry,
                    stackEntry.contains("Pine Walker"));
        });
        setStopAt(2, PhaseStep.PRECOMBAT_MAIN);
        execute();
    }

    // ── 3. regression guard: the face-UP path is unchanged ──────────────────

    /**
     * A face-UP spell on the stack still renders its real name and its full
     * rules text with the FULL TEXT marker — the hidden-information branch must
     * key on {@code Spell.isFaceDown(game)} only, never on "is a creature spell"
     * or similar.
     */
    @Test
    public void faceUpSpellOnStackStillRendersNameAndRules() throws Exception {
        addCard(Zone.HAND, playerB, "Day of Judgment", 1);
        addCard(Zone.BATTLEFIELD, playerB, "Plains", 5);
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 5);

        setStrictChooseMode(false);
        castSpell(2, PhaseStep.PRECOMBAT_MAIN, playerB, "Day of Judgment");
        runCode("render while a face-up spell is on the stack", 2, PhaseStep.PRECOMBAT_MAIN, playerB,
                (info, player, game) -> {
            Assert.assertTrue("test setup: Day of Judgment should be on the stack",
                    !currentGame.getStack().isEmpty());

            String stack = section(buildPriorityPromptUnchecked(playerA), "--- Stack ---");
            Assert.assertTrue("a face-up spell's real name must still render, got:\n" + stack,
                    stack.contains("Day of Judgment"));
            Assert.assertTrue("a face-up spell must still emit its FULL TEXT, got:\n" + stack,
                    stack.contains(FULL_TEXT_MARKER));
            Assert.assertTrue("a face-up spell's rules text must still render, got:\n" + stack,
                    stack.contains("Destroy all creatures"));
            Assert.assertFalse("a face-up spell must not be rendered as face-down, got:\n" + stack,
                    stack.contains(FACE_DOWN_NAME));
        });
        setStopAt(2, PhaseStep.PRECOMBAT_MAIN);
        execute();
    }
}
