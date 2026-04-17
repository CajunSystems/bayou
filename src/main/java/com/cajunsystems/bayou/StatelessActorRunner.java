package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.Actor;

/**
 * Runner for {@link Actor}. Does not interact with the gumbo log.
 */
final class StatelessActorRunner<M> extends AbstractActorRunner<M> {

    private final Actor<M> actor;

    StatelessActorRunner(String actorId, BayouSystem system, Actor<M> actor, MailboxConfig mailboxConfig) {
        super(actorId, system, mailboxConfig);
        this.actor = actor;
    }

    @Override
    protected void initialize() {
        actor.preStart(context);
    }

    @Override
    protected void processEnvelope(Envelope<M> envelope) {
        try {
            actor.handle(envelope.payload(), context);
        } catch (Exception e) {
            try {
                actor.onError(envelope.payload(), e, context);
            } catch (Exception onErrorEx) {
                context.logger().error("onError handler threw for actor '{}'", actorId, onErrorEx);
            }
        }
    }

    @Override
    protected void cleanup() {
        actor.postStop(context);
    }

    @Override
    protected void handleSignal(Signal signal) {
        try {
            actor.onSignal(signal, context);
        } catch (Exception e) {
            try {
                actor.onError(null, e, context);
            } catch (Exception ignored) {}
        }
    }
}
