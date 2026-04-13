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

    /**
     * Spawn a new child under this supervisor. The child starts immediately and is
     * supervised according to this supervisor's strategy.
     *
     * <p>The returned reference is typed to {@code ActorRef<?>}; cast to the concrete
     * message type if needed, or look the child up via
     * {@link BayouSystem#lookup(String) system.lookup(actorId)}.
     *
     * @param spec the child specification created via {@link ChildSpec} factory methods
     * @return a reference to the newly spawned child
     * @throws IllegalArgumentException if an actor with the same ID is already registered
     */
    ActorRef<?> spawnChild(ChildSpec spec);
}
