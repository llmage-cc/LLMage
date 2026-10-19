#!/usr/bin/env bash
# Compile + run the standalone assertions for the two proxy-side fixes of the
# 2026-09-14 lobby/proxy batch:
#   (A) TableWatchDecision — the push-on-change core of the hidden-tab table watch

#   (B) StartGameJoinGate  — one join+chat per game session on duplicate START_GAME

#
# Both classes are deliberately dependency-free, so this harness needs only the JDK —
# no Maven, no XMage classpath, no running server.
#
# Run with:  bash Mage.WebProxy/scripts/verify-lobby-proxy-units.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../src/main/java/mage/webproxy"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

cp "$SRC/TableWatchDecision.java" "$SRC/StartGameJoinGate.java" "$OUT/"
cat > "$OUT/VerifyLobbyProxyUnits.java" <<'EOF'
package mage.webproxy;

import java.util.UUID;

/**
 * Standalone assertions for TableWatchDecision + StartGameJoinGate.
 * No JUnit dependency: a missing test framework must not be able to silence these.
 */
public final class VerifyLobbyProxyUnits {

    private static int failures = 0;

    private static void check(String name, boolean cond) {
        check(name, cond, "");
    }

    private static void check(String name, boolean cond, String detail) {
        if (cond) {
            System.out.println("  PASS  " + name);
        } else {
            failures++;
            System.out.println("  FAIL  " + name + " " + detail);
        }
    }

