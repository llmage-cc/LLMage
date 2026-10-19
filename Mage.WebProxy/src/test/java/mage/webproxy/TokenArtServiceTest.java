package mage.webproxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link TokenArtService} — the token art resolver behind the web client's
 * printing picker and its default-art choice.
 *
 * These run against the REAL {@code tokens-database.txt} shipped in mage.jar (no
 * mocking), because the whole point of the service is to read that file correctly: the
 * column layout, the bare-vs-suffixed naming, and — critically — the fact that its
 * image index is NOT a Scryfall collector number are exactly what a mock would paper
 * over.
 *
 * Tests that need Scryfall assert SHAPE only (a well-formed URL, a failure reported
 * rather than swallowed); none assert a specific remote response, so the suite passes
 * offline.
 */
public class TokenArtServiceTest {

    /** A token with the common " Token" suffix and many printings. */
    private static final String SOLDIER = "Soldier Token";

    /** A token whose name has NO " Token" suffix — the case the old name test missed. */
    private static final String TREASURE = "Treasure";

    /**
     * The engine must know the tokens it can render. If tokens-database.txt is not on
     * the classpath, the service is supposed to fail loudly rather than silently
     * render every token as a card back.
     *
     * Note the name shapes: the database keys BARE names ("Soldier") while runtime
     * token names carry a " Token" suffix ("Soldier Token"). Both must resolve — the
     * 96 suffix-free runtime names match verbatim, and 289 more match only after the
     * suffix is stripped.
     */
    @Test
    public void tokensDatabaseIsOnTheClasspath() {
        Set<String> names = TokenArtService.knownTokenNames();
        assertFalse("tokens-database.txt must yield token names", names.isEmpty());
        assertTrue("expected a large token list, got " + names.size(), names.size() > 200);
        // The database stores the bare form.
        assertTrue("Soldier should be known", names.contains("Soldier"));
        assertTrue("Treasure (no ' Token' suffix) should be known", names.contains(TREASURE));
    }

    /**
     * THE regression this work started with: the engine's runtime name carries a
     * " Token" suffix, the database does not, so a raw-name lookup found nothing and
     * every common token silently lost all its candidate art.
     */
    @Test
    public void runtimeNamesWithTokenSuffixStillResolve() {
        assertTrue("'Soldier Token' must resolve to the bare 'Soldier' data",
                TokenArtService.isKnownToken(SOLDIER));
        assertFalse("Soldier must have engine sets",
                TokenArtService.engineSets(SOLDIER).isEmpty());
        // The same token resolves identically via either spelling.
        assertEquals(TokenArtService.engineSets("Soldier"),
                TokenArtService.engineSets(SOLDIER));
    }

    /** Suffix-free runtime names (the 116 the old name test missed) must resolve too. */
    @Test
    public void suffixFreeNamesResolve() {
        for (String name : new String[] { "Treasure", "Food", "Clue", "Ajani's Pridemate" }) {
            assertTrue("'" + name + "' must be a known token", TokenArtService.isKnownToken(name));
            assertFalse("'" + name + "' must have engine sets",
                    TokenArtService.engineSets(name).isEmpty());
        }
    }

    /** normalizeName strips the suffix case-insensitively and rejects empties. */
    @Test
    public void normalizeNameStripsSuffix() {
        assertEquals("Soldier", TokenArtService.normalizeName("Soldier Token"));
        assertEquals("Soldier", TokenArtService.normalizeName("Soldier token"));
        assertEquals("Soldier", TokenArtService.normalizeName("Soldier"));
        assertEquals("Ajani's Pridemate", TokenArtService.normalizeName("Ajani's Pridemate"));
        assertNull(TokenArtService.normalizeName(""));
        assertNull(TokenArtService.normalizeName(" Token"));
        assertNull(TokenArtService.normalizeName(null));
    }

