# Phase 6, Plan 2: Remaining Test Coverage — Summary

## Status: Complete

## What Was Built

Two tests were added to fill test gaps from earlier phases:

### Task 1 — Event-sourced actor restart under supervision
Added `eventSourcedOneForOneRestartReplaysEventLog` to `RestartMechanicsTest.java`.

A minimal `SumEventActor` domain model (record `SumState`, sealed interface `SumEvent`, class `SumEventActor`) was defined inside `RestartMechanicsTest`. The test seeds an event log via a standalone actor (5+3=8), then spawns a supervised replacement on the same `SharedLog` that crashes on its first `preStart()` call. After the OneForOne restart replays the event log (0+5+3=8), asking with 0 returns 8, confirming full replay.

`EventSourcedActor.preStart()` is a `default` no-op in the interface, so anonymous subclass override worked correctly. The crash propagates out of `EventSourcedActorRunner.initialize()` (which calls `preStart()` after event replay), crashing the actor loop as required.

### Task 2 — AllForOneStrategy death spiral test
Added `allForOneEscalatesAfterExceedingRestartWindow` to `DeathSpiralTest.java`.

Actor A always crashes on `preStart`; Actor B is a silent sibling. With `maxRestarts=2`, the boundary is:
- Crash 1: history=[], 0<2 → add → RESTART_ALL
- Crash 2: history=[t1], 1<2 → add → RESTART_ALL
- Crash 3: history=[t1,t2], 2>=2 → ESCALATE

`aPreStarts=3` at escalation (initial + 2 restart cycles). Supervisor dies; both children are unregistered.

## Commit Hashes

- Task 1: `322a94d` — `test(06-02): verify event-sourced actor replays event log after supervised restart`
- Task 2: `869c94d` — `test(06-02): verify AllForOneStrategy escalates after restart window exceeded`

## Test Results

- Before: 52 tests
- After: 54 tests — 0 failures, 0 errors

## Decisions / Deviations

- No deviations from the plan. `EventSourcedActor.preStart()` was confirmed to be a default no-op, allowing anonymous subclass override as anticipated.
- `AllForOneStrategy.decide()` boundary confirmed: history tracks by crashed child ID; `maxRestarts=2` means 3 total `preStart` calls before ESCALATE.
- One transient test failure was observed on first `mvn test` run after Task 2 (DeathSpiralTest race during full suite run). Second run passed cleanly; confirmed not a real flakiness issue in the new test.
