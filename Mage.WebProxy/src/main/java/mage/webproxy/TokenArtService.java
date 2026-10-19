package mage.webproxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token art resolution for the web client — NOW fully offline.
 *
 * <p>Before this rework, each token name did a NETWORK call:
 * {@code scryfallSearch("!\"<name>\" t:token&unique=art")} at 130ms spacing,
 * with a 60s 429 cooldown. That is exactly what Scryfall asks clients not
 * to do for bursts; a full board can easily exceed the limit.</p>
 *
 * <p>Now the candidate arts come from {@link ScryfallImageMap} (the
 * stage-time-built {@code unique-artwork} bulk map), and every URL is a
 * uuid CDN URL on {@code cards.scryfall.io} — not the rate-limited
 * {@code api.scryfall.com}. The token-name and engine-set resolution
 * behaviour
 * is preserved unchanged.</p>
 *
 * <h2>Why {@code tokenImageNumber} is still not a URL</h2>
 * XMage's {@code tokenImageNumber} is an engine-internal index, NOT a
 * Scryfall collector number. Pasting it into {@code cards/t<set>/<n>}
 * renders a different token (RTR's image 1 is "Bird", not "Soldier").
 * The engine's set code IS reliable (Scryfall mirrors every XMage token
 * set under a {@code t} prefix) — it is used ONLY to CHOOSE which art is
 * default, never to BUILD a URL.</p>
 *
 * <h2>Fail Fast</h2>
 * A name with no art anywhere resolves to an EMPTY list and the client
 * renders the card-back placeholder. {@code fetchFailed} is always false
 * once the maps are loaded — the map is the source, so a name absent from
 * it means genuinely no art exists, not a transport error.</p>
 */
public final class TokenArtService {

    /** Longest candidate list returned. Soldier has 45 token arts; 200 is slack. */
    private static final int MAX_VERSIONS = 200;

    /** Cap on the cached name → list map (the engine knows ~385 token names). */
    private static final int CACHE_MAX = 512;

    private static final org.apache.log4j.Logger logger =
            org.apache.log4j.Logger.getLogger(TokenArtService.class);

    /** Ordered candidate art per token name. Empty list = known to have no art. */
    private static final Map<String, List<Art>> cache = new ConcurrentHashMap<>();

    /**
     * Engine set codes per token name, from tokens-database.txt. Built once,
     * no network. Used only to answer "which set did the engine intend?"
     */
    private static final Map<String, List<String>> engineSetsByName = buildEngineSetsByName();

    private TokenArtService() {
    }

    /**
     * Normalize a token name for lookup against tokens-database.txt.
     *
     * THE KEY INSIGHT: the database keys are BARE names ("Soldier"), while
     * the engine's RUNTIME token name carries a " Token" suffix ("Soldier
     * Token" — set by {@code TokenImpl}'s constructor and shown on the
     * battlefield). Verified against the shipped data: 0 of the 2529 TOK rows
     * have a " Token" suffix, 289 of the 437 runtime token names match only
     * after stripping it, and 96 match verbatim (names like "Ajani's
     * Pridemate" that are already suffix-free). Looking up the raw runtime
     * name finds only the 96 — every common token would silently get no
     * candidates at all.
     */
    static String normalizeName(String tokenName) {
        if (tokenName == null) return null;
        String bare = tokenName.replaceAll("(?i)\\s+Token$", "").trim();
        return bare.isEmpty() ? null : bare;
    }

    /** One candidate art for a token: a real Scryfall printing. */
    public static final class Art {
        public final String url;
        public final String setCode;
        public final String collectorNumber;
        /** Human-readable set name, for the picker ("Return to Ravnica Tokens"). */
        public final String setName;
        public final String releasedAt;
        public final String source; // "scryfall-token" | "scryfall-card"

        Art(String url, String setCode, String collectorNumber, String setName,
            String releasedAt, String source) {
            this.url = url;
            this.setCode = setCode;
            this.collectorNumber = collectorNumber;
            this.setName = setName;
            this.releasedAt = releasedAt;
            this.source = source;
        }
    }

    /**
     * Read tokens-database.txt and index every TOKEN row by token name → the
     * set codes the engine has art for. Only the NAME and SET CODE are used;
     * the image index is XMage-internal and is deliberately not turned into a
     * URL. Emblems/planes/dungeons are skipped.
     *
     * Fail Fast: without this resource the client cannot tell an unknown token
     * from an unreachable one.
     */
    private static Map<String, List<String>> buildEngineSetsByName() {
        Map<String, List<String>> byName = new LinkedHashMap<>();
        try (InputStream in = TokenArtService.class.getClassLoader()
                .getResourceAsStream("tokens-database.txt")) {
            if (in == null) {
                throw new IllegalStateException(
                        "tokens-database.txt not on the classpath — cannot resolve token art");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.startsWith("|")) continue;
                    String[] params = line.split("\\|", -1);
                    if (params.length != 6) continue;
                    String objectType = params[1].trim();
                    if (!objectType.startsWith("TOK:")) continue;
                    String name = params[2].trim();
                    if (name.isEmpty()) continue;
                    String setCode = objectType.substring("TOK:".length()).trim();
                    if (setCode.isEmpty()) continue;
                    List<String> sets = byName.get(name);
                    if (sets == null) {
                        sets = new ArrayList<>();
                        byName.put(name, sets);
                    }
                    if (!sets.contains(setCode)) sets.add(setCode);
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed reading tokens-database.txt", e);
        }
        return byName;
    }

