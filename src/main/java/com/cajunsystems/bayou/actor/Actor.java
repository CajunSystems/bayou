package com.cajunsystems.bayou.actor;

import com.cajunsystems.bayou.BayouContext;

/**
 * A pure message-processing actor with no persisted state.
 *
 * <p>Each invocation of {@link #handle} is independent. Side effects and any external
 * state management are the responsibility of the implementation.
 * The gumbo log is not touched by the runtime on behalf of this flavour.
 *
 * <pre>{@code
 * BayouSystem system = ...;
 * ActorRef<String> greeter = system.spawn("greeter", (msg, ctx) ->
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
    void handle(M message, BayouContext context);

    /** Called once before the first message is delivered. */
    default void preStart(BayouContext context) {}

    /** Called once after the last message is processed and the actor has stopped. */
    default void postStop(BayouContext context) {}

    /**
     * Called when {@link #handle} throws. Default behaviour logs the error.
     * Override to implement retry, dead-letter forwarding, or escalation.
     */
    default void onError(M message, Throwable error, BayouContext context) {
        context.logger().error("Unhandled error processing message {}", message, error);
    }
}
