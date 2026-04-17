package com.cajunsystems.bayou;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Topic-based publish/subscribe registry for a {@link BayouSystem}.
 *
 * <p>Obtained via {@link BayouSystem#pubsub()}. Thread-safe for concurrent
 * subscribe/publish/unsubscribe. Dead actor references are silently skipped on publish
 * and lazily cleaned up to prevent unbounded list growth.
 */
public class BayouPubSub {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Ref<?>>> subscriptions =
            new ConcurrentHashMap<>();

    BayouPubSub() {}

    /** Subscribe {@code subscriber} to receive messages published to {@code topic}. */
    public <M> void subscribe(String topic, Ref<M> subscriber) {
        subscriptions.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(subscriber);
    }

    /** Remove {@code subscriber} from {@code topic}. No-op if not subscribed. */
    public void unsubscribe(String topic, Ref<?> subscriber) {
        var list = subscriptions.get(topic);
        if (list != null) list.remove(subscriber);
    }

    /**
     * Deliver {@code message} to all live subscribers on {@code topic}.
     *
     * <p>Dead actors are silently skipped. Bounded-mailbox overflows
     * ({@link MailboxFullException}) are silently swallowed. Dead subscribers are
     * lazily removed after each publish pass.
     */
    @SuppressWarnings("unchecked")
    public <M> void publish(String topic, M message) {
        var subscribers = subscriptions.get(topic);
        if (subscribers == null) return;
        boolean hadDead = false;
        for (Ref<?> ref : subscribers) {
            if (!ref.isAlive()) {
                hadDead = true;
                continue;
            }
            try {
                ((Ref<M>) ref).tell(message);
            } catch (Exception ignored) {}
        }
        if (hadDead) subscribers.removeIf(ref -> !ref.isAlive());
    }
}
