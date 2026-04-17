# Persistent PubSub

Bayou ships two publish/subscribe mechanisms. They share the same subscriber model (`Ref<M>`)
but differ fundamentally in durability.

---

## 1. Two Kinds of PubSub

| | `BayouPubSub` | `BayouTopic` |
|---|---|---|
| **Persistence** | In-memory only | Log-backed (gumbo) |
| **Survives restart** | No | Yes |
| **Late subscribers** | Receive only future messages | Can replay full history |
| **Durable subscriptions** | No | Yes — catch-up on reconnect |
| **Serializer required** | No | Yes |
| **Best for** | Ephemeral fan-out, metrics, UI events | Audit logs, event sourcing integration, durable delivery |

**Rule of thumb:** use `BayouPubSub` when a missed message doesn't matter; use `BayouTopic`
when messages must survive restarts or late subscribers need history.

---

## 2. BayouPubSub — In-Memory Broadcast

One `BayouPubSub` registry lives per system, accessed via `system.pubsub()`. It is
thread-safe; subscribe, publish, and unsubscribe may be called from any thread.

**Key characteristics:**
- Delivery is best-effort. Dead actors are silently skipped and lazily cleaned up.
- Bounded-mailbox overflows (`MailboxFullException`) are silently swallowed.
- No replay — a subscriber only receives messages published after it subscribes.

```java
import com.cajunsystems.bayou.BayouPubSub;
import com.cajunsystems.bayou.BayouSystem;
import com.cajunsystems.bayou.Ref;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;

public class PubSubExample {
    public static void main(String[] args) throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();

        try (SharedLogService log = SharedLogService.open(config);
             BayouSystem system = new BayouSystem(log)) {

            BayouPubSub pubsub = system.pubsub();

            // Subscribe a listener
            Ref<String> listener = system.spawn("listener",
                    (msg, ctx) -> ctx.logger().info("Received: {}", msg));
            pubsub.subscribe("news", listener);

            // Publish — delivered to all live subscribers
            pubsub.publish("news", "breaking news");
            pubsub.publish("news", "second update");

            // Unsubscribe when no longer needed
            pubsub.unsubscribe("news", listener);

            // This message is not delivered — listener was unsubscribed
            pubsub.publish("news", "missed message");

            Thread.sleep(100);
            system.shutdown();
        }
    }
}
```

Actors can subscribe themselves from `preStart` using `ctx.self()`:

```java
system.spawn("self-subscriber", new Actor<String>() {
    @Override
    public void preStart(BayouContext<String> ctx) {
        ctx.system().pubsub().subscribe("news", ctx.self());
    }

    @Override
    public void handle(String msg, BayouContext<String> ctx) {
        ctx.logger().info("News: {}", msg);
    }
});
```

---

## 3. BayouTopic — Persistent Topic

`BayouTopic<M>` is backed by the gumbo log. Every published message is appended to the
log before fan-out. Messages survive process restarts — a new subscriber can replay the
entire history.

Obtain a topic with `system.topic(name, serializer)`. The call is idempotent: the same
`BayouTopic` instance is returned for the same name.

```java
import com.cajunsystems.bayou.*;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;

import java.io.Serializable;

public class TopicExample {

    record OrderEvent(String orderId, String status) implements Serializable {}

    public static void main(String[] args) throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();

        try (SharedLogService log = SharedLogService.open(config);
             BayouSystem system = new BayouSystem(log)) {

            // Create or look up the topic (idempotent)
            BayouTopic<OrderEvent> topic = system.topic("orders", new JavaSerializer<>());

            // Subscribe a live listener
            Ref<OrderEvent> auditor = system.spawn("auditor",
                    (evt, ctx) -> ctx.logger().info("Order {}: {}", evt.orderId(), evt.status()));
            topic.subscribe(auditor);

            // Publish — appended to log, delivered to auditor
            topic.publish(new OrderEvent("ORD-1", "CREATED"));
            topic.publish(new OrderEvent("ORD-2", "CREATED"));
            topic.publish(new OrderEvent("ORD-1", "PAID"));

            // Unsubscribe when done
            topic.unsubscribe(auditor);

            Thread.sleep(100);
            system.shutdown();
        }
    }
}
```

---

## 4. Durable Subscriptions

A durable subscription remembers the last delivered position across reconnects. When the
subscriber comes back with the same `subscriptionId`, it receives all messages it missed
since the last delivery — catch-up replay — before resuming live delivery.

Use `topic.subscribe(subscriptionId, ref)` to register a durable subscriber.
Use `topic.unsubscribeDurable(subscriptionId)` to permanently forget the position.

