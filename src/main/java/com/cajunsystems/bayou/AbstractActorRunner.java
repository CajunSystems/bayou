package com.cajunsystems.bayou;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Base virtual-thread actor runner.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #initialize()} — called once in the actor thread before the message loop.
 *       Subclasses replay logs / restore snapshots here.</li>
 *   <li>Message loop — polls the mailbox and calls {@link #processEnvelope(Envelope)}.</li>
 *   <li>{@link #cleanup()} — called once after the loop exits; subclasses flush snapshots here.</li>
 * </ol>
 *
 * <p>Stopping is graceful: setting {@code running = false} lets the loop drain the remaining
 * mailbox before calling {@link #cleanup()}.
 *
 * @param <M> message type
 */
abstract class AbstractActorRunner<M> {

    final String actorId;
    private final BayouSystem system;

    private final LinkedBlockingQueue<Envelope<M>> mailbox = new LinkedBlockingQueue<>();
    final BayouContextImpl<M> context;
    final AtomicBoolean running = new AtomicBoolean(false);
    private volatile CompletableFuture<Void> stopFuture = new CompletableFuture<>();
    private volatile Consumer<ChildCrash> crashListener;

    /** Active timers scheduled on behalf of this actor. Cancelled on actor stop. */
    final Set<TimerRefImpl> activeTimers = ConcurrentHashMap.newKeySet();

    /** Listeners notified with a {@link Terminated} signal when this actor stops. */
    final List<Consumer<Signal>> signalListeners = new CopyOnWriteArrayList<>();

    AbstractActorRunner(String actorId, BayouSystem system) {
        this.actorId = actorId;
        this.system = system;
        this.context = new BayouContextImpl<>(actorId, system);
        this.context.setRunner(this);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    final void start() {
        running.set(true);
        Thread.ofVirtual()
                .name("bayou-" + actorId)
                .start(this::loop);
    }

    private void loop() {
        Throwable terminalCause = null;
        try {
            initialize();
            while (running.get() || !mailbox.isEmpty()) {
                Envelope<M> env = mailbox.poll(100, TimeUnit.MILLISECONDS);
                if (env != null) {
                    if (env.isSignal()) {
                        processSignalEnvelope(env.signal());
                    } else {
                        context.setCurrentEnvelope(env);
                        processEnvelope(env);
                        context.setCurrentEnvelope(null); // prevent stale reply() calls
                        // If it was an ask and the handler did not reply, fail the future
                        if (env.isAsk() && !env.replyFuture().isDone()) {
                            env.replyFuture().completeExceptionally(
                                    new IllegalStateException(
                                            "Actor '" + actorId + "' did not call reply() for ask message: "
                                                    + env.payload()));
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            terminalCause = e;
            running.set(false);
            context.logger().error("Actor '{}' terminated unexpectedly", actorId, e);
        } finally {
            // Cancel all active timers before calling cleanup()
            activeTimers.forEach(TimerRef::cancel);
            activeTimers.clear();
            try {
                cleanup();
            } catch (Exception e) {
                context.logger().error("Error during cleanup for actor '{}'", actorId, e);
            }
            stopFuture.complete(null);
            // Fire Terminated to all watchers (both crash and graceful stop)
            if (!signalListeners.isEmpty()) {
                Terminated terminated = new Terminated(actorId);
                for (var listener : signalListeners) {
                    try { listener.accept(terminated); } catch (Exception ignored) {}
                }
                signalListeners.clear();
            }
            if (terminalCause != null) {
                var listener = crashListener;
                if (listener != null) {
                    listener.accept(new ChildCrash(actorId, terminalCause, this));
                }
            }
        }
    }

    // ── Messaging ────────────────────────────────────────────────────────────

    final void tell(M message) {
        mailbox.offer(Envelope.tell(message));
    }

    final void signal(Signal signal) {
        mailbox.offer(Envelope.signal(signal));
    }

    private void processSignalEnvelope(Signal signal) {
        // Plan 2 will add: if (signal instanceof LinkedActorDied lad && !trapExits) throw ...
        handleSignal(signal);
    }

    protected void handleSignal(Signal signal) {
        // Default no-op; overridden by actor runners
    }

    @SuppressWarnings("unchecked")
    final <R> CompletableFuture<R> ask(M message) {
        Envelope<M> env = Envelope.ask(message);
        mailbox.offer(env);
        return (CompletableFuture<R>) env.replyFuture();
    }

    final CompletableFuture<Void> stop() {
        running.set(false);
        return stopFuture;
    }

    /** Called by a supervisor runner after construction to register itself for crash notifications. */
    void setCrashListener(Consumer<ChildCrash> listener) {
        this.crashListener = listener;
    }

    /**
     * Restart this runner after a crash or a graceful stop.
     * Resets running state, replaces the stop future, and starts a new virtual thread.
     * The mailbox is preserved — queued messages will be delivered after restart.
     *
     * <p>Caller must ensure the previous virtual thread has fully exited before calling.
     */
    void restart() {
        running.set(true);
        stopFuture = new CompletableFuture<>();
        Thread.ofVirtual().name("bayou-" + actorId).start(this::loop);
    }

    void escalate(Throwable cause) {
        if (crashListener == null) {
            context.logger().error(
                "Actor '{}': top-level supervisor exceeded restart window — stopping permanently",
                actorId, cause);
        }
        throw new EscalationException(actorId, cause);
    }

    // ── Template methods ─────────────────────────────────────────────────────

    /** Subclasses replay events or restore snapshots here (runs inside the actor thread). */
    protected abstract void initialize();

    /** Process one envelope; errors are handled internally by each subclass. */
    protected abstract void processEnvelope(Envelope<M> envelope);

    /** Post-stop hook; called after the message loop exits. */
    protected abstract void cleanup();

    final boolean isAlive() {
        return running.get();
    }

    // ── ActorRef bridge ──────────────────────────────────────────────────────

    final Ref<M> toRef() {
        return new RefImpl<>(this);
    }
}
