# Roadmap — Bayou Supervision

Add Erlang/Akka-style supervisor trees to Bayou. Six phases taking the system from silent actor death to a full "let it crash" supervision hierarchy.

## Phases

### Phase 1: Crash Signal Infrastructure
**Goal:** Runners signal their supervisor when they crash rather than dying silently.

- Add nullable `supervisorRef` field to `AbstractActorRunner`
- Modify `loop()` to catch fatal unhandled exceptions and fire a `ChildCrash` signal to the parent
- Define `ChildCrash` internal record (actorId, cause, runner reference)
- Ensure existing runners without a parent continue to behave exactly as before (no-op path)

**Done when:** A crashing actor's virtual thread death is observable by a parent reference rather than only visible in the log.

---

### Phase 2: Supervision Strategy Model
**Goal:** Public API types that represent supervision decisions — no behavior yet, just the vocabulary.

- `SupervisionStrategy` interface with `RestartDecision decide(String childId, Throwable cause)`
- `RestartDecision` enum: `RESTART`, `STOP`, `ESCALATE`
- `RestartWindow` value type: max restart count + duration (e.g. 5 restarts in 60s)
- `OneForOneStrategy` built-in implementation
- `AllForOneStrategy` built-in implementation
- All types public; no dependencies outside stdlib

**Done when:** A user can write `new OneForOneStrategy(new RestartWindow(5, Duration.ofSeconds(60)))` and get a strategy object.

---

### Phase 3: Supervisor Actor
**Goal:** A concrete supervisor type that holds children and reacts to crashes.

- `SupervisorActor` interface (user-facing): declares children + chooses strategy
- `SupervisorRunner` (package-private): owns child runners, receives `ChildCrash` signals, dispatches to strategy
- `BayouSystem.spawnSupervisor(id, supervisorActor)` factory method — spawns supervisor and its declared children
- `SupervisorRef` (or reuse `ActorRef<Void>`) as the handle returned to the caller
- Children are registered in `BayouSystem.actors` as normal; supervisor is their parent

**Done when:** `system.spawnSupervisor(...)` returns a ref; the supervisor's virtual thread starts alongside its children's threads.

---

### Phase 4: Restart Mechanics
**Goal:** Supervisors can actually restart children after receiving a crash signal.

- `restart(runner)` utility: calls `cleanup()`, re-runs `initialize()`, starts a new virtual thread
- Stateless restart: fresh start (no state to restore)
- Stateful restart: re-reads latest snapshot from Gumbo log
- EventSourced restart: replays full event log from Gumbo
- One-for-one: restart only the crashed child
- All-for-one: stop all siblings first, then restart all children in declaration order

**Done when:** A supervised stateful actor that crashes comes back online with its last snapshot restored, verified by test.

---

### Phase 5: Death Spiral Guard
**Goal:** Supervisors stop retrying after too many crashes and escalate up the tree.

- Per-child restart timestamp ring buffer (bounded by max-restarts count)
- After each restart: check if restart count within window >= max → if yes, trigger escalation
- Escalation: supervisor treats itself as crashed, fires its own `ChildCrash` signal to *its* parent
- Top-level supervisor (no parent): log critical error, stop the supervisor and all children gracefully
- `RestartWindow(max, duration)` drives the check; a window of `(Integer.MAX_VALUE, Duration.ZERO)` means unlimited

**Done when:** A child that crashes 6 times in 10s (with window set to 5/10s) causes the supervisor to escalate rather than restart again.

---

### Phase 6: Testing & Polish
**Goal:** Full test coverage, documentation, and cleanup.

- `SupervisionTest`: one-for-one strategy — verify only crashed child restarts
- `SupervisionTest`: all-for-one strategy — verify all siblings restart
- `SupervisionTest`: death spiral — verify escalation when window exceeded
- `SupervisionTest`: nested supervisors — verify crash propagates correctly up a two-level tree
- `SupervisionTest`: backward compatibility — existing spawn*() tests still pass
- `SupervisionTest`: restart state semantics — stateful/event-sourced actors resume correctly
- Javadoc on all new public types
- README section on supervision

**Done when:** All tests green; `mvn verify` passes; existing tests unmodified and passing.

---

## Phase Order Rationale

Phases are sequentially dependent:
1. Crash signals must exist before anything can react to them
2. Strategy model must be defined before a supervisor can use it
3. Supervisor actor type builds on both signals and strategies
4. Restart mechanics require a working supervisor to trigger them
5. Death spiral guard requires working restart mechanics to count restarts
6. Tests validate the complete, integrated system

## Out of Scope (this roadmap)

- Remote supervision
- Persistent supervisor tree in Gumbo
- Dynamic child add/remove at runtime
- Death watch (non-supervisor lifecycle monitoring)
