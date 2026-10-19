package mage.webproxy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import mage.players.PlayerType;
import mage.remote.SessionImpl;
import mage.utils.MageVersion;

/**
 * Capabilities of the connected XMage server, probed right after connectStart

 *
 * The web UI supports features that only exist on LLMage servers (LLM seats +
 * per-seat LLM config, browser-key LLM bridge, replay scrubber seekTo, replay
 * sidecar metadata, in-game game-log capture). When the proxy connects to an
 * OFFICIAL XMage server (xmage.today etc.) those features must fully deactivate:
 * the UI renders without them and the proxy never sends their wire messages —
 * a stock server would reject or misbehave on them.
 *
 * The probe is honest by construction: everything derives from what the server
 * itself reports (its PlayerType[] via ServerState, its MageVersion), never
 * from what we assume about the target address.
 *
 * All flags are Fail Fast inputs: any attempt to USE a missing capability must
 * be rejected loudly by the caller (no silent downgrade), while the UI simply
 * hides the unavailable controls.
 */
public final class ServerCapabilities {

    /** Server version reported by ServerState (may be null on probe failure). */
    private final MageVersion serverVersion;

    /**
     * Player types the server exposes (ServerState.playerTypes). An official
     * server never lists COMPUTER_LLM; an LLMage server does.
     */
    private final List<PlayerType> playerTypes;

    /** LLM capability: the server can run COMPUTER_LLM seats. */
    private final boolean llm;
    /** P1 capability: the server can run COMPUTER_P1 (Argentum policy-net) seats. */
    private final boolean p1;
    /**
     * Replay scrubber capability: the server supports Replays.seekTo.
     */
    private final boolean replayScrubber;
    /**
     * Replay sidecar metadata capability: the server writes saved/&lt;id&gt.json
     * sidecars (LLMage addition) and serves per-file fileMeta on get_replay_files.
     */
    private final boolean replaySidecar;

    private ServerCapabilities(MageVersion serverVersion, List<PlayerType> playerTypes,
                               boolean llm, boolean p1, boolean replayScrubber, boolean replaySidecar) {
        this.serverVersion = serverVersion;
        this.playerTypes = new ArrayList<>(playerTypes != null ? playerTypes : new ArrayList<PlayerType>());
        this.llm = llm;
        this.p1 = p1;
        this.replayScrubber = replayScrubber;
        this.replaySidecar = replaySidecar;
    }

    /** Test seam: build explicit capabilities without a live session. */
    static ServerCapabilities forTest(MageVersion version, List<PlayerType> types,
                                      boolean llm, boolean scrubber, boolean sidecar) {
        boolean p1 = types != null && types.contains(PlayerType.COMPUTER_P1);
        return new ServerCapabilities(version, types, llm, p1, scrubber, sidecar);
    }

    /**
     * Probe a connected session. Must be called AFTER connectStart returned true
     * (ServerState is fetched during the connect handshake — SessionImpl.connectStart
     * stores it before returning; the desktop client has the same ordering). A short
     * bounded wait covers the async delivery window without ever hanging the connect.
     */
    public static ServerCapabilities probe(SessionImpl session) {
        List<PlayerType> types = null;
        try {
            PlayerType[] t = session.getPlayerTypes();
            types = t != null ? Arrays.asList(t) : null;
        } catch (Throwable e) {
            // Fail toward "no capabilities" — the UI degrades to stock XMage.
            types = null;
        }
        boolean llm = types != null && types.contains(PlayerType.COMPUTER_LLM);
        boolean p1 = types != null && types.contains(PlayerType.COMPUTER_P1);
        // Measured on the official 1.4.61-V1 client (2026-09-24): upstream's
        // Replays interface has NO seekTo — the scrubber RPC is an LLMage-only
        // addition for this release, and the version string is identical
        // (1.4.61-V1) on both server kinds, so the version CANNOT disambiguate.
        // Until an upstream release carries seekTo (re-derive then: check the
        // new tag's mage.remote.interfaces.Replays and split the gate), the
        // scrubber and the sidecar metadata ride the same LLMage signal as llm.
        boolean scrubber = llm;
        boolean sidecar = llm;
        return new ServerCapabilities(sessionVersion(session), types, llm, p1, scrubber, sidecar);
    }

    /**
     * The server's MageVersion, if reachable. Reads through the same
     * ServerState the handshake populated; versionInfo text is parsed
     * informationally only — a parse failure never fails the connect.
     */
    private static MageVersion sessionVersion(SessionImpl session) {
        try {
            String info = session.getVersionInfo();
            if (info == null) return null;
            String s = info.trim();
            int space = s.indexOf(' ');
            if (space >= 0) s = s.substring(0, space);
            int dash = s.indexOf('-');
            String numeric = dash >= 0 ? s.substring(0, dash) : s;
            String[] parts = numeric.split("\\.");
            if (parts.length < 3) return null;
            return new MageVersion(Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    dash >= 0 ? s.substring(dash + 1) : "V1",
                    ServerCapabilities.class);
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when the server can run LLM seats. Gate for every LLM UI + RPC. */
    public boolean supportsLlm() {
        return llm;
    }

    /** True when the server can run COMPUTER_P1 (Argentum policy-net) seats. */
    public boolean supportsP1() {
        return p1;
    }

    public boolean supportsReplayScrubber() {
        return replayScrubber;
    }

    public boolean supportsReplaySidecar() {
        return replaySidecar;
    }

    public String serverVersionString() {
        return serverVersion != null ? serverVersion.toString(false) : "unknown";
    }

    /** The player types the server itself advertises (proxy may surface them to the UI). */
    public List<String> wirePlayerTypes() {
        List<String> out = new ArrayList<>();
        for (PlayerType pt : playerTypes) out.add(pt.name());
        return out;
    }

    /** Wire shape for the frontend "connected" message: {"capabilities": {...}}. */
    Map<String, Object> toWire() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("llm", llm);
        m.put("p1", p1);
        m.put("replayScrubber", replayScrubber);
        m.put("replaySidecar", replaySidecar);
        m.put("serverVersion", serverVersionString());
        m.put("playerTypes", wirePlayerTypes());
        return m;
    }

    /**
     * Gate check for LLM-carrying requests. Throws with a clear message when the
     * connected server cannot run them (Fail Fast — never silently drop).
     */
    void requireLlm(String what) {
        if (!llm) {
            throw new IllegalStateException(
                    "Server " + serverVersionString() + " does not support LLM players — cannot " + what);
        }
    }
}