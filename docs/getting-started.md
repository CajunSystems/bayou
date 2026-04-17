# Getting Started with Bayou

This guide takes you from zero to a working supervised, event-sourced actor publishing to a
persistent topic — no prior Bayou knowledge required. Follow it top-to-bottom in under
10 minutes.

---

## 1. Prerequisites

- **Java 21** (virtual threads are required; OpenJDK 21+ works)
- **Maven 3.8+**
- An IDE or a terminal

---

## 2. Add Bayou

Bayou is published via [JitPack](https://jitpack.io). Add the repository and dependency to
your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.CajunSystems</groupId>
    <artifactId>bayou</artifactId>
    <version>0.1.0</version>
</dependency>
```

Bayou pulls in `gumbo` (the underlying shared log) transitively — no further configuration
is needed for local development.

---

## 3. Hello, Actor!

Every actor needs a `BayouSystem`. For tests and quick experiments, use
`InMemoryPersistenceAdapter` so nothing is written to disk.

```java
import com.cajunsystems.bayou.BayouSystem;
import com.cajunsystems.bayou.Ref;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;

public class HelloActor {
    public static void main(String[] args) throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();

        try (SharedLogService log = SharedLogService.open(config);
             BayouSystem system = new BayouSystem(log)) {

            // Spawn a stateless actor using a lambda
            Ref<String> greeter = system.spawn("greeter",
                    (msg, ctx) -> ctx.logger().info("Hello, {}!", msg));

            greeter.tell("World");   // fire-and-forget
            greeter.tell("Bayou");

            system.shutdown();
        }
    }
}
```

`system.spawn(id, handler)` returns a `Ref<M>` — the only handle you need to send
messages. The handler lambda receives the message and a `BayouContext<M>` for
replying, scheduling timers, and accessing the system.

---

## 4. Getting a Reply

Actors answer queries via `ctx.reply(value)` and the caller collects the result with
`ref.ask(message)`, which returns a `CompletableFuture`.

```java
import com.cajunsystems.bayou.BayouSystem;
import com.cajunsystems.bayou.Ref;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;

import java.util.concurrent.TimeUnit;

public class AskExample {
    public static void main(String[] args) throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();

        try (SharedLogService log = SharedLogService.open(config);
             BayouSystem system = new BayouSystem(log)) {

            // This actor echoes back whatever it receives, uppercased
            Ref<String> upper = system.spawn("upper",
                    (msg, ctx) -> ctx.reply(msg.toUpperCase()));

            // ask() is non-blocking; .get() waits for the reply
            String result = upper.<String>ask("hello").get(1, TimeUnit.SECONDS);
            System.out.println(result); // prints: HELLO

            system.shutdown();
        }
    }
}
```

`ask()` creates an internal one-shot reply channel. If the actor does not call
`ctx.reply()` the future will time out.

---

## 5. Durable State with EventSourcedActor

`EventSourcedActor<S, E, M>` stores every state change as an immutable event in the
gumbo log. On restart all events are replayed through `apply` to reconstruct state —
no separate database needed.

```java
import com.cajunsystems.bayou.*;
import com.cajunsystems.bayou.actor.EventSourcedActor;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class BankAccountExample {

    record Balance(long cents) {}

    sealed interface BankEvent extends Serializable {
        record Deposited(long cents)  implements BankEvent {}
        record Withdrawn(long cents)  implements BankEvent {}
    }

    sealed interface BankCmd {
        record Deposit(long cents)   implements BankCmd {}
        record Withdraw(long cents)  implements BankCmd {}
        record GetBalance()          implements BankCmd {}
    }

    static class BankAccount implements EventSourcedActor<Balance, BankEvent, BankCmd> {

        @Override
        public Balance initialState() { return new Balance(0); }

        @Override
        public List<BankEvent> handle(Balance state, BankCmd cmd, BayouContext<BankCmd> ctx) {
            return switch (cmd) {
                case BankCmd.Deposit(long c)  -> List.of(new BankEvent.Deposited(c));
                case BankCmd.Withdraw(long c) -> state.cents() >= c
                        ? List.of(new BankEvent.Withdrawn(c))
                        : List.of();                          // insufficient funds — no event
                case BankCmd.GetBalance()     -> { ctx.reply(state.cents()); yield List.of(); }
            };
        }

        @Override
        public Balance apply(Balance state, BankEvent event) {
            return switch (event) {
                case BankEvent.Deposited(long c)  -> new Balance(state.cents() + c);
                case BankEvent.Withdrawn(long c)  -> new Balance(state.cents() - c);
            };
        }
    }

    public static void main(String[] args) throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();

        try (SharedLogService log = SharedLogService.open(config);
             BayouSystem system = new BayouSystem(log)) {

            Ref<BankCmd> account = system.spawnEventSourced(
                    "account-42", new BankAccount(), new JavaSerializer<>());

            account.tell(new BankCmd.Deposit(1000));
            account.tell(new BankCmd.Deposit(500));
            account.tell(new BankCmd.Withdraw(200));

            long balance = account.<Long>ask(new BankCmd.GetBalance()).get(1, TimeUnit.SECONDS);
            System.out.println("Balance: " + balance + " cents"); // 1300

            system.shutdown();
        }
    }
}
```

The key methods:

| Method | Signature | Purpose |
|---|---|---|
| `initialState()` | `→ S` | Starting state before any events |
| `handle()` | `(S, M, ctx) → List<E>` | Decide what events to emit |
| `apply()` | `(S, E) → S` | Fold one event into state |

---

## 6. Add Supervision

Wrap the event-sourced actor in a supervisor. If the actor crashes, the supervisor
automatically restarts it — no manual error handling needed.

```java
import com.cajunsystems.bayou.*;
import com.cajunsystems.bayou.actor.EventSourcedActor;

