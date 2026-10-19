package mage.server.game;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * ReplayUidRecover — convert replay .game saves written by an OLDER build into
 * saves loadable by THIS build.
 *
 * <h2>Why this exists</h2>
 * Engine classes that reach a .game stream mostly carry no explicit
 * serialVersionUID, so every rebuild re-derives it. A save written by build X is
 * unreadable by build Y if any serialized class's UID changed — the corpus on the
 * public stack is split across at least three such generations and a stock reader
 * can read only the one its build produced.
 *
 * <h2>Mechanism</h2>
 * Java compares serialVersionUID during descriptor read, BEFORE any object is
 * constructed, so a subclass/shim cannot relabel it. This tool instead runs the
 * read inside an ObjectInputStream whose {@code resolveClass} hook forces each
 * drifted class's cached local descriptor (`ObjectStreamClass.suid`) to the value
 * the STREAM declares. The comparison then trivially agrees and the read proceeds.
 * The override is GENERIC: it accepts whatever UID each stream carries, so no
 * per-generation UID table is needed.
 *
 * <p>The recovered object graph is then re-serialized by a STOCK
 * ObjectOutputStream, which stamps the CURRENT (local) UIDs. The output therefore
 * loads on this build with no special reader — that is the conversion.
 *
 * <h2>Fail fast</h2>
 * A class whose descriptor cannot be resolved (missing from the classpath) is a
 * hard error naming the class; nothing is silently skipped. A conversion that does
 * not round-trip (re-read with a STOCK reader and a different state count) is a
 * hard error, and the target file is left untouched.
 *
 * <h2>Requires</h2>
 * {@code --add-opens java.base/java.io=ALL-UNNAMED} (see scripts/replay-uid-recover.sh).
 */
public final class ReplayUidRecover {

    private ReplayUidRecover() {}

    private static final Field SUID;
    private static final Method LOOKUP2;

