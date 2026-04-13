# Project State

## Current Phase

**Phase 2: Supervision Strategy Model** — planned, ready to execute

## Phase Status

| Phase | Status | Notes |
|---|---|---|
| 1 — Crash Signal Infrastructure | complete | 01-01-SUMMARY.md |
| 2 — Supervision Strategy Model | planned | 1 plan: 02-01-PLAN.md |
| 3 — Supervisor Actor | not started | |
| 4 — Restart Mechanics | not started | |
| 5 — Death Spiral Guard | not started | |
| 6 — Testing & Polish | not started | |

## Last Action

Phase 2 planned — 2026-04-13

## Accumulated Decisions

- `ChildCrash` carries the runner reference — supervisors need it for restart in Phase 4
- `Consumer<ChildCrash>` used for listener (no custom interface needed)
- Crash signal fires after `cleanup()` and `stopFuture.complete()` — child fully stopped first
- `processEnvelope()` still swallows handler exceptions — "let it crash" from handler deferred to Phase 3/4
- `RestartDecision` has 4 values: RESTART, RESTART_ALL, STOP, ESCALATE (RESTART_ALL added for all-for-one clarity)
- `SupervisionStrategy` is `@FunctionalInterface` — supports lambda custom strategies
- Strategy `decide()` always returns constant for now — Phase 5 adds spiral counting

## Active Plan

`.planning/phases/02-supervision-strategy-model/02-01-PLAN.md`

Run `/gsd:execute-plan 2` to begin execution.