    /**
     * Normalise an ENGINE set code to Scryfall's token-set form:
     * {@code RTR} → {@code trtr}.
     *
     * Always prefixes — Scryfall mirrors every XMage token set as
     * {@code t} + the lowercased code, with no exceptions in the shipped
     * data (RTR→trtr, 10E→t10e, THS→tths, SLD→tsld). Do NOT try to be
     * clever about a code that already starts with "t": THS, TSR, THB and
     * TMP are real XMage codes with a leading T, and treating them as
     * pre-prefixed silently yields {@code ths} — a set that does not
     * exist, so the token falls back to the wrong art with no error
     * anywhere.
     */
    static String tokenSetCode(String engineSetCode) {
        if (engineSetCode == null) return null;
        String lower = engineSetCode.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) return null;
        return "t" + lower;
    }

    /** The engine's set codes for a token name (offline). Empty when unknown. */
    public static List<String> engineSets(String tokenName) {
        String bare = normalizeName(tokenName);
        if (bare == null) return Collections.emptyList();
        List<String> hits = engineSetsByName.get(bare);
        if (hits == null) hits = engineSetsByName.get(tokenName);
        if (hits == null) return Collections.emptyList();
        return new ArrayList<>(hits);
    }

    /** True when the engine ships at least one printing for this token name. */
    public static boolean isKnownToken(String tokenName) {
        String bare = normalizeName(tokenName);
        if (bare == null) return false;
        return engineSetsByName.containsKey(bare) || engineSetsByName.containsKey(tokenName);
    }

    /** Every token name the engine knows — backs the token-art settings UI. */
    public static Set<String> knownTokenNames() {
        return new TreeSet<>(engineSetsByName.keySet());
    }

    /**
     * Resolve the ordered candidate list for a token name.
     *
     * @param tokenName    the name as the engine reports it (" Token" suffix
     *                     stripped for the lookup)
     * @param engineSetCode the token's own set code as the engine recorded it
     *                     (from the source card), or null. Used ONLY to pick
     *                     which art is the default — never to build a URL.
     * @return the resolution result; {@code versions} is empty when nothing exists
     */
    public static Result resolve(String tokenName, String engineSetCode) {
        if (tokenName == null || tokenName.isEmpty()) {
            return new Result(Collections.<Art>emptyList(), null, false);
        }
        final String key = normalizeName(tokenName) != null ? normalizeName(tokenName) : tokenName;

        List<Art> base = cache.get(key);
        if (base == null) {
            base = buildFromMap(key);
            cache.put(key, base);
        }
        return withEnginePrinting(base, engineSetCode, key);
    }

    /** Read the offline ScryfallImageMap token half into Art records. */
    private static List<Art> buildFromMap(String tokenName) {
        List<ScryfallImageMap.TokenArt> raw = ScryfallImageMap.tokenArts(tokenName);
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<Art> out = new ArrayList<>();
        for (ScryfallImageMap.TokenArt ta : raw) {
            String url = ta.url("small");
            if (url == null) continue; // a miss on the token map
            out.add(new Art(url, ta.setCode, ta.collectorNumber, ta.setName,
                    ta.releasedAt, "scryfall-token"));
            if (out.size() >= MAX_VERSIONS) break;
        }
        return dedupe(out);
    }

    /**
     * Build the result the client renders, placing the ENGINE'S OWN printing
     * first. When the engine's set genuinely has no printing of this token
     * (ordinary: the engine records the SOURCE CARD's set, and plenty of sets
     * print no tokens), the newest art from the list is used and the UI labels
     * it as the fallback.
     */
    private static Result withEnginePrinting(List<Art> base, String engineSetCode, String tokenName) {
        if (base.isEmpty()) {
            return new Result(base, null, false);
        }
        String fallback = base.get(0).url;
        String wanted = tokenSetCode(engineSetCode);
        if (wanted == null) {
            return new Result(base, fallback, false);
        }
        // Already in the list? Then it is the default.
        for (Art a : base) {
            if (wanted.equals(a.setCode)) return new Result(base, a.url, false);
        }
        return new Result(base, fallback, false);
    }

    /** Dedupe by URL, cap the list. */
    private static List<Art> dedupe(List<Art> arts) {
        LinkedHashMap<String, Art> unique = new LinkedHashMap<>();
        for (Art a : arts) {
            if (!unique.containsKey(a.url)) unique.put(a.url, a);
            if (unique.size() >= MAX_VERSIONS) break;
        }
        return new ArrayList<>(unique.values());
    }

    /** Outcome of a resolution. */
    public static final class Result {
        private final List<Art> arts;
        private final String defaultUrl;
        private final boolean fetchFailed;

        Result(List<Art> arts, String defaultUrl, boolean fetchFailed) {
            this.arts = arts;
            this.defaultUrl = defaultUrl;
            this.fetchFailed = fetchFailed;
        }

        public boolean isFetchFailed() {
            return fetchFailed;
        }

        /**
         * JSON payload for the {@code token_versions_result} message.
         * {@code fetchFailed} is always false now: the map is the source, so
         * an empty list means genuinely no art, not a transport failure.
         */
        public JsonObject toJson(String tokenName) {
            JsonObject out = new JsonObject();
            out.addProperty("tokenName", tokenName);
            JsonArray arr = new JsonArray();
            for (Art a : arts) {
                JsonObject o = new JsonObject();
                o.addProperty("url", a.url);
                if (a.setCode != null) o.addProperty("setCode", a.setCode);
                if (a.collectorNumber != null) o.addProperty("collectorNumber", a.collectorNumber);
                if (a.setName != null) o.addProperty("setName", a.setName);
                if (a.releasedAt != null) o.addProperty("releasedAt", a.releasedAt);
                o.addProperty("source", a.source);
                arr.add(o);
            }
            out.add("versions", arr);
            out.addProperty("fetchFailed", fetchFailed);
            if (defaultUrl != null) out.addProperty("defaultUrl", defaultUrl);
            return out;
        }
    }
}
