package com.cajunsystems.bayou;

import java.util.concurrent.CompletableFuture;

/**
 * A typed reference to a live actor. Obtained from {@link BayouSystem} spawn methods.
 *
 * @param <M> the message type accepted by the actor
 */
public interface ActorRef<M> {

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
     * Signals the actor to stop after draining its current mailbox.
     *
     * @return a future that completes once the actor has shut down
     */
    CompletableFuture<Void> stop();
}
