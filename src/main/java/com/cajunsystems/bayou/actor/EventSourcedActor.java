package com.cajunsystems.bayou.actor;

import com.cajunsystems.bayou.BayouContext;
import com.cajunsystems.bayou.Signal;

import java.util.List;

/**
 * An actor whose state is fully derived by replaying a sequence of events stored in the
 * gumbo shared log.
 *
 * <p><b>Recovery:</b> on startup the runtime reads every event from the actor's log tag
 * ({@code bayou.events:<actorId>}) and folds them through {@link #apply} to reconstruct
 * state before delivering the first message.
 *
 * <p><b>Message handling:</b> each call to {@link #handle} returns the events to persist.
 * The runtime appends them atomically and then folds them into the in-memory state via
 * {@link #apply}.  If the handler returns an empty list the message is effectively a
 * read-only query.
 *
 * <pre>{@code
 * record Counter(int value) {}
 * sealed interface CounterEvent { record Incremented(int by) implements CounterEvent {} }
 * sealed interface CounterMsg  { record Increment(int by) implements CounterMsg {} }
 *
 * class CounterActor implements EventSourcedActor<Counter, CounterEvent, CounterMsg> {
 *     public Counter initialState() { return new Counter(0); }
 *
 *     public List<CounterEvent> handle(Counter state, CounterMsg msg, BayouContext<CounterMsg> ctx) {
 *         return switch (msg) {
 *             case CounterMsg.Increment(var by) -> List.of(new CounterEvent.Incremented(by));
 *         };
 *     }
 *
 *     public Counter apply(Counter state, CounterEvent event) {
 *         return switch (event) {
 *             case CounterEvent.Incremented(var by) -> new Counter(state.value() + by);
 *         };
 *     }
 * }
 * }</pre>
 *
 * @param <S> immutable state type
 * @param <E> event type (must be serializable by the provided {@link com.cajunsystems.bayou.BayouSerializer})
 * @param <M> message type
 */
public interface EventSourcedActor<S, E, M> {

    /** The state used when no events exist in the log yet (first-ever start). */
    S initialState();

    /**
     * Decide which events to emit in response to a message.
     * Must be deterministic given the same {@code state} and {@code message}.
     * Return an empty list for read-only queries.
     */
    List<E> handle(S state, M message, BayouContext<M> context);

    /**
     * Pure fold: apply one event to the current state and return the next state.
     * Must be deterministic and side-effect-free — it runs during both normal
     * processing and log replay.
     */
    S apply(S state, E event);

    /** Called once, after replay, before the first live message is delivered. */
    default void preStart(BayouContext<M> context) {}

    /** Called once after the actor stops. */
    default void postStop(BayouContext<M> context) {}

    /**
     * Called when {@link #handle} or event persistence throws. Default behaviour logs the error.
     * The actor's in-memory state is not rolled back; events already appended to the log remain.
     */
    default void onError(M message, Throwable error, BayouContext<M> context) {
        context.logger().error("Unhandled error processing message {}", message, error);
    }

    /**
     * Called when a lifecycle signal is delivered.
     * Return events to emit (return empty list if not handled).
     */
    default List<E> onSignal(S state, Signal signal, BayouContext<M> ctx) { return List.of(); }
}
