package com.cajunsystems.bayou;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A controllable actor probe for deterministic testing.
 * Obtain via {@link TestKit#probe(BayouSystem, String)}.
 *
 * @param <M> the message type this probe accepts
 */
public final class TestProbe<M> {

    private final BayouSystem system;
    private final Ref<M> ref;
    private final LinkedBlockingQueue<M> messages = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<Signal> signals = new LinkedBlockingQueue<>();

    TestProbe(BayouSystem system, String id) {
        this.system = system;
        this.ref = system.spawn(id, new ProbeActor<>(messages, signals));
    }

    /** The {@link Ref} for this probe — use it to subscribe to topics, link, watch, or wire actors. */
    public Ref<M> ref() {
        return ref;
    }

    /**
     * Block until a message equal to {@code expected} arrives, or throw {@link AssertionError}
     * if the timeout elapses or a different message is received.
     *
     * @return the received message
     */
    public M expectMessage(M expected, Duration timeout) {
        M received = poll(messages, timeout);
        if (received == null) {
            throw new AssertionError(
                "expectMessage: timeout after " + timeout + " — expected: " + expected);
        }
        if (!Objects.equals(received, expected)) {
            throw new AssertionError(
                "expectMessage: expected [" + expected + "] but got [" + received + "]");
        }
        return received;
    }

    /**
     * Block until a message of the given {@code type} arrives, or throw {@link AssertionError}.
     *
     * @return the received message cast to {@code T}
     */
    public <T extends M> T expectMessage(Class<T> type, Duration timeout) {
        M received = poll(messages, timeout);
        if (received == null) {
            throw new AssertionError(
                "expectMessage: timeout after " + timeout + " — expected type: " + type.getSimpleName());
        }
        if (!type.isInstance(received)) {
            throw new AssertionError(
                "expectMessage: expected type " + type.getSimpleName() +
                " but got " + received.getClass().getSimpleName() + ": " + received);
        }
        return type.cast(received);
    }

    /**
     * Assert that no message arrives within {@code within}. Throws {@link AssertionError}
     * if any message is received before the duration elapses.
     */
    public void expectNoMessage(Duration within) {
        M received = poll(messages, within);
        if (received != null) {
            throw new AssertionError(
                "expectNoMessage: unexpected message received: " + received);
        }
    }

    /**
     * Watch {@code watched} for termination, then block until a {@link Terminated} signal
     * for that actor arrives, or throw {@link AssertionError} on timeout.
     */
    public void expectTerminated(Ref<?> watched, Duration timeout) {
        system.watch(watched, this.ref);
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            Signal signal;
            try {
                signal = signals.poll(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted waiting for Terminated", e);
            }
            if (signal == null) break;
            if (signal instanceof Terminated t && t.actorId().equals(watched.actorId())) return;
        }
        throw new AssertionError(
            "expectTerminated: timeout after " + timeout + " — actor '" + watched.actorId() + "' did not terminate");
    }

    private M poll(LinkedBlockingQueue<M> queue, Duration timeout) {
        try {
            return queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for message", e);
        }
    }
}