    /**
     * The engine's set code is what makes "a Soldier from a Ravnica card shows a
     * Ravnica Soldier" possible, so its Scryfall token-set form must be exact:
     * {@code RTR} → {@code trtr}, {@code 10E} → {@code t10e}.
     *
     * Codes with a LEADING T are the trap (THS, TSR, THB, TMP are all real XMage
     * codes): "starts with t" is not the same as "already prefixed", and getting it
     * wrong yields a set that does not exist (`ths`), so the token silently falls
     * back to the wrong art with no error.
     *
     * This is the RELIABLE half of the engine's printing pair. The other half (the
     * image index) is deliberately absent from this service.
     */
    @Test
    public void engineSetCodesBecomeScryfallTokenSetCodes() {
        assertEquals("trtr", TokenArtService.tokenSetCode("RTR"));
        assertEquals("t10e", TokenArtService.tokenSetCode("10E"));
        // Leading-T engine codes must still be prefixed.
        assertEquals("tths", TokenArtService.tokenSetCode("THS"));
        assertEquals("ttsr", TokenArtService.tokenSetCode("TSR"));
        assertEquals("tthb", TokenArtService.tokenSetCode("THB"));
        assertEquals("tsld", TokenArtService.tokenSetCode("SLD"));
        assertEquals("tgrn", TokenArtService.tokenSetCode("GRN"));
        // Case-insensitive.
        assertEquals("trtr", TokenArtService.tokenSetCode("rtr"));
        assertNull(TokenArtService.tokenSetCode(null));
        assertNull(TokenArtService.tokenSetCode(""));
    }

    /**
     * THE defect that made the picker meaningless: the engine's image index is not a
     * Scryfall collector number, and the service must never synthesize a printing URL
     * from it. With the offline-map rework, token URLs are uuid-form CDN URLs
     * ({@code cards.scryfall.io/<size>/front/<h1>/<h2>/<uuid>.jpg}) built from the
     * scryfall uuid stored in the map — the {@code api.scryfall.com/cards/t<set>/<n>}
     * form is gone entirely.
     */
    @Test
    public void tokenUrlsAreUuidCdnFormAndNeverEngineSetPaths() {
        TokenArtService.Result r = TokenArtService.resolve(SOLDIER, "RTR");
        JsonArray versions = r.toJson(SOLDIER).getAsJsonArray("versions");
        assertTrue("Soldier must have candidates", versions.size() > 0);
        for (int i = 0; i < versions.size(); i++) {
            String url = versions.get(i).getAsJsonObject().get("url").getAsString();
            assertTrue("candidate is a uuid CDN url: " + url,
                    url.startsWith("https://cards.scryfall.io/small/front/"));
            assertFalse("no api.scryfall.com: " + url, url.contains("api.scryfall.com"));
            assertFalse("no set/number path: " + url,
                    url.matches(".*cards\\.scryfall\\.io/cards/.*"));
        }
    }

    /**
     * An unknown name yields no candidates and must NOT fabricate a URL — the client
     * relies on an empty list to fall through to the card-back placeholder.
     */
    @Test
    public void unknownTokenHasNoCandidates() {
        assertTrue(TokenArtService.engineSets("Definitely Not A Real Token Name").isEmpty());
        assertFalse(TokenArtService.isKnownToken("Definitely Not A Real Token Name"));
    }

    /**
     * An unknown token must resolve to an empty list rather than inventing a URL, and
     * must not throw. (Whether Scryfall is reachable is not asserted — only that the
     * failure mode is a clean empty result.)
     */
    @Test
    public void resolveUnknownTokenIsCleanlyEmpty() {
        TokenArtService.Result r = TokenArtService.resolve("Definitely Not A Real Token Name", "RTR");
        JsonObject json = r.toJson("Definitely Not A Real Token Name");
        assertEquals("an unknown token must resolve to zero candidates", 0,
                json.getAsJsonArray("versions").size());
        // No default either: with nothing to choose from there is no art to point at.
        assertFalse(json.has("defaultUrl"));
    }

