
import java.io.ObjectStreamClass;
import java.util.ArrayList;
import java.util.List;

/** Reads class names on stdin (dotted form), prints those that ARE Serializable per the JVM's own
 *  ObjectStreamClass oracle (transitive hierarchies resolved from target/classes). Used by
 *  scripts/check-serial-uid-drift.sh. Build: javac SerialOracle.java; usage: java -cp <cp>:. SerialOracle */
public class SerialOracle {
    public static void main(String[] args) throws Exception {
        List<String> names = new ArrayList<>();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) names.add(line);
            }
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (String n : names) {
            try {
                Class<?> c = Class.forName(n, false, cl);
                ObjectStreamClass osc = ObjectStreamClass.lookup(c);
                if (osc != null && java.io.Serializable.class.isAssignableFrom(c)) {
                    System.out.println(n + " " + osc.getSerialVersionUID());
                }
            } catch (Throwable t) { /* not on classpath; skip */ }
        }
    }
}
