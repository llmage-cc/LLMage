package mage.webproxy;

import mage.interfaces.MageClient;
import mage.interfaces.callback.ClientCallback;
import mage.interfaces.callback.ClientCallbackMethod;
import mage.utils.MageVersion;

import java.util.List;

/**
 * MageClient for the ONE-SHOT registration dial.
 *
 * <p>Registration is not part of the connect handshake: the desktop client's
 * {@code RegisterUserDialog} runs {@code SessionHandler.register(connection)} →
 * {@code SessionImpl.sendAuthRegister}, which opens a dedicated remoting
 * connection, calls the server's {@code authRegister} RPC and then tears the
 * connection down again ({@code doRemoteWorkAndHandleErrors(closeConnectionOnFinish=true)}).
 * The XMage server answers with a BOOLEAN (registered / not) and delivers the
 * human-readable outcome asynchronously as a {@code SHOW_USERMESSAGE} callback
 * ({@code Session.sendErrorMessageToClient} / {@code sendInfoMessageToClient}) —
 * that callback text is the ONLY place the reason ("User name 'x' already in
 * use", "Registration is disabled on this server.", or in the server's test mode
 * the generated initial password) appears.
 *
 * <p>WHY A DEDICATED CLIENT instead of reusing {@link WebProxyClient}:
 * <ul>
 *   <li>{@code SessionImpl.connectStop} (which {@code sendAuthRegister} always
 *       runs) calls {@code client.disconnected(false, false)} — on the shared
 *       client that pushes a spurious "Connection to XMage server lost" frame to
 *       a browser that never had a connection (the register exchange happens on
 *       the connect screen, before any login).</li>
 *   <li>The shared client buffers per-user {@code GameHistory} on construction;
 *       a throwaway register session has no game history to keep.</li>
 * </ul>
 *
 * <p>Thread safety: callbacks arrive on JBoss remoting listener threads while the
 * proxy's WS message thread polls for the text — the last message is held in a
 * volatile field. Nothing is forwarded to the browser here: the handler sends
 * exactly one {@code register_success} / {@code register_error} frame after the
 * RPC returns, carrying this client's captured text.
 */
final class RegisterAuthClient implements MageClient {

    private static final org.apache.log4j.Logger logger =
            org.apache.log4j.Logger.getLogger(RegisterAuthClient.class);

    /** Latest server text (SHOW_USERMESSAGE / showError / showMessage), "" when none arrived. */
    private volatile String lastMessage = "";

    /** Text of the last message, or "" when none has arrived yet. */
    String lastMessage() {
        return lastMessage;
    }

    /**
     * Wait up to {@code timeoutMillis} for the server's outcome text and return it.
     *
     * <p>WHY A WAIT IS NEEDED: the register RPC returns the boolean synchronously,
     * but the text rides a separate callback. {@code SessionImpl.CallbackHandler}
     * holds every callback until the client has seen a server state — for a register
     * session that never logs in, {@code serverState} stays null, so each callback is
     * delayed by {@code CONNECT_WAIT_BEFORE_PROCESS_ANY_CALLBACKS_SECS} (3 s) before
     * it reaches this client. Returns "" on timeout: the boolean result is the
     * authoritative outcome, the text is the reason when it arrived.
     */
    String awaitMessage(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            String current = lastMessage;
            if (!current.isEmpty()) {
                return current;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return lastMessage;
    }

    private void record(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        lastMessage = text;
        logger.info("Register callback: " + text.replaceAll("[\\r\\n\\t]", " "));
    }

    // ── MageClient ──

    @Override
    public MageVersion getVersion() {
        // Same version source as the proxy's own browser client
        // (WebProxyClient.getVersion): the server enforces version
        // compatibility on the register dial exactly like a login.
        return new MageVersion(WebProxyClient.class);
    }

    @Override
    public void connected(String message) {
        // A register dial never logs in (no connectUser call) — not reachable.
    }

    @Override
    public void disconnected(boolean askToReconnect, boolean keepMySessionActive) {
        // SessionImpl.connectStop at the end of every register task. No-op on
        // purpose: nothing of this session is preserved and the browser must not
        // see a "connection lost" frame for a connection it never established.
        logger.debug("Register dial closed (askToReconnect=" + askToReconnect + ")");
    }

    @Override
    public void showMessage(String message) {
        record(message);
    }

    @Override
    public void showError(String message) {
        record(message);
    }

    @Override
    public void onNewConnection() {
        logger.info("Register dial: new connection to XMage server");
    }

    @Override
    public void onCallback(ClientCallback callback) {
        ClientCallbackMethod method = callback.getMethod();
        if (method == ClientCallbackMethod.SHOW_USERMESSAGE || method == ClientCallbackMethod.SERVER_MESSAGE) {
            try {
                callback.decompressData();
            } catch (Throwable t) {
                logger.warn("Register callback: failed to decompress " + method, t);
            }
            Object data = callback.getData();
            if (data instanceof List<?> && ((List<?>) data).size() == 2) {
                // sendErrorMessageToClient / sendInfoMessageToClient send
                // [title, text] — the desktop shows get(1); do the same.
                Object text = ((List<?>) data).get(1);
                record(text != null ? text.toString() : null);
            } else if (data != null) {
                record(data.toString());
            }
        }
    }
}
