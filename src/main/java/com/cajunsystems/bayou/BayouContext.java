package com.cajunsystems.bayou;

import org.slf4j.Logger;

/**
 * Runtime context injected into every actor invocation.
 *
 * <p>Provides identity, system access, logging, and the ability to reply to ask-pattern messages.
 */
public interface BayouContext {

    /** The stable actor identity. */
    String actorId();

    /** The owning {@link BayouSystem}. Use this to look up or spawn other actors. */
    BayouSystem system();

    /** Pre-configured logger with the actor ID embedded in the name. */
    Logger logger();

    /**
     * Completes an in-flight {@link ActorRef#ask} request with the supplied value.
     * Has no effect when the current message was delivered via {@link ActorRef#tell}.
     */
    void reply(Object value);
}
