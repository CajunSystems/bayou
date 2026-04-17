package com.cajunsystems.bayou;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Package-private implementation of {@link BayouContext}.
 * One instance is created per actor runner and reused across all message deliveries;
 * {@link #setCurrentEnvelope} is called before each dispatch.
 */
class BayouContextImpl<M> implements BayouContext<M> {

    private final String actorId;
    private final BayouSystem system;
    private final Logger logger;
    private Envelope<?> currentEnvelope;
    private AbstractActorRunner<M> runner;

    BayouContextImpl(String actorId, BayouSystem system) {
        this.actorId = actorId;
        this.system = system;
        this.logger = LoggerFactory.getLogger("bayou.actor." + actorId);
    }

    void setCurrentEnvelope(Envelope<?> envelope) {
        this.currentEnvelope = envelope;
    }

    void setRunner(AbstractActorRunner<M> runner) {
        this.runner = runner;
    }

    @Override
    public String actorId() {
        return actorId;
    }

    @Override
    public BayouSystem system() {
        return system;
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void reply(Object value) {
        if (currentEnvelope != null && currentEnvelope.isAsk()) {
            ((CompletableFuture<Object>) currentEnvelope.replyFuture()).complete(value);
        }
    }

    @Override
    public TimerRef scheduleOnce(Duration delay, M message) {
        @SuppressWarnings("unchecked")
        TimerRefImpl[] holder = new TimerRefImpl[1];
        ScheduledFuture<?> future = system.scheduledExecutor().schedule(
            () -> {
                runner.activeTimers.remove(holder[0]);
                if (runner.isAlive()) {
                    runner.tell(message);
                }
            },
            delay.toMillis(),
            TimeUnit.MILLISECONDS
        );
        holder[0] = new TimerRefImpl(future);
        runner.activeTimers.add(holder[0]);
        return holder[0];
    }

    @Override
    public TimerRef schedulePeriodic(Duration period, M message) {
        ScheduledFuture<?> future = system.scheduledExecutor().scheduleAtFixedRate(
            () -> {
                if (runner.isAlive()) {
                    runner.tell(message);
                }
            },
            period.toMillis(),
            period.toMillis(),
            TimeUnit.MILLISECONDS
        );
        TimerRefImpl ref = new TimerRefImpl(future);
        runner.activeTimers.add(ref);
        return ref;
    }

    @Override
    public WatchHandle watch(Ref<?> target) {
        AbstractActorRunner<?> targetRunner = system.lookupRunner(target.actorId());
        if (targetRunner == null) throw new IllegalArgumentException("Unknown target actor: " + target.actorId());
        Consumer<Signal> listener = runner::signal;
        targetRunner.signalListeners.add(listener);
        return new WatchHandleImpl(targetRunner, listener);
    }

    @Override
    public void unwatch(WatchHandle handle) {
        handle.cancel();
    }

    @Override
    public void link(Ref<?> other) {
        system.link(runner.toRef(), other);
    }

    @Override
    public void unlink(Ref<?> other) {
        system.unlink(runner.toRef(), other);
    }

    @Override
    public void trapExits(boolean flag) {
        runner.trapExits = flag;
    }

    @Override
    public Ref<M> self() {
        return runner.toRef();
    }
}
