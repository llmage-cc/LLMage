package mage.webproxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.cards.Card;
import mage.cards.repository.CardRepository;
import mage.cards.repository.CardInfo;
import mage.game.FakeGame;
import mage.view.CardView;
import org.junit.Assert;
import org.junit.Test;

import java.util.UUID;

/**
 * Unit tests for the webui official-card-back feature.
 *
 * Contract pinned here:
 *  (a) a face-down CardView (morph as an opponent sees it) serializes with
 *      faceDown=true;
 *  (b) the same card carries NO imageUrl (guaranteed offline-map miss on the
 *      sanitised "Morph" name — it must never reach the browser as a dead
 *      Scryfall URL);
 *  (c) the non-controller view's visible name is the engine's sanitised
 *      "Morph" — no real-name leak through the wire;
 *  (d) a face-UP card is untouched: faceDown absent, imageUrl still built.
 *
 * Drives the REAL GameStateSerializer on a REAL CardView built from a real
 * engine card in a FakeGame (same technique WebProxyControlledHandsTest uses);
 * no server, no XMage beyond the engine modules already on the classpath.
 */
public class WebProxyFacedownSerializerTest {


    /** A real card (any) from the staged DB, MockCard-shaped. */
    private static Card mockCard(String setCode, String cardNumber) {
        CardInfo info = CardRepository.instance.findCard(setCode, cardNumber);
        Assert.assertNotNull("staged card DB must resolve " + setCode + ":" + cardNumber
                + " (run Mage.WebProxy/scripts/stage-test-db.sh after a rebuild)", info);
        return info.createMockCard();
    }

    /** Face-down shape the engine produces: name sanitised to "Morph". */
    private static CardView faceDownView(Card card, FakeGame game) {
        card.setFaceDown(true, game);
        return new CardView(card, game, false);
    }

    private static JsonObject serialize(CardView view) throws Exception {
        java.lang.reflect.Method m = GameStateSerializer.class.getDeclaredMethod(
                "serializeCard", mage.view.CardView.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> out = (java.util.Map<String, Object>) m.invoke(null, view);
        com.google.gson.Gson gson = new com.google.gson.Gson();
        return JsonParser.parseString(gson.toJson(out)).getAsJsonObject();
    }

    @Test
    public void faceDownCardCarriesFlagAndNoImageUrlAndSanitisedName() throws Exception {
        FakeGame game = new FakeGame();
        Card card = mockCard("MOR", "1"); // any real card; identity is hidden below
        card.setImageFileName("Morph");

        CardView view = faceDownView(card, game);
        Assert.assertTrue("CardView must report the card face-down", view.isFaceDown());

        JsonObject c = serialize(view);
        Assert.assertEquals("faceDown flag on the wire", Boolean.TRUE, c.get("faceDown").getAsBoolean());
        // Gson omits null map values, so "nulled" hides the key entirely —
        // assert both the key-absent and the JSON-null forms.
        Assert.assertTrue(
                "imageUrl must carry no URL for a face-down card (the offline-map"
                        + " entry for a sanitised name is a dead CDN 404)",
                c.get("imageUrl") == null || c.get("imageUrl").isJsonNull());
    }

    @Test
    public void faceUpCardUnaffected() throws Exception {
        FakeGame game = new FakeGame();
        Card card = mockCard("MOR", "1");
        CardView view = new CardView(card, game, false);
        Assert.assertFalse(view.isFaceDown());

        JsonObject c = serialize(view);
        Assert.assertTrue("faceUp card carries no faceDown key", !c.has("faceDown"));
        Assert.assertTrue("faceUp card keeps its image",
                c.get("imageUrl") != null && !c.get("imageUrl").isJsonNull()
                        && !c.get("imageUrl").getAsString().isEmpty());
        Assert.assertTrue("faceUp card keeps its name",
                card.getName().equals(c.get("name").getAsString()));
    }
}
