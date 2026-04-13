package com.cajunsystems.bayou;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Package-private runner for a supervised group of actors.
 *
 * <p>The supervisor's mailbox accepts {@link ChildCrash} signals routed here by each
 * child's crash listener. Crashes are processed sequentially on the supervisor's own
 * virtual thread.
 *
 * <p>Phase 3: calls {@link SupervisionStrategy#decide} and logs the decision.
 * Phase 4 will act on it (restart / stop / escalate).
 */
final class SupervisorRunner extends AbstractActorRunner<ChildCrash> {

    private final SupervisionStrategy strategy;
    private final List<ChildSpec> childSpecs;
    // CopyOnWriteArrayList: supervisor thread reads during crash handling; any thread may write via spawnChild()
    private final List<AbstractActorRunner<?>> childRunners = new CopyOnWriteArrayList<>();
    private final Logger logger;

    SupervisorRunner(String actorId, BayouSystem system,
                     SupervisionStrategy strategy,
                     List<ChildSpec> childSpecs) {
        super(actorId, system);
        this.strategy = strategy;
        this.childSpecs = List.copyOf(childSpecs);
        this.logger = LoggerFactory.getLogger("bayou.supervisor." + actorId);
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    protected void initialize() {
        for (ChildSpec spec : childSpecs) {
            startAndRegister(spec);
        }
        logger.info("Supervisor '{}' started {} children", actorId, childRunners.size());
    }

    @Override
    protected void processEnvelope(Envelope<ChildCrash> envelope) {
        ChildCrash crash = envelope.payload();
        RestartDecision decision = strategy.decide(crash.actorId(), crash.cause());
        logger.info("Supervisor '{}': child '{}' crashed ({}), decision: {}",
                actorId, crash.actorId(), crash.cause().getMessage(), decision);
        // Phase 4 will act on the decision (restart / stop / escalate)
    }

    @Override
    protected void cleanup() {
        logger.info("Supervisor '{}' stopping {} children", actorId, childRunners.size());
        CompletableFuture<?>[] stops = childRunners.stream()
                .map(AbstractActorRunner::stop)
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(stops).join();
    }

    // ── Dynamic child spawning ─────────────────────────────────────────────────

    /**
     * Spawn a new child under this supervisor at runtime.
     * Thread-safe: may be called from any thread.
     */
    ActorRef<?> spawnChild(ChildSpec spec) {
        ActorRef<?> ref = startAndRegister(spec);
        logger.info("Supervisor '{}': dynamically spawned child '{}'", actorId, spec.actorId());
        return ref;
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    private ActorRef<?> startAndRegister(ChildSpec spec) {
        AbstractActorRunner<?> runner = createChildRunner(spec);
        runner.setCrashListener(crash -> this.tell(crash));
        runner.start();
        childRunners.add(runner);
        context.system().registerActor(spec.actorId(), runner.toActorRef());
        return runner.toActorRef();
    }

    // ── Child factory ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private AbstractActorRunner<?> createChildRunner(ChildSpec spec) {
        if (spec instanceof StatelessChildSpec<?> s) {
            return new StatelessActorRunner<>(s.actorId(), context.system(), s.actor());
        } else if (spec instanceof StatefulChildSpec<?, ?> s) {
            return new StatefulActorRunner<>(s.actorId(), context.system(),
                    ((StatefulChildSpec<Object, Object>) s).actor(),
                    ((StatefulChildSpec<Object, Object>) s).stateSerializer(),
                    s.snapshotInterval());
        } else if (spec instanceof EventSourcedChildSpec<?, ?, ?> s) {
            return new EventSourcedActorRunner<>(s.actorId(), context.system(),
                    ((EventSourcedChildSpec<Object, Object, Object>) s).actor(),
                    ((EventSourcedChildSpec<Object, Object, Object>) s).eventSerializer());
        }
        throw new IllegalStateException("Unknown ChildSpec type: " + spec.getClass());
    }

    // ── Public ref ─────────────────────────────────────────────────────────────

    SupervisorRef toSupervisorRef() {
        return new SupervisorActorRefImpl(this);
    }
}
