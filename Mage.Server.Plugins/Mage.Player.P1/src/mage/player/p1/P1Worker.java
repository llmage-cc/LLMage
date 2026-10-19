package mage.player.p1;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One long-lived, lock-step bridge to the Argentum-P1 Python policy worker
 * ({@code python -m argentum_ml.p1.serve --checkpoint <dir>}).
 * <p>
 * Protocol: the child prints exactly one
 * {@code {"ready": "<ckpt name>"}} line on stdout at startup, then reads one JSON request line
 * and writes one JSON response line per decision. I/O is strictly lock-step (write one line,
 * flush, read exactly one line) — pipelining is the deadlock source.
 * <p>
 * stderr is drained on its own daemon thread, bound at creation to the one process it drains
 * (see {@link #drainStderr(Process)}); the worker emits benign warnings
 * (e.g. {@code UserWarning: Failed to initialize NumPy}) and a full pipe would block the child.
 * <p>
 * Configuration is environment-only (the plugin classloader cannot take constructor params):
 * <pre>
 *   P1_PYTHON            python interpreter (venv with torch + safetensors)
 *   P1_PKG_DIR           cwd for `-m argentum_ml.p1.serve` (the module root containing argentum_ml/)
 *   P1_CHECKPOINT        checkpoint directory
 *   P1_DEVICE            torch device (default cpu)
 *   P1_THREADS           torch threads (default 1)
 *   P1_STARTUP_TIMEOUT_MS  ready-line timeout (default 60000)
 *   P1_DECISION_TIMEOUT_MS per-decision read timeout (default 2000; first decision gets 15000)
 *   P1_EVIDENCE_LOG      optional file to append raw request/response pairs to (JSONL)
 *   P1_WORKER_LOG        optional file the child's stderr is tee'd to
 * </pre>
 */
public final class P1Worker implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(P1Worker.class);
    private static final Gson GSON = new Gson();

    private final String name;
    private final String python;
    private final String pkgDir;
    private final String checkpoint;
    private final String device;
    private final int threads;
    private final long startupTimeoutMs;
    private final long decisionTimeoutMs;
    private final long firstDecisionTimeoutMs;
    private final File evidenceLog;

    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private Thread stderrDrain;

    /**
     * Set once by {@link #close()}. A closed worker is terminal: it will not start again, so a
     * reaped worker can never reseed a fresh Python process behind a finished game's back. Read by
     * {@link #isClosed()} so the game-end reaper and its tests can observe the close.
     */
    private volatile boolean closed = false;

    // Stats for the run report.
    private int decisions = 0;
    private int errors = 0;
    private int restarts = 0;
    private int passes = 0;
    private long totalDecisionNanos = 0;

    public P1Worker(String name) {
        this.name = name;
        this.python = env("P1_PYTHON", "python3");
        this.pkgDir = env("P1_PKG_DIR", "argentum-p1/pkg/ml/src");
        this.checkpoint = env("P1_CHECKPOINT", "argentum-p1/ckpt");
        this.device = env("P1_DEVICE", "cpu");
        this.threads = parseInt(env("P1_THREADS", "1"), 1);
        this.startupTimeoutMs = parseLong(env("P1_STARTUP_TIMEOUT_MS", "60000"), 60000L);
        this.decisionTimeoutMs = parseLong(env("P1_DECISION_TIMEOUT_MS", "2000"), 2000L);
        this.firstDecisionTimeoutMs = Math.max(decisionTimeoutMs, 15000L);
        String ev = env("P1_EVIDENCE_LOG", "");
        this.evidenceLog = ev.isEmpty() ? null : new File(ev);
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    private static int parseInt(String v, int fallback) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long parseLong(String v, long fallback) {
        try {
            return Long.parseLong(v.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    int getDecisions() {
        return decisions;
    }

    int getErrors() {
        return errors;
    }

    int getRestarts() {
        return restarts;
    }

    long getTotalDecisionNanos() {
        return totalDecisionNanos;
    }

    /** Start the child and block on the ready line. Returns true on success. */
    public synchronized boolean start() {
        if (closed) {
            // A reaped worker is terminal — never reseed a Python process after close().
            logger.warn("[P1:" + name + "] start() refused: worker already closed");
            return false;
        }
        if (process != null && process.isAlive()) {
            return true;
        }
        stopQuietly();
        List<String> argv = new ArrayList<>();
        argv.add(python);
        argv.add("-m");
        argv.add("argentum_ml.p1.serve");
        argv.add("--checkpoint");
        argv.add(checkpoint);
        argv.add("--device");
        argv.add(device);
        argv.add("--threads");
        argv.add(Integer.toString(threads));
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(new File(pkgDir));
            pb.redirectErrorStream(false);
            logger.info("[P1:" + name + "] starting worker: " + String.join(" ", argv)
                    + " (cwd=" + pkgDir + ")");
            Process child = pb.start();
            process = child;
            stdin = new BufferedWriter(new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8));
            stdout = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
            stderrDrain = new Thread(() -> drainStderr(child), "p1-worker-stderr-" + name);
            stderrDrain.setDaemon(true);
            stderrDrain.start();

            long deadline = System.currentTimeMillis() + startupTimeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (stdout.ready()) {
                    String line = stdout.readLine();
                    if (line == null) {
                        logger.error("[P1:" + name + "] worker stdout EOF before ready line");
                        stopQuietly();
                        return false;
                    }
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    logger.info("[P1:" + name + "] worker ready line: " + line.trim());
                    try {
                        JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
                        if (obj.has("ready")) {
                            return true;
                        }
                    } catch (Exception ignore) {
                        // fall through and keep waiting
                    }
                } else {
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
            logger.error("[P1:" + name + "] no ready line within " + startupTimeoutMs + " ms");
            stopQuietly();
            return false;
        } catch (Exception e) {
            logger.error("[P1:" + name + "] worker start failed: " + e, e);
            stopQuietly();
            return false;
        }
    }

    /**
     * Drain one worker process's stderr until EOF.
     * <p>
     * Binding contract: the thread is bound for its whole lifetime to the single {@code child}
     * process passed in at creation time and must never read the mutable {@link #process} field —
     * after a restart ({@code decide()} → {@code stopQuietly()} → {@code start()}) that field
     * either is null or already points at the NEW process, whose stderr has its own drain thread.
     * The thread terminates when the child's stderr reaches EOF, which {@code stopQuietly()} /
     * {@code close()} provoke by destroying the process (and closing stdin); a daemon thread plus
     * process EOF is the exit path, so nothing joins or interrupts it.
     * <p>
     * Each invocation opens and closes its own {@code P1_WORKER_LOG} sink in append mode, so the
     * old and new drain threads never share a file handle.
     */
    private void drainStderr(Process child) {
        String target = env("P1_WORKER_LOG", "");
        FileWriter sink = null;
        try {
            if (!target.isEmpty()) {
                sink = new FileWriter(target, true);
            }
        } catch (IOException e) {
            sink = null;
        }
        try (BufferedReader err = new BufferedReader(
                new InputStreamReader(child.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            int seen = 0;
            while ((line = err.readLine()) != null) {
                if (seen < 40) {
                    logger.info("[P1:" + name + " stderr] " + line);
                }
                seen++;
                if (sink != null) {
                    sink.write(line);
                    sink.write('\n');
                }
            }
        } catch (Exception e) {
            logger.warn("[P1:" + name + "] stderr drain ended with error: " + e);
        } finally {
            if (sink != null) {
                try {
                    sink.close();
                } catch (IOException ignore) {
                    // nothing to do
                }
            }
        }
    }

    private synchronized void stopQuietly() {
        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (Exception ignore) {
            // best effort
        }
        if (process != null) {
            try {
                if (!process.waitFor(300, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (Exception ignore) {
                process.destroyForcibly();
            }
        }
        process = null;
        stdin = null;
        stdout = null;
    }

    /**
     * One decision. Returns the raw response object, or null when the worker could not answer
     * (the caller must then pass priority). On transport failure the worker is restarted once and
     * the decision retried on the fresh process.
     */
    public synchronized JsonObject decide(JsonObject request, boolean allowRestart) {
        JsonObject reply = decideOnce(request);
        if (reply == null && allowRestart) {
            restarts++;
            logger.warn("[P1:" + name + "] restarting worker after transport failure");
            stopQuietly();
            if (start()) {
                reply = decideOnce(request);
            }
        }
        return reply;
    }

    private JsonObject decideOnce(JsonObject request) {
        if (process == null || !process.isAlive()) {
            if (!start()) {
                return null;
            }
        }
        long timeout = (decisions == 0) ? firstDecisionTimeoutMs : decisionTimeoutMs;
        String reqLine = GSON.toJson(request);
        long t0 = System.nanoTime();
        try {
            stdin.write(reqLine);
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            logger.error("[P1:" + name + "] write to worker failed: " + e);
            errors++;
            return null;
        }
        long deadline = System.currentTimeMillis() + timeout;
        String line = null;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (stdout.ready()) {
                    line = stdout.readLine();
                    break;
                }
                Thread.sleep(2);
            }
        } catch (Exception e) {
            logger.error("[P1:" + name + "] read from worker failed: " + e);
            errors++;
            return null;
        }
        if (line == null) {
            logger.error("[P1:" + name + "] no response within " + timeout + " ms");
            errors++;
            return null;
        }
        long elapsed = System.nanoTime() - t0;
        totalDecisionNanos += elapsed;
        double ms = elapsed / 1_000_000.0;
        JsonObject reply;
        try {
            reply = JsonParser.parseString(line).getAsJsonObject();
        } catch (Exception e) {
            logger.error("[P1:" + name + "] unparseable response line: " + line);
            errors++;
            return null;
        }
        decisions++;
        if (reply.has("error")) {
            errors++;
            logger.warn("[P1:" + name + "] worker error: " + reply.get("error").getAsString());
        }
        appendEvidence(reqLine, line, ms);
        logger.info(String.format("[P1:%s] decision %d: %.2f ms -> %s", name, decisions, ms, line));
        return reply;
    }

    private void appendEvidence(String request, String response, double ms) {
        if (evidenceLog == null) {
            return;
        }
        try {
            if (!evidenceLog.getParentFile().exists()) {
                evidenceLog.getParentFile().mkdirs();
            }
            try (FileWriter fw = new FileWriter(evidenceLog, true)) {
                JsonObject rec = new JsonObject();
                rec.addProperty("seat", name);
                rec.addProperty("decision", decisions);
                rec.addProperty("ms", ms);
                rec.add("request", JsonParser.parseString(request));
                rec.add("response", JsonParser.parseString(response));
                fw.write(GSON.toJson(rec));
                fw.write('\n');
            }
        } catch (Exception e) {
            logger.warn("[P1:" + name + "] evidence log write failed: " + e.getMessage());
        }
    }

    /**
     * Extract the chosen candidate index from a worker reply. Returns -1 when the reply carries no
     * usable choice (error object, missing/out-of-range index).
     */
    public static int chosenIndex(JsonObject reply, int candidateCount) {
        if (reply == null || reply.has("error") || !reply.has("chosen")) {
            return -1;
        }
        JsonElement c = reply.get("chosen");
        if (c == null || c.isJsonNull()) {
            return -1;
        }
        int idx;
        try {
            idx = c.getAsInt();
        } catch (Exception e) {
            return -1;
        }
        return (idx >= 0 && idx < candidateCount) ? idx : -1;
    }

    /**
     * Close the worker: destroy the Python process and release its streams. Idempotent and
     * thread-safe — a second call is a no-op, so the game-end reaper (and the engine's internal
     * restart path) can call it from any callback thread without a double-destroy.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return; // idempotent: reached from several game-end hooks / callback threads
        }
        closed = true;
        logger.info("[P1:" + name + "] closing worker (decisions=" + decisions
                + ", errors=" + errors + ", restarts=" + restarts + ")");
        stopQuietly();
    }

    /** True once {@link #close()} has run. A closed worker never starts again. */
    public boolean isClosed() {
        return closed;
    }

    /** Convenience: build the top-level request object for one decision. */
    public JsonObject request(JsonObject sample, JsonArray allowed) {
        JsonObject req = new JsonObject();
        req.add("sample", sample);
        if (allowed != null) {
            req.add("allowed", allowed);
        }
        return req;
    }
}
