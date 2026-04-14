package com.cajunsystems.bayou;

import java.util.concurrent.CompletableFuture;

/**
 * Package-private {@link SupervisorRef} backed by a {@link SupervisorRunner}.
 * Exposes lifecycle operations only — supervisors do not accept user messages.
 */
final class SupervisorRefImpl implements SupervisorRef {

    private final SupervisorRunner runner;

    SupervisorRefImpl(SupervisorRunner runner) {
        this.runner = runner;
    }

    @Override
    public String actorId() {
        return runner.actorId;
    }

    @Override
    public void tell(Void message) {
        // Supervisors do not accept user messages — no-op
    }

    @Override
    public <R> CompletableFuture<R> ask(Void message) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Supervisors do not accept user messages"));
    }

    @Override
    public boolean isAlive() {
        return runner.isAlive();
    }

    @Override
    public CompletableFuture<Void> stop() {
        return runner.stop();
    }

    @Override
    public Ref<?> spawnChild(ChildSpec spec) {
        return runner.spawnChild(spec);
    }
}
