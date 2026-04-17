package com.cajunsystems.bayou;

/**
 * Commands handled by the internal {@link TopicActor}.
 *
 * <p>Package-private — callers interact with {@link BayouTopic} instead.
 *
 * @param <M> the message type published on the topic
 */
sealed interface TopicCommand<M> {

    /** Append {@code payload} to the durable log and deliver to all live subscribers. */
    record Publish<M>(M payload) implements TopicCommand<M> {}

    /** Add {@code subscriber} to the live-delivery list. */
    record Subscribe<M>(Ref<M> subscriber) implements TopicCommand<M> {}

    /** Remove {@code subscriber} from the live-delivery list. */
    record Unsubscribe<M>(Ref<M> subscriber) implements TopicCommand<M> {}

    /** Register a durable subscriber that resumes from its last processed offset. */
    record SubscribeDurable<M>(String subscriptionId, Ref<M> subscriber) implements TopicCommand<M> {}

    /** Cancel a durable subscription and forget its stored position. */
    record UnsubscribeDurable<M>(String subscriptionId) implements TopicCommand<M> {}

    /** Subscribe and replay all entries with seqnum > {@code offset}, then receive live messages. */
    record SubscribeFrom<M>(long offset, Ref<M> subscriber) implements TopicCommand<M> {}
}
