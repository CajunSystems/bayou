# Bayou

An actor system built on top of [gumbo](https://github.com/CajunSystems/gumbo) — a shared append-only log. Bayou ships three actor flavours that cover the spectrum from pure in-memory processing to fully event-sourced state.

## Dependency

Add gumbo via JitPack and then bayou itself:

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
    <version>main-SNAPSHOT</version>
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
    @Override public void handle(Job job, BayouContext ctx) { process(job); }
    @Override public void preStart(BayouContext ctx)  { openConnection(); }
    @Override public void postStop(BayouContext ctx)  { closeConnection(); }
    @Override public void onError(Job job, Throwable e, BayouContext ctx) {
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
    public List<BankEvent> handle(Balance state, BankCmd cmd, BayouContext ctx) {
        return switch (cmd) {
            case BankCmd.Deposit(long c)  -> List.of(new BankEvent.Deposited(c));
            case BankCmd.Withdraw(long c) -> state.cents() >= c
                    ? List.of(new BankEvent.Withdrawn(c))
                    : List.of();                            // insufficient funds — no event
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

Use this when state is large or changes too frequently for replay to be practical.

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
    public Tally reduce(Tally state, TallyCmd cmd, BayouContext ctx) {
        return switch (cmd) {
            case TallyCmd.Count(String w) -> state.add(w);
            case TallyCmd.Get(String w)   -> {
                ctx.reply(state.counts().getOrDefault(w, 0));
                yield state;
            }
        };
    }
}

// snapshot every 100 messages (default), or specify your own interval
Ref<TallyCmd> counter = system.spawnStateful(
        "word-counter", new WordCounter(), new JavaSerializer<>());

counter.tell(new TallyCmd.Count("hello"));
int n = counter.<Integer>ask(new TallyCmd.Get("hello")).get(1, SECONDS);
```

**Log tag:** `bayou.snapshots:<actorId>`

---

## Messaging

| Method | Behaviour |
|---|---|
| `ref.tell(msg)` | Fire-and-forget; message is queued and returns immediately |
| `ref.ask(msg)` | Returns a `CompletableFuture<R>`; the actor must call `ctx.reply(value)` |
| `ref.stop()` | Drains the mailbox, then stops; returns a future that completes on shutdown |

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
| `Actor` | — | never | never |
| `EventSourcedActor` | `bayou.events:<id>` | one append per emitted event | full replay on startup |
| `StatefulActor` | `bayou.snapshots:<id>` | one append every N messages + on stop | latest entry on startup |

Tags follow gumbo's namespace+key convention: `LogTag.of("bayou.events", actorId)`. Multiple actors coexist in the same physical log; each has its own scoped `LogView`.

---

## Supervision

Bayou supports Erlang/Akka-style supervision trees. Supervisors own a group of child actors and
react to their crashes — restarting, stopping, or escalating — so transient failures are
recovered automatically without any application-level error handling.

### Basic supervisor

Use `spawnSupervisor()` with a `SupervisorActor` that declares the children and the strategy:

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

`SupervisorRef` extends `Ref<Void>` — supervisors don't accept user messages. Use `ref.stop()` to stop the supervisor and all its children.

### Supervision strategies

Two built-in strategies:

| Strategy | Behaviour on crash |
|---|---|
| `OneForOneStrategy` | Restart only the crashed child; siblings are unaffected |
| `AllForOneStrategy` | Stop all children, then restart all in declaration order |

### Restart window and death spiral guard

A `RestartWindow` limits how many times a child can crash within a rolling time window before
the supervisor gives up and escalates:

```java
// Restart up to 5 times within 60 seconds, then escalate:
new OneForOneStrategy(new RestartWindow(5, Duration.ofSeconds(60)))

// Always restart — no limit:
new OneForOneStrategy(RestartWindow.UNLIMITED)
```

When the window is exceeded the supervisor escalates: it treats itself as crashed and fires a
crash signal to its own parent. A top-level supervisor (no parent) logs a critical error and
stops gracefully.

### Custom strategies and RestartDecision

`SupervisionStrategy` is a functional interface — pass a lambda for one-off decisions:

```java
// Always stop the crashed child; never restart
SupervisionStrategy stopAll = (childId, cause) -> RestartDecision.STOP;
```

The four `RestartDecision` values:

| Decision | Meaning |
|---|---|
| `RESTART` | Restart only the crashed child (one-for-one) |
| `RESTART_ALL` | Stop all children then restart all (all-for-one) |
| `STOP` | Permanently stop the crashed child; no restart |
| `ESCALATE` | Propagate failure up the supervision tree |

### Dynamic child spawning

`children()` defaults to an empty list — implement only `strategy()` for supervisors that
add children at runtime:

```java
SupervisorRef sup = system.spawnSupervisor("router", new SupervisorActor() {
    @Override
    public SupervisionStrategy strategy() {
        return new OneForOneStrategy(RestartWindow.UNLIMITED);
    }
});

// Add children from any thread after the supervisor is alive
sup.spawnChild(ChildSpec.stateless("worker-1", handler));
sup.spawnChild(ChildSpec.stateless("worker-2", handler));
```

### Nested supervisors

`ChildSpec.supervisor()` embeds a supervisor as a child, forming a multi-level tree. Escalation
propagates up: when a child supervisor exhausts its restart window it becomes a crash in the
parent supervisor's mailbox.

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

### Stateful and event-sourced restart semantics

Supervised stateful actors restore their last snapshot on restart. Supervised event-sourced actors
replay their full event log. No extra configuration is needed — restart calls `initialize()` on the
runner, which reads from the shared log automatically.
