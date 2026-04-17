# Phase 11, Plan 1: GenStateMachine / FSM — SUMMARY

## Status: Complete

## Commits

- `30c9d70` — feat(11-01): StateMachineActor — FSM behavior with transition, onEnter/onExit, spawnStateMachine
- `7de5a6e` — test(11-01): StateMachineTest — initial onEnter, transition order, ignored msg, full cycle, postStop

## What Was Built

**New files:**
- `actor/StateMachineActor.java` — interface with `transition(S, M, BayouContext<M>)`, `onEnter`, `onExit`, `preStart`, `postStop`, `onError`, `onSignal` all with default no-ops except `transition`
- `StateMachineActorRunner.java` — extends `AbstractActorRunner<M>`; tracks `currentState`; fires `onEnter` for initial state in `initialize()`; fires `onExit`+`onEnter` on transitions; swallows callback exceptions via `onError`
- `StateMachineTest.java` — 5 test cases

**Modified files:**
- `BayouSystem.java` — `spawnStateMachine(id, actor, initialState)` and `...MailboxConfig` overloads; `StateMachineActor` import

## Key Design Decisions

- `onEnter` fires for initial state in `initialize()` before `preStart` and before any messages — matches Erlang `gen_statem` entry callback semantics
- Callback exceptions (`onEnter`, `onExit` throws) are swallowed via `onError` — the actor does NOT crash on callback errors; state update still applies
- No `currentState()` on `BayouContext<M>` — state is always passed as the first parameter to every `StateMachineActor` method; avoids polluting shared context interface
- `Optional.empty()` from `transition` = stay in current state (no callbacks fire)
- `Optional.of(sameState)` from `transition` = equal check via `.equals()` means NO transition fires when the same state is returned (idempotent)

## Test Results

82/82 tests passing (77 pre-existing + 5 new StateMachineTest):
- `onEnter_calledForInitialState` ✓
- `validTransition_changesState_firesCallbacksInOrder` ✓
- `ignoredMessage_staysInState_noCallbacks` ✓
- `fullCycle_trafficLight` ✓
- `postStop_calledOnGracefulStop` ✓
