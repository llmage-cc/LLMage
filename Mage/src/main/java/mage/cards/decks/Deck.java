package mage.cards.decks;

import mage.MageObject;
import mage.cards.Card;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.game.GameException;
import mage.util.Copyable;
import mage.util.DeckUtil;
import org.apache.log4j.Logger;

import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Server side deck with workable cards, also can be used in GUI
 * <p>
 * If you need text only deck then look at DeckCardLists
 */
public class Deck implements Serializable, Copyable<Deck> {

    // Pinned so that the class's serial form does not move when the code around it does.
    // Without this, the default UID is computed from the class STRUCTURE — including
    // whether it has a <clinit> — so merely adding a static field renumbers it and every
    // previously saved replay fails to deserialize:
    //   java.io.InvalidClassException: mage.cards.decks.Deck; local class incompatible:
    //     stream classdesc serialVersionUID = -2364735690361892296,
    //     local class serialVersionUID = 3337142473934398759
    // That is exactly what happened on 2026-09-16, when a static Logger was added to this
    // class and orphaned both saves in saved/.
    // The value below is the pre-change UID, so the already-saved .game files load again.
    // Same treatment GameImpl and GameStates already carry for the replay path.
    private static final long serialVersionUID = -2364735690361892296L;

    private static final Logger logger = Logger.getLogger(Deck.class);

    static final int MAX_CARDS_PER_DECK = 2000;

    private String name; // TODO: must rework somehow - tiny leaders use deck name to find commander card and hide it for a user
    private final Set<Card> cards = new LinkedHashSet<>();
    private final Set<Card> sideboard = new LinkedHashSet<>();
    private transient DeckCardLayout cardsLayout; // client side only
    private transient DeckCardLayout sideboardLayout; // client side only

    public Deck() {
        super();
    }

    protected Deck(final Deck deck) {
        this.name = deck.name;
        this.cards.addAll(deck.cards.stream().map(Card::copy).collect(Collectors.toList()));
        this.sideboard.addAll(deck.sideboard.stream().map(Card::copy).collect(Collectors.toList()));
        this.cardsLayout = deck.cardsLayout == null ? null : deck.cardsLayout.copy();
        this.sideboardLayout = deck.sideboardLayout == null ? null : deck.sideboardLayout.copy();
    }

    public static Deck load(DeckCardLists deckCardLists) throws GameException {
        return Deck.load(deckCardLists, false);
    }

    public static Deck load(DeckCardLists deckCardLists, boolean ignoreErrors) throws GameException {
        return Deck.load(deckCardLists, ignoreErrors, true);
    }

    public static Deck append(Deck sourceDeck, Deck currentDeck) throws GameException {
        Deck newDeck = currentDeck.copy();
        sourceDeck.getCards().forEach(card -> {
            newDeck.cards.add(card.copy());
        });
        sourceDeck.getSideboard().forEach(card -> {
            newDeck.sideboard.add(card.copy());
        });
        return newDeck;
    }

    public static Deck load(DeckCardLists deckCardLists, boolean ignoreErrors, boolean mockCards) throws GameException {
        return load(deckCardLists, ignoreErrors, mockCards, null);
    }

    /**
     * Warning, AI can't play Mock cards, so call it with extra params in real games or tests
     *
     * @param deckCardLists cards to load
     * @param ignoreErrors  - do not raise exception error on wrong deck
     * @param mockCards     - use it for GUI only code, real game cards must be real
     * @return
     * @throws GameException
     */
    public static Deck load(DeckCardLists deckCardLists, boolean ignoreErrors, boolean mockCards, Map<String, CardInfo> cardInfoCache) throws GameException {
        Deck deck = new Deck();
        deck.setName(deckCardLists.getName());
        deck.cardsLayout = deckCardLists.getCardLayout() == null ? null : deckCardLists.getCardLayout().copy();
        deck.sideboardLayout = deckCardLists.getSideboardLayout() == null ? null : deckCardLists.getSideboardLayout().copy();

        // load only real cards
        int totalCards = 0;

        // main
        main:
        for (DeckCardInfo deckCardInfo : deckCardLists.getCards()) {
            for (int i = 1; i <= deckCardInfo.getAmount(); i++) {
                if (totalCards > MAX_CARDS_PER_DECK) {
                    break main;
                }

                Card card = createCard(deckCardInfo, mockCards, cardInfoCache, deckCardLists.getName());
                if (card != null) {
                    deck.cards.add(card);
                    totalCards++;
                } else if (!ignoreErrors) {
                    throw createCardNotFoundGameException(deckCardInfo, deckCardLists.getName());
                }
            }
        }

        // sideboard
        side:
        for (DeckCardInfo deckCardInfo : deckCardLists.getSideboard()) {
            for (int i = 1; i <= deckCardInfo.getAmount(); i++) {
                if (totalCards > MAX_CARDS_PER_DECK) {
                    break side;
                }
                Card card = createCard(deckCardInfo, mockCards, cardInfoCache, deckCardLists.getName());
                if (card != null) {
                    deck.sideboard.add(card);
                    totalCards++;
                } else if (!ignoreErrors) {
                    throw createCardNotFoundGameException(deckCardInfo, deckCardLists.getName());
                }
            }
        }

        return deck;
    }

