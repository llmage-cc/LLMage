package org.mage.test.game;

import mage.constants.PhaseStep;
import mage.game.GameState;
import org.apache.log4j.Appender;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Missing-bookmark recovery in the auto-restore path (the direction was
 * "save more bookmarks"; the undo/recovery half was still open).
 *
 * <p>
 * Legs:
 * <ul>
 *   <li>{@link #missingBookmarkIsAlwaysLoggedLoudly} - the exact shape the old code
 *       suppressed: an empty saved-states stack (left behind by a turn rollback) plus a
 *       request to restore into it. The old guard only logged when the stack was NOT empty
 *       ({@code if (!savedStates.isEmpty())}), so the dropped action and its trigger left no
 *       trace at all - the case that lost the Marit Lage trigger. Now it always logs, and the
 *       message names the empty stack.</li>
 *   <li>{@link #bookmarkFromACurrentlyOpenSaveIsValid} - {@code Game.hasBookmark} agrees with
 *       what a restore accepts (the test shared by the priority-loop anchor).</li>
 *   <li>{@link #droppedAnchorIsRebuiltLoudly} - the real churn shape: bookmarks are
 *       positional, so an inner {@code removeBookmark(older)} (PlayerImpl.resetStoredBookmark
 *       does exactly this) pops everything ABOVE it and kills the newer priority-loop anchor.
 *       The loss is logged loudly and a rebuilt bookmark works again, so one failed iteration
 *       no longer disables recovery for the rest of the loop.</li>
 * </ul>
 * The test game is a real (non-simulation) game - GameImpl.restoreState is a no-op in
 * simulations (GameImpl.java:1072) - so the real code paths are exercised.
 */
public class RestoreBookmarkRecoveryTest extends CardTestPlayerBase {

    /** Captures the raw log4j events emitted while the action runs. */
    private static final class CapturingAppender extends AppenderSkeleton {
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());

        @Override
        protected void append(LoggingEvent event) {
            messages.add(String.valueOf(event.getMessage()));
        }

        @Override
        public void close() {
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }

        boolean contains(String needle) {
            synchronized (messages) {
                return messages.stream().anyMatch(m -> m != null && m.contains(needle));
            }
        }

        void clear() {
            messages.clear();
        }
    }

    private static CapturingAppender attachRootAppender() {
        Logger root = Logger.getRootLogger();
        CapturingAppender appender = new CapturingAppender();
        appender.setThreshold(Level.WARN);
        root.addAppender(appender);
        return appender;
    }

    private static void detach(Logger root, Appender appender) {
        root.removeAppender(appender);
    }

    @Test
    public void missingBookmarkIsAlwaysLoggedLoudly() {
        CapturingAppender appender = attachRootAppender();
        Logger root = Logger.getRootLogger();
        try {
            runCode("missing bookmark", 1, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> {
                appender.clear();

                // Empty the saved-states stack the way a turn rollback does (removeBookmark_v2(1)
                // pops every entry). This is the shape the old code suppressed entirely.
                game.removeBookmark_v2(1);
                Assert.assertFalse("setup: the stack must be empty",
                        game.hasBookmark(1));

                GameState restored = game.restoreState(1, "restore-bookmark empty-stack leg");
                Assert.assertNull("a missing bookmark cannot restore", restored);

                Assert.assertTrue("a missing bookmark must always be logged",
                        appender.contains("It was not possible to do the requested undo operation"));
                Assert.assertTrue("the log must name the missing bookmark",
                        appender.contains("bookmark 0 does not exist"));
                Assert.assertTrue("the empty saved-states case must be named, not silently skipped",
                        appender.contains("no saved states at all"));
            });

            setStopAt(1, PhaseStep.END_TURN);
            execute();
        } finally {
            detach(root, appender);
        }
    }

    @Test
    public void bookmarkFromACurrentlyOpenSaveIsValid() {
        runCode("valid bookmark", 1, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> {
            int bookmark = game.bookmarkState();
            Assert.assertTrue("a bookmark just taken must be valid",
                    game.hasBookmark(bookmark));
            Assert.assertFalse("a bookmark that was never taken must not be valid",
                    game.hasBookmark(bookmark + 1));
            Assert.assertFalse("bookmark 0 is the no-bookmark sentinel, not a valid bookmark",
                    game.hasBookmark(0));

            GameState restored = game.restoreState(bookmark, "restore-bookmark valid-bookmark leg");
            Assert.assertNotNull("a live bookmark must restore", restored);
        });

        setStopAt(1, PhaseStep.END_TURN);
        execute();
    }

    @Test
    public void droppedAnchorIsRebuiltLoudly() {
        CapturingAppender appender = attachRootAppender();
        Logger root = Logger.getRootLogger();
        try {
            runCode("dropped anchor", 1, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> {
                appender.clear();

                // The churn shape PlayerImpl.resetStoredBookmark produces: an inner action takes
                // (and later removes) an older bookmark, and removeBookmark pops everything above
                // it - which is the newer priority-loop anchor.
                int older = game.bookmarkState();
                int anchor = game.bookmarkState();
                Assert.assertTrue("setup: both bookmarks must be valid",
                        game.hasBookmark(older) && game.hasBookmark(anchor));

                game.removeBookmark(older);
                Assert.assertTrue("setup: the older bookmark itself survives (positional bookmarks)",
                        game.hasBookmark(older));
                Assert.assertFalse("setup: the anchor above it is now unreachable",
                        game.hasBookmark(anchor));

                // The error path then reports the loss loudly instead of silently continuing.
                GameState restored = game.restoreState(anchor, "restore-bookmark dropped-anchor leg");
                Assert.assertNull("a dropped anchor cannot restore", restored);
                Assert.assertTrue("the loss of the action + trigger must be logged",
                        appender.contains("bookmark " + (anchor - 1) + " does not exist"));

                // Recovery: a fresh bookmark works again, so the next iteration can restore.
                int rebuilt = game.bookmarkState();
                Assert.assertTrue("a rebuilt bookmark must be valid", game.hasBookmark(rebuilt));
                Assert.assertNotNull("a rebuilt bookmark must restore",
                        game.restoreState(rebuilt, "restore-bookmark recovery leg"));
            });

            setStopAt(1, PhaseStep.END_TURN);
            execute();
        } finally {
            detach(root, appender);
        }
    }
}
