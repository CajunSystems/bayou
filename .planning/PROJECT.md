# Bayou — Supervision

Add Erlang/Akka-style supervisor trees to Bayou so actors can embrace the "let it crash" mindset. When an actor fails, its supervisor decides whether to restart it, restart all siblings, or escalate the failure up the tree.

## Goals

- Supervisor tree hierarchy with parent-child relationships
- One-for-one and all-for-one restart strategies
- Death spiral protection: max restarts within a time window; escalate to parent when exceeded
- Clean, ergonomic API — supervision is opt-in, existing code unchanged
- No new external dependencies; backward-compatible with all existing `spawn()` calls

## Non-Goals

- Remote supervision (cross-JVM or network)
- Persisting the supervisor tree itself to the Gumbo log
- (Deferred, not excluded: dynamic child add/remove at runtime, death watch / monitoring)

## Requirements

### Validated

- ✓ Three actor types: Stateless, Stateful, EventSourced — existing
- ✓ Virtual thread per actor with sequential message processing — existing
- ✓ `BayouSystem` factory: `spawn()`, `spawnStateful()`, `spawnEventSourced()` — existing
- ✓ `ActorRef<M>` public API: `tell()`, `ask()`, `stop()`, `isAlive()` — existing
- ✓ `BayouContext` in handlers: logger, system reference, reply — existing
- ✓ Gumbo SharedLog integration: event log + snapshot persistence — existing
- ✓ State recovery on restart: Stateful actors restore latest snapshot; EventSourced actors replay full event log — existing
- ✓ Graceful shutdown: `BayouSystem.shutdown()` drains all actor mailboxes — existing
- ✓ Pluggable serialization via `BayouSerializer<T>` — existing

### Active

- [ ] **Supervisor actor type** — an actor that owns child actors and applies a restart strategy when a child crashes
- [ ] **One-for-one strategy** — only the crashed child is restarted; siblings are unaffected
- [ ] **All-for-one strategy** — all children of the supervisor are restarted when any one crashes
- [ ] **Death spiral guard** — configurable max-restarts-within-window (e.g. 5 crashes in 60s); when exceeded, supervisor stops trying and escalates the failure to its own parent
- [ ] **Ergonomic spawn API** — supervisors declare their children at definition time; spawning a supervisor automatically registers and starts its children
- [ ] **Backward compatibility** — existing `BayouSystem.spawn*()` calls continue to work unchanged; supervision is purely opt-in
- [ ] **Crash notification path** — when a child crashes, the runner notifies its supervisor rather than dying silently
- [ ] **Restart state semantics** — on restart, Stateful actors recover from last snapshot; EventSourced actors replay their event log; Stateless actors start fresh

### Out of Scope

- Remote supervision — cross-JVM/network actor supervision (deferred to distributed milestone)
- Persistent supervisor state — writing the supervision tree itself to Gumbo (adds complexity, unclear value)

## Key Decisions

| Decision | Rationale | Outcome |
|---|---|---|
| Supervisor tree (not just restart policy config) | Matches Erlang/Akka mental model; fault isolation lives in tree structure, not magic config | Chosen |
| One-for-one + all-for-one in v1 | Covers 95% of use cases; rest-for-one deferred | Chosen |
| Max restarts + time window for spiral guard | Industry standard (Erlang OTP default); prevents infinite crash loops while tolerating transient failures | Chosen |
| Backward-compatible, opt-in API | Existing Bayou users must not be broken; supervision is additive | Chosen |
| No new external dependencies | Keep library footprint minimal; Java 21 stdlib is sufficient | Chosen |
| Dynamic child management deferred | Adds significant complexity; static tree covers v1 use cases | Deferred |
| Death watch deferred | Useful but independent of supervision tree; separate feature | Deferred |

## Constraints

- Java 21, Maven, no new compile-scope dependencies
- Must not break existing `spawn()`, `spawnStateful()`, `spawnEventSourced()` signatures
- All actor types (Stateless, Stateful, EventSourced) must be supervisable

---
*Last updated: 2026-04-13 after initialization*
