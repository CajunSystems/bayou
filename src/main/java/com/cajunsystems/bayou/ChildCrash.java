package com.cajunsystems.bayou;

/**
 * Internal signal fired by a runner to its crash listener when the actor's
 * virtual thread exits abnormally (unhandled exception escaping the message loop).
 *
 * <p>Package-private — not part of the public API.
 */
record ChildCrash(String actorId, Throwable cause, AbstractActorRunner<?> runner) {}
