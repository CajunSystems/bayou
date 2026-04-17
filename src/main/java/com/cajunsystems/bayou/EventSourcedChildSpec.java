package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.EventSourcedActor;

record EventSourcedChildSpec<S, E, M>(String actorId,
                                       EventSourcedActor<S, E, M> actor,
                                       BayouSerializer<E> eventSerializer,
                                       MailboxConfig mailboxConfig) implements ChildSpec {

    public EventSourcedChildSpec<S, E, M> withMailbox(MailboxConfig config) {
        return new EventSourcedChildSpec<>(actorId, actor, eventSerializer, config);
    }
}
