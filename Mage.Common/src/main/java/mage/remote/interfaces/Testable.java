
package mage.remote.interfaces;

import mage.cards.decks.DeckCardLists;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @author noxx
 */
public interface Testable {

    boolean isTestMode();

    boolean cheatShow(UUID gameId, UUID playerId);

    /**
     * Places cards by name into the player's zones (test mode only; the server rejects calls
     * outside test mode). Zones: hand, battlefield, library, graveyard, exile. Fails soft
     * (false) when the server does not support it.
     */
    boolean cheatSetup(UUID gameId, UUID playerId, Map<String, List<String>> cardsByZone);
}
