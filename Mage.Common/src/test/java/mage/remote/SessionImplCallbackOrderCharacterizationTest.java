package mage.remote;

import mage.interfaces.MageClient;
import mage.interfaces.ServerState;
import mage.interfaces.callback.ClientCallback;
import mage.interfaces.callback.ClientCallbackMethod;
import mage.utils.MageVersion;
import org.jboss.remoting.callback.Callback;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization of the client-side callback delivery ordering guarantee exercised through the
 * real {@link SessionImpl.CallbackHandler} path.
 *
 * <p>These tests PIN the CURRENT contract of the handler, not a wish: the only ordering the
 * handler provides is a stable sort of the callbacks that happen to be queued in ONE
 * {@code handleCallback} invocation's copy (the {@code waitingCallbacks} snapshot). There is no
 * ordering guarantee across separate invocations or across handler threads, so a callback that
 * reaches the handler out of {@code messageId} order is handed to {@code client.onCallback} in
 * that (inverted) order, and one that starts later can overtake an earlier one.
 *
 * <p>This is the evidence behind the verdict:
 * the live defect (96 inverted
 * adjacent {@code messageId}s in one spectator session) is a transport/hand-off ordering loss,
 * and the per-invocation sort here cannot restore a global order. If the handler is ever given a
 * global ordering guarantee, these pins must be revisited (they will fail and should be inverted
 * into positive assertions).
 */
public class SessionImplCallbackOrderCharacterizationTest {

    /** Records the {@code messageId} of every callback the handler delivers, in delivery order. */
    static class RecordingClient implements MageClient {

        final List<Integer> deliveredMessageIds = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onCallback(ClientCallback callback) {
            deliveredMessageIds.add(callback.getMessageId());
        }

        @Override
        public void onNewConnection() {
        }

        @Override
        public MageVersion getVersion() {
            return new MageVersion(MageVersion.MAGE_VERSION_MAJOR, MageVersion.MAGE_VERSION_MINOR,
                    MageVersion.MAGE_VERSION_RELEASE, MageVersion.MAGE_VERSION_RELEASE_INFO,
                    RecordingClient.class);
        }

        @Override
        public void connected(String message) {
        }

        @Override
        public void disconnected(boolean askToReconnect, boolean keepMySessionActive) {
        }

        @Override
        public void showMessage(String message) {
        }

        @Override
        public void showError(String message) {
        }
    }

    /**
     * A jboss callback whose {@code getCallbackObject()} blocks the caller. The cast
     * {@code (ClientCallback) callback.getCallbackObject()} is the FIRST statement of
     * {@link SessionImpl.CallbackHandler#handleCallback}, before the {@code waitingCallbacks}
     * monitor is taken — so this stalls a handler thread right after it has entered
     * {@code handleCallback} but before it can add its callback to the queue. That models any
     * scheduling pause in a callback thread after entry (an inbound oneway-pool worker being
     * preempted), i.e. a lower-id callback whose handler invocation started first but stalled.
     */
    static class BlockingCallback extends Callback {

        private final CountDownLatch entered;
        private final CountDownLatch release;

        BlockingCallback(Object callbackObject, CountDownLatch entered, CountDownLatch release) {
            super(callbackObject);
            this.entered = entered;
            this.release = release;
        }

        @Override
        public Object getCallbackObject() {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.getCallbackObject();
        }
    }

    private static ClientCallback callback(int messageId) {
        ClientCallback callback = new ClientCallback(ClientCallbackMethod.GAME_UPDATE, UUID.randomUUID(), null);
        callback.setMessageId(messageId);
        return callback;
    }

    private static SessionImpl handlerSession(RecordingClient client) throws Exception {
        SessionImpl session = new SessionImpl(client);
        // make serverState non-null so handleCallback skips the CONNECT_WAIT_BEFORE_PROCESS_ANY_CALLBACKS_SECS sleep
        ServerState serverState = new ServerState(Collections.emptyList(), Collections.emptyList(),
                new mage.players.PlayerType[0], new String[0], new String[0], false, null, 0, 0);
        Field serverStateField = SessionImpl.class.getDeclaredField("serverState");
        serverStateField.setAccessible(true);
        serverStateField.set(session, serverState);
        return session;
    }

    @Test
    @DisplayName("control: callbacks queued in ONE batch copy are delivered sorted by messageId")
    void batchCopyIsSortedByMessageId() throws Exception {
        RecordingClient client = new RecordingClient();
        SessionImpl session = handlerSession(client);
        SessionImpl.CallbackHandler handler = session.new CallbackHandler();

        // Seed the queue with two callbacks in REVERSE arrival order, then let a third arrival
        // trigger one copy: the copy must sort the whole batch by messageId.
        handler.waitingCallbacks.add(callback(6));
        handler.waitingCallbacks.add(callback(5));
        handler.handleCallback(new Callback(callback(7)));

        assertThat(client.deliveredMessageIds).containsExactly(5, 6, 7);
    }

    @Test
    @DisplayName("defect: invocations that arrive out of messageId order are relayed inverted (no cross-invocation sort)")
    void separateInvocationsAreDeliveredInArrivalOrder() throws Exception {
        RecordingClient client = new RecordingClient();
        SessionImpl session = handlerSession(client);
        SessionImpl.CallbackHandler handler = session.new CallbackHandler();

        // Two separate handler invocations: higher id first (models an inverted hand-off). The
        // handler keeps no watermark between invocations, so the per-invocation sort is a no-op
        // here and the inversion reaches client.onCallback untouched.
        handler.handleCallback(new Callback(callback(6)));
        handler.handleCallback(new Callback(callback(5)));

        assertThat(client.deliveredMessageIds)
                .as("separate invocations are not globally ordered; the inversion is delivered as-is")
                .containsExactly(6, 5);
    }

    @Test
    @DisplayName("defect: a lower-id handler invocation that started FIRST can still be overtaken")
    void lowerIdInvocationStartedFirstCanStillBeOvertaken() throws Exception {
        RecordingClient client = new RecordingClient();
        SessionImpl session = handlerSession(client);
        SessionImpl.CallbackHandler handler = session.new CallbackHandler();

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // id=5's handler invocation starts first and stalls BEFORE adding to waitingCallbacks.
        Thread lower = new Thread(() -> handler.handleCallback(
                new BlockingCallback(callback(5), entered, release)), "callback-lower-5");
        lower.start();
        assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        // id=6 then runs to completion on the calling thread and is delivered while id=5 is stalled.
        handler.handleCallback(new Callback(callback(6)));

        release.countDown();
        lower.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(5));

        assertThat(client.deliveredMessageIds)
                .as("a stall in the lower-id invocation lets the higher-id one overtake it; the sort cannot help")
                .containsExactly(6, 5);
    }
}
