# Back-pressure

By default, every Bayou actor has an unbounded mailbox. A slow actor processing messages
from a fast producer will accumulate messages indefinitely, eventually causing an
out-of-memory error. A bounded mailbox caps that growth and lets you control what happens
when the cap is reached.

---

## 1. Creating a bounded mailbox

Pass a `MailboxConfig` to any `spawn*` overload:

```java
import com.cajunsystems.bayou.*;
import com.cajunsystems.bayou.actor.Actor;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;

public class BoundedMailboxExample {
    public static void main(String[] args) throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();

        try (SharedLogService log = SharedLogService.open(config);
             BayouSystem system = new BayouSystem(log)) {

            // Accept at most 100 pending messages; silently drop the newest on overflow
            Ref<String> worker = system.spawn(
                    "worker",
                    (msg, ctx) -> ctx.logger().info("Processing: {}", msg),
                    MailboxConfig.bounded(100));

            for (int i = 0; i < 200; i++) {
                worker.tell("job-" + i);  // jobs 100-199 are dropped silently
            }

            Thread.sleep(500);
            system.shutdown();
        }
    }
}
```

---

## 2. Overflow strategies

| Strategy | What happens when the mailbox is full |
|---|---|
| `DROP_NEWEST` *(default)* | The incoming message is silently discarded |
| `DROP_OLDEST` | The oldest queued message is evicted to make room |
| `REJECT` | `tell()` throws `MailboxFullException` immediately |

```java
// Drop the oldest queued message (good for sliding windows, latest-value semantics)
MailboxConfig.bounded(50, OverflowStrategy.DROP_OLDEST)

// Throw on full — caller decides what to do
MailboxConfig.bounded(50, OverflowStrategy.REJECT)
```

---

## 3. `REJECT` strategy and `MailboxFullException`

With `REJECT`, `tell()` throws a `MailboxFullException` synchronously. The caller is
responsible for handling it — retry, route to a fallback, or propagate the error.

```java
Ref<String> worker = system.spawn("worker",
        (msg, ctx) -> ctx.logger().info("Processing: {}", msg),
        MailboxConfig.bounded(10, OverflowStrategy.REJECT));

try {
    worker.tell("urgent-job");
} catch (MailboxFullException e) {
    System.err.println("Mailbox full for " + e.actorId()
            + " (capacity=" + e.capacity() + ") — routing to fallback");
    fallback.tell("urgent-job");
}
```

---

## 4. Observing overflow with `OverflowListener`

Attach an `OverflowListener` to count, log, or emit metrics on every dropped message.
The listener is called on the sender's thread at the moment of overflow — keep it fast
and non-blocking.

```java
import com.cajunsystems.bayou.*;
import com.cajunsystems.bayou.actor.Actor;
import java.util.concurrent.atomic.AtomicLong;

public class OverflowMetricsExample {
    public static void main(String[] args) throws Exception {
        AtomicLong dropped = new AtomicLong();

        OverflowListener listener = (actorId, capacity, strategy) -> {
            long count = dropped.incrementAndGet();
            System.out.printf("overflow #%d on '%s' (cap=%d, strategy=%s)%n",
                    count, actorId, capacity, strategy);
        };

        // ...system setup...
        Ref<String> worker = system.spawn(
                "worker",
                (msg, ctx) -> {
                    Thread.sleep(50);  // slow consumer
                    ctx.logger().info("done: {}", msg);
                },
                MailboxConfig.bounded(5, OverflowStrategy.DROP_NEWEST, listener));

        for (int i = 0; i < 20; i++) {
            worker.tell("msg-" + i);
        }

        Thread.sleep(2_000);
        System.out.println("Total dropped: " + dropped.get());
    }
}
```

---

## 5. Choosing a capacity

Capacity is a bound on *in-flight* messages, not on throughput. A good starting point:

1. Estimate your actor's message processing time and the maximum acceptable latency.
2. `capacity = max_latency_ms / avg_processing_ms`
3. Round up and add a small buffer.

For example, if an actor takes 10 ms per message and you can tolerate 1 s of queued
work: `capacity = 1000 / 10 = 100`.

Monitor with `OverflowListener` in staging to tune the value before going to production.

---

## 6. Choosing a strategy

| Situation | Recommended strategy |
|---|---|
| Latest message is best (sensor readings, UI events) | `DROP_OLDEST` — discard stale data, keep the freshest |
| All messages are equally important; loss is acceptable | `DROP_NEWEST` — simple, low overhead |
| Caller must know when the system is overloaded | `REJECT` — explicit back-pressure signal |
| Actor is internal with a controlled producer | `DROP_NEWEST` with an `OverflowListener` for alerting |
| Unbounded growth is acceptable (small volume, trusted producer) | `MailboxConfig.unbounded()` (default) |

---

## 7. Bounded mailboxes with other spawn flavours

All actor flavours support bounded mailboxes:

```java
// Stateful actor
system.spawnStateful("counter", new CounterActor(), serializer,
        MailboxConfig.bounded(200, OverflowStrategy.DROP_OLDEST));

// Event-sourced actor
system.spawnEventSourced("ledger", new LedgerActor(), serializer,
        MailboxConfig.bounded(500, OverflowStrategy.REJECT));

// State machine actor
system.spawnStateMachine("connection", new ConnectionFsm(),
        ConnectionState.IDLE,
        MailboxConfig.bounded(50));
```
