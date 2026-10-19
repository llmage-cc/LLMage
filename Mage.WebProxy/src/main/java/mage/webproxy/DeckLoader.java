package mage.webproxy;

import mage.cards.decks.CardNameUtil;
import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses pasted decklists into DeckCardLists for XMage.
 *
 * Supports three formats:
 * 1. XMage .dck format: "4 [SET:001] Lightning Bolt" with NAME:/AUTHOR: headers
 * 2. Simple format: "4 Lightning Bolt" (one per line, set code optional)
 * 3. Annotated-export format (Moxfield/Archidekt-style, seen live 2026-09-19):
 *    "1 x Akroma's Will (lcc) 125 [Instant]" — optional "x" after the count,
 *    optional trailing "xN" count echo and "[:]" marker; the [Type] is ignored.
 *    The (SET) NUMBER pair is used as the card's [SET:NUM] code and goes through
 *    the same Fail-Fast name-vs-code check as format 1.
 *
 * Sideboard lines start with "SB:".
 *
 * A [SET:NUM] code must name the same card as the line says — a valid code that
 * points at a different card is rejected here (Fail Fast) instead of importing
 * the wrong card. The engine enforces the same rule in
 * {@code Deck.createCard}, so every caller is covered.
 */
public final class DeckLoader {

    // Matches: (SB:) (count) ([SET:NUM]) (card name)
    // [^]:] means "not ] or :" inside the character class
    private static final Pattern DCK_CARD_LINE = Pattern.compile(
        "^(SB:)?\\s*(\\d+)\\s*(?:\\[([^]:]+):([^]:]+)\\])?\\s*(.+?)\\s*$"
    );

    // Annotated-export line: (SB:) (count) [x] (name) ((SET)) (number) [[Type]] [xN] [[:]]
    // The "x" after the count is optional and takes the space with it EITHER side
    // ("1 x Name", "1x Name") — Moxfield exports use both; the [Type] group is
    // optional (several exporters omit it); a lazy name so card names containing
    // parentheses (e.g. "B.F.M. (Big Furry Monster)") backtrack to the LAST
    // "(word) digits" group; a (SET) number pair is still required so plain
    // "4 Forest" and "4 Xantcha" never land here.
    private static final Pattern ANNOTATED_CARD_LINE = Pattern.compile(
        "^(SB:)?\\s*(\\d+)(?:\\s*[xX]\\s+|\\s+)(.+?)\\s*\\(([A-Za-z0-9]+)\\)\\s*(\\d+)\\s*(?:\\[([^]]+)\\])?\\s*(?:[xX](\\d+))?\\s*(?:\\[:\\])?\\s*$"
    );

    // Shape of a mangled annotated name (what DCK_CARD_LINE would capture from a
    // near-miss annotated line): optional "x " prefix, " (set) digits" signature, any
    // tail ([Type] complete or truncated, [:], xN echo — all variants rejected).
    // Real card names never contain "(short-code) digits", so this cannot false-positive.
    private static final Pattern ANNOTATED_NAME_SHAPE = Pattern.compile(
        "^(?:[xX]\\s+)?(.+?)\\s*\\(([A-Za-z0-9]+)\\)\\s*(\\d+)(\\s.*)?$"
    );

    private DeckLoader() {}

