package mage.webproxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.P1DeckPool;

/**
 * Offering surface for the Argentum-P1 deck restriction on the web client.
 *
 * <p>The web New-Game seat dialog must let the user SELECT one of the 55
 * training decks rather than type an arbitrary list (the seat's deck textarea is
 * not a sufficient guarantee — see the server-side gate in
 * {@code mage.server.TableController}). This class serves the canonical list to
 * the browser and materializes the deterministic default deck for a P1 seat with
 * no explicit choice.
 *
 * <p>It is the proxy's half of the offering contract; the actual restriction is
 * enforced server-side, so a crafted client that ignores this list is still
 * rejected at join with a clear reason.
 */
final class P1DeckOffering {

    private P1DeckOffering() {
    }

    /** The {@code p1_decks} wire message: the 55 names + per-deck text + the default. */
    static JsonObject decksMessage() {
        P1DeckPool pool = P1DeckPool.instance();
        JsonObject resp = new JsonObject();
        resp.addProperty("type", "p1_decks");
        JsonArray arr = new JsonArray();
        for (String name : pool.deckNames()) {
            JsonObject d = new JsonObject();
            d.addProperty("name", name);
            d.addProperty("deck", pool.deckText(name));
            arr.add(d);
        }
        resp.add("decks", arr);
        resp.addProperty("default", pool.defaultDeckName());
        return resp;
    }

    /** The pool's deterministic default deck, materialized client-side. */
    static DeckCardLists defaultDeck() {
        return P1DeckPool.instance().defaultDeck();
    }

    /** True when the given client-side deck list is exactly one of the 55 training lists. */
    static boolean isTrainingDeck(DeckCardLists deck) {
        return P1DeckPool.instance().isTrainingDeck(deck);
    }
}
