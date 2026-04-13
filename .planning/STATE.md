# Project State

## Current Phase

**Phase 5: Death Spiral Guard** — not started

## Phase Status

| Phase | Status | Notes |
|---|---|---|
| 1 — Crash Signal Infrastructure | complete | 01-01-SUMMARY.md |
| 2 — Supervision Strategy Model | complete | 02-01-SUMMARY.md |
| 3 — Supervisor Actor | complete | 03-01-SUMMARY.md |
| 4 — Restart Mechanics | complete | 04-01-SUMMARY.md |
| 5 — Death Spiral Guard | planned | 05-01-PLAN.md |
| 6 — Testing & Polish | not started | |

## Last Action

Phase 4 complete — 2026-04-13

## Accumulated Decisions

- `ChildCrash` carries the runner reference — used in Phase 4 restart: `crash.runner().restart()`
- `Consumer<ChildCrash>` used for listener (no custom interface needed)
- Crash signal fires after `cleanup()` and `stopFuture.complete()` — child fully stopped first
- `processEnvelope()` still swallows handler exceptions — "let it crash" from handler deferred
- `RestartDecision` has 4 values: RESTART, RESTART_ALL, STOP, ESCALATE
- `SupervisionStrategy` is `@FunctionalInterface` — supports lambda custom strategies
- Strategy `decide()` always returns constant for now — Phase 5 adds spiral counting
- `RestartWindow` stored in strategy constructor — signature stable for Phase 5
- `SupervisorRunner extends AbstractActorRunner<ChildCrash>` — mailbox typed to crash signals
- `SupervisorRef extends ActorRef<Void>` — supervisors not user-messageable; stored in BayouSystem.actors
- `ChildSpec` sealed interface with 3 impls — factory methods only; `StatefulChildSpec` is public with `.snapshotInterval(int)` builder method
- `SupervisorActor.children()` is a `default` returning `List.of()` — dynamic-only supervisors only implement `strategy()`
- `SupervisorRef.spawnChild(ChildSpec)` — dynamic child addition at runtime; thread-safe via `CopyOnWriteArrayList`
- `AbstractActorRunner.stopFuture` is `volatile` (not final) — allows `restart()` to replace it
- `running.set(false)` in crash catch block — fixes `isAlive()` after crash; enables natural RESTART_ALL sibling filter
- `restart()` sets `running=true`, fresh `stopFuture`, new virtual thread — mailbox preserved
- ESCALATE is a no-op stub in Phase 4 — Phase 5 implements actual propagation
- `SupervisorRunner.childRunners` is `CopyOnWriteArrayList` — thread-safe for `spawnChild` from any thread

## Active Plan

Phase 5, Plan 1 — `05-01-PLAN.md` (3 tasks, ready to execute)
