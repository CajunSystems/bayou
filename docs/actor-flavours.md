# Actor Flavours

Bayou provides four actor flavours. Choosing the right one up front avoids unnecessary
rewrites later. This guide explains when to reach for each one.

---

## 1. Quick Decision Table

| | `Actor` | `StatefulActor` | `EventSourcedActor` | `StateMachineActor` |
|---|---|---|---|---|
| **Gumbo (log) usage** | None | Snapshot on interval + stop | Append every event; full replay on start | None |
| **Restart behaviour** | Fresh start | Loads latest snapshot | Replays all events → exact state | Fresh start |
| **Memory footprint** | Minimal | State in heap | State in heap (events on disk) | State enum in heap |
| **Typical use case** | I/O, routing, computation | Counters, caches, aggregates | Ledgers, audit logs, time-travel | Connection lifecycle, workflows |

**Default recommendation:** start with `Actor`. Upgrade to `EventSourcedActor` when you
need durability, or `StateMachineActor` when your transitions are well-defined.

---

## 2. Actor (Stateless)

The simplest flavour. No log interaction. Ideal for pure computation, I/O adapters,
message routing, or any actor where state does not need to survive a restart.

**When to use:**
- HTTP/database clients
- Log forwarders, metrics reporters
- Routers and load balancers
- Any actor you can afford to restart from zero

```java
import com.cajunsystems.bayou.BayouSystem;
import com.cajunsystems.bayou.Ref;
import com.cajunsystems.bayou.actor.Actor;
import com.cajunsystems.bayou.BayouContext;

// Lambda form — best for simple handlers
Ref<String> echo = system.spawn("echo",
        (msg, ctx) -> ctx.logger().info("echo: {}", msg));

echo.tell("hello");

// Full interface form — use when you need lifecycle hooks
system.spawn("worker", new Actor<String>() {
    @Override
    public void preStart(BayouContext<String> ctx) {
        ctx.logger().info("worker starting");
    }

    @Override
    public void handle(String msg, BayouContext<String> ctx) {
        ctx.logger().info("handling: {}", msg);
    }

    @Override
    public void postStop(BayouContext<String> ctx) {
        ctx.logger().info("worker stopped");
    }

    @Override
    public void onError(String msg, Throwable e, BayouContext<String> ctx) {
        ctx.logger().error("error processing: {}", msg, e);
        // default: rethrow — the supervisor will restart
    }
});
```

---

## 3. StatefulActor (Snapshot-Based)

A `(state, message) → state` reducer. State is held in memory and periodically
snapshotted to the gumbo log. On restart the latest snapshot is loaded — no replay is
needed.

**When to use:**
- Counters, rate limiters, running totals
- In-memory caches with periodic durability
- Aggregates where only the current state matters (not history)
- Anything where snapshot interval >> event frequency

**Snapshot mechanics:** a snapshot is written every `snapshotInterval` messages (default
100) and unconditionally when the actor stops. On startup only the latest snapshot is
read — the history of individual messages is not recoverable.

```java
import com.cajunsystems.bayou.*;
import com.cajunsystems.bayou.actor.StatefulActor;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

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
    @Override
    public Tally initialState() { return new Tally(new HashMap<>()); }

    @Override
    public Tally reduce(Tally state, TallyCmd cmd, BayouContext<TallyCmd> ctx) {
        return switch (cmd) {
            case TallyCmd.Count(String w) -> state.add(w);
            case TallyCmd.Get(String w)   -> {
                ctx.reply(state.counts().getOrDefault(w, 0));
                yield state; // state unchanged
            }
        };
    }
}

Ref<TallyCmd> counter = system.spawnStateful(
        "word-counter", new WordCounter(), new JavaSerializer<>());

counter.tell(new TallyCmd.Count("hello"));
counter.tell(new TallyCmd.Count("hello"));
int n = counter.<Integer>ask(new TallyCmd.Get("hello")).get(1, TimeUnit.SECONDS);
// n == 2
```

---

## 4. EventSourcedActor (Log-Sourced)

State is derived by replaying a log of immutable events. There is no separate state
store — the log **is** the state. Every `handle` call appends events; every restart
replays them through `apply`.

**When to use:**
- Financial ledgers, bank accounts, payment processors
- Audit logs where you must prove every change
- Anything that needs "what was the state at time T?" (time travel)
- Workflows where the full history drives business logic

