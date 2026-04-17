package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.StatefulActor;

/**
 * Child spec for a stateful actor. Returned by {@link ChildSpec#stateful}.
 * Use {@link #snapshotInterval(int)} to override the default snapshot interval.
 */
public record StatefulChildSpec<S, M>(String actorId,
                                       StatefulActor<S, M> actor,
                                       BayouSerializer<S> stateSerializer,
                                       int snapshotInterval,
                                       MailboxConfig mailboxConfig) implements ChildSpec {

    /**
     * Returns a new spec with the given snapshot interval.
     * A snapshot is written every {@code n} messages (default:
     * {@value BayouSystem#DEFAULT_SNAPSHOT_INTERVAL}).
     */
    public StatefulChildSpec<S, M> snapshotInterval(int n) {
        if (n <= 0) throw new IllegalArgumentException("snapshotInterval must be > 0");
        return new StatefulChildSpec<>(actorId, actor, stateSerializer, n, mailboxConfig);
    }

    public StatefulChildSpec<S, M> withMailbox(MailboxConfig config) {
        return new StatefulChildSpec<>(actorId, actor, stateSerializer, snapshotInterval, config);
    }
}