    /** An empty or null name is rejected without touching anything. */
    @Test
    public void emptyNameResolvesToNothing() {
        assertEquals(0, TokenArtService.resolve("", null)
                .toJson("").getAsJsonArray("versions").size());
        assertEquals(0, TokenArtService.resolve(null, null)
                .toJson("").getAsJsonArray("versions").size());
    }

    /**
     * The result payload must carry everything the picker renders: per-printing set
     * name and code (so "Ravnica vs Innistrad" is visible), and a default URL that is
     * one of the listed candidates.
     *
     * Reaching Scryfall is not required — a transport failure must still produce a
     * well-formed payload with {@code fetchFailed} set, never a half-built object or
     * a default that is not in the list.
     */
    @Test
    public void resultPayloadIsWellFormedAndDefaultIsAmongTheCandidates() {
        TokenArtService.Result r = TokenArtService.resolve(SOLDIER, "RTR");
        JsonObject json = r.toJson(SOLDIER);
        assertEquals(SOLDIER, json.get("tokenName").getAsString());
        assertNotNull(json.getAsJsonArray("versions"));
        assertTrue(json.has("fetchFailed"));

        JsonArray versions = json.getAsJsonArray("versions");
        if (versions.size() > 0) {
            // The default must be a URL the client was actually offered.
            assertTrue("defaultUrl must be one of the candidates",
                    json.has("defaultUrl"));
            String defaultUrl = json.get("defaultUrl").getAsString();
            boolean found = false;
            for (int i = 0; i < versions.size(); i++) {
                JsonObject v = versions.get(i).getAsJsonObject();
                assertTrue("every candidate needs a url", v.has("url"));
                assertTrue("a token candidate is a Scryfall printing URL: " + v.get("url").getAsString(),
                        v.get("url").getAsString().startsWith("https://"));
                if (defaultUrl.equals(v.get("url").getAsString())) found = true;
            }
            assertTrue("defaultUrl " + defaultUrl + " was not in the candidate list", found);
        }
    }

    /**
     * The relay stores per-game per-player choices and hands back a full snapshot, and
     * clearing or forgetting a player removes exactly that player's entries.
     */
    @Test
    public void relayRecordsAndForgetsPerGamePerPlayer() {
        java.util.UUID game = java.util.UUID.randomUUID();
        java.util.UUID p1 = java.util.UUID.randomUUID();
        java.util.UUID p2 = java.util.UUID.randomUUID();

        List<java.util.Map<String, Object>> afterFirst = TokenArtRelay.record(
                game, p1, "Alice", TREASURE,
                "https://cards.scryfall.io/small/front/12345678",
                "thob", "13");
        assertEquals("one player, one token -> one entry", 1, afterFirst.size());
        assertEquals("Alice", afterFirst.get(0).get("playerName"));

        List<java.util.Map<String, Object>> afterSecond = TokenArtRelay.record(
                game, p2, "Bob", SOLDIER,
                "https://cards.scryfall.io/small/front/23456789",
                "tmsh", "4");
        assertEquals("both players' choices are relayed", 2, afterSecond.size());

        // A second player's choice for the SAME token is a separate entry (each player
        // may prefer different art), not an overwrite.
        List<java.util.Map<String, Object>> sameToken = TokenArtRelay.record(
                game, p2, "Bob", TREASURE,
                "https://cards.scryfall.io/small/front/34567890",
                "tsnc", "1");
        assertEquals("per-player choices for one token coexist", 3, sameToken.size());

        // Clearing one removes only that one.
        List<java.util.Map<String, Object>> afterClear = TokenArtRelay.clear(game, p1, TREASURE);
        assertEquals(2, afterClear.size());

        // Forgetting a player drops all of their choices.
        TokenArtRelay.forgetPlayer(game, p2);
        assertEquals("Bob had two choices, both must go", 0, TokenArtRelay.snapshot(game).size());
    }

