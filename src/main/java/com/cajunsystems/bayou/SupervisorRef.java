package com.cajunsystems.bayou;

/**
 * Handle for a running supervisor. Supervisors do not accept user messages;
 * use {@link #stop()} and {@link #isAlive()} to manage the supervisor lifecycle.
 *
 * <p>Stopping a supervisor gracefully stops all its children first.
 */
public interface SupervisorRef extends ActorRef<Void> {
    // Inherits: actorId(), isAlive(), stop()
    // tell(Void) is a no-op; ask(Void) completes exceptionally
}
