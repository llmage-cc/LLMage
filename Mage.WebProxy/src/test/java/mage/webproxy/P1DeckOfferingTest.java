package mage.webproxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;
import org.junit.Assert;
import org.junit.Test;

/**
 * Offering-surface tests for the COMPUTER_P1 deck restriction: the proxy must be
 * able to hand the browser exactly the 55 training deck lists, and the default it
 * materializes for a P1 seat with no explicit choice must itself be in the pool.
 */
public class P1DeckOfferingTest {

    @Test
    public void decksMessageCarriesThePool() {
        JsonObject msg = P1DeckOffering.decksMessage();
        Assert.assertEquals("p1_decks", msg.get("type").getAsString());
        JsonArray decks = msg.getAsJsonArray("decks");
        Assert.assertEquals("the offering surface must list exactly 55 decks", 55, decks.size());
        for (int i = 0; i < decks.size(); i++) {
            JsonObject d = decks.get(i).getAsJsonObject();
            Assert.assertTrue("each entry must carry a decklist text", d.get("deck").getAsString().contains(" "));
        }
        Assert.assertEquals("Izzet Spellementals", msg.get("default").getAsString());

        // Proof output (shown with -Dxmage.build.tests.treeViewRunnerShowAllLogs=true):
        // exactly what the browser receives once per connect.
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < Math.min(5, decks.size()); i++) {
            if (i > 0) names.append(", ");
            names.append(decks.get(i).getAsJsonObject().get("name").getAsString());
        }
        String firstDeckFirstLine = decks.get(0).getAsJsonObject().get("deck").getAsString().split("\n")[0];
        System.out.println("[P1-EVIDENCE] p1_decks message: default=" + msg.get("default").getAsString()
                + " | deckCount=" + decks.size()
                + " | first5=" + names
                + " | firstDeckLine=\"" + firstDeckFirstLine + "\"");
    }

    @Test
    public void defaultDeckIsInThePool() {
        DeckCardLists d = P1DeckOffering.defaultDeck();
        Assert.assertEquals("Izzet Spellementals", d.getName());
        Assert.assertTrue("the materialized default must be a training deck",
                P1DeckOffering.isTrainingDeck(d));
    }

    @Test
    public void offPoolDeckIsNotTraining() {
        DeckCardLists deck = new DeckCardLists();
        deck.setName("anything");
        deck.getCards().add(new DeckCardInfo("Mountain", "", "", 60));
        Assert.assertFalse(P1DeckOffering.isTrainingDeck(deck));
    }
}
