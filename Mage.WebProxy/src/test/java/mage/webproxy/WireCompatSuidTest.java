package mage.webproxy;

import java.io.ObjectStreamClass;
import mage.game.Seat;
import mage.game.match.MatchOptions;
import mage.view.GameView;
import mage.view.MatchView;
import org.junit.Assert;
import org.junit.Test;

/**
 * Official-server wire compatibility.
 *
 * The proxy talks to XMage servers over JBoss Remoting + Java serialization.
 * For classes we send to or read from a server, our computed serialVersionUID
 * must equal the server's — otherwise the call dies with InvalidClassException.
 *
 * These constants were MEASURED, not guessed. Re-derived 2026-10-20 against
 * upstream tag xmage_1.4.62V1 (commit eb69d05b88, the "version bump" commit
 * the official 1.4.62 release is cut from): a clean `git archive` export of
 * the tag was compiled with `mvn -pl Mage.Common,Mage -am -DskipTests compile`
 * and each value read back with ObjectStreamClass.lookup. The export was
 * verified stock before measuring: no `llm*` / `playerList` fields and no explicit
 * serialVersionUID in the three classes, and each file's blob hash equals the
 * tag's. All three values came back identical to the 1.4.61 pin — nothing in
 * these classes changed between xmage_1.4.61V1 and xmage_1.4.62V1 — so the
 * wire contract is unchanged at 1.4.62.
 *
 * (Original 1.4.61 measurement, history: SuidScan in this module's
 * scripts/probe-server-version/ run against the official xmage.today client
 * distribution's mage-1.4.61.jar / mage-common-1.4.61.jar, whose live server
 * reported 1.4.61-V1 build 2026-08-12 12:28 == upstream tag xmage_1.4.61V1.)
 *
 * Our tree declares serialVersionUID explicitly in all three classes, so
 * lookup() returns the declaration; these assertions also catch that
 * declaration being dropped, retyped or edited.
 *
 * The fork adds fields to these classes (llmPlayerConfig, llmPlayerConfigs,
 * llmChatPublic, MatchView.playerList). With a MATCHING suid those extra
 * fields are ignored by the stock server (per-field name matching) and its
 * fields arrive null/default on our side — harmless both ways. With a
 * MISMATCHING suid every table create / replay-browser call against an
 * official server throws. So: if you change one of these classes' fields,
 * update the pin ONLY after re-deriving both sides deliberately (upstream
 * tag + our tree), never by accident of a silent rebuild.
 */
public class WireCompatSuidTest {

    private static long suid(Class<?> c) {
        ObjectStreamClass osc = ObjectStreamClass.lookup(c);
        Assert.assertNotNull("not serializable: " + c, osc);
        return osc.getSerialVersionUID();
    }

    @Test
    public void matchViewMatchesOfficialServer() {
        // theirs (measured 2026-10-20 from upstream tag xmage_1.4.62V1)
        Assert.assertEquals(-294697495608883183L, suid(MatchView.class));
    }

    @Test
    public void seatMatchesOfficialServer() {
        Assert.assertEquals(5595719867240619273L, suid(Seat.class));
    }

    @Test
    public void matchOptionsMatchesOfficialServer() {
        Assert.assertEquals(-6545123451908010934L, suid(MatchOptions.class));
    }

    @Test
    public void gameViewKeepsUpstreamExplicitPin() {
        // upstream pins 1L explicitly on both sides; our llmDebug additions
        // ride under it (per-field name matching ignores unknown fields)
        Assert.assertEquals(1L, suid(GameView.class));
    }
}