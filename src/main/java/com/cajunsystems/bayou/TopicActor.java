package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.Actor;
import com.cajunsystems.gumbo.api.LogView;
import com.cajunsystems.gumbo.core.LogTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Internal actor that backs a {@link BayouTopic}.
 *
 * <p>Package-private — never exposed directly to callers.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>On {@link TopicCommand.Publish}: serialize the payload and append it to the
 *       topic's {@link LogView}; then deliver the payload to all live subscribers,
 *       lazily removing dead ones.</li>
 *   <li>On {@link TopicCommand.Subscribe}: add the {@link Ref} to {@code liveSubscribers}.</li>
 *   <li>On {@link TopicCommand.Unsubscribe}: remove the {@link Ref} from {@code liveSubscribers}.</li>
 * </ul>
 *
 * @param <M> the message type published on the topic
 */
final class TopicActor<M> implements Actor<TopicCommand<M>> {

    private static final Logger log = LoggerFactory.getLogger(TopicActor.class);

    private final String name;
    private final BayouSerializer<M> serializer;
    private final CopyOnWriteArrayList<Ref<M>> liveSubscribers = new CopyOnWriteArrayList<>();

    private LogView logView;

    TopicActor(String name, BayouSerializer<M> serializer) {
        this.name = name;
        this.serializer = serializer;
    }

    @Override
    public void preStart(BayouContext<TopicCommand<M>> ctx) {
        logView = ctx.system().sharedLog().getView(LogTag.of("bayou.topic", name));
    }

    @Override
    public void handle(TopicCommand<M> message, BayouContext<TopicCommand<M>> ctx) {
        switch (message) {
            case TopicCommand.Publish<M> pub -> handlePublish(pub.payload(), ctx);
            case TopicCommand.Subscribe<M> sub -> liveSubscribers.add(sub.subscriber());
            case TopicCommand.Unsubscribe<M> unsub -> liveSubscribers.remove(unsub.subscriber());
        }
    }

    @SuppressWarnings("unchecked")
    private void handlePublish(M payload, BayouContext<TopicCommand<M>> ctx) {
        // 1. Persist to log
        try {
            byte[] bytes = serializer.serialize(payload);
            logView.append(bytes).join();
        } catch (Exception e) {
            ctx.logger().error("Topic '{}': failed to append to log", name, e);
        }

        // 2. Deliver to live subscribers
        boolean hadDead = false;
        for (Ref<M> ref : liveSubscribers) {
            if (!ref.isAlive()) {
                hadDead = true;
                continue;
            }
            try {
                ref.tell(payload);
            } catch (Exception ignored) {}
        }
        if (hadDead) {
            liveSubscribers.removeIf(ref -> !ref.isAlive());
        }
    }
}
