package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.Actor;
import com.cajunsystems.gumbo.api.LogView;
import com.cajunsystems.gumbo.core.AppendResult;
import com.cajunsystems.gumbo.core.LogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
 *       lazily removing dead ones; then deliver to durable subscribers and advance
 *       their stored position.</li>
 *   <li>On {@link TopicCommand.Subscribe}: add the {@link Ref} to {@code liveSubscribers}.</li>
 *   <li>On {@link TopicCommand.Unsubscribe}: remove the {@link Ref} from {@code liveSubscribers}.</li>
 *   <li>On {@link TopicCommand.SubscribeDurable}: replay missed messages and register
 *       for future delivery, tracking position in the log KV store.</li>
 *   <li>On {@link TopicCommand.UnsubscribeDurable}: remove durable subscriber and
 *       delete its stored position.</li>
 * </ul>
 *
 * @param <M> the message type published on the topic
 */
final class TopicActor<M> implements Actor<TopicCommand<M>> {

    private static final Logger log = LoggerFactory.getLogger(TopicActor.class);

    private final String name;
    private final BayouSerializer<M> serializer;
    private final CopyOnWriteArrayList<Ref<M>> liveSubscribers = new CopyOnWriteArrayList<>();
    private final HashMap<String, Ref<M>> durableSubscribers = new HashMap<>();

    private final LogView logView;

    TopicActor(String name, BayouSerializer<M> serializer, LogView logView) {
        this.name = name;
        this.serializer = serializer;
        this.logView = logView;
    }

    @Override
    public void handle(TopicCommand<M> message, BayouContext<TopicCommand<M>> ctx) {
        switch (message) {
            case TopicCommand.Publish<M> pub -> handlePublish(pub.payload(), ctx);
            case TopicCommand.Subscribe<M> sub -> liveSubscribers.add(sub.subscriber());
            case TopicCommand.Unsubscribe<M> unsub -> liveSubscribers.remove(unsub.subscriber());
            case TopicCommand.SubscribeDurable<M> sub ->
                handleSubscribeDurable(sub.subscriptionId(), sub.subscriber(), ctx);
            case TopicCommand.UnsubscribeDurable<M> unsub ->
                handleUnsubscribeDurable(unsub.subscriptionId(), ctx);
            case TopicCommand.SubscribeFrom<M> sf ->
                handleSubscribeFrom(sf.offset(), sf.subscriber(), ctx);
        }
    }

    private void handleSubscribeFrom(long offset, Ref<M> subscriber,
                                      BayouContext<TopicCommand<M>> ctx) {
        try {
            List<LogEntry> entries = logView.readAfter(offset).join();
            for (LogEntry entry : entries) {
                try {
                    subscriber.tell(serializer.deserialize(entry.dataUnsafe()));
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            ctx.logger().error("Topic '{}': failed to replay from offset {}", name, offset, e);
        }
        liveSubscribers.add(subscriber);
    }

    private static byte[] longToBytes(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    private static long bytesToLong(byte[] bytes) {
        return ByteBuffer.wrap(bytes).getLong();
    }

    private void handleSubscribeDurable(String subscriptionId, Ref<M> subscriber,
                                        BayouContext<TopicCommand<M>> ctx) {
        String key = "sub:" + subscriptionId;
        try {
            byte[] stored = logView.getValue(key).join();
            if (stored != null) {
                long lastSeqnum = bytesToLong(stored);
                List<LogEntry> missed = logView.readAfter(lastSeqnum).join();
                for (LogEntry entry : missed) {
                    try {
                        subscriber.tell(serializer.deserialize(entry.dataUnsafe()));
                    } catch (Exception ignored) {}
                }
            } else {
                logView.setValue(key, longToBytes(logView.getLatestSeqnum())).join();
            }
        } catch (Exception e) {
            ctx.logger().error("Topic '{}': error setting up durable subscriber '{}'", name, subscriptionId, e);
        }
        durableSubscribers.put(subscriptionId, subscriber);
    }

    private void handleUnsubscribeDurable(String subscriptionId, BayouContext<TopicCommand<M>> ctx) {
        durableSubscribers.remove(subscriptionId);
        try {
            logView.deleteValue("sub:" + subscriptionId).join();
        } catch (Exception e) {
            ctx.logger().error("Topic '{}': failed to delete position for '{}'", name, subscriptionId, e);
        }
    }

    private void handlePublish(M payload, BayouContext<TopicCommand<M>> ctx) {
        long seqnum;
        try {
            byte[] bytes = serializer.serialize(payload);
            AppendResult result = logView.append(bytes).join();
            seqnum = result.seqnum();
        } catch (Exception e) {
            ctx.logger().error("Topic '{}': failed to append to log", name, e);
            deliverToLiveSubscribers(payload);
            return;
        }

        deliverToLiveSubscribers(payload);

        Iterator<Map.Entry<String, Ref<M>>> it = durableSubscribers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Ref<M>> entry = it.next();
            Ref<M> ref = entry.getValue();
            if (!ref.isAlive()) {
                it.remove();
                continue;
            }
            try {
                ref.tell(payload);
                logView.setValue("sub:" + entry.getKey(), longToBytes(seqnum)).join();
            } catch (Exception e) {
                log.warn("Topic '{}': failed to deliver/advance for '{}'", name, entry.getKey(), e);
            }
        }
    }

    private void deliverToLiveSubscribers(M payload) {
        boolean hadDead = false;
        for (Ref<M> ref : liveSubscribers) {
            if (!ref.isAlive()) { hadDead = true; continue; }
            try { ref.tell(payload); } catch (Exception ignored) {}
        }
        if (hadDead) liveSubscribers.removeIf(ref -> !ref.isAlive());
    }
}
