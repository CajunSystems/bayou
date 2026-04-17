# Bayou

An actor system built on top of [gumbo](https://github.com/CajunSystems/gumbo) — a shared
append-only log. Bayou ships actor flavours that cover the spectrum from pure in-memory
processing to fully event-sourced state, plus Erlang/Elixir-style primitives: supervision
trees, timers, death watch, linking, back-pressure, and PubSub.

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
    <version>0.2.0</version>
</dependency>
```

Requires **Java 21**.

## Setup

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

## Hello, Actor!

```java
Ref<String> greeter = system.spawn("greeter",
        (msg, ctx) -> ctx.logger().info("Hello, {}!", msg));

greeter.tell("World");  // fire-and-forget
greeter.tell("Bayou");
```

## Supervision

```java
SupervisorRef supervisor = system.spawnSupervisor("root", new AppRoot(),
        new OneForOneStrategy(new RestartWindow(5, Duration.ofSeconds(60))));
```

Declare children inside your `SupervisorActor`; the framework restarts crashed children and escalates when the restart window is exceeded. Use `AllForOneStrategy` to restart all siblings together. See the [supervision guide](docs/supervision.md) for decision trees and nested supervisor examples.

## Actor Flavours

| Flavour | Use case | gumbo usage |
|---|---|---|
| `Actor` (stateless) | Pure computation, I/O, routing | Never |
| `EventSourcedActor` | Audit logs, financial, full history | Full replay on startup |
| `StatefulActor` | Counters, caches, aggregates | Latest snapshot on startup |
| `StateMachineActor` | Connection lifecycle, workflows, FSMs | Never |

## Primitives

| Primitive | Description |
|---|---|
| **Timers** | `ctx.scheduleOnce(delay, msg)` / `ctx.schedulePeriodic(interval, msg)` → `TimerRef.cancel()` |
| **Death Watch** | `ctx.watch(ref)` — receive `Terminated(id)` when the target stops or crashes |
| **Actor Linking** | `ctx.link(ref)` — bidirectional exit propagation; `ctx.trapExits(true)` converts exit signals to `LinkedActorDied` messages instead of crashing |
| **Back-pressure** | `MailboxConfig.bounded(n, OverflowStrategy)` — `DROP_NEWEST`, `DROP_OLDEST`, or `REJECT` (throws `MailboxFullException`) |
| **PubSub** | `system.pubsub()` for in-memory fan-out; `system.topic(name, serializer)` for durable log-backed topics |

## Documentation

| Guide | What it covers |
|---|---|
| [Getting Started](docs/getting-started.md) | End-to-end: supervised event-sourced actor + persistent topic + TestKit |
| [Actor Flavours](docs/actor-flavours.md) | When to use Actor, StatefulActor, EventSourcedActor, StateMachineActor |
| [Timers](docs/timers.md) | scheduleOnce, schedulePeriodic, cancellation, sliding timeouts |
| [Death Watch](docs/death-watch.md) | Observing actor termination with ctx.watch and Terminated signals |
| [Actor Linking](docs/actor-linking.md) | Bidirectional exit propagation, trapExits, LinkedActorDied |
| [Back-pressure](docs/back-pressure.md) | Bounded mailboxes, overflow strategies, OverflowListener |
| [Testing](docs/testing.md) | TestKit, TestProbe assertions, Awaitility patterns |
| [Persistent PubSub](docs/persistent-pubsub.md) | BayouTopic vs BayouPubSub, durable subscriptions, replay |
| [Supervision](docs/supervision.md) | Supervision trees, strategies, restart windows, escalation |
| [Patterns](docs/patterns.md) | Request-reply, fan-out, work queues, circuit breakers, pipelines |

## How gumbo is used

| Flavour | Gumbo tag | Write path | Read path |
|---|---|---|---|
| `Actor` / `StateMachineActor` | — | never | never |
| `EventSourcedActor` | `bayou.events:<id>` | one append per emitted event | full replay on startup |
| `StatefulActor` | `bayou.snapshots:<id>` | one append every N messages + on stop | latest entry on startup |