    public static void main(String[] args) {
        // ─────────────────────────────────────────────────────────────────────
        System.out.println("\n## (A) TableWatchDecision — when the watch pushes the table list");
        // ─────────────────────────────────────────────────────────────────────

        // First tick (nothing pushed yet): always push, so a user who just sat down
        // learns the current seat state without asking.
        check("first tick with a waiting table pushes",
                TableWatchDecision.decide(null, "1/2|WAITING", "WAITING")
                        == TableWatchDecision.Action.PUSH_CONTINUE);

        // THE DEFECT: the joiner fills the seat. This state change is what makes the
        // owner's Start Match button exist; it must be pushed immediately.
        check("seat filled 1/2 -> 2/2 pushes",
                TableWatchDecision.decide("1/2|WAITING", "2/2|READY_TO_START", "READY_TO_START")
                        == TableWatchDecision.Action.PUSH_CONTINUE);
        check("2/2 WAITING -> READY_TO_START pushes",
                TableWatchDecision.decide("2/2|WAITING", "2/2|READY_TO_START", "READY_TO_START")
                        == TableWatchDecision.Action.PUSH_CONTINUE);

        // Unchanged state must NOT churn the browser every 2 s.
        check("unchanged 2/2 READY_TO_START is skipped",
                TableWatchDecision.decide("2/2|READY_TO_START", "2/2|READY_TO_START", "READY_TO_START")
                        == TableWatchDecision.Action.SKIP);
        check("unchanged 1/2 WAITING is skipped",
                TableWatchDecision.decide("1/2|WAITING", "1/2|WAITING", "WAITING")
                        == TableWatchDecision.Action.SKIP);

        // A player leaving must be pushed as well (the owner loses the button).
        check("seat emptied 2/2 -> 1/2 pushes",
                TableWatchDecision.decide("2/2|READY_TO_START", "1/2|WAITING", "WAITING")
                        == TableWatchDecision.Action.PUSH_CONTINUE);

        // Once the match starts the watch pushes the final state and stops itself —
        // the waiting view has nothing left to report.
        check("STARTING pushes then stops",
                TableWatchDecision.decide("2/2|READY_TO_START", "2/2|STARTING", "STARTING")
                        == TableWatchDecision.Action.PUSH_STOP);
        check("DUELING pushes then stops",
                TableWatchDecision.decide("2/2|STARTING", "2/2|DUELING", "DUELING")
                        == TableWatchDecision.Action.PUSH_STOP);
        check("FINISHED pushes then stops",
                TableWatchDecision.decide("2/2|DUELING", "2/2|FINISHED", "FINISHED")
                        == TableWatchDecision.Action.PUSH_STOP);

        check("only WAITING/READY_TO_START are waiting states",
                TableWatchDecision.isWaitingState("WAITING")
                        && TableWatchDecision.isWaitingState("READY_TO_START")
                        && !TableWatchDecision.isWaitingState("STARTING")
                        && !TableWatchDecision.isWaitingState("DUELING")
                        && !TableWatchDecision.isWaitingState("SIDEBOARDING")
                        && !TableWatchDecision.isWaitingState("CONSTRUCTING")
                        && !TableWatchDecision.isWaitingState("DRAFTING")
                        && !TableWatchDecision.isWaitingState("FINISHED"));

        // Miss tolerance (2026-09-17, found live in testing): the watch is armed at
        // create time and the creator's own seat join lands ms later on another thread, so the
        // FIRST tick can legitimately read a list without the new table. One miss used to stop
        // the watch for good, which left the owner with no server-side push — the exact
        // hidden-tab bug the watch exists to fix. A miss must therefore only stop the watch
        // after MISS_TOLERANCE consecutive misses.
        check("a single miss does NOT stop the watch (the create/auto-join race)",
                !TableWatchDecision.shouldStopAfterMiss(1));
        check("misses below the tolerance keep the watch alive",
                !TableWatchDecision.shouldStopAfterMiss(TableWatchDecision.MISS_TOLERANCE - 1));
        check("the tolerance-th miss does stop the watch (table really is gone)",
                TableWatchDecision.shouldStopAfterMiss(TableWatchDecision.MISS_TOLERANCE));
        check("misses beyond the tolerance also stop the watch",
                TableWatchDecision.shouldStopAfterMiss(TableWatchDecision.MISS_TOLERANCE + 5));
        boolean threwOnZeroMiss = false;
        try {
            TableWatchDecision.shouldStopAfterMiss(0);
        } catch (IllegalArgumentException e) {
            threwOnZeroMiss = true;
        }
        check("a 0-miss call fails fast (this call must count the miss just read)", threwOnZeroMiss);

        // Fail Fast: a null read is a bug, not a reason to silently skip.
        boolean threwOnNullSig = false;
        try {
            TableWatchDecision.decide(null, null, "WAITING");
        } catch (IllegalArgumentException e) {
            threwOnNullSig = true;
        }
        check("null current signature fails fast", threwOnNullSig);

        // ─────────────────────────────────────────────────────────────────────
        System.out.println("\n## (B) StartGameJoinGate — one join+chat per game session");
        // ─────────────────────────────────────────────────────────────────────

        StartGameJoinGate gate = new StartGameJoinGate();
        UUID game1 = UUID.fromString("fe1ef2b2-1581-494d-b002-6246b8e82b4c");
        UUID game2 = UUID.fromString("fbb390e8-e4d0-493d-9b90-db2a9696e6f7");

        check("first START_GAME for a game joins",
                gate.shouldJoin(game1));
        check("duplicate START_GAME for the SAME game does not re-join",
                !gate.shouldJoin(game1));
        check("a third duplicate also does not re-join",
                !gate.shouldJoin(game1));
        // A Bo3's next game has a new gameId AND a new game chat, so it must join.
        check("a NEW gameId (Bo3 game 2) joins",
                gate.shouldJoin(game2));
        check("duplicate of game 2 does not re-join",
                !gate.shouldJoin(game2));
        // Back to game 1 is a genuinely new join request for a session that has since
        // moved on — the gate is "last game seen", not a permanent blocklist.
        check("re-joining an earlier gameId is treated as new work",
                gate.shouldJoin(game1));

        // Live evidence shape: 15 reload/reconnect cycles produced 15 START_GAME
        // callbacks and 15 "Joined game chat" lines for ONE gameId (/tmp/xmage-proxy.log).
        // With the gate, only the first of those joins.
        StartGameJoinGate replay = new StartGameJoinGate();
        int joins = 0;
        for (int i = 0; i < 15; i++) {
            if (replay.shouldJoin(game1)) {
                joins++;
            }
        }
        check("15 replayed START_GAME callbacks for one game -> exactly 1 join",
                joins == 1, "joins=" + joins);

        // Fail Fast: an unkeyable callback must not silently re-open the duplicate-join hole.
        boolean threwOnNullGame = false;
        try {
            new StartGameJoinGate().shouldJoin(null);
        } catch (IllegalArgumentException e) {
            threwOnNullGame = true;
        }
        check("null gameId fails fast", threwOnNullGame);

        // Concurrent duplicates (JBoss callback threads) must still yield one join.
        final StartGameJoinGate concurrent = new StartGameJoinGate();
        final java.util.concurrent.atomic.AtomicInteger concurrentJoins =
                new java.util.concurrent.atomic.AtomicInteger(0);
        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                if (concurrent.shouldJoin(game1)) {
                    concurrentJoins.incrementAndGet();
                }
            });
        }
        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        check("8 concurrent START_GAME callbacks -> exactly 1 join",
                concurrentJoins.get() == 1, "joins=" + concurrentJoins.get());

        System.out.println("\n" + (failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED"));
        System.exit(failures == 0 ? 0 : 1);
    }
}
EOF

javac -d "$OUT/classes" "$OUT/TableWatchDecision.java" "$OUT/StartGameJoinGate.java" "$OUT/VerifyLobbyProxyUnits.java"
java -cp "$OUT/classes" mage.webproxy.VerifyLobbyProxyUnits
