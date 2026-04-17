# Phase 15, Plan 1 — TestKit: Summary

## What Was Implemented

### Task 1 — ProbeActor + TestProbe + TestKit (commit `2e44496`)

Three new production-side classes added to `com.cajunsystems.bayou`:

- **`ProbeActor<M>`** (package-private) — implements `Actor<M>`; enqueues messages into `LinkedBlockingQueue<M>` and signals into `LinkedBlockingQueue<Signal>` via `handle()` / `onSignal()`.
- **`TestProbe<M>`** (public) — wraps a `Ref<M>` backed by `ProbeActor`; exposes:
  - `ref()` — returns the probe's `Ref<M>` for wiring into the system
  - `expectMessage(M expected, Duration)` — equality-based assertion with timeout
  - `expectMessage(Class<T>, Duration)` — type-based assertion with timeout
  - `expectNoMessage(Duration)` — asserts silence within duration
  - `expectTerminated(Ref<?>, Duration)` — calls `system.watch` then polls the signals queue
- **`TestKit`** (public final utility class) — single static factory: `probe(BayouSystem, String)`

### Task 2 — TestKitTest (commit `c2a9e48`)

Seven deterministic tests in `TestKitTest`:
1. `probeReceivesDirectTell` — direct tell to probe
2. `probeReceivesActorReply` — echo actor forwards to probe
3. `expectMessageByType` — type-cast assertion
4. `expectNoMessagePassesWhenMailboxEmpty` — silence assertion passes
5. `expectNoMessageFailsWhenMessageArrives` — silence assertion throws on unexpected message
6. `expectTerminatedDetectsActorStop` — watch + stop lifecycle
7. `probeReceivesPubSubMessage` — pubsub subscription via probe

All 7 tests pass; full suite: 104 tests, 0 failures.

## Deviations from Plan

### `expectTerminated` race condition handling

The plan anticipated a potential race between `dying.stop()` and `expectTerminated` calling `system.watch` internally. The deviation: rather than making the test call `system.watch` explicitly before `stop()`, the `expectTerminated` implementation was made race-resilient:

1. **Pre-check**: If a `Terminated` signal is already queued (from a prior external watch), consume it and return immediately.
2. **`isAlive()` check after `watch()`**: After registering the watch, if the actor is already dead (not alive), treat as terminated immediately — the actor stopped between the watch registration and the `isAlive` check, so the Terminated signal either already fired or the actor is unambiguously gone.
3. **`IllegalArgumentException` catch**: If the actor is already fully unregistered from the `runners` map when `system.watch` is called, catch the exception and treat as terminated.

This design means the test can simply call `dying.stop()` then `probe.expectTerminated(dying, ...)` without any explicit ordering workaround. The `expectTerminated` method is robust to all timing orderings.

Root cause: `AbstractActorRunner` for stateless actors does NOT call `unregisterActor` from `cleanup()` — only `SupervisorRunner` does that for its children. The `Terminated` signal fires from the actor's own virtual thread in `finally`, after `cleanup()` and `stopFuture.complete()`. If `system.watch` is registered after those steps but the actor is still in the `runners` map, the listener is added to a now-cleared `signalListeners` list and will never fire.

## Commit Hashes

| Task | Commit |
|------|--------|
| Task 1 — ProbeActor + TestProbe + TestKit | `2e44496` |
| Task 2 — TestKitTest | `c2a9e48` |
