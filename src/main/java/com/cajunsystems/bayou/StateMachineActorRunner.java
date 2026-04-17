package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.StateMachineActor;

import java.util.Optional;

/**
 * Runner for {@link StateMachineActor}. Does not interact with the gumbo log.
 */
final class StateMachineActorRunner<S, M> extends AbstractActorRunner<M> {

    private final StateMachineActor<S, M> actor;
    private final S initialState;
    private S currentState;

    StateMachineActorRunner(String actorId, BayouSystem system,
                             StateMachineActor<S, M> actor, S initialState,
                             MailboxConfig mailboxConfig) {
        super(actorId, system, mailboxConfig);
        this.actor = actor;
        this.initialState = initialState;
    }

    @Override
    protected void initialize() {
        currentState = initialState;
        try {
            actor.onEnter(currentState, context);
        } catch (Exception e) {
            try { actor.onError(null, e, context); } catch (Exception ignored) {}
        }
        actor.preStart(context);
    }

    @Override
    protected void processEnvelope(Envelope<M> envelope) {
        try {
            Optional<S> next = actor.transition(currentState, envelope.payload(), context);
            if (next.isPresent() && !next.get().equals(currentState)) {
                S previous = currentState;
                try {
                    actor.onExit(previous, context);
                } catch (Exception e) {
                    try { actor.onError(envelope.payload(), e, context); } catch (Exception ignored) {}
                }
                currentState = next.get();
                try {
                    actor.onEnter(currentState, context);
                } catch (Exception e) {
                    try { actor.onError(envelope.payload(), e, context); } catch (Exception ignored) {}
                }
            }
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
            try { actor.onError(null, e, context); } catch (Exception ignored) {}
        }
    }
}
