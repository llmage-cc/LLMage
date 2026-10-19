package org.mage.test.rollback;

import java.util.List;
import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

/**
 * Integration test for the replay game-log half of a turn rollback

 *
 * The log is NOT rolled back by {@code GameState.restore()} — the rollback call
 * site truncates it explicitly. Without that, the aborted timeline's lines
 * survive AND the re-played turn appends its own copies of the same events, so
 * every rollback-affected replay double-logs the re-played turns.
 *
 * The capture gate itself (GameController enabling the log for saveable games)
 * is a server-level concern and is not in this path: the test enables
 * accumulation explicitly, then asserts the engine semantics.
 */
public class GameLogRollbackTruncationTest extends CardTestPlayerBase {

    /**
     * Log lines embed the object's colored HTML name — {@code "PlayerA plays
     * <font color='green'>Forest [ce5]</font>"} — so a raw substring match for
     * "plays Forest" would miss. Strip the markup the same way the web client's
     * stripServerHtml does before matching.
     */
    private static String plain(String line) {
        return line.replaceAll("<[^>]+>", "");
    }

    private static int indexOfLineContaining(List<String> log, String needle) {
        for (int i = 0; i < log.size(); i++) {
            if (plain(log.get(i)).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static long countLinesContaining(List<String> log, String needle) {
        return log.stream().filter(line -> plain(line).contains(needle)).count();
    }

    @Test
    public void rollbackTruncatesTheAbortedTimelineAndDoesNotDuplicateReplayedLines() {
        // The saveable-game capture gate (GameController) is not in this path.
        currentGame.getState().setGameLogEnabled(true);

        addCard(Zone.HAND, playerA, "Forest", 1);
        addCard(Zone.HAND, playerA, "Island", 1);

        // Turn 1: a line that must SURVIVE the rollback (pre-rollback prefix).
        playLand(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Island");
        // Turn 3: a play in the ABORTED timeline — its line must be dropped...
        playLand(3, PhaseStep.PRECOMBAT_MAIN, playerA, "Forest");

        rollbackTurns(3, PhaseStep.POSTCOMBAT_MAIN, playerA, 0);
        rollbackAfterActionsStart();
        // ...because the re-played turn re-issues the same play, and BOTH copies
        // in the log is exactly the duplication this test forbids.
        playLand(3, PhaseStep.PRECOMBAT_MAIN, playerA, "Forest");
        rollbackAfterActionsEnd();

        setStopAt(3, PhaseStep.END_TURN);
        execute();

        List<String> log = currentGame.getState().getGameLog();
        Assert.assertFalse("the log must have accumulated entries", log.isEmpty());

        // The engine records the truncation (drives the viewer's per-frame cap
        // and the LLM prompt cursor reset).
        Assert.assertEquals("exactly one rollback truncation", 1, currentGame.getState().getGameLogTruncationCount());

        // The acceptance case: each aborted-timeline line was dropped and the
        // re-played turn re-issued it exactly once. A land play emits TWO lines
        // ("puts X from hand onto the Battlefield" + "plays X"), so the failure
        // mode being guarded against is each of them appearing TWICE (2 aborted
        // + 2 replayed = the double-logged replay this fixes).
        Assert.assertEquals("re-played play must not be double-logged",
                1, countLinesContaining(log, "plays Forest"));
        Assert.assertEquals("re-played land drop must not be double-logged",
                1, countLinesContaining(log, "puts Forest"));
        Assert.assertEquals("exactly one drop + one play line for Forest",
                2, countLinesContaining(log, "Forest"));

        // The pre-rollback prefix survives.
        Assert.assertEquals("pre-rollback line must survive", 1, countLinesContaining(log, "plays Island"));

        // The rollback announcement is a real event: it is kept, at the seam.
        int announcementIdx = indexOfLineContaining(log, "Rolling back to start of turn");
        Assert.assertTrue("announcement must be kept", announcementIdx >= 0);
        int islandIdx = indexOfLineContaining(log, "Island");
        int forestIdx = indexOfLineContaining(log, "Forest");
        Assert.assertTrue("prefix before the seam", islandIdx < announcementIdx);
        Assert.assertTrue("re-played lines after the seam", announcementIdx < forestIdx);
    }
}
