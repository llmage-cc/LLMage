package mage.webproxy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import mage.cards.decks.DeckCardLists;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * BuiltinDeckValidator — generates web/frontend/decks/builtin.json for the
 * web client's built-in deck picker.
 *
 * Sources (both are MANDATORY arguments; nothing is invented):
 *   --majors   <dir>   the mtgtop8 majors corpus (Legacy/Modern/Pioneer/Vintage/cEDH
 *                      dirs with <event-dir>/<rank>__<deck>_by_<player>.txt files and a
 * per-format manifest.json). Side-events dirs.dec files and
 *                      *.ILLEGAL.txt / *.UNAVAILABLE.txt renames are EXCLUDED.
 *   --samples  <dir>   the XMage sample decks tree (Mage.Client/release/sample-decks/).
 *                      Every .dck under it ships; Jumpstart holds no .dck (its
 *                      jumpstart_custom.txt is not a deck, matching the upstream
 *                      verify test's exclusion). Momir Basic/Momir Basic.dck IS a deck.
 *
 * EVERY deck is validated through the exact web path before it ships:
 *   1. DeckLoader.parseDeckList — the proxy's Fail-Fast parser (line shapes,
 *      [SET:NUM]-vs-name check against this build's card DB).
 *   2. Plain card names are resolved against CardRepository: a plain-name line
 *      with no printing in the DB would pass the parser but die server-side at
 *      join with "Card not found" (the web path has no TxtDeckImporter-style
 *      auto-normalization, so the generator does the work itself: "A / B" is
 *      rewritten to the DB's "A // B" form before shipping).
 *   3. Sample decks' [SET:NUM] codes must resolve in THIS database — the proxy
 *      DB and the server DB are version-matched, so an unresolvable code is a
 *      guaranteed join-time "Card not found".
 *
 * A deck that fails any check is QUARANTINED: reported on stderr with its
 * exact failure and EXCLUDED from the JSON. The run exits non-zero when
 * anything was quarantined (Fail Fast, AGENTS.md — no silent degradation).
 *
 * Run (CWD MUST be Mage.WebProxy — CardRepository opens ./db/cards.h2 relative
 * to it; running from another checkout CWD can clobber a foreign card table):
 *   cd Mage.WebProxy
 *   java -cp "target/classes:target/lib/*" mage.webproxy.BuiltinDeckValidator \
 *        --majors /path/to/mtgtop8-majors \
 *        --samples ../Mage.Client/release/sample-decks \
 *        --out web/frontend/decks/builtin.json
 */
public final class BuiltinDeckValidator {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    /** A majors playable file: <rank>__<Archetype>_by_<Player>.txt (rank may contain '-'). */
    private static final Pattern MAJORS_NAME = Pattern.compile(
        "^(?<rank>[0-9]+(?:-[0-9]+)?)__ (.+?) _by_(.+)\\.txt$", Pattern.COMMENTS);

    /** Same shape as DeckLoader's DCK_CARD_LINE, for the plain-name scan. */
    private static final Pattern CARD_LINE = Pattern.compile(
        "^(SB:)?\\s*(\\d+)\\s*(?:\\[([^]:]+):([^]:]+)\\])?\\s*(.+?)\\s*$");

    /** mtgtop8 writes split cards "A / B"; the DB stores "A // B". */
    private static final Pattern SINGLE_SLASH = Pattern.compile("(?<=[^/])\\s*/\\s*(?=[^/])");

    /** Cards that resolve only as a split-half etc. are still found by findCards. */

    private static final class DeckEntry {
        String id;
        String source;      // "majors" | "sample"
        String format;      // majors format (Legacy...) or "Sample"
        String group;       // submenu level 1: majors format / sample top folder
        String subcategory; // submenu level 2: event title (majors) / sub folder (sample, "" when none)
        String name;
        String author;
        String text;
    }

    private static final class Quarantine {
        final String deck;
        final String reason;
        Quarantine(String deck, String reason) { this.deck = deck; this.reason = reason; }
    }

    private final List<DeckEntry> decks = new ArrayList<>();
    private final List<Quarantine> quarantined = new ArrayList<>();
    private final Map<String, Boolean> nameResolvesCache = new HashMap<>();
    private final Map<String, Boolean> codeResolvesCache = new HashMap<>();

    public static void main(String[] args) throws IOException {
        Path majors = null;
        Path samples = null;
        Path out = null;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--majors" -> majors = Paths.get(args[i + 1]);
                case "--samples" -> samples = Paths.get(args[i + 1]);
                case "--out" -> out = Paths.get(args[i + 1]);
                default -> { }
            }
        }
        if (majors == null || samples == null || out == null) {
            System.err.println("Usage: BuiltinDeckValidator --majors <corpus> --samples <sample-decks dir> --out <builtin.json>");
            System.exit(2);
        }
        if (!Files.isDirectory(majors)) {
            System.err.println("FATAL: majors corpus dir not found: " + majors.toAbsolutePath());
            System.exit(2);
        }
        if (!Files.isDirectory(samples)) {
            System.err.println("FATAL: sample-decks dir not found: " + samples.toAbsolutePath());
            System.exit(2);
        }
        // Fail fast BEFORE touching anything: CardRepository opens ./db/cards.h2
        // relative to the CWD. Refuse to run without a populated DB next to the CWD
        // (the validator would otherwise silently skip every name check).
        Path db = Paths.get("db", "cards.h2.mv.db");
        if (!Files.isRegularFile(db) || sizeOf(db) < 30L * 1024 * 1024) {
            System.err.println("FATAL: " + db.toAbsolutePath() + " missing or <30MB."
                + " Run with CWD=Mage.WebProxy and stage the DB (scripts/stage-test-db.sh).");
            System.exit(2);
        }

        BuiltinDeckValidator v = new BuiltinDeckValidator();
        v.loadMajors(majors);
        v.loadSamples(samples);
        v.write(out, majors, samples);

        int n = v.quarantined.size();
        System.out.println();
        System.out.println("builtin.json: " + v.decks.size() + " decks ("
            + v.decks.stream().filter(d -> "majors".equals(d.source)).count() + " majors, "
            + v.decks.stream().filter(d -> "sample".equals(d.source)).count() + " sample), "
            + n + " quarantined");
        if (n > 0) {
            System.err.println();
            System.err.println("QUARANTINED DECKS (" + n + ") — excluded from builtin.json:");
            for (Quarantine q : v.quarantined) {
                System.err.println("  " + q.deck);
                System.err.println("    " + q.reason);
            }
            System.exit(1);
        }
    }

    private static long sizeOf(Path p) {
        try { return Files.size(p); } catch (IOException e) { return 0; }
    }

    // ── majors corpus ────────────────────────────────────────────────────

    private void loadMajors(Path corpus) throws IOException {
        List<Path> formats = new ArrayList<>();
        try (Stream<Path> s = Files.list(corpus)) {
            s.filter(Files::isDirectory)
             .filter(d -> !d.getFileName().toString().startsWith("_"))
             .forEach(formats::add);
        }
        formats.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path fmtDir : formats) {
            String format = fmtDir.getFileName().toString();
            Map<String, Map<String, String>> byFile = readManifest(fmtDir.resolve("manifest.json"));
            List<Path> files = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(fmtDir, FileVisitOption.FOLLOW_LINKS)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .filter(p -> !p.getFileName().toString().contains("ILLEGAL"))
                    .filter(p -> !p.getFileName().toString().contains("UNAVAILABLE"))
                    .filter(p -> !p.toString().contains("_side-events"))
                    .forEach(files::add);
            }
            files.sort(Comparator.comparing(p -> p.toString()));
            for (Path txt : files) {
                String rel = format + "/" + fmtDir.relativize(txt).toString().replace('\\', '/');
                try {
                    Map<String, String> meta = byFile.get(stemKey(txt));
                    if (meta == null) {
                        quarantine(rel, "no manifest entry for this deck (corpus/manifest mismatch)");
                        continue;
                    }
                    String body = Files.readString(txt, StandardCharsets.UTF_8);
                    String normalized = normalizePlain(body);
                    DeckEntry e = new DeckEntry();
                    e.id = "majors:" + rel;
                    e.source = "majors";
                    e.format = format;
                    e.group = format;
                    e.subcategory = meta.getOrDefault("event", format);
                    e.name = (meta.getOrDefault("deck", "").isEmpty() ? stripUnderscores(baseName(txt)) : meta.get("deck"));
                    e.author = meta.getOrDefault("player", "");
                    e.text = header(e, meta.getOrDefault("date", ""))
                        + normalized;
                    validateAndAdd(e, rel);
                } catch (Exception ex) {
                    quarantine(rel, "unreadable: " + ex);
                }
            }
        }
    }

    /** manifest.json decks[] keyed by "<event-dir>/<deck stem>" (the .dec path minus .dec). */
    private static Map<String, Map<String, String>> readManifest(Path manifest) {
        Map<String, Map<String, String>> byFile = new HashMap<>();
        if (!Files.isRegularFile(manifest)) {
            return byFile;
        }
        try {
            String json = Files.readString(manifest, StandardCharsets.UTF_8);
            Map<?, ?> root = GSON.fromJson(json, Map.class);
            Object list = root.get("decks");
            if (list instanceof Iterable<?> it) {
                for (Object o : it) {
                    if (o instanceof Map<?, ?> m) {
                        String file = String.valueOf(m.get("file"));
                        if (file.endsWith(".dec")) {
                            byFile.put(file.substring(0, file.length() - 4), toStringMap(m));
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Fail fast: a corrupt manifest must not silently drop metadata.
            throw new IllegalStateException("Cannot read " + manifest + ": " + e.getMessage(), e);
        }
        return byFile;
    }

    private static Map<String, String> toStringMap(Map<?, ?> m) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
        }
        return out;
    }

    private static String stemKey(Path txt) {
        String fn = txt.getFileName().toString();
        int slash = txt.toString().lastIndexOf(java.io.File.separatorChar);
        String parent = slash >= 0 ? txt.getParent().getFileName().toString() : "";
        return parent + "/" + fn.substring(0, fn.length() - 4);
    }

    // kept for clarity in loadMajors: manifest key from the txt path
    private static String stemKey2(Path txt) { return stemKey(txt); }

    private static String stem(Path txt) {
        String parent = txt.getParent().getFileName().toString();
        String fn = txt.getFileName().toString();
        return parent + "/" + fn.substring(0, fn.length() - 4);
    }

    // ── sample decks ─────────────────────────────────────────────────────

    private void loadSamples(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, FileVisitOption.FOLLOW_LINKS)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".dck"))
                .forEach(files::add);
        }
        files.sort(Comparator.comparing(p -> p.toString()));
        for (Path dck : files) {
            String rel = root.relativize(dck).toString().replace('\\', '/');
            String top = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
            String sub = rel.substring(rel.indexOf('/') + 1);
            String subcat = sub.contains("/")
                ? sub.substring(0, sub.lastIndexOf('/'))
                : "";
            try {
                String body = Files.readString(dck, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replaceAll("(?m)^EVENT:.*\\n", ""); // DeckLoader rejects EVENT: lines
                DeckEntry e = new DeckEntry();
                e.id = "sample:" + rel;
                e.source = "sample";
                e.format = "Sample";
                e.group = top;
                e.subcategory = subcat(subcat);
                e.name = nameFromBody(body, baseName(dck));
                e.author = authorFromBody(body);
                e.text = body.endsWith("\n") ? body : body + "\n";
                validateAndAdd(e, "sample:" + rel);
            } catch (Exception ex) {
                quarantine("sample:" + rel, "unreadable: " + ex);
            }
        }
    }

    private static String subcat(String s) { return s; }

    private static String nameFromBody(String body, String fallback) {
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (t.startsWith("NAME:")) {
                String n = t.substring(5).trim();
                if (!n.isEmpty()) return n;
            }
        }
        return fallback;
    }

    private static String authorFromBody(String body) {
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (t.startsWith("AUTHOR:")) {
                return t.substring(7).trim();
            }
        }
        return "";
    }

    // ── validation (the web path, exactly) ───────────────────────────────

    private void validateAndAdd(DeckEntry e, String debugName) {
        DeckCardLists parsed;
        try {
            parsed = DeckLoader.parseDeckList(e.text);
        } catch (Throwable t) {
            quarantine(debugName, "DeckLoader rejected: " + t.getMessage());
            return;
        }
        if (parsed.getCards().isEmpty() && parsed.getSideboard().isEmpty()) {
            quarantine(debugName, "parsed to an empty deck");
            return;
        }

        // Every card must resolve in THIS build's DB (server DB is version-matched):
        //  - [SET:NUM] codes must hit a printing (DeckLoader skips unknown codes by
        //    design for an OLDER proxy DB; here an unresolved code is a dead card).
        //  - plain names must hit findCards(name) (server resolves by name at join).
        for (String line : e.text.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")
                    || t.startsWith("NAME:") || t.startsWith("AUTHOR:") || t.startsWith("LAYOUT ")) {
                continue;
            }
            Matcher m = CARD_LINE.matcher(t);
            if (!m.matches()) {
                quarantine(debugName, "unparsed card line: " + t);
                return;
            }
            String setCode = m.group(3) == null ? "" : m.group(3).trim();
            String cardNumber = m.group(4) == null ? "" : m.group(4).trim();
            String cardName = m.group(5).trim();
            if (!setCode.isEmpty() && !cardNumber.isEmpty()) {
                if (!codeResolves(setCode, cardNumber)) {
                    quarantine(debugName, "[" + setCode + ":" + cardNumber + "] " + cardName
                        + " not in this build's card DB (join-time 'Card not found')");
                    return;
                }
            } else {
                String lookup = cardName.contains("//")
                    ? SINGLE_SLASH.matcher(cardName).replaceFirst(" // ")
                    : cardName;
                if (!nameResolves(lookup)) {
                    quarantine(debugName, "card not in this build's card DB: " + cardName);
                    return;
                }
            }
        }
        decks.add(e);
    }

    private boolean codeResolves(String setCode, String cardNumber) {
        String key = setCode + ":" + cardNumber;
        return codeResolvesCache.computeIfAbsent(key, k -> {
            CardInfo ci = CardRepository.instance.findCard(setCode, cardNumber);
            return ci != null;
        });
    }

    private boolean nameResolves(String name) {
        return nameResolvesCache.computeIfAbsent(name, n -> {
            List<CardInfo> cards = CardRepository.instance.findCards(n);
            return !cards.isEmpty();
        });
    }

    private void quarantine(String deck, String reason) {
        quarantined.add(new Quarantine(deck, reason));
        System.err.println("QUARANTINED " + deck + " :: " + reason);
    }

    // ── text normalization ───────────────────────────────────────────────

    /**
     * Normalize a majors .txt into the paste-ready web form:
     *  - "A / B" split names → the DB's "A // B" (the web path has no importer-side fix);
     *  - trim trailing whitespace, normalize line endings, keep SB: lines.
     * Lines are otherwise untouched — parseDeckList validates them.
     */
    static String normalizePlain(String body) {
        StringBuilder sb = new StringBuilder();
        for (String raw : body.split("\r?\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("//")) continue;
            Matcher m = CARD_LINE.matcher(line);
            if (m.matches() && (m.group(3) == null || m.group(3).isEmpty())) {
                String name = m.group(5).trim();
                String fixed = SINGLE_SLASH.matcher(name).replaceFirst(" // ");
                if (!fixed.equals(name)) {
                    line = line.substring(0, line.lastIndexOf(name)) + fixed;
                }
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String header(DeckEntry e, String date) {
        StringBuilder sb = new StringBuilder();
        sb.append("NAME:").append(e.name);
        if (e.author != null && !e.author.isEmpty()) sb.append(" (").append(e.author).append(')');
        sb.append('\n');
        if (e.author != null && !e.author.isEmpty()) {
            sb.append("AUTHOR:").append(e.author).append('\n');
        }
        return sb.toString();
    }

    private static String baseName(Path p) {
        String fn = p.getFileName().toString();
        return fn.substring(0, fn.length() - 4);
    }

    private static String stripUnderscores(String s) {
        return s.replace('_', ' ');
    }

    // ── output ───────────────────────────────────────────────────────────

    private void write(Path out, Path majors, Path samples) throws IOException {
        Map<String, Integer> byGroup = new LinkedHashMap<>();
        for (DeckEntry d : decks) {
            byGroup.merge(d.group, 1, Integer::sum);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generated", java.time.Instant.now().toString());
        root.put("majorsCorpus", majors.toString());
        root.put("sampleDir", samples.toString());
        root.put("counts", Map.of("majors", decks.stream().filter(d -> "majors".equals(d.source)).count(),
                                  "sample", decks.stream().filter(d -> "sample".equals(d.source)).count()));
        root.put("groups", byGroup);
        root.put("decks", decks);
        Files.createDirectories(out.getParent());
        Files.writeString(out, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
        System.out.println("wrote " + out.toAbsolutePath() + " (" + sizeOf(out) + " bytes)");
        for (Map.Entry<String, Integer> e : byGroup.entrySet()) {
            System.out.println("  " + e.getKey() + ": " + e.getValue());
        }
    }
}