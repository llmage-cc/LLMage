package org.mage.test.player.p1;

import mage.cards.Card;
import mage.cards.CardSetInfo;
import mage.cards.basiclands.Mountain;
import mage.cards.decks.Deck;
import mage.constants.MultiplayerAttackOption;
import mage.constants.RangeOfInfluence;
import mage.constants.Rarity;
import mage.game.Game;
import mage.game.GameOptions;
import mage.game.TwoPlayerDuel;
import mage.game.mulligan.MulliganType;
import mage.players.Player;
import mage.player.p1.P1Player;
import mage.player.p1.P1Worker;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * F-L1 regression: the P1 plugin's out-of-process Python worker ({@link P1Worker}) must be closed
 * and unregistered from {@code P1Player.WORKERS} on EVERY terminal lifecycle hook, exactly once.
 * <p>
 * Pre-fix, {@code won}/{@code lostForced}/{@code drew} only logged a stats line and {@code lost}
 * was not overridden at all, so {@link P1Worker#close()} was never called and the
 * {@code WORKERS} entry was never removed — every finished P1 game leaked a live ~321 MB Python
 * interpreter for the JVM's lifetime.
 * <p>
 * Harness: a never-started {@link TwoPlayerDuel} fixture (same shape as
 * {@code P1AdventureCastCandidatesTest}) with both seats' ranges seeded (drove {@code won()} /
 * {@code lostForced()} without seeding {@code inRange} and {@code getOpponents()} throws "game is
 * not started" — see {@code LLMPostGameReflectionAsyncTest.seedStartedGameState}). No policy worker
 * is ever started: instead a real throwaway OS process ({@code sleep 120}) is injected into the
 * worker's {@code process} field, so the test can observe that the lifecycle hook actually destroys
 * the process rather than merely dropping the map entry.
 * <p>
 * RED/GREEN: on the pre-fix code the reaping assertions fail — the worker is still registered and
 * the injected process is still alive after the hook — and the direct-seam test errors with
 * {@code NoSuchMethodException} for the missing {@code reapWorker}. After the fix all are GREEN.
 * Everything not present at base ({@code reapWorker}, the {@code closed} flag) is reached by
 * reflection so this class still compiles at base and produces a behavioural (not API-absence) RED.
 */
public class P1WorkerReapTest {

    // ------------------------------------------------------------------
    //  Hook coverage: each terminal hook must reap the seat's worker.
    // ------------------------------------------------------------------

    @Test
    public void wonReapsWorkerAndDestroysProcess() throws Exception {
        Fixture fx = newFixture();
        P1Worker worker = attachLiveWorker(fx);
        Process dummy = injectedProcess(worker);
        try {
            Assert.assertTrue("precondition: worker registered for the seat",
                    workersContains(fx.seat.getId()));

            fx.seat.won(fx.game);

            assertReaped(fx, worker, dummy);
        } finally {
            cleanup(fx, worker, dummy);
        }
    }

    @Test
    public void lostForcedReapsWorkerAndDestroysProcess() throws Exception {
        Fixture fx = newFixture();
        P1Worker worker = attachLiveWorker(fx);
        Process dummy = injectedProcess(worker);
        try {
            fx.seat.lostForced(fx.game);

            assertReaped(fx, worker, dummy);
        } finally {
            cleanup(fx, worker, dummy);
        }
    }

    @Test
    public void lostReapsWorkerAndDestroysProcess() throws Exception {
        Fixture fx = newFixture();
        P1Worker worker = attachLiveWorker(fx);
        Process dummy = injectedProcess(worker);
        try {
            fx.seat.lost(fx.game);

            assertReaped(fx, worker, dummy);
        } finally {
            cleanup(fx, worker, dummy);
        }
    }

    @Test
    public void drewReapsWorkerAndDestroysProcess() throws Exception {
        Fixture fx = newFixture();
        P1Worker worker = attachLiveWorker(fx);
        Process dummy = injectedProcess(worker);
        try {
            fx.seat.drew(fx.game);

            assertReaped(fx, worker, dummy);
        } finally {
            cleanup(fx, worker, dummy);
        }
    }

    // ------------------------------------------------------------------
    //  Idempotency: a second terminal hook on the same seat is a no-op
    //  (never a double-destroy error) and the reap happens exactly once.
    // ------------------------------------------------------------------

    @Test
    public void secondTerminalHookIsIdempotentNoOp() throws Exception {
        Fixture fx = newFixture();
        P1Worker worker = attachLiveWorker(fx);
        Process dummy = injectedProcess(worker);
        try {
            fx.seat.lostForced(fx.game);   // first terminal hook reaps
            fx.seat.lostForced(fx.game);   // second must be a no-op, never a double-destroy

            assertReaped(fx, worker, dummy);
        } finally {
            cleanup(fx, worker, dummy);
        }
    }

    @Test
    public void reapWorkerClosesExactlyOnce() throws Exception {
        Fixture fx = newFixture();
        P1Worker worker = attachLiveWorker(fx);
        Process dummy = injectedProcess(worker);
        try {
            Method reap = P1Player.class.getDeclaredMethod("reapWorker", UUID.class);
            reap.setAccessible(true);

            Object first = reap.invoke(null, fx.seat.getId());
            Object second = reap.invoke(null, fx.seat.getId());

            Assert.assertEquals("the first reap must claim and close the worker", Boolean.TRUE, first);
            Assert.assertEquals("the second reap must be a no-op (idempotent)", Boolean.FALSE, second);
            assertReaped(fx, worker, dummy);
        } finally {
            cleanup(fx, worker, dummy);
        }
    }

    // ------------------------------------------------------------------
    //  Fixture + reflection seams
    // ------------------------------------------------------------------

    private static final class Fixture {
        P1Player seat;
        P1Player opp;
        Game game;
    }

    /**
     * Never-started duel with two P1 seats and both ranges seeded. No cards matter to the terminal
     * hooks; one Mountain exists only so {@code loadCards} has a well-formed state.
     */
    private static Fixture newFixture() throws Exception {
        Fixture fx = new Fixture();
        fx.seat = new P1Player("P1Seat", RangeOfInfluence.ONE, 0);
        fx.opp = new P1Player("Opp", RangeOfInfluence.ONE, 0);

        fx.game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE,
                MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);
        GameOptions opts = new GameOptions();
        opts.testMode = true;
        opts.skipInitShuffling = true;
        setGameOptions(fx.game, opts);

        Card mountain = new Mountain(fx.seat.getId(),
                new CardSetInfo("Mountain", "TEST", "1", Rarity.LAND));
        mountain.setOwnerId(fx.seat.getId());
        Set<Card> cards = new LinkedHashSet<>();
        cards.add(mountain);
        fx.game.loadCards(cards, fx.seat.getId());
        fx.game.addPlayer(fx.seat, new Deck());
        fx.game.addPlayer(fx.opp, new Deck());

        // Seed inRange for BOTH seats: the win/loss cascade calls getOpponents() from each side,
        // which throws "game is not started" on a fixture that skipped beginTurn.
        for (Player p : fx.game.getPlayers().values()) {
            p.updateRange(fx.game);
        }
        return fx;
    }

    /** Attach a real (never-started) worker for the seat and return it. */
    private static P1Worker attachLiveWorker(Fixture fx) throws Exception {
        P1Worker worker = new P1Worker("seat0");
        P1Player.attachWorker(fx.seat.getId(), worker);
        return worker;
    }

    /** Inject a live throwaway process so the test can observe the destroy, not just the removal. */
    private static Process injectedProcess(P1Worker worker) throws Exception {
        Process dummy = new ProcessBuilder("sleep", "120").start();
        setProcess(worker, dummy);
        return dummy;
    }

    /** The two post-conditions every terminal hook must reach. */
    private static void assertReaped(Fixture fx, P1Worker worker, Process dummy) throws Exception {
        Assert.assertFalse("worker must be unregistered from WORKERS (F-L1: no leak)",
                workersContains(fx.seat.getId()));
        awaitExit(dummy, 3000);
        Assert.assertFalse("worker process must be destroyed, not orphaned", dummy.isAlive());
        Assert.assertTrue("worker must be marked closed", isClosed(worker));
    }

    /** Always leave the shared static WORKERS map clean and no stray OS process behind. */
    private static void cleanup(Fixture fx, P1Worker worker, Process dummy) throws Exception {
        try {
            dummy.destroyForcibly();
        } catch (Exception ignored) {
            // best-effort
        }
        try {
            workers().remove(fx.seat.getId());
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private static void awaitExit(Process p, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (p.isAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
    }

    // ----- reflection seams (keep this class compilable against the pre-fix code) -----

    private static void setGameOptions(Game game, GameOptions opts) throws Exception {
        Field f = mage.game.GameImpl.class.getDeclaredField("gameOptions");
        f.setAccessible(true);
        f.set(game, opts);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, P1Worker> workers() throws Exception {
        Field f = P1Player.class.getDeclaredField("WORKERS");
        f.setAccessible(true);
        return (Map<UUID, P1Worker>) f.get(null);
    }

    private static boolean workersContains(UUID playerId) throws Exception {
        return workers().containsKey(playerId);
    }

    private static void setProcess(P1Worker worker, Process process) throws Exception {
        Field f = P1Worker.class.getDeclaredField("process");
        f.setAccessible(true);
        f.set(worker, process);
    }

    private static boolean isClosed(P1Worker worker) throws Exception {
        Field f = P1Worker.class.getDeclaredField("closed");
        f.setAccessible(true);
        return f.getBoolean(worker);
    }
}
