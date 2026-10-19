package mage.webproxy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.UUID;

/**
 * Tests for {@link ScryfallImageMap} — the offline Scryfall bulk-data name/uuid
 * map that replaces the rate-limited per-card {@code named?} API.
 *
 * These run against the REAL {@code scryfall-name-map.json} and
 * {@code scryfall-token-map.json} on the mage.jar classpath (no mocking),
 * because the whole point of the maps is to encode the actual trimming rules:
 * art_series exclusion, accent folding, DFC back-face marking, and the
 * uuid-form URL generation. Any test that skips the real data would paper
 * over those exact traps.
 */
public class ScryfallImageMapTest {

    /** A uuid looks like 36 hex+dash chars. */
    private static boolean isUuid(String s) {
        return s != null && s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    // ── Card name map ─────────────────────────────────────────────────

    @Test
    public void lightningBoltResolvesToARealCardUuid() {
        String uuid = ScryfallImageMap.cardUuid("Lightning Bolt");
        assertNotNull("Lightning Bolt must resolve", uuid);
        assertTrue("Lightning Bolt must be a uuid, got: " + uuid, isUuid(uuid));
    }

    @Test
    public void artSeriesIsExcluded() {
        // A Strixhaven art-series "Lightning Bolt" entry must NOT win: layout
        // art_series entries were dropped when the map was built, and the
        // combined art-series name ("Lightning Bolt // Lightning Bolt") must
        // not resolve to anything.
        String url = ScryfallImageMap.cardUrlByName("Lightning Bolt", "small", false);
        assertNotNull(url);
        assertNull("the art-series combined name must be absent",
                ScryfallImageMap.cardUuid("Lightning Bolt // Lightning Bolt"));
    }

    @Test
    public void accentFoldedNameResolves() {
        // XMage sends "Adewale, Breaker of Chains" (no diacritics); the map
        // must also index it.
        String uuid = ScryfallImageMap.cardUuid("Adewale, Breaker of Chains");
        assertNotNull("accent-folded name must resolve", uuid);
        assertTrue(isUuid(uuid));
        // Same uuid as the accented form.
        assertEquals(uuid, ScryfallImageMap.cardUuid("Adéwalé, Breaker of Chains"));
    }

    @Test
    public void dfcFrontFaceHasBackMarker() {
        assertTrue("Delver of Secrets is a DFC",
                ScryfallImageMap.hasBackFace("Delver of Secrets"));
        String backUrl = ScryfallImageMap.cardUrlByName("Delver of Secrets", "small", true);
        assertNotNull(backUrl);
        assertTrue("back URL uses /back/", backUrl.contains("/back/"));
    }

    @Test
    public void missReturnsNullNotACallToTheApi() {
        // The Star-Wars crossover set (SWS) genuinely has no Scryfall data.
        assertNull(ScryfallImageMap.cardUuid("Anakin Skywalker"));
        assertNull(ScryfallImageMap.cardUrlByName("Anakin Skywalker", "small", false));
    }

    @Test
    public void imageUrlSmallIsUuidForm() {
        String url = ScryfallImageMap.imageUrlSmall("Lightning Bolt");
        assertNotNull(url);
        assertTrue("small URL on CDN", url.startsWith("https://cards.scryfall.io/small/front/"));
        assertTrue("uuid in path", url.contains(ScryfallImageMap.cardUuid("Lightning Bolt")));
    }

    // ── Token art map ─────────────────────────────────────────────────

    @Test
    public void tokenSoldierHasMultipleArts() {
        List<ScryfallImageMap.TokenArt> arts = ScryfallImageMap.tokenArts("Soldier");
        assertNotNull(arts);
        assertTrue("Soldier has >1 art, got " + arts.size(), arts.size() > 1);
    }

    @Test
    public void tokenArtCarriesScryfallUuid() {
        List<ScryfallImageMap.TokenArt> arts = ScryfallImageMap.tokenArts("Soldier");
        for (ScryfallImageMap.TokenArt a : arts) {
            assertTrue("every art is uuid-form", isUuid(a.uuid));
            assertNotNull("every art carries a set code", a.setCode);
        }
    }

    @Test
    public void tokenArtUrlIsCdnUuidForm() {
        List<ScryfallImageMap.TokenArt> arts = ScryfallImageMap.tokenArts("Soldier");
        String url = arts.get(0).url("small");
        assertTrue("token URL on CDN", url.startsWith("https://cards.scryfall.io/small/front/"));
        assertTrue("token URL carries uuid", url.contains(arts.get(0).uuid));
        assertFalse("token URL is NOT api.scryfall.com", url.contains("api.scryfall.com"));
    }

    @Test
    public void tokenMissReturnsEmptyList() {
        // tokens-database.txt has no such name and neither does Scryfall.
        assertTrue(ScryfallImageMap.tokenArts("Not A Real Token XYZ").isEmpty());
    }

    // ── TokenArtService integration (engine-set default) ───────────────

    @Test
    public void tokenArtServiceResolvesEngineSetDefault() {
        TokenArtService.Result r = TokenArtService.resolve("Soldier Token", "RTR");
        assertNotNull(r);
        assertTrue("resolve must fetchFailed=false, got " + r.isFetchFailed(), !r.isFetchFailed());
        // The engine set RTR is in the Ravnica token set — trtr/3 is the Soldier.
        assertNotNull("default art for RTR Soldier", r.toJson("Soldier").get("defaultUrl"));
    }

    @Test
    public void tokenArtServiceMissingTokenDoesNotThrow() {
        TokenArtService.Result r = TokenArtService.resolve("Not A Real Token XYZ", null);
        assertNotNull(r);
        assertTrue(!r.isFetchFailed());
        assertEquals(0, r.toJson("Not A Real Token XYZ").getAsJsonArray("versions").size());
    }

    @Test
    public void tokenArtServiceFetchFailedIsAlwaysFalse() {
        // After the offline map rework there are no transport failures.
        TokenArtService.Result r = TokenArtService.resolve("Spaceflight Token", null);
        assertFalse(r.isFetchFailed());
    }

    // ── GameStateSerializer wiring ─────────────────────────────────────

    @Test
    public void serializerProducesCdnUuidUrl() {
        String url = GameStateSerializer.buildImageUrl("Lightning Bolt");
        assertNotNull(url);
        assertTrue("serializer emits CDN uuid URL", url.startsWith("https://cards.scryfall.io/small/front/"));
        assertTrue("uuid in URL", url.contains(ScryfallImageMap.cardUuid("Lightning Bolt")));
    }

    @Test
    public void serializerMissReturnsNull() {
        // SWS (Star-Wars crossover) names have no Scryfall data at all.
        assertNull(GameStateSerializer.buildImageUrl("Anakin Skywalker"));
    }

    // ── Boot self-check (deployment guard) ─────────────────────────────

    @Test
    public void verifyLoadedPassesOnHealthyMaps() {
        // The real maps are on this classpath; the check must pass and must not
        // mutate anything (it is called from WebProxyServer.start()).
        ScryfallImageMap.verifyLoaded();
        assertNotNull(ScryfallImageMap.cardUuid("Lightning Bolt"));
        assertFalse(ScryfallImageMap.tokenArts("Soldier").isEmpty());
    }
}
