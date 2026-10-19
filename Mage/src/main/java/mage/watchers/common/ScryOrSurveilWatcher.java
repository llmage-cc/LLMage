package mage.watchers.common;

import mage.constants.WatcherScope;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.watchers.Watcher;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 *
 * @author muz
 */
public class ScryOrSurveilWatcher extends Watcher {
    // Pinned 2026-10-02 against silent UID drift orphaning the .game replay corpus
    // the third instance is
    // This is the value this class
    // computed when the pin was added: it FREEZES the wire UID, so later field/method/superclass
    // edits no longer change it. A deliberate format break must re-derive it explicitly.
    private static final long serialVersionUID = 6977335614126595227L;

    private final Set<UUID> playerIds = new HashSet<>();

    public ScryOrSurveilWatcher() {
        super(WatcherScope.GAME);
    }

    @Override
    public void watch(GameEvent event, Game game) {
        if (event.getType() == GameEvent.EventType.SCRIED
                || event.getType() == GameEvent.EventType.SURVEILED) {
            playerIds.add(event.getPlayerId());
        }
    }

    public boolean hasScriedOrSurveilled(UUID playerId) {
        return playerIds.contains(playerId);
    }

    @Override
    public void reset() {
        super.reset();
        playerIds.clear();
    }
}