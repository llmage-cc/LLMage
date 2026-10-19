package mage.webproxy;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link CardsDbGuard} — the proxy's boot-time cards-DB guard.
 *
 * Runs against the REAL staged DB at {@code Mage.WebProxy/db/cards.h2}
 * (populated by {@code scripts/stage-test-db.sh}; the same DB DeckLoaderTest
 * uses). The guard's happy path must:
 *  - stamp the BUILD rows for the running jar's build time (the trap it
 *    prevents: {@code RepositoryUtil.isNewBuildRun} dropping the CARD table in
 *    the CardRepository constructor when the build time is unstamped), and
 *  - leave the DB queryable through the real ORMLite path.
 *
 * The refusing path (empty DB) is covered by a live boot probe rather than a
 * unit test: the guard reads {@code ./db/cards.h2} relative to the JVM CWD and
 * the CWD cannot be swapped inside a test JVM.
 */
public class CardsDbGuardTest {

    @Test
    public void populatedStagedDbPassesAndStaysQueryable() {
        CardsDbGuard.verifyOrStamp();
        // The ORMLite canary the guard itself checks, re-asserted here.
        assertTrue("staged DB must resolve the canary card after the guard ran",
                !mage.cards.repository.CardRepository.instance
                        .findCards("Silvercoat Lion", 1, false, false).isEmpty());
    }

    @Test
    public void guardIsIdempotent() {
        // A second boot (or a test re-run) must not change behavior or throw.
        CardsDbGuard.verifyOrStamp();
        CardsDbGuard.verifyOrStamp();
    }
}
