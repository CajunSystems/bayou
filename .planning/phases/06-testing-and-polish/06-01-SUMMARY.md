# Phase 6, Plan 1: Nested Supervisor Support — SUMMARY

## Status: Complete

## What Was Built

### New Files
- `src/main/java/com/cajunsystems/bayou/SupervisorChildSpec.java` — 4th `ChildSpec` variant; a package-private sealed record holding `actorId` + `SupervisorActor` reference
- `src/test/java/com/cajunsystems/bayou/NestedSupervisionTest.java` — 2 integration tests verifying nested supervision wiring and escalation propagation

### Modified Files
- `src/main/java/com/cajunsystems/bayou/ChildSpec.java` — added `SupervisorChildSpec` to the `permits` clause; added `ChildSpec.supervisor(id, supervisorActor)` factory method with Javadoc
- `src/main/java/com/cajunsystems/bayou/SupervisorRunner.java`:
  - `createChildRunner()`: added `SupervisorChildSpec` branch that constructs a nested `SupervisorRunner`
  - `startAndRegister()`: replaced `runner.toActorRef()` with `instanceof SupervisorRunner sr ? sr.toSupervisorRef() : runner.toActorRef()` so `system.lookup("child-sup")` returns a `SupervisorRef`

## Commit Hashes

| Task | Commit |
|---|---|
| Task 1 — ChildSpec.supervisor() + SupervisorChildSpec + createChildRunner support | `228c827` |
| Task 2 — NestedSupervisionTest (2 tests) | `b19969c` |

## Test Results

```
Tests run: 52, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

52 tests total (50 existing + 2 new). All green.

## Decisions Made / Deviations from Plan

1. **Corrected escalation-test await and assertions** — The plan's test body used `system.lookup("child-sup").isEmpty()` as the await predicate and final assertion. After reviewing cleanup() semantics: the parent supervisor's STOP decision does not call `unregisterActor` on the child — only the child-sup's own `cleanup()` unregisters its children (grandchild). child-sup itself is only unregistered by the parent's cleanup(), which runs on `system.shutdown()`. So during the test, child-sup is still in `actors` but dead. The await was changed to:
   ```java
   await().atMost(10, TimeUnit.SECONDS).until(() ->
       system.lookup("child-sup").map(ref -> !ref.isAlive()).orElse(false));
   ```
   And the assertion was changed to:
   ```java
   assertThat(system.lookup("child-sup").map(ActorRef::isAlive)).contains(false);
   ```
   This matches the plan's "Important details" guidance exactly.

2. **No other deviations** — `SupervisorChildSpec` is package-private as designed; crash propagation uses the existing `crashListener` machinery without any new wiring.
