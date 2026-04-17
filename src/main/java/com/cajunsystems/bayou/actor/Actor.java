package com.cajunsystems.bayou.actor;

import com.cajunsystems.bayou.BayouContext;
import com.cajunsystems.bayou.Signal;

/**
 * A pure message-processing actor with no persisted state.
 *
 * <p>Each invocation of {@link #handle} is independent. Side effects and any external
 * state management are the responsibility of the implementation.
 * The gumbo log is not touched by the runtime on behalf of this flavour.
 *
 * <pre>{@code
 * BayouSystem system = ...;
 * Ref<String> greeter = system.spawn("greeter", (msg, ctx) ->
 *     ctx.logger().info("Hello, {}!", msg));
 * greeter.tell("World");
 * }</pre>
 *
 * @param <M> message type
 */
@FunctionalInterface
public interface Actor<M> {

    /**
     * Process a single message. Called sequentially — never concurrently with itself.
     * To reply to an {@code ask}, call {@link BayouContext#reply(Object)}.
     */
    void handle(M message, BayouContext<M> context);

    /** Called once before the first message is delivered. */
    default void preStart(BayouContext<M> context) {}

    /** Called once after the last message is processed and the actor has stopped. */
    default void postStop(BayouContext<M> context) {}

    /**
     * Called when {@link #handle} throws. Default behaviour logs the error.
     * Override to implement retry, dead-letter forwarding, or escalation.
     */
    default void onError(M message, Throwable error, BayouContext<M> context) {
        context.logger().error("Unhandled error processing message {}", message, error);
    }

    /**
     * Called when a lifecycle signal (e.g. {@link com.cajunsystems.bayou.Terminated},
     * {@link com.cajunsystems.bayou.LinkedActorDied}) is delivered to this actor.
     */
    default void onSignal(Signal signal, BayouContext<M> ctx) {}
}
