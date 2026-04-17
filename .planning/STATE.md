# Project State

## Current Phase

**Phase 8: Death Watch & Linking** — not started

## Phase Status

| Phase | Status | Notes |
|---|---|---|
| 1 — Crash Signal Infrastructure | complete | 01-01-SUMMARY.md |
| 2 — Supervision Strategy Model | complete | 02-01-SUMMARY.md |
| 3 — Supervisor Actor | complete | 03-01-SUMMARY.md |
| 4 — Restart Mechanics | complete | 04-01-SUMMARY.md |
| 5 — Death Spiral Guard | complete | 05-01-SUMMARY.md |
| 6 — Testing & Polish | complete | 06-01-SUMMARY.md, 06-02-SUMMARY.md, 06-03-SUMMARY.md |
| 7 — Timer Messages | complete | 07-01-SUMMARY.md |
| 8 — Death Watch & Linking | not started | |
| 9 — Back-pressure | not started | |
| 10 — PubSub / Process Groups | not started | |
| 11 — GenStateMachine / FSM | not started | |

## Last Action

Phase 7 complete — 2026-04-17

## Accumulated Decisions

- `BayouContext<M>` is now generic — enables type-safe `scheduleOnce(Duration, M)` / `schedulePeriodic(Duration, M)` without casts; lambda actors unaffected
- Timer delivery via `runner.tell(message)` directly — bypasses `Ref` layer; context holds `AbstractActorRunner<M> runner` field set in constructor via `setRunner(this)`
- Active timers tracked in `AbstractActorRunner.activeTimers` (ConcurrentHashMap key set); cancelled in `finally` block before `cleanup()` on actor stop
- `ScheduledExecutorService` in `BayouSystem` uses single-threaded platform daemon thread — more reliable for timing than virtual threads
- One-shot timers self-remove from `activeTimers` after firing to prevent set growth

- `ChildCrash` carries the runner reference — used in Phase 4 restart: `crash.runner().restart()`
- `Consumer<ChildCrash>` used for listener (no custom interface needed)
- Crash signal fires after `cleanup()` and `stopFuture.complete()` — child fully stopped first
- `processEnvelope()` still swallows handler exceptions — "let it crash" from handler deferred
- `RestartDecision` has 4 values: RESTART, RESTART_ALL, STOP, ESCALATE
- `SupervisionStrategy` is `@FunctionalInterface` — supports lambda custom strategies
- `RestartWindow` stored in strategy constructor — signature stable for Phase 5
- `SupervisorRunner extends AbstractActorRunner<ChildCrash>` — mailbox typed to crash signals
- `SupervisorRef extends ActorRef<Void>` — supervisors not user-messageable; stored in BayouSystem.actors
- `ChildSpec` sealed interface with 3 impls — factory methods only; `StatefulChildSpec` is public with `.snapshotInterval(int)` builder method
- `SupervisorActor.children()` is a `default` returning `List.of()` — dynamic-only supervisors only implement `strategy()`
- `SupervisorRef.spawnChild(ChildSpec)` — dynamic child addition at runtime; thread-safe via `CopyOnWriteArrayList`
- `AbstractActorRunner.stopFuture` is `volatile` (not final) — allows `restart()` to replace it
- `running.set(false)` in crash catch block — fixes `isAlive()` after crash; enables natural RESTART_ALL sibling filter
- `restart()` sets `running=true`, fresh `stopFuture`, new virtual thread — mailbox preserved
- `SupervisorRunner.childRunners` is `CopyOnWriteArrayList` — thread-safe for `spawnChild` from any thread
- `EscalationException extends RuntimeException` — thrown from `escalate()`, caught by existing `catch (Exception e)` in `loop()`, no changes to loop needed
- `escalate()` on `AbstractActorRunner` — checks `crashListener == null` to detect top-level (log critical); always throws EscalationException
- `cleanup()` unregisters children from `BayouSystem.actors` + clears `childRunners` — enables clean restart by parent
- `BayouSystem.unregisterActor()` — `actors.remove(actorId)`; safe from cleanup() thread
- Strategy `restartHistory` is `HashMap` (not ConcurrentHashMap) — `decide()` is called only from the supervisor's own virtual thread
- `SupervisorChildSpec` sealed record — 4th ChildSpec variant; `ChildSpec.supervisor()` factory creates nested supervisor specs
- `startAndRegister()` checks `instanceof SupervisorRunner` to register `SupervisorRef` vs `ActorRef` — ensures `system.lookup("child-sup")` returns a castable `SupervisorRef`
- Nested supervisor crash propagation uses existing `crashListener` machinery — no new wiring needed; escalation from child supervisor fires `ChildCrash` to parent supervisor's mailbox

## Active Plan

Phase 8, Plan 1: Signal Infrastructure & Death Watch — `08-01-PLAN.md`
