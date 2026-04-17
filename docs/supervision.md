# Supervision

Bayou supervision trees give you Erlang-style fault isolation: actors crash cleanly on
exceptions, and supervisors decide what to do next — restart, stop, or escalate — without
any application-level `try/catch`.

---

## 1. Why Supervision?

In traditional code, exceptions bubble up the call stack. In an actor system, each actor
runs on its own virtual thread. An unhandled exception crashes **only that actor**; it
does not propagate to callers or siblings. The supervisor is notified and can react.

This is the "let it crash" philosophy: keep actors simple and correct for the happy path,
and push failure recovery to the supervision layer. The result is a separation of concerns
— business logic actors do not contain defensive error handling, only supervisors do.

```
      Root Supervisor
     /       |       \
  Worker  Counter  Ledger     ← any of these can crash independently
```

When `Worker` throws, the supervisor restarts it. `Counter` and `Ledger` never know.

---

## 2. Quick Start

Spawn a supervisor with `system.spawnSupervisor(id, supervisorActor)`. Implement
`SupervisorActor` to declare initial children and choose a strategy.

```java
import com.cajunsystems.bayou.*;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;

import java.time.Duration;
import java.util.List;

public class QuickSupervisionExample {
    public static void main(String[] args) throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();

        try (SharedLogService log = SharedLogService.open(config);
             BayouSystem system = new BayouSystem(log)) {

            SupervisorRef supervisor = system.spawnSupervisor(
                    "app-supervisor",
                    new SupervisorActor() {

                        @Override
                        public List<ChildSpec> children() {
                            return List.of(
                                ChildSpec.stateless("fetcher",
                                        (msg, ctx) -> ctx.logger().info("Fetching: {}", msg)),
                                ChildSpec.stateless("parser",
                                        (msg, ctx) -> ctx.logger().info("Parsing: {}", msg)),
                                ChildSpec.stateless("writer",
                                        (msg, ctx) -> ctx.logger().info("Writing: {}", msg))
                            );
                        }

                        @Override
                        public SupervisionStrategy strategy() {
                            // Restart only the crashed child; up to 5 times per 60 seconds
                            return new OneForOneStrategy(
                                    new RestartWindow(5, Duration.ofSeconds(60)));
                        }
                    });

            // Look up children by their declared IDs
            Ref<String> fetcher = system.<String>lookup("fetcher").orElseThrow();
            fetcher.tell("https://example.com/data");

            Thread.sleep(100);
            system.shutdown();
        }
    }
}
```

`system.spawnSupervisor` returns a `SupervisorRef` (which extends `Ref<Void>`).
Supervisors do not accept user messages; use `supervisor.stop()` and `supervisor.isAlive()`
to manage the supervisor's lifecycle.

Child actors are registered in the system under their declared IDs. Look them up with
`system.lookup(actorId)`.

---

## 3. Supervision Strategies

| Strategy | What happens when a child crashes | Siblings |
|---|---|---|
| `OneForOneStrategy` | Restart only the crashed child | Unaffected |
| `AllForOneStrategy` | Stop all children, then restart all in declaration order | Also restarted |

**When to use `OneForOneStrategy`:**
Children are independent — a fetcher crash does not affect the parser or writer. This
is the most common strategy and a safe default.

**When to use `AllForOneStrategy`:**
Children share a resource (for example, they all use the same database connection
opened in `preStart`). If the connection actor dies, the others can no longer function
correctly and must restart together.

```java
// OneForOne — independent workers
new OneForOneStrategy(new RestartWindow(5, Duration.ofSeconds(60)))

// AllForOne — tightly coupled group
new AllForOneStrategy(new RestartWindow(3, Duration.ofSeconds(30)))
```

---

## 4. Restart Window and Death-Spiral Guard

A `RestartWindow` limits how many times a supervisor will restart a child within a
rolling time window. If the child exceeds the limit, the supervisor escalates to its
own parent instead of retrying.

```java
// Restart up to 5 times within 60 seconds, then escalate
new RestartWindow(5, Duration.ofSeconds(60))

// Always restart — no limit (use for permanent services you always want running)
RestartWindow.UNLIMITED
```

