package mage.cards.decks;

import mage.cards.repository.CardInfo;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class CardNameUtil {

    public static final Pattern CARD_NAME_PATTERN = Pattern.compile("[+ !\"&',\\-./0-9:A-Za-z]+");

    /**
     * Separator between the two names of a multi-name card ("Fire // Ice"), see
     * {@code CardRepository.findCards} and {@code NamePredicate}.
     */
    private static final Pattern MULTI_NAME_SPLIT = Pattern.compile("\\s+//\\s+");

    /**
     * Convert card names with unicode symbols to ascii, uses to deck import from a third party services
     */
    public static String normalizeCardName(String name) {
        // new symbols checks in verify test, no need to manually search it
        return name
                .replace("&amp;", "//")
                .replace("///", "//")
                .replace("Ã†", "Ae")
                .replace("Ã¶", "A")
                .replace("ö", "o")
                .replace("û", "u")
                .replace("í", "i")
                .replace("ï", "i")
                .replace("î", "i")
                .replace("â", "a")
                .replace("á", "a")
                .replace("à", "a")
                .replace("é", "e")
                .replace("ú", "u")
                .replace("†", "+")
                .replace("★", "*")
                .replace("Φ", "Ph")
                .replace("Ó", "O")
                .replace("ó", "o")
                .replace("ō", "o")
                .replace("ä", "a")
                .replace("ü", "u")
                .replace("É", "E")
                .replace("ñ", "n")
                .replace("꞉", "")
                .replace("®", "")
                .replace("—", "");
    }

    /**
     * All names a decklist line may legitimately use for one resolved card, lowercased:
     * <ul>
     *   <li>the card's own name ("Fire // Ice") and each of its halves ("Fire", "Ice") —
     *       split cards, MDFCs, flip/transform cards and adventure cards are all written
     *       as "A // B" in the DB, see {@code CardRepository.findCards};</li>
     *   <li>the side names the DB already exposes for the same printing
     *       ({@code secondSideName}, {@code doubleFacedSecondSideName}, {@code flipCardName},
     *       {@code meldsToCardName}, {@code spellOptionCardName}) — those are the variants
     *       {@code CardRepository.addNewNames} treats as the same card.</li>
     * </ul>
     */
    private static Set<String> acceptedNameVariants(CardInfo cardInfo) {
        Set<String> names = new LinkedHashSet<>();
        addNameVariants(names, cardInfo.getName());
        addNameVariants(names, cardInfo.getSecondSideName());
        addNameVariants(names, cardInfo.getDoubleFacedSecondSideName());
        addNameVariants(names, cardInfo.getFlipCardName());
        addNameVariants(names, cardInfo.getMeldsToCardName());
        addNameVariants(names, cardInfo.getSpellOptionCardName());
        return names;
    }

    private static void addNameVariants(Set<String> names, String name) {
        if (name == null) {
            return;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        names.add(trimmed.toLowerCase(Locale.ENGLISH));
        if (MULTI_NAME_SPLIT.matcher(trimmed).find()) {
            for (String half : MULTI_NAME_SPLIT.split(trimmed)) {
                String part = half.trim().toLowerCase(Locale.ENGLISH);
                if (!part.isEmpty()) {
                    names.add(part);
                }
            }
        }
    }

    /**
     * Verify that the card resolved by a decklist line's [SET:NUM] code really is the
     * card the line names. Multi-name cards are written as "A // B" in the DB while
     * decklists legitimately use "A", "B" or "A // B" (this mirrors
     * {@code CardRepository.findCards} / {@code NamePredicate}).
     * <p>
     * Used by deck import as a Fail-Fast guard: a valid-but-wrong [SET:NUM] code would
     * otherwise silently import a different card.
     *
     * @param lineCardName name written in the decklist line
     * @param resolvedCard card the set/number code resolved to
     */
    public static boolean isSameCardName(String lineCardName, CardInfo resolvedCard) {
        if (lineCardName == null || lineCardName.trim().isEmpty() || resolvedCard == null) {
            return false;
        }
        return acceptedNameVariants(resolvedCard).contains(lineCardName.trim().toLowerCase(Locale.ENGLISH));
    }

    /**
     * Build the deck-import error for a [SET:NUM] code that resolved to a different card
     * than the line names (Fail Fast: never import the wrong card silently).
     */
    public static String buildWrongCardError(String lineCardName, CardInfo resolvedCard) {
        if (resolvedCard == null) {
            throw new IllegalArgumentException("resolvedCard can't be null");
        }
        return String.format(
                "Wrong card: the line says '%s' but [%s:%s] is '%s' (in set %s)."
                        + " Fix the card number in the decklist (or remove the [SET:NUM] code).",
                lineCardName,
                resolvedCard.getSetCode(),
                resolvedCard.getCardNumber(),
                resolvedCard.getName(),
                resolvedCard.getSetCode()
        );
    }

    private CardNameUtil() {
        // utility class
    }

}
