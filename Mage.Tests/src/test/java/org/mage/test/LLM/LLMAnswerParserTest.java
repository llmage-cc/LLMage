package org.mage.test.LLM;

import mage.player.llm.LLMAnswerParser;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pure-parser tests for {@link LLMAnswerParser}, the two answer parsers the
 * LLM player uses for target selections and sideboard card lists.
 *
 * <p>Both parsers were extracted from {@code LLMPlayer} precisely so these
 * defects are exercisable without a game harness:
 *
 * <ul>
 *   <li><b>Target selection.</b> The validator accepts any run of digits,
 *       commas and whitespace, but the parse arms split on comma only, so
 *       whitespace-separated production answers ("18   2       19  18") became
 *       one token and the re-thrown {@code NumberFormatException} ended two
 *       2026-10-29 Bo3 games as technical winners.</li>
 *   <li><b>Sideboard.</b> Answer lines were returned verbatim, so markdown
 *       emphasis, bullet markers and stray section labels all became card
 *       names ("Could not find '**' in maindeck" ...).</li>
 * </ul>
 */
public class LLMAnswerParserTest {

    // ── Defect 1: target-selection numbers ────────────────────────────────

    /** The first production answer, verbatim: whitespace-separated numbers. */
    @Test
    public void whitespaceSeparatedProductionAnswerParses() {
        Assert.assertEquals(Arrays.asList(18, 2, 19, 18),
                LLMAnswerParser.parseTargetNumbers("18   2       19  18"));
    }

    /** The second production answer, verbatim. */
    @Test
    public void secondWhitespaceSeparatedProductionAnswerParses() {
        Assert.assertEquals(Arrays.asList(1, 7, 7),
                LLMAnswerParser.parseTargetNumbers("1                7             7"));
    }

    /** Every form the prompt/validator accept keeps parsing. */
    @Test
    public void singleAndCommaSeparatedFormsParse() {
        Assert.assertEquals(Collections.singletonList(3),
                LLMAnswerParser.parseTargetNumbers("3"));
        Assert.assertEquals(Arrays.asList(1, 4),
                LLMAnswerParser.parseTargetNumbers("1,4"));
        Assert.assertEquals("padded comma form (validator trims the same way)",
                Arrays.asList(1, 4),
                LLMAnswerParser.parseTargetNumbers(" 1 , 4 "));
        Assert.assertEquals("mixed comma and whitespace",
                Arrays.asList(1, 4, 7),
                LLMAnswerParser.parseTargetNumbers("1, 4   7"));
    }

    /** Digits survive the non-numeric strip; a prose answer still parses. */
    @Test
    public void proseAnswerStillYieldsItsNumbers() {
        Assert.assertEquals(Arrays.asList(2, 5),
                LLMAnswerParser.parseTargetNumbers("pick 2 then 5 ok"));
    }

    /** A strictly non-numeric answer is an empty list — never an exception. */
    @Test
    public void nonNumericAnswerYieldsEmptyListAndNeverThrows() {
        Assert.assertTrue(LLMAnswerParser.parseTargetNumbers("I choose the second one").isEmpty());
        Assert.assertTrue(LLMAnswerParser.parseTargetNumbers("").isEmpty());
        Assert.assertTrue(LLMAnswerParser.parseTargetNumbers("   ").isEmpty());
        Assert.assertTrue(LLMAnswerParser.parseTargetNumbers(null).isEmpty());
    }

    /**
     * A numeric-looking token too large for an int is skipped and reported, not
     * thrown — the caller logs it and lets the retry ladder handle the missing
     * target, which is the whole point of "a bad token must not throw".
     */
    @Test
    public void unparsableTokenIsSkippedAndReportedNotThrown() {
        LLMAnswerParser.TargetNumbers parsed =
                LLMAnswerParser.parseTargetNumbersReporting("1, 99999999999999999999");

        Assert.assertEquals(Collections.singletonList(1), parsed.numbers());
        Assert.assertEquals(Collections.singletonList("99999999999999999999"), parsed.skipped());
    }

    /** Only the first line is read: reasoning below it is not a selection. */
    @Test
    public void onlyTheFirstLineIsParsed() {
        Assert.assertEquals(Collections.singletonList(2),
                LLMAnswerParser.parseTargetNumbers("2\nReasoning: I also like 5 and 9."));
    }

    // ── Defect 2: sideboard card lists ────────────────────────────────────

