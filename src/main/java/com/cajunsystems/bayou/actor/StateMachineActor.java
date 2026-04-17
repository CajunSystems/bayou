package com.cajunsystems.bayou.actor;

import com.cajunsystems.bayou.BayouContext;
import com.cajunsystems.bayou.Signal;

import java.util.Optional;

/**
 * A finite state machine actor.
 *
 * <p>The runtime calls {@link #transition} for every message. If the returned state differs
 * from the current state, {@link #onExit} fires for the old state and {@link #onEnter} fires
 * for the new state. {@link #onEnter} is also called for the initial state during startup.
 *
 * <pre>{@code
 * enum Light { RED, GREEN, YELLOW }
 *
 * Ref<String> fsm = system.spawnStateMachine("traffic", new StateMachineActor<Light, String>() {
 *     public Optional<Light> transition(Light state, String msg, BayouContext<String> ctx) {
 *         return switch (msg) {
 *             case "go"   -> state == RED    ? Optional.of(GREEN)  : Optional.empty();
 *             case "slow" -> state == GREEN  ? Optional.of(YELLOW) : Optional.empty();
 *             case "stop" -> state == YELLOW ? Optional.of(RED)    : Optional.empty();
 *             default -> Optional.empty();
 *         };
 *     }
 * }, Light.RED);
 * }</pre>
 *
 * @param <S> state type (typically an {@code enum})
 * @param <M> message type
 */
public interface StateMachineActor<S, M> {

    /**
     * Process {@code message} in {@code state} and return the next state.
     * Return {@link Optional#empty()} to stay in the current state.
     */
    Optional<S> transition(S state, M message, BayouContext<M> ctx);

    /** Called when entering {@code state} (including the initial state on startup). */
    default void onEnter(S state, BayouContext<M> ctx) {}

    /** Called when leaving {@code state} due to a transition. */
    default void onExit(S state, BayouContext<M> ctx) {}

    /** Called once before the first message is delivered (after {@code onEnter} for initial state). */
    default void preStart(BayouContext<M> ctx) {}

    /** Called once after the last message is processed and the actor has stopped. */
    default void postStop(BayouContext<M> ctx) {}

    /**
     * Called when {@link #transition} throws. Default behaviour logs the error.
     * The actor is NOT crashed — the state remains unchanged.
     */
    default void onError(M message, Throwable error, BayouContext<M> ctx) {
        ctx.logger().error("Unhandled error in state machine for message {}", message, error);
    }

    /** Called when a lifecycle signal ({@link com.cajunsystems.bayou.Terminated}, etc.) arrives. */
    default void onSignal(Signal signal, BayouContext<M> ctx) {}
}
