import mage.interfaces.MageClient;
import mage.interfaces.callback.ClientCallback;
import mage.remote.Connection;
import mage.remote.SessionImpl;
import mage.utils.MageVersion;

/**
 * One-shot probe: connect to an XMage server, read the server's MageVersion,
 * print it, disconnect. No game, no tables, no chat messages.
 * Modeled on BotGameRunner (Mage.Server.Console).
 */
public final class ServerProbe implements MageClient {

    private final String host;
    private final int port;
    private volatile boolean connected = false;
    private SessionImpl session;

    private ServerProbe(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public void connected(String message) {
        connected = true;
        System.out.println("CONNECTED: " + message);
    }

    @Override
    public void disconnected(boolean askToReconnect, boolean keepMySessionActive) {
        System.out.println("DISCONNECTED: askReconnect=" + askToReconnect);
    }

    @Override
    public void showMessage(String message) {
        System.out.println("MESSAGE: " + message);
    }

    @Override
    public void showError(String message) {
        System.out.println("ERROR: " + message);
    }

    @Override
    public void onNewConnection() { /* ignore */ }

    @Override
    public void onCallback(ClientCallback callback) {
        // DUMP everything — version rejections and other connect-time server
        // messages arrive as callbacks (dialogs/messages), not showError.
        try {
            // server callbacks can arrive compressed (ZippedObject) — decompress
            // before reading (CallbackClientImpl does the same)
            callback.decompressData();
            Object data = callback.getData();
            String dataStr;
            if (data instanceof java.util.List) {
                StringBuilder sb = new StringBuilder("[");
                for (Object o : (java.util.List<?>) data) sb.append(o).append(", ");
                dataStr = sb.append("]").toString();
            } else {
                dataStr = String.valueOf(data);
            }
            System.out.println("CALLBACK: " + callback.getClass().getName()
                    + " info=" + callback.getInfo()
                    + " method=" + callback.getMethod()
                    + " data=" + dataStr);
        } catch (Throwable t) {
            System.out.println("CALLBACK: <unprintable> " + t);
        }
    }

    @Override
    public MageVersion getVersion() {
        // Built against OUR Mage.Common: reports 1.4.61-V1. The server's own
        // strict check compares its MageVersion to the one WE send.
        return new MageVersion(ServerProbe.class);
    }

    private int run() {
        session = new SessionImpl(this);
        Connection conn = new Connection();
        conn.setHost(host);
        conn.setPort(port);
        conn.setUsername("xmwebprobe");
        conn.setProxyType(Connection.ProxyType.NONE);
        System.out.println("Connecting to " + host + ":" + port + " ...");
        if (!session.connectStart(conn)) {
            // server sends the human-readable rejection as a SHOW_USERMESSAGE
            // callback — give it a few seconds to arrive before exiting
            try { Thread.sleep(8_000); } catch (InterruptedException ignore) { }
            System.out.println("RESULT: connectStart FAILED, lastError=" + session.getLastError());
            return 1;
        }
        long deadline = System.currentTimeMillis() + 45_000;
        while (!connected && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(200); } catch (InterruptedException e) { break; }
        }
        if (!connected) {
            System.out.println("RESULT: TIMEOUT waiting for connected callback, lastError=" + session.getLastError());
            try { session.connectStop(false, false); } catch (Exception ignore) { }
            return 2;
        }
        try {
            String versionInfo = session.getVersionInfo();
            System.out.println("SERVER_VERSION: " + versionInfo);
            System.out.println("RESULT: OK");
            return 0;
        } catch (Throwable t) {
            System.out.println("RESULT: EXCEPTION " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.out);
            return 4;
        } finally {
            try { session.connectStop(false, false); } catch (Exception ignore) { }
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("usage: ServerProbe <host> <port>");
            System.exit(64);
        }
        ServerProbe probe = new ServerProbe(args[0], Integer.parseInt(args[1]));
        int code = probe.run();
        System.out.println("EXIT=" + code);
        System.exit(code);
    }
}