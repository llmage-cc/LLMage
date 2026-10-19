package mage.webproxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mage.cards.repository.CardCriteria;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.constants.CardType;
import mage.constants.Rarity;
import mage.constants.SubType;
import mage.constants.SuperType;

import java.util.List;
import java.util.Locale;

/**
 * Server-side card search over CardRepository.
 *
 * Backs the web deck editor's SearchPanel and CollectionViewer. The swing client's
 * DeckEditorPanel uses the same CardCriteria flow; we expose a JSON-shaped subset of
 * it over the WebSocket.
 *
 * Source level 8 — no var, no records.
 */
public final class CardSearchService {

    /** Hard cap on a single search; the UI pages with start/count. */
    private static final int MAX_RESULTS_PER_REQUEST = 200;

    /** Cap on card_versions — Lightning Bolt has ~80 printings; the cap is generous. */
    private static final int MAX_VERSIONS_PER_CARD = 100;

    /**
     * Cap on a single card_lookup. The collection is stored in localStorage as
     * `${set}:${num}` keys, so the viewer sends owned keys — not a criteria search.
     * Mirrors MAX_RESULTS_PER_REQUEST: more keys than this is a client bug and the
     * caller must paginate the collection rather than silently truncate (fail fast).
     */
    private static final int MAX_LOOKUP_KEYS = 200;

    /**
     * Exact (setCode, cardNumber) lookups — the primitive a collection view needs.
     *
     * A collection is a set of owned printings, so the viewer must ask for exactly those
     * printings. The previous approach — a broad `card_search` with `setCodes` — could not
     * express "these specific cards": it returned an arbitrary first-200 slice of every
     * card in those sets, so an owned card whose number sorted past the cap (or a set
     * with more than 200 printings) was never rendered. Each key here is resolved with a
     * small indexed `CardCriteria` query (setCode + cardNumberAsInt range), measured at
     * ~1.5 ms/key warm.
     *
     * Keys that resolve to nothing are reported in `missing` — a fail-fast signal, not a
     * silent gap: a collection entry that no longer exists in the card DB means the DB
     * was rebuilt against a different card pool, and the UI must say so.
     *
     * @param keysJson array of {"setCode": "...", "cardNumber": "..."} objects
     */
    public static JsonObject lookupByKeys(JsonArray keysJson) {
        if (keysJson == null) {
            throw new IllegalArgumentException("card_lookup requires a keys array");
        }
        if (keysJson.size() > MAX_LOOKUP_KEYS) {
            throw new IllegalArgumentException("card_lookup accepts at most " + MAX_LOOKUP_KEYS
                    + " keys per request (got " + keysJson.size() + "); the client must page the collection");
        }
        JsonArray cardsArr = new JsonArray();
        JsonArray missingArr = new JsonArray();
        for (com.google.gson.JsonElement el : keysJson) {
            if (!el.isJsonObject()) {
                throw new IllegalArgumentException("card_lookup keys must be objects with setCode/cardNumber");
            }
            JsonObject key = el.getAsJsonObject();
            String setCode = key.has("setCode") && !key.get("setCode").isJsonNull()
                    ? key.get("setCode").getAsString().trim() : "";
            String cardNumber = key.has("cardNumber") && !key.get("cardNumber").isJsonNull()
                    ? key.get("cardNumber").getAsString().trim() : "";
            if (setCode.isEmpty() || cardNumber.isEmpty()) {
                throw new IllegalArgumentException("card_lookup key needs both setCode and cardNumber (got setCode='"
                        + setCode + "', cardNumber='" + cardNumber + "')");
            }
            CardInfo ci = findExact(setCode, cardNumber);
            if (ci == null) {
                missingArr.add(setCode + ":" + cardNumber);
            } else {
                cardsArr.add(toJson(ci));
            }
        }
        JsonObject result = new JsonObject();
        result.add("cards", cardsArr);
        result.addProperty("count", cardsArr.size());
        result.add("missing", missingArr);
        return result;
    }

