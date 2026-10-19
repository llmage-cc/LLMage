package mage.server;

import mage.cards.decks.Deck;
import mage.cards.decks.P1DeckPool;
import mage.game.Seat;
import mage.players.PlayerType;

import java.util.List;

/**
 * Server-side enforcement of the Argentum-P1 deck restriction.
 *
 * <p>A {@code COMPUTER_P1} seat may only be given one of the 55 Standard
 * decklists the policy net was trained on (see {@link P1DeckPool}). This is the
 * authoritative gate: it runs on the real, card-DB-resolved {@link Deck} inside
 * {@link TableController} at every point a deck can enter a match a P1 seat
 * sits at — the join RPC (match and tournament paths) and the between-games
 * sideboard submit. Hooking {@link mage.cards.decks.DeckValidator} (the
 * format-legality validator) would have been wrong: the restriction is
 * orthogonal to format legality (a legal Standard netdeck can still be
 * out-of-pool), it is per-SEAT not per-table, and the validator has no access
 * to the seat's player type. So the check lives next to the validator, in the
 * controller, keyed on the seat.
 *
 * <p>Only P1 seats are constrained: the same method returns "OK" for every
 * other seat type, so human and LLM seats keep full deck freedom.
 *
 * <p>The single escape hatch ({@link P1DeckPool#ALLOW_NON_TRAINING_PROPERTY})
 * is default OFF and is intentionally not wired into any config.
 */
final class P1DeckRestriction {

    private P1DeckRestriction() {
    }

    /** True when this seat's deck is restricted to the P1 training pool. */
    static boolean applies(Seat seat) {
        return seat != null && seat.getPlayerType() == PlayerType.COMPUTER_P1;
    }

    /**
     * The reason a P1 seat's deck must be rejected, or {@code null} when it is
     * acceptable (or when the seat is not a P1 seat). Honors the default-OFF
     * experimental escape hatch.
     */
    static String rejectReason(Seat seat, Deck deck) {
        if (!applies(seat) || P1DeckPool.isNonTrainingAllowed()) {
            return null;
        }
        P1DeckPool pool = P1DeckPool.instance();
        if (pool.isTrainingDeck(deck)) {
            return null;
        }
        return "This seat is an Argentum-P1 policy-net player: it may only run one of the "
                + pool.size() + " Standard decklists it was trained on. "
                + pool.describeMismatch(deck) + ". Pick a training deck (the deck picker lists them).";
    }

    /**
     * Text-only variant for callers that hold a {@code DeckCardLists} (the proxy
     * pre-join gate and the unit test). Identical decision to
     * {@link #rejectReason(Seat, Deck)}.
     */
    static String rejectReason(Seat seat, mage.cards.decks.DeckCardLists deck) {
        if (!applies(seat) || P1DeckPool.isNonTrainingAllowed()) {
            return null;
        }
        P1DeckPool pool = P1DeckPool.instance();
        if (pool.isTrainingDeck(deck)) {
            return null;
        }
        return "This seat is an Argentum-P1 policy-net player: it may only run one of the "
                + pool.size() + " Standard decklists it was trained on. "
                + pool.describeMismatch(deck) + ". Pick a training deck (the deck picker lists them).";
    }

    /**
     * A single clear server-log line for an authoritative rejection. Fail Fast:
     * the refusal is never silent, and it names what was wrong.
     */
    static String logLine(String where, String playerName, java.util.UUID tableId, Deck deck) {
        P1DeckPool pool = P1DeckPool.instance();
        return "P1 deck restriction: " + where + " rejected for COMPUTER_P1 seat '"
                + playerName + "' (tableId " + tableId + "): "
                + pool.describeMismatch(deck)
                + "; allowed pool = " + pool.size() + " training decks [" + sample(pool.deckNames()) + "]";
    }

    /** Log-line variant for callers holding a {@code DeckCardLists}. */
    static String logLine(String where, String playerName, java.util.UUID tableId, mage.cards.decks.DeckCardLists deck) {
        P1DeckPool pool = P1DeckPool.instance();
        return "P1 deck restriction: " + where + " rejected for COMPUTER_P1 seat '"
                + playerName + "' (tableId " + tableId + "): "
                + pool.describeMismatch(deck)
                + "; allowed pool = " + pool.size() + " training decks [" + sample(pool.deckNames()) + "]";
    }

    private static String sample(List<String> names) {
        int n = Math.min(6, names.size());
        String joined = String.join(", ", names.subList(0, n));
        return names.size() > n ? joined + ", ..." : joined;
    }
}
