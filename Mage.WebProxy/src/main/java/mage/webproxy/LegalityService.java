package mage.webproxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mage.MageObject;
import mage.cards.Card;
import mage.cards.decks.CardNameUtil;
import mage.cards.decks.Deck;
import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckValidator;
import mage.cards.decks.DeckValidatorError;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.deck.Brawl;
import mage.deck.CanadianHighlander;
import mage.deck.Commander;
import mage.deck.EuropeanHighlander;
import mage.deck.Freeform;
import mage.deck.Frontier;
import mage.deck.HistoricalType2;
import mage.deck.Historic;
import mage.deck.Legacy;
import mage.deck.Modern;
import mage.deck.Oathbreaker;
import mage.deck.Pauper;
import mage.deck.PennyDreadfulCommander;
import mage.deck.Pioneer;
import mage.deck.Standard;
import mage.deck.Vintage;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Server-side deck legality checks. Mirrors the swing client's
 * {@code DeckLegalityPanel}: every labeled format + EDH power level + Bracket 1..5.
 *
 * Runs entirely against the proxy's in-process {@link CardRepository} — no RPC to the
 * XMage server.
 *
 * Bracket logic is a 1:1 port of {@code mage.client.components.BracketLegalityLabel},
 * lifted into the proxy because the swing labels are Client-side and we want zero
 * client dependencies here.
 *
 * Source level 8 — no var, no records.
 */
public final class LegalityService {

    private LegalityService() {}

    // ── Format validators (mirrors DeckLegalityPanel.initDeckLabels) ──
    private static List<DeckValidator> buildValidators() {
        List<DeckValidator> list = new ArrayList<>();
        list.add(new Standard());
        list.add(new Pioneer());
        list.add(new Modern());
        list.add(new Legacy());
        list.add(new Vintage());
        list.add(new Pauper());
        list.add(new Historic());
        list.add(new Commander());
        list.add(new Oathbreaker());
        list.add(new Brawl());
        list.add(new Frontier());
        list.add(new HistoricalType2());
        list.add(new PennyDreadfulCommander());
        list.add(new EuropeanHighlander());
        list.add(new CanadianHighlander());
        list.add(new Freeform());
        return list;
    }

