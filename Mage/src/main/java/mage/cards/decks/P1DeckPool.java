package mage.cards.decks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.cards.Card;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The Argentum-P1 training deck pool — the ONLY decks a {@code COMPUTER_P1}
 * seat may be given.
 *
 * <p>The pool is exactly the 55 Standard decklists the policy net was trained
 * on. It lives as a classpath resource in the {@code mage} module
 * ({@code /p1/standard-train-decks.json}) so the server can read it WITHOUT a
 * runtime HuggingFace download: the server has no network dependency at join
 * time, and a plugin can never supply its own pool.
 *
 * <p>Pool membership is decided on the deck's <b>card multiset</b> (maindeck +
 * sideboard), not the display name: a hand-edited "Izzet Spellementals" whose
 * cards differ fingerprints differently and is rejected. This class is the
 * single source of truth used by BOTH:
 * <ul>
 *   <li>the server-side authoritative gate in
 *       {@code mage.server.TableController} (join + sideboard), and</li>
 *   <li>the offering surfaces (web proxy seat list, desktop client picker),
 *       which surface {@link #deckNames()} verbatim.</li>
 * </ul>
 *
 * <p>Deliberate product restriction: the net must never face an
 * out-of-distribution deck it cannot read. A future experimental option may
 * relax this; see {@link #isNonTrainingAllowed()}.
 */
public final class P1DeckPool {

    /**
     * System property naming the future experimental escape hatch. When
     * {@code true} the P1 deck restriction is LIFTED for the running server.
     * Default is absent/false — the restriction is HARD.
     *
     * <p>TODO(shim): this is the future experimental path. It is read here but
     * NOT wired into any shipped config (no config.xml entry, no start script
     * flag); an operator must set it explicitly with {@code -D}. When the
     * restriction is genuinely relaxed, replace this with a real
     * per-table/per-seat option and delete this shim.
     */
    public static final String ALLOW_NON_TRAINING_PROPERTY = "xmage.p1.allowNonTrainingDecks";

    /** The default deck for a P1 seat with no explicit choice (must be in the pool). */
    public static final String DEFAULT_DECK_NAME = "Izzet Spellementals";

    /**
     * Maindeck size below which a "deck" is not a legal 60-card constructed
     * list. The two 61-card training lists (4c Control, 4c Ouroboroid) are
     * legal and must pass; a hand-built 4-card stub that happens to carry a
     * training deck's name must not be able to trivially match it.
     */
    public static final int CONSTRUCTED_MIN_MAIN = 60;

    private static final String RESOURCE = "/p1/standard-train-decks.json";
    private static volatile P1DeckPool instance;

    /** fingerprint (see {@link #fingerprint}) -> canonical deck name. */
    private final Map<String, String> byFingerprint;
    /** canonical deck name -> original (unnormalized) card-name counts. */
    private final Map<String, Map<String, Integer>> decks;
    /** canonical deck name -> paste-ready decklist text (proxy/UI offering path). */
    private final Map<String, String> deckText;
    private final List<String> names;

    private P1DeckPool(Map<String, Map<String, Integer>> decks) {
        this.decks = Collections.unmodifiableMap(decks);
        Map<String, String> fp = new LinkedHashMap<>();
        Map<String, String> text = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> e : decks.entrySet()) {
            fp.put(fingerprintFromCounts(e.getValue()), e.getKey());
            text.put(e.getKey(), toDeckListText(e.getValue()));
        }
        this.byFingerprint = Collections.unmodifiableMap(fp);
        this.deckText = Collections.unmodifiableMap(text);
        this.names = Collections.unmodifiableList(new ArrayList<>(decks.keySet()));
    }

    /** Lazily load and parse the bundled pool. Fail Fast on a missing/broken resource. */
    public static P1DeckPool instance() {
        P1DeckPool local = instance;
        if (local == null) {
            synchronized (P1DeckPool.class) {
                local = instance;
                if (local == null) {
                    local = new P1DeckPool(load());
                    instance = local;
                }
            }
        }
        return local;
    }

    private static Map<String, Map<String, Integer>> load() {
        try (InputStream in = P1DeckPool.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("P1 training deck pool resource missing from classpath: " + RESOURCE);
            }
            String json = readUtf8(in);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.size() != 55) {
                throw new IllegalStateException("P1 training deck pool must hold exactly 55 decks, found "
                        + root.size() + " (" + RESOURCE + ")");
            }
            Map<String, Map<String, Integer>> decks = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> deckEntry : root.entrySet()) {
                JsonObject cards = deckEntry.getValue().getAsJsonObject();
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> cardEntry : cards.entrySet()) {
                    counts.put(cardEntry.getKey(), cardEntry.getValue().getAsInt());
                }
                decks.put(deckEntry.getKey(), Collections.unmodifiableMap(counts));
            }
            return decks;
        } catch (IllegalStateException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Unable to load the P1 training deck pool (" + RESOURCE + "): "
                    + e.getMessage(), e);
        }
    }

    /** Read a classpath stream as UTF-8, stripping a UTF-8 BOM if present. */
    private static String readUtf8(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        byte[] bytes = out.toByteArray();
        int start = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            start = 3;
        }
        return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    //  Queries
    // ------------------------------------------------------------------

    /** Number of decks in the pool (55). */
    public int size() {
        return names.size();
    }

    /** The 55 canonical deck names, in resource order. */
    public List<String> deckNames() {
        return names;
    }

    public boolean isKnownDeckName(String name) {
        return name != null && decks.containsKey(name);
    }

    /** The deterministic default deck for a P1 seat with no explicit choice. */
    public String defaultDeckName() {
        return DEFAULT_DECK_NAME;
    }

    /** Paste-ready decklist text for a canonical deck name (offering path). */
    public String deckText(String name) {
        return deckText.get(name);
    }

    /**
     * True when the given engine {@link Deck} is exactly one of the 55 training
     * lists (maindeck + sideboard multiset, 60-card floor). This is the
     * authoritative server-side check: it runs on the real, DB-resolved deck.
     */
    public boolean isTrainingDeck(Deck deck) {
        if (deck == null) {
            return false;
        }
        return isTrainingDeck(deck.getCards(), deck.getSideboard());
    }

    /**
     * Pre-load check on the text-only client shape (what the proxy parses and
     * hands to {@code joinTable}). Equivalent to {@link #isTrainingDeck(Deck)}
     * but usable before card resolution.
     */
    public boolean isTrainingDeck(DeckCardLists deck) {
        if (deck == null) {
            return false;
        }
        return isTrainingDeck(deck.getCards(), deck.getSideboard());
    }

    private boolean isTrainingDeck(java.util.Collection<Card> main, java.util.Collection<Card> side) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int mainCount = 0;
        if (main != null) {
            for (Card c : main) {
                if (c == null) continue;
                counts.merge(c.getName(), 1, Integer::sum);
                mainCount++;
            }
        }
        if (mainCount < CONSTRUCTED_MIN_MAIN) {
            return false;
        }
        if (side != null) {
            for (Card c : side) {
                if (c == null) continue;
                counts.merge(c.getName(), 1, Integer::sum);
            }
        }
        return byFingerprint.containsKey(fingerprintFromCounts(counts));
    }

    private boolean isTrainingDeck(List<DeckCardInfo> main, List<DeckCardInfo> side) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int mainCount = 0;
        if (main != null) {
            for (DeckCardInfo info : main) {
                if (info == null || info.getCardName() == null) continue;
                counts.merge(info.getCardName(), info.getAmount(), Integer::sum);
                mainCount += info.getAmount();
            }
        }
        if (mainCount < CONSTRUCTED_MIN_MAIN) {
            return false;
        }
        if (side != null) {
            for (DeckCardInfo info : side) {
                if (info == null || info.getCardName() == null) continue;
                counts.merge(info.getCardName(), info.getAmount(), Integer::sum);
            }
        }
        return byFingerprint.containsKey(fingerprintFromCounts(counts));
    }

    /**
     * Human-readable membership decision for the authoritative rejection log
     * and the client message. Returns {@code null} when the deck is in the pool.
     */
    public String checkMembership(Deck deck) {
        if (isTrainingDeck(deck)) {
            return null;
        }
        return "deck is not one of the 55 Argentum-P1 training decklists";
    }

    /** A short diagnostic line naming the pool size and the first mismatching card, if any. */
    public String describeMismatch(Deck deck) {
        if (deck == null) {
            return "no deck";
        }
        if (isTrainingDeck(deck)) {
            return "in pool";
        }
        // Name the deck the submitted list is CLOSEST to (lowest differing-card count),
        // so a near-miss ("hand-edited Izzet Spellementals") is visible in the log.
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Card c : deck.getCards()) {
            if (c != null) counts.merge(c.getName(), 1, Integer::sum);
        }
        for (Card c : deck.getSideboard()) {
            if (c != null) counts.merge(c.getName(), 1, Integer::sum);
        }
        return nearest(counts);
    }

    /** Same as {@link #describeMismatch(Deck)} for the text-only client shape. */
    public String describeMismatch(DeckCardLists deck) {
        if (deck == null) {
            return "no deck";
        }
        if (isTrainingDeck(deck)) {
            return "in pool";
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (DeckCardInfo info : deck.getCards()) {
            if (info != null && info.getCardName() != null) {
                counts.merge(info.getCardName(), info.getAmount(), Integer::sum);
            }
        }
        for (DeckCardInfo info : deck.getSideboard()) {
            if (info != null && info.getCardName() != null) {
                counts.merge(info.getCardName(), info.getAmount(), Integer::sum);
            }
        }
        return nearest(counts);
    }

    private String nearest(Map<String, Integer> counts) {
        String closest = null;
        int closestDelta = Integer.MAX_VALUE;
        for (Map.Entry<String, Map<String, Integer>> e : decks.entrySet()) {
            int delta = multisetDistance(counts, e.getValue());
            if (delta < closestDelta) {
                closestDelta = delta;
                closest = e.getKey();
            }
        }
        return "not in pool (nearest training deck '" + closest + "' differs by " + closestDelta + " card(s))";
    }

    // ------------------------------------------------------------------
    //  The experimental escape hatch (default OFF)
    // ------------------------------------------------------------------

    /**
     * The future experimental path: when {@code true}, a P1 seat may run decks
     * outside the training pool. DEFAULT OFF; read from the system property
     * {@link #ALLOW_NON_TRAINING_PROPERTY} only, never from config.xml.
     *
     * <p>TODO(shim): replace with a real per-table/per-seat option when the
     * experimental relaxation is designed; this property must NOT become a
     * shipped default.
     */
    public static boolean isNonTrainingAllowed() {
        return Boolean.parseBoolean(System.getProperty(ALLOW_NON_TRAINING_PROPERTY, "false"));
    }

    // ------------------------------------------------------------------
    //  Fingerprint + text helpers
    // ------------------------------------------------------------------

    /**
     * Canonical multiset fingerprint. Card names are normalized (trim, collapse
     * whitespace, lowercase, normalize the multi-name separator to a single
     * {@code " // "}) and counted; the counts are emitted in sorted order so the
     * fingerprint is order-independent. Basic-land printings with the same name
     * therefore match, but any card-count difference changes the fingerprint.
     */
    private static String fingerprintFromCounts(Map<String, Integer> counts) {
        Map<String, Integer> normalized = new TreeMap<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            normalized.merge(normalizeName(e.getKey()), e.getValue(), Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : normalized.entrySet()) {
            sb.append(e.getValue()).append('\u001f').append(e.getKey()).append('\u001e');
        }
        return sb.toString();
    }

    private static int multisetDistance(Map<String, Integer> a, Map<String, Integer> bRaw) {
        Map<String, Integer> b = new TreeMap<>();
        for (Map.Entry<String, Integer> e : bRaw.entrySet()) {
            b.merge(normalizeName(e.getKey()), e.getValue(), Integer::sum);
        }
        Map<String, Integer> aN = new TreeMap<>();
        for (Map.Entry<String, Integer> e : a.entrySet()) {
            aN.merge(normalizeName(e.getKey()), e.getValue(), Integer::sum);
        }
        int delta = 0;
        for (String k : aN.keySet()) {
            delta += Math.abs(aN.getOrDefault(k, 0) - b.getOrDefault(k, 0));
        }
        for (String k : b.keySet()) {
            if (!aN.containsKey(k)) {
                delta += b.get(k);
            }
        }
        return delta;
    }

    /** Normalize a card name for multiset matching. */
    static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        String s = name.trim().toLowerCase(Locale.ENGLISH);
        // Collapse the multi-name separator and any surrounding whitespace to " // ".
        s = s.replaceAll("\\s*/+\\s*", " // ");
        // Collapse any remaining internal whitespace runs.
        s = s.replaceAll("\\s+", " ");
        return s;
    }

    /** Paste-ready decklist text ("N Name", no sideboard — the pool is maindeck-only). */
    private static String toDeckListText(Map<String, Integer> counts) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            sb.append(e.getValue()).append(' ').append(e.getKey()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Parse the pool's canonical text for {@code name} into a client-side
     * {@link DeckCardLists}. Used by the proxy when it must materialize a
     * default deck for a P1 seat.
     */
    public DeckCardLists canonicalDeck(String name) {
        String text = deckText.get(name);
        if (text == null) {
            return null;
        }
        DeckCardLists lists = new DeckCardLists();
        lists.setName(name);
        for (String line : text.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int sp = line.indexOf(' ');
            int amount = Integer.parseInt(line.substring(0, sp));
            String cardName = line.substring(sp + 1);
            lists.getCards().add(new DeckCardInfo(cardName, "", "", amount));
        }
        return lists;
    }

    /** The deterministic default deck, materialized. */
    public DeckCardLists defaultDeck() {
        return canonicalDeck(DEFAULT_DECK_NAME);
    }
}
