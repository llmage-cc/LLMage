package org.mage.test.game;

import mage.game.GameState;
import mage.game.GameStates;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for GameStates.truncateFromTurn — the replay-timeline truncation
 * used by turn rollback.
 *
 * Design decision: a rollback must keep the pre-rollback
 * replay prefix and drop only the aborted timeline, so the re-played turns
 * append into ONE cohesive replay of the final game — instead of the old
 * gameStates.clear() that made every rollback-affected replay start mid-game.
 */
public class GameStatesTruncateTest {

    private static GameState state(int turn) {
        GameState s = new GameState();
        s.setTurnNum(turn);
        return s;
    }

    private static int[] turns(GameStates states) {
        int[] res = new int[states.getSize()];
        for (int i = 0; i < res.length; i++) {
            res[i] = states.get(i).getTurnNum();
        }
        return res;
    }

    private static void assertTurns(int[] actual, int... expected) {
        Assert.assertArrayEquals(expected, actual);
    }

    @Test
    public void truncateDropsAbortedTimelineAndKeepsPrefix() {
        GameStates states = new GameStates();
        // turns 1-4 recorded (2 states per turn for realism), then the
        // original turn-5 timeline started before the rollback
        for (int t = 1; t <= 4; t++) {
            states.save(state(t));
            states.save(state(t));
        }
        states.save(state(5));
        states.save(state(5));

        int removed = states.truncateFromTurn(5);

        Assert.assertEquals(2, removed);
        Assert.assertEquals(8, states.getSize());
        assertTurns(turns(states), 1, 1, 2, 2, 3, 3, 4, 4);
    }

    @Test
    public void replayedTurnsAppendAfterPrefixIntoCohesiveTimeline() {
        GameStates states = new GameStates();
        states.save(state(1));
        states.save(state(2));
        states.save(state(3));
        states.save(state(4));

        // rollback to start of turn 3, original turns 3-4 dropped...
        Assert.assertEquals(2, states.truncateFromTurn(3));
        // ...re-played turns 3 and 4 are saved again after the prefix
        states.save(state(3));
        states.save(state(3));
        states.save(state(4));
        states.save(state(5));

        assertTurns(turns(states), 1, 2, 3, 3, 4, 5);
    }

    @Test
    public void truncateTargetBeforeAllStatesDropsAbortedFuture() {
        // Possible after pruneOldest dropped the earliest states in a very
        // long game: the rollback target predates every saved state. The
        // saved states are all chronologically AFTER the restore point, so
        // they are the aborted timeline and must go — keeping them would
        // make the replay's turn numbers run backwards after the resume.
        GameStates states = new GameStates();
        states.save(state(3));
        states.save(state(4));

        Assert.assertEquals(2, states.truncateFromTurn(1));
        Assert.assertEquals(0, states.getSize());

        // re-played turns append into a fresh, cohesive timeline
        states.save(state(1));
        states.save(state(2));
        states.save(state(3));
        assertTurns(turns(states), 1, 2, 3);
    }

    @Test
    public void truncateTurnAfterAllStatesKeepsEverything() {
        GameStates states = new GameStates();
        states.save(state(1));
        states.save(state(2));

        Assert.assertEquals(0, states.truncateFromTurn(9));
        Assert.assertEquals(2, states.getSize());
    }

    @Test
    public void truncateOnEmptyListIsNoop() {
        GameStates states = new GameStates();
        Assert.assertEquals(0, states.truncateFromTurn(1));
        Assert.assertEquals(0, states.getSize());
    }

    @Test
    public void turnNumberSequenceStaysMonotonicAfterTruncateAndAppend() {
        GameStates states = new GameStates();
        for (int t = 1; t <= 6; t++) {
            states.save(state(t));
        }
        Assert.assertEquals(3, states.truncateFromTurn(4));
        for (int t = 4; t <= 6; t++) {
            states.save(state(t));
        }
        int[] turns = turns(states);
        for (int i = 1; i < turns.length; i++) {
            Assert.assertTrue("turn sequence must be non-decreasing at " + i, turns[i] >= turns[i - 1]);
        }
        Assert.assertEquals(6, states.getSize());
    }
}