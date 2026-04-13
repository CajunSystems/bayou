# Architecture

## Overview

Bayou is a Java 21 actor system library layered on top of **Gumbo** — an external append-only shared log. It implements the actor model with three distinct flavors covering a durability spectrum:

| Actor Type | Persistence | Use Case |
|---|---|---|
| **Stateless** | None — pure in-memory | Logging, routing, I/O sinks |
| **EventSourced** | Full event log (complete audit trail) | Bank accounts, order processing |
| **Stateful** | Periodic state snapshots | Word counters, caches, large state |

## Core Components

```
┌─────────────────────────────────────────────────┐
│                  User Code                      │
│          ActorRef<M>.tell() / ask()             │
└───────────────────┬─────────────────────────────┘
                    │
┌───────────────────▼─────────────────────────────┐
│              BayouSystem                         │
│  spawn() / spawnStateful() / spawnEventSourced() │
│  lookup() / shutdown()                           │
│  ConcurrentHashMap<String, ActorRef<?>> actors   │
└───────────────────┬─────────────────────────────┘
                    │ creates
     ┌──────────────▼──────────────┐
     │     AbstractActorRunner<M>   │  (package-private)
     │  • LinkedBlockingQueue mailbox│
     │  • AtomicBoolean running      │
     │  • Virtual thread lifecycle   │
     └──┬───────────┬───────────┬──┘
        │           │           │
  ┌─────▼──┐  ┌────▼────┐  ┌──▼──────────────┐
  │Stateless│  │Stateful │  │EventSourced     │
  │ Runner  │  │ Runner  │  │ Runner          │
  └────┬────┘  └────┬────┘  └───────┬─────────┘
       │            │               │
  no log      bayou.snapshots  bayou.events
              :<actorId>        :<actorId>
                    │               │
              ┌─────▼───────────────▼──────┐
              │       Gumbo SharedLog       │
              │  FileBasedPersistence (prod) │
              │  InMemoryPersistence (test)  │
              └────────────────────────────┘
```

## Actor Lifecycle — Template Method Pattern

All runners extend `AbstractActorRunner<M>` via the Template Method pattern:

```
start()
  └─► Virtual thread: loop()
        ├─ initialize()       ← subclass hook (snapshot restore / event replay)
        ├─ preStart(ctx)       ← user hook
        │
        ├─ LOOP: poll mailbox (100ms timeout)
        │    └─ processEnvelope(env)  ← subclass hook (handle, reduce, or handle+apply)
        │         ├─ setCurrentEnvelope(env)
        │         ├─ run user handler
        │         ├─ clearCurrentEnvelope()
        │         └─ if ask && !replied → completeExceptionally
        │
        ├─ postStop(ctx)       ← user hook
        └─ cleanup()          ← subclass hook (final snapshot / event flush)
             └─ stopFuture.complete()
```

**Graceful draining:** loop continues while `running.get() || !mailbox.isEmpty()` — pending messages are processed before shutdown.

## Message Flow (tell/ask)

```
actor.tell(msg)
  → ActorRefImpl → AbstractActorRunner.tell()
  → Envelope.tell(msg) added to LinkedBlockingQueue
  → loop polls → processEnvelope()
  → handler runs on actor's virtual thread

actor.ask(msg)
  → creates CompletableFuture in Envelope
  → handler calls ctx.reply(value)
  → future.complete(value) → caller gets result
  → if handler returns without reply → future.completeExceptionally(IllegalStateException)
```

## Actor Runner Specializations

### StatelessActorRunner
- No I/O
- `initialize()`: calls `actor.preStart()`
- `processEnvelope()`: `actor.handle()` with error callback
- `cleanup()`: calls `actor.postStop()`

### StatefulActorRunner
- State: `S state` in memory
- `initialize()`: reads latest snapshot from `bayou.snapshots:<id>` log view, deserializes via `BayouSerializer<S>`
- `processEnvelope()`: `state = actor.reduce(state, msg, ctx)` — rolls back state on exception
- Snapshot written every `snapshotInterval` messages (default configurable) and unconditionally on stop
- `cleanup()`: final snapshot + `actor.postStop()`

### EventSourcedActorRunner
- State: fold of all events
- `initialize()`: reads all entries from `bayou.events:<id>`, deserializes each as `E`, folds via `actor.apply(state, event)`. Calls `actor.preStart()` after replay.
- `processEnvelope()`: `List<E> events = actor.handle(state, msg, ctx)` → for each event: serialize + append to log, then `state = actor.apply(state, event)`
- `cleanup()`: calls `actor.postStop()`

## Gumbo Integration

Bayou wraps `SharedLog` from the Gumbo library:

```java
LogView view = sharedLog.getView(LogTag.of(namespace, key));
List<LogEntry> entries = view.readAll().join();   // read all
view.append(bytes).join();                        // append
```

Log tag namespaces:
- `bayou.snapshots` — stateful actor snapshots
- `bayou.events` — event-sourced actor events

## Threading Model

- One **virtual thread** per actor (`Thread.ofVirtual().name("bayou-" + actorId).start(this::loop)`)
- **Sequential processing** within each actor — no per-actor concurrency
- **No shared mutable state** between actors — communication via messages only
- Gumbo I/O is async; runners block on `.join()` within virtual threads

## Visibility / Public API Boundary

```
Public (library users):           Package-private (internals):
  BayouSystem                       AbstractActorRunner<M>
  ActorRef<M>                       StatelessActorRunner<M>
  BayouContext                      StatefulActorRunner<S,M>
  BayouSerializer<T>                EventSourcedActorRunner<S,E,M>
  JavaSerializer<T>                 ActorRefImpl<M>
  Actor<M>                          BayouContextImpl
  StatefulActor<S,M>                Envelope<M>
  EventSourcedActor<S,E,M>
  StatelessActor<M>  ← @Deprecated(forRemoval=true)
```
