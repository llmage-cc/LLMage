package org.mage.test.game;

import java.util.List;
import mage.game.GameState;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for the replay game-log capture on {@link GameState}

 *
 * Two properties are load-bearing for the replay viewer's Game Log tab:
 * <ul>
 *   <li><b>Flat capture:</b> a per-frame snapshot carries only the entry COUNT
 *       (the list itself lives on the live state / the save's top-level state),
 *       so a 401-state replay does not duplicate 2.5 MB of log text to deliver
 *       15 KB of distinct content.</li>
 *   <li><b>Truncation ledger:</b> rollback/undo truncate the log, so frames
 *       taken BEFORE the truncation must be capped by later keep-targets — never
 *       shown lines that a re-played timeline replaced.</li>
 * </ul>
 */
public class GameLogCaptureTest {

    private static GameState liveLog(String... entries) {
        GameState state = new GameState();
        state.setGameLogEnabled(true);
        for (String entry : entries) {
            state.addGameLog(entry);
        }
        return state;
    }

    // ── flat capture ──

    @Test
    public void disabledLogAccumulatesNothing() {
        GameState state = new GameState();
        state.addGameLog("not kept");
        Assert.assertEquals(0, state.getGameLogCount());
        Assert.assertTrue(state.getGameLog().isEmpty());
    }

    @Test
    public void snapshotCarriesCountNotTheList() {
        GameState live = liveLog("a", "b");

        GameState snapshot = live.copy();

        // The snapshot knows how many entries existed...
        Assert.assertEquals(2, snapshot.getGameLogCount());
        // ...but does NOT carry the list (that is the flat shape's whole point).
        Assert.assertTrue("snapshot must not duplicate the log list", snapshot.getGameLog().isEmpty());

        // A later snapshot sees the grown count without the live list growing.
        live.addGameLog("c");
        Assert.assertEquals(3, live.getGameLogCount());
        Assert.assertEquals(2, snapshot.getGameLogCount());
    }

    // ── truncation ──

    @Test
    public void truncateGameLogDropsAbortedLinesAndRecordsTheTarget() {
        GameState state = liveLog("t1", "t2", "t3", "t4");

        int removed = state.truncateGameLog(2);

        Assert.assertEquals(2, removed);
        Assert.assertEquals(2, state.getGameLog().size());
        Assert.assertEquals("t2", state.getGameLog().get(1));
        Assert.assertEquals(1, state.getGameLogTruncateEpoch());
        Assert.assertEquals(1, state.getGameLogTruncationCount());
        Assert.assertEquals(2, state.getGameLogTruncationTarget(0));
    }

    @Test
    public void truncateAtOrAboveCurrentSizeIsANoOp() {
        GameState state = liveLog("a", "b");

        Assert.assertEquals(0, state.truncateGameLog(2));
        Assert.assertEquals(0, state.truncateGameLog(9));

        Assert.assertEquals(2, state.getGameLog().size());
        Assert.assertEquals("a no-op must not record a truncation", 0, state.getGameLogTruncateEpoch());
        Assert.assertEquals(0, state.getGameLogTruncationCount());
    }

    /**
     * The acceptance case for the viewer: a frame taken BEFORE a rollback has a
     * count that is now a lie (its tail described the aborted timeline), so its
     * replay-safe slice is capped at the truncation target.
     */
    @Test
    public void framesTakenBeforeATruncationAreCappedAtTheTarget() {
        GameState state = liveLog("t1", "t2", "t3", "t4");
        int preRollbackEpoch = state.getGameLogTruncateEpoch();
        int preRollbackCount = state.getGameLogCount();
        Assert.assertEquals(4, preRollbackCount);

        state.truncateGameLog(2); // rollback to a save taken at 2 entries
        state.addGameLog("replayed-3"); // the re-played turn appends

        // The old frame claimed 4 entries; only the first 2 are provably the
        // same lines in the final log, so it is capped there.
        Assert.assertEquals(2, state.getGameLogSafeSlice(preRollbackEpoch, preRollbackCount));
        // A frame taken after the truncation is bounded by its own count.
        Assert.assertEquals(3, state.getGameLogSafeSlice(state.getGameLogTruncateEpoch(), state.getGameLogCount()));
    }

    @Test
    public void safeSliceIsTheCountCappedByLaterTruncationTargets() {
        GameState state = liveLog("a", "b");
        // No truncations => the caller's own recorded count stands. Clamping to
        // the actual log length is the CLIENT's job (replayLogSliceFor slices
        // min(count, log.length)) — a snapshot has no live list to clamp against.
        Assert.assertEquals(5, state.getGameLogSafeSlice(0, 5));
        Assert.assertEquals(0, state.getGameLogSafeSlice(0, 0));
    }

    /** The LLM prompt cursor is an index — after a rollback it must move back. */
    @Test
    public void cursorMovesBackOverTruncationsItHasNotSeen() {
        GameState state = liveLog("a", "b", "c");
        int seenEpoch = state.getGameLogTruncateEpoch();

        state.truncateGameLog(1);

        // The re-played events must count as NEW again.
        Assert.assertEquals(1, state.getGameLogCursor(seenEpoch, 3));
        // A caller that already saw this truncation keeps its cursor.
        Assert.assertEquals(3, state.getGameLogCursor(state.getGameLogTruncateEpoch(), 3));
    }

    /** Karn's restart clears the log; earlier frames must not resurrect it. */
    @Test
    public void gameRestartRecordsATruncationToZero() {
        GameState state = liveLog("pre-restart-1", "pre-restart-2");
        int preRestartEpoch = state.getGameLogTruncateEpoch();

        state.clearOnGameRestart();

        Assert.assertEquals(0, state.getGameLogCount());
        Assert.assertEquals("pre-restart frames must be capped at 0", 0, state.getGameLogSafeSlice(preRestartEpoch, 2));
        Assert.assertEquals(1, state.getGameLogTruncationCount());
        Assert.assertEquals(0, state.getGameLogTruncationTarget(0));
    }

    // ── restore / ledger sharing ──

    /**
     * restore() deliberately does NOT touch the log: callers truncate explicitly
     * first (rollback must order it before its own announcement so the
     * announcement survives). A restored snapshot must never resurrect lines the
     * viewer already saw truncated away.
     */
    @Test
    public void restoreLeavesTheLiveLogAlone() {
        GameState live = liveLog("a", "b", "c");
        GameState snapshot = live.copy();
        live.truncateGameLog(2);
        live.addGameLog("rollback announcement");

        live.restore(snapshot);

        // restore() must leave the live log exactly as the truncation left it:
        // the aborted lines stay gone, the announcement stays, and the snapshot
        // never resurrects a log list it does not carry.
        Assert.assertEquals(3, live.getGameLog().size());
        Assert.assertEquals("a", live.getGameLog().get(0));
        Assert.assertEquals("b", live.getGameLog().get(1));
        Assert.assertEquals("rollback announcement", live.getGameLog().get(2));
    }

    /**
     * The ledger is shared BY REFERENCE so a snapshot can see truncations that
     * happen AFTER it was taken — that is what caps its replay-safe slice.
     */
    @Test
    public void snapshotSeesTruncationsThatHappenAfterItWasTaken() {
        GameState live = liveLog("a", "b", "c");
        GameState snapshot = live.copy();
        int snapshotEpoch = snapshot.getGameLogTruncateEpoch();
        int snapshotCount = snapshot.getGameLogCount();

        live.truncateGameLog(1);

        Assert.assertEquals(1, snapshot.getGameLogSafeSlice(snapshotEpoch, snapshotCount));
        Assert.assertEquals(1, snapshot.getGameLogTruncationCount());
    }

    /**
     * A .game written before the ledger existed deserializes the field as null
     * (Java bypasses initializers), so every reader must tolerate that — an old
     * replay must not NPE the server.
     */
    @Test
    public void ledgerIsNullSafeForSavesWrittenBeforeItExisted() throws Exception {
        GameState state = liveLog("a", "b");
        java.lang.reflect.Field field = GameState.class.getDeclaredField("gameLogTruncationTargets");
        field.setAccessible(true);
        field.set(state, null);

        Assert.assertEquals(0, state.getGameLogTruncationCount());
        Assert.assertEquals(2, state.getGameLogSafeSlice(0, 2));
        Assert.assertEquals(2, state.getGameLogCursor(0, 2));
        // The lazy restore must also leave the object usable afterwards.
        Assert.assertEquals(0, state.truncateGameLog(2));
    }

    /** Entries are appended in order and read back in order (the viewer's slice contract). */
    @Test
    public void logIsOrderedSoASliceIsMeaningful() {
        GameState state = liveLog("first", "second", "third");
        List<String> log = state.getGameLog();
        int count = state.getGameLogCount();

        Assert.assertEquals(3, count);
        Assert.assertEquals("first", log.get(0));
        Assert.assertEquals("third", log.get(count - 1));
    }
}
