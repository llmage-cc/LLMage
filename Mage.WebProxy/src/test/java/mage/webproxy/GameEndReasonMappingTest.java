package mage.webproxy;

import mage.game.GameEndReason;
import org.junit.Assert;
import org.junit.Test;

/**
 * The end-reason mapping the browser receives as {@code game_over.endReason}
 * (GameEndView.getEndReason → WebProxyClient.handleGameOver).
 *
 * Pinned here rather than in Mage.Tests: these are wire-vocabulary assertions on
 * a pure helper, so they need no game and no engine corpus.
 *
 * Precedence rule under test: the subject player's match flags are authoritative
 * (a timeout/idle/quit seat reaches the game end through an ordinary concede, so
 * the recorded reason alone would call it "concede"), then the reason recorded on
 * the GameState, then "unknown".
 */
public class GameEndReasonMappingTest {

    @Test
    public void wireVocabularyIsExact() {
        Assert.assertEquals("llm_failure", GameEndReason.LLM_FAILURE);
        Assert.assertEquals("concede", GameEndReason.CONCEDE);
        Assert.assertEquals("timeout", GameEndReason.TIMEOUT);
        Assert.assertEquals("idle", GameEndReason.IDLE);
        Assert.assertEquals("quit", GameEndReason.QUIT);
        Assert.assertEquals("unknown", GameEndReason.UNKNOWN);
    }

    @Test
    public void absentReasonIsUnknown() {
        // A played-out win or a draw records nothing: additive field, no behaviour change.
        Assert.assertEquals(GameEndReason.UNKNOWN, GameEndReason.resolve(false, false, false, null));
    }

    @Test
    public void recordedLlmFailureSurvivesWhenNoFlagIsSet() {
        Assert.assertEquals(GameEndReason.LLM_FAILURE,
                GameEndReason.resolve(false, false, false, GameEndReason.LLM_FAILURE));
    }

    @Test
    public void timerTimeoutBeatsEveryRecordedReason() {
        Assert.assertEquals(GameEndReason.TIMEOUT,
                GameEndReason.resolve(true, false, true, GameEndReason.CONCEDE));
        Assert.assertEquals(GameEndReason.TIMEOUT,
                GameEndReason.resolve(true, true, true, GameEndReason.LLM_FAILURE));
    }

    @Test
    public void idleTimeoutBeatsQuitAndRecordedReason() {
        Assert.assertEquals(GameEndReason.IDLE,
                GameEndReason.resolve(false, true, true, GameEndReason.CONCEDE));
    }

    @Test
    public void quitBeatsRecordedConcede() {
        Assert.assertEquals(GameEndReason.QUIT,
                GameEndReason.resolve(false, false, true, GameEndReason.CONCEDE));
    }

    @Test
    public void unknownRecordedValuePassesThroughUnchanged() {
        // Additive wire data: a reason this build does not know yet (a newer
        // server) is reported verbatim instead of being flattened to "unknown".
        Assert.assertEquals("future_reason", GameEndReason.resolve(false, false, false, "future_reason"));
    }
}
