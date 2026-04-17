package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.Actor;

record StatelessChildSpec<M>(String actorId, Actor<M> actor, MailboxConfig mailboxConfig) implements ChildSpec {

    public StatelessChildSpec<M> withMailbox(MailboxConfig config) {
        return new StatelessChildSpec<>(actorId, actor, config);
    }
}