    /**
     * Resolve one printing exactly.
     *
     * Two primitives, because neither alone is sufficient:
     *  1. A CardCriteria scan (setCode + cardNumberAsInt range) — it forces
     *     splitCardHalf=false, so a split card resolves to the whole card ("Fuss // Bother",
     *     not "Fuss"), and the exact-number check picks the right row out of a collision.
     *  2. `findCard(setCode, cardNumber, false)` — the only primitive that reaches
     *     night-card rows. CardCriteria's constructor hardcodes `nightCard = false`
     *     (CardCriteria.java:59), so the scan in (1) can NEVER return BRO:163b
     *     ("Mishra, Lost to Phyrexia"). Without this fallback a collection holding any of
     *     the 21 night-only printings would report it as missing.
     */
    private static CardInfo findExact(String setCode, String cardNumber) {
        CardCriteria cc = new CardCriteria();
        cc.setCodes(java.util.Collections.singletonList(setCode));
        // Derive the numeric part for the indexed range filter (123b -> 123).
        String numeric = cardNumber.replaceAll("[^0-9].*$", "");
        if (!numeric.isEmpty()) {
            int n = Integer.parseInt(numeric);
            cc.minCardNumber(n);
            cc.maxCardNumber(n);
        }
        for (CardInfo ci : CardRepository.instance.findCards(cc)) {
            if (cardNumber.equalsIgnoreCase(ci.getCardNumber())) {
                return ci;
            }
        }
        return CardRepository.instance.findCard(setCode, cardNumber, false);
    }

    /**
     * All printings (different arts) of a single card by name. Used by the deck editor's
     * "Change Art" right-click action. Returns across sets, ordered by expansion.
     */
    public static JsonObject allVersions(String cardName) {
        if (cardName == null || cardName.trim().isEmpty()) {
            throw new IllegalArgumentException("card_versions requires a non-empty cardName");
        }
        List<CardInfo> cards = CardRepository.instance.findCards(cardName.trim(), MAX_VERSIONS_PER_CARD);
        JsonObject result = new JsonObject();
        JsonArray arr = new JsonArray();
        for (CardInfo ci : cards) {
            arr.add(toJson(ci));
        }
        result.add("versions", arr);
        result.addProperty("count", cards.size());
        result.addProperty("cardName", cardName);
        return result;
    }

    private CardSearchService() {}

    /** Result payload for a card_search request. */
    public static JsonObject search(JsonObject criteria) {
        CardCriteria cc = new CardCriteria();

        if (criteria.has("name") && !criteria.get("name").isJsonNull()) {
            String name = criteria.get("name").getAsString().trim();
            if (!name.isEmpty()) {
                cc.nameContains(name);
            }
        }
        if (criteria.has("rules") && !criteria.get("rules").isJsonNull()) {
            String rules = criteria.get("rules").getAsString().trim();
            if (!rules.isEmpty()) {
                cc.rules(rules);
            }
        }
        if (criteria.has("types") && criteria.get("types").isJsonArray()) {
            for (com.google.gson.JsonElement el : criteria.getAsJsonArray("types")) {
                cc.types(CardType.valueOf(el.getAsString().toUpperCase(Locale.ENGLISH)));
            }
        }
        if (criteria.has("notTypes") && criteria.get("notTypes").isJsonArray()) {
            for (com.google.gson.JsonElement el : criteria.getAsJsonArray("notTypes")) {
                cc.notTypes(CardType.valueOf(el.getAsString().toUpperCase(Locale.ENGLISH)));
            }
        }
        if (criteria.has("subtypes") && criteria.get("subtypes").isJsonArray()) {
            for (com.google.gson.JsonElement el : criteria.getAsJsonArray("subtypes")) {
                cc.subtypes(SubType.byDescription(el.getAsString()));
            }
        }
        if (criteria.has("supertypes") && criteria.get("supertypes").isJsonArray()) {
            for (com.google.gson.JsonElement el : criteria.getAsJsonArray("supertypes")) {
                cc.supertypes(SuperType.valueOf(el.getAsString().toUpperCase(Locale.ENGLISH)));
            }
        }
        if (criteria.has("rarities") && criteria.get("rarities").isJsonArray()) {
            JsonArray arr = criteria.getAsJsonArray("rarities");
            Rarity[] rarities = new Rarity[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                rarities[i] = Rarity.valueOf(arr.get(i).getAsString().toUpperCase(Locale.ENGLISH));
            }
            cc.rarities(rarities);
        }
        if (criteria.has("setCodes") && criteria.get("setCodes").isJsonArray()) {
            JsonArray arr = criteria.getAsJsonArray("setCodes");
            java.util.List<String> codes = new java.util.ArrayList<>();
            for (com.google.gson.JsonElement el : arr) {
                codes.add(el.getAsString());
            }
            cc.setCodes(codes);
        }
        if (criteria.has("manaValue") && !criteria.get("manaValue").isJsonNull()) {
            cc.manaValue(criteria.get("manaValue").getAsInt());
        }

        // Colors
        if (criteria.has("colors") && criteria.get("colors").isJsonArray()) {
            for (com.google.gson.JsonElement el : criteria.getAsJsonArray("colors")) {
                String c = el.getAsString().toLowerCase(Locale.ENGLISH);
                switch (c) {
                    case "white": case "w": cc.white(true); break;
                    case "blue":  case "u": cc.blue(true); break;
                    case "black": case "b": cc.black(true); break;
                    case "red":   case "r": cc.red(true); break;
                    case "green": case "g": cc.green(true); break;
                    default:
                        throw new IllegalArgumentException("Unknown color: " + c);
                }
            }
        }
        if (criteria.has("colorless") && criteria.get("colorless").getAsBoolean()) {
            cc.colorless(true);
        }

        // Paging
        long start = criteria.has("start") ? criteria.get("start").getAsLong() : 0L;
        long count = criteria.has("count") ? criteria.get("count").getAsLong() : 60L;
        if (count <= 0 || count > MAX_RESULTS_PER_REQUEST) {
            count = MAX_RESULTS_PER_REQUEST;
        }
        cc.start(start);
        cc.count(count);

        if (criteria.has("sortBy") && !criteria.get("sortBy").isJsonNull()) {
            cc.setOrderBy(criteria.get("sortBy").getAsString());
        }

        List<CardInfo> cards = CardRepository.instance.findCards(cc);

        JsonObject result = new JsonObject();
        JsonArray cardsArr = new JsonArray();
        for (CardInfo ci : cards) {
            cardsArr.add(toJson(ci));
        }
        result.add("cards", cardsArr);
        result.addProperty("count", cards.size());
        result.addProperty("start", start);
        return result;
    }

