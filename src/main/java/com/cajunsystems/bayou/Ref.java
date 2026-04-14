package com.cajunsystems.bayou;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A typed reference to a live actor. Obtained from {@link BayouSystem} spawn methods.
 *
 * @param <M> the message type accepted by the actor
 */
public interface Ref<M> {

    /** The stable identity of this actor within the system. */
    String actorId();

    /** Fire-and-forget delivery. Returns immediately; the message is queued in the actor's mailbox. */
    void tell(M message);

    /**
     * Request–response delivery. The actor must call {@link BayouContext#reply(Object)} to
     * complete the returned future; if the message is handled without a reply the future
     * completes exceptionally.
     *
     * @param <R> the expected reply type (unchecked — callers are responsible for the cast)
     */
    <R> CompletableFuture<R> ask(M message);

    /**
     * Returns {@code true} if the actor is currently running (not stopped or stopping).
     * A {@code false} result means messages sent via {@link #tell} will be silently dropped.
     */
    boolean isAlive();

    /**
     * Convenience overload of {@link #ask(Object)} with a built-in timeout.
     * The returned future completes exceptionally with {@link java.util.concurrent.TimeoutException}
     * if no reply arrives within the given duration.
     */
    default <R> CompletableFuture<R> ask(M message, Duration timeout) {
        return this.<R>ask(message).orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Signals the actor to stop after draining its current mailbox.
     *
     * @return a future that completes once the actor has shut down
     */
    CompletableFuture<Void> stop();
}
