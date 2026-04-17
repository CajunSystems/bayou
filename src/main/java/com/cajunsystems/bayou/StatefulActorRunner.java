package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.StatefulActor;
import com.cajunsystems.gumbo.api.LogView;
import com.cajunsystems.gumbo.core.LogEntry;
import com.cajunsystems.gumbo.core.LogTag;

import java.util.List;

/**
 * Runner for {@link StatefulActor}.
 *
 * <p>Log tag used: {@code bayou.snapshots:<actorId>}
 *
 * <p>On {@link #initialize()} the latest snapshot entry (if any) is deserialized to
 * restore state; no full replay is required.  A snapshot is written to the log every
 * {@code snapshotInterval} messages, and unconditionally on actor stop.
 */
final class StatefulActorRunner<S, M> extends AbstractActorRunner<M> {

    private final StatefulActor<S, M> actor;
    private final BayouSerializer<S> stateSerializer;
    private final LogView snapshotView;
    private final int snapshotInterval;

    private S state;
    private int messagesSinceSnapshot = 0;

    StatefulActorRunner(String actorId, BayouSystem system,
                         StatefulActor<S, M> actor,
                         BayouSerializer<S> stateSerializer,
                         int snapshotInterval,
                         MailboxConfig mailboxConfig) {
        super(actorId, system, mailboxConfig);
        this.actor = actor;
        this.stateSerializer = stateSerializer;
        this.snapshotView = system.sharedLog().getView(LogTag.of("bayou.snapshots", actorId));
        this.snapshotInterval = snapshotInterval;
    }

    @Override
    protected void initialize() {
        state = actor.initialState();
        try {
            List<LogEntry> snapshots = snapshotView.readAll().join();
            if (!snapshots.isEmpty()) {
                LogEntry latest = snapshots.get(snapshots.size() - 1);
                state = stateSerializer.deserialize(latest.dataUnsafe());
                context.logger().debug("Restored snapshot for actor '{}' (seqnum {})",
                        actorId, latest.seqnum());
            }
        } catch (Exception e) {
            throw new RuntimeException("Snapshot restore failed for actor '" + actorId + "'", e);
        }
        actor.preStart(context);
    }

    @Override
    protected void processEnvelope(Envelope<M> envelope) {
        S previousState = state;
        try {
            state = actor.reduce(state, envelope.payload(), context);
            messagesSinceSnapshot++;
            if (messagesSinceSnapshot >= snapshotInterval) {
                takeSnapshot();
            }
        } catch (Exception e) {
            state = previousState; // leave state unchanged on error
            try {
                actor.onError(envelope.payload(), e, context);
            } catch (Exception onErrorEx) {
                context.logger().error("onError handler threw for actor '{}'", actorId, onErrorEx);
            }
        }
    }

    @Override
    protected void cleanup() {
        if (messagesSinceSnapshot > 0) {
            takeSnapshot();
        }
        actor.postStop(context);
    }

    @Override
    protected void handleSignal(Signal signal) {
        S previousState = state;
        try {
            state = actor.onSignal(state, signal, context);
        } catch (Exception e) {
            state = previousState;
            try {
                actor.onError(null, e, context);
            } catch (Exception ignored) {}
        }
    }

    private void takeSnapshot() {
        try {
            byte[] bytes = stateSerializer.serialize(state);
            snapshotView.append(bytes).join();
            messagesSinceSnapshot = 0;
            context.logger().debug("Snapshot taken for actor '{}'", actorId);
        } catch (Exception e) {
            context.logger().error("Failed to take snapshot for actor '{}'", actorId, e);
        }
    }
}
