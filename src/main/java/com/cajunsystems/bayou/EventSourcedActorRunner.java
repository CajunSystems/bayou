package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.EventSourcedActor;
import com.cajunsystems.gumbo.api.LogView;
import com.cajunsystems.gumbo.core.LogEntry;
import com.cajunsystems.gumbo.core.LogTag;

import java.util.List;

/**
 * Runner for {@link EventSourcedActor}.
 *
 * <p>Log tag used: {@code bayou.events:<actorId>}
 *
 * <p>On {@link #initialize()} every existing entry is read and folded through
 * {@link EventSourcedActor#apply} to reconstruct state.  During normal operation each
 * call to {@link EventSourcedActor#handle} produces a list of events that are appended
 * to the log and folded into the in-memory state before the next message is delivered.
 */
final class EventSourcedActorRunner<S, E, M> extends AbstractActorRunner<M> {

    private final EventSourcedActor<S, E, M> actor;
    private final BayouSerializer<E> eventSerializer;
    private final LogView eventView;
    private S state;

    EventSourcedActorRunner(String actorId, BayouSystem system,
                             EventSourcedActor<S, E, M> actor,
                             BayouSerializer<E> eventSerializer) {
        super(actorId, system);
        this.actor = actor;
        this.eventSerializer = eventSerializer;
        this.eventView = system.sharedLog().getView(LogTag.of("bayou.events", actorId));
    }

    @Override
    protected void initialize() {
        state = actor.initialState();
        try {
            List<LogEntry> entries = eventView.readAll().join();
            for (LogEntry entry : entries) {
                E event = eventSerializer.deserialize(entry.dataUnsafe());
                state = actor.apply(state, event);
            }
        } catch (Exception e) {
            throw new RuntimeException("Event replay failed for actor '" + actorId + "'", e);
        }
        actor.preStart(context);
    }

    @Override
    protected void processEnvelope(Envelope<M> envelope) {
        try {
            List<E> events = actor.handle(state, envelope.payload(), context);
            for (E event : events) {
                byte[] bytes = eventSerializer.serialize(event);
                eventView.append(bytes).join();
                state = actor.apply(state, event);
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
            List<E> events = actor.onSignal(state, signal, context);
            for (E event : events) {
                byte[] bytes = eventSerializer.serialize(event);
                eventView.append(bytes).join();
                state = actor.apply(state, event);
            }
        } catch (Exception e) {
            try {
                actor.onError(null, e, context);
            } catch (Exception ignored) {}
        }
    }
}
