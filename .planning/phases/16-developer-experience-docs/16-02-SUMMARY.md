# Phase 16, Plan 2: Persistent PubSub + Supervision + Patterns + README — Summary

## Status: Complete

## Tasks Completed

### Task 1 — docs/persistent-pubsub.md + docs/supervision.md
- Commit: 2e63304
- persistent-pubsub.md: BayouPubSub vs BayouTopic comparison table, all 3 subscription modes (live/durable/replay), serialization guide, decision grid
- supervision.md: let-it-crash philosophy, quick-start 3-child example, OneForOne/AllForOne comparison, restart window and death-spiral guard, custom strategy lambda, dynamic children via spawnChild, nested supervisors with escalation propagation, decision tree table

### Task 2 — docs/patterns.md + README overhaul
- Commit: f17335f
- patterns.md: 6 patterns with complete runnable examples — request-reply (ask/reply/CompletableFuture), fan-out (direct tell loop + BayouPubSub variants), work queue (round-robin router), circuit breaker (StateMachineActor CLOSED/OPEN/HALF_OPEN), pipeline (3-stage parse→validate→persist), saga with compensating actions
- README.md: 534 lines → 91 lines landing page; all detailed reference content moved to guides; Documentation table links all 6 guides

## Deviations
None

## Verification
- mvn test: 104 tests, 0 failures, 0 errors, BUILD SUCCESS (no code changes; compile verified)
- ls docs/: persistent-pubsub.md, supervision.md, patterns.md present
- wc -l README.md: 91 lines (≤ 130 target)
