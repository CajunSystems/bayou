package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.EventSourcedActor;
import com.cajunsystems.bayou.actor.StatefulActor;
import com.cajunsystems.bayou.actor.StatelessActor;
import com.cajunsystems.gumbo.api.SharedLog;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry point for the Bayou actor system.
 *
 * <p>Wraps a {@link SharedLog} (gumbo) instance and provides factory methods for all three
 * actor flavours.  Each spawned actor is backed by a dedicated virtual thread.
 *
 * <pre>{@code
 * SharedLogConfig config = SharedLogConfig.builder()
 *         .persistenceAdapter(new InMemoryPersistenceAdapter())
 *         .build();
 * try (SharedLogService log = SharedLogService.open(config);
 *      BayouSystem system = new BayouSystem(log)) {
 *
 *     ActorRef<String> echo = system.spawn("echo",
 *             (msg, ctx) -> ctx.logger().info("echo: {}", msg));
 *     echo.tell("hello");
 *     system.shutdown();
 * }
 * }</pre>
 */
public class BayouSystem implements AutoCloseable {

    /** Default number of messages between automatic snapshots for stateful actors. */
    public static final int DEFAULT_SNAPSHOT_INTERVAL = 100;

    private final SharedLog sharedLog;
    private final ConcurrentHashMap<String, ActorRef<?>> actors = new ConcurrentHashMap<>();

    public BayouSystem(SharedLog sharedLog) {
        this.sharedLog = sharedLog;
    }

    // ── Package-private accessor for runners ─────────────────────────────────

    SharedLog sharedLog() {
        return sharedLog;
    }

    // ── Spawn: stateless ─────────────────────────────────────────────────────

    /**
     * Spawn a stateless actor. No log interaction occurs on behalf of this actor.
     *
     * @param actorId   unique identity within this system
     * @param actor     handler implementation (may be a lambda)
     * @param <M>       message type
     * @return a reference for sending messages
     */
    public <M> ActorRef<M> spawn(String actorId, StatelessActor<M> actor) {
        checkNotDuplicate(actorId);
        var runner = new StatelessActorRunner<>(actorId, this, actor);
        runner.start();
        ActorRef<M> ref = runner.toActorRef();
        actors.put(actorId, ref);
        return ref;
    }

    // ── Spawn: event-sourced ─────────────────────────────────────────────────

    /**
     * Spawn an event-sourced actor.
     *
     * <p>On startup all events stored under {@code bayou.events:<actorId>} are replayed to
     * reconstruct state.  Each handled message appends the returned events to that tag.
     *
     * @param actorId         unique identity within this system
     * @param actor           event-sourced actor implementation
     * @param eventSerializer serializer for the event type {@code E}
     * @param <S>             state type
     * @param <E>             event type
     * @param <M>             message type
     * @return a reference for sending messages
     */
    public <S, E, M> ActorRef<M> spawnEventSourced(String actorId,
                                                     EventSourcedActor<S, E, M> actor,
                                                     BayouSerializer<E> eventSerializer) {
        checkNotDuplicate(actorId);
        var runner = new EventSourcedActorRunner<>(actorId, this, actor, eventSerializer);
        runner.start();
        ActorRef<M> ref = runner.toActorRef();
        actors.put(actorId, ref);
        return ref;
    }

    // ── Spawn: stateful ──────────────────────────────────────────────────────

    /**
     * Spawn a stateful actor with the default snapshot interval ({@value #DEFAULT_SNAPSHOT_INTERVAL}).
     *
     * @param actorId         unique identity within this system
     * @param actor           stateful actor implementation
     * @param stateSerializer serializer for the state type {@code S}
     * @param <S>             state type
     * @param <M>             message type
     * @return a reference for sending messages
     */
    public <S, M> ActorRef<M> spawnStateful(String actorId,
                                              StatefulActor<S, M> actor,
                                              BayouSerializer<S> stateSerializer) {
        return spawnStateful(actorId, actor, stateSerializer, DEFAULT_SNAPSHOT_INTERVAL);
    }

    /**
     * Spawn a stateful actor with a custom snapshot interval.
     *
     * <p>A snapshot is written to {@code bayou.snapshots:<actorId>} every {@code snapshotInterval}
     * messages, and unconditionally on stop.  On startup the latest snapshot is read and used
     * as the initial state — no replay is needed.
     *
     * @param actorId          unique identity within this system
     * @param actor            stateful actor implementation
     * @param stateSerializer  serializer for the state type {@code S}
     * @param snapshotInterval how many messages between automatic snapshots (must be &gt; 0)
     * @param <S>              state type
     * @param <M>              message type
     * @return a reference for sending messages
     */
    public <S, M> ActorRef<M> spawnStateful(String actorId,
                                              StatefulActor<S, M> actor,
                                              BayouSerializer<S> stateSerializer,
                                              int snapshotInterval) {
        if (snapshotInterval <= 0) throw new IllegalArgumentException("snapshotInterval must be > 0");
        checkNotDuplicate(actorId);
        var runner = new StatefulActorRunner<>(actorId, this, actor, stateSerializer, snapshotInterval);
        runner.start();
        ActorRef<M> ref = runner.toActorRef();
        actors.put(actorId, ref);
        return ref;
    }

    // ── Shutdown ─────────────────────────────────────────────────────────────

    /**
     * Gracefully stop all actors (draining their mailboxes), then close the shared log.
     */
    public void shutdown() {
        CompletableFuture<?>[] stops = actors.values().stream()
                .map(ActorRef::stop)
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(stops).join();
        actors.clear();
        sharedLog.close();
    }

    /** Alias for {@link #shutdown()} to satisfy {@link AutoCloseable}. */
    @Override
    public void close() {
        shutdown();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void checkNotDuplicate(String actorId) {
        if (actors.containsKey(actorId)) {
            throw new IllegalArgumentException("Actor already registered with id: " + actorId);
        }
    }
}
