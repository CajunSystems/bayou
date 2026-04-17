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
}
