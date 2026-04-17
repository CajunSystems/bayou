package com.cajunsystems.bayou;

import com.cajunsystems.gumbo.api.LogView;

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
    private final LogView logView;

    BayouTopic(Ref<TopicCommand<M>> actorRef, LogView logView) {
        this.actorRef = actorRef;
        this.logView = logView;
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

    /**
     * Subscribe and replay all log entries with seqnum greater than {@code offset}, then receive
     * future messages live. Replay completes before any new publish is delivered to this subscriber.
     *
     * <p>Pass {@code offset = 0} (or use {@link #subscribeFromBeginning}) to receive all history.
     * Pass {@code offset = latestOffset()} to receive only future messages.
     */
    public void subscribeFrom(long offset, Ref<M> subscriber) {
        actorRef.tell(new TopicCommand.SubscribeFrom<>(offset, subscriber));
    }

    /**
     * Subscribe from the very beginning of the topic log, receiving all historical entries
     * followed by live messages. Equivalent to {@code subscribeFrom(-1, subscriber)}.
     *
     * <p>Uses offset {@code -1} because gumbo seqnums are 0-indexed; {@code readAfter(-1)}
     * returns all entries starting from seqnum 0.
     */
    public void subscribeFromBeginning(Ref<M> subscriber) {
        subscribeFrom(-1, subscriber);
    }

    /**
     * Returns the seqnum of the most recently appended entry, or {@code 0} if the topic is empty.
     * Use as the {@code offset} argument to {@link #subscribeFrom} to receive only future messages.
     */
    public long latestOffset() {
        return logView.getLatestSeqnum();
    }
}