    /** The reported shape: a markdown-bulleted plan must yield clean names. */
    @Test
    public void markdownBulletedPlanYieldsCleanCardNames() {
        String inSection = "**IN:**\n"
                + "- Pyroblast\n"
                + "* Fatal Push\n"
                + "1. Prismatic Ending";
        String outSection = "OUT:\n"
                + "- Lightning Bolt";

        List<String> cardsIn = LLMAnswerParser.parseCardList(inSection);
        List<String> cardsOut = LLMAnswerParser.parseCardList(outSection);

        Assert.assertEquals(Arrays.asList("Pyroblast", "Fatal Push", "Prismatic Ending"), cardsIn);
        Assert.assertEquals(Collections.singletonList("Lightning Bolt"), cardsOut);

        for (String name : cardsIn) {
            Assert.assertFalse("markdown emphasis leaked into '" + name + "'", name.contains("**"));
            Assert.assertFalse("bullet marker leaked into '" + name + "'", name.startsWith("- "));
        }
        Assert.assertFalse("IN: became a card name", cardsIn.contains("IN:"));
        Assert.assertFalse("OUT: became a card name", cardsOut.contains("OUT:"));
    }

    /** Underscore/bold/tilde/backtick emphasis and other bullet styles. */
    @Test
    public void otherMarkdownAndListFormsAreNormalised() {
        String section = "**__~~` Fatal Push `~~__**\n"
                + "+ Prismatic Ending\n"
                + "2) Lightning Bolt\n"
                + "\u2022 Thoughtseize\n"
                + "NONE\n"
                + "SIDEBOARD\n"
                + "";

        Assert.assertEquals(
                Arrays.asList("Fatal Push", "Prismatic Ending", "Lightning Bolt", "Thoughtseize"),
                LLMAnswerParser.parseCardList(section));
    }

    /** A residual section label carrying no card name is dropped. */
    @Test
    public void residualSectionLabelsAreDropped() {
        Assert.assertTrue(LLMAnswerParser.parseCardList("IN:").isEmpty());
        Assert.assertTrue(LLMAnswerParser.parseCardList("**OUT:**").isEmpty());
        Assert.assertTrue(LLMAnswerParser.parseCardList("PLAN").isEmpty());
        Assert.assertTrue(LLMAnswerParser.parseCardList("none").isEmpty());
        Assert.assertTrue(LLMAnswerParser.parseCardList("").isEmpty());
        Assert.assertTrue(LLMAnswerParser.parseCardList(null).isEmpty());
    }

    /**
     * Comma-bearing card names are NOT split: the whole line is the entry, so
     * resolution tries "Kess, Dissident Mage" before any of its parts.
     */
    @Test
    public void commaBearingCardNameIsNotSplit() {
        List<String> entries = LLMAnswerParser.parseCardList("- **Kess, Dissident Mage**");

        Assert.assertEquals(Collections.singletonList("Kess, Dissident Mage"), entries);
        Assert.assertEquals("the whole name is tried first, parts are fallbacks only",
                "Kess, Dissident Mage",
                LLMAnswerParser.cardNameCandidates(entries.get(0)).get(0));
    }

    /**
     * An inline list on one line ("IN: a, b") keeps the label out of the name
     * and still exposes both names — through the comma fallback, after the
     * whole entry fails to resolve.
     */
    @Test
    public void inlineSectionLabelLineStillYieldsBothNames() {
        List<String> entries = LLMAnswerParser.parseCardList("IN: Pyroblast, Red Elemental Blast");

        Assert.assertEquals(Collections.singletonList("Pyroblast, Red Elemental Blast"), entries);
        List<String> candidates = LLMAnswerParser.cardNameCandidates(entries.get(0));
        Assert.assertEquals("whole entry first", "Pyroblast, Red Elemental Blast", candidates.get(0));
        Assert.assertEquals("then the comma parts, both names",
                Arrays.asList("Pyroblast", "Red Elemental Blast"),
                candidates.subList(1, candidates.size()));
    }

    /**
     * End-to-end over the exact slicing {@code applySideboardChanges} does on a
     * markdown answer: the IN:/OUT: markers sit inside "**", and the sections
     * still parse to card names only.
     */
    @Test
    public void markdownAnswerSlicedLikeTheCallerParsesCleanly() {
        String answer = "Here is my plan:\n"
                + "**IN:**\n"
                + "- **Pyroblast**\n"
                + "- **Fatal Push**\n"
                + "**OUT:**\n"
                + "- Lightning Bolt\n"
                + "- Spell Pierce\n";

        String upper = answer.toUpperCase();
        int inIdx = upper.indexOf("IN:");
        int outIdx = upper.indexOf("OUT:", inIdx >= 0 ? inIdx : 0);
        Assert.assertTrue("the caller's IN:/OUT: detection must still find both", inIdx >= 0 && outIdx >= 0);

        List<String> cardsIn = LLMAnswerParser.parseCardList(answer.substring(inIdx + 3, outIdx).trim());
        List<String> cardsOut = LLMAnswerParser.parseCardList(answer.substring(outIdx + 4).trim());

        Assert.assertEquals(Arrays.asList("Pyroblast", "Fatal Push"), cardsIn);
        Assert.assertEquals(Arrays.asList("Lightning Bolt", "Spell Pierce"), cardsOut);
    }
}
