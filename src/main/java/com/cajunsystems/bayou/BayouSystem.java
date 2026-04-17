package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.EventSourcedActor;
import com.cajunsystems.bayou.actor.StatefulActor;
import com.cajunsystems.bayou.actor.Actor;
import com.cajunsystems.gumbo.api.SharedLog;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

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
 *     Ref<String> echo = system.spawn("echo",
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
    private final ConcurrentHashMap<String, Ref<?>> actors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AbstractActorRunner<?>> runners = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduledExecutor =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofPlatform().daemon(true).name("bayou-timer").unstarted(r);
            return t;
        });

    public BayouSystem(SharedLog sharedLog) {
        this.sharedLog = sharedLog;
    }

    // ── Package-private accessor for runners ─────────────────────────────────

    SharedLog sharedLog() {
        return sharedLog;
    }

    ScheduledExecutorService scheduledExecutor() {
        return scheduledExecutor;
    }

    /** Called by {@link SupervisorRunner} to register child actors spawned internally. */
    void registerActor(String actorId, Ref<?> ref) {
        checkNotDuplicate(actorId);
        actors.put(actorId, ref);
    }

    void registerRunner(String actorId, AbstractActorRunner<?> runner) {
        runners.put(actorId, runner);
    }

    AbstractActorRunner<?> lookupRunner(String actorId) {
        return runners.get(actorId);
    }

    /** Called by {@link SupervisorRunner#cleanup()} to deregister children on stop/escalation. */
    void unregisterActor(String actorId) {
        actors.remove(actorId);
        runners.remove(actorId);
    }

    // ── Spawn: stateless ─────────────────────────────────────────────────────

    /**
     * Spawn a stateless actor. No log interaction occurs on behalf of this actor.
     *
     * @param actorId   unique identity within this system
     * @param actor     {@link Actor} implementation (may be a lambda)
     * @param <M>       message type
     * @return a reference for sending messages
     */
    public <M> Ref<M> spawn(String actorId, Actor<M> actor) {
        return spawn(actorId, actor, MailboxConfig.unbounded());
    }

    /**
     * Spawn a stateless actor with a custom mailbox configuration.
     *
     * @param actorId       unique identity within this system
     * @param actor         {@link Actor} implementation (may be a lambda)
     * @param mailboxConfig mailbox capacity and overflow strategy
     * @param <M>           message type
     * @return a reference for sending messages
     */
    public <M> Ref<M> spawn(String actorId, Actor<M> actor, MailboxConfig mailboxConfig) {
        checkNotDuplicate(actorId);
        var runner = new StatelessActorRunner<>(actorId, this, actor, mailboxConfig);
        runner.start();
        Ref<M> ref = runner.toRef();
        actors.put(actorId, ref);
        runners.put(actorId, runner);
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
    public <S, E, M> Ref<M> spawnEventSourced(String actorId,
                                                EventSourcedActor<S, E, M> actor,
                                                BayouSerializer<E> eventSerializer) {
        return spawnEventSourced(actorId, actor, eventSerializer, MailboxConfig.unbounded());
    }

    /**
     * Spawn an event-sourced actor with a custom mailbox configuration.
     *
     * @param actorId         unique identity within this system
     * @param actor           event-sourced actor implementation
     * @param eventSerializer serializer for the event type {@code E}
     * @param mailboxConfig   mailbox capacity and overflow strategy
     * @param <S>             state type
     * @param <E>             event type
     * @param <M>             message type
     * @return a reference for sending messages
     */
    public <S, E, M> Ref<M> spawnEventSourced(String actorId,
                                                EventSourcedActor<S, E, M> actor,
                                                BayouSerializer<E> eventSerializer,
                                                MailboxConfig mailboxConfig) {
        checkNotDuplicate(actorId);
        var runner = new EventSourcedActorRunner<>(actorId, this, actor, eventSerializer, mailboxConfig);
        runner.start();
        Ref<M> ref = runner.toRef();
        actors.put(actorId, ref);
        runners.put(actorId, runner);
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
    public <S, M> Ref<M> spawnStateful(String actorId,
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
    public <S, M> Ref<M> spawnStateful(String actorId,
                                        StatefulActor<S, M> actor,
                                        BayouSerializer<S> stateSerializer,
                                        int snapshotInterval) {
        return spawnStateful(actorId, actor, stateSerializer, snapshotInterval, MailboxConfig.unbounded());
    }

    /**
     * Spawn a stateful actor with a custom snapshot interval and mailbox configuration.
     *
     * @param actorId          unique identity within this system
     * @param actor            stateful actor implementation
     * @param stateSerializer  serializer for the state type {@code S}
     * @param snapshotInterval how many messages between automatic snapshots (must be &gt; 0)
     * @param mailboxConfig    mailbox capacity and overflow strategy
     * @param <S>              state type
     * @param <M>              message type
     * @return a reference for sending messages
     */
    public <S, M> Ref<M> spawnStateful(String actorId,
                                        StatefulActor<S, M> actor,
                                        BayouSerializer<S> stateSerializer,
                                        int snapshotInterval,
                                        MailboxConfig mailboxConfig) {
        if (snapshotInterval <= 0) throw new IllegalArgumentException("snapshotInterval must be > 0");
        checkNotDuplicate(actorId);
        var runner = new StatefulActorRunner<>(actorId, this, actor, stateSerializer, snapshotInterval, mailboxConfig);
        runner.start();
        Ref<M> ref = runner.toRef();
        actors.put(actorId, ref);
        runners.put(actorId, runner);
        return ref;
    }

    // ── Spawn: supervisor ─────────────────────────────────────────────────────

    /**
     * Spawn a supervisor that owns and monitors a group of child actors.
     *
     * <p>The supervisor starts all declared children immediately. When a child crashes,
     * the supervisor applies its {@link SupervisionStrategy} to decide what to do.
     *
     * <pre>{@code
     * SupervisorRef ref = system.spawnSupervisor("my-supervisor", new SupervisorActor() {
     *     public List<ChildSpec> children() {
     *         return List.of(ChildSpec.stateless("worker", (msg, ctx) -> ...));
     *     }
     *     public SupervisionStrategy strategy() {
     *         return new OneForOneStrategy(RestartWindow.UNLIMITED);
     *     }
     * });
     * }</pre>
     *
     * @param actorId        unique identity for the supervisor within this system
     * @param supervisorActor the supervision definition (children + strategy)
     * @return a reference for managing the supervisor lifecycle
     */
    public SupervisorRef spawnSupervisor(String actorId, SupervisorActor supervisorActor) {
        checkNotDuplicate(actorId);
        List<ChildSpec> children = supervisorActor.children();
        for (ChildSpec child : children) {
            checkNotDuplicate(child.actorId());
        }
        var runner = new SupervisorRunner(actorId, this, supervisorActor.strategy(), children, MailboxConfig.unbounded());
        runner.start();
        SupervisorRef ref = runner.toSupervisorRef();
        actors.put(actorId, ref);
        runners.put(actorId, runner);
        return ref;
    }

    // ── Death watch ──────────────────────────────────────────────────────────

    /**
     * Register a death watch on {@code target}. When the target actor stops (crash or graceful),
     * {@code watcher} receives a {@link Terminated} signal via its {@code onSignal} handler.
     */
    public WatchHandle watch(Ref<?> target, Ref<?> watcher) {
        AbstractActorRunner<?> targetRunner = runners.get(target.actorId());
        AbstractActorRunner<?> watcherRunner = runners.get(watcher.actorId());
        if (targetRunner == null) throw new IllegalArgumentException("Unknown target actor: " + target.actorId());
        if (watcherRunner == null) throw new IllegalArgumentException("Unknown watcher actor: " + watcher.actorId());
        Consumer<Signal> listener = watcherRunner::signal;
        targetRunner.signalListeners.add(listener);
        return new WatchHandleImpl(targetRunner, listener);
    }

    /** Cancel a previously registered death watch. Idempotent. */
    public void unwatch(WatchHandle handle) {
        handle.cancel();
    }

    // ── Actor linking ─────────────────────────────────────────────────────────

    /**
     * Bidirectionally link two actors. If either dies (crash or graceful stop), the other
     * receives a {@link LinkedActorDied} signal. Unless the surviving actor has
     * {@link BayouContext#trapExits} enabled, it will also crash.
     *
     * <p>Linking a stopped actor or linking an actor to itself is a no-op.
     */
    public void link(Ref<?> a, Ref<?> b) {
        if (a.actorId().equals(b.actorId())) return;
        AbstractActorRunner<?> runnerA = runners.get(a.actorId());
        AbstractActorRunner<?> runnerB = runners.get(b.actorId());
        if (runnerA == null) throw new IllegalArgumentException("Unknown actor: " + a.actorId());
        if (runnerB == null) throw new IllegalArgumentException("Unknown actor: " + b.actorId());
        if (!runnerA.isAlive() || !runnerB.isAlive()) return;
        runnerA.linkedRunners.add(runnerB);
        runnerB.linkedRunners.add(runnerA);
    }

    /** Remove the bidirectional link between two actors. Idempotent. */
    public void unlink(Ref<?> a, Ref<?> b) {
        AbstractActorRunner<?> runnerA = runners.get(a.actorId());
        AbstractActorRunner<?> runnerB = runners.get(b.actorId());
        if (runnerA != null && runnerB != null) {
            runnerA.linkedRunners.remove(runnerB);
            runnerB.linkedRunners.remove(runnerA);
        }
    }

    // ── Shutdown ─────────────────────────────────────────────────────────────

    /**
     * Gracefully stop all actors (draining their mailboxes), then close the shared log.
     * The log is always closed even if one or more actors fail to stop cleanly.
     */
    public void shutdown() {
        scheduledExecutor.shutdownNow();
        CompletableFuture<?>[] stops = actors.values().stream()
                .map(Ref::stop)
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(stops).join();
        } finally {
            actors.clear();
            runners.clear();
            sharedLog.close();
        }
    }

    /** Alias for {@link #shutdown()} to satisfy {@link AutoCloseable}. */
    @Override
    public void close() {
        shutdown();
    }

    // ── Lookup ───────────────────────────────────────────────────────────────

    /**
     * Look up a previously spawned actor by its ID.
     *
     * @param actorId the actor's stable identity
     * @return the ref, or {@link Optional#empty()} if no actor with that ID is registered
     */
    @SuppressWarnings("unchecked")
    public <M> Optional<Ref<M>> lookup(String actorId) {
        return Optional.ofNullable((Ref<M>) actors.get(actorId));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void checkNotDuplicate(String actorId) {
        if (actors.containsKey(actorId)) {
            throw new IllegalArgumentException("Actor already registered with id: " + actorId);
        }
    }
}
