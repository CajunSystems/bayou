# Patterns

This guide covers six common actor patterns in Bayou. Each section explains when to reach
for the pattern and includes a complete, compiling example.

---

## 1. Request-Reply

The caller sends a message and waits for a response. `ref.ask(msg)` returns a
`CompletableFuture<R>`; the actor calls `ctx.reply(value)` to complete it.

**When to use:** query actors for their current state, coordinate synchronous-style
interactions in async code, bridge actor systems to blocking callers.

```java
import com.cajunsystems.bayou.BayouSystem;
import com.cajunsystems.bayou.Ref;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;

import java.util.concurrent.TimeUnit;

public class RequestReplyExample {

    sealed interface Cmd {
        record Echo(String text)    implements Cmd {}
        record Reverse(String text) implements Cmd {}
    }

    public static void main(String[] args) throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();

        try (SharedLogService log = SharedLogService.open(config);
             BayouSystem system = new BayouSystem(log)) {

            Ref<Cmd> processor = system.spawn("processor", (cmd, ctx) -> {
                switch (cmd) {
                    case Cmd.Echo(String t)    -> ctx.reply(t);
                    case Cmd.Reverse(String t) -> ctx.reply(new StringBuilder(t).reverse().toString());
                }
            });

            // ask() is non-blocking; .get() blocks the calling thread until the reply arrives
            String echo    = processor.<String>ask(new Cmd.Echo("hello")).get(1, TimeUnit.SECONDS);
            String reversed = processor.<String>ask(new Cmd.Reverse("bayou")).get(1, TimeUnit.SECONDS);

            System.out.println(echo);     // hello
            System.out.println(reversed); // uoyab

            system.shutdown();
        }
    }
}
```

Notes:
- Only the **first** `ctx.reply(value)` per message is delivered. Subsequent calls on the
  same context are ignored.
- If the actor does not call `ctx.reply()`, the future hangs. Always set a timeout with
  `.get(n, TimeUnit)`.
- `ask()` creates an internal one-shot reply channel that is discarded after the first reply.

---

## 2. Fan-Out

One actor (or caller) broadcasts a message to N workers. There are two variants:

### Variant A — Direct Tell Loop

Maintain a list of worker refs and tell each one directly. Simple, zero overhead,
works for a static or slowly-changing worker set.

```java
import com.cajunsystems.bayou.BayouSystem;
import com.cajunsystems.bayou.Ref;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;

import java.util.List;

public class FanOutDirectExample {
    public static void main(String[] args) throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();

        try (SharedLogService log = SharedLogService.open(config);
             BayouSystem system = new BayouSystem(log)) {

            // Create worker pool
            List<Ref<String>> workers = List.of(
                system.spawn("worker-1", (msg, ctx) -> ctx.logger().info("[1] {}", msg)),
                system.spawn("worker-2", (msg, ctx) -> ctx.logger().info("[2] {}", msg)),
                system.spawn("worker-3", (msg, ctx) -> ctx.logger().info("[3] {}", msg))
            );

            // Broadcaster actor fans out to all workers
            Ref<String> broadcaster = system.spawn("broadcaster", (msg, ctx) -> {
                for (Ref<String> worker : workers) {
                    worker.tell(msg);
                }
            });

            broadcaster.tell("deploy-config-v2");
            broadcaster.tell("deploy-config-v3");

            Thread.sleep(100);
            system.shutdown();
        }
    }
}
```

### Variant B — BayouPubSub

Use when the subscriber set is dynamic (actors subscribe and unsubscribe themselves) or
when you want loose coupling between the publisher and subscribers.

```java
import com.cajunsystems.bayou.*;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;

public class FanOutPubSubExample {
    public static void main(String[] args) throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();

        try (SharedLogService log = SharedLogService.open(config);
             BayouSystem system = new BayouSystem(log)) {

            BayouPubSub pubsub = system.pubsub();

            // Subscribers register independently
            Ref<String> cache    = system.spawn("cache",    (m, ctx) -> ctx.logger().info("cache: {}", m));
            Ref<String> metrics  = system.spawn("metrics",  (m, ctx) -> ctx.logger().info("metrics: {}", m));
            Ref<String> auditor  = system.spawn("auditor",  (m, ctx) -> ctx.logger().info("audit: {}", m));

            pubsub.subscribe("user-events", cache);
            pubsub.subscribe("user-events", metrics);
            pubsub.subscribe("user-events", auditor);

            // Publish — all three receive it
            pubsub.publish("user-events", "user.login:alice");
            pubsub.publish("user-events", "user.login:bob");

            Thread.sleep(100);
            system.shutdown();
        }
    }
}
```

---

## 3. Work Queue (Round-Robin Router)

A router actor dispatches work to a pool of worker actors in round-robin order. The
router keeps a cyclic index; each incoming message is forwarded to the next worker.

