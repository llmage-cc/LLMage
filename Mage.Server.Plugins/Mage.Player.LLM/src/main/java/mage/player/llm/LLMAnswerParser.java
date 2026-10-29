package mage.player.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pure, side-effect-free answer parsers for the LLM player.
 *
 * <p>Both parsers were extracted out of {@link LLMPlayer} because the two
 * filed defects in them were only reachable through a live game: the answer
 * parsers are the part that has to be exercisable without a game harness.
 * Nothing in here logs and nothing throws — the caller owns every diagnostic
 * (it has the raw answer and the logger) and every recovery decision.
 *
 * <p>Defect 1 (target selection): the validator accepts any run of digits,
 * commas and whitespace, but the old parse arms split on comma only, so a
 * whitespace-separated answer ({@code "18   2       19  18"}) arrived as one
 * token and the resulting {@code NumberFormatException} was re-thrown as a
 * {@code RuntimeException} that ended the game as a technical winner.
 *
 * <p>Defect 2 (sideboard): the old line parser returned each answer line
 * verbatim, so markdown emphasis, bullet markers and stray section labels all
 * became card names and every such token failed to resolve.
 */
public final class LLMAnswerParser {

    private LLMAnswerParser() {
    }

    // ==================================================================
    //  TARGET SELECTION NUMBERS
    // ==================================================================

    /**
     * Numbers parsed out of a target-selection answer, plus the tokens that
     * could not be parsed. Skipped tokens exist so the caller can warn about
     * them with the raw answer in hand; the parser only ever skips.
     */
    public static final class TargetNumbers {
        private final List<Integer> numbers;
        private final List<String> skipped;

        TargetNumbers(List<Integer> numbers, List<String> skipped) {
            this.numbers = Collections.unmodifiableList(numbers);
            this.skipped = Collections.unmodifiableList(skipped);
        }

        /** Parsed selection numbers in answer order (may contain 0 = cancel). */
        public List<Integer> numbers() {
            return numbers;
        }

        /**
         * Tokens that looked numeric but could not be parsed — in practice a
         * digit run too large for an {@code int}. Never throws over these.
         */
        public List<String> skipped() {
            return skipped;
        }
    }

    /**
     * Parse the selection numbers out of an answer. Splits on one or more
     * commas OR whitespace — exactly the accept-set the target validator
     * allows ({@code [\d,\s]+}) — so whitespace-separated production answers
     * such as {@code "18   2       19  18"} parse instead of blowing up.
     *
     * <p>Only the first line is read (reasoning blocks contain numbers that
     * are not selections), and every non-digit, non-comma, non-whitespace
     * character is stripped first, so a prose answer still contributes its
     * digits ({@code "pick 2 then 5 ok"} → {@code [2, 5]}).
     *
     * @return parsed numbers in answer order; empty when the answer carries
     *         none. Never null, never throws.
     */
    public static List<Integer> parseTargetNumbers(String answer) {
        return parseTargetNumbersReporting(answer).numbers();
    }

    /**
     * Same parse as {@link #parseTargetNumbers(String)}, also reporting the
     * tokens that could not be parsed so the caller can log them. A bad token
     * is skipped, never fatal: the caller's retry ladder (fewer than
     * {@code min} targets) stays the only recovery path.
     */
    public static TargetNumbers parseTargetNumbersReporting(String answer) {
        List<Integer> numbers = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (answer == null) {
            return new TargetNumbers(numbers, skipped);
        }
        String firstLine = answer.split("\n", 2)[0];
        String cleaned = firstLine.replaceAll("[^0-9,\\s]", "");
        for (String token : cleaned.split("[,\\s]+")) {
            if (token.isEmpty()) continue;
            try {
                numbers.add(Integer.parseInt(token));
            } catch (NumberFormatException e) {
                // Numeric-looking but unparseable (an int overflow from a digit
                // run such as a timestamp). Skip it — a throw here is what used
                // to end the game as a technical winner.
                skipped.add(token);
            }
        }
        return new TargetNumbers(numbers, skipped);
    }

