package org.mage.test.LLM;

import mage.player.llm.LLMPlayer;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Label/parsing hygiene pins.
 *
 * <p>These are pure-function pins: two behaviours are testable without a live
 * {@link mage.game.Game} and are asserted here:
 *
 * <ul>
 *   <li><b>Reasoning fence</b> — {@link LLMPlayer#stripReasoningFence(String)} must remove
 *       the conversation-mimicry {@code [model_reasoning]} fence (complete
 *       block, unclosed opener, and a stray close token) so the fence tokens
 *       can never be split out as "unrecognized card names" by the
 *       large-search card-name parser.</li>
 *   <li><b>Permanent label</b> — the permanent-label fallback must never emit a raw
 *       36-char UUID; a gone permanent is shortened to {@code (gone: …)}.</li>
 * </ul>
 *
 * <p>The surveil-decline and pre-first-turn / mulligan-bottom labels are
 * produced inside {@code presentTargetChoice}/{@code gameTurnStep},
 * which take a live {@link mage.game.Game} — asserted by an integration
 * test, not constructible as a unit here.
 */
public class LLMLabelHygieneTest {

    // Literal copies of the LLMClient fence tokens (package-private there;
    // this test lives in a different package and must not widen the API just
    // to read them).
    private static final String FENCE_OPEN = "[model_reasoning]";
    private static final String FENCE_CLOSE = "[/model_reasoning]";

    // ── Reasoning-fence stripping ────────────────────────────────────

    /** A complete fence block in an otherwise clean answer is fully removed. */
    @Test
    public void testCompleteFenceBlockStripped() {
        String answer = "Flow State, Brainstorm\n[model_reasoning]\n"
                + "I need to pick the slowest card here.\n[/model_reasoning]";
        String out = LLMPlayer.stripReasoningFence(answer);
        Assert.assertFalse("open token must be gone", out.contains(FENCE_OPEN));
        Assert.assertFalse("close token must be gone", out.contains(FENCE_CLOSE));
        Assert.assertTrue("real card names survive", out.contains("Flow State"));
        Assert.assertTrue("real card names survive", out.contains("Brainstorm"));
    }

    /** An unclosed opener means the rest of the answer is reasoning — cut it. */
    @Test
    public void testUnclosedOpenerCutsTrailingReasoning() {
        String answer = "Ponder\n[model_reasoning]\nPonder is the best card to name.";
        String out = LLMPlayer.stripReasoningFence(answer);
        Assert.assertFalse(out.contains(FENCE_OPEN));
        Assert.assertTrue("answer before the fence survives", out.contains("Ponder"));
        Assert.assertFalse("reasoning text after an unclosed fence is cut",
                out.contains("best card to name"));
    }

    /** A bare close token with no opener is still not a card name. */
    @Test
    public void testStrayCloseTokenStripped() {
        String out = LLMPlayer.stripReasoningFence("Brainstorm  [/model_reasoning]");
        Assert.assertFalse(out.contains(FENCE_CLOSE));
        Assert.assertTrue(out.contains("Brainstorm"));
    }

    /**
     * End-to-end shape of the bug: the large-search parser splits on
     * {@code [;\n]+}. Post-fix, the fence tokens must never appear as
     * candidate card names (pre-fix they were reported back as
     * "the following card names were not recognized").
     */
    @Test
    public void testFenceTokensNeverBecomeCandidateCardNames() {
        String answer = "[model_reasoning]\nthinking out loud\n[/model_reasoning]\n"
                + "Thassa's Oracle, Brainstorm";
        List<String> names = new ArrayList<>();
        for (String part : LLMPlayer.stripReasoningFence(answer).split("[,;\\n]+")) {
            String cleaned = part.trim();
            if (!cleaned.isEmpty()) names.add(cleaned);
        }
        Assert.assertFalse("no fence token may be a card-name candidate",
                names.contains(FENCE_OPEN) || names.contains(FENCE_CLOSE));
        Assert.assertTrue(names.contains("Thassa's Oracle"));
        Assert.assertTrue(names.contains("Brainstorm"));
    }

    /** null in → null out; a fence-free answer is returned unchanged. */
    @Test
    public void testFenceFreeAnswerUnchanged() {
        Assert.assertNull(LLMPlayer.stripReasoningFence(null));
        Assert.assertEquals("Flow State, Brainstorm",
                LLMPlayer.stripReasoningFence("Flow State, Brainstorm"));
    }

    // ── Permanent-label fallback ──────────────────────────────────────

    /**
     * With no resolvable permanent (game == null), the label must be a short
     * "(gone: …)" marker — never the raw 36-char UUID the transcript used to
     * carry ("Engine rejected the mana plan (permanent add25ef6-…-…)").
     */
    @Test
    public void testPermanentLabelFallbackShrinksRawUuid() throws Exception {
        Method m = LLMPlayer.class.getDeclaredMethod("permanentLabel",
                mage.game.Game.class, UUID.class);
        m.setAccessible(true);
        UUID id = UUID.fromString("add25ef6-c808-4e5d-a9dd-de7566a64eb4");
        String label = (String) m.invoke(null, null, id);

        Assert.assertFalse("must not print the raw full UUID",
                label.contains(id.toString()));
        Assert.assertTrue("unresolved permanent reads as gone: " + label,
                label.startsWith("(gone:"));
    }

    /** Null id is handled without throwing. */
    @Test
    public void testPermanentLabelNullId() throws Exception {
        Method m = LLMPlayer.class.getDeclaredMethod("permanentLabel",
                mage.game.Game.class, UUID.class);
        m.setAccessible(true);
        String label = (String) m.invoke(null, null, (Object) null);
        Assert.assertNotNull(label);
        Assert.assertFalse(label.isEmpty());
    }
}