    static {
        try {
            SUID = ObjectStreamClass.class.getDeclaredField("suid");
            SUID.setAccessible(true);
            LOOKUP2 = ObjectStreamClass.class.getDeclaredMethod("lookup", Class.class, boolean.class);
            LOOKUP2.setAccessible(true);
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "ReplayUidRecover requires --add-opens java.base/java.io=ALL-UNNAMED", t);
        }
    }

    /** Result of one conversion. */
    public static final class Result {
        public final String id;
        public final boolean converted;      // false = already loadable, no rewrite needed
        public final int states;
        public final Map<String, long[]> drift = new LinkedHashMap<>(); // class -> {streamUid, localUid}
        public final String error;
        Result(String id, boolean converted, int states, String error) {
            this.id = id; this.converted = converted; this.states = states; this.error = error;
        }
        public boolean ok() { return error == null; }
    }

    /** True when the stream declares a UID this build did not compute. */
    private static boolean driftSeen;

    /**
     * Reads {@code src} tolerating UID drift and writes a converted copy to
     * {@code dst}. Returns per-class drift details. Throws on any unrecoverable
     * condition (Fail Fast) — the caller must not treat a throw as "nothing to do".
     */
    public static Result convert(File src, File dst) throws Exception {
        String id = stripExt(src.getName());
        Map<String, long[]> drift = new LinkedHashMap<>();

        Object game, gameStates;
        // The read-time UID override mutates the JVM's CACHED ObjectStreamClass for each
        // drifted class. That cache is process-global, so it must be restored the moment
        // the read finishes — otherwise (a) the stock writer below would stamp the OLD
        // UIDs onto the output, and (b) later files in the same run would be read against
        // a contaminated cache and falsely reported as already-loadable.
        Map<ObjectStreamClass, Long> saved = new LinkedHashMap<>();
        try (InputStream fin = new BufferedInputStream(new FileInputStream(src));
             InputStream codec = ReplayCodec.wrapForRead(fin);
             ObjectInputStream in = new ObjectInputStream(codec) {

                 @Override
                 protected Class<?> resolveClass(ObjectStreamClass desc)
                         throws IOException, ClassNotFoundException {
                     Class<?> c;
                     try {
                         c = Class.forName(desc.getName(), false,
                                 Thread.currentThread().getContextClassLoader());
                     } catch (ClassNotFoundException e) {
                         // Fail Fast: name the class and its stream UID, do not skip it.
                         throw new InvalidClassException(desc.getName(),
                                 "class not on this build's classpath (stream uid="
                                         + desc.getSerialVersionUID()
                                         + "); the save was written by an incompatible build");
                     }
                     ObjectStreamClass local = lookupLocal(c);
                     if (local != null) {
                         long su = desc.getSerialVersionUID();
                         long lu = local.getSerialVersionUID();
                         if (su != lu) {
                             drift.put(desc.getName(), new long[]{su, lu});
                             saved.put(local, lu);       // remember the true value first
                             forceSuid(local, su);
                         }
                     }
                     return c;
                 }
             }) {
            game = in.readObject();
            gameStates = in.readObject();
        } finally {
            // Always restore, even if the read threw — never leave the cache contaminated.
            for (Map.Entry<ObjectStreamClass, Long> e : saved.entrySet()) {
                try { SUID.set(e.getKey(), e.getValue()); }
                catch (ReflectiveOperationException restoreFailure) {
                    throw new IllegalStateException(
                            "could not restore serialVersionUID for " + e.getKey().getName()
                                    + " — the JVM descriptor cache is contaminated for this process",
                            restoreFailure);
                }
            }
        }

        int states = stateCount(gameStates);

        // Already loadable by this build? then this is a NO-OP (identity).
        if (drift.isEmpty() && stockStateCountByPath(src) > 0) {
            Result r = new Result(id, false, states, null);
            r.drift.putAll(drift);
            return r;
        }

        // Re-serialize with a STOCK writer so the output carries THIS build's UIDs.
        File tmp = File.createTempFile(dst.getName() + ".", ".tmp", dst.getParentFile());
        try (OutputStream fout = new BufferedOutputStream(new FileOutputStream(tmp));
             OutputStream codec = ReplayCodec.wrapForWrite(fout);
             ObjectOutputStream out = new ObjectOutputStream(codec)) {
            out.writeObject(game);
            out.writeObject(gameStates);
        }

        // Round-trip gate: the output MUST load through the stock production reader
        // with the same state count, else we refuse to replace anything.
        int check = stockStateCountByPath(tmp);
        if (check != states) {
            tmp.delete();
            throw new IllegalStateException("round-trip failed for " + id
                    + ": source states=" + states + " converted states=" + check
                    + " — refusing to write " + dst);
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            throw new IllegalStateException("could not move converted replay into place: " + dst);
        }
        Result r = new Result(id, true, states, null);
        r.drift.putAll(drift);
        return r;
    }

    /** Loads with the production reader (mage.server.game.GameReplay) and returns the state count, or -1. */
    private static int stockStateCount(File f) {
        try {
            // GameReplay resolves "saved/<id>.game" relative to CWD — caller must chdir.
            GameReplay replay = new GameReplay(java.util.UUID.fromString(stripExt(f.getName())));
            return replay.getGame() == null ? -1 : stateCount(replay.getGame().getGameStates());
        } catch (Throwable t) {
            return -1;
        }
    }

    /** File-level round-trip gate — reads the explicit path with a STOCK reader. */
    private static int stockStateCountByPath(File f) {
        try (InputStream fin = new BufferedInputStream(new FileInputStream(f));
             InputStream codec = ReplayCodec.wrapForRead(fin);
             ObjectInputStream in = new ObjectInputStream(codec)) {
            in.readObject();
            Object gameStates = in.readObject();
            return stateCount(gameStates);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int stateCount(Object gameStates) {
        try {
            return (Integer) gameStates.getClass().getMethod("getSize").invoke(gameStates);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("GameStates.getSize() not callable on "
                    + gameStates.getClass().getName(), e);
        }
    }

    private static ObjectStreamClass lookupLocal(Class<?> c) {
        try { return (ObjectStreamClass) LOOKUP2.invoke(null, c, true); }
        catch (Throwable t) { return null; }
    }

    private static void forceSuid(ObjectStreamClass osc, long v) {
        try {
            SUID.set(osc, Long.valueOf(v));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot set serialVersionUID on "
                    + osc.getName() + " to " + v, e);
        }
    }

    private static String stripExt(String n) {
        return n.endsWith(".game") ? n.substring(0, n.length() - 5) : n;
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: ReplayUidRecover [--in-place] <file-or-dir>...");
            System.err.println("  Converts replay .game saves written by older builds so this build can load them.");
            System.err.println("  Run from a directory whose parent contains saved/ if you need the stock-reader gate.");
            System.exit(2);
        }
        boolean inPlace = false;
        List<File> targets = new ArrayList<>();
        for (String a : args) {
            if (a.equals("--in-place")) { inPlace = true; continue; }
            File f = new File(a);
            if (f.isDirectory()) {
                File[] g = f.listFiles((d, n) -> n.endsWith(".game"));
                if (g != null) targets.addAll(Arrays.asList(g));
            } else {
                targets.add(f);
            }
        }
        int converted = 0, identity = 0, failed = 0;
        for (File f : targets) {
            try {
                File out = inPlace ? f : new File(f.getParentFile(), stripExt(f.getName()) + ".recovered.game");
                Result r = convert(f, out);
                if (!r.ok()) {
                    System.out.println("FAIL " + r.id + ": " + r.error);
                    failed++;
                } else if (r.converted) {
                    System.out.println("CONV " + r.id + " states=" + r.states
                            + " drifted=" + r.drift.size() + " -> " + out.getName());
                    for (Map.Entry<String, long[]> e : r.drift.entrySet()) {
                        System.out.println("       " + e.getKey()
                                + " stream=" + e.getValue()[0] + " local=" + e.getValue()[1]);
                    }
                    converted++;
                } else {
                    System.out.println("SKIP " + r.id + " (already loadable, states=" + r.states + ")");
                    identity++;
                }
            } catch (Throwable t) {
                System.out.println("FAIL " + f.getName() + ": " + t);
                failed++;
            }
        }
        System.out.println("summary: converted=" + converted + " identity=" + identity + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
