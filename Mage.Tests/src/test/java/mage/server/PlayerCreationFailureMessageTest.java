package mage.server;

import mage.constants.RangeOfInfluence;
import mage.players.LLMPlayerConfig;
import mage.players.Player;
import mage.players.PlayerType;
import mage.server.game.PlayerFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for the dead-endpoint join failure: a seat whose player
 * construction throws must report WHY (exception class + message) and WHICH
 * endpoint, instead of a bare "Could not create player ... of type Computer - LLM".
 *
 * <p>Lives in package {@code mage.server} to reach the package-private message
 * composer exactly as the Join Table path calls it.
 *
 * <p>Every case runs its factory calls on a dedicated thread so the
 * per-thread failure capture starts empty and cannot be influenced by another
 * test method in the same JVM. The throwaway player class is unregistered in
 * {@link #cleanup()}, so the shared registry is left as found.
 */
public class PlayerCreationFailureMessageTest {

    private static final PlayerType SEAT_TYPE = PlayerType.COMPUTER_LLM;
    private static final String REFUSAL = "connect timed out to http://127.0.0.1:9/v1/models";

    /**
     * Stand-in for LLMPlayer against a dead endpoint: the constructor throws, which
     * the factory sees as an InvocationTargetException wrapping this exception.
     * Does not implement Player on purpose - construction fails before the cast.
     */
    public static class RefusingPlayer {

        public RefusingPlayer(String name, RangeOfInfluence range, int skill) {
            throw new IllegalStateException(REFUSAL);
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

    // --- failure capture ---------------------------------------------------

    @Test
    public void testFailedConstructionCapturesTheRealCause() {
        String cause = onFreshThread(() -> {
            PlayerFactory.instance.addPlayerType(SEAT_TYPE.toString(), RefusingPlayer.class);
            Optional<Player> player = PlayerFactory.instance.createPlayer(
                    SEAT_TYPE, "Computer", RangeOfInfluence.ALL, 1);
            Assert.assertFalse("a throwing constructor must still fail fast (empty Optional)", player.isPresent());
            return PlayerFactory.getLastCreationFailure();
        });

        Assert.assertNotNull("a failed creation must capture a cause", cause);
        Assert.assertTrue("the cause must name the throwing class, got: " + cause,
                cause.contains(IllegalStateException.class.getName()));
        Assert.assertTrue("the cause must carry the exception message, got: " + cause,
                cause.contains(REFUSAL));
        Assert.assertFalse("the reflection wrapper must be unwrapped, got: " + cause,
                cause.contains("InvocationTargetException"));
    }

    @Test
    public void testCauseIsClearedBeforeEachAttempt() {
        String secondCause = onFreshThread(() -> {
            PlayerFactory.instance.addPlayerType(SEAT_TYPE.toString(), RefusingPlayer.class);
            Assert.assertFalse(PlayerFactory.instance.createPlayer(
                    SEAT_TYPE, "Computer", RangeOfInfluence.ALL, 1).isPresent());
            Assert.assertTrue(PlayerFactory.getLastCreationFailure().contains(REFUSAL));

            // Second attempt: nothing registered any more, so it fails differently.
            PlayerFactory.instance.removePlayerType(SEAT_TYPE);
            Assert.assertFalse(PlayerFactory.instance.createPlayer(
                    SEAT_TYPE, "Computer", RangeOfInfluence.ALL, 1).isPresent());
            return PlayerFactory.getLastCreationFailure();
        });

        Assert.assertTrue("the second attempt must report its own failure, got: " + secondCause,
                secondCause.contains("no player class registered"));
        Assert.assertFalse("a stale cause must never leak into the next attempt, got: " + secondCause,
                secondCause.contains(REFUSAL));
    }

    // --- composed Join Table message ---------------------------------------

    @Test
    public void testJoinMessageIsTheShortFormWithCauseAndEndpoint() {
        String message = onFreshThread(() -> {
            PlayerFactory.instance.addPlayerType(SEAT_TYPE.toString(), RefusingPlayer.class);
            Assert.assertFalse(PlayerFactory.instance.createPlayer(
                    SEAT_TYPE, "Computer", RangeOfInfluence.ALL, 1).isPresent());

            LLMPlayerConfig seatConfig = new LLMPlayerConfig();
            seatConfig.setApiEndpoint("http://127.0.0.1:9");
            return TableController.buildPlayerCreationFailureMessage(
                    "Could not create player Computer of type " + SEAT_TYPE, SEAT_TYPE, seatConfig);
        });

        // Shape: cause: <Class>: <short reason> | endpoint: <seat endpoint>.
        // The historical prefix is log-only now; the full text is pinned in JoinFailureShortMessageTest.
        Assert.assertTrue("the visible text is the short form, got: " + message,
                message.startsWith("cause: IllegalStateException: connect timed out to http://127.0.0.1:9/v1/models"));
        Assert.assertTrue("the message must name the seat's configured endpoint last, got: " + message,
                message.endsWith(" | endpoint: http://127.0.0.1:9"));
        Assert.assertFalse("the historical prefix belongs to the log, not the dialog, got: " + message,
                message.contains("Could not create player"));
    }

    @Test
    public void testJoinMessageNamesTheServerDefaultWhenTheSeatHasNoEndpoint() {
        String message = onFreshThread(() -> TableController.buildPlayerCreationFailureMessage(
                "Could not create player Computer of type " + SEAT_TYPE, SEAT_TYPE, null));
        Assert.assertEquals("an LLM seat without a per-seat config dials the server default",
                "cause: unknown | endpoint: server default",
                message);
    }

    @Test
    public void testJoinMessageIsUnchangedWhenThereIsNothingToReport() {
        // No cause captured on this fresh thread and a non-LLM seat: byte-for-byte the old text,
        // so readers that match the prefix see exactly what they saw before.
        String message = onFreshThread(() -> TableController.buildPlayerCreationFailureMessage(
                "Could not create player Bob of type Computer - monte carlo",
                PlayerType.COMPUTER_MONTE_CARLO, null));
        Assert.assertEquals("Could not create player Bob of type Computer - monte carlo", message);
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Run the body on its own thread and return its value, so the factory's
     * per-thread failure capture is virgin for that case. Rethrows an assertion
     * or runtime failure from the body on the test thread.
     */
    private static String onFreshThread(java.util.function.Supplier<String> body) {
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                result.set(body.get());
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "player-creation-failure-test");
        worker.start();
        try {
            worker.join(30_000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the worker", ex);
        }
        Assert.assertFalse("worker thread did not finish", worker.isAlive());
        if (failure.get() != null) {
            throw new AssertionError("failure on the worker thread", failure.get());
        }
        return result.get();
    }
}