import java.time.Duration;
import java.util.List;

public class SupervisedBankExample {

    // ... (BankAccount, BankEvent, BankCmd, Balance as above)

    public static void main(String[] args) throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();

        try (SharedLogService log = SharedLogService.open(config);
             BayouSystem system = new BayouSystem(log)) {

            // The supervisor owns the account actor
            SupervisorRef supervisor = system.spawnSupervisor(
                    "account-supervisor",
                    new SupervisorActor() {
                        @Override
                        public List<ChildSpec> children() {
                            return List.of(
                                ChildSpec.eventSourced(
                                    "account-42",
                                    new BankAccount(),
                                    new JavaSerializer<>())
                            );
                        }

                        @Override
                        public SupervisionStrategy strategy() {
                            // Restart only the crashed child, up to 5 times per minute
                            return new OneForOneStrategy(
                                new RestartWindow(5, Duration.ofSeconds(60)));
                        }
                    });

            // Look up the child actor by its ID
            Ref<BankCmd> account = system.<BankCmd>lookup("account-42").orElseThrow();

            account.tell(new BankCmd.Deposit(1000));

            long balance = account.<Long>ask(new BankCmd.GetBalance()).get(1, TimeUnit.SECONDS);
            System.out.println("Balance: " + balance);

            system.shutdown();
        }
    }
}
```

`OneForOneStrategy` restarts only the crashed child; siblings are unaffected.
`AllForOneStrategy` restarts all children when any one crashes.
When the restart window is exceeded the supervisor escalates to its parent; a top-level
supervisor logs a critical error and stops.

---

## 7. Publish to a Persistent Topic

`BayouTopic<M>` is a durable, log-backed publish/subscribe channel. Messages survive
restarts — subscribers can replay from any point in history.

```java
import com.cajunsystems.bayou.*;

import java.io.Serializable;

public class TopicExample {

    record TransactionEvent(String accountId, long amountCents) implements Serializable {}

    public static void main(String[] args) throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();

