# Bayou

An actor system built on top of [gumbo](https://github.com/CajunSystems/gumbo) — a shared append-only log. Bayou ships actor flavours that cover the spectrum from pure in-memory processing to fully event-sourced state, plus Erlang/Elixir-style primitives: supervision trees, timers, death watch, linking, back-pressure, PubSub, and finite state machines.

## Dependency

Add via [JitPack](https://jitpack.io):

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

Requires **Java 21**.

## Setup

Create a `SharedLog` (backed by gumbo) and hand it to `BayouSystem`:

```java
SharedLogConfig config = SharedLogConfig.builder()
        .persistenceAdapter(new FileBasedPersistenceAdapter("/var/data/bayou"))
        .build();

try (SharedLogService log = SharedLogService.open(config);
     BayouSystem system = new BayouSystem(log)) {

    // spawn actors, send messages …
    system.shutdown();
}
```

For tests, swap in `InMemoryPersistenceAdapter`.

---

## Actor flavours

### 1. Actor (stateless)

No state, no log interaction. The right default — use it for everything that doesn't need durability.

```java
Ref<String> greeter = system.spawn("greeter",
        (msg, ctx) -> ctx.logger().info("Hello, {}!", msg));

greeter.tell("World");
```

Implement the full interface when you need lifecycle hooks:

```java
system.spawn("worker", new Actor<Job>() {
    @Override public void handle(Job job, BayouContext<Job> ctx) { process(job); }
    @Override public void preStart(BayouContext<Job> ctx)  { openConnection(); }
    @Override public void postStop(BayouContext<Job> ctx)  { closeConnection(); }
    @Override public void onError(Job job, Throwable e, BayouContext<Job> ctx) {
        ctx.logger().error("Failed job {}", job.id(), e);
    }
});
```

---

### 2. EventSourcedActor (event-sourced)

State is derived entirely by replaying events stored in the gumbo log. There is no separate database — the log **is** the state store.

```
handle(state, message) → List<Event>   // decide what happened
apply(state, event)    → State         // fold one event into state
```

On startup all events for the actor are replayed through `apply` to reconstruct state before the first live message is delivered.

```java
record Balance(long cents) {}

sealed interface BankEvent extends Serializable {
    record Deposited(long cents)  implements BankEvent {}
    record Withdrawn(long cents)  implements BankEvent {}
}

sealed interface BankCmd {
    record Deposit(long cents)  implements BankCmd {}
    record Withdraw(long cents) implements BankCmd {}
    record GetBalance()         implements BankCmd {}
}

class BankAccount implements EventSourcedActor<Balance, BankEvent, BankCmd> {

    @Override public Balance initialState() { return new Balance(0); }

    @Override
    public List<BankEvent> handle(Balance state, BankCmd cmd, BayouContext<BankCmd> ctx) {
        return switch (cmd) {
            case BankCmd.Deposit(long c)  -> List.of(new BankEvent.Deposited(c));
            case BankCmd.Withdraw(long c) -> state.cents() >= c
                    ? List.of(new BankEvent.Withdrawn(c))
                    : List.of();
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

Ref<BankCmd> account = system.spawnEventSourced(
        "account-42", new BankAccount(), new JavaSerializer<>());

account.tell(new BankCmd.Deposit(10_00));
long balance = account.<Long>ask(new BankCmd.GetBalance()).get(1, SECONDS);
```

**Log tag:** `bayou.events:<actorId>`

---

### 3. StatefulActor (reducer / function-core)

A `(state, message) → state` reducer. State is held in memory and periodically snapshotted to the log. On restart only the latest snapshot is loaded — no full replay required.

```java
record Tally(Map<String, Integer> counts) implements Serializable {
    Tally add(String word) {
        var next = new HashMap<>(counts);
        next.merge(word, 1, Integer::sum);
        return new Tally(next);
    }
}

sealed interface TallyCmd {
    record Count(String word) implements TallyCmd {}
    record Get(String word)   implements TallyCmd {}
}

class WordCounter implements StatefulActor<Tally, TallyCmd> {
    @Override public Tally initialState() { return new Tally(new HashMap<>()); }

    @Override
    public Tally reduce(Tally state, TallyCmd cmd, BayouContext<TallyCmd> ctx) {
        return switch (cmd) {
            case TallyCmd.Count(String w) -> state.add(w);
            case TallyCmd.Get(String w)   -> {
                ctx.reply(state.counts().getOrDefault(w, 0));
                yield state;
            }
        };
    }
}

Ref<TallyCmd> counter = system.spawnStateful(
        "word-counter", new WordCounter(), new JavaSerializer<>());

counter.tell(new TallyCmd.Count("hello"));
int n = counter.<Integer>ask(new TallyCmd.Get("hello")).get(1, SECONDS);
```

**Log tag:** `bayou.snapshots:<actorId>`

---

### 4. StateMachineActor (FSM)

A finite state machine actor. Declare a state enum and implement `transition` — the runtime handles `onEnter`/`onExit` callbacks on every state change. `onEnter` is called for the initial state on startup.

```java
enum TrafficLight { RED, GREEN, YELLOW }

Ref<String> fsm = system.spawnStateMachine("traffic",
    new StateMachineActor<TrafficLight, String>() {
        @Override
        public Optional<TrafficLight> transition(TrafficLight state, String msg,
                                                 BayouContext<String> ctx) {
            return switch (msg) {
                case "go"   -> state == RED    ? Optional.of(GREEN)  : Optional.empty();
                case "slow" -> state == GREEN  ? Optional.of(YELLOW) : Optional.empty();
                case "stop" -> state == YELLOW ? Optional.of(RED)    : Optional.empty();
                default     -> Optional.empty(); // stay in current state
            };
        }

        @Override
        public void onEnter(TrafficLight state, BayouContext<String> ctx) {
            ctx.logger().info("Entering {}", state);
        }

        @Override
        public void onExit(TrafficLight state, BayouContext<String> ctx) {
            ctx.logger().info("Leaving {}", state);
        }
    },
    TrafficLight.RED); // initial state

fsm.tell("go");   // RED → GREEN
fsm.tell("slow"); // GREEN → YELLOW
fsm.tell("stop"); // YELLOW → RED
```

Return `Optional.empty()` to stay in the current state — no callbacks fire. Return a new state to trigger `onExit(old)` + `onEnter(new)`.

---

## Messaging

| Method | Behaviour |
|---|---|
| `ref.tell(msg)` | Fire-and-forget; message is queued and returns immediately |
| `ref.ask(msg)` | Returns a `CompletableFuture<R>`; the actor must call `ctx.reply(value)` |
| `ref.stop()` | Drains the mailbox, then stops; returns a future that completes on shutdown |
| `ref.isAlive()` | Returns `true` if the actor is still running |

---

## Timers

Schedule messages to self after a delay or on a recurring interval — equivalent to Erlang's `Process.send_after`:

```java
system.spawn("reminder", new Actor<String>() {
    private TimerRef periodic;

    @Override
    public void preStart(BayouContext<String> ctx) {
        // One-shot: deliver "wake-up" after 5 seconds
        ctx.scheduleOnce(Duration.ofSeconds(5), "wake-up");

        // Recurring: deliver "tick" every second
        periodic = ctx.schedulePeriodic(Duration.ofSeconds(1), "tick");
    }

    @Override
    public void handle(String msg, BayouContext<String> ctx) {
        if ("tick".equals(msg)) ctx.logger().info("tick");
        if ("wake-up".equals(msg)) periodic.cancel(); // stop the recurring timer
    }
});
```

- `ctx.scheduleOnce(Duration, M)` → `TimerRef` — fires once
- `ctx.schedulePeriodic(Duration, M)` → `TimerRef` — fires repeatedly until cancelled or actor stops
- `TimerRef.cancel()` — cancels a pending or recurring timer; idempotent
- All active timers are automatically cancelled when an actor stops

---

## Death Watch & Linking

### Death Watch

Watch another actor for termination — receive a `Terminated` signal when it stops (crash or graceful):

```java
system.spawn("monitor", new Actor<String>() {
    @Override
    public void preStart(BayouContext<String> ctx) {
        Ref<String> target = ctx.system().lookup("worker").orElseThrow();
        ctx.watch(target); // or system.watch(target, monitorRef)
    }

    @Override
    public void onSignal(Signal signal, BayouContext<String> ctx) {
        if (signal instanceof Terminated t) {
            ctx.logger().info("Actor '{}' has stopped", t.actorId());
        }
    }
});
```

Use `ctx.unwatch(handle)` (or `system.unwatch(handle)`) with the returned `WatchHandle` to cancel a watch.

### Linking

Link two actors bidirectionally — if either dies, the other receives a `LinkedActorDied` signal and crashes unless it traps exits:

```java
// From within an actor:
ctx.link(otherRef);   // bidirectional link
ctx.unlink(otherRef); // remove it

// Or from outside:
system.link(refA, refB);
system.unlink(refA, refB);
```

### Trapping exits

Convert incoming exit signals into deliverable signals instead of crashing:

```java
system.spawn("resilient", new Actor<String>() {
    @Override
    public void preStart(BayouContext<String> ctx) {
        ctx.trapExits(true);
        ctx.link(partnerRef);
    }

    @Override
    public void onSignal(Signal signal, BayouContext<String> ctx) {
        if (signal instanceof LinkedActorDied lad) {
            ctx.logger().warn("Partner '{}' died: {}", lad.actorId(), lad.cause());
            // actor survives; handle the failure here
        }
    }
});
```

---

## Back-pressure

Protect actors from runaway producers with bounded mailboxes:

```java
// Drop incoming messages when mailbox is full (default strategy)
Ref<String> actor = system.spawn("bounded",
        (msg, ctx) -> process(msg),
        MailboxConfig.bounded(100));

// Explicit overflow strategy
MailboxConfig config = MailboxConfig.bounded(100, OverflowStrategy.DROP_OLDEST);

// With observability hook
MailboxConfig config = MailboxConfig.bounded(100, OverflowStrategy.REJECT,
        (actorId, capacity, strategy) ->
            metrics.increment("mailbox.overflow", actorId));
```

**Overflow strategies:**

| Strategy | Behaviour when full |
|---|---|
| `DROP_NEWEST` | Silently discard the incoming message (default) |
| `DROP_OLDEST` | Remove the oldest queued message, enqueue the new one |
| `REJECT` | Throw `MailboxFullException` from `tell()` |

Supervised children also support bounded mailboxes via the `withMailbox` builder:

```java
ChildSpec.stateless("worker", handler)
         .withMailbox(MailboxConfig.bounded(50, OverflowStrategy.DROP_OLDEST))
```

All existing actors without a config use an unbounded mailbox — zero behaviour change.

---

## PubSub

Topic-based publish/subscribe. One `BayouPubSub` registry per system, accessed via `system.pubsub()`:

```java
BayouPubSub pubsub = system.pubsub();

// Subscribe
Ref<String> subscriber = system.spawn("listener", (msg, ctx) -> handle(msg));
pubsub.subscribe("events", subscriber);

// Publish to all live subscribers
pubsub.publish("events", "something happened");

// Unsubscribe
pubsub.unsubscribe("events", subscriber);
```

Actors can subscribe themselves from within `preStart` using `ctx.self()`:

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

Dead actor references are silently skipped on publish and lazily cleaned up. `MailboxFullException` from bounded actors is also silently swallowed — PubSub is best-effort delivery.

---

## Supervision

Bayou supports Erlang/Akka-style supervision trees. Supervisors own a group of child actors and
react to their crashes — restarting, stopping, or escalating — so transient failures are
recovered automatically without any application-level error handling.

### Basic supervisor

```java
SupervisorRef ref = system.spawnSupervisor("my-supervisor", new SupervisorActor() {

    @Override
    public List<ChildSpec> children() {
        return List.of(
            ChildSpec.stateless("worker",  (msg, ctx) -> handle(msg)),
            ChildSpec.stateful("counter",  new CounterActor(), new JavaSerializer<>()),
            ChildSpec.eventSourced("ledger", new LedgerActor(), new JavaSerializer<>())
        );
    }

    @Override
    public SupervisionStrategy strategy() {
        return new OneForOneStrategy(new RestartWindow(5, Duration.ofSeconds(60)));
    }
});
```

`SupervisorRef` extends `Ref<Void>` — supervisors don't accept user messages.

### Supervision strategies

| Strategy | Behaviour on crash |
|---|---|
| `OneForOneStrategy` | Restart only the crashed child; siblings are unaffected |
| `AllForOneStrategy` | Stop all children, then restart all in declaration order |

### Restart window and death spiral guard

```java
// Restart up to 5 times within 60 seconds, then escalate
new OneForOneStrategy(new RestartWindow(5, Duration.ofSeconds(60)))

// Always restart — no limit
new OneForOneStrategy(RestartWindow.UNLIMITED)
```

When the window is exceeded the supervisor escalates to its own parent. A top-level supervisor logs a critical error and stops gracefully.

### Custom strategies

`SupervisionStrategy` is a functional interface:

```java
SupervisionStrategy stopAll = (childId, cause) -> RestartDecision.STOP;
```

| Decision | Meaning |
|---|---|
| `RESTART` | Restart only the crashed child |
| `RESTART_ALL` | Stop all children then restart all |
| `STOP` | Permanently stop the crashed child |
| `ESCALATE` | Propagate failure up the tree |

### Dynamic child spawning

```java
SupervisorRef sup = system.spawnSupervisor("router", new SupervisorActor() {
    @Override
    public SupervisionStrategy strategy() {
        return new OneForOneStrategy(RestartWindow.UNLIMITED);
    }
});

sup.spawnChild(ChildSpec.stateless("worker-1", handler));
sup.spawnChild(ChildSpec.stateless("worker-2", handler));
```

### Nested supervisors

```java
system.spawnSupervisor("root", new SupervisorActor() {
    @Override
    public List<ChildSpec> children() {
        return List.of(
            ChildSpec.supervisor("db-group", new SupervisorActor() {
                @Override
                public List<ChildSpec> children() {
                    return List.of(
                        ChildSpec.stateless("db-writer", writerActor),
                        ChildSpec.stateless("db-reader", readerActor)
                    );
                }
                @Override
                public SupervisionStrategy strategy() {
                    return new AllForOneStrategy(new RestartWindow(3, Duration.ofSeconds(30)));
                }
            })
        );
    }
    @Override
    public SupervisionStrategy strategy() {
        return new OneForOneStrategy(RestartWindow.UNLIMITED);
    }
});
```

---

## Serialization

`BayouSerializer<T>` is the pluggable serialization interface:

```java
public interface BayouSerializer<T> {
    byte[] serialize(T value) throws IOException;
    T deserialize(byte[] bytes) throws IOException;
}
```

`JavaSerializer<T extends Serializable>` ships as a ready-made default. For production, implement the interface with Kryo, Protobuf, or any other format.

---

## How gumbo is used

| Flavour | Gumbo tag | Write path | Read path |
|---|---|---|---|
| `Actor` / `StateMachineActor` | — | never | never |
| `EventSourcedActor` | `bayou.events:<id>` | one append per emitted event | full replay on startup |
| `StatefulActor` | `bayou.snapshots:<id>` | one append every N messages + on stop | latest entry on startup |

Tags follow gumbo's namespace+key convention: `LogTag.of("bayou.events", actorId)`. Multiple actors coexist in the same physical log; each has its own scoped `LogView`.