    /** Serialize a CardInfo into the JSON the frontend expects. */
    public static JsonObject toJson(CardInfo ci) {
        JsonObject c = new JsonObject();
        c.addProperty("name", ci.getName());
        c.addProperty("setCode", ci.getSetCode());
        c.addProperty("cardNumber", ci.getCardNumber());
        c.addProperty("manaValue", ci.getManaValue());
        c.addProperty("rarity", ci.getRarity() != null ? ci.getRarity().name() : null);
        if (ci.getPower() != null) c.addProperty("power", ci.getPower());
        if (ci.getToughness() != null) c.addProperty("toughness", ci.getToughness());
        if (ci.getStartingLoyalty() != null) c.addProperty("loyalty", ci.getStartingLoyalty());

        JsonArray types = new JsonArray();
        for (CardType t : ci.getTypes()) types.add(t.name());
        c.add("types", types);

        JsonArray subtypes = new JsonArray();
        for (mage.constants.SubType s : ci.getSubTypes()) subtypes.add(s.toString());
        c.add("subtypes", subtypes);

        JsonArray supertypes = new JsonArray();
        for (SuperType st : ci.getSupertypes()) supertypes.add(st.name());
        c.add("supertypes", supertypes);

        JsonArray colorsArr = new JsonArray();
        if (ci.getColor() != null) {
            mage.ObjectColor col = ci.getColor();
            if (col.isWhite()) colorsArr.add("white");
            if (col.isBlue()) colorsArr.add("blue");
            if (col.isBlack()) colorsArr.add("black");
            if (col.isRed()) colorsArr.add("red");
            if (col.isGreen()) colorsArr.add("green");
            if (!col.isMulticolored() && !col.isWhite() && !col.isBlue() && !col.isBlack()
                    && !col.isRed() && !col.isGreen()) {
                colorsArr.add("colorless");
            }
        }
        c.add("colors", colorsArr);

        JsonArray manaCosts = new JsonArray();
        for (String mc : ci.getManaCosts(CardInfo.ManaCostSide.ALL)) manaCosts.add(mc);
        c.add("manaCost", manaCosts);

        JsonArray rules = new JsonArray();
        for (String r : ci.getRules()) rules.add(r);
        c.add("rules", rules);

        // Image URL — offline CDN URL via the Scryfall bulk-data map; the
        // set/number branch falls through to the name map (the uuid is the
        // source of truth either way).
        c.addProperty("imageUrl", GameStateSerializer.buildImageUrl(ci.getName()));

        return c;
    }
}
