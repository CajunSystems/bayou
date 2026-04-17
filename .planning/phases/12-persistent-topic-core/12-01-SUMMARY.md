# Phase 12, Plan 1 — Persistent Topic Core: Summary

## What Was Implemented

### New production files
- `TopicCommand<M>` (`src/main/java/com/cajunsystems/bayou/TopicCommand.java`) — package-private sealed interface with three records: `Publish<M>`, `Subscribe<M>`, `Unsubscribe<M>`.
- `TopicActor<M>` (`src/main/java/com/cajunsystems/bayou/TopicActor.java`) — package-private `Actor<TopicCommand<M>>` implementation. Initializes a `LogView` at `LogTag.of("bayou.topic", name)` in `preStart`. On `Publish`: serializes payload and appends to the log view (exceptions are logged but not propagated), then delivers to `liveSubscribers` (CopyOnWriteArrayList) with lazy dead-ref cleanup via `removeIf`. On `Subscribe`/`Unsubscribe`: mutates `liveSubscribers`.
- `BayouTopic<M>` (`src/main/java/com/cajunsystems/bayou/BayouTopic.java`) — public facade wrapping a `Ref<TopicCommand<M>>`. Delegates `publish`/`subscribe`/`unsubscribe` as fire-and-forget tells.
- `BayouSystem` modified — added `ConcurrentHashMap<String, BayouTopic<?>> topics` field and `topic(String name, BayouSerializer<M> serializer)` factory using `computeIfAbsent` for idempotency. Topic actor registered with ID `"bayou-topic-" + name` so the existing `shutdown()` loop stops it automatically.

### New test file
- `PersistentTopicTest` (`src/test/java/com/cajunsystems/bayou/PersistentTopicTest.java`) — 6 test cases:
  1. `publishDeliversToLiveSubscribers` — 2 subscribers × 3 messages = 6 deliveries verified with Awaitility.
  2. `unsubscribeStopsDelivery` — subscribes, delivers first message, unsubscribes, publishes second, asserts only first received.
  3. `topicIsIdempotent` — same `BayouTopic` instance returned for the same name via `assertThat(...).isSameAs(...)`.
  4. `publishPersistsToLog` — publishes 10 messages, stops the topic actor to drain its mailbox, creates a second `BayouSystem` on the same `SharedLog`, reads the log view, asserts 10 entries.
  5. `topicIsolation` — subscriber on `"topic-a"` receives nothing from `"topic-b"` publishes.
  6. `deadSubscriberIsCleanedUp` — stops subscriber, publishes, asserts no exception and system is still alive.

## Key Decisions During Implementation

### Deviation from plan: restart test uses actor.stop() not system.shutdown()
The plan said to "shutdown system1 and create system2 with the same SharedLogService." However `BayouSystem.shutdown()` calls `sharedLog.close()`, which shuts down the `SharedLogService`'s internal executor — making subsequent `readAll()` calls on system2 fail with `RejectedExecutionException`. The fix was to follow the same pattern used in `EventSourcedActorTest.stateIsReplayedOnRestart()`: stop only the topic actor explicitly (`system.lookup("bayou-topic-events").orElseThrow().stop().get(5, SECONDS)`) to drain its mailbox without closing the shared log. The `@AfterEach tearDown` handles system1 cleanup.

### Unused imports removed
`InMemoryPersistenceAdapter`, `SharedLogConfig`, `SharedLogService`, and `AtomicInteger` imports were removed from the test file since they weren't needed after the restart strategy was adjusted.

### No other deviations from plan
All design decisions (sealed interface, CopyOnWriteArrayList, lazy removeIf, logView in preStart, computeIfAbsent idempotency, actor ID `"bayou-topic-" + name`) were implemented as specified.

## Commit Hashes

| Task | Commit |
|------|--------|
| Task 1 — TopicCommand + TopicActor + BayouTopic + BayouSystem.topic() | `3621eb3` |
| Task 2 — PersistentTopicTest (6 cases) | `4022e53` |

## Issues Encountered

1. **RejectedExecutionException in restart test** (first run): `system1.shutdown()` closed the SharedLogService's internal thread-pool executor. Fix: stop only the topic actor to drain its mailbox; leave the log alive for system2. Resolved on second attempt.

## Test Results

- `mvn test -Dtest=PersistentTopicTest`: 6/6 pass
- `mvn test` (full suite): 88/88 pass, 0 failures, 0 errors