    /**
     * The engine's own set must win. A Ravnica Soldier must default to the
     * Ravnica printing (trtr), not the newest art from another set.
     */
    @Test
    public void engineSetWins() {
        TokenArtService.Result r = TokenArtService.resolve("Soldier Token", "RTR");
        JsonObject json = r.toJson("Soldier Token");
        JsonArray versions = json.getAsJsonArray("versions");
        assertTrue("Soldier must have candidates", versions.size() > 0);
        assertTrue("defaultUrl must be present", json.has("defaultUrl"));
        String defaultUrl = json.get("defaultUrl").getAsString();

        JsonObject defEntry = null;
        for (int i = 0; i < versions.size(); i++) {
            JsonObject v = versions.get(i).getAsJsonObject();
            if (defaultUrl.equals(v.get("url").getAsString())) defEntry = v;
        }
        assertNotNull("the default must appear in the candidate list", defEntry);
        assertEquals("the default must be the engine's own set", "trtr",
                defEntry.get("setCode").getAsString());
        // Ravnica's Soldier token is collector number 3 in that set.
        assertEquals("and the engine's own printing", "3",
                defEntry.get("collectorNumber").getAsString());
    }

    /**
     * A set with no printing of the token falls back to the newest art rather than
     * inventing one — the engine records the SOURCE CARD's set, and plenty of sets print
     * no tokens at all.
     */
    @Test
    public void unknownEngineSetFallsBackToTheNewestArt() {
        TokenArtService.Result r = TokenArtService.resolve("Saproling Token", "ZZZ");
        JsonObject json = r.toJson("Saproling Token");
        JsonArray versions = json.getAsJsonArray("versions");
        assertTrue("Saproling must have candidates", versions.size() > 0);
        // The fallback is the list's own first entry (newest), which must not be a ZZZ set.
        String defaultUrl = json.get("defaultUrl").getAsString();
        JsonObject def = null;
        for (int i = 0; i < versions.size(); i++) {
            JsonObject v = versions.get(i).getAsJsonObject();
            if (defaultUrl.equals(v.get("url").getAsString())) def = v;
        }
        assertNotNull(def);
        assertFalse("a nonexistent set must never be the default",
                "tzzz".equals(def.get("setCode").getAsString()));
    }

    /**
     * The broadcast-host allowlist is what stops one player styling another player's
     * board with an arbitrary URL. Anything off the Scryfall image hosts must be
     * rejected — including the obvious attack shapes.
     */
    @Test
    public void broadcastUrlAllowlistRejectsNonScryfallHosts() {
        assertTrue(WebProxySession.isAllowedCardImageUrl(
                "https://api.scryfall.com/cards/t10e/1?format=image&version=small"));
        assertTrue(WebProxySession.isAllowedCardImageUrl(
                "https://cards.scryfall.io/small/front/c/8/c8ae31d1.jpg"));
        assertTrue(WebProxySession.isAllowedCardImageUrl(
                "https://img.scryfall.io/whatever.png"));

        // Off-allowlist, wrong scheme, and injection-shaped values.
        assertFalse(WebProxySession.isAllowedCardImageUrl("http://api.scryfall.com/cards/t10e/1"));
        assertFalse(WebProxySession.isAllowedCardImageUrl("https://evil.example.com/x.png"));
        assertFalse(WebProxySession.isAllowedCardImageUrl("javascript:alert(1)"));
        assertFalse(WebProxySession.isAllowedCardImageUrl("data:image/png;base64,AAAA"));
        // A host that merely CONTAINS an allowed name must not pass.
        assertFalse(WebProxySession.isAllowedCardImageUrl("https://api.scryfall.com.evil.example/x"));
        assertFalse(WebProxySession.isAllowedCardImageUrl(""));
        assertFalse(WebProxySession.isAllowedCardImageUrl(null));
    }
}
