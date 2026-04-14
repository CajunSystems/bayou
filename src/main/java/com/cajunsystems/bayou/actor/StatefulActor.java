package com.cajunsystems.bayou.actor;

import com.cajunsystems.bayou.BayouContext;

/**
 * A function-core style actor: {@code (S state, M message) -> S}.
 *
 * <p>State is kept in memory and periodically snapshotted to the gumbo shared log
 * ({@code bayou.snapshots:<actorId>}).  On startup the runtime reads the latest
 * snapshot (if any) and uses it as the starting state — no full event replay required.
 *
 * <p>This is the right choice when:
 * <ul>
 *   <li>State is too large or changes too frequently for full replay to be practical.</li>
 *   <li>You need durability but don't need a complete audit trail of every message.</li>
 * </ul>
 *
 * <pre>{@code
 * record Tally(Map<String, Integer> counts) implements Serializable {}
 *
 * class WordCounter implements StatefulActor<Tally, String> {
 *     public Tally initialState() { return new Tally(new HashMap<>()); }
 *
 *     public Tally reduce(Tally state, String word, BayouContext ctx) {
 *         var next = new HashMap<>(state.counts());
 *         next.merge(word, 1, Integer::sum);
 *         return new Tally(next);
 *     }
 * }
 *
 * Ref<String> counter = system.spawnStateful(
 *     "word-counter", new WordCounter(), new JavaSerializer<>());
 * }</pre>
 *
 * @param <S> immutable (or defensively copied) state type; must be serializable
 * @param <M> message type
 */
public interface StatefulActor<S, M> {

    /** The state used when no snapshot exists yet (first-ever start). */
    S initialState();

    /**
     * Pure reducer: given the current state and an incoming message, return the next state.
     * To reply to an {@code ask}, call {@link BayouContext#reply(Object)}.
     */
    S reduce(S state, M message, BayouContext context);

    /** Called once, after snapshot restoration, before the first live message is delivered. */
    default void preStart(BayouContext context) {}

    /** Called once after the actor stops (a final snapshot is taken automatically). */
    default void postStop(BayouContext context) {}

    /**
     * Called when {@link #reduce} throws. The state is left unchanged.
     * Default behaviour logs the error.
     */
    default void onError(M message, Throwable error, BayouContext context) {
        context.logger().error("Unhandled error processing message {}", message, error);
    }
}