**When to use:** CPU-bound tasks you want to parallelise, independent jobs with no
ordering requirement, any workload where distributing evenly across workers is sufficient.

```java
import com.cajunsystems.bayou.BayouSystem;
import com.cajunsystems.bayou.BayouContext;
import com.cajunsystems.bayou.Ref;
import com.cajunsystems.bayou.actor.Actor;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;

import java.util.List;

public class WorkQueueExample {

    static class RoundRobinRouter implements Actor<String> {
        private final List<Ref<String>> workers;
        private int index = 0;

        RoundRobinRouter(List<Ref<String>> workers) {
            this.workers = workers;
        }

        @Override
        public void handle(String job, BayouContext<String> ctx) {
            workers.get(index % workers.size()).tell(job);
            index++;
        }
    }

    public static void main(String[] args) throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();

        try (SharedLogService log = SharedLogService.open(config);
             BayouSystem system = new BayouSystem(log)) {

            // Spawn 3 workers
            List<Ref<String>> workers = List.of(
                system.spawn("worker-1", (job, ctx) -> ctx.logger().info("[w1] processing {}", job)),
                system.spawn("worker-2", (job, ctx) -> ctx.logger().info("[w2] processing {}", job)),
                system.spawn("worker-3", (job, ctx) -> ctx.logger().info("[w3] processing {}", job))
            );

            Ref<String> router = system.spawn("router", new RoundRobinRouter(workers));

            // Jobs are dispatched: w1, w2, w3, w1, w2, w3
            for (int i = 1; i <= 6; i++) {
                router.tell("job-" + i);
            }

            Thread.sleep(100);
            system.shutdown();
        }
    }
}
```

For a supervised worker pool with automatic restart on crash, place the router and workers
under a supervisor (see [Supervision](supervision.md)).

---

## 4. Circuit Breaker

A circuit breaker wraps an unreliable dependency (an HTTP client, a database). When the
dependency starts failing, the circuit opens and rejects calls immediately — protecting
the system from cascading failures while the dependency recovers.

States: `CLOSED` (normal), `OPEN` (rejecting), `HALF_OPEN` (probing recovery).

Implement with `StateMachineActor`:

```java
import com.cajunsystems.bayou.*;
import com.cajunsystems.bayou.actor.StateMachineActor;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class CircuitBreakerExample {

    enum CircuitState { CLOSED, OPEN, HALF_OPEN }

    sealed interface CircuitCmd {
        record Call(String payload) implements CircuitCmd {}
        record Success()            implements CircuitCmd {}
        record Failure()            implements CircuitCmd {}
        record Probe()              implements CircuitCmd {}
    }

    static class CircuitBreaker implements StateMachineActor<CircuitState, CircuitCmd> {
        private int failures = 0;
        private static final int THRESHOLD = 3;

        @Override
        public Optional<CircuitState> transition(
                CircuitState state, CircuitCmd cmd, BayouContext<CircuitCmd> ctx) {
            return switch (state) {
                case CLOSED -> switch (cmd) {
                    case CircuitCmd.Call(String p) -> {
                        ctx.logger().info("CLOSED — calling with: {}", p);
                        // Simulate: forward to real dependency here
                        ctx.reply("ok:" + p);
                        yield Optional.empty();
                    }
                    case CircuitCmd.Failure() -> {
                        failures++;
                        if (failures >= THRESHOLD) {
                            ctx.logger().warn("Circuit OPENING after {} failures", failures);
                            // Schedule a probe after 5 seconds
                            ctx.scheduleOnce(java.time.Duration.ofSeconds(5), new CircuitCmd.Probe());
                            yield Optional.of(CircuitState.OPEN);
                        }
                        yield Optional.empty();
                    }
                    default -> Optional.empty();
                };
                case OPEN -> switch (cmd) {
                    case CircuitCmd.Call(String p) -> {
                        ctx.logger().warn("OPEN — rejecting call: {}", p);
                        ctx.reply("circuit-open");
                        yield Optional.empty();
                    }
                    case CircuitCmd.Probe() -> {
                        ctx.logger().info("Probing — entering HALF_OPEN");
                        yield Optional.of(CircuitState.HALF_OPEN);
                    }
                    default -> Optional.empty();
                };
                case HALF_OPEN -> switch (cmd) {
                    case CircuitCmd.Call(String p) -> {
                        ctx.logger().info("HALF_OPEN — probing with: {}", p);
                        ctx.reply("probe:" + p);
                        yield Optional.empty();
                    }
                    case CircuitCmd.Success() -> {
                        failures = 0;
                        ctx.logger().info("Probe succeeded — closing circuit");
                        yield Optional.of(CircuitState.CLOSED);
                    }
                    case CircuitCmd.Failure() -> {
                        ctx.logger().warn("Probe failed — reopening circuit");
                        ctx.scheduleOnce(java.time.Duration.ofSeconds(5), new CircuitCmd.Probe());
                        yield Optional.of(CircuitState.OPEN);
                    }
                    default -> Optional.empty();
                };
            };
        }

        @Override
        public void onEnter(CircuitState state, BayouContext<CircuitCmd> ctx) {
            ctx.logger().info("Circuit entered state: {}", state);
        }
    }

    public static void main(String[] args) throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();

        try (SharedLogService log = SharedLogService.open(config);
             BayouSystem system = new BayouSystem(log)) {

            Ref<CircuitCmd> breaker = system.spawnStateMachine(
                    "circuit-breaker", new CircuitBreaker(), CircuitState.CLOSED);

            // Normal calls succeed
            String r1 = breaker.<String>ask(new CircuitCmd.Call("request-1")).get(1, TimeUnit.SECONDS);
            System.out.println(r1); // ok:request-1

            // Simulate failures to trip the breaker
            breaker.tell(new CircuitCmd.Failure());
            breaker.tell(new CircuitCmd.Failure());
            breaker.tell(new CircuitCmd.Failure()); // trips at threshold 3

            Thread.sleep(50); // let the state transition propagate

            // Circuit is now OPEN — calls are rejected immediately
            String r2 = breaker.<String>ask(new CircuitCmd.Call("request-2")).get(1, TimeUnit.SECONDS);
            System.out.println(r2); // circuit-open

            system.shutdown();
        }
    }
}
```

