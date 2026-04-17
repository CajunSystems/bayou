# Phase 7, Plan 1: Timer Messages — SUMMARY

## Status: Complete

## Commits

- `f0728de` — feat(07-01): timer infrastructure — TimerRef, ScheduledExecutorService, BayouContext<M> generic
- `ddb9b26` — test(07-01): TimerTest — scheduleOnce, cancel, periodic, stop cleanup, independent cancellation

## What Was Built

**New files:**
- `TimerRef.java` — public interface: `cancel()`, `isCancelled()`
- `TimerRefImpl.java` — package-private: wraps `ScheduledFuture<?>`, atomic cancellation via `AtomicBoolean`
- `TimerTest.java` — 5 test cases covering all timer behaviors

**Modified files:**
- `BayouContext.java` — genericized to `BayouContext<M>`; added `scheduleOnce(Duration, M)` and `schedulePeriodic(Duration, M)`
- `BayouContextImpl.java` — genericized to `BayouContextImpl<M>`; added `AbstractActorRunner<M> runner` field + `setRunner()`; implemented timer methods using `runner.tell()` for direct mailbox delivery
- `AbstractActorRunner.java` — typed `context` field to `BayouContextImpl<M>`; added `Set<TimerRefImpl> activeTimers`; wires `context.setRunner(this)` in constructor; cancels all active timers in `finally` block before `cleanup()`
- `BayouSystem.java` — added `ScheduledExecutorService` (single-threaded daemon platform thread); package-private `scheduledExecutor()` accessor; `shutdownNow()` called before actor stops in `shutdown()`
- `actor/Actor.java`, `actor/StatefulActor.java`, `actor/EventSourcedActor.java` — updated `BayouContext` → `BayouContext<M>` in all method signatures

## Key Design Decisions

- `BayouContext<M>` made generic — enables type-safe `scheduleOnce(Duration, M)` / `schedulePeriodic(Duration, M)` without casts; lambda actors unaffected by type inference
- Timer delivery via `runner.tell(message)` directly — bypasses the `Ref` layer, no self-reference needed
- `setRunner(this)` called in `AbstractActorRunner` constructor (after context is created) — avoids constructor-escape anti-pattern
- One-shot timers self-remove from `activeTimers` after firing — prevents unbounded set growth
- Timer cancellation in `finally` block before `cleanup()` — ensures timers are always cancelled even if cleanup throws
- Scheduler uses platform daemon thread (not virtual) — `ScheduledExecutorService` is more reliable with platform threads for timing guarantees

## Deviations from Plan

- Tasks 1 and 2 merged into one commit — the plan said "stubs in Task 1, implement in Task 2" but the plan also said "implement them fully." Both are in commit `f0728de`. All functionality is present.
- Two test files (`RestartMechanicsTest.java`, `SupervisorActorTest.java`) had explicit raw-type `BayouContext ctx` lambda parameters that required updating to `BayouContext<String> ctx`. Other anonymous inner class usages compiled fine via type inference.

## Test Results

59/59 tests passing (54 pre-existing + 5 new TimerTest cases):
- `scheduleOnce_firesAfterDelay` ✓
- `scheduleOnce_cancelPreventsDelivery` ✓
- `schedulePeriodic_firesRepeatedly` ✓
- `actorStop_cancelsActiveTimers` ✓
- `multipleTimers_independentCancellation` ✓