        try (SharedLogService log = SharedLogService.open(config);
             BayouSystem system = new BayouSystem(log)) {

            // Create (or look up) the topic
            BayouTopic<TransactionEvent> topic =
                    system.topic("transactions", new JavaSerializer<>());

            // Spawn a listener and subscribe it
            Ref<TransactionEvent> auditor = system.spawn("auditor",
                    (evt, ctx) -> ctx.logger().info(
                        "Audit: {} deposited {} cents", evt.accountId(), evt.amountCents()));

            topic.subscribe(auditor);

            // Publish — the auditor receives this message
            topic.publish(new TransactionEvent("account-42", 1000));
            topic.publish(new TransactionEvent("account-42", 500));

            // A late subscriber can replay everything from the start
            Ref<TransactionEvent> reporter = system.spawn("reporter",
                    (evt, ctx) -> ctx.logger().info("Report: {}", evt));
            topic.subscribeFromBeginning(reporter);

            Thread.sleep(200); // allow delivery

            system.shutdown();
        }
    }
}
```

Key `BayouTopic` methods:

| Method | Description |
|---|---|
| `topic.publish(msg)` | Append to log and fan-out to live subscribers |
| `topic.subscribe(ref)` | Subscribe from the current head (live only) |
| `topic.subscribeFromBeginning(ref)` | Replay all messages, then stay subscribed |
| `topic.subscribeFrom(offset, ref)` | Replay from a specific log offset |
| `topic.latestOffset()` | Current end of the log |
| `topic.unsubscribeDurable(id)` | Forget a durable subscription position |

---

## 8. Test with TestKit

`TestKit.probe(system, id)` returns a `TestProbe<M>` — a special actor whose inbox you
can query synchronously in tests. No `Thread.sleep` or `CountDownLatch` required.

```java
import com.cajunsystems.bayou.*;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;
import org.junit.jupiter.api.*;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BayouExampleTest {

    BayouSystem system;

    @BeforeEach
    void setUp() throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();
        SharedLogService log = SharedLogService.open(config);
        system = new BayouSystem(log);
    }

    @AfterEach
    void tearDown() {
        system.shutdown();
    }

    @Test
    void echoActorRepliesViaProbe() {
        // Create a probe — it is itself a registered actor
        TestProbe<String> probe = TestKit.probe(system, "probe");

        // Spawn a simple echo actor that forwards to the probe
        Ref<String> echo = system.spawn("echo",
                (msg, ctx) -> probe.ref().tell(msg + "-echo"));

        echo.tell("ping");

        // Assert the probe received the expected message within 1 second
        probe.expectMessage("ping-echo", Duration.ofSeconds(1));
    }

    @Test
    void probeCanAssertSilence() {
        TestProbe<String> probe = TestKit.probe(system, "probe");

        // No one sends to the probe — assert nothing arrives
        probe.expectNoMessage(Duration.ofMillis(200));
    }

    @Test
    void probeDetectsActorTermination() {
        TestProbe<String> probe = TestKit.probe(system, "probe");
        Ref<String> shortLived = system.spawn("short-lived", (msg, ctx) -> {});

        shortLived.stop();

        // Blocks until the actor stops or the timeout expires
        probe.expectTerminated(shortLived, Duration.ofSeconds(2));
    }

    @Test
    void probeSubscribesToTopic() {
        TestProbe<String> probe = TestKit.probe(system, "probe");
        BayouTopic<String> topic = system.topic("test-topic", new JavaSerializer<>());

        topic.subscribe(probe.ref());
        topic.publish("hello from topic");

        probe.expectMessage("hello from topic", Duration.ofSeconds(1));
    }
}
```

`TestProbe<M>` quick reference:

| Method | Description |
|---|---|
| `probe.ref()` | The `Ref<M>` — send messages to the probe with this |
| `probe.expectMessage(msg, timeout)` | Assert exact message arrives within timeout |
| `probe.expectMessage(Class<T>, timeout)` | Assert a message of a given type arrives |
| `probe.expectNoMessage(duration)` | Assert no message arrives within duration |
| `probe.expectTerminated(ref, timeout)` | Assert actor stops within timeout |

---

## 9. What's Next

| Guide | Description |
|---|---|
| [actor-flavours.md](actor-flavours.md) | Decision table — when to use Actor, StatefulActor, EventSourcedActor, or StateMachineActor |
| [testing.md](testing.md) | TestKit in depth — all assertions, Awaitility comparison, tips |
| supervision.md *(coming soon)* | Full supervision tree reference — strategies, nesting, dynamic children |
| persistent-pubsub.md *(coming soon)* | `BayouTopic` deep-dive — durable subscriptions, replay, offsets |
| patterns.md *(coming soon)* | Request-reply, scatter-gather, circuit breaker, saga patterns |