---

## 5. Pipeline

A pipeline chains actors so that each stage processes a message and forwards the result to
the next. Messages flow left-to-right as immutable values; there is no shared state between
stages.

**When to use:** multi-step data processing where each step is independent (parse → validate
→ persist), ETL pipelines, request handling chains.

```java
import com.cajunsystems.bayou.BayouSystem;
import com.cajunsystems.bayou.Ref;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;

public class PipelineExample {

    // Immutable message types flowing through the pipeline
    record RawInput(String text) {}
    record ParsedData(String[] fields) {}
    record ValidatedRecord(String id, int amount) {}

    public static void main(String[] args) throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();

        try (SharedLogService log = SharedLogService.open(config);
             BayouSystem system = new BayouSystem(log)) {

            // Stage 3 — persist (leaf; no downstream)
            Ref<ValidatedRecord> persister = system.spawn("persister",
                    (rec, ctx) -> ctx.logger().info("Persisting: id={} amount={}", rec.id(), rec.amount()));

            // Stage 2 — validate; forwards to persister
            Ref<ParsedData> validator = system.spawn("validator", (data, ctx) -> {
                if (data.fields().length < 2) {
                    ctx.logger().warn("Dropping invalid record: not enough fields");
                    return;
                }
                try {
                    ValidatedRecord rec = new ValidatedRecord(data.fields()[0],
                            Integer.parseInt(data.fields()[1].trim()));
                    persister.tell(rec);
                } catch (NumberFormatException e) {
                    ctx.logger().warn("Dropping invalid record: bad amount '{}'", data.fields()[1]);
                }
            });

            // Stage 1 — parse; forwards to validator
            Ref<RawInput> parser = system.spawn("parser", (raw, ctx) -> {
                ParsedData parsed = new ParsedData(raw.text().split(","));
                validator.tell(parsed);
            });

            // Feed data into the pipeline head
            parser.tell(new RawInput("order-001,1500"));
            parser.tell(new RawInput("order-002,bad-amount"));
            parser.tell(new RawInput("order-003,2200"));
            parser.tell(new RawInput("missing-fields"));

            Thread.sleep(100);
            system.shutdown();
        }
    }
}
```

Assemble pipelines bottom-up (last stage first) so each stage can close over the reference
to the next. For fault tolerance, place all stages under a supervisor; if any stage crashes,
the supervisor restarts it while the others continue processing.

---

## 6. Saga / Compensating Actions

A saga coordinates a multi-step process where each step talks to an external service or
actor. If any step fails, already-completed steps must be rolled back via compensation
messages.

**When to use:** distributed transactions, multi-service order workflows, any long-running
process where partial completion is worse than rolling back entirely.

The saga actor tracks completed steps in its own state. On failure it iterates backward
through the completed steps and sends a compensation message to each.

