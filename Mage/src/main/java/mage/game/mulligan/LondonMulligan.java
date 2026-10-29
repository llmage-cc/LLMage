package mage.game.mulligan;

import mage.cards.CardsImpl;
import mage.constants.Outcome;
import mage.filter.FilterCard;
import mage.game.Game;
import mage.players.Player;
import mage.target.Target;
import mage.target.common.TargetCardInHand;
import org.apache.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class LondonMulligan extends Mulligan {

    /**
     * Pinned to the UID this class computed before the 2026-10-27 logging change.
     * Adding a static initializer (the Logger below) changes the JVM-computed
     * default UID, which would orphan every saved game that carries this mulligan
     * implementation. The SERIALIZED shape (instance fields) is unchanged, so the
     * historical value stays correct — pin it instead of letting the shape drift.
     */
    private static final long serialVersionUID = 1322864360735411516L;

    private static final Logger logger = Logger.getLogger(LondonMulligan.class);

    private final Map<UUID, Integer> startingHandSizes = new HashMap<>();
    private final Map<UUID, Integer> openingHandSizes = new HashMap<>();

    public LondonMulligan(int freeMulligans) {
        super(freeMulligans);
    }

    LondonMulligan(final LondonMulligan mulligan) {
        super(mulligan);

        this.startingHandSizes.putAll(mulligan.startingHandSizes);
        this.openingHandSizes.putAll(mulligan.openingHandSizes);
    }

    @Override
    public void executeMulliganPhase(Game game, int startingHandSize) {
        /*
         * 103.4. Each player draws a number of cards equal to their starting hand size, which is normally
         * seven. (Some effects can modify a player’s starting hand size.) A player who is dissatisfied with
         * their initial hand may take a mulligan. First, the starting player declares whether they will
         * take a mulligan. Then each other player in turn order does the same. Once each player has made a
         * declaration, all players who decided to take mulligans do so at the same time. To take a mulligan,
         * a player shuffles the cards in their hand back into their library, draws a new hand of cards equal
         * to their starting hand size, then puts a number of those cards onto the bottom of their library in
         * any order equal to the number of times that player has taken a mulligan. Once a player chooses not
         * to take a mulligan, the remaining cards become the player’s opening hand, and that player may not
         * take any further mulligans. This process is then repeated until no player takes a mulligan. A
         * player can’t take a number of mulligans greater their starting hand size.
         *
         * https://magic.wizards.com/en/articles/archive/competitive-gaming/mythic-championship-ii-format-and-london-test-2019-02-21
         */

        for (UUID playerId : game.getState().getPlayerList(game.getStartingPlayerId())) {
            openingHandSizes.put(playerId, startingHandSize);
            startingHandSizes.put(playerId, startingHandSize);
        }

        super.executeMulliganPhase(game, startingHandSize);
    }

    @Override
    public int mulliganDownTo(Game game, UUID playerId) {
        Player player = game.getPlayer(playerId);
        int deduction = 1;
        if (freeMulligans > 0) {
            if (usedFreeMulligans.containsKey(player.getId())) {
                int used = usedFreeMulligans.get(player.getId());
                if (used < freeMulligans) {
                    deduction = 0;
                }
            } else {
                deduction = 0;
            }
        }
        return openingHandSizes.get(playerId) - deduction;
    }

    @Override
    public boolean canTakeMulligan(Game game, Player player) {
        return super.canTakeMulligan(game, player) && openingHandSizes.get(player.getId()) > 0;
    }

    @Override
    public void mulligan(Game game, UUID playerId) {
        Player player = game.getPlayer(playerId);
        int numCards = startingHandSizes.get(player.getId());
        player.getLibrary().addAll(player.getHand().getCards(game), game);
        player.getHand().clear();
        player.shuffleLibrary(null, game);
        int deduction = 1;
        if (freeMulligans > 0) {
            if (usedFreeMulligans.containsKey(player.getId())) {
                int used = usedFreeMulligans.get(player.getId());
                if (used < freeMulligans) {
                    deduction = 0;
                    usedFreeMulligans.put(player.getId(), used + 1);
                }
            } else {
                deduction = 0;
                usedFreeMulligans.put(player.getId(), 1);
            }
        }
        openingHandSizes.put(playerId, openingHandSizes.get(playerId) - deduction);
        int newHandSize = openingHandSizes.get(player.getId());
        if (deduction == 0) {
            game.informPlayers(player.getLogName() +
                    " mulligans for free.");
        } else {
            game.informPlayers(player.getLogName() +
                    " mulligans down to " +
                    newHandSize +
                    (newHandSize == 1 ? " card" : " cards"));
        }
        drawHand(numCards, player, game);

        while (player.canRespond() && player.getHand().size() > newHandSize) {
            Target target = new TargetCardInHand(new FilterCard("card (" + (player.getHand().size() - newHandSize) + " more) to put on the bottom of your library"));
            // A card on the bottom is mandatory here (rule 103.4) and the seat has to name
            // it. A false return is the contract's documented "stop choosing"
            // (Player.chooseTarget) — the seat did not choose, so:
            //   * never pick a card for it (AGENTS.md: no graceful fallbacks that hide a
            //     failure — inventing a card would also silently fix an illegal hand), and
            //   * never re-ask it with the same oversized hand: a seat that answers the
            //     same way every time spins this loop forever instead of shrinking the
            //     hand (audit D3 — the loop previously relied on the concede flipping
            //     canRespond()).
            // Stop the bottom step and leave a diagnosable record instead of spinning.
            if (!player.chooseTarget(Outcome.Discard, target, null, game)
                    || target.getTargets().isEmpty()) {
                String failure = player.getLogName() + " did not choose a card to put on the"
                        + " bottom of their library (London mulligan) — stopping the bottom"
                        + " step with a hand of " + player.getHand().size()
                        + " instead of " + newHandSize;
                logger.warn(failure);
                game.informPlayers(failure);
                break;
            }
            player.putCardsOnBottomOfLibrary(new CardsImpl(target.getTargets()), game, null, true);
        }
    }

    @Override
    public void endMulligan(Game game, UUID playerId) {
    }

    @Override
    public LondonMulligan copy() {
        return new LondonMulligan(this);
    }
}
