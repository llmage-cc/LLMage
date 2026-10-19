package mage.webproxy;

import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for DeckLoader.parseDeckList, focused on the 2026-09-19 annotated-export
 * bug: lines like "1 x Akroma's Will (lcc) 125 [Instant] x1 [:]" used to parse with
 * the annotations glued into the card NAME (set/number empty), which the proxy then
 * accepted silently and the XMage server rejected with the opaque
 * "Card not found - x Akroma's Will (lcc) 125 [Instant] - ... in deck null"

 *
 * Note: lines carrying a resolvable [SET:NUM] code go through the real card DB
 * (DatabaseUtils resolves ./db relative to the CWD), so this module's ./db/cards.h2
 * staging must exist for those cases; codes the DB cannot resolve skip the check
 * by design (the proxy must not reject cards its older DB copy lacks).
 * Stage it with scripts/stage-test-db.sh — a plain copy of a populated DB empties
 * itself on the first run after a rebuild (RepositoryUtil.isNewBuildRun drops the
 * CARD table when the jar's Build-Time is not recorded in the BUILD table).
 */
public class DeckLoaderTest {

    // ── format 1: XMage .dck ──

    @Test
    public void dckFormatParsesSetAndNumber() {
        DeckCardLists deck = DeckLoader.parseDeckList(
            "1 [lcc:125] Akroma's Will\n" +
            "8 [lci:401] Forest\n"
        );
        Assert.assertEquals(2, deck.getCards().size());
        DeckCardInfo first = deck.getCards().get(0);
        Assert.assertEquals("Akroma's Will", first.getCardName());
        Assert.assertEquals("lcc", first.getSetCode());
        Assert.assertEquals("125", first.getCardNumber());
    }

    // ── format 2: simple ──

    @Test
    public void simpleFormatParses() {
        DeckCardLists deck = DeckLoader.parseDeckList(
            "24 Mountain\n12 Grizzly Bears\n8 Lightning Bolt\nSB: 4 Mountain\n"
        );
        Assert.assertEquals(3, deck.getCards().size());
        Assert.assertEquals(1, deck.getSideboard().size());
        Assert.assertEquals("", deck.getCards().get(0).getSetCode());
        Assert.assertEquals("Mountain", deck.getSideboard().get(0).getCardName());
    }

    // ── format 3: annotated export (the 2026-09-19 bug) ──

    @Test
    public void annotatedExportParsesCleanly() {
        DeckCardLists deck = DeckLoader.parseDeckList(
            "1 x Akroma's Will (lcc) 125 [Instant] x1 [:]\n" +
            "8 x Forest (lci) 401 [Land] x8 [:]\n" +
            "1 Pantlaza, Sun-Favored (lcc) 4 [Commander{top}] x1 [:]\n"
        );
        Assert.assertEquals(3, deck.getCards().size());

        DeckCardInfo akroma = deck.getCards().get(0);
        Assert.assertEquals("Akroma's Will", akroma.getCardName());
        Assert.assertEquals("lcc", akroma.getSetCode());
        Assert.assertEquals("125", akroma.getCardNumber());
        Assert.assertEquals(1, akroma.getAmount());

        DeckCardInfo forest = deck.getCards().get(1);
        Assert.assertEquals("Forest", forest.getCardName());
        Assert.assertEquals("lci", forest.getSetCode());
        Assert.assertEquals("401", forest.getCardNumber());
        Assert.assertEquals(8, forest.getAmount());

        DeckCardInfo pantlaza = deck.getCards().get(2);
        Assert.assertEquals("Pantlaza, Sun-Favored", pantlaza.getCardName());
        Assert.assertEquals("lcc", pantlaza.getSetCode());
        Assert.assertEquals("4", pantlaza.getCardNumber());
    }

    @Test
    public void annotatedExportSideboardAndNoEcho() {
        DeckCardLists deck = DeckLoader.parseDeckList(
            "1 x Arcane Signet (lcc) 299 [Artifact]\n" +
            "SB: 2 x Mountain (lci) 400 [Land] x2\n"
        );
        Assert.assertEquals(1, deck.getCards().size());
        Assert.assertEquals(1, deck.getSideboard().size());
        Assert.assertEquals("Arcane Signet", deck.getCards().get(0).getCardName());
        Assert.assertEquals("lcc", deck.getCards().get(0).getSetCode());
        DeckCardInfo sb = deck.getSideboard().get(0);
        Assert.assertEquals("Mountain", sb.getCardName());
        Assert.assertEquals(2, sb.getAmount());
    }

    @Test
    public void annotatedExportBacktracksParenthesizedNames() {
        // "B.F.M. (Big Furry Monster)" — a real card whose NAME contains parentheses.
        // The lazy name group must backtrack to the LAST "(code) digits" pair, so the
        // name survives intact and the (uds) code is used. The deliberately wrong
        // [lcc:125] code makes findCardNameMismatch quote the parsed name in the error,
        // proving the backtracking extracted "B.F.M. (Big Furry Monster)" and not
        // "x B.F.M. (Big Furry Monster) (uds) 126".
        try {
            DeckLoader.parseDeckList("1 x B.F.M. (Big Furry Monster) (uds) 126 [Creature] x1 [:]\n");
            Assert.fail("expected a name-vs-code mismatch rejection");
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            Assert.assertTrue("error should quote the real name: " + msg,
                msg.contains("B.F.M. (Big Furry Monster)"));
            Assert.assertTrue("error should not contain glued annotations: " + msg,
                !msg.contains("(Big Furry Monster) (uds)"));
        }
    }

    @Test
    public void annotatedExportCountEchoContradictionIsRejected() {
        try {
            DeckLoader.parseDeckList("8 x Forest (lci) 401 [Land] x4\n");
            Assert.fail("count 8 vs trailing x4 must be rejected");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("contradicts"));
        }
    }

    @Test
    public void annotatedExportWrongCardForCodeIsRejected() {
        // [lcc:125] is Akroma's Will — naming Apex Altisaur must fail fast.
        try {
            DeckLoader.parseDeckList("1 x Apex Altisaur (lcc) 125 [Instant]\n");
            Assert.fail("annotated line naming a different card than its code must be rejected");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("Invalid deck line 1"));
        }
    }

    @Test
    public void dckFormatWrongCardForCodeIsRejected() {
        try {
            DeckLoader.parseDeckList("1 [lcc:125] Apex Altisaur\n");
            Assert.fail("dck line naming a different card than its code must be rejected");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("Invalid deck line 1"));
        }
    }

    // ── near-miss guard: annotated shape without the full structure ──

    @Test
    public void annotatedExportNoSpaceXAndNoTypeParse() {
        // Moxfield-style "1x Name (set) num" — no space before the x, no [Type] group.
        DeckCardLists deck = DeckLoader.parseDeckList(
            "1x Akroma's Will (lcc) 125 [Instant]\n" +
            "24x Forest (lci) 401 [Land] x24\n" +
            "1 x Pantlaza, Sun-Favored (lcc) 4\n"
        );
        Assert.assertEquals(3, deck.getCards().size());
        Assert.assertEquals("Akroma's Will", deck.getCards().get(0).getCardName());
        Assert.assertEquals(24, deck.getCards().get(1).getAmount());
        Assert.assertEquals("lci", deck.getCards().get(1).getSetCode());
        Assert.assertEquals("Pantlaza, Sun-Favored", deck.getCards().get(2).getCardName());
        Assert.assertEquals("4", deck.getCards().get(2).getCardNumber());
    }

    @Test
    public void plainFormatXSeparatorIsStrippedFromName() {
        // "4x Lightning Bolt" / "4 x Grizzly Bears" — the loose .dck regex swallows the
        // x into the name; it must be stripped (this produced the original garbage).
        DeckCardLists deck = DeckLoader.parseDeckList(
            "4x Lightning Bolt\n4 x Grizzly Bears\n"
        );
        Assert.assertEquals("Lightning Bolt", deck.getCards().get(0).getCardName());
        Assert.assertEquals("Grizzly Bears", deck.getCards().get(1).getCardName());
    }

    @Test
    public void truncatedAnnotatedLineIsRejectedWithHint() {
        try {
            DeckLoader.parseDeckList("1 x Akroma's Will (lcc) 125 [Instant\n");
            Assert.fail("annotated near-miss (truncated [Type]) must be rejected with a hint");
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            Assert.assertTrue("should explain the format problem: " + msg,
                msg.contains("annotated export"));
        }
    }

    @Test
    public void trailingGarbageAnnotatedLineIsRejectedWithHint() {
        try {
            DeckLoader.parseDeckList("1 x Forest (lci) 401 [Land\n");
            Assert.fail("truncated type bracket must be rejected with a hint");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("annotated export"));
        }
    }

    // ── real-world captured deck (subset of the 2026-09-19 Pantlaza list) ──

    @Test
    public void capturedAnnotatedDeckParsesEndToEnd() {
        String deckText =
            "1 x Akroma's Will (lcc) 125 [Instant] x1 [:]\n" +
            "1 x Apex Altisaur (lcc) 232 [Creature] x1 [:]\n" +
            "1 x Arcane Signet (lcc) 299 [Artifact] x1 [:]\n" +
            "8 x Forest (lci) 401 [Land] x8 [:]\n" +
            "4 x Mountain (lci) 400 [Land] x4 [:]\n" +
            "1 x Pantlaza, Sun-Favored (lcc) 4 [Commander{top}] x1 [:]\n";
        DeckCardLists deck = DeckLoader.parseDeckList(deckText);
        Assert.assertEquals(6, deck.getCards().size());
        int total = 0;
        for (DeckCardInfo c : deck.getCards()) {
            total += c.getAmount();
            Assert.assertFalse("name must not contain annotations: " + c.getCardName(),
                c.getCardName().contains("(") && c.getSetCode().isEmpty());
        }
        Assert.assertEquals(16, total);
    }

    // ── general guards still hold ──

    @Test
    public void emptyDeckIsRejected() {
        try {
            DeckLoader.parseDeckList("   \n# only a comment\n");
            Assert.fail("empty deck must be rejected");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("empty"));
        }
    }

    @Test
    public void garbageLineIsRejected() {
        try {
            DeckLoader.parseDeckList("not a deck line\n");
            Assert.fail("garbage must be rejected");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("Invalid deck line 1"));
        }
    }
}