```java
import com.cajunsystems.bayou.*;
import com.cajunsystems.bayou.actor.StatefulActor;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class SagaExample {

    // External services (stubs — replace with real actors)
    record ReserveInventory(String orderId, int qty) {}
    record ReleaseInventory(String orderId, int qty) {}

    record ChargePayment(String orderId, int cents) {}
    record RefundPayment(String orderId, int cents) {}

    record ScheduleShipment(String orderId) {}
    record CancelShipment(String orderId) {}

    // Saga commands
    sealed interface SagaCmd {
        record Start(String orderId, int qty, int cents) implements SagaCmd {}
        record InventoryReserved()                        implements SagaCmd {}
        record InventoryFailed()                          implements SagaCmd {}
        record PaymentCharged()                           implements SagaCmd {}
        record PaymentFailed()                            implements SagaCmd {}
        record ShipmentScheduled()                        implements SagaCmd {}
    }

    enum SagaStep { INVENTORY, PAYMENT, SHIPMENT }

    record SagaState(
            String orderId,
            int qty,
            int cents,
            List<SagaStep> completed
    ) implements Serializable {}

    static class OrderSaga implements StatefulActor<SagaState, SagaCmd> {

        // Inject real actor refs in production
        private final Ref<ReserveInventory>   inventory;
        private final Ref<ChargePayment>      payment;
        private final Ref<ScheduleShipment>   shipment;
        private final Ref<ReleaseInventory>   inventoryCompensation;
        private final Ref<RefundPayment>      paymentCompensation;
        private final Ref<CancelShipment>     shipmentCompensation;

        OrderSaga(
                Ref<ReserveInventory> inventory,
                Ref<ChargePayment> payment,
                Ref<ScheduleShipment> shipment,
                Ref<ReleaseInventory> inventoryCompensation,
                Ref<RefundPayment> paymentCompensation,
                Ref<CancelShipment> shipmentCompensation) {
            this.inventory              = inventory;
            this.payment                = payment;
            this.shipment               = shipment;
            this.inventoryCompensation  = inventoryCompensation;
            this.paymentCompensation    = paymentCompensation;
            this.shipmentCompensation   = shipmentCompensation;
        }

        @Override
        public SagaState initialState() {
            return new SagaState("", 0, 0, new ArrayList<>());
        }

        @Override
        public SagaState reduce(SagaState state, SagaCmd cmd, BayouContext<SagaCmd> ctx) {
            return switch (cmd) {
                case SagaCmd.Start(String id, int qty, int cents) -> {
                    ctx.logger().info("Saga starting for order {}", id);
                    // Step 1: reserve inventory
                    inventory.tell(new ReserveInventory(id, qty));
                    yield new SagaState(id, qty, cents, new ArrayList<>());
                }
                case SagaCmd.InventoryReserved() -> {
                    ctx.logger().info("Inventory reserved — charging payment");
                    List<SagaStep> completed = new ArrayList<>(state.completed());
                    completed.add(SagaStep.INVENTORY);
                    // Step 2: charge payment
                    payment.tell(new ChargePayment(state.orderId(), state.cents()));
                    yield new SagaState(state.orderId(), state.qty(), state.cents(), completed);
                }
                case SagaCmd.InventoryFailed() -> {
                    ctx.logger().warn("Inventory failed — no compensation needed (nothing completed)");
                    yield state;
                }
                case SagaCmd.PaymentCharged() -> {
                    ctx.logger().info("Payment charged — scheduling shipment");
                    List<SagaStep> completed = new ArrayList<>(state.completed());
                    completed.add(SagaStep.PAYMENT);
                    // Step 3: schedule shipment
                    shipment.tell(new ScheduleShipment(state.orderId()));
                    yield new SagaState(state.orderId(), state.qty(), state.cents(), completed);
                }
                case SagaCmd.PaymentFailed() -> {
                    ctx.logger().warn("Payment failed — compensating completed steps");
                    compensate(state, ctx);
                    yield state;
                }
                case SagaCmd.ShipmentScheduled() -> {
                    ctx.logger().info("Saga complete for order {}", state.orderId());
                    yield state;
                }
            };
        }

        private void compensate(SagaState state, BayouContext<SagaCmd> ctx) {
            // Iterate completed steps in reverse order and undo each one
            List<SagaStep> completed = new ArrayList<>(state.completed());
            java.util.Collections.reverse(completed);
            for (SagaStep step : completed) {
                switch (step) {
                    case PAYMENT   -> paymentCompensation.tell(new RefundPayment(state.orderId(), state.cents()));
                    case INVENTORY -> inventoryCompensation.tell(new ReleaseInventory(state.orderId(), state.qty()));
                    case SHIPMENT  -> shipmentCompensation.tell(new CancelShipment(state.orderId()));
                }
                ctx.logger().info("Compensated step: {}", step);
            }
        }
    }
}
```

Key design points:
- The saga actor holds the list of completed steps in its state so compensation is always
  possible even after a crash and restart.
- Each step is a fire-and-forget `tell`; the external service replies back to the saga
  actor via a `SagaCmd` message (e.g. `InventoryReserved`, `PaymentFailed`).
- For durability, use `StatefulActor` (snapshot-based) or `EventSourcedActor` (full event
  replay) so the saga state survives process restarts.
- Compensation messages must be idempotent — the saga may retry them on restart.
