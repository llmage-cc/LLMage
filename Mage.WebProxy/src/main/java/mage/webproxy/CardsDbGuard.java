package mage.webproxy;

import mage.cards.repository.CardRepository;
import mage.cards.repository.DatabaseUtils;
import mage.util.JarVersion;
import org.apache.log4j.Logger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Boot-time guard for the proxy's cards DB (./db/cards.h2).
 *
 * TWO failure modes this exists for, both measured live on the public stack

 *
 * 1. SELF-EMPTYING DB. {@link CardRepository}'s constructor drops the CARD table
 *    whenever the running mage jar's Build-Time is not recorded in the DB's
 *    BUILD table ({@code RepositoryUtil.isNewBuildRun} — "recreate db on new
 *    build"). The server repopulates by scanning card classes; the proxy
 *    DELIBERATELY never scans ({@code CardScanner.scanned = true} in
 *    WebProxyServer.start()), so for the proxy the drop is permanent — the
 *    first card_search / deck-editor action after any jar rebuild silently
 *    emptied the DB (measured: 147 MB / 92,684 cards → 0 cards on the first
 *    card_search after the 2026-09-24 web deploy). Fix: stamp the BUILD rows
 *    for 'card' and 'expansion' with the running jar's build time BEFORE any
 *    ORMLite access — the same technique
 *    {@code Mage.WebProxy/scripts/stage-test-db.sh} already uses for tests —
 *    so the drop never triggers for this process.
 *
 * 2. EMPTY/INCOMPLETE DB. A staged DB that is empty (or missing a table)
 *    would serve a deck editor that errors on every card. Refuse to start
 *    instead: the deployment contract is "stage a populated cards DB".
 *
 * Called from {@link WebProxyServer#start()} before any socket is bound.
 */
final class CardsDbGuard {

    private static final Logger logger = Logger.getLogger(CardsDbGuard.class);

    /** Same canary the repository's own health check uses (CardRepository.safeFindKnownCard). */
    private static final String CANARY_CARD = "Silvercoat Lion";

    private CardsDbGuard() {
    }

    static void verifyOrStamp() {
        String buildTime = JarVersion.getBuildTime(CardRepository.class);
        boolean stampable = JarVersion.isBuildTimeOk(buildTime);

        long cards;
        long expansions;
        try (Connection connection = DriverManager.getConnection(
                DatabaseUtils.prepareH2Connection(DatabaseUtils.DB_NAME_CARDS, false))) {
            try (Statement st = connection.createStatement()) {
                // ORMLite would create this on its own; do it here so the stamp
                // below works even on a first-run DB.
                st.execute("CREATE TABLE IF NOT EXISTS build (entity VARCHAR(255), last_build VARCHAR(255))");
                if (stampable) {
                    stampIfMissing(st, "card", buildTime);
                    stampIfMissing(st, "expansion", buildTime);
                }
            }
            cards = countRows(connection, "card");
            expansions = countRows(connection, "expansion");
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Cannot open the cards DB (" + DatabaseUtils.prepareH2Connection(DatabaseUtils.DB_NAME_CARDS, false)
                            + "): the web proxy needs a populated staged cards DB — see "
                            + "", e);
        }

        if (cards <= 0 || expansions <= 0) {
            throw new IllegalStateException("Cards DB is empty or incomplete: " + cards + " cards, "
                    + expansions + " expansions. Stage a populated copy (cp <repo>/db/cards.h2.mv.db "
                    + "web-release/db/) before starting the proxy — see "
                    + "");
        }

        // Prove the real ORMLite path opens — and that the BUILD stamp above
        // actually prevented the constructor's drop — before binding sockets.
        try {
            if (CardRepository.instance.findCards(CANARY_CARD, 1, false, false).isEmpty()) {
                throw new IllegalStateException("Cards DB opened but cannot resolve '" + CANARY_CARD
                        + "' (" + cards + " cards, " + expansions + " expansions counted) — the DB is "
                        + "unusable; re-stage it before starting the proxy");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Cards DB failed its open probe (repository could not be "
                    + "queried for '" + CANARY_CARD + "'): " + t, t);
        }

        logger.info("CardsDbGuard: cards DB OK (" + cards + " cards, " + expansions
                + " expansions, jar build '" + buildTime + "')");
    }

    private static void stampIfMissing(Statement st, String entity, String buildTime) throws SQLException {
        try (PreparedStatement ps = st.getConnection().prepareStatement(
                "SELECT COUNT(*) FROM build WHERE entity = ? AND last_build = ?")) {
            ps.setString(1, entity);
            ps.setString(2, buildTime);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getLong(1) > 0) {
                    return;
                }
            }
        }
        try (PreparedStatement ps = st.getConnection().prepareStatement(
                "INSERT INTO build (entity, last_build) VALUES (?, ?)")) {
            ps.setString(1, entity);
            ps.setString(2, buildTime);
            ps.executeUpdate();
        }
        logger.info("CardsDbGuard: stamped BUILD row " + entity + " = '" + buildTime
                + "' (without it the repository constructor would drop the table on this build)");
    }

    /** Row count, or 0 when the table does not exist yet. */
    private static long countRows(Connection connection, String table) {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            return 0;
        }
    }
}
