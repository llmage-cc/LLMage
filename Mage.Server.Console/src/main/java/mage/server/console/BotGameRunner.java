package mage.server.console;

import mage.constants.TableState;
import mage.interfaces.MageClient;
import mage.interfaces.callback.ClientCallback;
import mage.remote.Connection;
import mage.remote.Session;
import mage.remote.SessionImpl;
import mage.utils.MageVersion;
import mage.view.TableView;
import org.apache.log4j.Logger;

import java.util.Collection;
import java.util.UUID;

/**
 * Headless client that connects to an XMage server, sends a \\botgame --llm2
 * command, waits for the game to finish, then exits.
 *
 * Usage: java ... mage.server.console.BotGameRunner [--bo3] [--timeout-minutes N]
 *
 * Exit codes:
 *   0 — game completed successfully
 *   1 — connection/startup failure
 *   2 — game timed out
 */
public class BotGameRunner implements MageClient {

    private static final Logger logger = Logger.getLogger(BotGameRunner.class);
    private static final MageVersion version = new MageVersion(BotGameRunner.class);

    private final String host;
    private final int port;
    private final boolean bo3;
    private final boolean vsStockAi;
    private final boolean p1Seat;
    private final int timeoutMinutes;
    private final String deck1;
    private final String deck2;
    private Session session;
    private volatile boolean connected = false;
    private volatile String lastError = null;
    private volatile String gameResult = "unknown";

    public BotGameRunner(String host, int port, boolean bo3, boolean vsStockAi, boolean p1Seat,
                         int timeoutMinutes, String deck1, String deck2) {
        this.host = host;
        this.port = port;
        this.bo3 = bo3;
        this.vsStockAi = vsStockAi;
        this.p1Seat = p1Seat;
        this.timeoutMinutes = timeoutMinutes;
        this.deck1 = deck1;
        this.deck2 = deck2;
    }

    @Override
    public MageVersion getVersion() {
        return version;
    }

    @Override
    public void connected(String message) {
        logger.info("Connected: " + message);
        connected = true;
    }

    @Override
    public void disconnected(boolean askToReconnect, boolean keepMySessionActive) {
        logger.info("Disconnected (keepSession=" + keepMySessionActive + ")");
        connected = false;
    }

    @Override
    public void showMessage(String message) {
        logger.info("Server message: " + message);
    }

    @Override
    public void showError(String message) {
        logger.error("Server error: " + message);
        lastError = message;
    }

    @Override
    public void onNewConnection() {
        // ignore
    }

    @Override
    public void onCallback(ClientCallback callback) {
        // We don't process callbacks — we just poll table state
    }

    public int run() {
        // 1. Connect
        session = new SessionImpl(this);
        Connection conn = new Connection();
        conn.setHost(host);
        conn.setPort(port);
        conn.setUsername(SessionImpl.ADMIN_NAME);
        conn.setProxyType(Connection.ProxyType.NONE);
        // No admin password needed when authentication is off

        logger.info("Connecting to " + host + ":" + port + " as Admin...");
        if (!session.connectStart(conn)) {
            logger.error("Failed to connect to server");
            return 1;
        }

        // Wait for connected callback
        long deadline = System.currentTimeMillis() + 30_000;
        while (!connected && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(200); } catch (InterruptedException e) { break; }
        }
        if (!connected) {
            logger.error("Connection timed out waiting for callback");
            return 1;
        }

        // 2. Join main room chat
        UUID mainRoomId = session.getMainRoomId();
        UUID roomChatId = session.getRoomChatId(mainRoomId).orElse(null);
        if (roomChatId == null) {
            logger.error("Could not find room chat");
            session.connectStop(false, false);
            return 1;
        }
        if (!session.joinChat(roomChatId)) {
            logger.error("Failed to join room chat");
            session.connectStop(false, false);
            return 1;
        }
        logger.info("Joined main room chat");

        // 3. Send botgame command
        StringBuilder cmdBuilder = new StringBuilder("\\botgame");
        if (p1Seat) cmdBuilder.append(" --p1");
        else if (!vsStockAi) cmdBuilder.append(" --llm2");
        if (bo3) cmdBuilder.append(" --bo3");
        if (deck1 != null && !deck1.isEmpty()) {
            cmdBuilder.append(" ").append(deck1);
            if (deck2 != null && !deck2.isEmpty()) {
                cmdBuilder.append(" ").append(deck2);
            }
        }
        String command = cmdBuilder.toString();
        logger.info("Sending command: " + command);
        if (!session.sendChatMessage(roomChatId, command)) {
            logger.error("Failed to send botgame command");
            session.connectStop(false, false);
            return 1;
        }

        // 4. Wait for the table to appear and the game to end.
        // The table stays in DUELING state during async postgame reflection,
        // so we detect completion by watching for the games list to empty out.
        long gameDeadline = System.currentTimeMillis() + (timeoutMinutes * 60_000L);
        UUID tableId = null;
        boolean tableFound = false;
        boolean gameEnded = false;
        int emptyGamesCount = 0;  // consecutive polls with zero games

