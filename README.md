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
    <version>0.1.0</version>
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
| **Timers** | `ctx.scheduleOnce` / `ctx.schedulePeriodic` — send a message to self after a delay or on a fixed interval |
| **Death Watch & Linking** | `ctx.watch(ref)` to observe termination; `ctx.link(ref)` for bidirectional fate sharing |
| **Back-pressure** | `MailboxConfig.bounded(n, OverflowStrategy)` — drop newest, drop oldest, or reject on full |
| **PubSub** | `system.pubsub()` for in-memory fan-out; `system.topic(name, serializer)` for durable log-backed topics |

## Documentation

| Guide | What it covers |
|---|---|
| [Getting Started](docs/getting-started.md) | End-to-end: supervised event-sourced actor + persistent topic + TestKit |
| [Actor Flavours](docs/actor-flavours.md) | When to use Actor, StatefulActor, EventSourcedActor, StateMachineActor |
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
