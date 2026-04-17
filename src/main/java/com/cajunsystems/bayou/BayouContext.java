package com.cajunsystems.bayou;

import org.slf4j.Logger;
import java.time.Duration;

/**
 * Runtime context injected into every actor invocation.
 *
 * <p>Provides identity, system access, logging, reply, and timer scheduling.
 */
public interface BayouContext<M> {

    /** The stable actor identity. */
    String actorId();

    /** The owning {@link BayouSystem}. Use this to look up or spawn other actors. */
    BayouSystem system();

    /** Pre-configured logger with the actor ID embedded in the name. */
    Logger logger();

    /**
     * Completes an in-flight {@link Ref#ask} request with the supplied value.
     * Has no effect when the current message was delivered via {@link Ref#tell}.
     */
    void reply(Object value);

    /**
     * Schedule a one-shot message to this actor after {@code delay}.
     * The message is delivered into this actor's own mailbox as a normal {@code M} message.
     * Returns a {@link TimerRef} that can be used to cancel the timer before it fires.
     */
    TimerRef scheduleOnce(Duration delay, M message);

    /**
     * Schedule a repeating message to this actor, firing every {@code period}.
     * The first firing occurs after one {@code period} has elapsed.
     * Returns a {@link TimerRef} that must be cancelled to stop the repetition.
     */
    TimerRef schedulePeriodic(Duration period, M message);

    /**
     * Watch {@code target} for death. When it stops (crash or graceful), this actor receives
     * a {@link Terminated} signal via {@link com.cajunsystems.bayou.actor.Actor#onSignal}.
     *
     * @return a handle to cancel the watch
     */
    WatchHandle watch(Ref<?> target);

    /** Cancel a previously registered death watch. Idempotent. */
    void unwatch(WatchHandle handle);

    /**
     * Bidirectionally link this actor to {@code other}. If either dies, the other
     * receives a {@link LinkedActorDied} signal and (unless trapping exits) also crashes.
     */
    void link(Ref<?> other);

    /** Remove the bidirectional link between this actor and {@code other}. Idempotent. */
    void unlink(Ref<?> other);

    /**
     * Set this actor's exit-trapping mode. When {@code true}, {@link LinkedActorDied} signals
     * are delivered to {@code onSignal} instead of causing a crash.
     * Default is {@code false}.
     */
    void trapExits(boolean flag);

    /** Returns this actor's own {@link Ref} — useful for subscribing to {@link BayouPubSub} topics
     *  from within {@code preStart} or {@code handle} without an untyped lookup. */
    Ref<M> self();
}
