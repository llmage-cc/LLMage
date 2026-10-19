package org.mage.test.serverside.deck;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.P1DeckPool;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Unit tests for the COMPUTER_P1 deck restriction ({@link P1DeckPool} and the
 * server-side gate). This is the REAL proof the feature works: no live rig is
 * required — the server decision ({@code isTrainingDeck}) is exactly what the
 * authoritative {@code TableController} gate calls.
 *
 * <p>The held-out eval decks are read from a bundled test resource
 * ({@code /p1/standard-heldout.json}) so the test can assert they are REJECTED:
 * the restriction must not offer, and must not accept, the 4 eval lists.
 */
public class P1DeckPoolTest {

    /** The 4 held-out EVAL decks (must NOT be in the pool). */
    private static JsonObject heldout() {
        try (InputStream in = P1DeckPoolTest.class.getResourceAsStream("/p1/standard-heldout.json")) {
            Assert.assertNotNull("held-out fixture missing", in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static DeckCardLists toLists(JsonObject counts, String name) {
        DeckCardLists deck = new DeckCardLists();
        deck.setName(name);
        for (Map.Entry<String, JsonElement> entry : counts.entrySet()) {
            deck.getCards().add(new DeckCardInfo(entry.getKey(), "", "", entry.getValue().getAsInt()));
        }
        return deck;
    }

    @Test
    public void testPoolSizeAndDefault() {
        P1DeckPool pool = P1DeckPool.instance();
        Assert.assertEquals("pool must hold exactly 55 training decks", 55, pool.size());
        Assert.assertTrue("default deck must be in the pool",
                pool.deckNames().contains(pool.defaultDeckName()));
        Assert.assertEquals("Izzet Spellementals", pool.defaultDeckName());
        Assert.assertNotNull("default deck text must be materializable", pool.defaultDeck());
        Assert.assertEquals(pool.defaultDeckName(), pool.defaultDeck().getName());
    }

    @Test
    public void testTrainingDecksAccepted() {
        P1DeckPool pool = P1DeckPool.instance();
        // Every one of the 55 canonical lists is accepted.
        for (String name : pool.deckNames()) {
            DeckCardLists lists = pool.canonicalDeck(name);
            Assert.assertTrue("canonical training deck should be accepted: " + name,
                    pool.isTrainingDeck(lists));
        }
    }

    @Test
    public void testOffPoolDeckRejected() {
        P1DeckPool pool = P1DeckPool.instance();
        DeckCardLists deck = new DeckCardLists();
        deck.setName("Hand-Built Junk");
        for (int i = 0; i < 20; i++) deck.getCards().add(new DeckCardInfo("Mountain", "", "", 3));
        Assert.assertFalse("an arbitrary 60-card deck must be rejected", pool.isTrainingDeck(deck));
    }

    @Test
    public void testNameSpoofRejected() {
        // A deck NAMED like a training list but with the wrong cards must be rejected:
        // membership is decided on the multiset, not the display name.
        P1DeckPool pool = P1DeckPool.instance();
        DeckCardLists spoof = new DeckCardLists();
        spoof.setName("Izzet Spellementals");
        spoof.getCards().add(new DeckCardInfo("Mountain", "", "", 60));
        Assert.assertFalse("name-spoofed deck must be rejected", pool.isTrainingDeck(spoof));
    }

    @Test
    public void testNearMissRejected() {
        // One card swapped out of a real training deck must fail the fingerprint.
        P1DeckPool pool = P1DeckPool.instance();
        DeckCardLists near = pool.canonicalDeck("Izzet Spellementals");
        Assert.assertTrue(pool.isTrainingDeck(near));
        DeckCardInfo first = near.getCards().get(0);
        near.getCards().remove(0);
        near.getCards().add(new DeckCardInfo("Definitely Not A Real Card", "", "", first.getAmount()));
        Assert.assertFalse("a one-card deviation must be rejected", pool.isTrainingDeck(near));
    }

    @Test
    public void testHeldOutEvalDecksRejected() {
        P1DeckPool pool = P1DeckPool.instance();
        JsonObject held = heldout();
        Assert.assertFalse("held-out fixture should not be empty", held.size() == 0);
        for (Map.Entry<String, JsonElement> entry : held.entrySet()) {
            String name = entry.getKey();
            DeckCardLists deck = toLists(entry.getValue().getAsJsonObject(), name);
            Assert.assertFalse("held-out eval deck must NOT be in the pool: " + name,
                    pool.isTrainingDeck(deck));
        }
    }

    @Test
    public void testDefaultDeckIsExactlyDefault() {
        P1DeckPool pool = P1DeckPool.instance();
        DeckCardLists d = pool.defaultDeck();
        Assert.assertTrue("the materialized default must itself be a training deck", pool.isTrainingDeck(d));
    }
}
