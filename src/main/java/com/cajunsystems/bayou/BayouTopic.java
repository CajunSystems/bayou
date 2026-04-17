package com.cajunsystems.bayou;

/**
 * A durable, log-backed topic that survives process restarts.
 *
 * <p>Obtained via {@link BayouSystem#topic(String, BayouSerializer)}.  The call is
 * idempotent — the same {@code BayouTopic} instance is returned for the same name.
 *
 * <p>All three operations ({@link #publish}, {@link #subscribe}, {@link #unsubscribe})
 * are fire-and-forget tells to the underlying {@link TopicActor}.
 *
 * @param <M> the message type published on this topic
 */
public final class BayouTopic<M> {

    private final Ref<TopicCommand<M>> actorRef;

    BayouTopic(Ref<TopicCommand<M>> actorRef) {
        this.actorRef = actorRef;
    }

    /**
     * Append {@code message} to the durable log and deliver it to all live subscribers.
     * Fire-and-forget — returns immediately.
     */
    public void publish(M message) {
        actorRef.tell(new TopicCommand.Publish<>(message));
    }

    /**
     * Add {@code subscriber} to this topic's live-delivery list.
     * The subscriber will receive future published messages (no replay).
     */
    public void subscribe(Ref<M> subscriber) {
        actorRef.tell(new TopicCommand.Subscribe<>(subscriber));
    }

    /**
     * Remove {@code subscriber} from this topic's live-delivery list.
     * No-op if the subscriber is not currently subscribed.
     */
    public void unsubscribe(Ref<M> subscriber) {
        actorRef.tell(new TopicCommand.Unsubscribe<>(subscriber));
    }

    /**
     * Register a durable subscriber. On reconnect (same {@code subscriptionId}), all messages
     * published since the last delivery are replayed before live delivery resumes.
     */
    public void subscribe(String subscriptionId, Ref<M> subscriber) {
        actorRef.tell(new TopicCommand.SubscribeDurable<>(subscriptionId, subscriber));
    }

    /**
     * Cancel a durable subscription and permanently forget its stored position.
     * A subsequent {@link #subscribe(String, Ref)} with the same ID starts fresh from now.
     */
    public void unsubscribeDurable(String subscriptionId) {
        actorRef.tell(new TopicCommand.UnsubscribeDurable<>(subscriptionId));
    }
}