    public static DeckCardLists parseDeckList(String deckList) {
        if (deckList == null || deckList.trim().isEmpty()) {
            throw new IllegalArgumentException("Deck list is empty");
        }

        DeckCardLists deck = new DeckCardLists();
        String[] lines = deckList.split("\n");
        int lineNumber = 0;

        for (String rawLine : lines) {
            lineNumber++;
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }

            if (line.startsWith("NAME:")) {
                deck.setName(line.substring(5).trim());
                continue;
            }
            if (line.startsWith("AUTHOR:")) {
                deck.setAuthor(line.substring(7).trim());
                continue;
            }
            if (line.startsWith("LAYOUT ")) {
                continue;
            }

            boolean isSideboard;
            int count;
            String setCode;
            String cardNumber;
            String cardName;

            // Format 3 must be tried FIRST: annotated-export lines also match
            // DCK_CARD_LINE, and falling through would glue the annotations into the
            // card name ("x Akroma's Will (lcc) 125 [Instant]") — accepted silently,
            // then rejected by the server as "Card not found".
            Matcher annotated = ANNOTATED_CARD_LINE.matcher(line);
            if (annotated.matches()) {
                isSideboard = annotated.group(1) != null;
                count = parseCount(lineNumber, annotated.group(2));
                setCode = annotated.group(4);
                cardNumber = annotated.group(5);
                cardName = annotated.group(3).trim();
                // The leading count and the trailing "xN" echo must agree.
                if (annotated.group(7) != null && Integer.parseInt(annotated.group(7)) != count) {
                    throw new IllegalArgumentException(
                        "Invalid deck line " + lineNumber + ": count " + count
                        + " contradicts trailing x" + annotated.group(7) + " in \"" + line + "\""
                    );
                }
            } else {
                Matcher matcher = DCK_CARD_LINE.matcher(line);
                if (!matcher.matches()) {
                    throw new IllegalArgumentException(
                        "Invalid deck line " + lineNumber + ": " + line
                    );
                }

                isSideboard = matcher.group(1) != null;
                count = parseCount(lineNumber, matcher.group(2));
                setCode = matcher.group(3) != null ? matcher.group(3).trim() : "";
                cardNumber = matcher.group(4) != null ? matcher.group(4).trim() : "";
                cardName = matcher.group(5).trim();

                // "1x Lightning Bolt" / "4 x Grizzly Bears": the count-x separator is
                // not part of the card name. No real card name starts with a lowercase
                // x followed by whitespace ("Xantcha" is Xa-), so a leading "x " here
                // is always the separator the loose regex swallowed.
                if (cardName.length() > 2 && (cardName.startsWith("x ") || cardName.startsWith("X "))) {
                    cardName = cardName.substring(2).trim();
                }

                // Reject whitespace-only set codes / card numbers. The regex [^]:]+
                // technically rejects an empty bracket group, but a bracket like
                // [BAD:  ] produces a cardNumber of spaces which would later confuse
                // DeckCardInfo / Scryfall lookups. Fail Fast with a clear error.
                // (The annotated-export branch can't produce blank codes: its set
                // group is [A-Za-z0-9]+ and its number group is \d+ by construction.)
                if (matcher.group(3) != null && setCode.isEmpty()) {
                    throw new IllegalArgumentException(
                        "Invalid deck line " + lineNumber + ": empty set code in [" + matcher.group(3) + ":" + matcher.group(4) + "]"
                    );
                }
                if (matcher.group(4) != null && cardNumber.isEmpty()) {
                    throw new IllegalArgumentException(
                        "Invalid deck line " + lineNumber + ": empty card number in [" + matcher.group(3) + ":" + matcher.group(4) + "]"
                    );
                }

                // Fail Fast on the near-miss: a line that LOOKS like an annotated export
                // but lacks the full shape (e.g. no [Type]) would produce a garbage name
                // like "x Name (set) 123 [Creat" — reject with a hint instead of letting
                // it die on the server as an opaque "Card not found".
                if (setCode.isEmpty() && cardNumber.isEmpty()
                        && ANNOTATED_NAME_SHAPE.matcher(cardName).matches()) {
                    throw new IllegalArgumentException(
                        "Invalid deck line " + lineNumber + ": looks like an annotated export (\""
                        + line + "\"). Use \"N [SET:NUM] Card Name\", plain \"N Card Name\", "
                        + "or the full form \"N x Card Name (SET) NUM [Type]\"."
                    );
                }
            }

            // Fail Fast: a [SET:NUM] code must resolve to the card the line names.
            String mismatch = findCardNameMismatch(cardName, setCode, cardNumber);
            if (mismatch != null) {
                throw new IllegalArgumentException("Invalid deck line " + lineNumber + ": " + mismatch);
            }

            DeckCardInfo cardInfo = new DeckCardInfo(cardName, cardNumber, setCode, count);

            if (isSideboard) {
                deck.getSideboard().add(cardInfo);
            } else {
                deck.getCards().add(cardInfo);
            }
        }

        if (deck.getCards().isEmpty() && deck.getSideboard().isEmpty()) {
            throw new IllegalArgumentException("Deck is empty after parsing");
        }

        return deck;
    }

    /**
     * Same Fail-Fast check for callers that already built a DeckCardLists without going
     * through the text parser (the web client's {@code submit_deck}, which posts JSON
     * entries). Throws on the first entry whose [SET:NUM] code contradicts its name.
     */
    public static void verifyDeckCardNames(DeckCardLists deck) {
        if (deck == null) {
            throw new IllegalArgumentException("Deck is empty");
        }
        for (String zone : new String[]{"cards", "sideboard"}) {
            List<DeckCardInfo> entries = "cards".equals(zone) ? deck.getCards() : deck.getSideboard();
            for (DeckCardInfo entry : entries) {
                String mismatch = findCardNameMismatch(entry.getCardName(), entry.getSetCode(), entry.getCardNumber());
                if (mismatch != null) {
                    throw new IllegalArgumentException(mismatch);
                }
            }
        }
    }

    private static int parseCount(int lineNumber, String raw) {
        try {
            int count = Integer.parseInt(raw);
            if (count < 1) {
                throw new IllegalArgumentException(
                    "Invalid deck line " + lineNumber + ": card count must be 1 or more, got " + raw
                );
            }
            return count;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Invalid deck line " + lineNumber + ": bad card count \"" + raw + "\""
            );
        }
    }

    /**
     * @return null when the line is fine, otherwise the error text to reject it with.
     * Lines without a name (nothing to check against) or without a complete set/number
     * code (resolved by name downstream) are skipped. A code the proxy's own card
     * database cannot resolve is left to the XMage server, which reports the same code
     * as "Card not found" — the proxy must not reject cards its older DB copy lacks.
     */
    private static String findCardNameMismatch(String cardName, String setCode, String cardNumber) {
        if (cardName == null || cardName.isEmpty() || setCode == null || setCode.isEmpty()
                || cardNumber == null || cardNumber.isEmpty()) {
            return null;
        }
        CardInfo resolved = CardRepository.instance.findCard(setCode, cardNumber);
        if (resolved == null) {
            return null;
        }
        if (!CardNameUtil.isSameCardName(cardName, resolved)) {
            return CardNameUtil.buildWrongCardError(cardName, resolved);
        }
        return null;
    }
}