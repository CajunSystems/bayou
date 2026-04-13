# Testing

## Framework & Libraries

| Library | Version | Role |
|---|---|---|
| JUnit Jupiter | 5.10.2 | Test runner, `@Test`, `@BeforeEach`, `@AfterEach` |
| AssertJ | 3.25.3 | Fluent assertions (`assertThat(...).containsExactly(...)`) |
| Awaitility | 4.2.1 | Async polling (`await().atMost(5, SECONDS).untilAsserted(...)`) |

## Test Structure

```
src/test/java/com/cajunsystems/bayou/
├── BayouTestSupport.java      Shared utility: freshSystem() → in-memory BayouSystem
├── StatelessActorTest.java    6 tests
├── StatefulActorTest.java     6 tests
├── EventSourcedActorTest.java 6 tests
└── InterActorTest.java        8 tests
                               26 total
```

## Setup / Teardown Pattern

Every test class follows the same pattern:

```java
private BayouSystem system;

@BeforeEach void setUp() throws Exception {
    system = BayouTestSupport.freshSystem();   // InMemoryPersistenceAdapter
}

@AfterEach void tearDown() {
    system.shutdown();
}
```

## Coverage by Test Class

### StatelessActorTest (6 tests)
| Test | What it verifies |
|---|---|
| `tellDeliverMessagesInOrder` | FIFO ordering |
| `askReturnsReply` | request-reply pattern |
| `lambdaImplementsActor` | @FunctionalInterface usability |
| `preStartAndPostStopAreCalled` | lifecycle hooks |
| `errorInHandlerCallsOnError` | error callback invocation |
| `duplicateActorIdThrows` | registry uniqueness check |

### StatefulActorTest (6 tests)
| Test | What it verifies |
|---|---|
| `reducerAccumulatesState` | reducer pure function, state accumulation |
| `snapshotRestoredOnRestart` | persistence + recovery across system restart |
| `snapshotIntervalTriggersAutomatically` | auto-snapshot cadence |
| `errorInReducerLeavesStatUnchanged` | atomicity / state rollback on error |
| `snapshotIntervalZeroThrows` | constructor validation |
| `preStartAndPostStopCalled` | lifecycle hooks |

### EventSourcedActorTest (6 tests)
| Test | What it verifies |
|---|---|
| `stateAccumulatesAcrossMessages` | event folding |
| `resetEventClearsState` | event semantics (reset event) |
| `stateIsReplayedOnRestart` | full event log replay on recovery |
| `readOnlyQueryProducesNoEvents` | read-only handle() returns empty list |
| `preStartCalledAfterReplay` | lifecycle ordering (replay → preStart) |
| `postStopCalledAfterStop` | post-stop hook |

### InterActorTest (8 tests)
| Test | What it verifies |
|---|---|
| `actorCanSpawnChildViaContext` | spawning from inside a handler via `ctx.system()` |
| `lookupReturnsRefForSpawnedActor` | registry lookup |
| `lookupReturnsEmptyForUnknownActor` | absent actor |
| `isAliveReturnsTrueWhileRunning` | liveness |
| `isAliveReturnsFalseAfterStop` | post-stop liveness |
| `askWithTimeoutCompletesNormally` | `ask(msg, duration)` happy path |
| `askWithTimeoutExpiresWhenNoReply` | timeout expiration |
| *(8th test)* | TBD / additional inter-actor scenario |

## Key Test Patterns

### Async message capture
```java
var received = new CopyOnWriteArrayList<String>();
ActorRef<String> actor = system.spawn("echo", (msg, ctx) -> received.add(msg));
actor.tell("a"); actor.tell("b");
await().atMost(5, SECONDS).untilAsserted(
    () -> assertThat(received).containsExactly("a", "b"));
```

### Ask-pattern with timeout
```java
int count = actor.<Integer>ask(new Get("word")).get(5, SECONDS);
assertThat(count).isEqualTo(2);
```

### Restart persistence test
```java
var sharedLog = system.sharedLog();
// send messages to system 1, stop it
BayouSystem system2 = new BayouSystem(sharedLog);
// spawn same actor id, verify recovered state
```

### Atomic error counting
```java
var errorCount = new AtomicInteger();
system.spawn("faulty", new Actor<>() {
    public void onError(String m, Throwable e, BayouContext c) {
        errorCount.incrementAndGet();
    }
});
await().atMost(5, SECONDS).until(() -> errorCount.get() == 1);
```

## Coverage Gaps

- No stress/load tests (high message volume, many concurrent actors)
- No tests for snapshot deserialization failures
- No tests for event replay failures
- No tests for calling `stop()` multiple times
- No tests for operations during shutdown
- No tests for custom serializer implementations
- No tests for actor ID validation edge cases (empty string, special chars)
- No tests for exceptions in `preStart()` / `postStop()`
- No performance benchmarks
