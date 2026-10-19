import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * SerialSaveScope — decides which classes can reach a {@code .game} stream and reports each one's
 * JVM-computed {@code serialVersionUID}. It is the oracle behind
 * {@code scripts/check-save-uid-manifest.sh} and {@code scripts/serial-uid-manifest.txt}.
 *
 * <pre>
 *   SerialSaveScope --reachable [--seed FILE] [--max-poly N]
 *       FQN \t UID            the deterministic save scope (what the checker recomputes)
 *
 *   SerialSaveScope --manifest --reference DIR [--seed FILE]
 *       FQN \t UID \t source   source = reached | subtype | observed
 *
 *   SerialSaveScope --stream-uids SAVE.game|DIR ...
 *       FQN \t UID            what a reference save actually carries in its stream
 * </pre>
 *
 * <h2>The scope rule</h2>
 *
 * <p>Seed with the objects {@code GameController.saveGame()} writes — a {@code GameImpl} and its
 * {@code GameStates} — plus every class named by {@code --seed} (the existing manifest, so the
 * scope can never shrink by accident). Walk the non-static, non-transient declared field types of
 * every class in scope, unwrapping array dimensions, generic type arguments and the superclass
 * chain. Where a class in scope is an interface or abstract, also pull in its concrete subtypes on
 * the classpath, but only when there are at most {@code --max-poly} of them (default 500). Repeat
 * until the scope stops growing: the result is a fixpoint, hence a deterministic function of the
 * tree plus the seed file.
 *
 * <p>The cap is what makes {@code Game}, {@code Player} and {@code Effect} expand (a handful of
 * implementations) while refusing {@code Card}, {@code Ability}, {@code Token} and
 * {@code MageObject} (~32k card classes). A refusal is printed on stderr and is a documented gap —
 * never a silent pass. What it means: classes reachable ONLY through a refused root are outside
 * the manifest, so a UID change in one of them is not caught here.
 *
 * <p>Classes with no source in this repo (JDK collections, arrays) are dropped: their UIDs belong
 * to the JDK and cannot be pinned here.
 *
 * <p>{@code --manifest} additionally ratifies classes that real reference saves carry
 * ({@code source = observed}) but the static walk cannot see. Those rows are pinned like any other,
 * so a later drift of them fails the checker.
 */
public class SerialSaveScope {

    /** The objects GameController.saveGame() writes, in order: game, game.getGameStates(). */
    private static final String[] ROOTS = {
        "mage.game.GameImpl",
        "mage.game.GameStates",
    };

    /** Refuse subtype expansion above this implementor count; the refusal is reported, not hidden. */
    private static int maxPoly = 500;

    private static final Set<String> REFUSED = new TreeSet<>();
    private static final Set<String> EXPANDED = new TreeSet<>();

