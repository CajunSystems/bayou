# Summary — Phase 2, Plan 1: Supervision Strategy Model

## Status: Complete

## What Was Built

Five new public types establishing the vocabulary for supervision decisions. No wiring to `BayouSystem` yet — these are the stable types that Phase 3 consumes.

### `RestartDecision.java` (new)
Enum with four values:
- `RESTART` — restart only the crashed child (one-for-one)
- `RESTART_ALL` — restart all children (all-for-one)
- `STOP` — stop the crashed child permanently
- `ESCALATE` — propagate the failure up the tree

`RESTART_ALL` was added beyond the original roadmap spec so the directive fully encodes the action, letting `SupervisorRunner` (Phase 3) switch on the enum without `instanceof` checks.

### `RestartWindow.java` (new)
Public record: `maxRestarts` + `within` (a `Duration`). Validated on construction (no negative max, no null duration). `UNLIMITED` constant (`Integer.MAX_VALUE`, `Duration.ZERO`) disables the guard. Stored by both strategy implementations so the constructor signature is stable when Phase 5 adds counting logic.

### `SupervisionStrategy.java` (new)
`@FunctionalInterface` with single method `decide(String childId, Throwable cause) → RestartDecision`. Lambda-friendly for custom per-exception routing.

### `OneForOneStrategy.java` (new)
Stores `RestartWindow`; `decide()` always returns `RESTART`. Phase 5 will add the spiral-guard counting inside this class.

### `AllForOneStrategy.java` (new)
Stores `RestartWindow`; `decide()` always returns `RESTART_ALL`. Phase 5 will add the spiral-guard counting inside this class.

### `StrategyModelTest.java` (new, 11 tests)
Pure unit tests — no Gumbo, no async. Covers: `RestartWindow` storage + validation, `UNLIMITED` constant, both strategy `decide()` returns, null-argument rejection, and the lambda custom strategy pattern.

## Commits

| Hash | Task | Description |
|---|---|---|
| `feb4b13` | Task 1 | `feat(02-01): add RestartDecision enum and RestartWindow record` |
| `eecd96d` | Task 2 | `feat(02-01): add SupervisionStrategy interface and OneForOne/AllForOne implementations` |
| `b12ae1d` | Task 3 | `test(02-01): verify RestartWindow, OneForOneStrategy, AllForOneStrategy, and lambda strategies` |

## Test Results

```
StrategyModelTest:     11 tests — 0 failures
CrashSignalTest:        2 tests — 0 failures
EventSourcedActorTest:  6 tests — 0 failures
StatelessActorTest:     6 tests — 0 failures
InterActorTest:         7 tests — 0 failures
StatefulActorTest:      6 tests — 0 failures
────────────────────────────────────────────
Total:                 38 tests — BUILD SUCCESS
```

## Decisions Made

- `RESTART_ALL` added to enum (beyond roadmap spec) — avoids `instanceof` in `SupervisorRunner`
- `SupervisionStrategy` made `@FunctionalInterface` — supports lambda custom strategies
- Strategies stateless for now (always return constant) — Phase 5 adds restart-count tracking
- `RestartWindow` stored in strategy constructor now — constructor signature stays stable in Phase 5

## Deviations

None — executed exactly as planned. `RESTART_ALL` addition was planned in the Phase 2 plan (not a runtime discovery).
