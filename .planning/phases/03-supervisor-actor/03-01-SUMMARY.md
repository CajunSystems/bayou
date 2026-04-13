# Summary — Phase 3, Plan 1: Supervisor Actor

## Status: Complete

## What Was Built

Nine new files (8 source + 1 test) wiring together the first concrete supervisor type. A user can now call `system.spawnSupervisor(id, supervisorActor)`, get a `SupervisorRef` back, and have a running supervisor that observes child crashes and dispatches to the configured strategy.

### `SupervisorRef.java` (new)
Public interface extending `ActorRef<Void>`. Signals to callers that supervisors do not accept user messages. `tell(Void)` is a no-op; `ask(Void)` returns a pre-failed future. Extends `ActorRef<Void>` so supervisors can be stored in `BayouSystem.actors` alongside regular actors.

### `SupervisorActor.java` (new)
Public interface with two methods: `children()` (declares the child group) and `strategy()` (selects the supervision strategy). Lambda-unfriendly by design — both methods are needed, so callers use anonymous classes or a named implementation.

### `ChildSpec.java` (new — sealed interface)
Public sealed interface with `actorId()` + four static factory methods: `stateless`, `stateful` (default interval), `stateful` (custom interval), `eventSourced`. Implementations are package-private records; callers never see the concrete types.

### `StatelessChildSpec.java`, `StatefulChildSpec.java`, `EventSourcedChildSpec.java` (new — package-private records)
Three records implementing `ChildSpec`. Carry the typed actor reference, serializer, and configuration needed by `SupervisorRunner.createChildRunner()` to construct the appropriate runner.

### `SupervisorRunner.java` (new)
Package-private. Extends `AbstractActorRunner<ChildCrash>` — the supervisor's mailbox is typed to crash signals. `initialize()` iterates `childSpecs`, constructs a runner per spec via instanceof pattern matching, sets each child's crash listener to `crash -> this.tell(crash)`, starts the child, and registers it in `BayouSystem.actors`. `processEnvelope()` calls `strategy.decide()` and logs the decision — no restart action yet (Phase 4). `cleanup()` stops all child runners in parallel via `CompletableFuture.allOf().join()`. The unchecked casts in `createChildRunner()` are safe because type parameters are consistent within each spec object at construction time.

### `SupervisorActorRefImpl.java` (new)
Package-private. Implements `SupervisorRef`. Delegates `actorId()`, `isAlive()`, and `stop()` to the backing `SupervisorRunner`. `tell(Void)` is a no-op; `ask(Void)` returns a pre-failed future with an `UnsupportedOperationException`.

### `BayouSystem.java` (modified)
Added `registerActor(String, ActorRef<?>)` package-private helper (used by `SupervisorRunner.initialize()` to register children). Added `spawnSupervisor(String, SupervisorActor)` public factory method: validates no duplicate IDs for the supervisor or any child, creates and starts the `SupervisorRunner`, and stores the `SupervisorRef` in `actors`. Added `import java.util.List`.

### `SupervisorActorTest.java` (new, 4 tests)
Integration tests using a real in-memory `SharedLog`:
- `supervisorStartsAllDeclaredChildren` — verifies children are alive and discoverable via `lookup()`
- `childrenReceiveMessagesNormally` — verifies children process messages correctly under supervision
- `stoppingSupervisorStopsAllChildren` — verifies `ref.stop()` propagates to all children
- `supervisorReceivesCrashSignalFromChild` — verifies crash from `preStart()` reaches strategy `decide()`

## Commits

| Hash | Task | Description |
|---|---|---|
| `6858999` | Task 1 | `feat(03-01): add SupervisorRef, SupervisorActor, and ChildSpec public API shell` |
| `448cab7` | Task 2 | `feat(03-01): add SupervisorRunner and SupervisorActorRefImpl` |
| `488499b` | Task 3 | `feat(03-01): wire spawnSupervisor and registerActor into BayouSystem` |
| `5cd36ac` | Task 4 | `test(03-01): verify supervisor lifecycle, child messaging, and crash signal routing` |

## Test Results

```
SupervisorActorTest:    4 tests — 0 failures
StrategyModelTest:     11 tests — 0 failures
CrashSignalTest:        2 tests — 0 failures
EventSourcedActorTest:  6 tests — 0 failures
StatelessActorTest:     6 tests — 0 failures
InterActorTest:         7 tests — 0 failures
StatefulActorTest:      6 tests — 0 failures
────────────────────────────────────────────
Total:                 42 tests — BUILD SUCCESS
```

## Decisions Made

- `context.system()` used in `SupervisorRunner` to access `BayouSystem` — avoids needing a separate field since `BayouContextImpl` already holds it
- Type witness (`system.<String>lookup(...)`) used in test to resolve type inference ambiguity — cleaner than an unchecked cast at the call site
- Tasks 2 and 3 committed separately even though Task 3 was needed to compile Task 2 — both were minimal enough to warrant their own commits

## Deviations

None — executed exactly as planned. The type witness fix in the test was a minor compilation adjustment, not a design deviation.
