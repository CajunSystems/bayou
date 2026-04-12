package com.cajunsystems.bayou;

import java.util.concurrent.CompletableFuture;

/**
 * Package-private {@link ActorRef} implementation backed by an {@link AbstractActorRunner}.
 */
final class ActorRefImpl<M> implements ActorRef<M> {

    private final AbstractActorRunner<M> runner;

    ActorRefImpl(AbstractActorRunner<M> runner) {
        this.runner = runner;
    }

    @Override
    public String actorId() {
        return runner.actorId;
    }

    @Override
    public void tell(M message) {
        runner.tell(message);
    }

    @Override
    public <R> CompletableFuture<R> ask(M message) {
        return runner.ask(message);
    }

    @Override
    public CompletableFuture<Void> stop() {
        return runner.stop();
    }
}