        logger.info("Waiting for bot game to start and finish (timeout: " + timeoutMinutes + " min)...");

        while (System.currentTimeMillis() < gameDeadline) {
            try {
                Collection<TableView> tables = session.getTables(mainRoomId);
                if (tables == null) {
                    try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
                    continue;
                }

                for (TableView tv : tables) {
                    if ("Bot Game".equals(tv.getTableName())) {
                        tableId = tv.getTableId();
                        tableFound = true;
                        TableState state = tv.getTableState();

                        if (state == TableState.FINISHED) {
                            gameEnded = true;
                            gameResult = tv.getControllerName() + " | " + tv.getAdditionalInfoShort();
                            logger.info("Game finished! Result: " + gameResult);
                            break;
                        }

                        // Game has ended but table is still DUELING (postgame reflection in progress)
                        if (state == TableState.DUELING && tv.getGames().isEmpty()) {
                            emptyGamesCount++;
                            if (emptyGamesCount >= 3) {  // 3 consecutive polls = 15s of no games
                                gameEnded = true;
                                gameResult = tv.getControllerName() + " | " + tv.getAdditionalInfoShort();
                                logger.info("Game ended (postgame in progress). Result: " + gameResult);
                                break;
                            }
                        } else {
                            emptyGamesCount = 0;
                        }

                        logger.info("Table state: " + state + " | games: " + tv.getGames().size()
                                + " | " + tv.getControllerName());
                    }
                }

                // If the table was found before but is now gone (health check
                // removed it after postgame), treat as game completed.
                if (!gameEnded && tableFound && tableId != null) {
                    final UUID removedTableId = tableId;
                    boolean tableStillExists = tables.stream()
                            .anyMatch(tv -> tv.getTableId().equals(removedTableId));
                    if (!tableStillExists) {
                        gameEnded = true;
                        gameResult = "Table removed by health check (postgame likely complete)";
                        logger.info("Bot game table removed by health check — treating as completed.");
                    }
                }

                if (gameEnded) break;

                try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
            } catch (Exception e) {
                logger.warn("Error polling tables: " + e.getMessage());
                try { Thread.sleep(5000); } catch (InterruptedException ex) { break; }
            }
        }

        // 5. Disconnect
        session.connectStop(false, false);

        if (!tableFound) {
            logger.error("Bot game table never appeared — timeout or server issue");
            return 2;
        }
        if (!gameEnded) {
            logger.error("Game did not finish within " + timeoutMinutes + " minutes");
            return 2;
        }

        logger.info("Bot game completed successfully: " + gameResult);
        return 0;
    }

    // ── CLI entry point ────────────────────────────────────────────────

    public static void main(String[] args) {
        String host = "localhost";
        int port = 17171;
        boolean bo3 = false;
        boolean vsStockAi = false;
        boolean p1Seat = false;
        int timeoutMinutes = 60;
        String deck1 = null;
        String deck2 = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host":
                    host = args[++i];
                    break;
                case "--port":
                    port = Integer.parseInt(args[++i]);
                    break;
                case "--bo3":
                    bo3 = true;
                    break;
                case "--vs-stock-ai":
                    vsStockAi = true;
                    break;
                case "--p1":
                    p1Seat = true;
                    break;
                case "--timeout-minutes":
                    timeoutMinutes = Integer.parseInt(args[++i]);
                    break;
                case "--deck1":
                    deck1 = args[++i];
                    break;
                case "--deck2":
                    deck2 = args[++i];
                    break;
                case "--help":
                case "-h":
                    System.out.println("BotGameRunner — headless XMage bot game launcher");
                    System.out.println("Usage: java ... BotGameRunner [options]");
                    System.out.println("  --host HOST          Server host (default: localhost)");
                    System.out.println("  --port PORT          Server port (default: 17171)");
                    System.out.println("  --bo3                Best of 3 match (default: single game)");
                    System.out.println("  --vs-stock-ai        Opponent is the stock MAD AI (omit --llm2; default: LLM vs LLM)");
                    System.out.println("  --p1                 Seat 0 is the Argentum-P1 policy net (mage-player-p1), seat 1 is COMPUTER_LLM");
                    System.out.println("  --timeout-minutes N   Max wait time (default: 60)");
                    System.out.println("  --deck1 PATH         Deck file for player 1 (optional)");
                    System.out.println("  --deck2 PATH         Deck file for player 2 (optional)");
                    System.exit(0);
                    break;
            }
        }

        BotGameRunner runner = new BotGameRunner(host, port, bo3, vsStockAi, p1Seat, timeoutMinutes, deck1, deck2);
        int exitCode = runner.run();
        System.exit(exitCode);
    }
}