    // ==================================================================
    //  SIDEBOARD CARD LISTS
    // ==================================================================

    /** A line that is nothing but a section label carries no card name. */
    private static final Pattern SECTION_LABEL_ONLY =
            Pattern.compile("(?i)^(?:IN|OUT|SIDEBOARD|PLAN)\\s*:?$");

    /** A section label prefixed to an inline list ("IN: Pyroblast, REB"). */
    private static final Pattern SECTION_LABEL_PREFIX =
            Pattern.compile("(?i)^(?:IN|OUT|SIDEBOARD|PLAN)\\s*:\\s*");

    /** Leading markdown list marker: "- ", "+ ", a bullet char, or "1."/"1)". */
    private static final Pattern LIST_MARKER =
            Pattern.compile("^(?:[-+\\u2022\\u00b7\\u2023\\u25aa\\u25cf\\u25e6]|\\d+[.)])\\s*");

    /**
     * Parse a sideboard section (the text after {@code IN:} or {@code OUT:})
     * into card-name entries, one per answer line.
     *
     * <p>Each line is normalised before it can become a card name: markdown
     * emphasis is stripped ({@code **}, {@code __}, {@code *}, backtick,
     * {@code ~~}), a leading list marker is stripped ({@code -}, {@code +},
     * bullet chars, {@code 1.}/{@code 1)}), and the line is trimmed. Lines
     * that end up empty, that are {@code NONE}, or that are a residual section
     * label ({@code IN:}, {@code OUT:}, {@code SIDEBOARD}, {@code PLAN}) are
     * dropped. A label prefixed to real content ({@code "IN: Pyroblast"}) is
     * stripped so the card name behind it survives.
     *
     * <p>Commas are deliberately NOT split here: card names legitimately
     * contain them ("Kess, Dissident Mage", "Atraxa, Praetors' Voice"). The
     * caller resolves the whole line first and only falls back to the
     * comma-split parts — see {@link #cardNameCandidates(String)}.
     *
     * @return normalised entries in answer order; empty when nothing is left.
     *         Never null, never throws.
     */
    public static List<String> parseCardList(String section) {
        List<String> names = new ArrayList<>();
        if (section == null) return names;
        for (String raw : section.split("[\r\n]+")) {
            String line = normaliseCardLine(raw);
            if (line != null) names.add(line);
        }
        return names;
    }

    /**
     * Candidate name forms for one card-list entry, in resolution order: the
     * whole normalised entry first, then its comma-split parts (trimmed, empty
     * parts dropped). Card names containing commas therefore resolve as a
     * whole and are never blind-split; only an entry that does not resolve
     * whole (an inline {@code "Pyroblast, Red Elemental Blast"} answer) falls
     * back to its parts.
     */
    public static List<String> cardNameCandidates(String entry) {
        List<String> candidates = new ArrayList<>();
        if (entry == null) return candidates;
        String whole = entry.trim();
        if (whole.isEmpty()) return candidates;
        candidates.add(whole);
        if (whole.indexOf(',') >= 0) {
            for (String part : whole.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) candidates.add(trimmed);
            }
        }
        return candidates;
    }

    /** Normalise one answer line; null means "not a card name, drop it". */
    private static String normaliseCardLine(String raw) {
        if (raw == null) return null;
        String line = raw
                .replace("**", "")
                .replace("__", "")
                .replace("~~", "")
                .replace("`", "")
                .replace("*", "");
        line = LIST_MARKER.matcher(line.trim()).replaceFirst("").trim();
        if (SECTION_LABEL_ONLY.matcher(line).matches()) return null;
        line = SECTION_LABEL_PREFIX.matcher(line).replaceFirst("").trim();
        if (line.isEmpty()) return null;
        if (line.equalsIgnoreCase("NONE")) return null;
        return line;
    }
}
