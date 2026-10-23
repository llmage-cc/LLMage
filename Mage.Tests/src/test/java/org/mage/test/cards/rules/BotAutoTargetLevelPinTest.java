package org.mage.test.cards.rules;

import mage.constants.RangeOfInfluence;
import mage.player.ai.ComputerPlayer;
import mage.target.TargetImpl;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

import static org.junit.Assert.assertEquals;

/**
 * Fork-authored pin for the auto-target-level contract (genuine bot seats get level 1,
 * scripted test seats keep upstream's level 2).
 * <p>
 * An earlier fork change set the non-human branch of {@code TargetImpl.tryToAutoChoose}
 * to level 1 (NON_FEEL_BAD) so a bot's removal spell would stop auto-targeting the bot's
 * own permanent. That branch also caught the scripted test seats ({@code TestPlayer} is
 * {@code isHuman() == false} and {@code isComputer() == AIRealGameSimulation}, default
 * FALSE), so single-target prompts became real prompts and upstream tests' staged targets
 * were left unconsumed (OblivionSowerTest, TheCreationOfAvacynTest ×3, StingBilbosSwordTest).
 * <p>
 * The fix keeps level 1 for genuine bot seats only and gives every other non-human seat
 * upstream's level 2. These assertions cover both legs without a running game, because
 * the level derivation does not need game state for either seat.
 *
 */
public class BotAutoTargetLevelPinTest extends CardTestPlayerBase {

    @Test
    public void testScriptedTestSeatKeepsUpstreamLevelTwo() {
        playerA.setTestMode(true); // TestPlayer.isComputer() is illegal outside test mode
        assertEquals("scripted TestPlayer must keep upstream's level 2",
                2, TargetImpl.autoTargetLevelForPlayer(playerA, null));
    }

    @Test
    public void testGenuineBotSeatKeepsNonFeelBadLevelOne() {
        // real AI seat (isHuman() == false, isComputer() == true) — not TestComputerPlayer,
        // whose isComputer() delegates to the linked TestPlayer and is therefore false
        ComputerPlayer bot = new ComputerPlayer("Bot", RangeOfInfluence.ALL);
        assertEquals("a genuine bot seat must keep the fork's NON_FEEL_BAD guard",
                1, TargetImpl.autoTargetLevelForPlayer(bot, null));
    }
}