```java
import com.cajunsystems.bayou.*;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

public class DurableSubscriptionExample {

    record Notification(String text) implements Serializable {}

    public static void main(String[] args) throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();

        try (SharedLogService log = SharedLogService.open(config);
             BayouSystem system = new BayouSystem(log)) {

            BayouTopic<Notification> topic = system.topic("notifications", new JavaSerializer<>());

            // First subscriber session
            Ref<Notification> session1 = system.spawn("notif-consumer-v1",
                    (n, ctx) -> ctx.logger().info("Received: {}", n.text()));
            topic.subscribe("consumer-A", session1);

            topic.publish(new Notification("message-1"));
            topic.publish(new Notification("message-2"));
            Thread.sleep(100);

            // Simulate disconnect — stop the old ref
            session1.stop().get(1, TimeUnit.SECONDS);

            // Publish while disconnected — consumer-A misses these
            topic.publish(new Notification("message-3"));
            topic.publish(new Notification("message-4"));
            topic.publish(new Notification("message-5"));

            // Reconnect with the same subscriptionId — catches up from where it left off
            Ref<Notification> session2 = system.spawn("notif-consumer-v2",
                    (n, ctx) -> ctx.logger().info("Caught up: {}", n.text()));
            topic.subscribe("consumer-A", session2); // receives message-3, 4, 5 then stays live

            Thread.sleep(200);

            // Forget the position entirely
            topic.unsubscribeDurable("consumer-A");

            system.shutdown();
        }
    }
}
```

---

## 5. Replay from Offset

Use `subscribeFrom` or `subscribeFromBeginning` when you want to replay a portion of the
log without the state tracking of a durable subscription.

| Method | Description |
|---|---|
| `topic.subscribeFromBeginning(ref)` | Replay every message from the start, then stay live |
| `topic.subscribeFrom(offset, ref)` | Replay entries with seqnum > offset, then stay live |
| `topic.latestOffset()` | Current end of the log (pass this to receive only future messages) |

Internally `subscribeFromBeginning` calls `subscribeFrom(-1, ref)`. Gumbo seqnums are
0-indexed, so `readAfter(-1)` returns all entries from seqnum 0.

```java
import com.cajunsystems.bayou.*;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;

import java.io.Serializable;

public class ReplayExample {

    record Event(int seq) implements Serializable {}

    public static void main(String[] args) throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();

        try (SharedLogService log = SharedLogService.open(config);
             BayouSystem system = new BayouSystem(log)) {

            BayouTopic<Event> topic = system.topic("events", new JavaSerializer<>());

            // Publish 5 events before any subscriber exists
            for (int i = 1; i <= 5; i++) {
                topic.publish(new Event(i));
            }

            // Snapshot the current end of the log
            long nowOffset = topic.latestOffset();

            // Publish 5 more events
            for (int i = 6; i <= 10; i++) {
                topic.publish(new Event(i));
            }

            // Replay everything from the beginning (events 1–10, then live)
            Ref<Event> replayAll = system.spawn("replay-all",
                    (e, ctx) -> ctx.logger().info("Replayed: {}", e.seq()));
            topic.subscribeFromBeginning(replayAll);

            // Replay only events after the snapshot (events 6–10, then live)
            Ref<Event> replayPartial = system.spawn("replay-partial",
                    (e, ctx) -> ctx.logger().info("Partial replay: {}", e.seq()));
            topic.subscribeFrom(nowOffset, replayPartial);

            // Receive only future messages (no replay)
            Ref<Event> liveOnly = system.spawn("live-only",
                    (e, ctx) -> ctx.logger().info("Live: {}", e.seq()));
            topic.subscribeFrom(topic.latestOffset(), liveOnly);

            Thread.sleep(200);
            system.shutdown();
        }
    }
}
```

---

## 6. Serialization

`BayouTopic` requires a serializer because messages are written to the gumbo log.

```java
public interface BayouSerializer<T> {
    byte[] serialize(T value) throws IOException;
    T deserialize(byte[] bytes) throws IOException;
}
```

Bayou ships `JavaSerializer<T extends Serializable>` as a zero-configuration default.
It uses Java object serialization — simple and portable, but not compact or schema-evolved.

```java
// JavaSerializer — for development and tests
BayouTopic<MyEvent> topic = system.topic("my-events", new JavaSerializer<>());
```

For production, implement `BayouSerializer<T>` with a compact format:

```java
// Kryo example (add the Kryo dependency yourself)
public class KryoSerializer<T> implements BayouSerializer<T> {
    private final Kryo kryo = new Kryo();
    private final Class<T> type;

    public KryoSerializer(Class<T> type) { this.type = type; }

    @Override
    public byte[] serialize(T value) {
        Output out = new Output(256, -1);
        kryo.writeObject(out, value);
        return out.toBytes();
    }

    @Override
    public T deserialize(byte[] bytes) {
        return kryo.readObject(new Input(bytes), type);
    }
}
```

Production checklist:
- Avoid Java serialization for schema-evolving types — adding fields breaks deserialization.
- Use a versioned format (Protobuf, Avro, Kryo with registration) for long-lived topics.
- `JavaSerializer` is fine for tests and ephemeral topics in local development.

---

## 7. Which to Use?

| Scenario | Recommendation |
|---|---|
| Fan-out to UI components or metrics sinks | `BayouPubSub` |
| Broadcast within the same process, no durability needed | `BayouPubSub` |
| Messages must survive process restarts | `BayouTopic` |
| Late subscribers need history | `BayouTopic` with `subscribeFromBeginning` |
| Consumer reconnects and must not miss messages | `BayouTopic` with durable subscription |
| Audit log / event sourcing integration | `BayouTopic` |
| Replay a specific window of history | `BayouTopic` with `subscribeFrom(offset, ref)` |
