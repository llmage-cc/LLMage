package mage.server;

import mage.constants.RangeOfInfluence;
import mage.players.LLMPlayerConfig;
import mage.players.Player;
import mage.players.PlayerType;
import mage.server.game.PlayerFactory;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Pins the short join-failure form:
 * the client-visible join-failure text is the SHORT form
 * <pre>cause: &lt;Class&gt;: &lt;short reason&gt; | endpoint: &lt;url&gt;</pre>
 * about 120 characters and hard-capped at
 * {@link TableController#MAX_JOIN_FAILURE_MESSAGE_CHARS}; the full detection text
 * (historical prefix, complete exception message, endpoint) stays in the SERVER LOG.
 *
 * <p>Before this the dialog inlined the whole exception — the real LLM detection
 * message is ~400 chars, so the dialog was ~500 chars and named the endpoint twice.
 *
 * <p>Cases:
 * <ul>
 *   <li>the visible text matches the short-form pattern, names the endpoint, drops the
 *       historical prefix and the full reason, and stays under the bound;</li>
 *   <li>the full text (prefix + entire cause message) is available to the log call and
 *       really reaches the log4j event of the Join Table path;</li>
 *   <li>an absurd class name plus an absurd URL still respect the bound;</li>
 *   <li>a cause with no exception class ("no player class registered for type X") is
 *       still explained in the short form.</li>
 * </ul>
 *
 * <p>Lives in package {@code mage.server} to reach the package-private composers exactly
 * as {@code TableController.joinTable}/{@code joinTournament} call them. Every case runs
 * its factory calls on a dedicated thread (the failure capture is a ThreadLocal) and
 * unregisters the throwaway player class afterwards.
 */
public class JoinFailureShortMessageTest {

    private static final PlayerType SEAT_TYPE = PlayerType.COMPUTER_LLM;
    private static final String PREFIX = "Could not create player Computer of type Computer - LLM";
    private static final String ENDPOINT = "http://127.0.0.1:9/v1/chat/completions";

    /** The real shape: the ~400-char, multi-line message LLMConfig throws on a dead endpoint. */
    private static final String DETECTION_MESSAGE =
            "Failed to detect context window from OpenAI-compatible endpoint '" + ENDPOINT + "' "
            + "(tried GET http://127.0.0.1:9/v1/models): Connection refused.\n"
            + "  Set modelContextWindow manually for this model "
            + "(xmage.llm.model_context_window or the GUI LLM config).";

    /** A tail that only survives if the message was NOT truncated. */
    private static final String FULL_TEXT_TAIL = "or the GUI LLM config";

    /** The short-form shape, anchored: cause class, reason, endpoint, in that order. */
    private static final Pattern SHORT_FORM =
            Pattern.compile("^cause: [A-Za-z_$][A-Za-z0-9_$]*: .+ \\| endpoint: .+$");

    /** Stand-in for a seat whose construction fails on a dead endpoint. */
    public static class RefusingPlayer {

        public RefusingPlayer(String name, RangeOfInfluence range, int skill) {
            throw new IllegalStateException(DETECTION_MESSAGE);
        }
    }

    /** A deliberately over-long exception class name (forces the bound's fallback path). */
    public static class AnExceptionClassWhoseSimpleNameAloneIsMostOfTheBudget
            extends RuntimeException {
        public AnExceptionClassWhoseSimpleNameAloneIsMostOfTheBudget(String message) {
            super(message);
        }
    }

    public static class VeryVerboseRefusingPlayer {

        public VeryVerboseRefusingPlayer(String name, RangeOfInfluence range, int skill) {
            throw new AnExceptionClassWhoseSimpleNameAloneIsMostOfTheBudget(DETECTION_MESSAGE);
        }
    }

    @Before
    public void cleanupBefore() {
        PlayerFactory.instance.removePlayerType(SEAT_TYPE);
    }

    @After
    public void cleanup() {
        PlayerFactory.instance.removePlayerType(SEAT_TYPE);
    }

    // --- the visible text --------------------------------------------------

    @Test
    public void visibleMessageIsTheBoundedShortForm() {
        String message = onFreshThread(() -> {
            failCreation(RefusingPlayer.class);
            LLMPlayerConfig seatConfig = new LLMPlayerConfig();
            seatConfig.setApiEndpoint(ENDPOINT);
            return TableController.buildPlayerCreationFailureMessage(PREFIX, SEAT_TYPE, seatConfig);
        });

        Assert.assertTrue("shape must be 'cause: <Class>: <reason> | endpoint: <url>', got: " + message,
                SHORT_FORM.matcher(message).matches());
        Assert.assertTrue("the message must stay inside the hard bound, got " + message.length()
                        + " chars: " + message,
                message.length() <= TableController.MAX_JOIN_FAILURE_MESSAGE_CHARS);
        Assert.assertTrue("the bound must be the ruled short-form size, not a runaway limit",
                TableController.MAX_JOIN_FAILURE_MESSAGE_CHARS <= 200);
        Assert.assertFalse("the historical prefix must be gone from the dialog, got: " + message,
                message.contains("Could not create player"));
        Assert.assertFalse("the full detection reason must not be inlined, got: " + message,
                message.contains(FULL_TEXT_TAIL));
        Assert.assertTrue("the reason is clipped, not dropped, got: " + message,
                message.contains("..."));
        Assert.assertTrue("the exception class must be named, got: " + message,
                message.contains("cause: IllegalStateException: "));
        Assert.assertTrue("the seat's endpoint must be named last, got: " + message,
                message.endsWith(" | endpoint: " + ENDPOINT));
    }

    @Test
    public void boundHoldsForAnAbsurdClassAndUrl() {
        String longUrl = ENDPOINT + "/" + padding(180);
        String message = onFreshThread(() -> {
            failCreation(VeryVerboseRefusingPlayer.class);
            LLMPlayerConfig seatConfig = new LLMPlayerConfig();
            seatConfig.setApiEndpoint(longUrl);
            return TableController.buildPlayerCreationFailureMessage(PREFIX, SEAT_TYPE, seatConfig);
        });

        Assert.assertTrue("even absurd inputs must respect the bound, got " + message.length()
                        + " chars: " + message,
                message.length() <= TableController.MAX_JOIN_FAILURE_MESSAGE_CHARS);
        Assert.assertTrue("the class name must survive the shortening (only the reason and the "
                        + "URL may give way), got: " + message,
                message.startsWith("cause: ")
                        && message.contains("AnExceptionClassWhoseSimpleNameAloneIsMostOfTheBudget"));
    }

    @Test
    public void causeWithoutAnExceptionClassStillExplainsItself() {
        String message = onFreshThread(() -> {
            // Nothing registered for the type: PlayerFactory records a bare sentence.
            Assert.assertFalse(PlayerFactory.instance.createPlayer(
                    SEAT_TYPE, "Computer", RangeOfInfluence.ALL, 1).isPresent());
            return TableController.buildPlayerCreationFailureMessage(PREFIX, SEAT_TYPE, null);
        });

        Assert.assertTrue("the captured reason must be shown, got: " + message,
                message.startsWith("cause: no player class registered for type "));
        Assert.assertTrue("an LLM seat without a per-seat endpoint dials the server default, got: "
                        + message,
                message.endsWith(" | endpoint: server default"));
        Assert.assertTrue("still inside the bound, got " + message.length() + " chars",
                message.length() <= TableController.MAX_JOIN_FAILURE_MESSAGE_CHARS);
    }

    // --- the full text stays in the log ------------------------------------

    @Test
    public void fullTextIsKeptForTheServerLogAndReallyLogged() {
        String loggedText = onFreshThread(() -> {
            failCreation(RefusingPlayer.class);
            LLMPlayerConfig seatConfig = new LLMPlayerConfig();
            seatConfig.setApiEndpoint(ENDPOINT);

            String full = TableController.buildPlayerCreationFailureLogText(PREFIX, SEAT_TYPE, seatConfig);
            Assert.assertTrue("the log text must keep the historical prefix, got: " + full,
                    full.startsWith(PREFIX));
            Assert.assertTrue("the log text must keep the WHOLE cause message, got: " + full,
                    full.contains(FULL_TEXT_TAIL));
            Assert.assertTrue("the log text must keep the endpoint, got: " + full,
                    full.contains(ENDPOINT));
            Assert.assertTrue("the log text is expected to exceed the dialog bound, got "
                            + full.length() + " chars",
                    full.length() > TableController.MAX_JOIN_FAILURE_MESSAGE_CHARS);

            // The real Join Table log call, captured from log4j.
            Logger controllerLogger = Logger.getLogger(TableController.class);
            CapturingAppender appender = new CapturingAppender();
            appender.setThreshold(Level.WARN);
            controllerLogger.addAppender(appender);
            try {
                TableController.logPlayerCreationFailure("User: tester => ", PREFIX, SEAT_TYPE, seatConfig);
            } finally {
                controllerLogger.removeAppender(appender);
            }
            Assert.assertTrue("the full text must reach the server log, captured: " + appender.joined(),
                    appender.contains(FULL_TEXT_TAIL));
            Assert.assertTrue("the log line must still identify the seat, captured: " + appender.joined(),
                    appender.contains(PREFIX));
            return full;
        });

        Assert.assertFalse("nothing may be lost: the log carries the detail the dialog dropped",
                loggedText.isEmpty());
    }

    // --- helpers -----------------------------------------------------------

    /** A run of one character, for the absurd-input bound case (no String.repeat on Java 8). */
    private static String padding(int length) {
        char[] chars = new char[length];
        java.util.Arrays.fill(chars, 'a');
        return new String(chars);
    }

    private static void failCreation(Class<?> playerClass) {
        PlayerFactory.instance.addPlayerType(SEAT_TYPE.toString(), playerClass);
        Optional<Player> player = PlayerFactory.instance.createPlayer(
                SEAT_TYPE, "Computer", RangeOfInfluence.ALL, 1);
        Assert.assertFalse("a throwing constructor must still fail fast (empty Optional)",
                player.isPresent());
    }

    /** Captures raw log4j events (same shape as RestoreBookmarkRecoveryTest). */
    private static final class CapturingAppender extends AppenderSkeleton {

        private final List<String> messages = Collections.synchronizedList(new ArrayList<>());

        @Override
        protected void append(LoggingEvent event) {
            Object rendered = event.getMessage();
            messages.add(String.valueOf(rendered));
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

        String joined() {
            synchronized (messages) {
                return String.join(" || ", messages);
            }
        }
    }

    /** Run the body on its own thread (virgin PlayerFactory ThreadLocal) and return its value. */
    private static String onFreshThread(java.util.function.Supplier<String> body) {
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                result.set(body.get());
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "join-failure-short-message-test");
        worker.start();
        try {
            worker.join(30_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the worker", e);
        }
        Assert.assertFalse("worker thread did not finish", worker.isAlive());
        if (failure.get() != null) {
            throw new AssertionError("failure on the worker thread", failure.get());
        }
        return result.get();
    }
}