    // ── Main entry ──
    public static JsonObject check(JsonObject deckJson) {
        Deck deck = buildDeck(deckJson);

        JsonObject result = new JsonObject();

        // Formats
        JsonArray formats = new JsonArray();
        for (DeckValidator validator : buildValidators()) {
            JsonObject f = new JsonObject();
            f.addProperty("format", validator.getName());
            f.addProperty("shortName", validator.getShortName());

            boolean legal;
            try {
                legal = validator.validate(deck);
            } catch (Throwable t) {
                // Validator exceptions (e.g. ban list fetch on Standard) should not kill
                // the whole legality check. Mark unknown and surface the message.
                f.addProperty("status", "unknown");
                f.addProperty("error", t.getClass().getSimpleName() + ": " + t.getMessage());
                formats.add(f);
                continue;
            }

            boolean partly = validator.isPartlyValid();
            f.addProperty("status", legal ? "legal" : (partly ? "partly" : "illegal"));

            JsonArray errorsArr = new JsonArray();
            for (DeckValidatorError err : validator.getErrorsListSorted(20)) {
                JsonObject e = new JsonObject();
                e.addProperty("group", err.getGroup());
                e.addProperty("message", err.getMessage());
                if (err.getCardName() != null) e.addProperty("cardName", err.getCardName());
                errorsArr.add(e);
            }
            f.add("errors", errorsArr);
            formats.add(f);
        }
        result.add("formats", formats);

        // EDH power level
        JsonObject edh = new JsonObject();
        try {
            Commander commander = new Commander();
            List<String> foundPowerCards = new ArrayList<>();
            List<String> foundInfo = new ArrayList<>();
            int level = commander.getEdhPowerLevel(deck, foundPowerCards, foundInfo);
            edh.addProperty("level", level);
            JsonArray cardsArr = new JsonArray();
            for (String s : foundPowerCards) cardsArr.add(s);
            edh.add("powerCards", cardsArr);
            JsonArray infoArr = new JsonArray();
            for (String s : foundInfo) infoArr.add(s);
            edh.add("powerInfo", infoArr);
        } catch (Throwable t) {
            edh.addProperty("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        result.add("edhPower", edh);

        // Brackets 1..5
        JsonArray brackets = new JsonArray();
        for (int level = 1; level <= 5; level++) {
            JsonObject b = new JsonObject();
            try {
                BracketResult br = computeBracket(deck, level);
                b.addProperty("level", level);
                b.addProperty("legal", br.badCards.isEmpty());
                JsonArray bad = new JsonArray();
                for (String s : br.badCards) bad.add(s);
                b.add("badCards", bad);
                JsonArray groups = new JsonArray();
                for (Map.Entry<String, java.util.AbstractMap.SimpleEntry<Integer, List<String>>> ge : br.groups.entrySet()) {
                    JsonObject g = new JsonObject();
                    g.addProperty("name", ge.getKey());
                    g.addProperty("count", ge.getValue().getKey());
                    JsonArray cards = new JsonArray();
                    for (String c : ge.getValue().getValue()) cards.add(c);
                    g.add("cards", cards);
                    groups.add(g);
                }
                b.add("groups", groups);
            } catch (Throwable t) {
                b.addProperty("level", level);
                b.addProperty("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            brackets.add(b);
        }
        result.add("brackets", brackets);

        return result;
    }

    // ── Build a real Deck from frontend entries, mirroring DeckLegalityPanel's
    //    mock→real conversion. We go straight to CardRepository. ──
    private static Deck buildDeck(JsonObject deckJson) {
        Deck deck = new Deck();
        deck.setName(deckJson.has("name") ? deckJson.get("name").getAsString() : "web-deck");

        JsonArray cards = deckJson.getAsJsonArray("cards");
        JsonArray sideboard = deckJson.getAsJsonArray("sideboard");

        if (cards != null) {
            for (com.google.gson.JsonElement el : cards) {
                JsonObject ce = el.getAsJsonObject();
                addCard(deck, ce, false);
            }
        }
        if (sideboard != null) {
            for (com.google.gson.JsonElement el : sideboard) {
                JsonObject ce = el.getAsJsonObject();
                addCard(deck, ce, true);
            }
        }
        return deck;
    }

    private static void addCard(Deck deck, JsonObject ce, boolean sideboard) {
        String name = ce.get("name").getAsString();
        String setCode = ce.has("setCode") && !ce.get("setCode").isJsonNull()
                ? ce.get("setCode").getAsString() : "";
        String cardNumber = ce.has("cardNumber") && !ce.get("cardNumber").isJsonNull()
                ? ce.get("cardNumber").getAsString() : "";
        int count = ce.has("count") ? ce.get("count").getAsInt() : 1;

        // Look up REAL card via CardRepository (fail fast if missing).
        CardInfo info;
        boolean resolvedByCode = false;
        if (!setCode.isEmpty() && !cardNumber.isEmpty()) {
            info = CardRepository.instance.findCard(setCode, cardNumber);
            resolvedByCode = true;
        } else {
            info = CardRepository.instance.findPreferredCoreExpansionCard(name);
            if (info == null) info = CardRepository.instance.findCard(name);
        }
        if (info == null) {
            throw new IllegalStateException("Card not found in repository: " + name
                + (!setCode.isEmpty() ? " [" + setCode + ":" + cardNumber + "]" : ""));
        }
        // A [SET:NUM] code is authoritative for WHICH PRINTING, but the line also names a
        // card — and a valid code pointing at a different card used to import silently
        // The guard exists in
        // DeckLoader and on submit_deck; this legality/import path builds cards straight
        // from JSON and bypassed it, so the deck editor's Import… accepted
        // "Lightning Bolt [M10:150]" (= Panic Attack) with no error. Found live 2026-09-17
        // by the SESSION B test agent after the parse fix made the code reach here at all.
        if (resolvedByCode && !CardNameUtil.isSameCardName(name, info)) {
            throw new IllegalStateException(CardNameUtil.buildWrongCardError(name, info));
        }

        // Deck.getCards() / getSideboard() are Set<Card> — adding the same Card instance
        // N times collapses to 1. We must create a fresh Card per copy.
        for (int i = 0; i < count; i++) {
            Card card = info.createCard();
            if (sideboard) deck.getSideboard().add(card);
            else deck.getCards().add(card);
        }
    }

    // ── Bracket calculation (port of BracketLegalityLabel) ──

    private static final String GROUP_GAME_CHANGES = "Game Changers";
    private static final String GROUP_INFINITE_COMBOS = "Early-game 2-Card Combos";
    private static final String GROUP_MASS_LAND_DESTRUCTION = "Mass Land Destruction";
    private static final String GROUP_EXTRA_TURN = "Extra Turns";

    private static final Map<String, List<Integer>> MAX_GROUP_LIMITS = new LinkedHashMap<>();
    static {
        MAX_GROUP_LIMITS.put(GROUP_GAME_CHANGES, Arrays.asList(0, 0, 0, 3, 99, 99));
        MAX_GROUP_LIMITS.put(GROUP_INFINITE_COMBOS, Arrays.asList(0, 0, 0, 0, 99, 99));
        MAX_GROUP_LIMITS.put(GROUP_MASS_LAND_DESTRUCTION, Arrays.asList(0, 0, 0, 0, 99, 99));
        MAX_GROUP_LIMITS.put(GROUP_EXTRA_TURN, Arrays.asList(0, 0, 0, 3, 99, 99));
    }

    private static final List<String> GAME_CHANGERS = new ArrayList<>();
    static {
        GAME_CHANGERS.addAll(Arrays.asList(
            "Ad Nauseam","Ancient Tomb","Aura Shards","Biorhythm","Bolas's Citadel",
            "Braids, Cabal Minion","Demonic Tutor","Drannith Magistrate","Chrome Mox",
            "Coalition Victory","Consecrated Sphinx","Crop Rotation","Cyclonic Rift",
            "Enlightened Tutor","Farewell","Field of the Dead","Fierce Guardianship",
            "Force of Will","Gaea's Cradle","Gamble","Gifts Ungiven","Glacial Chasm",
            "Grand Arbiter Augustin IV","Grim Monolith","Humility","Imperial Seal",
            "Intuition","Jeska's Will","Lion's Eye Diamond","Mana Vault","Mishra's Workshop",
            "Mox Diamond","Mystical Tutor","Narset, Parter of Veils","Natural Order",
            "Necropotence","Notion Thief","Rhystic Study","Opposition Agent",
            "Orcish Bowmasters","Panoptic Mirror","Seedborn Muse","Serra's Sanctum",
            "Smothering Tithe","Survival of the Fittest","Teferi's Protection",
            "Tergrid, God of Fright","Thassa's Oracle","The One Ring",
            "The Tabernacle at Pendrell Vale","Underworld Breach","Vampiric Tutor",
            "Worldly Tutor"
        ));
    }

    private static volatile Set<String> fullInfiniteCombos = null; // lazy-loaded, volatile for thread safety

    private static Set<String> loadInfiniteCombos() {
        if (fullInfiniteCombos != null) return fullInfiniteCombos;
        synchronized (LegalityService.class) {
            if (fullInfiniteCombos != null) return fullInfiniteCombos;
            Set<String> set = new HashSet<>();
            // Resource lives in the mage-*.jar (Mage/src/main/resources/brackets/),
            // which is on the proxy classpath via run-web-proxy.sh's release/lib/*
            // inclusion. Same file BracketLegalityLabel reads.
            InputStream in = LegalityService.class.getClassLoader()
                .getResourceAsStream("brackets/infinite-combos.txt");
            if (in == null) {
                throw new IllegalStateException("infinite-combos.txt missing from proxy resources");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    List<String> cards = Arrays.asList(line.split("@"));
                    if (cards.size() != 2) continue;
                    Collections.sort(cards);
                    set.add(String.join("@", cards));
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Failed to load infinite combos", e);
            }
            fullInfiniteCombos = set;
            return set;
        }
    }

    private static final class BracketResult {
        final List<String> badCards = new ArrayList<>();
        final Map<String, java.util.AbstractMap.SimpleEntry<Integer, List<String>>> groups = new LinkedHashMap<>();
    }

    private static BracketResult computeBracket(Deck deck, int bracketLevel) {
        List<String> foundGameChangers = new ArrayList<>();
        List<String> foundInfiniteCombos = new ArrayList<>();
        List<String> foundMassLand = new ArrayList<>();
        List<String> foundExtraTurn = new ArrayList<>();

        // Collect
        Stream.concat(deck.getCards().stream(), deck.getSideboard().stream())
            .map(MageObject::getName)
            .filter(GAME_CHANGERS::contains)
            .sorted()
            .forEach(foundGameChangers::add);

        Set<String> combos = loadInfiniteCombos();
        List<Card> deckCards = new ArrayList<>();
        deckCards.addAll(deck.getCards());
        deckCards.addAll(deck.getSideboard());
        Set<Card> foundComboCards = new HashSet<>();
        for (Card c1 : deckCards) {
            for (Card c2 : deckCards) {
                if (c1 == c2) continue;
                List<String> names = Arrays.asList(c1.getName(), c2.getName());
                Collections.sort(names);
                if (combos.contains(String.join("@", names))) {
                    foundComboCards.add(c1);
                    foundComboCards.add(c2);
                    break;
                }
            }
        }
        foundComboCards.stream().map(MageObject::getName).sorted().forEach(foundInfiniteCombos::add);

        Stream.concat(deck.getCards().stream(), deck.getSideboard().stream())
            .filter(card -> card.getRules().stream()
                .map(s -> s.toLowerCase(Locale.ENGLISH))
                .anyMatch(s -> (s.contains("destroy") || s.contains("sacrifice"))
                    && (s.contains("all") || s.contains("x target") || s.contains("{x} target"))
                    && containsLandName(s)))
            .map(Card::getName)
            .sorted()
            .forEach(foundMassLand::add);

        Stream.concat(deck.getCards().stream(), deck.getSideboard().stream())
            .filter(card -> card.getRules().stream()
                .map(s -> s.toLowerCase(Locale.ENGLISH))
                .anyMatch(s -> s.contains("extra turn")))
            .map(Card::getName)
            .sorted()
            .forEach(foundExtraTurn::add);

        // Score against bracket limits
        BracketResult r = new BracketResult();
        if (foundGameChangers.size() > MAX_GROUP_LIMITS.get(GROUP_GAME_CHANGES).get(bracketLevel)) {
            r.badCards.addAll(foundGameChangers);
        }
        if (foundInfiniteCombos.size() > MAX_GROUP_LIMITS.get(GROUP_INFINITE_COMBOS).get(bracketLevel)) {
            r.badCards.addAll(foundInfiniteCombos);
        }
        if (foundMassLand.size() > MAX_GROUP_LIMITS.get(GROUP_MASS_LAND_DESTRUCTION).get(bracketLevel)) {
            r.badCards.addAll(foundMassLand);
        }
        if (foundExtraTurn.size() > MAX_GROUP_LIMITS.get(GROUP_EXTRA_TURN).get(bracketLevel)) {
            r.badCards.addAll(foundExtraTurn);
        }

        r.groups.put(GROUP_GAME_CHANGES, new java.util.AbstractMap.SimpleEntry<>(foundGameChangers.size(), foundGameChangers));
        r.groups.put(GROUP_INFINITE_COMBOS, new java.util.AbstractMap.SimpleEntry<>(foundInfiniteCombos.size(), foundInfiniteCombos));
        r.groups.put(GROUP_MASS_LAND_DESTRUCTION, new java.util.AbstractMap.SimpleEntry<>(foundMassLand.size(), foundMassLand));
        r.groups.put(GROUP_EXTRA_TURN, new java.util.AbstractMap.SimpleEntry<>(foundExtraTurn.size(), foundExtraTurn));
        return r;
    }

    private static boolean containsLandName(String lowerText) {
        return lowerText.contains("lands")
            || lowerText.contains("plains")
            || lowerText.contains("island")
            || lowerText.contains("swamp")
            || lowerText.contains("mountain")
            || lowerText.contains("forest");
    }
}