`RestartWindow` fields:
- `maxRestarts` — maximum restart count within the window (must be >= 0)
- `within` — rolling time window; pass `Duration.ZERO` to disable time-windowing

A supervisor with `RestartWindow.UNLIMITED` never escalates due to restart frequency.

**Escalation behaviour:** a supervisor that exceeds its restart window sends a failure
signal to its own parent supervisor. The parent then applies its own strategy to that
supervisor — treating it like any other crashed child. A top-level supervisor with no
parent logs a critical error and stops itself along with all remaining children.

```java
// Guard against death spirals: stop if the child keeps crashing
SupervisorRef sup = system.spawnSupervisor("guarded", new SupervisorActor() {
    @Override
    public List<ChildSpec> children() {
        return List.of(
            ChildSpec.stateless("flaky-service",
                    (msg, ctx) -> ctx.logger().info("Handling: {}", msg))
        );
    }

    @Override
    public SupervisionStrategy strategy() {
        // Allow at most 3 restarts in any 10-second window
        return new OneForOneStrategy(new RestartWindow(3, Duration.ofSeconds(10)));
    }
});
```

---

## 5. Custom Strategy

`SupervisionStrategy` is a `@FunctionalInterface`:

```java
@FunctionalInterface
public interface SupervisionStrategy {
    RestartDecision decide(String childId, Throwable cause);
}
```

Use a lambda when the built-in strategies don't fit — for example, to make the decision
based on the exception type or the child's identity:

```java
SupervisionStrategy custom = (childId, cause) -> {
    if (cause instanceof IllegalArgumentException) {
        // Bad input — stop the child permanently, don't retry
        return RestartDecision.STOP;
    }
    if (cause instanceof java.net.ConnectException) {
        // Network glitch — restart this child only
        return RestartDecision.RESTART;
    }
    if ("db-writer".equals(childId)) {
        // DB writer crash is critical — restart all siblings too
        return RestartDecision.RESTART_ALL;
    }
    // For anything else, escalate to the parent supervisor
    return RestartDecision.ESCALATE;
};
```

Available `RestartDecision` values:

| Decision | Effect |
|---|---|
| `RESTART` | Restart only the crashed child; siblings continue running |
| `RESTART_ALL` | Stop all children, then restart all in declaration order |
| `STOP` | Permanently stop the crashed child; supervisor keeps running |
| `ESCALATE` | Treat this supervisor as crashed; parent supervisor decides what to do |

Custom strategies are not subject to `RestartWindow` limits because the window logic is
built into `OneForOneStrategy` and `AllForOneStrategy`. If you need rate-limiting with a
custom strategy, wrap it in a `OneForOneStrategy` or implement the counting yourself.

---

## 6. Dynamic Children

Spawn additional children at runtime with `SupervisorRef.spawnChild(ChildSpec)`. Dynamic
children are supervised under the same strategy as the initial children.

This is the preferred way to build worker pools that grow on demand:

```java
import com.cajunsystems.bayou.*;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;

import java.util.ArrayList;
import java.util.List;

public class DynamicWorkerPool {
    public static void main(String[] args) throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();

        try (SharedLogService log = SharedLogService.open(config);
             BayouSystem system = new BayouSystem(log)) {

            // Spawn with no initial children — pool is empty
            SupervisorRef pool = system.spawnSupervisor("worker-pool",
                    new SupervisorActor() {
                        @Override
                        public SupervisionStrategy strategy() {
                            return new OneForOneStrategy(RestartWindow.UNLIMITED);
                        }
                    });

            // Add workers dynamically as demand grows
            List<Ref<String>> workers = new ArrayList<>();
            for (int i = 1; i <= 4; i++) {
                int id = i;
                Ref<?> child = pool.spawnChild(
                        ChildSpec.stateless("worker-" + id,
                                (msg, ctx) -> ctx.logger().info("[worker-{}] {}", id, msg)));
                @SuppressWarnings("unchecked")
                Ref<String> w = (Ref<String>) child;
                workers.add(w);
            }

            // Dispatch work across the pool
            for (int i = 0; i < 8; i++) {
                workers.get(i % workers.size()).tell("job-" + i);
            }

            Thread.sleep(100);
            system.shutdown();
        }
    }
}
```

