# Summary — Phase 4, Plan 1: Restart Mechanics

## Status: Complete

## What Was Built

Supervisors now act on crash signals rather than only logging them. A supervised actor that crashes is restarted, stopped, or escalated according to the configured strategy. Stateful and event-sourced actors recover their last persisted state on restart automatically — the existing `initialize()` machinery handles it without any changes to the concrete runners.

Two pre-execution API improvements were also made (user-requested before execution began).

---

### Pre-execution API improvements

**`StatefulChildSpec` — builder-style `snapshotInterval(int)`**

`ChildSpec.stateful()` previously had two overloads (3-arg and 4-arg). The 4-arg overload was removed; `StatefulChildSpec` was made public and gained a fluent `snapshotInterval(int n)` method:

```java
ChildSpec.stateful("counter", actor, ser)                  // default
ChildSpec.stateful("counter", actor, ser).snapshotInterval(10)  // override
```

**`SupervisorRef.spawnChild(ChildSpec)` + `SupervisorActor.children()` default**

`SupervisorRef` gained a `spawnChild(ChildSpec)` method for dynamic child management. `SupervisorActor.children()` became a `default` returning `List.of()`, so a dynamic-only supervisor only needs to implement `strategy()`. `SupervisorRunner.childRunners` changed from `ArrayList` to `CopyOnWriteArrayList` for thread safety (supervisor loop + external `spawnChild` callers). A private `startAndRegister(ChildSpec)` helper deduplicates the spawn logic.

---

### Task 1 — `AbstractActorRunner` restart primitive

**Three changes to `AbstractActorRunner.java`:**

1. `stopFuture`: `private final` → `private volatile` — allows `restart()` to replace it.
2. `running.set(false)` added to the crash `catch (Exception e)` block — fixes `isAlive()` returning `true` for a dead actor; the window between crash and restart now correctly reports `false`.
3. `restart()` method added:
   ```java
   void restart() {
       running.set(true);
       stopFuture = new CompletableFuture<>();
       Thread.ofVirtual().name("bayou-" + actorId).start(this::loop);
   }
   ```
   Handles both cases: crashed runner (`running` was just set to `false`) and normally-stopped sibling (`running` set to `false` by `stop()`). Mailbox is preserved — queued messages are delivered after restart.

### Task 2 — `SupervisorRunner.processEnvelope()` decision switch

Replaced the log-only body with a full switch on `RestartDecision`:

- **`RESTART`**: calls `crash.runner().restart()` — one-for-one restart of only the crashed child.
- **`RESTART_ALL`**: stops all alive siblings (crashed runner has `isAlive() == false` now, naturally excluded), waits for all `CompletableFuture`s to complete, then calls `restart()` on every child in `childRunners` (declaration order).
- **`STOP`**: logs that the child is permanently stopped; no restart action.
- **`ESCALATE`**: logs a warning "not yet implemented — treating as permanently stopped"; Phase 5 adds actual escalation.

**Fixed `supervisorReceivesCrashSignalFromChild` test** (in `SupervisorActorTest`): the always-crashing actor was replaced with a crash-once actor (`AtomicBoolean` guard). Without this, Phase 4's restart action caused an infinite crash-restart loop that broke the `decisions.hasSize(1)` assertion.

### Task 3 — `RestartMechanicsTest` (5 tests)

Five integration tests using real in-memory `SharedLog`:

- `statelessOneForOneRestartResumesMessageProcessing`: actor crashes on first `preStart()`, restarts, delivers `["hello", "world"]` correctly.
- `statefulOneForOneRestartRestoresSnapshot`: standalone actor accumulates state=15 and writes snapshots; supervised actor on same log crashes on first `preStart()`, restarts, reads snapshot → state=15 verified via `ask`.
- `allForOneRestartsBothCrashedAndSiblings`: actor A crashes on first start with `AllForOneStrategy`; both A and B reach `preStarts >= 2`, confirming sibling B was stopped and restarted.
- `stopDecisionLeavesCrashedActorPermanentlyDead`: strategy returns `STOP`; actor crashes and `isAlive()` stays `false` after 300ms with no restart.
- `spawnChildAddsChildUnderSupervision`: supervisor started with no initial children; `spawnChild()` adds a dynamic child that processes `["ping", "pong"]` correctly.

---

## Commits

| Hash | Task | Description |
|---|---|---|
| `401462c` | Pre-exec | `refactor: add StatefulChildSpec.snapshotInterval() builder method, drop 4-arg overload` |
| `97e90d7` | Pre-exec | `feat: add SupervisorRef.spawnChild() and default children() for dynamic supervision` |
| `94b2519` | Task 1 | `feat(04-01): add AbstractActorRunner.restart() and fix isAlive() after crash` |
| `2a50d35` | Task 2 | `feat(04-01): implement restart decision switch in SupervisorRunner.processEnvelope` |
| `ace9632` | Task 3 | `test(04-01): verify restart mechanics — stateless, stateful snapshot, all-for-one, stop, spawnChild` |

## Test Results

```
RestartMechanicsTest:   5 tests — 0 failures  (4 planned + 1 for spawnChild)
SupervisorActorTest:    4 tests — 0 failures  (includes fixed crash-signal test)
StrategyModelTest:     11 tests — 0 failures
StatefulActorTest:      6 tests — 0 failures
StatelessActorTest:     6 tests — 0 failures
InterActorTest:         7 tests — 0 failures
EventSourcedActorTest:  6 tests — 0 failures
CrashSignalTest:        2 tests — 0 failures
──────────────────────────────────────────────
Total:                 47 tests — BUILD SUCCESS
```

## Decisions Made

- `running.set(false)` in crash path — required for `isAlive()` correctness and for `RESTART_ALL` sibling filter (crashed runner naturally excluded via `isAlive()`)
- `stopFuture` made `volatile` not `AtomicReference` — simpler, sufficient given single-writer guarantee (only `restart()` replaces it, called from supervisor thread for one-for-one, or sequentially for all-for-one)
- `CopyOnWriteArrayList` for `childRunners` — read-heavy (crash handling iterates), occasional writes (`spawnChild`); snapshot semantics during all-for-one restart is acceptable
- `ESCALATE` is a no-op stub — Phase 5 implements actual tree propagation
- `spawnChild` test added beyond plan scope — correctness gap for new feature warranted coverage

## Deviations

- **Pre-execution API changes** (user-requested): `StatefulChildSpec` builder and `spawnChild`/`children()` default — documented above, no plan changes needed.
- **5th test added** (`spawnChildAddsChildUnderSupervision`): new `spawnChild` feature added pre-execution had no test coverage; added as auto-add per deviation rules.
- Plan's `statefulOneForOneRestartRestoresSnapshot` used `ChildSpec.stateful(..., 1)` (old 4-arg); updated to `.snapshotInterval(1)` per the pre-execution API change.
