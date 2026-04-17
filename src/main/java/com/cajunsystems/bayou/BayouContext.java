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
}