`spawnChild` registers the new actor in the system under its declared ID and returns a
`Ref<?>` typed to the wildcard. Cast or use `system.lookup(actorId)` to get the typed ref.

---

## 7. Nested Supervisors

Large systems benefit from multi-level supervision trees. Use `ChildSpec.supervisor(id,
supervisorActor)` to nest one supervisor inside another.

```java
import com.cajunsystems.bayou.*;
import com.cajunsystems.gumbo.adapter.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.config.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;

import java.time.Duration;
import java.util.List;

public class NestedSupervisorsExample {
    public static void main(String[] args) throws Exception {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();

        try (SharedLogService log = SharedLogService.open(config);
             BayouSystem system = new BayouSystem(log)) {

            system.spawnSupervisor("root", new SupervisorActor() {

                @Override
                public List<ChildSpec> children() {
                    return List.of(
                        // Group 1: database actors — tight coupling, all-for-one
                        ChildSpec.supervisor("db-supervisor", new SupervisorActor() {
                            @Override
                            public List<ChildSpec> children() {
                                return List.of(
                                    ChildSpec.stateless("db-writer",
                                            (msg, ctx) -> ctx.logger().info("Writing: {}", msg)),
                                    ChildSpec.stateless("db-reader",
                                            (msg, ctx) -> ctx.logger().info("Reading: {}", msg))
                                );
                            }
                            @Override
                            public SupervisionStrategy strategy() {
                                // db-writer and db-reader share a resource — restart both on any crash
                                return new AllForOneStrategy(
                                        new RestartWindow(3, Duration.ofSeconds(30)));
                            }
                        }),

                        // Group 2: API actors — independent, one-for-one
                        ChildSpec.supervisor("api-supervisor", new SupervisorActor() {
                            @Override
                            public List<ChildSpec> children() {
                                return List.of(
                                    ChildSpec.stateless("rest-handler",
                                            (msg, ctx) -> ctx.logger().info("REST: {}", msg)),
                                    ChildSpec.stateless("grpc-handler",
                                            (msg, ctx) -> ctx.logger().info("gRPC: {}", msg))
                                );
                            }
                            @Override
                            public SupervisionStrategy strategy() {
                                return new OneForOneStrategy(
                                        new RestartWindow(5, Duration.ofSeconds(60)));
                            }
                        })
                    );
                }

                @Override
                public SupervisionStrategy strategy() {
                    // If either child supervisor escalates, restart it
                    return new OneForOneStrategy(new RestartWindow(2, Duration.ofSeconds(60)));
                }
            });

            Thread.sleep(100);
            system.shutdown();
        }
    }
}
```

**Escalation propagation:** when a child supervisor exceeds its restart window, it escalates
to its parent. The parent supervisor sees this as a crashed child and applies its own
strategy. A crash in `db-writer` that exhausts `db-supervisor`'s window will escalate to
`root`, which can restart `db-supervisor` — restarting the entire database group from
scratch. This lets you tune the recovery policy at each level of the tree independently.

---

## 8. Decision Tree

Use this table to choose your supervision approach:

| Question | Answer → choice |
|---|---|
| Are children independent (one crash doesn't affect others)? | Yes → `OneForOneStrategy` |
| Do children share a resource (DB connection, network channel)? | Yes → `AllForOneStrategy` |
| Should different crash types produce different outcomes? | Yes → custom `SupervisionStrategy` lambda |
| Is the child pool size unknown at startup? | Yes → start with no children, use `spawnChild` |
| Is this group part of a larger system with its own failure domain? | Yes → wrap in a nested `ChildSpec.supervisor` |
| Does the child crash occasionally and must always recover? | Yes → `RestartWindow.UNLIMITED` |
| Is there a risk of a crash loop exhausting resources? | Yes → bound the window: `new RestartWindow(5, Duration.ofSeconds(60))` |

**ASCII tree — typical three-level layout:**

```
root (OneForOne, UNLIMITED)
├── api-supervisor (OneForOne, 5/60s)
│   ├── rest-handler
│   └── grpc-handler
└── data-supervisor (AllForOne, 3/30s)
    ├── db-writer
    └── db-reader
```

Start with a flat tree and add levels only when you need different failure domains.
Deeply nested trees are harder to reason about; two or three levels is usually enough.