    private static GameException createCardNotFoundGameException(DeckCardInfo deckCardInfo, String deckName) {
        if (CardRepository.checkDatabaseHealthAndFix()) {
            // it's ok, just unknown card
            String cardError = String.format("Card not found - %s - %s - %s in deck %s.",
                    deckCardInfo.getCardName(),
                    deckCardInfo.getSetCode(),
                    deckCardInfo.getCardNumber(),
                    deckName
            );
            cardError += "\n\nPossible reasons:";
            cardError += "\n - deck problem: un-implemented card or outdated set (fix it by open in deck editor);";
            cardError += "\n - server problem: memory issue (load your deck again or wait a server's restart).";
            return new GameException(cardError);
        } else {
            // critical error, server must be restarted
            return new GameException("Critical problems detected on the server side (memory issues), wait for a restart.");
        }
    }

    private static Card createCard(DeckCardInfo deckCardInfo, boolean mockCards, Map<String, CardInfo> cardInfoCache, String deckName) throws GameException {
        CardInfo cardInfo;
        if (cardInfoCache != null) {
            // from cache
            String key = String.format("%s_%s", deckCardInfo.getSetCode(), deckCardInfo.getCardNumber());
            cardInfo = cardInfoCache.getOrDefault(key, null);
            if (cardInfo == null) {
                cardInfo = CardRepository.instance.findCard(deckCardInfo.getSetCode(), deckCardInfo.getCardNumber());
                cardInfoCache.put(key, cardInfo);
            }
        } else {
            // from db
            cardInfo = CardRepository.instance.findCard(deckCardInfo.getSetCode(), deckCardInfo.getCardNumber());
        }

        // Fail Fast: a [SET:NUM] code that resolves to a DIFFERENT card than the deck list
        // line names must never be imported silently. The code was
        // valid, so the card is found and everything downstream (deck validation, game)
        // happily uses the wrong card. Check only the set/number lookup result: the
        // name-based fallback below matches by name by construction, and a line with an
        // empty name has nothing to contradict. ignoreErrors does NOT suppress this —
        // it only tolerates unknown cards, never the wrong card.
        String lineCardName = deckCardInfo.getCardName();
        if (cardInfo != null && lineCardName != null && !lineCardName.trim().isEmpty()
                && !CardNameUtil.isSameCardName(lineCardName, cardInfo)) {
            String error = CardNameUtil.buildWrongCardError(lineCardName, cardInfo)
                    + (deckName == null || deckName.isEmpty() ? "" : " (deck " + deckName + ")");
            logger.error(error);
            throw new GameException(error);
        }

        // Fallback: if setCode/cardNumber lookup fails (e.g. null values from random deck gen),
        // try name-based lookup.
        // NOTE: findCard(cardName) returns a *random* printing of the card name (non-deterministic).
        // This is acceptable for random-deck generation (BotGameHandler.generateRandomDeck passes
        // null set/number), but the specific art/set chosen will vary run-to-run.
        if (cardInfo == null) {
            cardInfo = CardRepository.instance.findCard(deckCardInfo.getCardName());
        }

        if (cardInfo == null) {
            return null;
        }

        if (mockCards) {
            return cardInfo.createMockCard();
        } else {
            return cardInfo.createCard();
        }
    }

    /**
     * Prepare new deck with cards only without layout and other client side settings.
     * Use it for any export and server's deck update/submit
     */
    public DeckCardLists prepareCardsOnlyDeck() {
        DeckCardLists deckCardLists = new DeckCardLists();
        deckCardLists.setName(name);

        for (Card card : cards) {
            deckCardLists.getCards().add(new DeckCardInfo(card.getName(), card.getCardNumber(), card.getExpansionSetCode()));
        }

        for (Card card : sideboard) {
            deckCardLists.getSideboard().add(new DeckCardInfo(card.getName(), card.getCardNumber(), card.getExpansionSetCode()));
        }

        return deckCardLists;
    }

    public Set<String> getExpansionSetCodes() {
        Set<String> sets = new LinkedHashSet<>();
        for (Card card : getCards()) {
            sets.add(card.getExpansionSetCode());
        }
        for (Card card : getSideboard()) {
            sets.add(card.getExpansionSetCode());
        }
        return sets;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Set<Card> getCards() {
        return cards;
    }

    // TODO: delete and replace by getCards()
    public Set<Card> getMaindeckCards() {
        return cards
                .stream()
                .filter(card -> !card.isExtraDeckCard())
                .collect(Collectors.toSet());
    }

    public Card findCard(UUID cardId) {
        return cards
                .stream()
                .filter(card -> card.getId().equals(cardId))
                .findFirst()
                .orElse(null);
    }

    public DeckCardLayout getCardsLayout() {
        return cardsLayout;
    }

    public Set<Card> getSideboard() {
        return sideboard;
    }

    public Card findSideboardCard(UUID cardId) {
        return sideboard
                .stream()
                .filter(card -> card.getId().equals(cardId))
                .findFirst()
                .orElse(null);
    }

    public DeckCardLayout getSideboardLayout() {
        return sideboardLayout;
    }

    public void clearLayouts() {
        this.cardsLayout = null;
        this.sideboardLayout = null;
    }

    @Override
    public Deck copy() {
        return new Deck(this);
    }

    public long getDeckHash(boolean ignoreMainBasicLands) {
        return DeckUtil.getDeckHash(
                this.cards.stream().map(MageObject::getName).collect(Collectors.toList()),
                this.sideboard.stream().map(MageObject::getName).collect(Collectors.toList()),
                ignoreMainBasicLands
        );
    }
}
