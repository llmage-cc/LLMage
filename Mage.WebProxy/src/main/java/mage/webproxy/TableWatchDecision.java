package mage.webproxy;

/**
 * Pure decision core of the proxy's table-state watch.

 *
 * <p>The watch exists because the seated owner's "Start Match" button used to depend on a page
 * timer that Chromium throttles in hidden tabs (measured 41 s and 58 s of owner-side lag).
 * The proxy now re-reads the watched table server-side and pushes a {@code table_list} to the
 * browser when — and only when — its seats/state actually changed; a WebSocket message is not
 * a timer and is therefore not throttled.
 *
 * <p>This class is the part of that logic that is worth asserting mechanically: when to push,
 * when to stay quiet, and when the watch has outlived its purpose. It is deliberately
 * dependency-free (no TableView, no session) so it can be compiled and exercised on its own —
 * see {@code Mage.WebProxy/scripts/verify-lobby-proxy-units.sh}.
 */
final class TableWatchDecision {

    private TableWatchDecision() {
    }

    enum Action {
        /** Nothing changed since the last push — stay quiet (no browser churn). */
        SKIP,
        /** Push the fresh table list and keep watching. */
        PUSH_CONTINUE,
        /** Push the fresh table list, then stop: the table has left the waiting state. */
        PUSH_STOP
    }

    /**
     * Consecutive misses tolerated before the watch concludes the table is really gone.
     *
     * <p>A single miss is NOT proof: {@code startTableWatch} arms the watch the instant a table
     * is created, and the creator's own seat-0 join lands a few ms later on another thread — so
     * the first tick can legitimately read a table list that does not contain the new table yet.
     * Measured live 2026-09-17: the first tick missed by 66 ms
     * and the watch stopped itself, leaving the owner with no server-side push at all — i.e. the
     * exact hidden-tab bug the watch exists to fix. Three misses at 2 s intervals is long enough
     * to ride out that race and still shut down promptly on a genuinely deleted table.
     */
    static final int MISS_TOLERANCE = 3;

    /**
     * @param previousSignature signature of the last pushed state, or null when nothing has
     *                          been pushed yet (first tick — always push, so a freshly seated
     *                          user learns the current seat count without asking).
     * @param currentSignature  signature of the state just read from the server.
     * @param stateName         the table's state name just read from the server.
     */
    static Action decide(String previousSignature, String currentSignature, String stateName) {
        if (currentSignature == null) {
            throw new IllegalArgumentException("currentSignature is null — the watch read no table state");
        }
        if (stateName == null) {
            throw new IllegalArgumentException("stateName is null — the watch read no table state");
        }
        if (currentSignature.equals(previousSignature)) {
            return Action.SKIP;
        }
        return isWaitingState(stateName) ? Action.PUSH_CONTINUE : Action.PUSH_STOP;
    }

    /**
     * True while the table is still waiting for its seats to fill — the only states in which
     * the watch has anything to report. Every other state (STARTING/DUELING/SIDEBOARDING/…)
     * means the match has begun or the table is finished, so the watch shuts itself down.
     */
    static boolean isWaitingState(String stateName) {
        return "WAITING".equals(stateName) || "READY_TO_START".equals(stateName);
    }

    /**
     * Whether the watch should give up after reading a table list that does not contain the
     * watched table. Returns true only once {@link #MISS_TOLERANCE} consecutive misses have
     * accumulated, so a transient miss (the create/auto-join race) does not kill the watch.
     *
     * @param consecutiveMisses misses counted so far, INCLUDING this one
     */
    static boolean shouldStopAfterMiss(int consecutiveMisses) {
        if (consecutiveMisses < 1) {
            throw new IllegalArgumentException(
                    "consecutiveMisses must be >= 1 (this call counts the miss just read)");
        }
        return consecutiveMisses >= MISS_TOLERANCE;
    }
}
