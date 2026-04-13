# Phase 6, Plan 3: README Documentation — Summary

## Status

Complete

## What Was Done

Added a `## Supervision` section to `README.md` covering the full supervision API. The section
includes 7 topics with code examples, matching the existing README's imperative, example-first style:

1. **Overview** — "let it crash" philosophy; supervisors monitor children and apply strategies
2. **Basic supervisor** — `spawnSupervisor()`, `SupervisorActor`, `ChildSpec` factory methods (stateless, stateful, eventSourced), `SupervisionStrategy` choice
3. **Supervision strategies** — `OneForOneStrategy` vs `AllForOneStrategy` with a comparison table
4. **Restart window and death spiral guard** — `RestartWindow(maxRestarts, within)` and `RestartWindow.UNLIMITED`; escalation behaviour when window is exceeded
5. **Custom strategies and RestartDecision** — `SupervisionStrategy` as a functional interface with lambda example; table of all four `RestartDecision` values
6. **Dynamic child spawning** — `SupervisorRef.spawnChild(ChildSpec)` at runtime; `children()` default to empty list
7. **Nested supervisors** — `ChildSpec.supervisor()` for multi-level trees; escalation propagation
8. **Stateful and event-sourced restart semantics** — snapshot/event-log restore on restart

## Commit

`13e4717c8f37409bc360b5f4df02c05f00141cca` — `docs(06-03): add supervision section to README`

## Test Count

54 tests, all passing (unchanged — documentation-only change).
