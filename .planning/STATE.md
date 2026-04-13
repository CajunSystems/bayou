# Project State

## Current Phase

**Phase 4: Restart Mechanics** — planned, ready to execute

## Phase Status

| Phase | Status | Notes |
|---|---|---|
| 1 — Crash Signal Infrastructure | complete | 01-01-SUMMARY.md |
| 2 — Supervision Strategy Model | complete | 02-01-SUMMARY.md |
| 3 — Supervisor Actor | complete | 03-01-SUMMARY.md |
| 4 — Restart Mechanics | planned | 1 plan: 04-01-PLAN.md |
| 5 — Death Spiral Guard | not started | |
| 6 — Testing & Polish | not started | |

## Last Action

Phase 4 planned — 2026-04-13

## Accumulated Decisions

- `ChildCrash` carries the runner reference — supervisors need it for restart in Phase 4
- `Consumer<ChildCrash>` used for listener (no custom interface needed)
- Crash signal fires after `cleanup()` and `stopFuture.complete()` — child fully stopped first
- `processEnvelope()` still swallows handler exceptions — "let it crash" from handler deferred to Phase 3/4
- `RestartDecision` has 4 values: RESTART, RESTART_ALL, STOP, ESCALATE
- `SupervisionStrategy` is `@FunctionalInterface` — supports lambda custom strategies
- Strategy `decide()` always returns constant for now — Phase 5 adds spiral counting
- `RestartWindow` stored in strategy constructor — signature stable for Phase 5
- `SupervisorRunner extends AbstractActorRunner<ChildCrash>` — mailbox typed to crash signals
- `SupervisorRef extends ActorRef<Void>` — supervisors not user-messageable; stored in BayouSystem.actors
- `ChildSpec` sealed interface with 3 package-private record impls — factory methods only
- Phase 3 `processEnvelope()` calls `strategy.decide()` + logs only — Phase 4 adds restart action
- `context.system()` used in SupervisorRunner to access BayouSystem (avoids extra field)
- `SupervisorRunner.createChildRunner()` uses unchecked casts with `@SuppressWarnings` — safe because type params are consistent within each spec object
- `restart()` uses `running.set(true)` + fresh `stopFuture` + new virtual thread — no state cleared, mailbox preserved
- `running.set(false)` added to crash catch block — fixes `isAlive()` returning `true` after crash
- `stopFuture` is `private volatile` (not final) — allows `restart()` to replace it
- ESCALATE in Phase 4 is a no-op stub (log warning, treat as STOP) — Phase 5 implements actual escalation

## Active Plan

`.planning/phases/04-restart-mechanics/04-01-PLAN.md`

Run `/gsd:execute-plan 4` to begin execution.
