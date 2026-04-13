# Summary — Phase 1, Plan 1: Crash Signal Infrastructure

## Status: Complete

## What Was Built

Added the notification plumbing that lets a future supervisor know when a child actor dies abnormally, rather than having the failure disappear into a log line.

### `ChildCrash.java` (new)
Package-private record carrying `actorId`, `cause` (the `Throwable`), and `runner` (the `AbstractActorRunner<?>` reference). The `runner` reference is included now so Phase 4 (restart mechanics) can call `initialize()` + start a new virtual thread on the same runner without the supervisor needing to re-spawn.

### `AbstractActorRunner.java` (modified)
Three changes:
1. **`crashListener` field** — `volatile Consumer<ChildCrash>`, nullable. `volatile` ensures the supervisor's write is visible to the actor's virtual thread even though they run concurrently.
2. **`setCrashListener()` setter** — package-private, called by `SupervisorRunner` (Phase 3) after constructing a child runner and before calling `start()`.
3. **`loop()` modification** — `Throwable terminalCause` local captures any exception escaping the message loop. In `finally`, after `cleanup()` completes and `stopFuture` is resolved, the signal fires if `terminalCause != null` and a listener is registered. Order matters: cleanup first, then signal — the child is fully stopped before the supervisor reacts.

### `CrashSignalTest.java` (new, 2 tests)
- `crashListenerReceivesSignalOnAbnormalExit` — creates a runner whose `initialize()` throws; verifies the `ChildCrash` arrives with correct `actorId`, `cause`, and `runner` reference.
- `noSignalOnGracefulStop` — creates a normal runner, calls `stop()`, verifies no crash signal is emitted.

## Commits

| Hash | Task | Description |
|---|---|---|
| `ac78733` | Task 1 | `feat(01-01): add ChildCrash signal record` |
| `99f6284` | Task 2 | `feat(01-01): add crashListener field and signal dispatch to AbstractActorRunner` |
| `b58d655` | Task 3 | `test(01-01): verify crash signal fires on abnormal exit, not on graceful stop` |

## Test Results

```
CrashSignalTest:       2 tests — 0 failures
EventSourcedActorTest: 6 tests — 0 failures
StatelessActorTest:    6 tests — 0 failures
InterActorTest:        7 tests — 0 failures
StatefulActorTest:     6 tests — 0 failures
─────────────────────────────────────────
Total:                27 tests — BUILD SUCCESS
```

## Decisions Made

- **`Consumer<ChildCrash>` not a custom interface** — avoids an extra type; `SupervisorRunner` will pass a method reference in Phase 3.
- **Signal after `stopFuture.complete()`** — supervisor cannot restart a child until it is fully stopped; ordering guarantees this.
- **`InterruptedException` is not a crash** — controlled shutdown path; no signal by design.
- **`processEnvelope()` still swallows handler exceptions** — "let it crash" from a handler requires revisiting exception propagation in Phase 3/4; noted in plan, deferred.

## Deviations

None — executed exactly as planned.