    public static void main(String[] args) throws Exception {
        String mode = null;
        List<String> rest = new ArrayList<>();
        List<String> references = new ArrayList<>();
        String seedFile = null;
        String outFile = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--manifest", "--reachable", "--stream-uids" -> mode = args[i];
                case "--max-poly" -> maxPoly = Integer.parseInt(args[++i]);
                case "--seed" -> seedFile = args[++i];
                case "--reference" -> references.add(args[++i]);
                case "--out" -> outFile = args[++i];
                default -> rest.add(args[i]);
            }
        }
        if (mode == null) {
            System.err.println("usage: SerialSaveScope --reachable [--seed FILE] [--max-poly N]"
                    + " | --manifest --reference DIR [--seed FILE] | --stream-uids FILE|DIR..."
                    + "\n       [--out FILE]   write rows here instead of stdout (logging goes to stdout)");
            System.exit(2);
        }
        rest.addAll(references);
        ClassLoader cl = Thread.currentThread().getContextClassLoader();

        // The engine's log4j/reload4j console appender writes to stdout, which would corrupt a
        // caller that is capturing our rows. When --out is given, rows go to that file and are
        // never interleaved with log lines.
        PrintStream rows = System.out;
        if (outFile != null) {
            rows = new PrintStream(new FileOutputStream(outFile), false, "UTF-8");
        }

        switch (mode) {
            case "--reachable" -> {
                for (Class<?> c : scope(seedNames(seedFile), cl)) {
                    Long uid = uidOf(c);
                    if (uid != null && repoOwned(c.getName())) {
                        rows.println(c.getName() + "\t" + uid);
                    }
                }
            }
            case "--stream-uids" -> {
                TreeMap<String, Long> uids = new TreeMap<>();
                for (String s : rest) collectStreamUids(new File(s), uids);
                for (Map.Entry<String, Long> e : uids.entrySet()) {
                    rows.println(e.getKey() + "\t" + e.getValue());
                }
            }
            case "--manifest" -> manifest(seedNames(seedFile), references, cl, rows);
            default -> throw new IllegalStateException(mode);
        }
        rows.flush();
        if (outFile != null) rows.close();
        reportRefused();
    }

    /** Class names from a seed file (first column; '#' comments and blank lines ignored). */
    private static Set<String> seedNames(String seedFile) throws IOException {
        Set<String> out = new TreeSet<>();
        if (seedFile == null) return out;
        File f = new File(seedFile);
        if (!f.isFile()) {
            System.err.println("ERROR: seed file not found: " + seedFile);
            System.exit(2);
        }
        for (String line : java.nio.file.Files.readAllLines(f.toPath())) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            out.add(t.split("\t")[0].split("\\s+")[0]);
        }
        return out;
    }

    private static void reportRefused() {
        if (!REFUSED.isEmpty()) {
            System.err.println("WARN: refused subtype expansion (implementors > " + maxPoly + "): "
                    + REFUSED + " — classes reachable ONLY through these are NOT in the manifest"
                    + "");
        }
    }

    // ------------------------------------------------------------------- scope

    /**
     * The save-scope fixpoint: seeds = save roots + seedNames, then repeatedly add the field-type
     * closure and bounded subtypes until the size stops growing. Deterministic given the tree and
     * the seed file — which is what lets the checker recompute it and compare.
     */
    static Set<Class<?>> scope(Set<String> seedNames, ClassLoader cl) {
        Set<Class<?>> current = new LinkedHashSet<>();
        for (String r : ROOTS) current.add(loadOrDie(r, cl));
        for (String n : seedNames) {
            try {
                current.add(Class.forName(n, false, cl));
            } catch (Throwable t) {
                // A seed row that no longer resolves (class renamed/deleted): the checker reports it
                // as stale. Dropping it here must not shrink the walk, so the caller decides.
                System.err.println("NOTE: seed class not on this classpath (stale manifest row?): " + n);
            }
        }
        Set<String> expanded = new TreeSet<>();
        for (int iter = 0; iter < 32; iter++) {
            Set<Class<?>> next = new LinkedHashSet<>(current);
            for (Class<?> c : current) {
                for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                    for (Field f : k.getDeclaredFields()) {
                        int mod = f.getModifiers();
                        if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) continue;
                        // getGenericType, not getType: List<GameState> erases to List, and the
                        // erased type never leads to GameState.
                        for (Class<?> t : typesOf(f.getGenericType())) next.add(t);
                    }
                }
                if (c.isInterface() || Modifier.isAbstract(c.getModifiers())) {
                    List<Class<?>> subs = subtypesOf(c, cl);
                    if (subs.size() > maxPoly) {
                        REFUSED.add(c.getName() + "=" + subs.size());
                    } else {
                        if (!subs.isEmpty()) expanded.add(c.getName());
                        next.addAll(subs);
                    }
                }
            }
            boolean stable = next.size() == current.size();
            current = next;
            if (stable) break;
        }
        EXPANDED.addAll(expanded);
        return current;
    }

    private static Class<?> loadOrDie(String fqn, ClassLoader cl) {
        try {
            return Class.forName(fqn, false, cl);
        } catch (Throwable t) {
            System.err.println("ERROR: save root not on classpath: " + fqn + " (" + t + ")"
                    + " — build the gate modules first");
            System.exit(2);
            throw new IllegalStateException(); // unreachable
        }
    }

    static void manifest(Set<String> seedNames, List<String> references, ClassLoader cl,
            PrintStream rows) throws IOException {
        TreeMap<String, Long> reached = new TreeMap<>();
        for (Class<?> c : scope(seedNames, cl)) {
            if (!repoOwned(c.getName())) continue;
            Long uid = uidOf(c);
            if (uid == null) continue;
            reached.put(c.getName(), uid);
        }
        TreeMap<String, Long> observed = new TreeMap<>();
        for (String s : references) collectStreamUids(new File(s), observed);
        // A reference-observed class must be loadable here, or its pin can never be verified.
        for (String n : observed.keySet()) {
            try {
                Class.forName(n, false, cl);
            } catch (Throwable t) {
                System.err.println("ERROR: reference save names " + n
                        + " which is not on this classpath — it cannot be pinned or verified (" + t + ")");
                System.exit(2);
            }
        }
        for (Map.Entry<String, Long> e : observed.entrySet()) {
            reached.putIfAbsent(e.getKey(), e.getValue()); // ratified: pinned like any other row
        }
        for (Map.Entry<String, Long> e : reached.entrySet()) {
            String src = observed.containsKey(e.getKey()) ? "observed"
                    : EXPANDED.contains(e.getKey()) ? "subtype" : "reached";
            rows.println(e.getKey() + "\t" + e.getValue() + "\t" + src);
        }
        if (!observed.isEmpty()) {
            System.err.println("NOTE: " + observed.size() + " classes were observed in reference"
                    + " saves; the rows marked 'observed' are the ones the static walk cannot see");
        }
    }

    /** Repo-owned = has a pinnable source in this repo. Arrays and JDK classes do not. */
    static boolean repoOwned(String fqn) {
        return fqn.startsWith("mage.") && !fqn.startsWith("[");
    }

    private static Long uidOf(Class<?> c) {
        ObjectStreamClass osc = ObjectStreamClass.lookup(c);
        return osc == null ? null : osc.getSerialVersionUID();
    }

    // --------------------------------------------------------------- type walk

    /** Non-JDK classes named by a type: array components, generic arguments, wildcard/variable bounds. */
    static List<Class<?>> typesOf(Type t) {
        List<Class<?>> out = new ArrayList<>();
        collect(t, out, 0);
        return out;
    }

    private static void collect(Type t, List<Class<?>> out, int depth) {
        if (t == null || depth > 8) return;
        if (t instanceof Class<?> c) {
            if (c.isArray()) {
                collect(c.getComponentType(), out, depth + 1);
                return;
            }
            if (isJdk(c)) return;
            out.add(c);
            return;
        }
        if (t instanceof ParameterizedType p) {
            collect(p.getRawType(), out, depth + 1);
            for (Type a : p.getActualTypeArguments()) collect(a, out, depth + 1);
            return;
        }
        if (t instanceof GenericArrayType g) {
            collect(g.getGenericComponentType(), out, depth + 1);
            return;
        }
        if (t instanceof WildcardType w) {
            for (Type b : w.getUpperBounds()) collect(b, out, depth + 1);
            return;
        }
        if (t instanceof TypeVariable<?> v) {
            for (Type b : v.getBounds()) collect(b, out, depth + 1);
        }
    }

    private static boolean isJdk(Class<?> c) {
        if (c.isPrimitive()) return true;
        String n = c.getName();
        return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk.")
                || n.startsWith("sun.") || n.startsWith("com.sun.");
    }

    // ----------------------------------------------------------------- subtypes

    private static Set<String> classpathNames;

    /** Concrete repo-owned classes assignable to c (not c itself). Scans target/classes entries. */
    static List<Class<?>> subtypesOf(Class<?> c, ClassLoader cl) {
        List<Class<?>> cached = subtypeCache.get(c);
        if (cached != null) return cached;
        List<Class<?>> out = new ArrayList<>();
        for (Class<?> k : classpathClasses(cl)) {
            if (k == c || !repoOwned(k.getName())) continue;
            if (k.isInterface() || Modifier.isAbstract(k.getModifiers())) continue;
            if (c.isAssignableFrom(k)) out.add(k);
        }
        subtypeCache.put(c, out);
        return out;
    }

    private static final Map<Class<?>, List<Class<?>>> subtypeCache = new HashMap<>();

    private static List<Class<?>> classpathClasses(ClassLoader cl) {
        if (classpathNames == null) {
            classpathNames = new TreeSet<>();
            for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
                File f = new File(entry);
                if (!f.isDirectory() || !f.getName().equals("classes")) continue;
                scanDir(f, f, classpathNames);
            }
        }
        List<Class<?>> out = new ArrayList<>();
        for (String n : classpathNames) {
            try {
                out.add(Class.forName(n, false, cl));
            } catch (Throwable t) {
                // skip: optional dependency, not on this CP
            }
        }
        return out;
    }

    private static void scanDir(File root, File dir, Set<String> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) {
                scanDir(root, k, out);
            } else if (k.getName().endsWith(".class") && !k.getName().contains("$")) {
                String rel = root.toURI().relativize(k.toURI()).getPath();
                out.add(rel.substring(0, rel.length() - 6).replace('/', '.'));
            }
        }
    }

    // -------------------------------------------------------------- stream uids

    /** Class name -> stream UID, for every descriptor a .game carries (recursing into directories). */
    static void collectStreamUids(File f, Map<String, Long> out) throws IOException {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids == null) return;
            Arrays.sort(kids);
            for (File k : kids) {
                if (k.isFile() && k.getName().endsWith(".game")) collectStreamUids(k, out);
            }
            return;
        }
        if (!f.isFile()) {
            System.err.println("ERROR: no such reference save: " + f);
            System.exit(2);
        }
        try (InputStream fin = new FileInputStream(f)) {
            InputStream codec;
            try {
                codec = mage.server.game.ReplayCodec.wrapForRead(fin);
            } catch (IOException badCodec) {
                // Loud, named, non-fatal: a corpus scan must keep going, but a file it could not
                // read is never silently counted as evidence.
                System.err.println("SKIP: " + f.getName() + " — " + badCodec.getMessage());
                return;
            }
            ObjectInputStream ois = new ObjectInputStream(codec) {
                @Override
                protected Class<?> resolveClass(ObjectStreamClass desc) {
                    if (repoOwned(desc.getName())) {
                        out.putIfAbsent(desc.getName(), desc.getSerialVersionUID());
                    }
                    try {
                        return Class.forName(desc.getName(), false,
                                Thread.currentThread().getContextClassLoader());
                    } catch (Throwable t) {
                        return null; // keep descending: the descriptors are the evidence we want
                    }
                }
            };
            try {
                ois.readObject();
                ois.readObject();
            } catch (Throwable t) {
                // truncated read is expected for a save written by a different build: descriptors
                // collected so far are still valid evidence of what the format carried.
            }
        }
    }
}
