package mage.webproxy;

import java.util.Map;
import mage.players.PlayerType;
import org.junit.Assert;
import org.junit.Test;

/**
 * Capability-probe tests.
 *
 * ServerCapabilities.probe() derives llm from the session's own ServerState
 * player types; these tests assert the derivation + the wire shape without a
 * live server (SessionImpl is not an interface we can stub cheaply, so the
 * probe logic is exercised through its seam: a probe over a null/stock
 * player-type source must yield llm=false, and the wire map must carry all
 * flags).
 */
public class ServerCapabilitiesTest {

    private static Map<String, Object> wireFor(boolean llm) {
        // Build through the public seam: probe() needs a SessionImpl; the
        // derivation itself (playerTypes -> flags -> wire) is what these tests
        // pin, so construct via probe over a session whose getPlayerTypes we
        // control by subclassing the fields' source — NOT possible without a
        // server. Instead assert the wire contract of an explicit stock/llmage
        // ServerCapabilities built reflectively? No: the class exposes exactly
        // one public constructor seam — probe(). Keep the live-server contract
        // on the e2e script (see verify-capability-gating) and here pin ONLY
        // the wire vocabulary through a probe against a session whose
        // getPlayerTypes is forced via a subclass. SessionImpl.getPlayerTypes
        // reads serverState (null unconnected) → NPE path → llm=false: that is
        // the "no ServerState" behavior worth pinning.
        try {
            mage.remote.SessionImpl session = new mage.remote.SessionImpl(new mage.webproxy.WebProxyClient("probe-test"));
            // NOT connected: serverState is null. getPlayerTypes() NPEs →
            // probe must catch and yield stock-mode capabilities.
            return ServerCapabilities.probe(session).toWire();
        } catch (Throwable t) {
            throw new RuntimeException("probe setup failed", t);
        }
    }

    @Test
    public void unconnectedSessionYieldsNoLlm() {
        Map<String, Object> wire = wireFor(false);
        Assert.assertEquals(Boolean.FALSE, wire.get("llm"));
        // scrubber + sidecar ride the same signal on 1.4.61
        Assert.assertEquals(Boolean.FALSE, wire.get("replayScrubber"));
        Assert.assertEquals(Boolean.FALSE, wire.get("replaySidecar"));
    }

    @Test
    public void wireShapeIsComplete() {
        Map<String, Object> wire = wireFor(false);
        Assert.assertTrue("serverVersion key missing",
                wire.containsKey("serverVersion"));
        Assert.assertTrue("playerTypes key missing",
                wire.containsKey("playerTypes"));
        Assert.assertEquals("unknown", wire.get("serverVersion"));
        Assert.assertTrue(wire.get("playerTypes") instanceof java.util.List);
    }

    @Test
    public void gateHelperRejectsNullCapabilities() {
        // requireServerLlm semantics pinned indirectly: ServerCapabilities with
        // llm=false must throw from requireLlm — build a stock-caps instance
        // via probe on an unconnected session.
        try {
            mage.remote.SessionImpl session = new mage.remote.SessionImpl(new mage.webproxy.WebProxyClient("probe"));
            ServerCapabilities caps = ServerCapabilities.probe(session);
            caps.requireLlm("test-op");
            Assert.fail("requireLlm must throw without llm capability");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("does not support LLM players"));
        }
    }

    @Test
    public void computerLlmInListEnablesLlm() {
        // The core derivation rule, exercised through the forTest seam: a server
        // advertising COMPUTER_LLM → llm=true.
        ServerCapabilities caps = ServerCapabilities.forTest(
                null,
                java.util.Arrays.asList(PlayerType.HUMAN, PlayerType.COMPUTER_LLM),
                true, true, true);
        Assert.assertTrue(caps.supportsLlm());
        Assert.assertEquals(Boolean.TRUE, caps.toWire().get("llm"));
        ServerCapabilities stock = ServerCapabilities.forTest(
                null,
                java.util.Arrays.asList(PlayerType.HUMAN, PlayerType.COMPUTER_MAD),
                false, false, false);
        Assert.assertFalse(stock.supportsLlm());
        Assert.assertEquals(Boolean.FALSE, stock.toWire().get("llm"));
    }
}