package mage.webproxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.Normalizer;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Offline Scryfall image-name map. Replaces the rate-limited
 * {@code api.scryfall.com/cards/named} per-card lookups with a
 * stage-time-built map served from the proxy's classpath.
 *
 * Two JSON resources land in mage.jar (same classpath as tokens-database.txt):
 * <ul>
 *   <li>{@code scryfall-name-map.json} — {@code {cards: {name->uuid}, backs: [name]}}</li>
 *   <li>{@code scryfall-token-map.json} — {@code {tokens: {name -> [{id,set,num,set_name,released},…]}}}</li>
 * </ul>
 *
 * All URLs are built manually on the proxy:
 * {@code https://cards.scryfall.io/{size}/front/{id[0]}/{id[1]}/{id}.jpg}
 * (back face: {@code /back/} instead of {@code /front/}, same uuid).
 * The {@code *.scryfall.io} origins are NOT rate-limited; only the api.scryfall.com
 * endpoints are. See
 *
 * Fail Fast: missing or malformed map resources = IllegalStateException at
 * static-init (mirrors TokenArtService.buildEngineSetsByName). A map MISS
 * for a card name = null/empty; NO live-API fallback.
 */
public final class ScryfallImageMap {

    private static final org.apache.log4j.Logger logger =
            org.apache.log4j.Logger.getLogger(ScryfallImageMap.class);

    private static final String NAME_MAP_RESOURCE = "scryfall-name-map.json";
    private static final String TOKEN_MAP_RESOURCE = "scryfall-token-map.json";

    /** Card name → Scryfall uuid (includes folded aliases). */
    private static final Map<String, String> cardMap = loadCardMap();
    /** Token name → candidate arts (uuid + set/number). */
    private static final Map<String, List<TokenArt>> tokenMap = loadTokenMap();

    /** Names whose entry carries a back face (front-face names of DFC/transform). */
    private static final Set<String> backs = loadBacks();

    private ScryfallImageMap() {}

    // ── Resource loading (fail fast) ───────────────────────────────────

    private static InputStream requireResource(String name) {
        InputStream in = ScryfallImageMap.class.getClassLoader().getResourceAsStream(name);
        if (in == null) {
            throw new IllegalStateException(
                    name + " not on the classpath — cannot resolve card images");
        }
        return in;
    }

    private static JsonObject parseResource(String name) {
        try (InputStreamReader r = new InputStreamReader(requireResource(name), "UTF-8")) {
            JsonElement el = JsonParser.parseReader(r);
            if (!el.isJsonObject()) {
                throw new IllegalStateException(name + " is not a JSON object");
            }
            return el.getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed reading " + name, e);
        }
    }

    private static Map<String, String> loadCardMap() {
        JsonObject root = parseResource(NAME_MAP_RESOURCE);
        JsonObject cards = root.getAsJsonObject("cards");
        if (cards == null) {
            throw new IllegalStateException(NAME_MAP_RESOURCE + " missing \"cards\" object");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : cards.entrySet()) {
            map.put(e.getKey(), e.getValue().getAsString());
        }
        if (map.isEmpty()) {
            throw new IllegalStateException(NAME_MAP_RESOURCE + " has 0 entries");
        }
        logger.info("ScryfallImageMap: loaded " + map.size() + " card-name keys");
        return Collections.unmodifiableMap(map);
    }

    private static Set<String> loadBacks() {
        JsonObject root = parseResource(NAME_MAP_RESOURCE);
        JsonArray arr = root.getAsJsonArray("backs");
        Set<String> set = new LinkedHashSet<>();
        if (arr != null) {
            for (JsonElement el : arr) {
                set.add(el.getAsString());
            }
        }
        logger.info("ScryfallImageMap: " + set.size() + " back-face names");
        return Collections.unmodifiableSet(set);
    }

    private static Map<String, List<TokenArt>> loadTokenMap() {
        JsonObject root = parseResource(TOKEN_MAP_RESOURCE);
        JsonObject tokens = root.getAsJsonObject("tokens");
        if (tokens == null) {
            throw new IllegalStateException(TOKEN_MAP_RESOURCE + " missing \"tokens\" object");
        }
        Map<String, List<TokenArt>> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : tokens.entrySet()) {
            JsonArray arr = e.getValue().getAsJsonArray();
            List<TokenArt> arts = new ArrayList<>();
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                arts.add(new TokenArt(
                        o.get("id").getAsString(),
                        o.get("set").getAsString(),
                        o.get("num").getAsString(),
                        o.get("set_name") != null ? o.get("set_name").getAsString() : null,
                        o.get("released") != null ? o.get("released").getAsString() : null));
            }
            map.put(e.getKey(), Collections.unmodifiableList(arts));
        }
        if (map.isEmpty()) {
            throw new IllegalStateException(TOKEN_MAP_RESOURCE + " has 0 entries");
        }
        logger.info("ScryfallImageMap: loaded " + map.size() + " token names");
        return Collections.unmodifiableMap(map);
    }

    // ── Accent folding ─────────────────────────────────────────────────

    /**
     * Accent-fold: NFD + strip combining marks (Adéwalé → Adewale).
     * Both maps ALSO index the folded form of every name at load time;
     * this helper exists for runtime lookups of a name that was somehow
     * missed by the builder's fold (e.g. an ad-hoc test name).
     */
    static String fold(String name) {
        String nfd = Normalizer.normalize(name, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(nfd.length());
        for (int i = 0; i < nfd.length(); i++) {
            char c = nfd.charAt(i);
            if (!Character.isHighSurrogate(c) && !Character.isLowSurrogate(c)
                    && Character.getType(c) == Character.NON_SPACING_MARK) {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    // ── Card lookups ───────────────────────────────────────────────────

    /**
     * Scryfall uuid for a card name, or null on a MISS (NO live-API fallback).
     * Tries exact hit first, then the accent-folded form.
     */
    public static String cardUuid(String cardName) {
        if (cardName == null || cardName.isEmpty()) return null;
        String uuid = cardMap.get(cardName);
        if (uuid == null) {
            uuid = cardMap.get(fold(cardName));
        }
        return uuid;
    }

    /** True when this card name is a DFC/transform whose back face carries its own image. */
    public static boolean hasBackFace(String cardName) {
        if (cardName == null) return false;
        return backs.contains(cardName) || backs.contains(fold(cardName));
    }

    /**
     * Build a CDN image URL. Size: {@code small|normal|large|png}.
     * {@code back=true} produces the back-face URL (same uuid, {@code /back/} path).
     * Returns null on a miss.
     */
    public static String cardUrl(String uuid, String size, boolean back) {
        if (uuid == null) return null;
        String face = back ? "back" : "front";
        return "https://cards.scryfall.io/" + size + "/" + face + "/"
                + uuid.charAt(0) + "/" + uuid.charAt(1) + "/" + uuid + ".jpg";
    }

    /** Convenience: card-URL for a name. Null on miss. */
    public static String cardUrlByName(String cardName, String size, boolean back) {
        return cardUrl(cardUuid(cardName), size, back);
    }

    /** Convenience: small front-face URL. */
    public static String imageUrlSmall(String cardName) {
        return cardUrl(cardUuid(cardName), "small", false);
    }

    /** Convenience: normal front-face URL. */
    public static String imageUrlNormal(String cardName) {
        return cardUrl(cardUuid(cardName), "normal", false);
    }

    // ── Token lookups ──────────────────────────────────────────────────

    /** All candidate arts for a token name (empty = unknown). */
    public static List<TokenArt> tokenArts(String tokenName) {
        if (tokenName == null) return Collections.emptyList();
        List<TokenArt> arts = tokenMap.get(tokenName);
        if (arts == null) {
            arts = tokenMap.get(fold(tokenName));
        }
        return arts != null ? arts : Collections.emptyList();
    }

    /** True when the token map has any entries for this name. */
    public static boolean isKnownToken(String tokenName) {
        return !tokenArts(tokenName).isEmpty();
    }

    /** Every token name in the map. */
    public static Set<String> knownTokenNames() {
        return tokenMap.keySet();
    }

    // ── Runtime self-check (deployment guard) ──────────────────────────

    /**
     * Force the maps to load and prove they are USABLE, loudly — for the proxy's
     * boot path. Deployment trap this exists for (2026-09-24): the web image is
     * assembled from a staging tree whose {@code target/lib/} can be a STALE
     * mage jar from an earlier build. The proxy jar then carries this class but
     * the sibling mage jar carries no map resources, so the static initializer
     * fails — and because every consumer is lazy (first card-image lookup), an
     * idle proxy looks healthy right up until the first card is rendered.
     * Calling this at boot turns "dies on first use" into "refuses to start",
     * which is the Fail-Fast contract for a broken classpath composition.
     */
    public static void verifyLoaded() {
        // Touch every map (static-init runs on first access; any missing or
        // malformed resource throws IllegalStateException from the loader).
        if (cardMap.isEmpty()) {
            throw new IllegalStateException(NAME_MAP_RESOURCE + " loaded 0 entries");
        }
        if (tokenMap.isEmpty()) {
            throw new IllegalStateException(TOKEN_MAP_RESOURCE + " loaded 0 entries");
        }
        // Spot-check resolution: presence of entries alone is not enough — a
        // truncated map can still be non-empty. "Lightning Bolt" and "Soldier"
        // are stable across map rebuilds.
        if (cardUuid("Lightning Bolt") == null) {
            throw new IllegalStateException(
                    NAME_MAP_RESOURCE + " cannot resolve 'Lightning Bolt' — map is truncated or stale");
        }
        if (tokenArts("Soldier").isEmpty()) {
            throw new IllegalStateException(
                    TOKEN_MAP_RESOURCE + " cannot resolve 'Soldier' — map is truncated or stale");
        }
        logger.info("ScryfallImageMap: self-check OK (" + cardMap.size()
                + " card names, " + tokenMap.size() + " token names)");
    }

    // ── Token art record ────────────────────────────────────────────────

    /** One candidate art for a token: a real Scryfall printing, uuid-first. */
    public static final class TokenArt {
        public final String uuid;
        public final String setCode;         // Scryfall set code (e.g. "trtr")
        public final String collectorNumber;
        public final String setName;          // for the picker UI
        public final String releasedAt;

        TokenArt(String uuid, String setCode, String collectorNumber, String setName, String releasedAt) {
            this.uuid = uuid;
            this.setCode = setCode;
            this.collectorNumber = collectorNumber;
            this.setName = setName;
            this.releasedAt = releasedAt;
        }

        /** cards.scryfall.io uuid URL at the given size. */
        public String url(String size) {
            return cardUrl(uuid, size, false);
        }
    }
}