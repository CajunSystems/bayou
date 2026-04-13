# Structure

## Directory Tree

```
bayou/
├── .github/
│   └── workflows/
│       └── ci.yml                           GitHub Actions CI
├── src/
│   ├── main/java/com/cajunsystems/bayou/
│   │   ├── AbstractActorRunner.java          Base virtual-thread runner (abstract, pkg-private)
│   │   ├── ActorRef.java                     Public communication interface (tell/ask/stop/isAlive)
│   │   ├── ActorRefImpl.java                 Package-private ActorRef implementation
│   │   ├── BayouContext.java                 Runtime context interface (logger/system/reply)
│   │   ├── BayouContextImpl.java             Context impl — holds currentEnvelope, dispatches reply
│   │   ├── BayouSerializer.java              Pluggable serialization interface
│   │   ├── BayouSystem.java                  Entry point — factory, registry, shutdown
│   │   ├── Envelope.java                     Mailbox entry record (payload + optional replyFuture)
│   │   ├── EventSourcedActorRunner.java      Event-sourced runner (replay on init, append on handle)
│   │   ├── JavaSerializer.java               Built-in Java serialization (dev/test only)
│   │   ├── StatefulActorRunner.java          Stateful runner (snapshot restore/write)
│   │   ├── StatelessActorRunner.java         Stateless runner (no I/O)
│   │   └── actor/
│   │       ├── Actor.java                    @FunctionalInterface — stateless actor contract
│   │       ├── EventSourcedActor.java        Event-sourced actor contract (handle→events, apply)
│   │       ├── StatefulActor.java            Stateful actor contract (initialState, reduce)
│   │       └── StatelessActor.java           @Deprecated alias for Actor<M>
│   └── test/java/com/cajunsystems/bayou/
│       ├── BayouTestSupport.java             freshSystem() — in-memory BayouSystem for tests
│       ├── EventSourcedActorTest.java        6 tests: events, replay, query semantics
│       ├── InterActorTest.java               8 tests: lookup, spawn, isAlive, ask timeout
│       ├── StatefulActorTest.java            6 tests: reducer, snapshots, error rollback
│       └── StatelessActorTest.java           6 tests: tell, ask, lifecycle, errors
├── pom.xml                                   Maven build (Java 21, Gumbo, JUnit 5)
├── README.md
└── LICENSE
```

## Package Layout

```
com.cajunsystems.bayou
├── Public user API
│   ├── BayouSystem          — entry point
│   ├── ActorRef<M>          — send messages, stop actor
│   ├── BayouContext         — runtime env inside handlers
│   ├── BayouSerializer<T>   — pluggable serialization contract
│   └── JavaSerializer<T>    — default (dev-only) impl
│
├── Internal runtime (all package-private)
│   ├── AbstractActorRunner<M>
│   ├── StatelessActorRunner<M>
│   ├── StatefulActorRunner<S,M>
│   ├── EventSourcedActorRunner<S,E,M>
│   ├── ActorRefImpl<M>
│   ├── BayouContextImpl
│   └── Envelope<M>
│
└── com.cajunsystems.bayou.actor   — actor interfaces for users to implement
    ├── Actor<M>                   @FunctionalInterface
    ├── StatefulActor<S,M>
    ├── EventSourcedActor<S,E,M>
    └── StatelessActor<M>          @Deprecated
```

## Code Size (approximate)

| File | Lines |
|---|---|
| BayouSystem.java | 187 |
| StatefulActorRunner.java | 94 |
| EventSourcedActorRunner.java | 73 |
| AbstractActorRunner.java | 119 |
| EventSourcedActor.java | 78 |
| StatefulActor.java | 62 |
| BayouContextImpl.java | 52 |
| ActorRef.java | 50 |
| StatelessActorRunner.java | 40 |
| JavaSerializer.java | 35 |
| All others | < 30 each |
