# Phase 5, Plan 1: Death Spiral Guard — SUMMARY

## Status

Complete — 2026-04-13

---

## What Was Built

### New Files

- **`EscalationException.java`** — Package-private `RuntimeException` subclass thrown by `escalate()`. Carries the supervisor ID and original crash cause. Caught naturally by the existing `catch (Exception e)` block in `AbstractActorRunner.loop()` — no changes to `loop()` needed.

- **`DeathSpiralTest.java`** — 3 new integration tests verifying: (1) supervisor dies after exceeding restart window, (2) UNLIMITED window never escalates, (3) top-level escalation stops gracefully and leaves system operational.

### Modified Files

- **`OneForOneStrategy.java`** — Added `HashMap<String, ArrayDeque<Instant>> restartHistory` field and spiral-counting logic to `decide()`. Short-circuits to `RESTART` for `UNLIMITED`. Trims expired entries by time window, checks count before adding, returns `ESCALATE` when `size >= maxRestarts`.

- **`AllForOneStrategy.java`** — Identical spiral-counting logic, returns `RESTART_ALL` (not `RESTART`) before limit.

- **`AbstractActorRunner.java`** — Added `escalate(Throwable cause)` method. Logs critical error if `crashListener == null` (top-level), then always throws `EscalationException`.

- **`BayouSystem.java`** — Added `unregisterActor(String actorId)` — one-line `actors.remove(actorId)`.

- **`SupervisorRunner.java`** — Two changes:
  1. ESCALATE case in `processEnvelope()` now calls `escalate(crash.cause())` instead of logging a stub warning.
  2. `cleanup()` now unregisters each child from `BayouSystem` and clears `childRunners` after stopping them — enables clean restart by a parent supervisor.

- **`SupervisorActorTest.java`** — Auto-fix for regression: `stoppingSupervisorStopsAllChildren` updated to assert `isEmpty()` on lookup after supervisor stops, since `cleanup()` now deregisters children.

---

## Commit Hashes

| Task | Commit | Message |
|---|---|---|
| Task 1 | `bd27b24` | feat(05-01): add death-spiral counting to OneForOneStrategy and AllForOneStrategy |
| Task 2 | `b144618` | feat(05-01): implement ESCALATE — escalate() on AbstractActorRunner, unregisterActor on BayouSystem, cleanup deregisters children |
| Task 3 | `cd5bd32` | test(05-01): verify death-spiral escalation — window exceeded, unlimited, top-level graceful stop |

---

## Test Results

```
Tests run: 50, Failures: 0, Errors: 0, Skipped: 0
```

- `DeathSpiralTest` — 3 tests (all new)
- `StrategyModelTest` — 11 tests (no regression)
- `RestartMechanicsTest` — 5 tests (no regression)
- `SupervisorActorTest` — 4 tests (auto-fixed assertion)

---

## Decisions Made

- **`EscalationException extends RuntimeException`** — thrown from `escalate()`, caught by existing `catch (Exception e)` in `loop()`, sets `terminalCause`, calls `cleanup()`, fires crash signal to parent if any. No changes to `loop()` needed.

- **`escalate()` on `AbstractActorRunner`** — checks `crashListener == null` to detect top-level (logs critical); always throws `EscalationException` so the supervisor's virtual thread crashes like a normal actor crash, triggering the full shutdown sequence.

- **`cleanup()` unregisters children from `BayouSystem.actors` + clears `childRunners`** — enables a parent supervisor to restart this supervisor cleanly (re-running `initialize()` → `registerActor()` without duplicate-ID collision). `ConcurrentHashMap.remove` is idempotent; `shutdown()` calls `actors.clear()` anyway.

- **`BayouSystem.unregisterActor()`** — `actors.remove(actorId)`; safe from cleanup() thread (called on supervisor's virtual thread).

- **Strategy `restartHistory` is `HashMap`** (not `ConcurrentHashMap`) — `decide()` is called only from the supervisor's own virtual thread.

- **`RestartWindow.UNLIMITED` check uses reference equality (`==`)** — `UNLIMITED` is a `public static final` constant; reference equality is correct and sufficient.

- **`SupervisorActorTest` auto-fix** — after `cleanup()`, children are deregistered from `BayouSystem`. Test assertions for stopped supervisor's children changed from `contains(false)` to `isEmpty()`. `DeathSpiralTest` written with the same corrected pattern.
