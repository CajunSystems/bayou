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
                     List<ChildSpec> childSpecs,
                     MailboxConfig mailboxConfig) {
        super(actorId, system, mailboxConfig);
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

        switch (decision) {
            case RESTART -> {
                logger.info("Supervisor '{}': restarting child '{}'", actorId, crash.actorId());
                crash.runner().restart();
            }
            case RESTART_ALL -> {
                logger.info("Supervisor '{}': all-for-one restart triggered by '{}'",
                        actorId, crash.actorId());
                // Stop all alive siblings (crashed runner already has running=false)
                CompletableFuture<?>[] stops = childRunners.stream()
                        .filter(AbstractActorRunner::isAlive)
                        .map(AbstractActorRunner::stop)
                        .toArray(CompletableFuture[]::new);
                CompletableFuture.allOf(stops).join();
                // Restart all children in declaration order (including crashed one)
                for (AbstractActorRunner<?> runner : childRunners) {
                    runner.restart();
                }
                logger.info("Supervisor '{}': all-for-one restart complete", actorId);
            }
            case STOP ->
                logger.info("Supervisor '{}': child '{}' permanently stopped",
                        actorId, crash.actorId());
            case ESCALATE -> {
                logger.info("Supervisor '{}': child '{}' exceeded restart window — escalating",
                        actorId, crash.actorId());
                escalate(crash.cause());
            }
        }
    }

    @Override
    protected void cleanup() {
        logger.info("Supervisor '{}' stopping {} children", actorId, childRunners.size());
        CompletableFuture<?>[] stops = childRunners.stream()
                .map(AbstractActorRunner::stop)
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(stops).join();
        for (AbstractActorRunner<?> runner : childRunners) {
            context.system().unregisterActor(runner.actorId);
        }
        childRunners.clear();
    }

    // ── Dynamic child spawning ─────────────────────────────────────────────────

    /**
     * Spawn a new child under this supervisor at runtime.
     * Thread-safe: may be called from any thread.
     */
    Ref<?> spawnChild(ChildSpec spec) {
        Ref<?> ref = startAndRegister(spec);
        logger.info("Supervisor '{}': dynamically spawned child '{}'", actorId, spec.actorId());
        return ref;
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    private Ref<?> startAndRegister(ChildSpec spec) {
        AbstractActorRunner<?> runner = createChildRunner(spec);
        runner.setCrashListener(crash -> this.tell(crash));
        runner.start();
        childRunners.add(runner);
        Ref<?> ref = (runner instanceof SupervisorRunner sr)
                ? sr.toSupervisorRef()
                : runner.toRef();
        context.system().registerActor(spec.actorId(), ref);
        return ref;
    }

    // ── Child factory ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private AbstractActorRunner<?> createChildRunner(ChildSpec spec) {
        if (spec instanceof StatelessChildSpec<?> s) {
            return new StatelessActorRunner<>(s.actorId(), context.system(), s.actor(), s.mailboxConfig());
        } else if (spec instanceof StatefulChildSpec<?, ?> s) {
            return new StatefulActorRunner<>(s.actorId(), context.system(),
                    ((StatefulChildSpec<Object, Object>) s).actor(),
                    ((StatefulChildSpec<Object, Object>) s).stateSerializer(),
                    s.snapshotInterval(),
                    s.mailboxConfig());
        } else if (spec instanceof EventSourcedChildSpec<?, ?, ?> s) {
            return new EventSourcedActorRunner<>(s.actorId(), context.system(),
                    ((EventSourcedChildSpec<Object, Object, Object>) s).actor(),
                    ((EventSourcedChildSpec<Object, Object, Object>) s).eventSerializer(),
                    s.mailboxConfig());
        } else if (spec instanceof SupervisorChildSpec s) {
            return new SupervisorRunner(s.actorId(), context.system(),
                    s.supervisorActor().strategy(),
                    s.supervisorActor().children(),
                    s.mailboxConfig());
        }
        throw new IllegalStateException("Unknown ChildSpec type: " + spec.getClass());
    }

    // ── Public ref ─────────────────────────────────────────────────────────────

    SupervisorRef toSupervisorRef() {
        return new SupervisorRefImpl(this);
    }
}