**Replay mechanics:** on startup all events stored under `bayou.events:<actorId>` are
replayed through `apply` in order. For large event streams consider periodic compaction
or a `StatefulActor` snapshot instead.

```java
import com.cajunsystems.bayou.*;
import com.cajunsystems.bayou.actor.EventSourcedActor;

import java.io.Serializable;
import java.util.List;

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

class BankAccount implements EventSourcedActor<Balance, BankEvent, BankCmd> {

    @Override
    public Balance initialState() { return new Balance(0); }

    @Override
    public List<BankEvent> handle(Balance state, BankCmd cmd, BayouContext<BankCmd> ctx) {
        return switch (cmd) {
            case BankCmd.Deposit(long c)  -> List.of(new BankEvent.Deposited(c));
            case BankCmd.Withdraw(long c) -> state.cents() >= c
                    ? List.of(new BankEvent.Withdrawn(c))
                    : List.of(); // insufficient funds
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

account.tell(new BankCmd.Deposit(1000));
long balance = account.<Long>ask(new BankCmd.GetBalance()).get(1, TimeUnit.SECONDS);
```

---

## 5. StateMachineActor (FSM)

A finite state machine. Declare a state type (usually an `enum`), implement `transition`
to return the next state on each message, and optionally implement `onEnter`/`onExit`
for side-effects on state changes.

**When to use:**
- Connection lifecycle (DISCONNECTED → CONNECTING → CONNECTED → CLOSING)
- Order workflows (PENDING → PAID → SHIPPED → DELIVERED)
- Any multi-step process with well-defined transitions

**Callback semantics:**
- `onEnter(state, ctx)` — called when entering a state (including the initial state on startup)
- `onExit(state, ctx)` — called just before leaving a state
- `transition` returns `Optional.empty()` to stay in the current state — no callbacks fire

```java
import com.cajunsystems.bayou.*;
import com.cajunsystems.bayou.actor.StateMachineActor;

import java.util.Optional;

enum TrafficLight { RED, GREEN, YELLOW }

Ref<String> fsm = system.spawnStateMachine("traffic",
    new StateMachineActor<TrafficLight, String>() {

        @Override
        public Optional<TrafficLight> transition(
                TrafficLight state, String msg, BayouContext<String> ctx) {
            return switch (msg) {
                case "go"   -> state == TrafficLight.RED    ? Optional.of(TrafficLight.GREEN)  : Optional.empty();
                case "slow" -> state == TrafficLight.GREEN  ? Optional.of(TrafficLight.YELLOW) : Optional.empty();
                case "stop" -> state == TrafficLight.YELLOW ? Optional.of(TrafficLight.RED)    : Optional.empty();
                default     -> Optional.empty();
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

fsm.tell("go");   // RED → GREEN (onExit(RED), onEnter(GREEN))
fsm.tell("slow"); // GREEN → YELLOW
fsm.tell("stop"); // YELLOW → RED
```

---

## 6. Mixed Architectures

Real systems mix flavours. A common pattern is a supervisor that coordinates actors of
different types:

```java
SupervisorRef root = system.spawnSupervisor("root", new SupervisorActor() {

    @Override
    public List<ChildSpec> children() {
        return List.of(
            // Stateless I/O adapter
            ChildSpec.stateless("http-client", httpClientActor),
            // Stateful cache
            ChildSpec.stateful("rate-limiter", new RateLimiter(), new JavaSerializer<>()),
            // Event-sourced ledger
            ChildSpec.eventSourced("ledger", new Ledger(), new JavaSerializer<>()),
            // FSM for connection lifecycle
            ChildSpec.stateMachine("connection", new ConnectionFsm(), ConnectionState.DISCONNECTED)
        );
    }

    @Override
    public SupervisionStrategy strategy() {
        return new OneForOneStrategy(new RestartWindow(5, Duration.ofSeconds(60)));
    }
});
```

Use actor linking (`ctx.link(ref)`) when two actors of different flavours must fail
together. Use death watch (`ctx.watch(ref)`) when you only need to observe termination.

---

## 7. In Doubt?

**Start with `Actor`.** It has zero overhead and zero dependencies on gumbo. If you
discover you need state, switch to `StatefulActor`. If you discover you need full event
history or audit trails, switch to `EventSourcedActor`. Use `StateMachineActor` only
when you have a well-defined state diagram.

Premature persistence is the actor-systems equivalent of premature optimization.
