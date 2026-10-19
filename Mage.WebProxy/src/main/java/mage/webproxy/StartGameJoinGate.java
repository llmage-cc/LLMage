package mage.webproxy;

import java.util.UUID;

/**
 * Idempotency gate for the START_GAME callback path.

 *
 * <p>The XMage server re-sends START_GAME for the same gameId — deliberately, e.g. the
 * forced-join safety net in {@code GameController.sendInfoAboutPlayersNotJoinedYetAndTryToFixIt}
 * re-emits the start event when a seat has not acknowledged its join, and a rejoin can
 * produce a duplicate as well. The web proxy's START_GAME handler used to answer EVERY
 * callback with {@code joinGame(gameId)} + {@code joinChat(gameChatId)}, so each duplicate
 * re-ran the join handshake and re-joined the game chat (live evidence: 15 reloads = 15
 * START_GAME callbacks = 15 "Joined game chat" lines for one gameId in /tmp/xmage-proxy.log).
 *
 * <p>The server side of that work is idempotent today, but "idempotent by accident" is not
 * a contract: this gate makes the proxy do the work exactly once per game session. The key
 * is the gameId, so a Bo3's next game (new gameId, new game chat) still joins normally.
 *
 * <p>State is per WebProxyClient instance, which is per browser WebSocket connection: a page
 * reload creates a fresh client and re-joins the game chat, which is exactly what the rejoin
 * path needs.
 *
 * <p>Deliberately dependency-free (java.util.UUID only) so it can be compiled and asserted
 * standalone — see {@code Mage.WebProxy/scripts/verify-lobby-proxy-units.sh}.
 */
final class StartGameJoinGate {

    private UUID joinedGameId;

    /**
     * @return true when this START_GAME is the first one for {@code gameId} (the caller must
     *         run joinGame + joinChat), false for every duplicate (skip both).
     * @throws IllegalArgumentException when the callback carried no gameId — the join cannot
     *         be keyed, and silently joining on an unkeyable event would re-open the duplicate-join hole.
     *         Fail Fast: the caller's own null check must have caught this.
     */
    synchronized boolean shouldJoin(UUID gameId) {
        if (gameId == null) {
            throw new IllegalArgumentException(
                    "START_GAME callback carried no gameId — cannot key join idempotency");
        }
        if (gameId.equals(joinedGameId)) {
            return false;
        }
        joinedGameId = gameId;
        return true;
    }
}
